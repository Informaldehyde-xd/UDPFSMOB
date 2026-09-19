/* Ported from https://github.com/pcm720/udpfsd (BSD-3-Clause), udpfs/handlers.go.
 * Parses each UDPFS request payload, calls the backend, sends the reply. */
package com.ps2manager.udpfsserver.udpfs

import com.ps2manager.udpfsserver.FileLogger

class UdpfsHandlers(
    private val conn: UdpfsConnection,
    private val backend: UdpfsBackend
) {
    companion object {
        private const val TAG = "UdpfsHandlers"
    }

    fun handlePayload(payload: ByteArray) {
        if (payload.isEmpty()) return
        val msgType = payload[0].toInt() and 0xFF
        if (conn.verbose) FileLogger.i(TAG, "[${conn.peerAddr}]: dispatching msgType=0x${msgType.toString(16)} (${UdpfsMsg.name(msgType)}) payloadLen=${payload.size} hex=${FileLogger.hex(payload)}")
        when (msgType) {
            UdpfsMsg.OPEN_REQ -> handleOpen(payload)
            UdpfsMsg.CLOSE_REQ -> handleClose(payload)
            UdpfsMsg.READ_REQ -> handleRead(payload)
            UdpfsMsg.WRITE_REQ -> handleWriteReq(payload)
            UdpfsMsg.WRITE_DATA -> handleWriteData(payload)
            UdpfsMsg.LSEEK_REQ -> handleLseek(payload)
            UdpfsMsg.DREAD_REQ -> handleDread(payload)
            UdpfsMsg.GETSTAT_REQ -> handleGetstat(payload)
            UdpfsMsg.MKDIR_REQ -> handleMkdir(payload)
            UdpfsMsg.REMOVE_REQ -> handleRemove(payload)
            UdpfsMsg.RMDIR_REQ -> handleRmdir(payload)
            UdpfsMsg.BREAD_REQ -> handleBread(payload)
            UdpfsMsg.BWRITE_REQ -> handleBwriteReq(payload)
            else -> {
                FileLogger.w(TAG, "[${conn.peerAddr}]: unknown message type: 0x${msgType.toString(16)}")
                conn.sendAck(true)
            }
        }
    }

    private fun readCString(payload: ByteArray, start: Int): String {
        var end = start
        while (end < payload.size && payload[end] != 0.toByte()) end++
        return String(payload, start, end - start, Charsets.ISO_8859_1)
    }
    private fun u16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
        ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)
    private fun i32(b: ByteArray, off: Int): Int = u32(b, off).toInt()

    private fun handleOpen(payload: ByteArray) {
        if (payload.size < 8) { conn.sendOpenReply(-Errno.EINVAL, StatInfo()); return }
        val flags = u16(payload, 2)
        val path = readCString(payload, 8)
        val isDir = payload.size > 1 && payload[1] != 0.toByte()

        if (!isDir) {
            conn.lookupHandle(path, flags)?.let { h ->
                if (conn.verbose) FileLogger.d(TAG, "[${conn.peerAddr}]: reusing file handle $h for $path")
                conn.sendOpenReply(h, StatInfo())
                return
            }
        }

        try {
            val result = backend.open(path, flags, isDir)
            conn.addHandle(result.handle, path, flags, isDir)
            conn.sendOpenReply(result.handle, result.stat)
        } catch (e: Exception) {
            FileLogger.w(TAG, "[${conn.peerAddr}]: OPEN failed for '$path' (flags=$flags isDir=$isDir)", e)
            conn.sendOpenReply(-errorToErrno(e), StatInfo())
        }
    }

    private fun handleClose(payload: ByteArray) {
        if (payload.size < 8) { conn.sendCloseReply(-Errno.EINVAL); return }
        val handle = i32(payload, 4)
        if (handle == BLOCK_DEVICE_HANDLE) { conn.sendCloseReply(handle); return }
        try {
            backend.close(handle)
            conn.removeHandle(handle)
            conn.sendCloseReply(0)
        } catch (e: Exception) {
            conn.sendCloseReply(-errorToErrno(e))
        }
    }

    private fun handleRead(payload: ByteArray) {
        if (payload.size < 12) { conn.sendReadResult(-Errno.EINVAL, null); return }
        val handle = i32(payload, 4)
        val size = u32(payload, 8).toInt()
        try {
            val result = backend.read(handle, size, conn.dataBuffer)
            conn.sendReadResult(result.n, result.data)
        } catch (e: Exception) {
            FileLogger.w(TAG, "[${conn.peerAddr}]: READ failed handle=$handle size=$size", e)
            conn.sendReadResult(-errorToErrno(e), null)
        }
    }

    private fun handleWriteReq(payload: ByteArray) {
        if (payload.size < 12) { conn.sendAck(true); return }
        val handle = i32(payload, 4)
        try {
            backend.writeStart(handle)
        } catch (e: Exception) {
            conn.sendWriteDone(-errorToErrno(e)); return
        }
        conn.setWriteHandle(handle)
        if (payload.size <= 12) { conn.sendAck(true); return }
        val chunkPayload = payload.copyOfRange(12, payload.size)
        if (chunkPayload.size < 8) { conn.sendAck(true); return }
        processChunk(handle, chunkPayload)
    }

    private fun handleWriteData(payload: ByteArray) {
        if (payload.size < 8) { conn.sendAck(true); return }
        val handle = conn.getWriteHandle()
        if (handle == -1) { conn.sendAck(true); return }
        processChunk(handle, payload)
    }

    private fun processChunk(handle: Int, chunkPayload: ByteArray) {
        val chunkNr = u16(chunkPayload, 2)
        val chunkSize = u16(chunkPayload, 4)
        val totalChunks = u16(chunkPayload, 6)
        var chunkData = chunkPayload.copyOfRange(8, chunkPayload.size)
        if (chunkSize < chunkData.size) chunkData = chunkData.copyOfRange(0, chunkSize)

        try {
            val done = backend.writeChunk(handle, chunkNr, chunkSize, totalChunks, chunkData)
            if (done) {
                val n = backend.completeWrite(handle)
                conn.sendWriteDone(n)
            } else {
                conn.sendAck(true)
            }
        } catch (e: Exception) {
            conn.sendWriteDone(-errorToErrno(e))
        }
    }

    private fun handleLseek(payload: ByteArray) {
        if (payload.size < 16) { conn.sendLseekReply(-1); return }
        val handle = i32(payload, 4)
        val offsetLo = u32(payload, 8)
        val offsetHi = u32(payload, 12)
        val offset = (offsetHi shl 32) or (offsetLo and 0xFFFFFFFFL)
        val whence = payload[1].toInt() and 0xFF
        try {
            conn.sendLseekReply(backend.lseek(handle, offset, whence))
        } catch (e: Exception) {
            FileLogger.w(TAG, "[${conn.peerAddr}]: LSEEK failed handle=$handle offset=$offset whence=$whence", e)
            conn.sendLseekReply(-1)
        }
    }

    private fun handleDread(payload: ByteArray) {
        if (payload.size < 8) { conn.sendDreadReply(-Errno.EINVAL, "", StatInfo()); return }
        val handle = i32(payload, 4)
        try {
            val entry = backend.dread(handle)
            if (entry == null) conn.sendDreadReply(0, "", StatInfo())
            else conn.sendDreadReply(1, entry.name, entry.stat)
        } catch (e: Exception) {
            FileLogger.w(TAG, "[${conn.peerAddr}]: DREAD failed handle=$handle", e)
            conn.sendDreadReply(-errorToErrno(e), "", StatInfo())
        }
    }

    private fun handleGetstat(payload: ByteArray) {
        if (payload.size < 4) { conn.sendGetstatReply(-Errno.EINVAL, StatInfo()); return }
        val path = readCString(payload, 4)
        try {
            conn.sendGetstatReply(0, backend.getstat(path))
        } catch (e: Exception) {
            FileLogger.w(TAG, "[${conn.peerAddr}]: GETSTAT failed for '$path'", e)
            conn.sendGetstatReply(-errorToErrno(e), StatInfo())
        }
    }

    private fun handleMkdir(payload: ByteArray) {
        if (payload.size < 4) { conn.sendResultReplyOnly(-Errno.EINVAL); return }
        try { backend.mkdir(readCString(payload, 4)); conn.sendResultReplyOnly(0) }
        catch (e: Exception) { conn.sendResultReplyOnly(-errorToErrno(e)) }
    }

    private fun handleRemove(payload: ByteArray) {
        if (payload.size < 4) { conn.sendResultReplyOnly(-Errno.EINVAL); return }
        try { backend.remove(readCString(payload, 4)); conn.sendResultReplyOnly(0) }
        catch (e: Exception) { conn.sendResultReplyOnly(-errorToErrno(e)) }
    }

    private fun handleRmdir(payload: ByteArray) {
        if (payload.size < 4) { conn.sendResultReplyOnly(-Errno.EINVAL); return }
        try { backend.rmdir(readCString(payload, 4)); conn.sendResultReplyOnly(0) }
        catch (e: Exception) { conn.sendResultReplyOnly(-errorToErrno(e)) }
    }

    private fun handleBread(payload: ByteArray) {
        if (payload.size < 16) { conn.sendReadResult(-Errno.EINVAL, null); return }
        val sectorCount = u16(payload, 2)
        val handle = i32(payload, 4)
        val sectorNr = (u32(payload, 12) shl 32) or (u32(payload, 8) and 0xFFFFFFFFL)
        try {
            val data = backend.bread(handle, sectorNr, sectorCount, conn.dataBuffer)
            if (conn.verbose) FileLogger.d(TAG, "[${conn.peerAddr}]: BREAD handle=$handle sectorNr=$sectorNr sectorCount=$sectorCount -> ${data.size} bytes")
            conn.sendReadResult(data.size, data)
        } catch (e: Exception) {
            FileLogger.w(TAG, "[${conn.peerAddr}]: BREAD handle=$handle sectorNr=$sectorNr sectorCount=$sectorCount failed", e)
            conn.sendReadResult(-errorToErrno(e), null)
        }
    }

    private fun handleBwriteReq(payload: ByteArray) {
        if (payload.size < 16) { conn.sendAck(true); return }
        val sectorCount = u16(payload, 2)
        val handle = i32(payload, 4)
        val sectorNr = (u32(payload, 12) shl 32) or (u32(payload, 8) and 0xFFFFFFFFL)
        try {
            backend.bwriteStart(handle, sectorNr, sectorCount)
        } catch (e: Exception) {
            conn.sendWriteDone(-errorToErrno(e)); return
        }
        conn.setWriteHandle(handle)
        if (payload.size <= 16) { conn.sendAck(true); return }
        val chunkPayload = payload.copyOfRange(16, payload.size)
        if (chunkPayload.size < 8) { conn.sendAck(true); return }
        processChunk(handle, chunkPayload)
    }
}
/* Ported from https://github.com/pcm720/udpfsd (BSD-3-Clause), udpfs/connection.go.
 * Wraps a UdpRdmaSession + backend, encodes/sends UDPFS replies, and tracks
 * per-peer file handles so a PS2 reset can be recovered without losing them. */
package com.ps2manager.udpfsserver.udpfs

import com.ps2manager.udpfsserver.udprdma.UdpRdmaSession
import java.net.InetSocketAddress
import java.util.concurrent.locks.ReentrantLock

class UdpfsConnection(
    val peerAddr: InetSocketAddress,
    private val session: UdpRdmaSession,
    private val backend: UdpfsBackend,
    val verbose: Boolean = false
) {
    private class CachedHandle(val path: String, val flag: Int, var reset: Boolean, val isDir: Boolean)

    private val lock = ReentrantLock()
    private val usedHandles = HashMap<Int, CachedHandle>()
    private var activeWriteHandle = -1
    val dataBuffer = ByteArray(128 * 1024)

    init {
        session.resetCallback = { resetPeer() }
    }

    fun processIncoming(data: ByteArray): ByteArray? = session.processDataPacket(data, data.size)

    /** Resyncs the session on a DISCOVERY packet from this peer — see
     *  UdpRdmaSession.onDiscovery. */
    fun onDiscovery(discoverySeqNr: Int) = session.onDiscovery(discoverySeqNr)

    fun sendAck(ack: Boolean) = session.sendAck(ack)
    fun sendOpenReply(handle: Int, st: StatInfo) = session.sendData(UdpfsPacking.packOpenReply(handle, st))
    fun sendReadResult(result: Int, data: ByteArray?) =
        session.sendRawDataWithHeader(UdpfsPacking.packResultReply(result), data ?: ByteArray(0))
    fun sendWriteDone(result: Int) {
        clearWriteHandle()
        session.sendData(UdpfsPacking.packWriteDone(result))
    }
    fun sendLseekReply(position: Long) = session.sendData(UdpfsPacking.packLseekReply(position))
    fun sendDreadReply(result: Int, name: String, st: StatInfo) =
        session.sendData(UdpfsPacking.packDreadReply(result, name, st))
    fun sendGetstatReply(result: Int, st: StatInfo) = session.sendData(UdpfsPacking.packGetstatReply(result, st))
    fun sendCloseReply(result: Int) = session.sendData(UdpfsPacking.packCloseReply(result))
    fun sendResultReplyOnly(result: Int) = session.sendData(UdpfsPacking.packResultReply(result))

    fun lookupHandle(path: String, flag: Int): Int? {
        lock.lock()
        try {
            for ((fsHandle, h) in usedHandles) {
                if (h.reset && h.path == path && h.flag == flag) {
                    h.reset = false
                    return fsHandle
                }
            }
            return null
        } finally { lock.unlock() }
    }

    fun addHandle(handle: Int, path: String, flag: Int, isDir: Boolean) {
        lock.lock()
        try { usedHandles[handle] = CachedHandle(path, flag, false, isDir) } finally { lock.unlock() }
    }

    fun removeHandle(handle: Int) {
        lock.lock()
        try {
            usedHandles.remove(handle)
            if (activeWriteHandle == handle) activeWriteHandle = -1
        } finally { lock.unlock() }
    }

    fun resetPeer() {
        lock.lock()
        try {
            for ((fsHandle, h) in usedHandles) {
                try { backend.lseek(fsHandle, 0, 0) } catch (_: Exception) { }
                h.reset = true
            }
        } finally { lock.unlock() }
    }

    fun close() {
        lock.lock()
        try {
            for (fsHandle in usedHandles.keys.toList()) {
                try { backend.close(fsHandle) } catch (_: Exception) { }
            }
            usedHandles.clear()
        } finally { lock.unlock() }
        session.close()
    }

    fun getWriteHandle(): Int {
        lock.lock()
        try { return activeWriteHandle } finally { lock.unlock() }
    }
    fun setWriteHandle(handle: Int) {
        lock.lock()
        try { activeWriteHandle = handle } finally { lock.unlock() }
    }
    fun clearWriteHandle() {
        lock.lock()
        try { activeWriteHandle = -1 } finally { lock.unlock() }
    }
}
/* Real filesystem backend — serves UDPFS requests against a folder on the
 * phone's storage using java.io, the same approach as SMBOPL's OplDiskDriver. */
package com.ps2manager.udpfsserver.udpfs

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AndroidFsBackend(private val rootDir: File) : UdpfsBackend {

    companion object {
        // PS2 optical media (CD and DVD alike) use 2048-byte logical sectors —
        // the standard ISO9660 sector size BREAD/BWRITE address by.
        // Matches the reference server exactly: BREAD/BWRITE against a
        // regular file handle (anything other than the reserved block-device
        // handle 0, which this share-folder backend never uses) addresses
        // 512-byte sectors — NOT the 2048-byte CD/DVD sector size. Using
        // 2048 here silently served data from 4x the wrong file offset,
        // in 4x the wrong quantity, for every BREAD request.
        private const val SECTOR_SIZE = 512L
    }

    private sealed class Handle {
        class RegularFile(val raf: RandomAccessFile, var writeState: WriteState? = null) : Handle()
        class Directory(val entries: MutableList<File>, var index: Int = 0) : Handle()
    }
    private class WriteState(var chunksReceived: Int = 0, var totalChunks: Int = 0)

    private val handles = ConcurrentHashMap<Int, Handle>()
    private val nextHandle = AtomicInteger(1)

    private fun resolve(path: String): File {
        val cleanPath = path.trimStart('/', '\\')
        val target = File(rootDir, cleanPath).canonicalFile
        val rootCanonical = rootDir.canonicalFile
        if (!target.path.startsWith(rootCanonical.path)) {
            throw UdpfsErrno(Errno.EACCES)
        }
        return target
    }

    override fun open(path: String, flags: Int, isDir: Boolean): OpenResult {
        val file = resolve(path)

        if (isDir) {
            if (!file.exists() || !file.isDirectory) throw UdpfsErrno(Errno.ENOENT)
            val entries = file.listFiles()?.toMutableList() ?: mutableListOf()
            val h = nextHandle.getAndIncrement()
            handles[h] = Handle.Directory(entries)
            return OpenResult(h, statFor(file))
        }

        val create = (flags and UdpfsFlag.CREATE) != 0
        val truncate = (flags and UdpfsFlag.TRUNCATE) != 0
        val writable = (flags and 0x03) != UdpfsFlag.READ_ONLY

        if (!file.exists()) {
            if (!create) throw UdpfsErrno(Errno.ENOENT)
            file.parentFile?.mkdirs()
            file.createNewFile()
        }

        val mode = if (writable) "rw" else "r"
        val raf = RandomAccessFile(file, mode)
        if (truncate && writable) raf.setLength(0)
        if ((flags and UdpfsFlag.APPEND) != 0) raf.seek(raf.length())

        val h = nextHandle.getAndIncrement()
        handles[h] = Handle.RegularFile(raf)
        return OpenResult(h, statFor(file))
    }

    override fun close(handle: Int) {
        when (val h = handles.remove(handle)) {
            is Handle.RegularFile -> h.raf.close()
            is Handle.Directory -> { }
            null -> throw UdpfsErrno(Errno.EBADF)
        }
    }

    override fun read(handle: Int, size: Int, readBuffer: ByteArray): ReadResult {
        val h = handles[handle] as? Handle.RegularFile ?: throw UdpfsErrno(Errno.EBADF)
        val toRead = minOf(size, readBuffer.size)
        val n = h.raf.read(readBuffer, 0, toRead)
        if (n <= 0) return ReadResult(0, ByteArray(0))
        return ReadResult(n, readBuffer.copyOf(n))
    }

    override fun writeStart(handle: Int) {
        val h = handles[handle] as? Handle.RegularFile ?: throw UdpfsErrno(Errno.EBADF)
        h.writeState = WriteState()
    }

    override fun writeChunk(handle: Int, chunkNr: Int, chunkSize: Int, totalChunks: Int, chunk: ByteArray): Boolean {
        val h = handles[handle] as? Handle.RegularFile ?: throw UdpfsErrno(Errno.EBADF)
        val ws = h.writeState ?: throw UdpfsErrno(Errno.EINVAL)
        h.raf.write(chunk)
        ws.chunksReceived++
        ws.totalChunks = totalChunks
        return ws.chunksReceived >= totalChunks
    }

    override fun completeWrite(handle: Int): Int {
        val h = handles[handle] as? Handle.RegularFile ?: throw UdpfsErrno(Errno.EBADF)
        val written = h.writeState?.chunksReceived ?: 0
        h.writeState = null
        return written
    }

    override fun lseek(handle: Int, offset: Long, whence: Int): Long {
        val h = handles[handle] as? Handle.RegularFile ?: throw UdpfsErrno(Errno.EBADF)
        val newPos = when (whence) {
            0 -> offset
            1 -> h.raf.filePointer + offset
            2 -> h.raf.length() + offset
            else -> throw UdpfsErrno(Errno.EINVAL)
        }
        if (newPos < 0) throw UdpfsErrno(Errno.EINVAL)
        h.raf.seek(newPos)
        return newPos
    }

    override fun dread(handle: Int): DreadEntry? {
        val h = handles[handle] as? Handle.Directory ?: throw UdpfsErrno(Errno.EBADF)
        if (h.index >= h.entries.size) return null
        val f = h.entries[h.index]
        h.index++
        return DreadEntry(f.name, statFor(f))
    }

    override fun getstat(path: String): StatInfo {
        val file = resolve(path)
        if (!file.exists()) throw UdpfsErrno(Errno.ENOENT)
        return statFor(file)
    }

    override fun mkdir(path: String) {
        val file = resolve(path)
        if (file.exists()) throw UdpfsErrno(Errno.EEXIST)
        if (!file.mkdirs()) throw UdpfsErrno(Errno.EIO)
    }

    override fun remove(path: String) {
        val file = resolve(path)
        if (!file.exists()) throw UdpfsErrno(Errno.ENOENT)
        if (!file.delete()) throw UdpfsErrno(Errno.EIO)
    }

    override fun rmdir(path: String) {
        val file = resolve(path)
        if (!file.exists() || !file.isDirectory) throw UdpfsErrno(Errno.ENOENT)
        if (!file.delete()) throw UdpfsErrno(Errno.EIO)
    }

    override fun bread(handle: Int, sectorNr: Long, sectorCount: Int, readBuffer: ByteArray): ByteArray {
        val h = handles[handle] as? Handle.RegularFile ?: throw UdpfsErrno(Errno.EBADF)
        val offset = sectorNr * SECTOR_SIZE
        val requested = sectorCount.toLong() * SECTOR_SIZE
        val remaining = h.raf.length() - offset
        if (remaining <= 0) return ByteArray(0)
        val toRead = minOf(requested, remaining, readBuffer.size.toLong()).toInt()
        h.raf.seek(offset)
        val n = h.raf.read(readBuffer, 0, toRead)
        if (n <= 0) return ByteArray(0)
        return readBuffer.copyOf(n)
    }

    override fun bwriteStart(handle: Int, sectorNr: Long, sectorCount: Int) {
        val h = handles[handle] as? Handle.RegularFile ?: throw UdpfsErrno(Errno.EBADF)
        h.raf.seek(sectorNr * SECTOR_SIZE)
        h.writeState = WriteState()
    }

    private fun statFor(file: File): StatInfo =
        StatInfo.fromFile(file.isDirectory, if (file.isDirectory) 0L else file.length(), file.lastModified())
}
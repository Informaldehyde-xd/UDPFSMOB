/* Transparent ZSO (ziso) decompression — LZ4-compressed PS2/PSP disc images.
 *
 * Format: 24-byte header, then (numBlocks+1) little-endian uint32 index
 * entries, then block data (each either raw or LZ4-block-compressed).
 * Each index entry's low 31 bits give that block's file offset shifted
 * right by `align`; bit 31 set means the block is stored uncompressed. A
 * block's compressed length is derived from the gap to the next block's
 * offset, and its decompressed length is `blockSize` (or the remainder,
 * for the final block).
 *
 * Decompression happens per-block, on demand, with a small cache — reads
 * from this Android app go over the network in small pieces (UDPFS
 * READ/BREAD requests), so without caching, one on-disk block would get
 * decompressed repeatedly for every small read that lands inside it. */
package com.ps2manager.udpfsserver.udpfs

import java.io.File
import java.io.RandomAccessFile

class ZsoFile(file: File) {
    private val raf = RandomAccessFile(file, "r")
    val totalSize: Long
    private val blockSize: Int
    private val align: Int
    private val index: IntArray // numBlocks+1 entries, raw (with plain-bit still set)

    init {
        val header = ByteArray(24)
        raf.seek(0)
        raf.readFully(header)
        check(isZisoMagic(header)) { "not a ZISO file: ${file.path}" }
        totalSize = leU64(header, 8)
        blockSize = leU32(header, 16).toInt()
        align = header[21].toInt() and 0xFF

        val numBlocks = ((totalSize + blockSize - 1) / blockSize).toInt()
        val idxBytes = ByteArray((numBlocks + 1) * 4)
        raf.seek(24)
        raf.readFully(idxBytes)
        index = IntArray(numBlocks + 1) { i -> leU32(idxBytes, i * 4).toInt() }
    }

    // Small direct-mapped cache of recently decompressed blocks. PS2 reads
    // during boot/gameplay are often near-sequential or revisit the same
    // small region repeatedly (retries, re-reads of a directory sector,
    // etc.) — a handful of slots catches most of that without much memory.
    private val cacheSlots = 4
    private val cacheBlockIdx = IntArray(cacheSlots) { -1 }
    private val cacheData = arrayOfNulls<ByteArray>(cacheSlots)
    private var cacheNext = 0

    /** Reads up to `length` decompressed bytes starting at `position`.
     *  Returns fewer bytes only at EOF, matching RandomAccessFile.read semantics. */
    fun readAt(position: Long, length: Int): ByteArray {
        if (position < 0 || position >= totalSize || length <= 0) return ByteArray(0)
        val toRead = minOf(length.toLong(), totalSize - position).toInt()
        val out = ByteArray(toRead)
        var outPos = 0
        var pos = position
        while (outPos < toRead) {
            val blockIdx = (pos / blockSize).toInt()
            val offsetInBlock = (pos % blockSize).toInt()
            val block = getBlock(blockIdx)
            val n = minOf(block.size - offsetInBlock, toRead - outPos)
            System.arraycopy(block, offsetInBlock, out, outPos, n)
            outPos += n
            pos += n
        }
        return out
    }

    fun close() {
        raf.close()
    }

    private fun getBlock(blockIdx: Int): ByteArray {
        for (i in 0 until cacheSlots) {
            if (cacheBlockIdx[i] == blockIdx) return cacheData[i]!!
        }
        val data = decompressBlock(blockIdx)
        cacheBlockIdx[cacheNext] = blockIdx
        cacheData[cacheNext] = data
        cacheNext = (cacheNext + 1) % cacheSlots
        return data
    }

    private fun decompressBlock(blockIdx: Int): ByteArray {
        val rawStart = index[blockIdx]
        val rawEnd = index[blockIdx + 1]
        val plain = (rawStart and (1 shl 31)) != 0
        val startOff = (rawStart and 0x7FFFFFFF).toLong() shl align
        val endOff = (rawEnd and 0x7FFFFFFF).toLong() shl align
        val compLen = (endOff - startOff).toInt()
        val uncompLen = minOf(blockSize.toLong(), totalSize - blockIdx.toLong() * blockSize).toInt()

        val raw = ByteArray(compLen)
        synchronized(raf) {
            raf.seek(startOff)
            raf.readFully(raw)
        }
        return if (plain) {
            if (raw.size == uncompLen) raw else raw.copyOf(uncompLen)
        } else {
            lz4DecompressBlock(raw, uncompLen)
        }
    }

    companion object {
        fun isZso(file: File): Boolean = file.name.endsWith(".zso", ignoreCase = true)

        /** Lightweight peek at just the decompressed total size, without
         *  parsing the block index or holding the file open — enough for
         *  directory listings and GETSTAT, which only need the size. */
        fun peekTotalBytes(file: File): Long? = try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(24)
                raf.readFully(header)
                if (!isZisoMagic(header)) null else leU64(header, 8)
            }
        } catch (e: Exception) {
            null
        }

        private fun isZisoMagic(header: ByteArray): Boolean =
            header.size >= 4 && header[0] == 'Z'.code.toByte() && header[1] == 'I'.code.toByte() &&
                header[2] == 'S'.code.toByte() && header[3] == 'O'.code.toByte()

        private fun leU32(b: ByteArray, off: Int): Long =
            (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

        private fun leU64(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
            return v
        }

        /** Raw LZ4 block decompression — no frame header, no checksums, just
         *  the token/literal/match sequence format ZSO stores each block in. */
        private fun lz4DecompressBlock(input: ByteArray, outLen: Int): ByteArray {
            val out = ByteArray(outLen)
            var ip = 0
            var op = 0
            val inLen = input.size
            while (ip < inLen && op < outLen) {
                val token = input[ip].toInt() and 0xFF
                ip++
                var literalLen = (token ushr 4) and 0xF
                if (literalLen == 15) {
                    while (ip < inLen) {
                        val b = input[ip].toInt() and 0xFF
                        ip++
                        literalLen += b
                        if (b != 255) break
                    }
                }
                if (literalLen > 0) {
                    System.arraycopy(input, ip, out, op, literalLen)
                    ip += literalLen
                    op += literalLen
                }
                if (ip >= inLen || op >= outLen) break

                val offset = (input[ip].toInt() and 0xFF) or ((input[ip + 1].toInt() and 0xFF) shl 8)
                ip += 2
                var matchLen = token and 0xF
                if (matchLen == 15) {
                    while (ip < inLen) {
                        val b = input[ip].toInt() and 0xFF
                        ip++
                        matchLen += b
                        if (b != 255) break
                    }
                }
                matchLen += 4 // LZ4's minimum match length
                var matchPos = op - offset
                // Byte-by-byte on purpose: matches can overlap the copy source
                // (run-length-style repeats), so array-copy in bulk is unsafe.
                for (i in 0 until matchLen) {
                    out[op] = out[matchPos]
                    op++
                    matchPos++
                }
            }
            return out
        }
    }
}

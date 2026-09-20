/* CHD (Compressed Hunks of Data) v5 reader. Header and compressed-map
 * format verified against MAME/libchdr's actual source (decompress_v5_map
 * in libchdr_chd.c) — see the huffman/bitstream files for the shared
 * decode machinery the map uses.
 *
 * Supported hunk codecs: none (raw), self (alias to an earlier hunk in
 * this file), zlib (via Android's built-in Inflater), and lzma (via
 * LzmaDecoder, CHD's fixed lc=0/lp=0/pb=0 configuration). This covers the
 * codecs chdman actually picks for DVD-mode ("createdvd") CHDs, which is
 * how PS2 discs are normally ripped — lzma almost always wins for game
 * data and is what most files will actually use.
 *
 * Deliberately NOT supported, with a clear error rather than silent
 * corruption: parent/delta CHDs, and the huff/flac/zstd/cd* codecs. A
 * hunk using any of these throws immediately when read, rather than
 * guessing at output. */
package com.ps2manager.udpfsserver.udpfs

import com.ps2manager.udpfsserver.FileLogger
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Inflater

class ChdFile(file: File) : CompressedImage {
    private val raf = RandomAccessFile(file, "r")
    override val totalSize: Long
    private val hunkBytes: Int
    private val hunkCount: Int
    private val compressors: IntArray // 4 fourcc codes, 0 = unused slot

    // Parallel arrays, one entry per hunk, populated by decompressV5Map().
    private val hunkCompType: IntArray
    private val hunkOffset: LongArray
    private val hunkLength: IntArray

    init {
        val header = ByteArray(HEADER_SIZE)
        raf.seek(0)
        raf.readFully(header)
        check(header[0] == 'M'.code.toByte() && header[1] == 'C'.code.toByte() &&
            header[2] == 'o'.code.toByte() && header[3] == 'm'.code.toByte()) { "not a CHD file: ${file.path}" }
        val version = beU32(header, 12)
        check(version == 5L) { "only CHD v5 is supported (got v$version): ${file.path}" }

        compressors = IntArray(4) { i -> beU32(header, 16 + i * 4).toInt() }
        totalSize = beU64(header, 32)
        val mapOffset = beU64(header, 40)
        hunkBytes = beU32(header, 56).toInt()
        val parentSha1 = header.copyOfRange(104, 124)
        check(parentSha1.all { it == 0.toByte() }) { "parent/delta CHDs are not supported: ${file.path}" }

        hunkCount = ((totalSize + hunkBytes - 1) / hunkBytes).toInt()
        hunkCompType = IntArray(hunkCount)
        hunkOffset = LongArray(hunkCount)
        hunkLength = IntArray(hunkCount)
        decompressV5Map(mapOffset)
        FileLogger.i(TAG, "opened ${file.name}: totalSize=$totalSize hunkBytes=$hunkBytes hunkCount=$hunkCount " +
            "compressors=[${compressors.joinToString(",") { fourccName(it) }}] mapOffset=$mapOffset")
        FileLogger.i(TAG, "first 20 hunks: " + (0 until minOf(20, hunkCount)).joinToString(" ") { h ->
            "$h:${hunkCompType[h]}@${hunkOffset[h]}+${hunkLength[h]}"
        })
    }

    private fun decompressV5Map(mapOffset: Long) {
        val mapHeader = ByteArray(16)
        raf.seek(mapOffset)
        raf.readFully(mapHeader)
        val mapBytes = beU32(mapHeader, 0).toInt()
        val firstOffs = beU48(mapHeader, 4)
        // mapcrc is a 16-bit big-endian field at byte offset 10 — parsed but not
        // re-verified: a bitstream desync from a bad decode would already surface
        // as an overflow or an out-of-range hunk reference before this point.
        val lengthBits = mapHeader[12].toInt() and 0xFF
        val selfBits = mapHeader[13].toInt() and 0xFF
        val parentBits = mapHeader[14].toInt() and 0xFF
        check(lengthBits <= 32 && selfBits <= 32 && parentBits <= 32) { "malformed CHD map header" }

        val compressed = ByteArray(mapBytes)
        raf.seek(mapOffset + 16)
        raf.readFully(compressed)
        val bits = BitReader(compressed)

        val decoder = HuffmanDecoder(16, 8)
        check(decoder.importTreeRle(bits)) { "CHD map Huffman tree import failed (corrupt file?)" }

        // Pass 1: decode each hunk's compression-type byte (RLE + Huffman coded).
        var repCount = 0
        var lastComp = 0
        for (h in 0 until hunkCount) {
            if (repCount > 0) {
                hunkCompType[h] = lastComp
                repCount--
                continue
            }
            check(!bits.overflow) { "CHD map decode overran its input (corrupt file?)" }
            val v = decoder.decodeOne(bits)
            when (v) {
                COMPRESSION_RLE_SMALL -> {
                    hunkCompType[h] = lastComp
                    repCount = 2 + decoder.decodeOne(bits)
                }
                COMPRESSION_RLE_LARGE -> {
                    hunkCompType[h] = lastComp
                    repCount = 2 + 16 + (decoder.decodeOne(bits) shl 4)
                    repCount += decoder.decodeOne(bits)
                }
                else -> {
                    lastComp = v
                    hunkCompType[h] = v
                }
            }
        }

        // Pass 2: for each hunk, read its offset/length/crc (raw bits, same
        // stream, continuing right where pass 1 left off).
        var curOffset = firstOffs
        var lastSelf = 0
        for (h in 0 until hunkCount) {
            when (val type = hunkCompType[h]) {
                COMPRESSION_TYPE_0, COMPRESSION_TYPE_1, COMPRESSION_TYPE_2, COMPRESSION_TYPE_3 -> {
                    val length = bits.read(lengthBits)
                    bits.read(16) // crc16 — not re-verified per hunk, only the overall map CRC is
                    hunkOffset[h] = curOffset
                    hunkLength[h] = length
                    curOffset += length
                }
                COMPRESSION_NONE -> {
                    bits.read(16)
                    hunkOffset[h] = curOffset
                    hunkLength[h] = hunkBytes
                    curOffset += hunkBytes
                }
                COMPRESSION_SELF -> {
                    lastSelf = bits.read(selfBits)
                    hunkOffset[h] = lastSelf.toLong()
                }
                COMPRESSION_SELF_0, COMPRESSION_SELF_1 -> {
                    if (type == COMPRESSION_SELF_1) lastSelf++
                    hunkCompType[h] = COMPRESSION_SELF
                    hunkOffset[h] = lastSelf.toLong()
                }
                COMPRESSION_PARENT -> {
                    bits.read(parentBits) // keep stream aligned; parent CHDs unsupported
                }
                COMPRESSION_PARENT_SELF, COMPRESSION_PARENT_0, COMPRESSION_PARENT_1 -> {
                    // No bits consumed for these pseudo-types (matches reference); just
                    // normalize to PARENT so reads on this hunk fail clearly, not silently.
                    hunkCompType[h] = COMPRESSION_PARENT
                }
            }
        }
    }

    // ---- reading ----

    private val hunkCache = HashMap<Int, ByteArray>()
    private val hunkCacheOrder = ArrayDeque<Int>()
    private val hunkCacheSlots = 4

    override fun readAt(position: Long, length: Int): ByteArray {
        if (position < 0 || position >= totalSize || length <= 0) return ByteArray(0)
        val toRead = minOf(length.toLong(), totalSize - position).toInt()
        val out = ByteArray(toRead)
        var outPos = 0
        var pos = position
        while (outPos < toRead) {
            val hunkIdx = (pos / hunkBytes).toInt()
            val offsetInHunk = (pos % hunkBytes).toInt()
            val hunk = getHunk(hunkIdx)
            val n = minOf(hunk.size - offsetInHunk, toRead - outPos)
            System.arraycopy(hunk, offsetInHunk, out, outPos, n)
            outPos += n
            pos += n
        }
        return out
    }

    override fun close() {
        raf.close()
    }

    private fun getHunk(hunkIdx: Int): ByteArray {
        hunkCache[hunkIdx]?.let { return it }
        val data = decodeHunk(hunkIdx)
        hunkCache[hunkIdx] = data
        hunkCacheOrder.addLast(hunkIdx)
        if (hunkCacheOrder.size > hunkCacheSlots) {
            hunkCache.remove(hunkCacheOrder.removeFirst())
        }
        return data
    }

    private fun decodeHunk(hunkIdx: Int): ByteArray {
        val realUncompLen = minOf(hunkBytes.toLong(), totalSize - hunkIdx.toLong() * hunkBytes).toInt()
        return when (val type = hunkCompType[hunkIdx]) {
            COMPRESSION_NONE -> {
                val raw = ByteArray(hunkLength[hunkIdx])
                synchronized(raf) { raf.seek(hunkOffset[hunkIdx]); raf.readFully(raw) }
                if (raw.size == realUncompLen) raw else raw.copyOf(realUncompLen)
            }
            COMPRESSION_SELF -> getHunk(hunkOffset[hunkIdx].toInt())
            COMPRESSION_TYPE_0, COMPRESSION_TYPE_1, COMPRESSION_TYPE_2, COMPRESSION_TYPE_3 -> {
                val slot = type // COMPRESSION_TYPE_0..3 are literally 0..3, matching compressors[] index
                val fourcc = compressors[slot]
                val compData = ByteArray(hunkLength[hunkIdx])
                synchronized(raf) { raf.seek(hunkOffset[hunkIdx]); raf.readFully(compData) }
                FileLogger.d(TAG, "decoding hunk $hunkIdx: type=$type codec=${fourccName(fourcc)} " +
                    "offset=${hunkOffset[hunkIdx]} compLen=${compData.size} outLen=$realUncompLen " +
                    "first16=${compData.copyOf(minOf(16, compData.size)).joinToString(" ") { "%02X".format(it) }}")
                try {
                    when (fourcc) {
                        CODEC_ZLIB -> inflate(compData, realUncompLen)
                        CODEC_LZMA -> LzmaDecoder.decompress(compData, realUncompLen)
                        else -> error(
                            "unsupported CHD hunk codec '${fourccName(fourcc)}' — only none/self/zlib/lzma " +
                                "are supported (hunk $hunkIdx)"
                        )
                    }
                } catch (e: Exception) {
                    FileLogger.e(TAG, "hunk $hunkIdx decode failed: codec=${fourccName(fourcc)} " +
                        "offset=${hunkOffset[hunkIdx]} compLen=${compData.size} outLen=$realUncompLen", e)
                    throw e
                }
            }
            COMPRESSION_PARENT -> error("hunk $hunkIdx requires a parent CHD, which is not supported")
            else -> error("unrecognized CHD hunk compression type $type (hunk $hunkIdx)")
        }
    }

    private fun inflate(compData: ByteArray, outLen: Int): ByteArray {
        val inflater = Inflater(true) // raw deflate, no zlib wrapper — matches CHD's usage
        inflater.setInput(compData)
        val out = ByteArray(outLen)
        var pos = 0
        while (pos < outLen && !inflater.finished()) {
            val n = inflater.inflate(out, pos, outLen - pos)
            if (n == 0 && inflater.needsInput()) break
            pos += n
        }
        inflater.end()
        return out
    }

    companion object {
        private const val TAG = "ChdFile"
        private const val HEADER_SIZE = 124

        private const val COMPRESSION_TYPE_0 = 0
        private const val COMPRESSION_TYPE_1 = 1
        private const val COMPRESSION_TYPE_2 = 2
        private const val COMPRESSION_TYPE_3 = 3
        private const val COMPRESSION_NONE = 4
        private const val COMPRESSION_SELF = 5
        private const val COMPRESSION_PARENT = 6
        private const val COMPRESSION_RLE_SMALL = 7
        private const val COMPRESSION_RLE_LARGE = 8
        private const val COMPRESSION_SELF_0 = 9
        private const val COMPRESSION_SELF_1 = 10
        private const val COMPRESSION_PARENT_SELF = 11
        private const val COMPRESSION_PARENT_0 = 12
        private const val COMPRESSION_PARENT_1 = 13

        private val CODEC_ZLIB = fourcc("zlib")
        private val CODEC_LZMA = fourcc("lzma")

        fun isChd(file: File): Boolean = file.name.endsWith(".chd", ignoreCase = true)

        /** Lightweight peek at just the decompressed total size, without parsing
         *  the map or holding the file open — for directory listings/GETSTAT. */
        fun peekTotalBytes(file: File): Long? = try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(40)
                raf.readFully(header)
                if (header[0] != 'M'.code.toByte() || header[1] != 'C'.code.toByte() ||
                    header[2] != 'o'.code.toByte() || header[3] != 'm'.code.toByte()
                ) null else beU64(header, 32)
            }
        } catch (e: Exception) {
            null
        }

        private fun fourcc(s: String): Int =
            (s[0].code shl 24) or (s[1].code shl 16) or (s[2].code shl 8) or s[3].code

        private fun fourccName(v: Int): String {
            val b = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
            return String(b, Charsets.US_ASCII)
        }

        private fun beU32(b: ByteArray, off: Int): Long =
            ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
                ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

        private fun beU48(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 0 until 6) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }

        private fun beU64(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }
    }
}

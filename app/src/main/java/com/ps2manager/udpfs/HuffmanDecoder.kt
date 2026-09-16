/* Static Huffman decoder, ported to match libchdr's huffman.c (itself
 * MAME/Aaron Giles's code) algorithm exactly: canonical Huffman codes
 * assigned from a list of per-symbol code lengths, which are themselves
 * imported from an RLE-coded bitstream (huffman_import_tree_rle). Decode
 * is via a flat lookup table indexed by the next maxBits bits, matching
 * the reference's O(1)-per-symbol decode approach. */
package com.ps2manager.udpfsserver.udpfs

class HuffmanDecoder(private val numCodes: Int, private val maxBits: Int) {
    // lookup[bits] = (symbol shl 5) or codeLength, matching MAKE_LOOKUP in the reference.
    private val lookup = IntArray(1 shl maxBits)
    private val codeLength = IntArray(numCodes)
    private val codeValue = IntArray(numCodes)

    fun decodeOne(bits: BitReader): Int {
        val peeked = bits.peek(maxBits)
        val entry = lookup[peeked]
        val length = entry and 0x1F
        bits.remove(length)
        return entry ushr 5
    }

    /** Imports a code-length-per-symbol list from an RLE-coded bitstream,
     *  then builds canonical codes and the decode lookup table — mirrors
     *  huffman_import_tree_rle exactly, including its escape/RLE scheme:
     *  a value of 1 is an escape; a second 1 means "literal length 1";
     *  otherwise the next value+3 is a repeat count for the previous length. */
    fun importTreeRle(bits: BitReader): Boolean {
        val numBits = if (maxBits >= 16) 5 else if (maxBits >= 8) 4 else 3

        var curNode = 0
        while (curNode < numCodes) {
            val nodeBits = bits.read(numBits)
            if (nodeBits != 1) {
                codeLength[curNode++] = nodeBits
            } else {
                val second = bits.read(numBits)
                if (second == 1) {
                    codeLength[curNode++] = 1
                } else {
                    val repCount = bits.read(numBits) + 3
                    if (repCount + curNode > numCodes) return false
                    repeat(repCount) { codeLength[curNode++] = second }
                }
            }
        }
        if (curNode != numCodes) return false

        if (!assignCanonicalCodes()) return false
        buildLookupTable()
        return !bits.overflow
    }

    private fun assignCanonicalCodes(): Boolean {
        val bitHisto = IntArray(33)
        for (i in 0 until numCodes) {
            val len = codeLength[i]
            if (len > maxBits) return false
            if (len <= 32) bitHisto[len]++
        }
        var curStart = 0
        for (codeLen in 32 downTo 1) {
            val nextStart = (curStart + bitHisto[codeLen]) ushr 1
            if (codeLen != 1 && nextStart * 2 != (curStart + bitHisto[codeLen])) return false
            bitHisto[codeLen] = curStart
            curStart = nextStart
        }
        for (i in 0 until numCodes) {
            val len = codeLength[i]
            if (len > 0) codeValue[i] = bitHisto[len]++
        }
        return true
    }

    private fun buildLookupTable() {
        val lookupEnd = 1 shl maxBits
        for (code in 0 until numCodes) {
            val len = codeLength[code]
            if (len <= 0) continue
            val value = (code shl 5) or (len and 0x1F)
            val shift = maxBits - len
            val dest = codeValue[code] shl shift
            val destEnd = ((codeValue[code] + 1) shl shift) - 1
            if (dest >= lookupEnd || destEnd >= lookupEnd || destEnd < dest) continue
            for (i in dest..destEnd) lookup[i] = value
        }
    }
}

/* Standard LZMA decompression (range coder + literal/match state machine),
 * specialized for the fixed lc=0, lp=0, pb=0 parameters MAME's CHD codec
 * uses for every hunk. Each call is a fully independent, self-contained
 * LZMA stream — CHD resets LZMA state per hunk (by design: this is what
 * makes random hunk access possible at all for disc emulation), so there
 * is no cross-hunk dictionary/state to carry over.
 *
 * With lc=0, lp=0, pb=0: there's no previous-byte literal context and no
 * position-parity context, so posState and the literal context index are
 * always 0 — this collapses several 2D probability tables down to 1D,
 * which is both correct for CHD specifically and meaningfully simpler to
 * get right than the general lc/lp/pb case.
 *
 * Algorithm structure follows the well-known public-domain LZMA reference
 * decoder (Igor Pavlov / the "LZMA SDK" and the widely-circulated
 * LzmaSpec.cpp walkthrough) directly — this is standard, independent of
 * anything CHD-specific beyond the parameter values themselves. */
package com.ps2manager.udpfsserver.udpfs

object LzmaDecoder {

    private const val NUM_STATES = 12
    private const val NUM_POS_SLOT_BITS = 6
    private const val NUM_ALIGN_BITS = 4
    private const val START_POS_MODEL_INDEX = 4
    private const val END_POS_MODEL_INDEX = 14
    private const val NUM_FULL_DISTANCES = 1 shl (END_POS_MODEL_INDEX / 2) // 128
    private const val MATCH_MIN_LEN = 2

    /** Decompresses a single self-contained CHD-flavored raw LZMA stream
     *  (no .lzma header, no end-of-stream marker — the caller-known [outLen]
     *  is the only way the end is determined, matching how CHD calls it). */
    fun decompress(input: ByteArray, outLen: Int): ByteArray {
        val out = ByteArray(outLen)
        if (outLen == 0) return out
        val rc = RangeDecoder(input)

        var state = 0
        var rep0 = 0
        var rep1 = 0
        var rep2 = 0
        var rep3 = 0

        val isMatch = IntArray(NUM_STATES) { 1024 }
        val isRep = IntArray(NUM_STATES) { 1024 }
        val isRepG0 = IntArray(NUM_STATES) { 1024 }
        val isRepG1 = IntArray(NUM_STATES) { 1024 }
        val isRepG2 = IntArray(NUM_STATES) { 1024 }
        val isRep0Long = IntArray(NUM_STATES) { 1024 }
        // 4 length-class slot decoders, each a 6-bit tree (64 entries; index 0 unused).
        val posSlotDecoders = Array(4) { IntArray(1 shl NUM_POS_SLOT_BITS) { 1024 } }
        // Shared table for posSlot in [START_POS_MODEL_INDEX, END_POS_MODEL_INDEX);
        // sized/addressed exactly as the reference decoder's PosDecoders array.
        val posDecoders = IntArray(1 + NUM_FULL_DISTANCES - END_POS_MODEL_INDEX) { 1024 }
        val alignDecoder = IntArray(1 shl NUM_ALIGN_BITS) { 1024 }
        val literalProbs = IntArray(0x300) { 1024 } // single context: lc=0, lp=0

        val lenChoice = IntArray(2) { 1024 }
        val lenLow = IntArray(1 shl 3) { 1024 }
        val lenMid = IntArray(1 shl 3) { 1024 }
        val lenHigh = IntArray(1 shl 8) { 1024 }
        val repLenChoice = IntArray(2) { 1024 }
        val repLenLow = IntArray(1 shl 3) { 1024 }
        val repLenMid = IntArray(1 shl 3) { 1024 }
        val repLenHigh = IntArray(1 shl 8) { 1024 }

        fun decodeLen(choice: IntArray, low: IntArray, mid: IntArray, high: IntArray): Int {
            if (rc.decodeBit(choice, 0) == 0) {
                return bitTreeDecode(rc, low, 3)
            }
            if (rc.decodeBit(choice, 1) == 0) {
                return 8 + bitTreeDecode(rc, mid, 3)
            }
            return 16 + bitTreeDecode(rc, high, 8)
        }

        var outPos = 0
        while (outPos < outLen) {
            if (rc.decodeBit(isMatch, state) == 0) {
                // --- literal --- (lc=0, lp=0 means no previous-byte context needed)
                var symbol = 1
                if (state >= 7) {
                    // "matched" literal: bias decode against the byte at the rep0 distance
                    var matchByte = out[outPos - rep0 - 1].toInt() and 0xFF
                    do {
                        val matchBit = (matchByte ushr 7) and 1
                        matchByte = (matchByte shl 1) and 0xFF
                        val bit = rc.decodeBit(literalProbs, ((1 + matchBit) shl 8) + symbol)
                        symbol = (symbol shl 1) or bit
                        if (matchBit != bit) break
                    } while (symbol < 0x100)
                }
                while (symbol < 0x100) {
                    symbol = (symbol shl 1) or rc.decodeBit(literalProbs, symbol)
                }
                out[outPos] = (symbol and 0xFF).toByte()
                outPos++
                state = if (state < 4) 0 else if (state < 10) state - 3 else state - 6
                continue
            }

            // --- match or rep-match ---
            var len: Int
            if (rc.decodeBit(isRep, state) != 0) {
                // rep-match
                if (rc.decodeBit(isRepG0, state) == 0) {
                    if (rc.decodeBit(isRep0Long, state) == 0) {
                        // short rep: single byte, no length decode
                        state = if (state < 7) 9 else 11
                        out[outPos] = out[outPos - rep0 - 1]
                        outPos++
                        continue
                    }
                } else {
                    val dist: Int
                    if (rc.decodeBit(isRepG1, state) == 0) {
                        dist = rep1
                    } else {
                        if (rc.decodeBit(isRepG2, state) == 0) {
                            dist = rep2
                        } else {
                            dist = rep3
                            rep3 = rep2
                        }
                        rep2 = rep1
                    }
                    rep1 = rep0
                    rep0 = dist
                }
                len = MATCH_MIN_LEN + decodeLen(repLenChoice, repLenLow, repLenMid, repLenHigh)
                state = if (state < 7) 8 else 11
            } else {
                // new match
                rep3 = rep2; rep2 = rep1; rep1 = rep0
                len = MATCH_MIN_LEN + decodeLen(lenChoice, lenLow, lenMid, lenHigh)
                state = if (state < 7) 7 else 10

                val lenState = minOf(len - MATCH_MIN_LEN, 3)
                val posSlot = bitTreeDecode(rc, posSlotDecoders[lenState], NUM_POS_SLOT_BITS)
                if (posSlot < START_POS_MODEL_INDEX) {
                    rep0 = posSlot
                } else {
                    val numDirectBits = (posSlot ushr 1) - 1
                    rep0 = (2 or (posSlot and 1)) shl numDirectBits
                    if (posSlot < END_POS_MODEL_INDEX) {
                        rep0 += bitTreeReverseDecode(rc, posDecoders, rep0 - posSlot, numDirectBits)
                    } else {
                        rep0 += (rc.decodeDirectBits(numDirectBits - NUM_ALIGN_BITS) shl NUM_ALIGN_BITS).toInt()
                        rep0 += bitTreeReverseDecode(rc, alignDecoder, 0, NUM_ALIGN_BITS)
                    }
                }
            }

            var srcPos = outPos - rep0 - 1
            var remaining = len
            while (remaining > 0 && outPos < outLen) {
                out[outPos] = out[srcPos]
                outPos++
                srcPos++
                remaining--
            }
        }
        return out
    }

    private fun bitTreeDecode(rc: RangeDecoder, probs: IntArray, numBits: Int): Int {
        var m = 1
        for (i in 0 until numBits) m = (m shl 1) + rc.decodeBit(probs, m)
        return m - (1 shl numBits)
    }

    private fun bitTreeReverseDecode(rc: RangeDecoder, probs: IntArray, base: Int, numBits: Int): Int {
        var m = 1
        var symbol = 0
        for (i in 0 until numBits) {
            val bit = rc.decodeBit(probs, base + m)
            m = (m shl 1) + bit
            symbol = symbol or (bit shl i)
        }
        return symbol
    }

    /** LZMA range decoder. Uses Long throughout (masked to 32 bits) instead
     *  of relying on unsigned-integer wraparound tricks, purely so the
     *  arithmetic is easy to verify by inspection. */
    private class RangeDecoder(private val src: ByteArray) {
        private var pos = 0
        private var range: Long = 0xFFFFFFFFL
        private var code: Long = 0L

        init {
            pos++ // first byte is always 0 by construction; skip it
            repeat(4) { code = ((code shl 8) or nextByte()) and 0xFFFFFFFFL }
        }

        private fun nextByte(): Long {
            val b = if (pos < src.size) (src[pos].toLong() and 0xFF) else 0L
            pos++
            return b
        }

        private fun normalize() {
            if (range < 0x1000000L) {
                range = (range shl 8) and 0xFFFFFFFFL
                code = ((code shl 8) or nextByte()) and 0xFFFFFFFFL
            }
        }

        fun decodeBit(probs: IntArray, index: Int): Int {
            val v = probs[index]
            val bound = (range ushr 11) * v
            return if (code < bound) {
                range = bound
                probs[index] = v + ((2048 - v) ushr 5)
                normalize()
                0
            } else {
                code -= bound
                range -= bound
                probs[index] = v - (v ushr 5)
                normalize()
                1
            }
        }

        /** Decodes numBits "bypass" bits with uniform (non-adaptive) probability. */
        fun decodeDirectBits(numBits: Int): Long {
            var result = 0L
            repeat(numBits) {
                range = range ushr 1
                code -= range
                if (code < 0) {
                    code += range
                    result = result shl 1
                } else {
                    result = (result shl 1) or 1L
                }
                normalize()
            }
            return result
        }
    }
}

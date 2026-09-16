/* MSB-first bitstream reader, matching the reference CHD library's
 * bitstream_peek/bitstream_remove/bitstream_read semantics exactly: a
 * 32-bit buffer holding bits left-justified (MSB-aligned), refilled a
 * byte at a time from the source array, zero-padding once the source is
 * exhausted (tracked via [overflow] rather than throwing). */
package com.ps2manager.udpfsserver.udpfs

class BitReader(private val src: ByteArray, private val srcLen: Int = src.size) {
    private var bytePos = 0
    private var buffer: Long = 0L       // top bufBits of this hold valid data, MSB-aligned in a 32-bit field
    private var bufBits = 0
    var overflow = false
        private set

    private fun refill() {
        while (bufBits <= 24) {
            val b = if (bytePos < srcLen) (src[bytePos].toLong() and 0xFF) else run { overflow = true; 0L }
            bytePos++
            buffer = (buffer or (b shl (24 - bufBits))) and 0xFFFFFFFFL
            bufBits += 8
        }
    }

    fun peek(numBits: Int): Int {
        if (numBits == 0) return 0
        refill()
        return (buffer ushr (32 - numBits)).toInt()
    }

    fun remove(numBits: Int) {
        buffer = (buffer shl numBits) and 0xFFFFFFFFL
        bufBits -= numBits
    }

    fun read(numBits: Int): Int {
        val v = peek(numBits)
        remove(numBits)
        return v
    }
}

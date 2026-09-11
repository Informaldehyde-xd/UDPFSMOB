/* Ported from https://github.com/pcm720/udpfsd, udprdma/protocol.go
 * (BSD-3-Clause). Wire format verified byte-for-byte against the real
 * Go source — everything is little-endian, matching the PS2 IOP. */
package com.ps2manager.udpfsserver.udprdma

object UdpRdmaConst {
    const val UDPFS_PORT = 0xF5F6
    const val SERVICE_UDPFS = 0xF5F5

    const val SEND_WINDOW = 8
    const val MAX_DATA_PAYLOAD = 1408
    // Matches the reference server's WINDOW_ACK_TIMEOUT=0.1s / MAX_WINDOW_RETRIES=200
    // exactly: real IOP hardware needs up to ~20s of tolerance waiting for a window
    // ACK, not the few hundred ms this used to allow. Giving up early here was
    // mistaken for a buffer-capacity problem, when it was actually just impatience —
    // the client was still going to ACK, given enough time.
    const val FIN_ACK_TIMEOUT_MS = 100L
    const val WINDOW_ACK_TIMEOUT_MS = 100L
    const val MAX_RETRANSMITS = 200

    const val PACKET_DISCOVERY = 0
    const val PACKET_INFORM = 1
    const val PACKET_DATA = 2

    const val DATA_FLAG_ACK = 1
    const val DATA_FLAG_FIN = 2

    const val HEADER_SIZE = 2
    const val DISC_HEADER_SIZE = 4
    const val DATA_HEADER_SIZE = 4
}

/** 2-byte UDPRDMA base header: 4-bit type, 12-bit seq_nr, packed little-endian. */
data class RdmaHeader(val packetType: Int, val seqNr: Int) {
    fun pack(b: ByteArray, offset: Int = 0) {
        val v = (packetType and 0xF) or ((seqNr and 0xFFF) shl 4)
        b[offset] = (v and 0xFF).toByte()
        b[offset + 1] = ((v shr 8) and 0xFF).toByte()
    }

    companion object {
        fun unpack(b: ByteArray, offset: Int = 0): RdmaHeader {
            val v = (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)
            return RdmaHeader(v and 0xF, (v shr 4) and 0xFFF)
        }
    }
}

/** 4-byte Discovery/Inform header. */
data class RdmaDiscHeader(val serviceId: Int, val reserved: Int) {
    fun pack(b: ByteArray, offset: Int = 0) {
        b[offset] = (serviceId and 0xFF).toByte()
        b[offset + 1] = ((serviceId shr 8) and 0xFF).toByte()
        b[offset + 2] = (reserved and 0xFF).toByte()
        b[offset + 3] = ((reserved shr 8) and 0xFF).toByte()
    }

    companion object {
        fun unpack(b: ByteArray, offset: Int = 0): RdmaDiscHeader {
            val service = (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)
            val reserved = (b[offset + 2].toInt() and 0xFF) or ((b[offset + 3].toInt() and 0xFF) shl 8)
            return RdmaDiscHeader(service, reserved)
        }
    }
}

/** 4-byte DATA packet header: 12-bit seqNrAck, 2-bit flags, 4-bit hdrWordCount,
 *  14-bit dataByteCount, packed little-endian into a 32-bit word. */
data class RdmaDataHeader(
    val seqNrAck: Int,
    val flags: Int,
    val hdrWordCount: Int,
    val dataByteCount: Int
) {
    fun pack(b: ByteArray, offset: Int = 0) {
        val v = (seqNrAck.toLong() and 0xFFF) or
            ((flags.toLong() and 0x3) shl 12) or
            ((hdrWordCount.toLong() and 0xF) shl 14) or
            ((dataByteCount.toLong() and 0x3FFF) shl 18)
        b[offset] = (v and 0xFF).toByte()
        b[offset + 1] = ((v shr 8) and 0xFF).toByte()
        b[offset + 2] = ((v shr 16) and 0xFF).toByte()
        b[offset + 3] = ((v shr 24) and 0xFF).toByte()
    }

    companion object {
        fun unpack(b: ByteArray, offset: Int = 0): RdmaDataHeader {
            val v = (b[offset].toLong() and 0xFF) or
                ((b[offset + 1].toLong() and 0xFF) shl 8) or
                ((b[offset + 2].toLong() and 0xFF) shl 16) or
                ((b[offset + 3].toLong() and 0xFF) shl 24)
            return RdmaDataHeader(
                seqNrAck = (v and 0xFFF).toInt(),
                flags = ((v shr 12) and 0x3).toInt(),
                hdrWordCount = ((v shr 14) and 0xF).toInt(),
                dataByteCount = ((v shr 18) and 0x3FFF).toInt()
            )
        }
    }
}

/** Validates a UDPRDMA DISCOVERY packet and builds the INFORM reply, or null if
 *  invalid or for a different service. */
fun processDiscoveryPacket(data: ByteArray, len: Int, expectedService: Int): ByteArray? {
    if (len < UdpRdmaConst.HEADER_SIZE + UdpRdmaConst.DISC_HEADER_SIZE) return null
    val hdr = RdmaHeader.unpack(data)
    if (hdr.packetType != UdpRdmaConst.PACKET_DISCOVERY) return null
    val disc = RdmaDiscHeader.unpack(data, UdpRdmaConst.HEADER_SIZE)
    if (disc.serviceId != expectedService) return null

    val reply = ByteArray(UdpRdmaConst.HEADER_SIZE + UdpRdmaConst.DISC_HEADER_SIZE)
    RdmaHeader(UdpRdmaConst.PACKET_INFORM, 1).pack(reply)
    RdmaDiscHeader(expectedService, 0).pack(reply, UdpRdmaConst.HEADER_SIZE)
    return reply
}
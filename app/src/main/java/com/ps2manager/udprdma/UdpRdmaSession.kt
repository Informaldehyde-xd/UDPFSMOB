/* Ported from https://github.com/pcm720/udpfsd (BSD-3-Clause):
 * udprdma/session.go, session_tx.go, session_rx.go, acks.go, ack_timer.go.
 * Behavior (ring-buffer Go-Back-N ARQ, flow-controlled send window, FIN/window
 * ACK timeouts with bounded retransmits) mirrors the reference implementation. */
package com.ps2manager.udpfsserver.udprdma

import com.ps2manager.udpfsserver.FileLogger
import java.net.InetSocketAddress
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.ceil

class UdpRdmaSession(
    val peerAddr: InetSocketAddress,
    private val scheduler: ScheduledExecutorService,
    private val writeTo: (InetSocketAddress, ByteArray) -> Unit,
    private val writeBatch: ((InetSocketAddress, List<ByteArray>) -> Unit)? = null,
    // Overridable per-peer, but the default (UdpRdmaConst.SEND_WINDOW=8) matches
    // the reference server exactly and applies uniformly — real Modulo traffic
    // that looked like a small hard buffer cap turned out to be the ACK-wait
    // timeout giving up too early (see MAX_RETRANSMITS), not a window-size issue.
    private val sendWindow: Int = UdpRdmaConst.SEND_WINDOW
) {
    companion object {
        private const val TAG = "UdpRdmaSession"
        private const val RING_SIZE = 2048
        // Modulo shares one running counter across discovery + data packets. When
        // it starts a new logical exchange mid-session (e.g. resuming BREAD access
        // for ISO boot after an idle gap), its counter restarts near 0 rather than
        // continuing from wherever this session's rxSeqExpected had climbed to —
        // and it doesn't always land exactly on 0. Treat any low incoming sequence
        // number below this threshold, while far behind our current expectation,
        // as a restart rather than corruption/reordering.
        private const val RESTART_SEQ_THRESHOLD = 16
    }

    private class TxPacket(var data: ByteArray, var seq: Int)
    private class Transfer {
        var header: ByteArray? = null
        var data: ByteArray? = null
        var offset: Int = -1
        var maxChunk: Int = 0
    }
    private enum class AckWaitMode { NONE, WINDOW, FIN }

    private val lock = ReentrantLock()
    private val txBuffer = Array(RING_SIZE) { TxPacket(ByteArray(0), 0) }
    private var txWriteIndex = 0
    private var txReadIndex = 0
    private val transfer = Transfer()

    private var txSeqNr = 2
    // Sentinel meaning "nothing ACKed yet". Must always be (txSeqNr's starting
    // value - 1) so inFlightLocked()'s modular math is correct — txSeqNr starts
    // at 2 (see comment below), so this is 1, not the more obvious-looking 0xFFF
    // (which would only be correct if txSeqNr started at 0).
    private var txSeqNrAcked = 1
    private var rxSeqExpected = 0
    private var rxSeqInitialized = false

    private var ackFuture: ScheduledFuture<*>? = null
    private var ackTimerExpectSeq = 0
    private var ackTimerSeq = 0
    private var ackWaitMode = AckWaitMode.NONE
    private var retransmitAttempts = 0
    private var finPending = false

    var closed = false
        private set

    var resetCallback: (() -> Unit)? = null

    private fun writeBatchOrLoop(addr: InetSocketAddress, packets: List<ByteArray>) {
        val wb = writeBatch
        if (wb != null) wb(addr, packets) else for (p in packets) writeTo(addr, p)
    }

    fun close() {
        lock.lock()
        try {
            if (closed) return
            closed = true
            stopAckTimer()
        } finally { lock.unlock() }
    }

    fun resetSession() {
        lock.lock()
        try { resetSessionLocked() } finally { lock.unlock() }
    }

    private fun resetSessionLocked() {
        txSeqNr = 2
        txSeqNrAcked = 1
        txReadIndex = 0
        txWriteIndex = 0
        rxSeqExpected = 0
        rxSeqInitialized = false
        stopAckTimer()
        retransmitAttempts = 0
        finPending = false
        clearTransfer()
        resetCallback?.invoke()
    }

    private fun inFlightLocked(): Int {
        if (txReadIndex == txWriteIndex) return 0
        return (txSeqNr - txSeqNrAcked - 1) and 0xFFF
    }

    private fun seqBetween(start: Int, seq: Int, end: Int): Boolean {
        val s = start and 0xFFF; val q = seq and 0xFFF; val e = end and 0xFFF
        return if (s <= e) q in s..e else (q >= s || q <= e)
    }

    private fun clearTransfer() {
        transfer.header = null
        transfer.data = null
        transfer.offset = -1
    }

    private fun resetSendStateLocked() {
        txReadIndex = 0
        txWriteIndex = 0
        stopAckTimer()
        retransmitAttempts = 0
        finPending = false
    }

    // ---- TX ----

    fun sendData(payload: ByteArray) {
        lock.lock()
        try {
            resetSendStateLocked()
            transfer.header = null
            transfer.data = payload
            transfer.offset = 0
            transfer.maxChunk = optimalChunkSize(payload.size)
            handleTransferLocked()
        } finally { lock.unlock() }
    }

    fun sendRawDataWithHeader(header: ByteArray, data: ByteArray) {
        lock.lock()
        try {
            resetSendStateLocked()
            transfer.header = header
            transfer.data = data
            transfer.offset = 0
            transfer.maxChunk = optimalChunkSize(data.size)
            handleTransferLocked()
        } finally { lock.unlock() }
    }

    fun sendAck(ack: Boolean) {
        lock.lock()
        try { sendAckLocked(ack) } finally { lock.unlock() }
    }

    private fun handleTransferLocked() {
        val batch = ArrayList<ByteArray>(sendWindow)
        while (transfer.data != null && inFlightLocked() < sendWindow) {
            val hdr = transfer.header
            if (transfer.offset == 0 && hdr != null && hdr.isNotEmpty()) {
                var firstDataMax = transfer.maxChunk
                if (hdr.size < UdpRdmaConst.MAX_DATA_PAYLOAD) {
                    val room = UdpRdmaConst.MAX_DATA_PAYLOAD - hdr.size
                    if (room < firstDataMax) firstDataMax = room
                }
                val data = transfer.data!!
                var chunkSize = firstDataMax
                if (chunkSize > data.size) chunkSize = data.size
                val fin = chunkSize >= data.size
                batch.add(packDataPacketLocked(hdr, data.copyOfRange(0, chunkSize), fin))
                transfer.offset = chunkSize
                if (fin) clearTransfer()
                continue
            }

            val data = transfer.data ?: break
            if (transfer.offset >= data.size) { clearTransfer(); break }

            var chunkSize = transfer.maxChunk
            if (transfer.offset + chunkSize > data.size) chunkSize = data.size - transfer.offset
            val fin = transfer.offset + chunkSize >= data.size
            val chunk = data.copyOfRange(transfer.offset, transfer.offset + chunkSize)
            transfer.offset += chunkSize
            if (fin) clearTransfer()
            batch.add(packDataPacketLocked(null, chunk, fin))
        }
        if (batch.isNotEmpty()) writeBatchOrLoop(peerAddr, batch)
        updateAckTimerLocked()
    }

    private fun handleAckTimeoutLocked() {
        val waitingFin = finPending
        val waitingWindow = !finPending && transfer.data != null && inFlightLocked() >= sendWindow
        if (!waitingFin && !waitingWindow) { stopAckTimer(); return }

        retransmitAttempts++
        val from = (txSeqNrAcked + 1) and 0xFFF
        val sent = retransmitFromLocked(from)
        if (sent == 0) FileLogger.w(TAG, "[$peerAddr]: ACK retransmit from $from sent 0 packets")

        if (retransmitAttempts < UdpRdmaConst.MAX_RETRANSMITS) {
            FileLogger.d(TAG, "[$peerAddr]: ${if (waitingFin) "FIN" else "window"} ACK timeout, retransmitting from $from")
            armAckTimerLocked()
            return
        }

        if (waitingFin) {
            FileLogger.w(TAG, "[$peerAddr]: final FIN ACK timeout, giving up")
            finPending = false
        } else {
            FileLogger.w(TAG, "[$peerAddr]: final window ACK timeout, aborting transfer")
            clearTransfer()
        }
        stopAckTimer()
        retransmitAttempts = 0
    }

    private fun sendAckLocked(ack: Boolean) {
        val flags = if (ack) UdpRdmaConst.DATA_FLAG_ACK else 0
        val seqAck = if (ack) (rxSeqExpected - 1) and 0xFFF else rxSeqExpected

        val pkt = ByteArray(UdpRdmaConst.HEADER_SIZE + UdpRdmaConst.DATA_HEADER_SIZE)
        RdmaHeader(UdpRdmaConst.PACKET_DATA, txSeqNr).pack(pkt)
        RdmaDataHeader(seqAck, flags, 0, 0).pack(pkt, UdpRdmaConst.HEADER_SIZE)
        writeTo(peerAddr, pkt)
    }

    private fun pruneAckedLocked(seqNrAck: Int) {
        while (txReadIndex != txWriteIndex) {
            val p = txBuffer[txReadIndex]
            if (((p.seq - seqNrAck - 1) and 0xFFF) < RING_SIZE) break
            txReadIndex = (txReadIndex + 1) % txBuffer.size
        }
    }

    private fun retransmitFromLocked(fromSeq: Int): Int {
        val batch = ArrayList<ByteArray>(sendWindow)
        var i = txReadIndex
        while (i != txWriteIndex) {
            val p = txBuffer[i]
            val diff = (p.seq - fromSeq) and 0xFFF
            if (diff >= RING_SIZE) break
            if (p.seq != (fromSeq - 1) and 0xFFF) batch.add(p.data)
            i = (i + 1) % txBuffer.size
        }
        if (batch.isNotEmpty()) writeBatchOrLoop(peerAddr, batch)
        return batch.size
    }

    private fun packDataPacketLocked(header: ByteArray?, data: ByteArray, fin: Boolean): ByteArray {
        val hdrSize = header?.size ?: 0
        val dataSize = data.size
        val padded = (dataSize + 3) and 3.inv()

        var flags = UdpRdmaConst.DATA_FLAG_ACK
        if (fin) flags = flags or UdpRdmaConst.DATA_FLAG_FIN

        val pkt = ByteArray(hdrSize + padded + UdpRdmaConst.HEADER_SIZE + UdpRdmaConst.DATA_HEADER_SIZE)
        RdmaHeader(UdpRdmaConst.PACKET_DATA, txSeqNr).pack(pkt)
        RdmaDataHeader(
            seqNrAck = (rxSeqExpected - 1) and 0xFFF,
            flags = flags,
            hdrWordCount = hdrSize / 4,
            dataByteCount = padded
        ).pack(pkt, UdpRdmaConst.HEADER_SIZE)

        val off = UdpRdmaConst.HEADER_SIZE + UdpRdmaConst.DATA_HEADER_SIZE
        if (header != null) System.arraycopy(header, 0, pkt, off, hdrSize)
        System.arraycopy(data, 0, pkt, off + hdrSize, dataSize)
        // remaining padding bytes stay zero — ByteArray is zero-initialized by default

        txBuffer[txWriteIndex].seq = txSeqNr
        txBuffer[txWriteIndex].data = pkt
        txWriteIndex = (txWriteIndex + 1) % txBuffer.size
        check(txWriteIndex != txReadIndex) { "udprdma: ring buffer is full" }

        txSeqNr = (txSeqNr + 1) and 0xFFF

        if (fin) {
            finPending = true
            retransmitAttempts = 0
        }
        return pkt
    }

    private fun optimalChunkSize(totalBytes: Int): Int {
        var bestChunk = UdpRdmaConst.MAX_DATA_PAYLOAD
        var bestPackets = ceil(totalBytes / UdpRdmaConst.MAX_DATA_PAYLOAD.toDouble()).toInt()
        for (maxChunk in intArrayOf(1024, 1280, UdpRdmaConst.MAX_DATA_PAYLOAD)) {
            val packets = ceil(totalBytes / maxChunk.toDouble()).toInt()
            if (packets < bestPackets) { bestPackets = packets; bestChunk = maxChunk }
        }
        return bestChunk
    }

    // ---- ACK timer ----

    private fun armAckTimerLocked() {
        val fin = finPending
        val delayMs = if (fin) UdpRdmaConst.FIN_ACK_TIMEOUT_MS else UdpRdmaConst.WINDOW_ACK_TIMEOUT_MS
        val mode = if (fin) AckWaitMode.FIN else AckWaitMode.WINDOW
        if (ackWaitMode == mode) return

        ackFuture?.cancel(false)
        ackTimerSeq++
        ackTimerExpectSeq = ackTimerSeq
        ackWaitMode = mode
        val expectSeqAtSchedule = ackTimerSeq
        ackFuture = scheduler.schedule({
            ackTimerFired(expectSeqAtSchedule)
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun stopAckTimer() {
        ackTimerSeq++
        ackWaitMode = AckWaitMode.NONE
        ackFuture?.cancel(false)
        ackFuture = null
    }

    private fun updateAckTimerLocked() {
        if (inFlightLocked() == 0) { stopAckTimer(); return }
        if (finPending || (transfer.data != null && inFlightLocked() >= sendWindow)) {
            armAckTimerLocked()
            return
        }
        stopAckTimer()
    }

    private fun ackTimerFired(expectSeq: Int) {
        lock.lock()
        try {
            if (closed || ackWaitMode == AckWaitMode.NONE ||
                ackTimerExpectSeq != ackTimerSeq || expectSeq != ackTimerSeq
            ) return
            ackWaitMode = AckWaitMode.NONE
            handleAckTimeoutLocked()
        } finally { lock.unlock() }
    }

    // ---- ACK/NACK handling ----

    private fun onAckLocked(seqNrAck: Int) {
        if (txReadIndex == txWriteIndex) return
        val lastSent = (txSeqNr - 1) and 0xFFF
        if (!seqBetween(txSeqNrAcked, seqNrAck, lastSent)) return

        txSeqNrAcked = seqNrAck
        pruneAckedLocked(seqNrAck)
        retransmitAttempts = 0

        if (seqNrAck == lastSent) {
            stopAckTimer()
            finPending = false
            return
        }
        updateAckTimerLocked()
    }

    private fun onPeerAckLocked(seq: Int) {
        onAckLocked(seq)
        if (transfer.data != null) handleTransferLocked()
    }

    private fun onPeerNackLocked(seq: Int) {
        retransmitAttempts = 0
        val sent = retransmitFromLocked(seq)
        if (sent == 0) FileLogger.w(TAG, "[$peerAddr]: NACK retransmit from $seq sent 0 packets")
        updateAckTimerLocked()
    }

    // ---- RX ----

    /** Validates a UDPRDMA DATA packet and returns the payload for the upper layer,
     *  or null if this packet was a control packet (ACK/NACK) or invalid. */
    fun processDataPacket(data: ByteArray, len: Int): ByteArray? {
        lock.lock()
        try {
            if (len < 6) return null
            val hdr = RdmaHeader.unpack(data)
            if (hdr.packetType != UdpRdmaConst.PACKET_DATA) return null
            val header = RdmaDataHeader.unpack(data, 2)

            val payloadOffset = 6
            val payloadAvail = len - payloadOffset
            val hdrSize = header.hdrWordCount * 4
            var payloadSize = hdrSize + header.dataByteCount
            if (payloadSize > payloadAvail) payloadSize = payloadAvail

            val isAck = header.flags and UdpRdmaConst.DATA_FLAG_ACK != 0

            if (payloadSize == 0) {
                val seq = header.seqNrAck
                if (isAck) onPeerAckLocked(seq) else onPeerNackLocked(seq)
                return null
            }

            // Modulo shares one running counter across discovery + data packets,
            // so a new session's first data packet often doesn't start at 0 like
            // a spec-compliant client's would. Sync to whatever the peer sends
            // as the baseline instead of assuming 0, but only for the very first
            // data-bearing packet of this session.
            if (!rxSeqInitialized) {
                rxSeqInitialized = true
                rxSeqExpected = hdr.seqNr
                FileLogger.i(TAG, "[$peerAddr]: first data packet, syncing rxSeqExpected to peer's seq ${hdr.seqNr}")
            }

            if (hdr.seqNr != rxSeqExpected) {
                val prevSeq = (rxSeqExpected - 1) and 0xFFF
                if (hdr.seqNr == prevSeq) {
                    FileLogger.d(TAG, "[$peerAddr]: got previous packet ${hdr.seqNr} (expected $rxSeqExpected), acking")
                    val retransmit = transfer.data != null
                    val retransmitFrom = (txSeqNrAcked + 1) and 0xFFF
                    sendAckLocked(true)
                    if (isAck) onPeerAckLocked(header.seqNrAck)
                    if (retransmit) onPeerNackLocked(retransmitFrom)
                    return null
                }
                if (hdr.seqNr == 0 || (hdr.seqNr < rxSeqExpected && hdr.seqNr <= RESTART_SEQ_THRESHOLD)) {
                    FileLogger.w(TAG, "[$peerAddr]: got unexpectedly low sequence number ${hdr.seqNr} (expected $rxSeqExpected), assuming the peer restarted its counter")
                    resetSessionLocked()
                    rxSeqInitialized = true
                } else {
                    FileLogger.w(TAG, "[$peerAddr]: got unexpected sequence number ${hdr.seqNr} (expected $rxSeqExpected)")
                    sendAckLocked(false)
                    if (isAck) onPeerAckLocked(header.seqNrAck)
                    return null
                }
            }

            rxSeqExpected = (hdr.seqNr + 1) and 0xFFF
            sendAckLocked(true)
            if (isAck) onPeerAckLocked(header.seqNrAck)
            return data.copyOfRange(payloadOffset, payloadOffset + payloadSize)
        } finally { lock.unlock() }
    }
}
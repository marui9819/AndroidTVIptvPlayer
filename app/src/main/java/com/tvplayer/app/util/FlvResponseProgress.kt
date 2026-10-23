package com.tvplayer.app.util

import java.util.zip.CRC32

/** Rejects already delivered cross-response packets using bounded timestamp/size/content history. */
internal class FlvResponseProgress(private val historyLimit: Int = 512) {
    private data class Packet(val timestampMs: Long, val crc: Long, val bytes: Int)
    private val videoHistory = LinkedHashMap<Packet, Long>()
    private val audioHistory = LinkedHashMap<Packet, Long>()
    private var previousVideoPackets: Set<Packet> = emptySet()
    private var previousAudioPackets: Set<Packet> = emptySet()
    private val crc = CRC32()
    private var response = 0L
    private var videoPackets = 0
    private var audioPackets = 0
    private var overlapPackets = 0
    private var overlapAudioPackets = 0
    private var firstVideoMs: Long? = null
    private var lastVideoMs: Long? = null
    private var firstAudioMs: Long? = null
    private var lastAudioMs: Long? = null
    private var firstKeyframeHash: String? = null
    private var firstNewVideoMs: Long? = null
    private var lastNewVideoMs: Long? = null
    private var firstNewAudioMs: Long? = null
    private var lastNewAudioMs: Long? = null

    init { require(historyLimit > 0) }

    /** A progressive retry can clear both sample queues and reopen this object. */
    fun reset() {
        videoHistory.clear()
        audioHistory.clear()
        previousVideoPackets = emptySet()
        previousAudioPackets = emptySet()
        response = 0L
    }

    fun beginResponse() {
        // Comparing must not evict the very tail we're trying to match when
        // a replay starts earlier than the bounded history window.
        previousVideoPackets = videoHistory.keys.toHashSet()
        previousAudioPackets = audioHistory.keys.toHashSet()
        response++
        videoPackets = 0
        audioPackets = 0
        overlapPackets = 0
        overlapAudioPackets = 0
        firstVideoMs = null
        lastVideoMs = null
        firstAudioMs = null
        lastAudioMs = null
        firstKeyframeHash = null
        firstNewVideoMs = null
        lastNewVideoMs = null
        firstNewAudioMs = null
        lastNewAudioMs = null
    }

    /** False only for an identical encoded media packet already sent by an earlier response. */
    fun observe(tag: ByteArray, length: Int = tag.size): Boolean {
        if (length < 16 || tag[0] == 'F'.code.toByte()) return true
        val type = tag[0].toInt() and 0x1f
        val payloadLength = ((tag[1].toInt() and 0xff) shl 16) or
            ((tag[2].toInt() and 0xff) shl 8) or (tag[3].toInt() and 0xff)
        if (payloadLength <= 0 || payloadLength + 15 != length) return true
        val timestampMs = ((tag[7].toLong() and 0xff) shl 24) or
            ((tag[4].toLong() and 0xff) shl 16) or
            ((tag[5].toLong() and 0xff) shl 8) or (tag[6].toLong() and 0xff)
        if (type == 9) {
            // AVC configuration/end markers aren't encoded video frames.
            if (payloadLength <= 5 || (tag[11].toInt() and 0xf) != 7 || tag[12].toInt() != 1) return true
            videoPackets++
            if (firstVideoMs == null) firstVideoMs = timestampMs
            lastVideoMs = timestampMs
            crc.reset()
            crc.update(tag, 11, payloadLength)
            val packet = Packet(timestampMs, crc.value, payloadLength)
            if (firstKeyframeHash == null && (tag[11].toInt() and 0xf0) == 0x10) {
                firstKeyframeHash = java.lang.Long.toHexString(crc.value)
            }
            if (packet in previousVideoPackets) {
                overlapPackets++
                return false
            }
            videoHistory[packet] = response
            while (videoHistory.size > historyLimit) videoHistory.remove(videoHistory.keys.first())
            if (firstNewVideoMs == null) firstNewVideoMs = timestampMs
            lastNewVideoMs = timestampMs
        } else if (type == 8) {
            val codec = (tag[11].toInt() ushr 4) and 0xf
            if (codec == 10 && (payloadLength < 2 || tag[12].toInt() != 1)) return true
            audioPackets++
            if (firstAudioMs == null) firstAudioMs = timestampMs
            lastAudioMs = timestampMs
            crc.reset()
            crc.update(tag, 11, payloadLength)
            val packet = Packet(timestampMs, crc.value, payloadLength)
            if (packet in previousAudioPackets) {
                overlapAudioPackets++
                return false
            }
            audioHistory[packet] = response
            while (audioHistory.size > historyLimit * 4) audioHistory.remove(audioHistory.keys.first())
            if (firstNewAudioMs == null) firstNewAudioMs = timestampMs
            lastNewAudioMs = timestampMs
        }
        return true
    }

    fun snapshot(): FlvResponseProgressSnapshot = FlvResponseProgressSnapshot(
        videoPackets, audioPackets, overlapPackets, firstVideoMs, lastVideoMs,
        firstAudioMs, lastAudioMs, firstKeyframeHash, overlapAudioPackets,
        maxOf(
            ((lastNewVideoMs ?: 0L) - (firstNewVideoMs ?: 0L)).coerceAtLeast(0L),
            ((lastNewAudioMs ?: 0L) - (firstNewAudioMs ?: 0L)).coerceAtLeast(0L)
        )
    )
}

internal data class FlvResponseProgressSnapshot(
    val videoPackets: Int,
    val audioPackets: Int,
    val overlappingVideoPackets: Int,
    val firstVideoTimestampMs: Long?,
    val lastVideoTimestampMs: Long?,
    val firstAudioTimestampMs: Long?,
    val lastAudioTimestampMs: Long?,
    val firstKeyframeHash: String?,
    val overlappingAudioPackets: Int,
    val newMediaDurationMs: Long
) {
    val newVideoPackets: Int get() = videoPackets - overlappingVideoPackets
    val newAudioPackets: Int get() = audioPackets - overlappingAudioPackets
    val mediaDurationMs: Long get() = maxOf(
        ((lastVideoTimestampMs ?: 0L) - (firstVideoTimestampMs ?: 0L)).coerceAtLeast(0L),
        ((lastAudioTimestampMs ?: 0L) - (firstAudioTimestampMs ?: 0L)).coerceAtLeast(0L)
    )
}

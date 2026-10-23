package com.tvplayer.app.util

/**
 * One FLV decode clock for BOTH tracks. Run after raw-packet deduplication and
 * before the tag is exposed to FlvExtractor. Never synthesize an AAC cadence:
 * missing packets, millisecond rounding and normal forward gaps belong to the
 * original mux timeline, not to two independently compressed track clocks.
 *
 * Only a substantial backward clock reset at an HTTP response boundary changes
 * the common offset. AVC composition-time bytes remain untouched, so B-frame
 * presentation reordering is preserved. All state is bounded and loader-owned.
 */
internal class FlvSharedTimeline(private val resetThresholdMs: Long = 1_000L) {
    private var offsetMs = 0L
    private var correctionCount = 0
    private var boundaryPending = true
    private val lastDtsMs = mutableMapOf<Int, Long>()
    private val frameStepsMs = mutableMapOf<Int, Long>()

    fun reset() {
        offsetMs = 0L
        correctionCount = 0
        boundaryPending = true
        lastDtsMs.clear()
        frameStepsMs.clear()
    }

    fun beginResponse() {
        boundaryPending = true
    }

    /** Mutates the four DTS bytes only; configuration and metadata are not media. */
    fun adjust(tag: ByteArray) {
        val type = mediaType(tag) ?: return
        val rawMs = timestampMs(tag)
        var mappedMs = rawMs + offsetMs
        val previousMs = lastDtsMs[type]
        if (boundaryPending) {
            boundaryPending = false
            val referenceMs = previousMs ?: lastDtsMs.values.maxOrNull()
            if (referenceMs != null && mappedMs < referenceMs - resetThresholdMs) {
                val stepMs = frameStepsMs[type] ?: if (type == AUDIO) 23L else 40L
                val correctionMs = referenceMs + stepMs - mappedMs
                offsetMs += correctionMs
                mappedMs += correctionMs
                correctionCount++
            }
        }
        if (previousMs != null) {
            val stepMs = mappedMs - previousMs
            if (stepMs in 1L..100L) frameStepsMs[type] = stepMs
        }
        lastDtsMs[type] = mappedMs
        if (offsetMs != 0L) {
            tag[4] = (mappedMs ushr 16).toByte()
            tag[5] = (mappedMs ushr 8).toByte()
            tag[6] = mappedMs.toByte()
            tag[7] = (mappedMs ushr 24).toByte()
        }
    }

    fun snapshot(): FlvSharedTimelineSnapshot = FlvSharedTimelineSnapshot(
        offsetMs, correctionCount, lastDtsMs[AUDIO], lastDtsMs[VIDEO]
    )

    private fun mediaType(tag: ByteArray): Int? {
        if (tag.size < 16) return null
        val type = tag[0].toInt() and 0x1f
        val size = ((tag[1].toInt() and 0xff) shl 16) or
            ((tag[2].toInt() and 0xff) shl 8) or (tag[3].toInt() and 0xff)
        if (size <= 0 || size + 15 != tag.size) return null
        return when (type) {
            AUDIO -> if ((tag[11].toInt() ushr 4) and 0xf != 10 ||
                (size >= 2 && tag[12].toInt() == 1)
            ) AUDIO else null
            VIDEO -> if (size > 5 && tag[11].toInt() and 0xf == 7 && tag[12].toInt() == 1) VIDEO else null
            else -> null
        }
    }

    private fun timestampMs(tag: ByteArray): Long =
        ((tag[7].toLong() and 0xff) shl 24) or
            ((tag[4].toLong() and 0xff) shl 16) or
            ((tag[5].toLong() and 0xff) shl 8) or (tag[6].toLong() and 0xff)

    private companion object {
        const val AUDIO = 8
        const val VIDEO = 9
    }
}

internal data class FlvSharedTimelineSnapshot(
    val offsetMs: Long,
    val correctionCount: Int,
    val lastAudioDtsMs: Long?,
    val lastVideoDtsMs: Long?
) {
    /** Mux-tail distance, NOT a measurement of audible/displayed lip sync. */
    val audioVideoDtsDeltaMs: Long? get() =
        lastAudioDtsMs?.let { audio -> lastVideoDtsMs?.let { video -> audio - video } }
}

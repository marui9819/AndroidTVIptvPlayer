package com.tvplayer.app.util

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.audio.AudioProcessor
import com.google.android.exoplayer2.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Passes PCM through unchanged while reporting sparse, non-silent sample activity. */
internal class PcmActivityAudioProcessor(
    private val onAudiblePcm: (Long) -> Unit
) : BaseAudioProcessor() {
    private var lastNotificationNanos = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        ) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        if (containsAudibleSamples(inputBuffer, format)) {
            val now = System.nanoTime()
            if (lastNotificationNanos == 0L || now - lastNotificationNanos >= NOTIFICATION_INTERVAL_NANOS) {
                lastNotificationNanos = now
                runCatching { onAudiblePcm(now) }
            }
        }

        val output = replaceOutputBuffer(inputBuffer.remaining())
        output.put(inputBuffer)
        output.flip()
    }

    override fun onFlush() {
        lastNotificationNanos = 0L
    }

    private fun containsAudibleSamples(input: ByteBuffer, format: AudioProcessor.AudioFormat): Boolean {
        val bytesPerSample = when (format.encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return false
        }
        val channelCount = format.channelCount.coerceAtLeast(1)
        val bytesPerFrame = bytesPerSample * channelCount
        if (bytesPerFrame <= 0) return false

        val samples = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val start = samples.position()
        val limit = samples.limit()
        val frameStride = maxOf(1, SAMPLE_STRIDE_BYTES / bytesPerFrame)
        var frame = 0
        while (start + frame * bytesPerFrame + bytesPerFrame <= limit) {
            val frameOffset = start + frame * bytesPerFrame
            for (channel in 0 until channelCount) {
                val sampleOffset = frameOffset + channel * bytesPerSample
                val audible = when (format.encoding) {
                    C.ENCODING_PCM_16BIT ->
                        kotlin.math.abs(samples.getShort(sampleOffset).toInt()) >= PCM_16BIT_THRESHOLD
                    C.ENCODING_PCM_FLOAT ->
                        kotlin.math.abs(samples.getFloat(sampleOffset)) >= PCM_FLOAT_THRESHOLD
                    else -> false
                }
                if (audible) return true
            }
            frame += frameStride
        }
        return false
    }

    private companion object {
        const val PCM_16BIT_THRESHOLD = 64
        const val PCM_FLOAT_THRESHOLD = 0.0005f
        const val SAMPLE_STRIDE_BYTES = 32
        const val NOTIFICATION_INTERVAL_NANOS = 750_000_000L
    }
}

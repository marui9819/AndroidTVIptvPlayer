package com.tvplayer.app.util

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmActivityAudioProcessorTest {

    @Test
    fun reportsNonSilentPcmAndPassesSamplesThroughUnchanged() {
        var activityCount = 0
        val processor = configuredProcessor(C.ENCODING_PCM_16BIT) { activityCount++ }
        val input = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN)
        input.putShort(1_024)
        while (input.hasRemaining()) input.put(0)
        input.flip()
        val original = ByteArray(input.remaining()).also { input.duplicate().get(it) }

        processor.queueInput(input)
        val output = processor.output
        val actual = ByteArray(output.remaining()).also { output.get(it) }

        assertTrue(activityCount > 0)
        assertArrayEquals(original, actual)
    }

    @Test
    fun digitalSilenceDoesNotCountAsAudiblePcm() {
        var activityCount = 0
        val processor = configuredProcessor(C.ENCODING_PCM_16BIT) { activityCount++ }
        val input = ByteBuffer.allocateDirect(4_096)
        while (input.hasRemaining()) input.put(0)
        input.flip()

        processor.queueInput(input)

        assertEquals(0, activityCount)
        assertFalse(processor.output.remaining() == 0)
    }

    @Test
    fun reportsLowLevelFloatAudioWithoutChangingIt() {
        var activityCount = 0
        val processor = configuredProcessor(C.ENCODING_PCM_FLOAT) { activityCount++ }
        val input = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN)
        input.putFloat(0.01f)
        while (input.hasRemaining()) input.put(0)
        input.flip()
        val original = ByteArray(input.remaining()).also { input.duplicate().get(it) }

        processor.queueInput(input)
        val output = processor.output
        val actual = ByteArray(output.remaining()).also { output.get(it) }

        assertTrue(activityCount > 0)
        assertArrayEquals(original, actual)
    }

    private fun configuredProcessor(
        encoding: Int,
        onActivity: (Long) -> Unit
    ): PcmActivityAudioProcessor = PcmActivityAudioProcessor(onActivity).apply {
        configure(AudioProcessor.AudioFormat(48_000, 2, encoding))
        flush()
    }
}

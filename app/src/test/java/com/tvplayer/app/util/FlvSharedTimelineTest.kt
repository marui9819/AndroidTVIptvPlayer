package com.tvplayer.app.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlvSharedTimelineTest {
    @Test
    fun halfSecondSharedGapDoesNotCompressOnlyTheAudio() {
        val timeline = FlvSharedTimeline()
        assertEquals(100_000L, mapped(timeline, 8, 100_000))
        assertEquals(100_000L, mapped(timeline, 9, 100_000))
        // 119 changed these to 100023 and 100500, introducing 477 ms drift.
        assertEquals(100_500L, mapped(timeline, 8, 100_500))
        assertEquals(100_500L, mapped(timeline, 9, 100_500))
        assertEquals(0L, timeline.snapshot().audioVideoDtsDeltaMs)
    }

    @Test
    fun repeatedForwardGapsAndResponseBoundariesCannotAccumulateAvDrift() {
        val timeline = FlvSharedTimeline()
        for (i in 0..20_000) {
            if (i % 50 == 0) timeline.beginResponse()
            val videoMs = i * 500L
            assertEquals(videoMs, mapped(timeline, 9, videoMs))
            assertEquals(videoMs + 12, mapped(timeline, 8, videoMs + 12))
            assertEquals(12L, timeline.snapshot().audioVideoDtsDeltaMs)
        }
        assertEquals(0, timeline.snapshot().correctionCount)
    }

    @Test
    fun normalAacRoundingAndMissingFramesStayOnTheSourceClock() {
        val timeline = FlvSharedTimeline()
        for (time in listOf(0L, 23L, 46L, 70L, 93L, 244L, 488L, 511L)) {
            assertEquals(time, mapped(timeline, 8, time))
        }
        assertEquals(0, timeline.snapshot().correctionCount)
    }

    @Test
    fun equalAudioTimestampsDoNotCreateAnIndependentClock() {
        val timeline = FlvSharedTimeline()
        repeat(100) { assertEquals(1_000L, mapped(timeline, 8, 1_000)) }
        assertEquals(1_000L, mapped(timeline, 9, 1_000))
        assertEquals(0L, timeline.snapshot().audioVideoDtsDeltaMs)
    }

    @Test
    fun clockResetUsesOneOffsetForBothTracksWhenAudioArrivesFirst() {
        val timeline = FlvSharedTimeline()
        mapped(timeline, 8, 100_000)
        mapped(timeline, 9, 100_000)
        mapped(timeline, 8, 100_023)
        mapped(timeline, 9, 100_040)
        timeline.beginResponse()
        val audioMs = mapped(timeline, 8, 0)
        val videoMs = mapped(timeline, 9, 17)
        assertEquals(100_046L, audioMs)
        assertEquals(17L, videoMs - audioMs)
        assertEquals(1, timeline.snapshot().correctionCount)
    }

    @Test
    fun repeatedVideoFirstResetsKeepTheOriginalAvPhase() {
        val timeline = FlvSharedTimeline()
        var previousVideoMs = -1L
        repeat(1_000) {
            timeline.beginResponse()
            for (frame in 0..100) {
                val videoMs = mapped(timeline, 9, frame * 40L)
                val audioMs = mapped(timeline, 8, frame * 40L + 13)
                assertTrue(videoMs > previousVideoMs)
                assertEquals(13L, audioMs - videoMs)
                previousVideoMs = videoMs
            }
        }
        assertEquals(999, timeline.snapshot().correctionCount)
    }

    @Test
    fun backwardBFramePresentationOrderAndCompositionOffsetsAreUnchanged() {
        val timeline = FlvSharedTimeline()
        mapped(timeline, 9, 100_000)
        timeline.beginResponse()
        val presentations = mutableListOf<Long>()
        for ((dts, composition) in listOf(0L to 80, 40L to -40, 80L to -40)) {
            val tag = tag(9, dts, composition)
            val payload = tag.copyOfRange(11, tag.size)
            timeline.adjust(tag)
            assertArrayEquals(payload, tag.copyOfRange(11, tag.size))
            presentations += timestamp(tag) + composition
        }
        assertEquals(listOf(100_120L, 100_040L, 100_080L), presentations)
    }

    @Test
    fun configurationAtZeroCannotChooseTheResetOffset() {
        val timeline = FlvSharedTimeline()
        mapped(timeline, 9, 100_000)
        timeline.beginResponse()
        for (type in listOf(8, 9)) {
            val configuration = tag(type, 0).also { it[12] = 0 }
            val original = configuration.copyOf()
            timeline.adjust(configuration)
            assertArrayEquals(original, configuration)
        }
        assertEquals(100_040L, mapped(timeline, 9, 100_040))
        assertEquals(0, timeline.snapshot().correctionCount)
    }

    @Test
    fun progressiveRetryResetsClockAndDedupHistoryTogether() {
        val timeline = FlvSharedTimeline()
        val progress = FlvResponseProgress()
        progress.beginResponse()
        val old = tag(9, 100_000)
        assertTrue(progress.observe(old))
        timeline.adjust(old)
        timeline.beginResponse()
        mapped(timeline, 9, 0)
        assertEquals(1, timeline.snapshot().correctionCount)
        timeline.reset()
        progress.reset()
        progress.beginResponse()
        assertEquals(0L, mapped(timeline, 9, 0))
        assertTrue(progress.observe(tag(9, 100_000)))
        assertEquals(0L, timeline.snapshot().offsetMs)
    }

    @Test
    fun overlapRemovalHappensBeforeMappingAndPreservesBothTrackTails() {
        val timeline = FlvSharedTimeline()
        val progress = FlvResponseProgress()
        val delivered = mutableListOf<Pair<Int, Long>>()
        for (range in listOf(0..120, 20..170, 100..230)) {
            timeline.beginResponse()
            progress.beginResponse()
            for (frame in range) for (type in listOf(9, 8)) {
                val time = frame * 40L + if (type == 8) 12 else 0
                val tag = tag(type, time)
                if (progress.observe(tag)) {
                    timeline.adjust(tag)
                    delivered += type to timestamp(tag)
                }
            }
        }
        for (type in listOf(9, 8)) {
            assertEquals((0..230).map { it * 40L + if (type == 8) 12 else 0 },
                delivered.filter { it.first == type }.map { it.second })
        }
        assertEquals(12L, timeline.snapshot().audioVideoDtsDeltaMs)
        assertEquals(0, timeline.snapshot().correctionCount)
    }

    private fun mapped(timeline: FlvSharedTimeline, type: Int, timeMs: Long): Long =
        tag(type, timeMs).also(timeline::adjust).let(::timestamp)

    private fun timestamp(tag: ByteArray): Long =
        ((tag[7].toLong() and 0xff) shl 24) or
            ((tag[4].toLong() and 0xff) shl 16) or
            ((tag[5].toLong() and 0xff) shl 8) or (tag[6].toLong() and 0xff)

    private fun tag(type: Int, timeMs: Long, compositionMs: Int = 0): ByteArray {
        val payload = if (type == 9) byteArrayOf(0x17, 1,
            (compositionMs ushr 16).toByte(), (compositionMs ushr 8).toByte(), compositionMs.toByte(), 99)
            else byteArrayOf(0xaf.toByte(), 1, 99)
        return ByteArray(15 + payload.size).also {
            it[0] = type.toByte()
            it[3] = payload.size.toByte()
            it[4] = (timeMs ushr 16).toByte()
            it[5] = (timeMs ushr 8).toByte()
            it[6] = timeMs.toByte()
            it[7] = (timeMs ushr 24).toByte()
            payload.copyInto(it, 11)
            it[it.lastIndex] = (11 + payload.size).toByte()
        }
    }
}

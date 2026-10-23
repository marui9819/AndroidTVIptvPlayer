package com.tvplayer.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlvResponseProgressTest {
    @Test
    fun overlappingResponsesDeliverEachEncodedVideoPacketExactlyOnce() {
        val progress = FlvResponseProgress()
        val delivered = mutableListOf<Int>()
        progress.beginResponse()
        for (frame in 0..120) {
            val bytes = tag(9, frame * 40L, frame)
            if (progress.observe(bytes)) delivered += frame
        }
        progress.beginResponse()
        // The CDN restarts four seconds before the last delivered frame.
        for (frame in 20..170) {
            val bytes = tag(9, frame * 40L, frame)
            if (progress.observe(bytes)) delivered += frame
        }
        assertEquals((0..170).toList(), delivered)
        assertEquals(101, progress.snapshot().overlappingVideoPackets)
        assertEquals(50, progress.snapshot().newVideoPackets)
        assertEquals(1_960L, progress.snapshot().newMediaDurationMs)
    }

    @Test
    fun repeatedSilentAudioPacketsAreSkippedOnlyAtTheSameOriginalTimestamp() {
        val progress = FlvResponseProgress()
        progress.beginResponse()
        assertTrue(progress.observe(tag(8, 1_000, 0)))
        assertTrue(progress.observe(tag(8, 1_023, 0)))
        progress.beginResponse()
        assertFalse(progress.observe(tag(8, 1_000, 0)))
        assertFalse(progress.observe(tag(8, 1_023, 0)))
        assertTrue(progress.observe(tag(8, 1_046, 0)))
        assertEquals(2, progress.snapshot().overlappingAudioPackets)
        assertEquals(1, progress.snapshot().newAudioPackets)
    }

    @Test
    fun newConnectionWithResetClockAndCodecConfigurationStillPasses() {
        val progress = FlvResponseProgress()
        progress.beginResponse()
        assertTrue(progress.observe(tag(9, 1_000_000, 9)))
        progress.beginResponse()
        assertTrue(progress.observe(tag(9, 0, 9)))
        val configuration = tag(9, 0, 9).also { it[12] = 0 }
        assertTrue(progress.observe(configuration))
        progress.beginResponse()
        assertTrue(progress.observe(configuration))
    }

    @Test
    fun fragmentedFlvResponsesPreserveTagBoundariesAndRemoveOnlyTheirSharedPrefix() {
        val progress = FlvResponseProgress()
        val frames = FlvTagFrameReader()
        val delivered = mutableListOf<Int>()
        for (range in listOf(0..5, 3..9, 7..12)) {
            val header = byteArrayOf(70, 76, 86, 1, 1, 0, 0, 0, 9, 0, 0, 0, 0)
            val response = header + range.flatMap { tag(9, it * 40L, it).toList() }.toByteArray()
            var offset = 0
            frames.beginResponse()
            progress.beginResponse()
            while (true) {
                val frame = frames.readChunk { target, start, length ->
                    if (offset == response.size) -1 else {
                        val count = minOf(3, length, response.size - offset)
                        response.copyInto(target, start, offset, offset + count)
                        offset += count
                        count
                    }
                } ?: break
                if (progress.observe(frame)) delivered += frame[16].toInt() and 0xff
            }
        }
        assertEquals((0..12).toList(), delivered)
    }

    @Test
    fun distinguishesRepeatedEncodedPacketsFromNewTimestampsAndNewContent() {
        val progress = FlvResponseProgress()
        progress.beginResponse()
        progress.observe(tag(9, 1_000, 1))
        progress.observe(tag(9, 1_040, 2))
        progress.observe(tag(8, 990, 1))
        progress.observe(tag(8, 1_036, 2))
        assertEquals(46L, progress.snapshot().mediaDurationMs)
        assertNotNull(progress.snapshot().firstKeyframeHash)

        progress.beginResponse()
        progress.observe(tag(9, 1_000, 1))
        progress.observe(tag(9, 1_040, 3)) // same time, different packet
        progress.observe(tag(9, 1_080, 1)) // same packet, different time
        assertEquals(1, progress.snapshot().overlappingVideoPackets)
        assertEquals(3, progress.snapshot().videoPackets)
    }

    @Test
    fun responseHistoryIsBoundedAndUnsignedFlvTimestampsArePreserved() {
        val progress = FlvResponseProgress(historyLimit = 2)
        progress.beginResponse()
        progress.observe(tag(9, 100, 1))
        progress.observe(tag(9, 200, 2))
        progress.observe(tag(9, 300, 3))
        progress.beginResponse()
        progress.observe(tag(9, 100, 1))
        assertEquals(0, progress.snapshot().overlappingVideoPackets)
        progress.observe(tag(9, 0xffff_ff00L, 4))
        assertEquals(0xffff_ff00L, progress.snapshot().lastVideoTimestampMs)
    }

    @Test
    fun replayCannotEvictThePreviousTailBeforeItIsCompared() {
        val progress = FlvResponseProgress(historyLimit = 2)
        progress.beginResponse()
        for (id in 1..3) assertTrue(progress.observe(tag(9, id * 40L, id)))
        progress.beginResponse()
        assertTrue(progress.observe(tag(9, 40, 1))) // older than bounded history
        assertFalse(progress.observe(tag(9, 80, 2)))
        assertFalse(progress.observe(tag(9, 120, 3)))
        assertEquals(2, progress.snapshot().overlappingVideoPackets)
    }

    private fun tag(type: Int, timestampMs: Long, content: Int): ByteArray {
        val payload = if (type == 9) byteArrayOf(0x17, 1, 0, 0, 0, content.toByte())
            else byteArrayOf(0xaf.toByte(), 1, content.toByte())
        return ByteArray(15 + payload.size).also { bytes ->
            bytes[0] = type.toByte()
            bytes[3] = payload.size.toByte()
            bytes[4] = (timestampMs ushr 16).toByte()
            bytes[5] = (timestampMs ushr 8).toByte()
            bytes[6] = timestampMs.toByte()
            bytes[7] = (timestampMs ushr 24).toByte()
            payload.copyInto(bytes, 11)
            bytes[bytes.lastIndex] = (11 + payload.size).toByte()
        }
    }
}

package com.tvplayer.app.util

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FlvTagFrameReaderTest {
    // A fixed binary FLV framing fixture, independent of the test tag builder.
    // It includes AAC configuration/raw data, an AVC tag with composition time,
    // and a script tag. Tests exercise bytes through InputStream, not just sizes.
    // This is a framing fixture; it does not claim to be decoder-ready footage.
    private val header = hex("46 4c 56 01 05 00 00 00 09 00 00 00 00")
    private val tags = listOf(
        hex("08 00 00 04 00 00 00 00 00 00 00 af 00 12 10 00 00 00 0f"),
        hex("08 00 00 06 00 00 17 00 00 00 00 af 01 21 10 04 60 00 00 00 11"),
        hex("09 00 00 0a dc ba 98 fe 00 00 00 17 01 ff ff fe 00 00 00 01 65 00 00 00 15"),
        hex("12 00 00 04 00 00 28 00 00 00 00 05 00 00 09 00 00 00 0f")
    )
    private val response = join(listOf(header) + tags)

    @Test
    fun emittedHeaderAndTagsReconstructTheExactInputStream() {
        val frames = FlvTagFrameReader().apply { beginResponse(emitHeader = true) }
        val input = FragmentedInput(response, intArrayOf(1, 7, 2, 19, 3))
        val actual = drain(frames, input::read)

        assertUnits(listOf(header) + tags, actual)
        assertArrayEquals(response, join(actual))
        val callsAtEof = input.calls
        assertNull(frames.readChunk(input::read))
        assertEquals(callsAtEof, input.calls)
    }

    @Test
    fun defaultResponseConsumesHeaderAndReturnsOnlyWholeTags() {
        val input = FragmentedInput(response)
        assertUnits(tags, drain(FlvTagFrameReader(), input::read))
    }

    @Test
    fun alreadyConsumedHeaderStartsDirectlyAtTagHeader() {
        val frames = FlvTagFrameReader().apply { beginResponse(headerAlreadyConsumed = true) }
        val input = FragmentedInput(join(tags), intArrayOf(1))
        assertUnits(tags, drain(frames, input::read))
    }

    @Test
    fun doesNotReadAheadIntoTheNextUnit() {
        val frames = FlvTagFrameReader().apply { beginResponse(emitHeader = true) }
        val input = FragmentedInput(response)
        var boundary = 0
        for (unit in listOf(header) + tags) {
            assertArrayEquals(unit, frames.readChunk(input::read))
            boundary += unit.size
            assertEquals(boundary, input.position)
        }
        assertNull(frames.readChunk(input::read))
    }

    @Test
    fun allFixedAndVaryingReadFragmentSizesProduceIdenticalUnits() {
        for (size in 1..response.size + 1) {
            val input = FragmentedInput(response, intArrayOf(size))
            val frames = FlvTagFrameReader().apply { beginResponse(emitHeader = true) }
            assertUnits(listOf(header) + tags, drain(frames, input::read), "fragment=$size")
        }
    }

    @Test
    fun everyByteTruncationExposesOnlyCompleteUnits() {
        for (emitHeader in listOf(false, true)) {
            assertEveryTruncation(headerConsumed = false, emitHeader = emitHeader)
        }
        assertEveryTruncation(headerConsumed = true, emitHeader = false)
    }

    @Test
    fun everyByteTruncationAlsoCoversHeaderExtensions() {
        for (emitHeader in listOf(false, true)) {
            assertEveryTruncation(headerConsumed = false, emitHeader = emitHeader, headerBytes = fileHeader(23))
        }
    }

    @Test
    fun ioFailureAtEveryByteOffsetNeverExposesThePartialUnit() {
        val units = listOf(header) + tags
        val injected = IOException("injected transport failure")
        for (failAt in 0..response.size) {
            val input = FragmentedInput(response, intArrayOf(1, 5, 2))
            val frames = FlvTagFrameReader().apply { beginResponse(emitHeader = true) }
            val actual = mutableListOf<ByteArray>()
            val error = expectIOException {
                while (true) {
                    val unit = frames.readChunk { bytes, offset, length ->
                        if (input.position == failAt) throw injected
                        input.read(bytes, offset, minOf(length, failAt - input.position))
                    } ?: break
                    actual += unit
                }
            }
            assertSame("offset=$failAt", injected, error)
            assertUnits(completeUnits(units, failAt), actual, "offset=$failAt")
        }
    }

    @Test
    fun beginResponseAfterEveryTruncationDropsOldPartialBytes() {
        for (cut in 1 until response.size) {
            val frames = FlvTagFrameReader().apply { beginResponse(emitHeader = true) }
            val truncated = FragmentedInput(response.copyOf(cut), intArrayOf(1, 3))
            try {
                drain(frames, truncated::read)
            } catch (_: EOFException) {
                // The next response is independent of the failed old unit.
            }
            frames.beginResponse()
            val fresh = FragmentedInput(response, intArrayOf(2, 1, 5))
            assertUnits(tags, drain(frames, fresh::read), "cut=$cut")
        }
    }

    @Test
    fun beginResponseAfterIoFailureCanStartAtAnAlreadyConsumedHeader() {
        val frames = FlvTagFrameReader()
        val input = FragmentedInput(response)
        val failAt = header.size + 12
        val injected = IOException("half a payload")
        assertSame(injected, expectIOException {
            frames.readChunk { bytes, offset, length ->
                if (input.position == failAt) throw injected
                input.read(bytes, offset, minOf(length, failAt - input.position))
            }
        })

        frames.beginResponse(headerAlreadyConsumed = true)
        val fresh = FragmentedInput(join(tags), intArrayOf(1))
        assertUnits(tags, drain(frames, fresh::read))
    }

    @Test
    fun nextResponseHeaderIsConsumedWithoutBeingInsertedBetweenTags() {
        val frames = FlvTagFrameReader().apply { beginResponse(emitHeader = true) }
        val first = FragmentedInput(response)
        val emitted = drain(frames, first::read).toMutableList()
        frames.beginResponse()
        val second = FragmentedInput(response, intArrayOf(1))
        emitted += drain(frames, second::read)
        assertUnits(listOf(header) + tags + tags, emitted)
    }

    @Test
    fun emptyInputIsCleanEofInEveryHeaderMode() {
        for ((consumed, emit) in listOf(false to false, false to true, true to false)) {
            val frames = FlvTagFrameReader().apply { beginResponse(consumed, emit) }
            val input = FragmentedInput(byteArrayOf())
            assertNull(frames.readChunk(input::read))
            assertNull(frames.readChunk(input::read))
            assertEquals(1, input.calls)
        }
    }

    @Test
    fun completeHeaderWithoutTagsIsCleanEof() {
        val frames = FlvTagFrameReader().apply { beginResponse(emitHeader = true) }
        val input = FragmentedInput(header, intArrayOf(1))
        assertArrayEquals(header, frames.readChunk(input::read))
        assertNull(frames.readChunk(input::read))
        assertNull(FlvTagFrameReader().readChunk(FragmentedInput(header)::read))
    }

    @Test
    fun extendedHeaderIsPreservedAndItsPreviousTagSizeIsCheckedAtDataOffset() {
        val extended = fileHeader(65_536)
        val frames = FlvTagFrameReader(maxHeaderBytes = 65_536).apply { beginResponse(emitHeader = true) }
        val input = FragmentedInput(extended + tags[0], intArrayOf(1, 4093, 7))
        assertArrayEquals(extended, frames.readChunk(input::read))
        assertArrayEquals(tags[0], frames.readChunk(input::read))
        assertNull(frames.readChunk(input::read))

        val badPreviousSize = fileHeader(17).also { it[it.lastIndex] = 1 }
        expectMalformed(badPreviousSize)
    }

    @Test
    fun invalidSignaturesVersionsAndReservedFlagsAreRejected() {
        for (index in 0..2) expectMalformed(header.copyOf().also { it[index] = 0 })
        for (version in listOf(0, 2, 255)) {
            expectMalformed(header.copyOf().also { it[3] = version.toByte() })
        }
        for (reserved in listOf(2, 8, 16, 32, 64, 128)) {
            expectMalformed(header.copyOf().also { it[4] = (5 or reserved).toByte() })
        }
        for (flags in listOf(0, 1, 4, 5)) {
            val valid = header.copyOf().also { it[4] = flags.toByte() }
            assertNull(FlvTagFrameReader().readChunk(FragmentedInput(valid)::read))
        }
    }

    @Test
    fun invalidAndUnsignedOversizedHeaderOffsetsFailBeforeReadingExtensions() {
        for (offset in listOf(0L, 8L, 65_537L, 0x8000_0009L, 0xffff_ffffL)) {
            val fixed = header.copyOf(9).also { putUnsignedInt(it, 5, offset) }
            val input = FragmentedInput(fixed)
            expectIOException { FlvTagFrameReader().readChunk(input::read) }
            assertEquals(9, input.position)
        }
        val input = FragmentedInput(fileHeader(14))
        expectIOException { FlvTagFrameReader(maxHeaderBytes = 13).readChunk(input::read) }
        assertEquals(9, input.position)
    }

    @Test
    fun previousTagSizeZeroMustBeZeroInAllFourBytes() {
        for (index in 9..12) expectMalformed(header.copyOf().also { it[index] = 1 })
    }

    @Test
    fun eachTagsPreviousSizeMustMatchIncludingUnsignedHighBits() {
        val valid = tags[2]
        for (index in valid.size - 4 until valid.size) {
            val invalid = valid.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            expectMalformed(header + invalid)
        }
        for (size in listOf(0L, 10L, 22L, 0xffff_ffffL)) {
            val invalid = valid.copyOf().also { putUnsignedInt(it, it.size - 4, size) }
            expectMalformed(header + invalid)
        }
    }

    @Test
    fun tagReservedBitsAndNonzeroStreamIdsAreRejected() {
        for (bit in listOf(0x40, 0x80)) {
            expectMalformed(header + tags[0].copyOf().also { it[0] = (8 or bit).toByte() })
        }
        for (index in 8..10) {
            expectMalformed(header + tags[0].copyOf().also { it[index] = 1 })
        }
    }

    @Test
    fun exactPayloadLimitAndZeroLengthTagAreAccepted() {
        for (size in listOf(0, 1, 32, 257)) {
            val tag = tag(ByteArray(size) { (it * 17).toByte() })
            val frames = FlvTagFrameReader(maxTagPayloadBytes = size)
            val input = FragmentedInput(header + tag, intArrayOf(1, 13, 2))
            assertArrayEquals(tag, frames.readChunk(input::read))
            assertNull(frames.readChunk(input::read))
        }
    }

    @Test
    fun defaultTwoMiBPayloadLimitWorksWithFragmentedReads() {
        val tag = tag(ByteArray(2 * 1024 * 1024) { it.toByte() })
        val input = FragmentedInput(tag, intArrayOf(4093, 17, 8191))
        val frames = FlvTagFrameReader().apply { beginResponse(headerAlreadyConsumed = true) }
        assertArrayEquals(tag, frames.readChunk(input::read))
        assertNull(frames.readChunk(input::read))
    }

    @Test
    fun oversizePayloadIsRejectedFromItsHeaderWithoutReadingOrAllocatingItsBody() {
        for (declared in listOf(33, 0xff_ffff)) {
            val fixed = tags[0].copyOf(11).also {
                it[1] = (declared ushr 16).toByte()
                it[2] = (declared ushr 8).toByte()
                it[3] = declared.toByte()
            }
            val input = FragmentedInput(header + fixed + byteArrayOf(1, 2, 3))
            expectIOException { FlvTagFrameReader(maxTagPayloadBytes = 32).readChunk(input::read) }
            assertEquals(header.size + 11, input.position)
        }
    }

    @Test(timeout = 2_000)
    fun zeroProgressIsRejectedOnTheFirstOccurrenceIncludingMidTag() {
        for (stallAt in listOf(0, 1, 9, 12, 13, 14, 24, 25, 30)) {
            val frames = FlvTagFrameReader()
            val input = FragmentedInput(response, intArrayOf(1, 3))
            var zeroCalls = 0
            expectIOException {
                drain(frames) { bytes, offset, length ->
                    if (input.position == stallAt) {
                        zeroCalls++
                        0
                    } else {
                        input.read(bytes, offset, minOf(length, stallAt - input.position))
                    }
                }
            }
            assertEquals(1, zeroCalls)
        }
    }

    @Test
    fun invalidReaderCountsAreRejected() {
        for (count in listOf(-2, Int.MIN_VALUE)) {
            expectIOException { FlvTagFrameReader().readChunk { _, _, _ -> count } }
        }
        expectIOException { FlvTagFrameReader().readChunk { _, _, length -> length + 1 } }
    }

    @Test
    fun failedResponseConsumesNothingMoreUntilBeginResponse() {
        val frames = FlvTagFrameReader()
        val input = FragmentedInput(response.copyOf(header.size + 12))
        assertTrue(expectIOException { frames.readChunk(input::read) } is EOFException)
        expectIOException {
            frames.readChunk { _, _, _ -> throw AssertionError("Failed response consumed more input") }
        }
        frames.beginResponse()
        assertUnits(tags, drain(frames, FragmentedInput(response)::read))
    }

    @Test
    fun invalidConfigurationAndUnavailableHeaderEmissionAreRejected() {
        for (limit in listOf(-1, 0x100_0000, Int.MAX_VALUE)) {
            expectIllegalArgument { FlvTagFrameReader(maxTagPayloadBytes = limit) }
        }
        for (limit in listOf(-1, 0, 8, Int.MAX_VALUE - 3, Int.MAX_VALUE)) {
            expectIllegalArgument { FlvTagFrameReader(maxHeaderBytes = limit) }
        }
        expectIllegalArgument {
            FlvTagFrameReader().beginResponse(headerAlreadyConsumed = true, emitHeader = true)
        }
    }

    private fun assertEveryTruncation(
        headerConsumed: Boolean,
        emitHeader: Boolean,
        headerBytes: ByteArray = header
    ) {
        val wireUnits = if (headerConsumed) tags else listOf(headerBytes) + tags
        val bytes = join(wireUnits)
        val boundaries = mutableSetOf(0)
        var boundary = 0
        wireUnits.forEach { boundary += it.size; boundaries += boundary }
        for (fragment in listOf(1, 2, 7, bytes.size + 1)) {
            for (cut in 0..bytes.size) {
                val label = "consumed=$headerConsumed emit=$emitHeader fragment=$fragment cut=$cut"
                val frames = FlvTagFrameReader().apply { beginResponse(headerConsumed, emitHeader) }
                val input = FragmentedInput(bytes.copyOf(cut), intArrayOf(fragment))
                val actual = mutableListOf<ByteArray>()
                var failure: IOException? = null
                try {
                    while (true) actual += frames.readChunk(input::read) ?: break
                } catch (error: IOException) {
                    failure = error
                }
                if (cut in boundaries) {
                    assertNull(label, failure)
                } else {
                    assertTrue(label, failure is EOFException)
                }
                val complete = completeUnits(wireUnits, cut)
                val expected = if (!headerConsumed && !emitHeader) complete.drop(1) else complete
                assertUnits(expected, actual, label)
            }
        }
    }

    private fun completeUnits(units: List<ByteArray>, byteCount: Int): List<ByteArray> {
        var end = 0
        return units.takeWhile { end += it.size; end <= byteCount }
    }

    private fun expectMalformed(bytes: ByteArray) {
        val error = expectIOException {
            drain(FlvTagFrameReader(), FragmentedInput(bytes, intArrayOf(1, 4))::read)
        }
        assertTrue("Expected a format error, got $error", error !is EOFException)
    }

    private fun drain(
        frames: FlvTagFrameReader,
        read: (ByteArray, Int, Int) -> Int
    ): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        while (true) result += frames.readChunk(read) ?: return result
    }

    private fun assertUnits(expected: List<ByteArray>, actual: List<ByteArray>, label: String = "") {
        assertEquals(label, expected.size, actual.size)
        expected.indices.forEach { assertArrayEquals("$label unit=$it", expected[it], actual[it]) }
    }

    private inline fun expectIOException(block: () -> Unit): IOException {
        try {
            block()
        } catch (error: IOException) {
            return error
        }
        throw AssertionError("Expected IOException")
    }

    private inline fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }

    private class FragmentedInput(bytes: ByteArray, private val fragments: IntArray = intArrayOf(Int.MAX_VALUE)) {
        private val stream = ByteArrayInputStream(bytes)
        var position = 0
            private set
        var calls = 0
            private set

        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            require(length > 0) { "Reader must never request zero bytes" }
            val fragment = fragments[calls % fragments.size]
            calls++
            return stream.read(buffer, offset, minOf(length, fragment)).also {
                if (it > 0) position += it
            }
        }
    }

    private fun fileHeader(dataOffset: Int): ByteArray = ByteArray(dataOffset + 4).also {
        header.copyInto(it, endIndex = 9)
        putUnsignedInt(it, 5, dataOffset.toLong())
        for (index in 9 until dataOffset) it[index] = (index * 31).toByte()
    }

    private fun tag(payload: ByteArray): ByteArray = ByteArray(11 + payload.size + 4).also {
        it[0] = 9
        it[1] = (payload.size ushr 16).toByte()
        it[2] = (payload.size ushr 8).toByte()
        it[3] = payload.size.toByte()
        payload.copyInto(it, destinationOffset = 11)
        putUnsignedInt(it, it.size - 4, (11 + payload.size).toLong())
    }

    private fun putUnsignedInt(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0..3) bytes[offset + index] = (value ushr (24 - 8 * index)).toByte()
    }

    private fun join(units: List<ByteArray>): ByteArray {
        val result = ByteArray(units.sumOf { it.size })
        var offset = 0
        units.forEach { it.copyInto(result, destinationOffset = offset); offset += it.size }
        return result
    }

    private fun hex(value: String): ByteArray = value.trim().split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }.toByteArray()
}

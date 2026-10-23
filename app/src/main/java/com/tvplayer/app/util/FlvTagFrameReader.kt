package com.tvplayer.app.util

import java.io.EOFException
import java.io.IOException

internal class MalformedFlvException(message: String) : IOException(message)

/**
 * Reads one complete FLV container unit at a time, without retaining a response.
 *
 * Construction is equivalent to [beginResponse] with its default arguments. Call
 * [beginResponse] for each new HTTP response, including after a read failure.
 * Only set `emitHeader` for the initial response delivered to an extractor. An
 * emitted header includes its extensions and the four-byte PreviousTagSize0.
 * `maxHeaderBytes` limits DataOffset (the header excluding PreviousTagSize0).
 *
 * The reader must return 1..length bytes or -1 for EOF. A zero-progress read is
 * rejected immediately. A read/validation failure poisons the current response;
 * no incomplete unit is returned, and no further input is consumed until
 * [beginResponse]. Previously returned arrays belong to the caller. This class
 * is not thread-safe and does not own, close, or retain the supplied reader.
 */
class FlvTagFrameReader(
    private val maxTagPayloadBytes: Int = 2 * 1024 * 1024,
    private val maxHeaderBytes: Int = 65_536
) {
    init {
        require(maxTagPayloadBytes in 0..MAX_FLV_PAYLOAD_BYTES) {
            "maxTagPayloadBytes must fit the unsigned 24-bit FLV payload size"
        }
        require(maxHeaderBytes in FILE_HEADER_BYTES..(Int.MAX_VALUE - PREVIOUS_TAG_SIZE_BYTES)) {
            "maxHeaderBytes must accommodate a FLV header without integer overflow"
        }
    }

    private enum class State { HEADER, TAG, EOF, FAILED }

    private var state = State.HEADER
    private var emitResponseHeader = false
    var lastHeaderBytes: Int = 0
        private set

    /**
     * Discards the previous response's state, including any failed partial unit.
     *
     * With `headerAlreadyConsumed`, input must start at the first tag, AFTER the
     * header and PreviousTagSize0. That consumed header cannot be emitted.
     */
    fun beginResponse(headerAlreadyConsumed: Boolean = false, emitHeader: Boolean = false) {
        require(!headerAlreadyConsumed || !emitHeader) {
            "Cannot emit an already consumed FLV header"
        }
        state = if (headerAlreadyConsumed) State.TAG else State.HEADER
        emitResponseHeader = emitHeader
        lastHeaderBytes = 0
    }

    /**
     * Returns a complete header (if requested) or 11-byte tag header + payload +
     * four-byte PreviousTagSize. Returns null only at a clean response boundary.
     * A partial header/tag throws [EOFException]; other malformed input, zero
     * progress, and invalid reader counts throw [IOException]. Reader IOExceptions
     * are propagated unchanged. After any IOException, call [beginResponse].
     */
    @Throws(IOException::class)
    fun readChunk(reader: (ByteArray, Int, Int) -> Int): ByteArray? {
        when (state) {
            State.EOF -> return null
            State.FAILED -> throw IOException("FLV response failed; call beginResponse before reading again")
            else -> Unit
        }
        try {
            if (state == State.HEADER) {
                val header = readHeader(reader)
                if (header == null) {
                    state = State.EOF
                    return null
                }
                state = State.TAG
                if (emitResponseHeader) return header
            }
            val tag = readTag(reader)
            if (tag == null) state = State.EOF
            return tag
        } catch (error: IOException) {
            state = State.FAILED
            throw error
        }
    }

    private fun readHeader(reader: (ByteArray, Int, Int) -> Int): ByteArray? {
        val fixed = ByteArray(FILE_HEADER_BYTES)
        if (!readFully(reader, fixed, 0, fixed.size, allowCleanEof = true, part = "FLV header")) {
            return null
        }
        if (fixed[0] != 'F'.code.toByte() || fixed[1] != 'L'.code.toByte() ||
            fixed[2] != 'V'.code.toByte()
        ) throw MalformedFlvException("Invalid FLV signature")
        if (fixed[3].toInt() != 1) throw MalformedFlvException("Unsupported FLV version")
        if ((fixed[4].toInt() and 0xfa) != 0) throw MalformedFlvException("Invalid FLV header reserved flags")
        val dataOffset = unsignedInt(fixed, 5)
        if (dataOffset < FILE_HEADER_BYTES || dataOffset > maxHeaderBytes.toLong()) {
            throw MalformedFlvException("FLV DataOffset $dataOffset exceeds allowed header bounds")
        }
        val header = ByteArray(dataOffset.toInt() + PREVIOUS_TAG_SIZE_BYTES)
        fixed.copyInto(header)
        readFully(reader, header, fixed.size, header.size - fixed.size, part = "FLV header extension/PreviousTagSize0")
        if (unsignedInt(header, dataOffset.toInt()) != 0L) {
            throw MalformedFlvException("FLV PreviousTagSize0 must be zero")
        }
        lastHeaderBytes = header.size
        return header
    }

    private fun readTag(reader: (ByteArray, Int, Int) -> Int): ByteArray? {
        val fixed = ByteArray(TAG_HEADER_BYTES)
        if (!readFully(reader, fixed, 0, fixed.size, allowCleanEof = true, part = "FLV tag header")) {
            return null
        }
        if ((fixed[0].toInt() and 0xc0) != 0) throw MalformedFlvException("Invalid FLV tag reserved bits")
        if (fixed[8].toInt() != 0 || fixed[9].toInt() != 0 || fixed[10].toInt() != 0) {
            throw MalformedFlvException("FLV tag StreamID must be zero")
        }
        val payloadBytes = ((fixed[1].toInt() and 0xff) shl 16) or
            ((fixed[2].toInt() and 0xff) shl 8) or (fixed[3].toInt() and 0xff)
        if (payloadBytes > maxTagPayloadBytes) {
            throw MalformedFlvException("FLV tag payload $payloadBytes exceeds limit $maxTagPayloadBytes")
        }
        // Only one complete tag is allocated; no media from it escapes on failure.
        val previousSizeOffset = TAG_HEADER_BYTES + payloadBytes
        val tag = ByteArray(previousSizeOffset + PREVIOUS_TAG_SIZE_BYTES)
        fixed.copyInto(tag)
        readFully(reader, tag, fixed.size, tag.size - fixed.size, part = "FLV tag payload/PreviousTagSize")
        if (unsignedInt(tag, previousSizeOffset) != previousSizeOffset.toLong()) {
            throw MalformedFlvException("FLV PreviousTagSize does not match tag header + payload")
        }
        return tag
    }

    private fun readFully(
        reader: (ByteArray, Int, Int) -> Int,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        allowCleanEof: Boolean = false,
        part: String
    ): Boolean {
        var consumed = 0
        while (consumed < length) {
            val remaining = length - consumed
            val count = reader(buffer, offset + consumed, remaining)
            when {
                count == -1 -> {
                    if (allowCleanEof && consumed == 0) return false
                    throw EOFException("Incomplete $part: received $consumed of $length bytes")
                }
                count == 0 -> throw IOException("Zero-progress read while reading $part")
                count < -1 || count > remaining -> throw IOException("Invalid reader count $count for $remaining bytes")
                else -> consumed += count
            }
        }
        return true
    }

    private fun unsignedInt(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)

    private companion object {
        const val FILE_HEADER_BYTES = 9
        const val TAG_HEADER_BYTES = 11
        const val PREVIOUS_TAG_SIZE_BYTES = 4
        const val MAX_FLV_PAYLOAD_BYTES = 0xff_ffff
    }
}

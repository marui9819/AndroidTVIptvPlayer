package com.tvplayer.app.network

import com.google.gson.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HuyaStreamResolverTest {

    companion object {
        private const val FIXED_UID = 12_340_001L
        private const val FIXED_TIME_MS = 1_700_000_000_000L
        // Base64 of AB>_$0_$1_$2_$3: the '+' in QUI+ is deliberate.
        private const val ENCODED_FM = "QUI%2BXyQwXyQxXyQyXyQz"
        private const val VALID_ANTI_CODE =
            "fm=$ENCODED_FM&wsTime=65f00000&ctype=huya%5Flive&t=%31%30%30"
    }

    @Test
    fun antiCodeDecodesBase64PlusAndEncodedSigningFieldsExactlyOnce() {
        val parsed = parseHuyaAntiCode(VALID_ANTI_CODE)

        assertEquals("QUI+XyQwXyQxXyQyXyQz", parsed.fm)
        assertEquals("huya_live", parsed.ctype)
        assertEquals("100", parsed.t)
        assertEquals("65f00000", parsed.wsTime)
    }

    @Test
    fun antiCodeDoesNotRecursivelyDecodePercentEscapes() {
        val parsed = parseHuyaAntiCode(
            "fm=QUI%252BXyQw&wsTime=65f00000&ctype=huya%252Blive&t=%2531"
        )

        assertEquals("QUI%2BXyQw", parsed.fm)
        assertEquals("huya%2Blive", parsed.ctype)
        assertEquals("%31", parsed.t)
    }

    @Test
    fun antiCodeStillAcceptsEncodedParameterNames() {
        val parsed = parseHuyaAntiCode("f%6D=$ENCODED_FM&wsTime=65f00000&c%74ype=huya%5Flive&t=100")

        assertEquals("QUI+XyQwXyQxXyQyXyQz", parsed.fm)
        assertEquals("huya_live", parsed.ctype)
    }

    @Test
    fun desktopAndMobileSigningShareTheFixedDecodedAntiCodeVector() {
        val parsed = parseHuyaAntiCode(VALID_ANTI_CODE)
        // Independently calculated from prefix AB>, seqid 1700012340001,
        // converted UID 3159040256 and MD5(seqid|huya_live|100).
        val expected = HuyaStreamSignature(
            wsSecret = "3493c6034b5482ae40c95a380ae5c43a",
            wsTime = "65f00000",
            ctype = "huya_live",
            t = "100",
            seqId = 1_700_012_340_001L,
            convertedUid = 3_159_040_256L
        )

        assertEquals(expected, signHuyaAntiCode(parsed, "test_stream", "huya_live", FIXED_UID, FIXED_TIME_MS))
        assertEquals(expected, signHuyaAntiCode(parsed, "test_stream", "tars_mobile", FIXED_UID, FIXED_TIME_MS))
    }

    @Test
    fun literalPlusInCtypeSurvivesSigningAndQuerySerialization() {
        val antiCode = "fm=$ENCODED_FM&wsTime=65f00000&ctype=huya%2Blive&t=%31%30%30"
        val signature = signHuyaAntiCode(
            parseHuyaAntiCode(antiCode), "test_stream", "huya_live", FIXED_UID, FIXED_TIME_MS
        )

        assertEquals("huya+live", signature.ctype)
        assertEquals("e682a21121db875c689c4f3c27155301", signature.wsSecret)
        val url = fixedUrl("http://tx.example/live", "test_stream", "flv", antiCode, 0)
        assertTrue(url.contains("&ctype=huya%2Blive&"))
        assertEquals("huya+live", parseHuyaAntiCode(url.substringAfter('?')).ctype)
    }

    @Test
    fun signingKeepsTheExistingDesktopAndMobileDefaults() {
        val parsed = parseHuyaAntiCode("fm=$ENCODED_FM&wsTime=65f00000")
        val desktop = signHuyaAntiCode(parsed, "test_stream", "huya_live", FIXED_UID, FIXED_TIME_MS)
        val mobile = signHuyaAntiCode(parsed, "test_stream", "tars_mobile", FIXED_UID, FIXED_TIME_MS)

        assertEquals("huya_live", desktop.ctype)
        assertEquals("tars_mobile", mobile.ctype)
        assertEquals("100", desktop.t)
        assertEquals("100", mobile.t)
        assertEquals("3493c6034b5482ae40c95a380ae5c43a", desktop.wsSecret)
        assertEquals("4f668cafc482b61e6838aed9928ba9f6", mobile.wsSecret)
    }

    @Test
    fun fixedSignedUrlKeepsExistingParametersAndTheirEncodedValues() {
        val url = fixedUrl(
            "http://tx.example/live", "test_stream", "flv",
            "$VALID_ANTI_CODE&fs=a%2Bb%3D&dMod=x%2By&sdkPcdn=1%252B2", 2000
        )

        assertEquals(
            "https://tx.example/live/test_stream.flv?" +
                "wsSecret=3493c6034b5482ae40c95a380ae5c43a&wsTime=65f00000" +
                "&ctype=huya_live&fs=a%2Bb%3D&seqid=1700012340001&u=3159040256" +
                "&sdk_sid=1700000000000&ratio=2000&t=100&ver=1&sv=2401090219&codec=264" +
                "&dMod=x%2By&sdkPcdn=1%252B2",
            url
        )
    }

    @Test
    fun mobileUrlWithBase64PlusUsesTheFixedSignatureWithoutAnotherDecode() {
        val signedUrl = buildHuyaMobileStreamUrl(
            "//tx.example/live/test_stream.m3u8?$VALID_ANTI_CODE&fs=a%2Bb%3D",
            FIXED_UID, FIXED_TIME_MS
        )

        assertEquals(
            "https://tx.example/live/test_stream.m3u8?" +
                "wsSecret=3493c6034b5482ae40c95a380ae5c43a&wsTime=65f00000" +
                "&u=3159040256&seqid=1700012340001&ctype=huya_live&ver=1&fs=a%2Bb%3D" +
                "&sdk_sid=1700000000000&t=100&sv=2401090219&codec=264",
            signedUrl
        )
    }

    @Test
    fun mobileUrlEncodesLiteralCtypePlusWithoutChangingItsSignatureInput() {
        val signedUrl = buildHuyaMobileStreamUrl(
            "https://tx.example/live/test_stream.m3u8?" +
                "fm=$ENCODED_FM&wsTime=65f00000&ctype=huya%2Blive&t=100",
            FIXED_UID, FIXED_TIME_MS
        ) ?: throw AssertionError("Expected a signed mobile URL")

        assertTrue(signedUrl.contains("wsSecret=e682a21121db875c689c4f3c27155301&"))
        assertTrue(signedUrl.contains("&ctype=huya%2Blive&"))
        assertEquals("huya+live", parseHuyaAntiCode(signedUrl.substringAfter('?')).ctype)
    }

    @Test
    fun malformedFlvSignatureDoesNotDiscardIndependentHls() {
        val failures = mutableListOf<String>()
        val candidates = buildHuyaStreamCandidates(
            listOf(streamInfo(flvAntiCode = "fm=%%%&wsTime=65f00000")), listOf(0), ::fixedUrl,
            onCandidateFailure = { cdn, mime, _ -> failures.add("$cdn|$mime") }
        )

        assertEquals(listOf("TX|video/x-flv"), failures)
        assertEquals(1, candidates.size)
        assertEquals("application/x-mpegURL", candidates.single().mimeType)
        assertTrue(candidates.single().url.startsWith("https://tx.example/hls/test_stream.m3u8?"))
        assertTrue(candidates.single().url.contains("wsSecret=3493c6034b5482ae40c95a380ae5c43a&"))
    }

    @Test
    fun malformedHlsBase64DoesNotDiscardIndependentFlv() {
        val failures = mutableListOf<String>()
        val candidates = buildHuyaStreamCandidates(
            listOf(streamInfo(hlsAntiCode = "fm=not!base64&wsTime=65f00000")), listOf(0), ::fixedUrl,
            onCandidateFailure = { _, mime, _ -> failures.add(mime) }
        )

        assertEquals(listOf("application/x-mpegURL"), failures)
        assertEquals(listOf("video/x-flv"), candidates.map { it.mimeType })
    }

    @Test
    fun malformedFlvFieldDoesNotPreventBuildingHlsForTheSameCdn() {
        val stream = streamInfo().apply { add("sFlvUrl", JsonObject()) }
        val candidates = buildHuyaStreamCandidates(listOf(stream), listOf(0), ::fixedUrl)

        assertEquals(1, candidates.size)
        assertEquals("application/x-mpegURL", candidates.single().mimeType)
    }

    @Test
    fun missingFlvFieldsDoNotPreventBuildingAnHlsOnlyCdn() {
        val stream = streamInfo().apply {
            remove("sFlvUrl")
            remove("sFlvUrlSuffix")
            remove("sFlvAntiCode")
        }
        val candidates = buildHuyaStreamCandidates(listOf(stream), listOf(0), ::fixedUrl)

        assertEquals(listOf("application/x-mpegURL"), candidates.map { it.mimeType })
    }

    @Test
    fun malformedCdnDoesNotAbortLaterCdnAndBitrateCandidates() {
        val bad = streamInfo(cdn = "TX").apply { addProperty("iMobilePriorityRate", "bad-priority") }
        val good = streamInfo(cdn = "HW")
        val candidates = buildHuyaStreamCandidates(listOf(bad, good), listOf(0, 2000), ::fixedUrl)

        assertEquals(listOf("HW", "HW", "HW", "HW"), candidates.map { it.cdnType })
        assertEquals(listOf(0, 0, 2000, 2000), candidates.map { it.bitrate })
        assertEquals(listOf("video/x-flv", "application/x-mpegURL", "video/x-flv", "application/x-mpegURL"),
            candidates.map { it.mimeType })
        assertTrue(candidates.all { it.priority == 7 })
    }

    @Test
    fun oneBitrateConstructionFailureDoesNotAbortOtherCandidates() {
        val candidates = buildHuyaStreamCandidates(
            listOf(streamInfo()), listOf(0, 2000),
            buildUrl = { base, name, suffix, antiCode, bitrate ->
                require(suffix != "flv" || bitrate != 0) { "Synthetic first-candidate failure" }
                fixedUrl(base, name, suffix, antiCode, bitrate)
            }
        )

        assertEquals(listOf("application/x-mpegURL", "video/x-flv", "application/x-mpegURL"),
            candidates.map { it.mimeType })
        assertEquals(listOf(0, 2000, 2000), candidates.map { it.bitrate })
    }

    @Test
    fun missingHlsAntiCodeNeverReusesTheFlvSignature() {
        val stream = streamInfo().apply { remove("sHlsAntiCode") }
        val candidates = buildHuyaStreamCandidates(listOf(stream), listOf(0), ::fixedUrl)

        assertEquals(listOf("video/x-flv"), candidates.map { it.mimeType })
    }

    @Test
    fun entirelyMalformedSignaturesYieldNoCandidates() {
        val stream = streamInfo(
            flvAntiCode = "fm=not!base64&wsTime=65f00000",
            hlsAntiCode = "fm=$ENCODED_FM"
        )

        assertTrue(buildHuyaStreamCandidates(listOf(stream), listOf(0), ::fixedUrl).isEmpty())
    }

    @Test
    fun huyaPlaybackHeadersMatchAptvMacPlayerProfile() {
        assertEquals(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            HuyaStreamResolver.huyaHeaders()["User-Agent"]
        )
    }

    @Test
    fun mobileHlsUsesTheMobileSigningOriginAndUserAgent() {
        val headers = HuyaStreamResolver.mobileHuyaMediaHeaders()
        assertTrue(headers["User-Agent"].orEmpty().contains("Android 10"))
        assertEquals("https://m.huya.com/", headers["Referer"])
        assertEquals("https://m.huya.com", headers["Origin"])
    }

    @Test
    fun rejectsCdnHostnamesThatAndroidTlsCannotUseAsSni() {
        assertFalse(isValidHuyaCdnHost("2409_8c28_90a2_300__149.bytefcdnrd.com"))
        assertFalse(isValidHuyaCdnHost("bad_host.example.com"))
        assertFalse(isValidHuyaCdnHost("-invalid.example.com"))
    }

    @Test
    fun acceptsValidDnsAndIpHosts() {
        assertTrue(isValidHuyaCdnHost("txosi-safety.huya.com"))
        assertTrue(isValidHuyaCdnHost("192.0.2.15"))
        assertTrue(isValidHuyaCdnHost("2409:8c28:90a2:300::149"))
    }

    @Test
    fun detectsInvalidCdnHostErrorsThroughWrappedCauses() {
        val error = IllegalStateException(
            "playback failed",
            IllegalArgumentException("Invalid input to toASCII: 2409_8c28.bytefcdnrd.com")
        )

        assertTrue(isInvalidHuyaCdnHostError(error))
        assertFalse(isInvalidHuyaCdnHostError(IllegalStateException("network timeout")))
    }

    private fun fixedUrl(
        baseUrl: String,
        streamName: String,
        suffix: String,
        antiCode: String,
        bitrate: Int
    ): String = buildHuyaResolvedStreamUrl(
        baseUrl, streamName, suffix, antiCode, bitrate, forceHttps = true,
        uid = FIXED_UID, timestampMillis = FIXED_TIME_MS
    )

    private fun streamInfo(
        cdn: String = "TX",
        flvAntiCode: String = VALID_ANTI_CODE,
        hlsAntiCode: String = VALID_ANTI_CODE
    ): JsonObject = JsonObject().apply {
        addProperty("sCdnType", cdn)
        addProperty("iMobilePriorityRate", 7)
        addProperty("sStreamName", "test_stream")
        addProperty("sFlvUrl", "http://${cdn.lowercase()}.example/flv")
        addProperty("sFlvUrlSuffix", "flv")
        addProperty("sFlvAntiCode", flvAntiCode)
        addProperty("sHlsUrl", "http://${cdn.lowercase()}.example/hls")
        addProperty("sHlsUrlSuffix", "m3u8")
        addProperty("sHlsAntiCode", hlsAntiCode)
    }
}

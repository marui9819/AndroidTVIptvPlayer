package com.tvplayer.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaybackDiagnosticsTest {

    @Test
    fun protectionOptionsAndBudgetRemainObservableButNoTimerTokenIsExposed() {
        val safe = DebugReporter.sanitizeLivePlaybackDetails(mapOf(
            "avPrecheck" to false, "audioRecovery" to true,
            "protectionAttempt" to 2, "protectionLimit" to 3,
            "candidateMustBePreconnected" to true, "token" to "private-runtime-token"
        ))
        assertEquals(false, safe["avPrecheck"])
        assertEquals(true, safe["audioRecovery"])
        assertEquals(2, safe["protectionAttempt"])
        assertEquals(3, safe["protectionLimit"])
        assertFalse(safe.containsKey("token"))
    }

    @Test
    fun bufferEvictsOldestEntriesAtEntryLimit() {
        val buffer = BoundedPlaybackLogBuffer(maxEntries = 2, maxBytes = 1024)

        assertTrue(buffer.append(1, "one"))
        assertTrue(buffer.append(2, "two"))
        assertTrue(buffer.append(3, "three"))

        assertEquals(listOf(2L, 3L), buffer.readAfter(0, 50).map { it.id })
        assertEquals(8, buffer.sizeBytes())
    }

    @Test
    fun bufferEvictsOldestEntriesAtByteLimit() {
        val buffer = BoundedPlaybackLogBuffer(maxEntries = 10, maxBytes = 6)

        assertTrue(buffer.append(1, "1234"))
        assertTrue(buffer.append(2, "5678"))

        assertEquals(listOf(2L), buffer.readAfter(0, 50).map { it.id })
        assertEquals(4, buffer.sizeBytes())
        assertFalse(buffer.append(3, "1234567"))
        assertEquals(4, buffer.sizeBytes())
    }

    @Test
    fun readsAreIncrementalAndResponseCountIsCapped() {
        val buffer = BoundedPlaybackLogBuffer(maxEntries = 300, maxBytes = 300 * 4)
        repeat(220) { assertTrue(buffer.append((it + 1).toLong(), "log")) }

        assertEquals((21L..220L).toList(), buffer.readAfter(20, 500).map { it.id })
        assertEquals(200, buffer.readAfter(0, 500).size)
    }

    @Test
    fun playbackWatchdogFieldsSurviveFilteringWithoutExposingUrls() {
        val safe = DebugReporter.sanitizeLivePlaybackDetails(
            mapOf(
                "playbackState" to "ready",
                "lastFrameAgeMs" to 31_000L,
                "selectedChannelIndex" to 14,
                "resolvedUrl" to "https://example.invalid/private-token",
                "unrecognizedField" to "drop"
            )
        )

        assertEquals(
            mapOf(
                "playbackState" to "ready",
                "lastFrameAgeMs" to 31_000L,
                "selectedChannelIndex" to 14
            ),
            safe
        )
    }
}

package com.tvplayer.app.util

import com.tvplayer.app.data.model.Channel
import org.junit.Assert.*
import org.junit.Test

class ChannelUserPolicyTest {
    private fun entry(id: Int, url: String = "https://test/$id", key: String = "station$id") =
        RecentPlayback(url, "频道$id", key, id.toLong() + 1, id.toLong())

    @Test fun historyKeepsTwentyDistinctConfirmedStationsNewestFirst() {
        var records = emptyList<RecentPlayback>()
        repeat(25) { records = ChannelUserPolicy.record(records, entry(it), 20) }
        assertEquals(20, records.size)
        assertEquals("频道24", records.first().name)
        assertEquals("频道5", records.last().name)
    }

    @Test fun replayAndAlternativeSourceMoveSameStationToFrontWithoutDuplicates() {
        val initial = listOf(entry(2), entry(1), entry(0))
        val replay = ChannelUserPolicy.record(initial, entry(1), 20)
        assertEquals(listOf("station1", "station2", "station0"), replay.map { it.logicalKey })
        val alternate = ChannelUserPolicy.record(replay, entry(7, key = "station1"), 20)
        assertEquals(3, alternate.size)
        assertEquals("https://test/7", alternate.first().url)
        assertFalse(alternate.any { it.url == "https://test/1" })
    }

    @Test fun originalUrlSurvivesDatabaseIdChangesAndMissingUrlsNeverSubstitute() {
        val record = entry(1)
        val replacement = Channel.create("新ID原频道", record.url, 1L).copy(id = 777L)
        val unrelatedReusedId = Channel.create("别的频道", "https://test/other", 1L).copy(id = 1L)
        val resolved = ChannelUserPolicy.resolveHistory(listOf(record, entry(9)), listOf(unrelatedReusedId, replacement))
        assertEquals(listOf(777L), resolved.map { it.id })
        assertEquals(record.playedAt, resolved.single().lastPlayed)
    }

    @Test fun configurableLimitAndInvalidAttemptsAreBounded() {
        assertEquals(1, ChannelUserPolicy.historyLimit(-1))
        assertEquals(100, ChannelUserPolicy.historyLimit(1000))
        val history = listOf(entry(3), entry(2), entry(1))
        assertEquals(1, ChannelUserPolicy.record(history, entry(4), 1).size)
        assertEquals(history, ChannelUserPolicy.record(history, entry(4).copy(url = " "), 20))
        assertEquals(history, ChannelUserPolicy.record(history, entry(4).copy(playedAt = 0), 20))
    }

    @Test fun explicitHistorySelectionUsesItsUrlNotMergedPreferredSourceOrRecycledId() {
        val preferred = Channel.create("同名频道", "https://test/preferred", 1).copy(id = 1)
        val requested = preferred.copy(id = 2, url = "https://test/requested", isAvailable = false)
        assertEquals(requested, ChannelUserPolicy.requestedSource(listOf(preferred, requested), 2, requested.url))
        val reimported = requested.copy(id = 88)
        assertEquals(reimported, ChannelUserPolicy.requestedSource(listOf(preferred, reimported), 1, requested.url))
        assertNull(ChannelUserPolicy.requestedSource(listOf(preferred), 1, requested.url))
        assertEquals(preferred, ChannelUserPolicy.requestedSource(listOf(preferred), 1, null))
    }

    @Test fun transparencyAndExitCountdownHaveClearSemantics() {
        assertEquals(.5f, ChannelUserPolicy.opacity(50), 0f)
        assertEquals(1f, ChannelUserPolicy.opacity(0), 0f)
        assertEquals(0f, ChannelUserPolicy.opacity(100), 0f)
        assertEquals(1L, ChannelUserPolicy.remainingMinutes(60_001L, 60_000L))
        assertEquals(2L, ChannelUserPolicy.remainingMinutes(120_001L, 60_000L))
        assertEquals(0L, ChannelUserPolicy.remainingMinutes(59_999L, 60_000L))
    }

    @Test fun readyWithoutActualAvEvidenceIsNotSuccessfulPlayback() {
        fun success(playing: Boolean = true, video: Boolean = true, frame: Boolean = true,
                    audio: Int = 1, advancing: Boolean = true, huya: Boolean = true) =
            ChannelUserPolicy.successfulPlayback(playing, video, frame, audio, advancing, huya)
        assertTrue(success())
        assertFalse(success(playing = false))
        assertFalse(success(frame = false))
        assertFalse(success(audio = 0))
        assertFalse(success(advancing = false))
        assertTrue(success(video = false, frame = false, huya = false)) // radio
        assertFalse(success(video = false, frame = false, audio = 0, huya = false))
        assertTrue(success(audio = 0, advancing = false, huya = false)) // intentional silent video
    }
}

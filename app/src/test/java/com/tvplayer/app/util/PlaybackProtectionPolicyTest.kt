package com.tvplayer.app.util

import org.junit.Assert.*
import org.junit.Test

class PlaybackProtectionPolicyTest {
    private fun recover(
        enabled: Boolean = true, huyaFlv: Boolean = true, playing: Boolean = true,
        deferred: Boolean = false, age: Long = 9_000L, tracks: Int = 0,
        advancing: Boolean = false, pcm: Boolean = false, attempts: Int = 0
    ) = PlaybackProtectionPolicy.shouldRecoverAudio(enabled, huyaFlv, playing, deferred, age, tracks, advancing, pcm, attempts)

    @Test fun candidateValidationWorksForPhoneAndTvWithoutReclassifyingHardware() {
        assertTrue(PlaybackProtectionPolicy.acceptsCandidate(true, true, true, true))
        assertTrue(PlaybackProtectionPolicy.acceptsCandidate(true, null, true, true))
        assertFalse(PlaybackProtectionPolicy.acceptsCandidate(true, false, true, true))
        assertFalse(PlaybackProtectionPolicy.acceptsCandidate(true, true, false, true))
        assertFalse(PlaybackProtectionPolicy.acceptsCandidate(true, true, true, false))
        assertFalse(PlaybackProtectionPolicy.acceptsCandidate(false, true, true, true))
    }

    @Test fun quietAudioAndBypassedPcmObserverDoNotTriggerRenewals() {
        assertFalse(recover(tracks = 1, advancing = true, pcm = false))
        assertFalse(recover(tracks = 1, advancing = true, pcm = false, age = 120_000L))
        assertFalse(recover(pcm = true))
    }

    @Test fun missingTracksAndUnstartedAudioHaveDifferentGracePeriods() {
        assertFalse(recover(age = 2_999L))
        assertTrue(recover(age = 3_000L))
        assertFalse(recover(tracks = 1, age = 7_999L))
        assertTrue(recover(tracks = 1, age = 8_000L))
    }

    @Test fun userInteractionPauseAndDisabledSettingAlwaysWin() {
        assertFalse(recover(enabled = false))
        assertFalse(recover(huyaFlv = false))
        assertFalse(recover(playing = false))
        assertFalse(recover(deferred = true))
        assertFalse(recover(age = -1L))
    }

    @Test fun extraAudioRequestsHaveAHardBudget() {
        assertTrue(recover(attempts = 2))
        assertFalse(recover(attempts = 3))
        assertFalse(recover(attempts = Int.MAX_VALUE))
    }
}

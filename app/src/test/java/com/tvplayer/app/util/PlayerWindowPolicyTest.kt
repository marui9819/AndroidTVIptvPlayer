package com.tvplayer.app.util

import org.junit.Assert.*
import org.junit.Test

class PlayerWindowPolicyTest {
    @Test fun pipRequiresPhoneApi26AndDeviceSupport() {
        assertTrue(PlayerWindowPolicy.supportsPip(false, 26, true))
        assertTrue(PlayerWindowPolicy.supportsPip(false, 34, true))
        assertFalse(PlayerWindowPolicy.supportsPip(false, 25, true))
        assertFalse(PlayerWindowPolicy.supportsPip(false, 34, false))
        assertFalse(PlayerWindowPolicy.supportsPip(true, 34, true))
        assertFalse(PlayerWindowPolicy.supportsPip(true, 23, false))
    }
    @Test fun enteringPipAndActivePipDoNotPauseButNormalBackgroundDoes() {
        assertTrue(PlayerWindowPolicy.keepPlayingOnPause(false, true, false, false))
        assertTrue(PlayerWindowPolicy.keepPlayingOnPause(false, false, true, false))
        assertFalse(PlayerWindowPolicy.keepPlayingOnPause(false, false, false, false))
        assertFalse(PlayerWindowPolicy.keepPlayingOnPause(false, true, true, true))
        assertFalse(PlayerWindowPolicy.keepPlayingOnPause(true, true, true, false))
    }
}

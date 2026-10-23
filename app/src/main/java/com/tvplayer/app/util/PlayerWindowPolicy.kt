package com.tvplayer.app.util

internal object PlayerWindowPolicy {
    fun supportsPip(tv: Boolean, sdk: Int, feature: Boolean) = !tv && sdk >= 26 && feature
    fun keepPlayingOnPause(tv: Boolean, inPip: Boolean, enteringPip: Boolean, finishing: Boolean) =
        !tv && !finishing && (inPip || enteringPip)
}

package com.tvplayer.app.util

/** Content checks, not an energy/silence detector. Quiet programmes must not trigger renewals. */
internal object PlaybackProtectionPolicy {
    const val MAX_AUDIO_RECOVERIES = 3
    const val PRECHECK_TIMEOUT_MS = 12_000L

    fun acceptsCandidate(isFlv: Boolean, audioFlag: Boolean?, audioPayload: Boolean, keyframe: Boolean) =
        isFlv && audioFlag != false && audioPayload && keyframe

    fun shouldRecoverAudio(
        enabled: Boolean,
        isHuyaFlv: Boolean,
        readyAndPlaying: Boolean,
        interactionDeferred: Boolean,
        sourceAgeMs: Long,
        supportedTracks: Int,
        audioClockAdvanced: Boolean,
        audiblePcmObserved: Boolean,
        attempts: Int
    ): Boolean {
        if (!enabled || !isHuyaFlv || !readyAndPlaying || interactionDeferred ||
            audiblePcmObserved || attempts >= MAX_AUDIO_RECOVERIES
        ) return false
        // AudioTrack consumption is also positive evidence on phones, where
        // offload/passthrough can bypass a PCM observer. No energy is NOT a fault.
        if (supportedTracks > 0 && audioClockAdvanced) return false
        return sourceAgeMs >= if (supportedTracks <= 0) 3_000L else 8_000L
    }
}

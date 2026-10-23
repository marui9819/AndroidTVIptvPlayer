package com.tvplayer.app.util

import kotlin.math.min
import kotlin.math.roundToInt

/** Uses the displayed viewport, not stream resolution or the device's full screen. */
internal object VideoOverlayPolicy {
    data class Sizes(
        val scale: Float,
        val nameSp: Float,
        val clockSp: Float,
        val versionSp: Float,
        val nameInsetX: Int,
        val nameInsetY: Int,
        val cornerInset: Int,
        val namePaddingX: Int,
        val namePaddingY: Int,
        val cornerPaddingX: Int,
        val cornerPaddingY: Int,
        val gap: Int
    )

    fun sizes(widthPx: Int, heightPx: Int, density: Float): Sizes? {
        if (widthPx <= 0 || heightPx <= 0 || !density.isFinite() || density <= 0f) return null
        // 640 x 360 dp is the full-size reference. Large TVs retain their
        // existing 22/18/12 sp badges; a phone's embedded video scales down.
        val scale = min(widthPx / density / 640f, heightPx / density / 360f).coerceIn(0.5f, 1f)
        fun px(dp: Float) = (dp * scale * density).roundToInt()
        return Sizes(
            scale, (22f * scale).coerceAtLeast(12f), (18f * scale).coerceAtLeast(10f),
            (12f * scale).coerceAtLeast(9f),
            px(28f), px(24f), px(16f), px(14f), px(9f), px(10f), px(6f), px(12f)
        )
    }

    /** Reserve the actual right badge width, including padding, even for large system fonts. */
    fun nameMaxWidth(widthPx: Int, density: Float, sizes: Sizes, cornerWidthPx: Int): Int {
        val right = if (cornerWidthPx > 0) sizes.cornerInset + cornerWidthPx + sizes.gap else sizes.cornerInset
        val available = (widthPx - sizes.nameInsetX - right).coerceAtLeast(1)
        return min(min((700f * density).roundToInt(), (widthPx * 0.62f).roundToInt()), available)
            .coerceAtLeast(1)
    }
}

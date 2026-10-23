package com.tvplayer.app.ui.player

import android.graphics.Rect
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.tvplayer.app.util.VideoOverlayPolicy
import kotlin.math.ceil

/** Layout-driven only: no polling, player/Surface changes, or per-video-frame work. */
internal class AdaptivePlaybackOverlay(
    private val viewport: FrameLayout,
    private val name: TextView,
    private val corner: View,
    private val clock: TextView,
    private val version: TextView,
    private val activeVideo: () -> View?,
    private val videoSurfaces: List<View>
) {
    private var posted = false
    private var disposed = false
    private var lastSizes: VideoOverlayPolicy.Sizes? = null
    private var lastFontScale = 0f
    private var lastBounds: Rect? = null
    private var lastNameWidth = -1
    private val update = Runnable { posted = false; if (!disposed) apply() }
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> requestUpdate() }

    init {
        clock.setSingleLine(true)
        version.setSingleLine(true)
        version.ellipsize = TextUtils.TruncateAt.END
        viewport.addOnLayoutChangeListener(layoutListener)
        corner.addOnLayoutChangeListener(layoutListener)
        videoSurfaces.forEach { it.addOnLayoutChangeListener(layoutListener) }
        requestUpdate()
    }

    fun requestUpdate() {
        if (disposed || posted) return
        posted = true
        viewport.post(update)
    }

    private fun apply() {
        val metrics = viewport.resources.displayMetrics
        val density = metrics.density
        // FIT may add letter/pillarboxing, ZOOM may extend beyond the root.
        // Keep both badges inside the visible video rectangle. Reading a View
        // rectangle does not touch the decoder or SurfaceHolder geometry.
        val bounds = Rect(0, 0, viewport.width, viewport.height)
        activeVideo()?.takeIf { it.width > 0 && it.height > 0 }?.let { video ->
            val videoBounds = Rect(0, 0, video.width, video.height)
            viewport.offsetDescendantRectToMyCoords(video, videoBounds)
            if (videoBounds.intersect(bounds)) bounds.set(videoBounds)
        }
        val sizes = VideoOverlayPolicy.sizes(bounds.width(), bounds.height(), density) ?: return
        val fontScale = viewport.resources.configuration.fontScale
        val changed = sizes != lastSizes || fontScale != lastFontScale || bounds != lastBounds
        if (changed) {
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizes.nameSp)
            clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizes.clockSp)
            version.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizes.versionSp)
            name.setPadding(sizes.namePaddingX, sizes.namePaddingY, sizes.namePaddingX, sizes.namePaddingY)
            corner.setPadding(sizes.cornerPaddingX, sizes.cornerPaddingY, sizes.cornerPaddingX, sizes.cornerPaddingY)
            val rtl = viewport.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val start = if (rtl) viewport.width - bounds.right else bounds.left
            val end = if (rtl) bounds.left else viewport.width - bounds.right
            (name.layoutParams as FrameLayout.LayoutParams).also {
                it.marginStart = start + sizes.nameInsetX
                it.topMargin = bounds.top + sizes.nameInsetY
                name.layoutParams = it
            }
            (corner.layoutParams as FrameLayout.LayoutParams).also {
                it.setMargins(bounds.left + sizes.cornerInset, bounds.top + sizes.cornerInset,
                    viewport.width - bounds.right + sizes.cornerInset, sizes.cornerInset)
                it.marginStart = start + sizes.cornerInset
                it.marginEnd = end + sizes.cornerInset
                corner.layoutParams = it
            }
            lastSizes = sizes
            lastFontScale = fontScale
            lastBounds = Rect(bounds)
        }
        // A long version label must not make the two corners overlap. Measure
        // text with the current font (including accessibility font scaling).
        val maxCornerText = (bounds.width() * 0.4f).toInt().coerceAtLeast(1)
        if (clock.maxWidth != maxCornerText) clock.maxWidth = maxCornerText
        if (version.maxWidth != maxCornerText) version.maxWidth = maxCornerText
        val cornerTextWidth = listOf(clock, version).filter { it.visibility == View.VISIBLE }
            .maxOfOrNull { ceil(it.paint.measureText(it.text.toString())).toInt().coerceAtMost(maxCornerText) } ?: 0
        val cornerWidth = if (corner.visibility == View.VISIBLE && cornerTextWidth > 0)
            cornerTextWidth + corner.paddingLeft + corner.paddingRight else 0
        val maxName = VideoOverlayPolicy.nameMaxWidth(bounds.width(), density, sizes, cornerWidth)
        if (name.maxWidth != maxName) name.maxWidth = maxName
        if (changed || lastNameWidth != maxName) {
            Log.i("PlaybackOverlay", "viewport=${viewport.width}x${viewport.height} video=$bounds scale=${sizes.scale} " +
                "nameSp=${sizes.nameSp} clockSp=${sizes.clockSp} versionSp=${sizes.versionSp} " +
                "namePx=${name.textSize} clockPx=${clock.textSize} maxNamePx=$maxName")
            lastNameWidth = maxName
        }
    }

    fun dispose() {
        disposed = true
        viewport.removeCallbacks(update)
        viewport.removeOnLayoutChangeListener(layoutListener)
        corner.removeOnLayoutChangeListener(layoutListener)
        videoSurfaces.forEach { it.removeOnLayoutChangeListener(layoutListener) }
    }
}

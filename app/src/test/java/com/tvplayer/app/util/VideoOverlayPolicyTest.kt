package com.tvplayer.app.util

import org.junit.Assert.*
import org.junit.Test

class VideoOverlayPolicyTest {
    @Test fun embeddedPhoneVideoIsSmallerThanFullscreen() {
        val portrait = VideoOverlayPolicy.sizes(1080, 607, 2.75f)!!
        val fullscreen = VideoOverlayPolicy.sizes(2280, 1080, 2.75f)!!
        assertEquals(13.49f, portrait.nameSp, 0.05f)
        assertEquals(11.04f, portrait.clockSp, 0.05f)
        assertEquals(9f, portrait.versionSp, 0f)
        assertEquals(22f, fullscreen.nameSp, 0f)
        assertTrue(portrait.nameInsetX < fullscreen.nameInsetX)
        assertTrue(portrait.namePaddingY < fullscreen.namePaddingY)
    }

    @Test fun sameLogicalViewportHasSameFontRegardlessOfPixelDensity() {
        val mdpi = VideoOverlayPolicy.sizes(384, 216, 1f)!!
        val highDpi = VideoOverlayPolicy.sizes(1152, 648, 3f)!!
        assertEquals(mdpi.scale, highDpi.scale, 0f)
        assertEquals(mdpi.nameSp, highDpi.nameSp, 0f)
        assertEquals(mdpi.clockSp, highDpi.clockSp, 0f)
        assertTrue(kotlin.math.abs(mdpi.cornerInset * 3 - highDpi.cornerInset) <= 1)
    }

    @Test fun tvAndLargeWindowsKeepExistingUpperBound() {
        for ((w, h, d) in listOf(Triple(1920, 1080, 1.5f), Triple(3840, 2160, 2f))) {
            val sizes = VideoOverlayPolicy.sizes(w, h, d)!!
            assertEquals(22f, sizes.nameSp, 0f)
            assertEquals(18f, sizes.clockSp, 0f)
            assertEquals(12f, sizes.versionSp, 0f)
            assertEquals((28f * d).toInt(), sizes.nameInsetX)
        }
    }

    @Test fun tinyOrNarrowWindowsHaveReadableLowerBound() {
        for ((w, h) in listOf(200 to 112, 1000 to 100, 100 to 1000)) {
            val sizes = VideoOverlayPolicy.sizes(w, h, 3f)!!
            assertEquals(12f, sizes.nameSp, 0f)
            assertEquals(10f, sizes.clockSp, 0f)
            assertEquals(9f, sizes.versionSp, 0f)
        }
    }

    @Test fun heightAlsoLimitsSizeForShortWideVideoWindows() {
        val normal = VideoOverlayPolicy.sizes(640, 360, 1f)!!
        val short = VideoOverlayPolicy.sizes(640, 180, 1f)!!
        assertEquals(1f, normal.scale, 0f)
        assertEquals(0.5f, short.scale, 0f)
    }

    @Test fun waitsForRealNonzeroViewport() {
        assertNull(VideoOverlayPolicy.sizes(0, 0, 1f))
        assertNull(VideoOverlayPolicy.sizes(1080, 0, 2.75f))
        assertNull(VideoOverlayPolicy.sizes(-1, 720, 1f))
        assertNull(VideoOverlayPolicy.sizes(1280, 720, 0f))
        assertNull(VideoOverlayPolicy.sizes(1280, 720, Float.NaN))
        assertNull(VideoOverlayPolicy.sizes(1280, 720, Float.POSITIVE_INFINITY))
    }

    @Test fun longNamesReserveVisibleCornerWidthAndNeverExceedViewport() {
        val sizes = VideoOverlayPolicy.sizes(1080, 607, 2.75f)!!
        val maxName = VideoOverlayPolicy.nameMaxWidth(1080, 2.75f, sizes, 400)
        assertTrue(maxName + sizes.nameInsetX + sizes.gap + 400 + sizes.cornerInset <= 1080)
        assertTrue(maxName < VideoOverlayPolicy.nameMaxWidth(1080, 2.75f, sizes, 0))
        assertEquals(1, VideoOverlayPolicy.nameMaxWidth(1080, 2.75f, sizes, 2000))
    }

    @Test fun repeatedModeChangesAreStatelessAndReversible() {
        val initial = VideoOverlayPolicy.sizes(1080, 607, 2.75f)
        repeat(10) {
            VideoOverlayPolicy.sizes(2280, 1080, 2.75f)
            VideoOverlayPolicy.sizes(616, 346, 2.75f)
            assertEquals(initial, VideoOverlayPolicy.sizes(1080, 607, 2.75f))
        }
    }
}

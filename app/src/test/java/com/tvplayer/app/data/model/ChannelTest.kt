package com.tvplayer.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelTest {

    @Test
    fun normalizeName_removesQualitySuffixesWithoutBreakingFhdOrUhd() {
        assertEquals("央视1", Channel.normalizeName("央视1 FHD"))
        assertEquals("央视1", Channel.normalizeName("央视1 UHD"))
        assertEquals("央视1", Channel.normalizeName("央视1 HD"))
        assertEquals("央视1", Channel.normalizeName("央视1 高清"))
    }

    @Test
    fun normalizeName_isLocaleIndependent() {
        assertEquals("CCTV5", Channel.normalizeName("cctv5 hd"))
    }
}

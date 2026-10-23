package com.tvplayer.app.ui.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsSectionTest {
    @Test fun sectionsHaveStableUniqueKeysAndRestoreSafely() {
        assertEquals(6, SettingsSection.values().size)
        assertEquals(6, SettingsSection.values().map { it.key }.distinct().size)
        SettingsSection.values().forEach {
            assertEquals(it, SettingsSection.fromKey(it.key))
            assertTrue(it.title.isNotBlank())
            assertTrue(it.description.isNotBlank())
        }
        assertNull(SettingsSection.fromKey(null))
        assertNull(SettingsSection.fromKey("unknown"))
    }
}

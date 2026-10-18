package com.cellscope.drive

import androidx.appcompat.app.AppCompatDelegate
import com.cellscope.drive.ui.theme.AppThemeMode
import org.junit.Assert.*
import org.junit.Test

class AppThemeManagerTest {

    @Test
    fun testAppThemeMode_MappingFromValue() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromValue(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM))
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.fromValue(AppCompatDelegate.MODE_NIGHT_NO))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromValue(AppCompatDelegate.MODE_NIGHT_YES))
    }

    @Test
    fun testAppThemeMode_FallbackToSystemForUnknown() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromValue(9999))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromValue(-99))
    }

    @Test
    fun testAppThemeMode_DisplayNames() {
        assertEquals("System Default", AppThemeMode.SYSTEM.displayName)
        assertEquals("Light Mode", AppThemeMode.LIGHT.displayName)
        assertEquals("Dark Mode", AppThemeMode.DARK.displayName)
    }
}

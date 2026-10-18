package com.cellscope.drive.ui.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

enum class AppThemeMode(val value: Int, val displayName: String) {
    SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, "System Default"),
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO, "Light Mode"),
    DARK(AppCompatDelegate.MODE_NIGHT_YES, "Dark Mode");

    companion object {
        fun fromValue(value: Int): AppThemeMode {
            return entries.firstOrNull { it.value == value } ?: SYSTEM
        }
    }
}

object AppThemeManager {
    private const val PREFS_NAME = "cellscope_theme_preferences"
    private const val KEY_THEME_MODE = "key_theme_mode"

    fun getThemeMode(context: Context): AppThemeMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getInt(KEY_THEME_MODE, AppThemeMode.SYSTEM.value)
        return AppThemeMode.fromValue(value)
    }

    fun setThemeMode(context: Context, mode: AppThemeMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_THEME_MODE, mode.value).apply()
        applyTheme(mode)
    }

    fun applyTheme(mode: AppThemeMode) {
        AppCompatDelegate.setDefaultNightMode(mode.value)
    }

    fun applySavedTheme(context: Context) {
        val mode = getThemeMode(context)
        applyTheme(mode)
    }
}

package com.docscan.util

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode {
    LIGHT,
    DARK,
    SYSTEM_DEFAULT;

    companion object {
        fun fromString(value: String?): AppThemeMode {
            return when (value?.uppercase()) {
                "LIGHT" -> LIGHT
                "DARK" -> DARK
                "SYSTEM_DEFAULT", "SYSTEM" -> SYSTEM_DEFAULT
                else -> SYSTEM_DEFAULT
            }
        }
    }
}

object ThemeManager {
    private const val PREFS_NAME = "app_settings_prefs"
    private const val KEY_THEME_MODE = "app_theme_mode"

    private val _themeMode = MutableStateFlow(AppThemeMode.SYSTEM_DEFAULT)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM_DEFAULT.name)
        _themeMode.value = AppThemeMode.fromString(saved)
    }

    fun setThemeMode(context: Context, mode: AppThemeMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    @Composable
    fun isDarkThemeActive(mode: AppThemeMode): Boolean {
        return when (mode) {
            AppThemeMode.DARK -> true
            AppThemeMode.LIGHT -> false
            AppThemeMode.SYSTEM_DEFAULT -> isSystemInDarkTheme()
        }
    }
}

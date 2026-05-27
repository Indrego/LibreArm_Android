package com.ptylr.librearm.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Typed wrapper around the app's SharedPreferences. Keys live here so callers
 * don't reach into the underlying file directly.
 */
class Preferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var autoSaveToHealth: Boolean
        get() = prefs.getBoolean(KEY_AUTO_HEALTH, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_HEALTH, value) }

    var averageMode: Boolean
        get() = prefs.getBoolean(KEY_AVERAGE_THREE, false)
        set(value) = prefs.edit { putBoolean(KEY_AVERAGE_THREE, value) }

    var delayBetweenRunsSeconds: Int
        get() = prefs.getInt(KEY_DELAY_BETWEEN_RUNS, DEFAULT_DELAY_SECONDS)
        set(value) = prefs.edit { putInt(KEY_DELAY_BETWEEN_RUNS, value) }

    companion object {
        private const val PREFS_NAME = "librearm_prefs"
        private const val KEY_AUTO_HEALTH = "pref_auto_health"
        private const val KEY_AVERAGE_THREE = "pref_average_three"
        private const val KEY_DELAY_BETWEEN_RUNS = "pref_delay_between_runs"
        const val DEFAULT_DELAY_SECONDS = 30
    }
}

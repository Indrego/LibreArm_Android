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

    init {
        migrateAverageBooleanToCount()
    }

    var autoSaveToHealth: Boolean
        get() = prefs.getBoolean(KEY_AUTO_HEALTH, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_HEALTH, value) }

    var readingsCount: Int
        get() = prefs.getInt(KEY_READINGS_COUNT, DEFAULT_READINGS_COUNT).coerceIn(1, 3)
        set(value) = prefs.edit { putInt(KEY_READINGS_COUNT, value.coerceIn(1, 3)) }

    var delayBetweenRunsSeconds: Int
        get() = prefs.getInt(KEY_DELAY_BETWEEN_RUNS, DEFAULT_DELAY_SECONDS)
        set(value) = prefs.edit { putInt(KEY_DELAY_BETWEEN_RUNS, value) }

    /**
     * One-time migration from the boolean `pref_average_three` (true → 3, false → 1)
     * to the new integer `pref_readings_count`. Safe to call on every launch — only
     * fires when the new key is missing and the old key is present.
     */
    private fun migrateAverageBooleanToCount() {
        if (prefs.contains(KEY_READINGS_COUNT)) return
        if (!prefs.contains(KEY_LEGACY_AVERAGE_THREE)) return
        val wasAveraging = prefs.getBoolean(KEY_LEGACY_AVERAGE_THREE, false)
        prefs.edit {
            putInt(KEY_READINGS_COUNT, if (wasAveraging) 3 else 1)
            remove(KEY_LEGACY_AVERAGE_THREE)
        }
    }

    companion object {
        private const val PREFS_NAME = "librearm_prefs"
        private const val KEY_AUTO_HEALTH = "pref_auto_health"
        private const val KEY_LEGACY_AVERAGE_THREE = "pref_average_three"
        private const val KEY_READINGS_COUNT = "pref_readings_count"
        private const val KEY_DELAY_BETWEEN_RUNS = "pref_delay_between_runs"
        const val DEFAULT_DELAY_SECONDS = 30
        const val DEFAULT_READINGS_COUNT = 1
    }
}

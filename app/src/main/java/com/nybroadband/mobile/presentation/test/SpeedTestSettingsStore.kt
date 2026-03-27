package com.nybroadband.mobile.presentation.test

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SharedPreferences-backed store for Test Settings.
 *
 * Persists all user preferences shown in [SpeedTestSettingsFragment]:
 *   - Data use counters (all-time + current period, split by cellular vs Wi-Fi)
 *   - Default tag (Indoors / Outdoors / Driving / Other)
 *   - Use Tags / Ask Before Each Test toggles
 *   - Download and upload duration (7–15 s)
 *   - Server selection (null = auto)
 *   - Theme (default / glow / stealth)
 *   - Speed unit (kbps / mbps) and distance unit (miles / km)
 *
 * All writes use [apply] (async) for non-blocking UI interaction.
 */
@Singleton
class SpeedTestSettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Default Tag ────────────────────────────────────────────────────────────

    var defaultTag: String
        get() = prefs.getString(KEY_DEFAULT_TAG, TAG_INDOORS) ?: TAG_INDOORS
        set(v) = prefs.edit().putString(KEY_DEFAULT_TAG, v).apply()

    var useTags: Boolean
        get() = prefs.getBoolean(KEY_USE_TAGS, true)
        set(v) = prefs.edit().putBoolean(KEY_USE_TAGS, v).apply()

    var askBeforeTest: Boolean
        get() = prefs.getBoolean(KEY_ASK_BEFORE, false)
        set(v) = prefs.edit().putBoolean(KEY_ASK_BEFORE, v).apply()

    // ── Data limits ────────────────────────────────────────────────────────────

    var setCellularDataLimit: Boolean
        get() = prefs.getBoolean(KEY_SET_CELLULAR_LIMIT, false)
        set(v) = prefs.edit().putBoolean(KEY_SET_CELLULAR_LIMIT, v).apply()

    var resetUsageMonthly: Boolean
        get() = prefs.getBoolean(KEY_RESET_MONTHLY, false)
        set(v) = prefs.edit().putBoolean(KEY_RESET_MONTHLY, v).apply()

    // ── Test duration ──────────────────────────────────────────────────────────

    var downloadDurationSeconds: Int
        get() = prefs.getInt(KEY_DOWNLOAD_DURATION, 7)
        set(v) = prefs.edit().putInt(KEY_DOWNLOAD_DURATION, v).apply()

    var uploadDurationSeconds: Int
        get() = prefs.getInt(KEY_UPLOAD_DURATION, 7)
        set(v) = prefs.edit().putInt(KEY_UPLOAD_DURATION, v).apply()

    // ── Server ─────────────────────────────────────────────────────────────────

    var selectedServerId: String?
        get() = prefs.getString(KEY_SERVER_ID, null)
        set(v) {
            if (v == null) prefs.edit().remove(KEY_SERVER_ID).apply()
            else prefs.edit().putString(KEY_SERVER_ID, v).apply()
        }

    // ── Appearance ─────────────────────────────────────────────────────────────

    var theme: String
        get() = prefs.getString(KEY_THEME, THEME_DEFAULT) ?: THEME_DEFAULT
        set(v) = prefs.edit().putString(KEY_THEME, v).apply()

    var speedUnit: String
        get() = prefs.getString(KEY_SPEED_UNIT, UNIT_MBPS) ?: UNIT_MBPS
        set(v) = prefs.edit().putString(KEY_SPEED_UNIT, v).apply()

    var distanceUnit: String
        get() = prefs.getString(KEY_DIST_UNIT, UNIT_MILES) ?: UNIT_MILES
        set(v) = prefs.edit().putString(KEY_DIST_UNIT, v).apply()

    // ── Data usage counters ─────────────────────────────────────────────────────

    /** Accumulated bytes across all tests ever run on this device. */
    var allTimeDataBytes: Long
        get() = prefs.getLong(KEY_ALL_TIME_BYTES, 0L)
        set(v) = prefs.edit().putLong(KEY_ALL_TIME_BYTES, v).apply()

    /** Accumulated bytes since the last [resetPeriodUsage] call. */
    var periodDataBytes: Long
        get() = prefs.getLong(KEY_PERIOD_BYTES, 0L)
        set(v) = prefs.edit().putLong(KEY_PERIOD_BYTES, v).apply()

    var periodCellularBytes: Long
        get() = prefs.getLong(KEY_PERIOD_CELLULAR, 0L)
        set(v) = prefs.edit().putLong(KEY_PERIOD_CELLULAR, v).apply()

    var periodWifiBytes: Long
        get() = prefs.getLong(KEY_PERIOD_WIFI, 0L)
        set(v) = prefs.edit().putLong(KEY_PERIOD_WIFI, v).apply()

    /**
     * Called after each completed test.
     * [bytes] is the measured delta from [android.net.TrafficStats] for this app's UID.
     * [wasOnWifi] splits the amount between Wi-Fi and cellular period buckets.
     */
    fun addTestDataBytes(bytes: Long, wasOnWifi: Boolean) {
        if (bytes <= 0L) return
        allTimeDataBytes += bytes
        periodDataBytes  += bytes
        if (wasOnWifi) periodWifiBytes    += bytes
        else           periodCellularBytes += bytes
    }

    /** Resets current-period counters (called from Reset Usage button). */
    fun resetPeriodUsage() {
        prefs.edit()
            .putLong(KEY_PERIOD_BYTES, 0L)
            .putLong(KEY_PERIOD_CELLULAR, 0L)
            .putLong(KEY_PERIOD_WIFI, 0L)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "speed_test_settings"

        // Keys
        private const val KEY_DEFAULT_TAG         = "default_tag"
        private const val KEY_USE_TAGS            = "use_tags"
        private const val KEY_ASK_BEFORE          = "ask_before_test"
        private const val KEY_SET_CELLULAR_LIMIT  = "set_cellular_data_limit"
        private const val KEY_RESET_MONTHLY       = "reset_usage_monthly"
        private const val KEY_DOWNLOAD_DURATION   = "download_duration"
        private const val KEY_UPLOAD_DURATION     = "upload_duration"
        private const val KEY_SERVER_ID           = "selected_server_id"
        private const val KEY_THEME               = "theme"
        private const val KEY_SPEED_UNIT          = "speed_unit"
        private const val KEY_DIST_UNIT           = "distance_unit"
        private const val KEY_ALL_TIME_BYTES      = "all_time_data_bytes"
        private const val KEY_PERIOD_BYTES        = "period_data_bytes"
        private const val KEY_PERIOD_CELLULAR     = "period_cellular_bytes"
        private const val KEY_PERIOD_WIFI         = "period_wifi_bytes"

        // Tag values
        const val TAG_INDOORS  = "indoors"
        const val TAG_OUTDOORS = "outdoors"
        const val TAG_DRIVING  = "driving"
        const val TAG_OTHER    = "other"

        // Theme values
        const val THEME_DEFAULT = "default"
        const val THEME_GLOW    = "glow"
        const val THEME_STEALTH = "stealth"

        // Unit values
        const val UNIT_KBPS  = "kbps"
        const val UNIT_MBPS  = "mbps"
        const val UNIT_MILES = "miles"
        const val UNIT_KM    = "km"
    }
}
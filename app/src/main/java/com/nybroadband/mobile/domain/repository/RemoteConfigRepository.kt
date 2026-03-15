package com.nybroadband.mobile.domain.repository

import com.nybroadband.mobile.data.local.db.entity.RemoteConfigCacheEntity
import kotlinx.coroutines.flow.Flow

/**
 * Domain contract for remote configuration.
 *
 * Reads are served from the local [remote_config_cache] Room table (synchronous,
 * offline-safe). Background refresh calls Firebase Remote Config SDK and writes
 * the activated values into the cache.
 *
 * Typed accessors (getString / getInt / getBoolean) insulate callers from raw
 * JSON and return safe defaults on missing or unparseable values.
 *
 * Key constants are defined here to prevent hard-coded strings at call sites.
 */
interface RemoteConfigRepository {

    companion object {
        /** JSON array of TestPreset objects — drives recurring and manual test modes. */
        const val KEY_PRESET_DEFINITIONS     = "preset_definitions"

        /** Max GeoJSON features rendered on the map. Default: 500. */
        const val KEY_MAP_POINT_LIMIT        = "map_point_limit"

        /** SyncWorker upload batch size. Default: 50. */
        const val KEY_SYNC_BATCH_SIZE        = "sync_batch_size"

        /**
         * Feature flag — speed test engine.
         * Must remain false until NDT7/Ookla integration is explicitly scheduled.
         * Default: false.
         */
        const val KEY_FEATURE_SPEED_TEST     = "feature_flag_speed_test"

        /**
         * Minimum supported app versionCode. App shows a force-upgrade dialog
         * when the running version is below this value. Default: 0 (no gate).
         */
        const val KEY_MIN_APP_VERSION        = "min_app_version"
    }

    /**
     * Observe a raw cache entry — useful for reactive feature-flag gating.
     * Emits null if the key has never been fetched.
     */
    fun observeRaw(key: String): Flow<RemoteConfigCacheEntity?>

    /** Returns cached string value, or [default] if absent. */
    suspend fun getString(key: String, default: String = ""): String

    /** Returns cached int value, or [default] if absent or unparseable. */
    suspend fun getInt(key: String, default: Int = 0): Int

    /** Returns cached boolean value, or [default] if absent or unparseable. */
    suspend fun getBoolean(key: String, default: Boolean = false): Boolean

    /**
     * Trigger a Firebase Remote Config fetch + activate.
     * On success, writes all activated key-value pairs to the local cache
     * and returns true. On network/Firebase failure, leaves the cache
     * unchanged and returns false.
     */
    suspend fun refresh(): Boolean

    /**
     * Force-invalidate a single cached key so the next [refresh] replaces it.
     * Useful after the backend publishes a breaking config change mid-session.
     */
    suspend fun invalidate(key: String)
}

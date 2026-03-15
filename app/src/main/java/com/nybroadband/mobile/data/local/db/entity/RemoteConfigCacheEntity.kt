package com.nybroadband.mobile.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local shadow of Firebase Remote Config key-value pairs.
 *
 * Firebase Remote Config SDK maintains its own internal cache, but this Room
 * table lets coroutine/suspend callers read config values synchronously from
 * the database without bridging to Firebase's callback-based API.
 *
 * Values are stored as raw JSON strings. The consuming repository is
 * responsible for deserializing to Boolean, Int, String, or a data class.
 * See [RemoteConfigRepository] for typed accessors and key constants.
 *
 * Key examples:
 *   "preset_definitions"        → JSON array of TestPreset objects
 *   "map_point_limit"           → "500"
 *   "sync_batch_size"           → "50"
 *   "feature_flag_speed_test"   → "false"   (deferred — keep false for MVP)
 *   "min_app_version"           → "3"       (versionCode; used for force-upgrade gate)
 *
 * Cache expiry:
 *   [expiresAtMs] = 0 → no expiry; only replaced by an explicit Remote Config fetch.
 *   [expiresAtMs] > 0 → [RemoteConfigRepository.refresh] triggers a Firebase fetch
 *                        when the current time exceeds this value.
 */
@Entity(tableName = "remote_config_cache")
data class RemoteConfigCacheEntity(

    @PrimaryKey val key: String,
    val value: String,                              // JSON-serialized value
    val fetchedAtMs: Long,                          // epoch ms of the Firebase fetch
    val expiresAtMs: Long = 0L                      // 0 = no expiry
)

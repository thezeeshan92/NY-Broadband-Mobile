package com.nybroadband.mobile.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nybroadband.mobile.data.local.db.entity.RemoteConfigCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RemoteConfigCacheDao {

    /** Insert or replace — called after every Firebase Remote Config activate(). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: RemoteConfigCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(entries: List<RemoteConfigCacheEntity>)

    @Query("SELECT * FROM remote_config_cache WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): RemoteConfigCacheEntity?

    /** Observe a single key — useful for driving reactive UI from a config flag. */
    @Query("SELECT * FROM remote_config_cache WHERE `key` = :key LIMIT 1")
    fun observe(key: String): Flow<RemoteConfigCacheEntity?>

    @Query("SELECT * FROM remote_config_cache")
    suspend fun getAll(): List<RemoteConfigCacheEntity>

    /** Force-invalidate a single key; next [RemoteConfigRepository.refresh] replaces it. */
    @Query("DELETE FROM remote_config_cache WHERE `key` = :key")
    suspend fun remove(key: String)

    /**
     * Wipe and replace the entire cache after a successful Firebase fetch.
     * Call inside a transaction with [putAll] to avoid a read-your-own-writes gap.
     */
    @Query("DELETE FROM remote_config_cache")
    suspend fun clearAll()

    /**
     * Returns keys where [expiresAtMs] > 0 and the cache has passed its TTL.
     * RemoteConfigRepository uses this to decide whether a proactive refresh is needed.
     */
    @Query("""
        SELECT `key` FROM remote_config_cache
        WHERE expiresAtMs > 0
          AND expiresAtMs < :nowMs
    """)
    suspend fun getExpiredKeys(nowMs: Long = System.currentTimeMillis()): List<String>
}

package com.nybroadband.mobile.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nybroadband.mobile.data.local.db.entity.ServerDefinitionEntity

@Dao
interface ServerDefinitionDao {

    /** Insert or replace — used by Remote Config refresh to update endpoints. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: ServerDefinitionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(servers: List<ServerDefinitionEntity>)

    /**
     * Active endpoints in priority order (lowest value = highest priority).
     * SyncWorker iterates this list for failover.
     */
    @Query("SELECT * FROM server_definitions WHERE isActive = 1 ORDER BY priority ASC")
    suspend fun getActive(): List<ServerDefinitionEntity>

    @Query("SELECT * FROM server_definitions ORDER BY priority ASC")
    suspend fun getAll(): List<ServerDefinitionEntity>

    @Query("SELECT * FROM server_definitions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ServerDefinitionEntity?

    @Query("UPDATE server_definitions SET isActive = :isActive WHERE id = :id")
    suspend fun setActive(id: String, isActive: Boolean)

    @Delete
    suspend fun delete(server: ServerDefinitionEntity)
}

package com.nybroadband.mobile.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nybroadband.mobile.data.local.db.entity.DeadZoneReportEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeadZoneDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(report: DeadZoneReportEntity)

    /** Full list for the Results screen "Dead Zones" filter. */
    @Query("SELECT * FROM dead_zone_reports ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<DeadZoneReportEntity>>

    @Query("SELECT COUNT(*) FROM dead_zone_reports")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM dead_zone_reports WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DeadZoneReportEntity?

    /**
     * SyncWorker batch — oldest PENDING records first.
     * Default limit keeps batch payloads small; worker loops until queue drains.
     */
    @Query("""
        SELECT * FROM dead_zone_reports
        WHERE syncStatus = 'PENDING'
        ORDER BY timestamp ASC
        LIMIT :limit
    """)
    suspend fun getPending(limit: Int = 50): List<DeadZoneReportEntity>

    @Query("""
        UPDATE dead_zone_reports
        SET syncStatus = 'UPLOADED',
            uploadedAt = :now,
            remoteId   = :remoteId
        WHERE id = :id
    """)
    suspend fun markUploaded(id: String, remoteId: String, now: Long = System.currentTimeMillis())

    @Query("""
        UPDATE dead_zone_reports
        SET syncStatus     = 'FAILED',
            uploadAttempts = uploadAttempts + 1
        WHERE id IN (:ids)
    """)
    suspend fun markFailed(ids: List<String>)

    @Delete
    suspend fun delete(report: DeadZoneReportEntity)

    @Query("DELETE FROM dead_zone_reports WHERE id = :id")
    suspend fun deleteById(id: String)
}

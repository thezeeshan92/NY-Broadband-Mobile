package com.nybroadband.mobile.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nybroadband.mobile.data.local.db.entity.MeasurementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(measurement: MeasurementEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(measurements: List<MeasurementEntity>)

    /**
     * Lightweight projection for map rendering.
     * Only fetches columns needed to draw circles on the map.
     * Limited to 500 most-recent accurate points to keep GeoJSON source manageable.
     */
    @Query("""
        SELECT id, lat, lon, signalTier, sampleType, timestamp
        FROM measurements
        WHERE gpsAccuracyMeters <= 50
        ORDER BY timestamp DESC
        LIMIT 500
    """)
    fun observeMapPoints(): Flow<List<MapPointProjection>>

    /** Results list — filtered by sampleType / deadZoneType via the :filter string. */
    @Query("""
        SELECT * FROM measurements
        WHERE (:filter = 'ALL'
            OR (:filter = 'SPEED_TESTS' AND sampleType IN ('ACTIVE_MANUAL', 'ACTIVE_RECURRING'))
            OR (:filter = 'PASSIVE'     AND sampleType = 'PASSIVE')
            OR (:filter = 'DEAD_ZONES'  AND deadZoneType IS NOT NULL))
        ORDER BY timestamp DESC
    """)
    fun observeFiltered(filter: String): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM measurements WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MeasurementEntity?

    /** SyncWorker batch — oldest PENDING records first. */
    @Query("""
        SELECT * FROM measurements
        WHERE uploadStatus = 'PENDING'
        ORDER BY timestamp ASC
        LIMIT :limit
    """)
    suspend fun getPending(limit: Int = 100): List<MeasurementEntity>

    @Query("UPDATE measurements SET uploadStatus = 'UPLOADED', uploadedAt = :now WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<String>, now: Long = System.currentTimeMillis())

    @Query("UPDATE measurements SET uploadStatus = 'FAILED', uploadAttempts = uploadAttempts + 1 WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<String>)

    @Query("DELETE FROM measurements WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<String>)

    /** Monthly data usage for cap enforcement. Returns 0 if no records. */
    @Query("""
        SELECT COALESCE(SUM(COALESCE(bytesDownloaded, 0) + COALESCE(bytesUploaded, 0)), 0)
        FROM measurements
        WHERE sampleType != 'PASSIVE'
        AND timestamp >= :monthStart
    """)
    fun observeMonthlyDataUsageBytes(monthStart: Long): Flow<Long>

    @Query("SELECT COUNT(*) FROM measurements")
    fun observeTotalCount(): Flow<Int>
}

/** Lightweight projection used by MapRenderer — avoids loading full entity for map dots. */
data class MapPointProjection(
    val id: String,
    val lat: Double,
    val lon: Double,
    val signalTier: String,
    val sampleType: String,
    val timestamp: Long
)

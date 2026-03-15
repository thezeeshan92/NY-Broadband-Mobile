package com.nybroadband.mobile.domain.repository

import com.nybroadband.mobile.data.local.db.entity.DeadZoneReportEntity
import kotlinx.coroutines.flow.Flow

/**
 * Domain contract for dead zone report persistence.
 *
 * Reports are submitted from [DeadZoneFragment], written here, and uploaded
 * by SyncWorker. They are distinct from the deadZoneType flag on MeasurementEntity,
 * which marks auto-detected dead zones during passive collection.
 */
interface DeadZoneRepository {

    /** Full list ordered newest-first — drives the Results "Dead Zones" filter. */
    fun observeAll(): Flow<List<DeadZoneReportEntity>>

    /** Running count — used in Settings usage summary. */
    fun observeCount(): Flow<Int>

    suspend fun submit(report: DeadZoneReportEntity)

    suspend fun getById(id: String): DeadZoneReportEntity?

    /** Returns up to [limit] oldest PENDING reports for SyncWorker batch upload. */
    suspend fun getPendingForSync(limit: Int = 50): List<DeadZoneReportEntity>

    suspend fun markUploaded(id: String, remoteId: String)

    suspend fun markFailed(ids: List<String>)
}

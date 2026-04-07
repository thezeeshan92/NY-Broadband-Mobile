package com.nybroadband.mobile.data.repository

import com.nybroadband.mobile.data.local.db.dao.DeadZoneDao
import com.nybroadband.mobile.data.local.db.dao.SyncQueueDao
import com.nybroadband.mobile.data.local.db.entity.DeadZoneReportEntity
import com.nybroadband.mobile.data.local.db.entity.SyncQueueEntity
import com.nybroadband.mobile.domain.repository.DeadZoneRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first implementation of [DeadZoneRepository].
 *
 * Every [submit] call does two things atomically from the caller's perspective:
 *   1. Persists the report to Room (visible instantly in Results list).
 *   2. Enqueues an entry in sync_queue (SyncWorker uploads it when online).
 *
 * If the device is offline at submission time, the report is still safely
 * stored locally. SyncWorker will pick it up when connectivity resumes.
 *
 * The unique index on (entityType, entityId) in sync_queue means calling
 * [submit] more than once for the same report is safe — subsequent enqueue()
 * calls with OnConflictStrategy.IGNORE are no-ops.
 */
@Singleton
class DeadZoneRepositoryImpl @Inject constructor(
    private val deadZoneDao: DeadZoneDao,
    private val syncQueueDao: SyncQueueDao
) : DeadZoneRepository {

    override fun observeAll(): Flow<List<DeadZoneReportEntity>> =
        deadZoneDao.observeAll()

    override fun observeCount(): Flow<Int> =
        deadZoneDao.observeCount()

    /**
     * Persist the report locally and queue it for upload.
     *
     * [nextAttemptMs] is set to now so SyncWorker picks it up immediately
     * on the next run. No network call is made here — the caller is always offline-safe.
     */
    override suspend fun submit(report: DeadZoneReportEntity) {
        deadZoneDao.insert(report)
        val now = System.currentTimeMillis()
        syncQueueDao.enqueue(
            SyncQueueEntity(
                entityType    = ENTITY_TYPE,
                entityId      = report.id,
                createdAtMs   = now,
                nextAttemptMs = now     // eligible for upload immediately
            )
        )
    }

    override suspend fun getById(id: String): DeadZoneReportEntity? =
        deadZoneDao.getById(id)

    override suspend fun getPendingForSync(limit: Int): List<DeadZoneReportEntity> =
        deadZoneDao.getPending(limit)

    override suspend fun markUploaded(id: String, remoteId: String) =
        deadZoneDao.markUploaded(id, remoteId)

    override suspend fun markFailed(ids: List<String>) =
        deadZoneDao.markFailed(ids)

    override suspend fun delete(id: String) =
        deadZoneDao.deleteById(id)

    companion object {
        /** Matches SyncQueueEntity.entityType value for dead zone records. */
        const val ENTITY_TYPE = "DEAD_ZONE"
    }
}

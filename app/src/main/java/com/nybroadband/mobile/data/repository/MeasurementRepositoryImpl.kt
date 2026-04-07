package com.nybroadband.mobile.data.repository

import com.nybroadband.mobile.data.local.db.dao.MapPointProjection
import com.nybroadband.mobile.data.local.db.dao.MeasurementDao
import com.nybroadband.mobile.data.local.db.dao.SyncQueueDao
import com.nybroadband.mobile.data.local.db.entity.MeasurementEntity
import com.nybroadband.mobile.data.local.db.entity.SyncQueueEntity
import com.nybroadband.mobile.domain.repository.MeasurementRepository
import com.nybroadband.mobile.domain.repository.SyncRepository.Companion.ENTITY_MEASUREMENT
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first [MeasurementRepository] implementation.
 *
 * Every write enqueues the measurement in sync_queue so [SyncWorker] will
 * upload it when connectivity is available. The unique index on
 * (entityType, entityId) in sync_queue makes enqueue() calls idempotent —
 * safe to call on every save without risk of duplicate uploads.
 */
@Singleton
class MeasurementRepositoryImpl @Inject constructor(
    private val measurementDao: MeasurementDao,
    private val syncQueueDao: SyncQueueDao
) : MeasurementRepository {

    override fun observeMapPoints(): Flow<List<MapPointProjection>> =
        measurementDao.observeMapPoints()

    override fun observeFiltered(filter: String): Flow<List<MeasurementEntity>> =
        measurementDao.observeFiltered(filter)

    override fun observeTotalCount(): Flow<Int> =
        measurementDao.observeTotalCount()

    override fun observeMonthlyDataUsageBytes(monthStartMs: Long): Flow<Long> =
        measurementDao.observeMonthlyDataUsageBytes(monthStartMs)

    override suspend fun save(measurement: MeasurementEntity) {
        measurementDao.insert(measurement)
        enqueue(measurement.id)
    }

    override suspend fun saveAll(measurements: List<MeasurementEntity>) {
        measurementDao.insertAll(measurements)
        val now = System.currentTimeMillis()
        val queueItems = measurements.map { m ->
            SyncQueueEntity(
                entityType    = ENTITY_MEASUREMENT,
                entityId      = m.id,
                createdAtMs   = now,
                nextAttemptMs = now
            )
        }
        syncQueueDao.enqueueAll(queueItems)
    }

    override suspend fun getById(id: String): MeasurementEntity? =
        measurementDao.getById(id)

    override suspend fun getPendingForSync(limit: Int): List<MeasurementEntity> =
        measurementDao.getPending(limit)

    override suspend fun markUploaded(ids: List<String>) =
        measurementDao.markUploaded(ids)

    override suspend fun markFailed(ids: List<String>) =
        measurementDao.markFailed(ids)

    override suspend fun deleteAll(ids: List<String>) =
        measurementDao.deleteAll(ids)

    private suspend fun enqueue(id: String) {
        val now = System.currentTimeMillis()
        syncQueueDao.enqueue(
            SyncQueueEntity(
                entityType    = ENTITY_MEASUREMENT,
                entityId      = id,
                createdAtMs   = now,
                nextAttemptMs = now
            )
        )
    }
}

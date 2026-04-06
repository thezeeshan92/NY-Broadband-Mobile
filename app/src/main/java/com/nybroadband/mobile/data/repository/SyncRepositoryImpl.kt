package com.nybroadband.mobile.data.repository

import com.nybroadband.mobile.data.local.db.dao.SyncQueueDao
import com.nybroadband.mobile.data.local.db.entity.SyncQueueEntity
import com.nybroadband.mobile.domain.repository.SyncRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val syncQueueDao: SyncQueueDao
) : SyncRepository {

    override suspend fun enqueue(entityType: String, entityId: String) {
        val now = System.currentTimeMillis()
        syncQueueDao.enqueue(
            SyncQueueEntity(
                entityType    = entityType,
                entityId      = entityId,
                createdAtMs   = now,
                nextAttemptMs = now
            )
        )
    }

    override suspend fun enqueueAll(items: List<Pair<String, String>>) {
        val now = System.currentTimeMillis()
        syncQueueDao.enqueueAll(
            items.map { (type, id) ->
                SyncQueueEntity(
                    entityType    = type,
                    entityId      = id,
                    createdAtMs   = now,
                    nextAttemptMs = now
                )
            }
        )
    }

    override suspend fun getDue(nowMs: Long, limit: Int): List<SyncQueueEntity> =
        syncQueueDao.getDue(nowMs, limit)

    override suspend fun remove(entityType: String, entityIds: List<String>) =
        syncQueueDao.removeByEntityIds(entityIds, entityType)

    override suspend fun reschedule(
        queueId: Long,
        attemptCount: Int,
        nowMs: Long,
        errorCode: Int?,
        errorMessage: String?
    ) {
        val delay = min(
            SyncRepository.BASE_DELAY_MS * (1L shl attemptCount), // BASE * 2^n
            SyncRepository.MAX_DELAY_MS
        )
        syncQueueDao.updateRetry(
            queueId       = queueId,
            attempts      = attemptCount,
            nextAttemptMs = nowMs + delay,
            errorCode     = errorCode,
            errorMessage  = errorMessage?.take(255)
        )
    }

    override fun observeQueueDepth(): Flow<Int> =
        syncQueueDao.observeQueueDepth()

    override suspend fun pruneExhausted(nowMs: Long) =
        syncQueueDao.pruneExhausted(
            cutoffMs    = nowMs,
            maxAttempts = SyncRepository.MAX_ATTEMPTS
        )
}

package com.nybroadband.mobile.domain.repository

import com.nybroadband.mobile.data.local.db.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

/**
 * Domain contract for the retry-aware upload queue.
 *
 * SyncWorker calls [getDue] on each run, attempts uploads in batches,
 * then calls [remove] on success or [reschedule] on failure.
 *
 * Exponential backoff formula (implemented in SyncWorker):
 *   nextAttemptMs = nowMs + min(BASE_DELAY_MS * 2^attemptCount, MAX_DELAY_MS)
 *
 * After [MAX_ATTEMPTS] failures the record is left in the queue but will not
 * be retried until [pruneExhausted] clears it or the user force-syncs.
 */
interface SyncRepository {

    companion object {
        const val ENTITY_MEASUREMENT = "MEASUREMENT"
        const val ENTITY_DEAD_ZONE   = "DEAD_ZONE"

        const val BASE_DELAY_MS = 30_000L           // 30 seconds
        const val MAX_DELAY_MS  = 3_600_000L        // 1 hour cap
        const val MAX_ATTEMPTS  = 5
    }

    /**
     * Enqueue a single entity. Safe to call on every write — the unique
     * (entityType, entityId) index silently ignores duplicates.
     */
    suspend fun enqueue(entityType: String, entityId: String)

    /** Bulk enqueue after a batch write (e.g., passive collection flush). */
    suspend fun enqueueAll(items: List<Pair<String, String>>)

    /**
     * Records eligible for upload as of [nowMs].
     * Results are ordered oldest-first (FIFO within a worker run).
     */
    suspend fun getDue(nowMs: Long = System.currentTimeMillis(), limit: Int = 50): List<SyncQueueEntity>

    /** Remove from queue after successful upload. */
    suspend fun remove(entityType: String, entityIds: List<String>)

    /**
     * Reschedule with exponential backoff after a failed upload attempt.
     * Caller passes the [attemptCount] from the queue record + 1.
     */
    suspend fun reschedule(
        queueId: Long,
        attemptCount: Int,
        nowMs: Long = System.currentTimeMillis(),
        errorCode: Int? = null,
        errorMessage: String? = null
    )

    /** Pending count — surface as sync-pending badge in Settings. */
    fun observeQueueDepth(): Flow<Int>

    /**
     * Remove records that have hit [MAX_ATTEMPTS] and whose [nextAttemptMs]
     * is in the past. Called at the end of each SyncWorker run.
     */
    suspend fun pruneExhausted(nowMs: Long = System.currentTimeMillis())
}

package com.nybroadband.mobile.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nybroadband.mobile.data.local.db.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncQueueDao {

    /**
     * Insert or replace. REPLACE semantics allow SyncWorker to re-queue a record
     * with an updated [SyncQueueEntity.nextAttemptMs] in a single operation.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: SyncQueueEntity)

    /**
     * Bulk enqueue after a batch write (e.g., passive collection flush).
     * IGNORE semantics prevent clobbering an existing entry's attemptCount.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueueAll(items: List<SyncQueueEntity>)

    /**
     * Records eligible for upload right now.
     * Ordered by creation time so the oldest items upload first (FIFO within a run).
     */
    @Query("""
        SELECT * FROM sync_queue
        WHERE nextAttemptMs <= :nowMs
        ORDER BY createdAtMs ASC
        LIMIT :limit
    """)
    suspend fun getDue(nowMs: Long = System.currentTimeMillis(), limit: Int = 50): List<SyncQueueEntity>

    /** Remove successfully uploaded records from the queue. */
    @Query("""
        DELETE FROM sync_queue
        WHERE entityId IN (:entityIds)
          AND entityType = :entityType
    """)
    suspend fun removeByEntityIds(entityIds: List<String>, entityType: String)

    /**
     * Reschedule with updated backoff after a failed attempt.
     * Called by SyncWorker immediately after a batch failure.
     */
    @Query("""
        UPDATE sync_queue
        SET attemptCount     = :attempts,
            nextAttemptMs    = :nextAttemptMs,
            lastErrorCode    = :errorCode,
            lastErrorMessage = :errorMessage
        WHERE id = :queueId
    """)
    suspend fun updateRetry(
        queueId: Long,
        attempts: Int,
        nextAttemptMs: Long,
        errorCode: Int?,
        errorMessage: String?
    )

    /** Running queue depth — surface as pending badge in Settings. */
    @Query("SELECT COUNT(*) FROM sync_queue")
    fun observeQueueDepth(): Flow<Int>

    /**
     * Purge records that have exhausted retries and are older than [cutoffMs].
     * Called periodically by SyncWorker to prevent unbounded queue growth.
     * Default [maxAttempts] = 5 matches SyncRepository.MAX_ATTEMPTS.
     */
    @Query("""
        DELETE FROM sync_queue
        WHERE attemptCount >= :maxAttempts
          AND nextAttemptMs < :cutoffMs
    """)
    suspend fun pruneExhausted(cutoffMs: Long, maxAttempts: Int = 5)
}

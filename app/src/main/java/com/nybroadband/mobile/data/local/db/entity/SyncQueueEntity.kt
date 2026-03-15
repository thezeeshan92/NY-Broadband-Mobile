package com.nybroadband.mobile.data.local.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Retry-aware upload queue for offline-first sync.
 *
 * Each row represents one pending upload attempt for a specific entity.
 * SyncWorker queries rows where [nextAttemptMs] <= NOW, processes them in
 * batches, then either removes (success) or reschedules (failure) each entry.
 *
 * Exponential backoff:
 *   nextAttemptMs = min(BASE_DELAY_MS * 2^attemptCount, MAX_DELAY_MS)
 *   Constants defined in [SyncRepository] companion object.
 *
 * The unique index on (entityType, entityId) prevents duplicate queue entries
 * for the same entity — safe to call enqueue() on every write operation.
 *
 * Entity types:
 *   "MEASUREMENT" → references measurements.id
 *   "DEAD_ZONE"   → references dead_zone_reports.id
 */
@Entity(
    tableName = "sync_queue",
    indices = [
        Index(value = ["entityType", "entityId"], unique = true),
        Index(value = ["nextAttemptMs"])
    ]
)
data class SyncQueueEntity(

    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val entityType: String,                         // "MEASUREMENT" | "DEAD_ZONE"
    val entityId: String,                           // FK to the owning entity's primary key
    val operation: String = "UPLOAD",               // "UPLOAD" (DELETE deferred for MVP)

    val createdAtMs: Long,
    val nextAttemptMs: Long,                        // epoch ms; when this item is eligible
    val attemptCount: Int = 0,

    val lastErrorCode: Int? = null,                 // HTTP status, or -1 for network error
    val lastErrorMessage: String? = null            // truncated; diagnostic use only
)

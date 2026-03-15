package com.nybroadband.mobile.data.local.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A standalone user-submitted dead zone report from [DeadZoneFragment].
 *
 * Distinct from the [MeasurementEntity.deadZoneType] flag, which marks
 * auto-detected or passive-collection dead zones embedded in a signal sample.
 * This entity captures intentional user reports with richer context (note,
 * photos) and travels through a separate upload path.
 *
 * syncStatus lifecycle:
 *   PENDING → (SyncWorker uploads) → UPLOADED
 *                                 ↘ FAILED (re-queued in sync_queue)
 */
@Entity(
    tableName = "dead_zone_reports",
    indices = [
        Index(value = ["syncStatus"]),
        Index(value = ["timestamp"])
    ]
)
data class DeadZoneReportEntity(

    @PrimaryKey val id: String,                     // UUID generated at capture time

    // Location
    val timestamp: Long,                            // epoch ms
    val lat: Double,
    val lon: Double,
    val gpsAccuracyMeters: Float,

    // User-supplied context
    val note: String?,                              // optional free-text description
    val photoUris: String?,                         // pipe-separated URI list, null if no photos

    // Device metadata (sent to backend for de-duplication)
    val deviceModel: String,
    val androidVersion: Int,
    val appVersion: String,

    // Sync state
    val syncStatus: String = "PENDING",             // PENDING | UPLOADED | FAILED
    val uploadAttempts: Int = 0,
    val uploadedAt: Long? = null,
    val remoteId: String? = null                    // server-assigned ID after upload
)

package com.nybroadband.mobile.data.local.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "measurements",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["uploadStatus"]),
        Index(value = ["sampleType"]),
        Index(value = ["lat", "lon"])
    ]
)
data class MeasurementEntity(
    @PrimaryKey val id: String,

    // Location
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    val gpsAccuracyMeters: Float,

    // Network identity
    val mcc: String?,
    val mnc: String?,
    val carrierName: String?,
    val networkType: String,           // "2G" | "3G" | "4G" | "5G" | "UNKNOWN"

    // Signal (all nullable — OEM TelephonyManager may not return values)
    val rsrp: Int?,                    // dBm, valid: -140 to -44
    val rsrq: Int?,                    // dB,  valid: -20  to -3
    val rssi: Int?,                    // dBm, fallback for 2G/3G
    val sinr: Int?,                    // dB,  valid: -23  to 40
    val signalBars: Int,               // computed 0–4
    val signalTier: String,            // GOOD | FAIR | WEAK | POOR | NONE

    // Speed test results (null for passive samples)
    val downloadSpeedMbps: Double?,
    val uploadSpeedMbps: Double?,
    val latencyMs: Int?,
    val jitterMs: Int?,
    val bytesDownloaded: Long?,
    val bytesUploaded: Long?,
    val testDurationSec: Int?,
    val testServerName: String?,
    val testServerLocation: String?,

    // Collection context
    val sampleType: String,            // PASSIVE | ACTIVE_MANUAL | ACTIVE_RECURRING
    val activityMode: String?,         // DRIVING | WALKING | HIKING | BIKING | SMART_AUTO
    val sessionId: String?,
    val isNoService: Boolean,
    val deadZoneType: String?,         // null | AUTO | MANUAL
    val deadZoneNote: String?,

    // Device metadata
    val deviceModel: String,
    val androidVersion: Int,
    val appVersion: String,

    // Sync state
    val uploadStatus: String = "PENDING",   // PENDING | UPLOADED | FAILED
    val uploadAttempts: Int = 0,
    val uploadedAt: Long? = null
)

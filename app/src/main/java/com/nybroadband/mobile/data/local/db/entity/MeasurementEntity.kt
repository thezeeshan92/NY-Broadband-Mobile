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

    // ── Location ─────────────────────────────────────────────────────────────
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    val gpsAccuracyMeters: Float,

    // ── Network identity ──────────────────────────────────────────────────────
    val mcc: String?,
    val mnc: String?,
    val carrierName: String?,
    val networkType: String,           // "2G" | "3G" | "4G" | "5G_NSA" | "5G_SA" | "UNKNOWN"

    // ── Universal primary signal metrics (all nullable — OEM may not report) ──
    val rsrp: Int?,                    // dBm, LTE RSRP / NR SS-RSRP,  valid: -140 to -44
    val rsrq: Int?,                    // dB,  LTE RSRQ / NR SS-RSRQ,  valid: -20  to -3
    val rssi: Int?,                    // dBm, 2G/3G fallback or LTE RSSI
    val sinr: Int?,                    // dB,  LTE SINR / NR SS-SINR,  valid: -23  to 40
    val signalBars: Int,               // computed 0–4
    val signalTier: String,            // GOOD | FAIR | WEAK | POOR | NONE

    // ── 2G (GSM / CDMA / EDGE) specific ──────────────────────────────────────
    val gsmBer: Int?,                  // Bit Error Rate 0–7 (99→null)
    val gsmTimingAdv: Int?,            // Timing Advance 0–219 (~550 m/unit)

    // ── 3G (UMTS / WCDMA / HSPA) specific ────────────────────────────────────
    val umtsRscp: Int?,                // Received Signal Code Power, dBm
    val umtsEcNo: Int?,                // Energy per Chip / Noise, dB

    // ── 4G (LTE) specific ────────────────────────────────────────────────────
    val lteCqi: Int?,                  // Channel Quality Indicator 0–15
    val lteTimingAdv: Int?,            // Timing Advance 0–1282

    // ── 5G NR (CSI beam set) specific ────────────────────────────────────────
    val nrCsiRsrp: Int?,               // CSI-RSRP, dBm  (-156 to -31)
    val nrCsiRsrq: Int?,               // CSI-RSRQ, dB   (-43  to  20)
    val nrCsiSinr: Int?,               // CSI-SINR, dB   (-23  to  40)

    // ── Speed test results (null for passive samples) ─────────────────────────
    val downloadSpeedMbps: Double?,
    val uploadSpeedMbps: Double?,
    val latencyMs: Int?,
    val jitterMs: Int?,
    val bytesDownloaded: Long?,
    val bytesUploaded: Long?,
    val testDurationSec: Int?,
    val testServerName: String?,
    val testServerLocation: String?,

    // ── NDT7 extended metrics (null for non-NDT7 tests) ──────────────────────
    // All RTT values are in microseconds for maximum precision.
    val minRttUs: Long?,               // Minimum TCP RTT (TCPInfo.MinRTT), µs
    val meanRttUs: Long?,              // Smoothed mean RTT (TCPInfo.RTT), µs
    val rttVarUs: Long?,               // RTT variance / jitter (TCPInfo.RTTVar), µs
    val retransmitRate: Double?,       // BytesRetrans/BytesSent — NDT7 packet-loss proxy
    val bbrBandwidthBps: Long?,        // BBR bandwidth estimate, bits/sec
    val bbrMinRttUs: Long?,            // BBR minimum RTT, µs
    val serverUuid: String?,           // NDT7 test UUID from locate API

    // ── Collection context ────────────────────────────────────────────────────
    val sampleType: String,            // PASSIVE | ACTIVE_MANUAL | ACTIVE_RECURRING
    val activityMode: String?,         // DRIVING | WALKING | HIKING | BIKING | SMART_AUTO
    val sessionId: String?,
    val isNoService: Boolean,
    val deadZoneType: String?,         // null | AUTO | MANUAL
    val deadZoneNote: String?,

    // ── Device metadata ───────────────────────────────────────────────────────
    val deviceModel: String,
    val androidVersion: Int,
    val appVersion: String,

    // ── Sync state ────────────────────────────────────────────────────────────
    val uploadStatus: String = "PENDING",   // PENDING | UPLOADED | FAILED
    val uploadAttempts: Int = 0,
    val uploadedAt: Long? = null
)

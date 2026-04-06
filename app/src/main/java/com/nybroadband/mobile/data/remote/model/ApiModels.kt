package com.nybroadband.mobile.data.remote.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ─── Device Registration ──────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class RegisterDeviceRequest(
    @Json(name = "device_model")       val deviceModel: String,
    @Json(name = "android_version")    val androidVersion: String,
    @Json(name = "app_version")        val appVersion: String,
    @Json(name = "device_fingerprint") val deviceFingerprint: String
)

@JsonClass(generateAdapter = true)
data class RegisterDeviceResponse(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "created")   val created: Boolean
)

// ─── Measurements Batch ───────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class CreateMeasurementRequest(
    @Json(name = "device_id")           val deviceId: String,
    @Json(name = "timestamp")           val timestamp: String,         // ISO-8601
    @Json(name = "lat")                 val lat: Double,
    @Json(name = "lon")                 val lon: Double,
    @Json(name = "gps_accuracy_meters") val gpsAccuracyMeters: Double?,
    @Json(name = "mcc")                 val mcc: String?,
    @Json(name = "mnc")                 val mnc: String?,
    @Json(name = "carrier_name")        val carrierName: String?,
    @Json(name = "network_type")        val networkType: String,
    @Json(name = "rsrp")                val rsrp: Int?,
    @Json(name = "rsrq")                val rsrq: Int?,
    @Json(name = "rssi")                val rssi: Int?,
    @Json(name = "sinr")                val sinr: Int?,
    @Json(name = "signal_bars")         val signalBars: Int?,
    @Json(name = "signal_tier")         val signalTier: String?,
    @Json(name = "download_speed_mbps") val downloadSpeedMbps: Double?,
    @Json(name = "upload_speed_mbps")   val uploadSpeedMbps: Double?,
    @Json(name = "latency_ms")          val latencyMs: Int?,
    @Json(name = "jitter_ms")           val jitterMs: Int?,
    @Json(name = "test_server_name")    val testServerName: String?,
    @Json(name = "sample_type")         val sampleType: String,
    @Json(name = "is_no_service")       val isNoService: Boolean,
    @Json(name = "gsm_ber")             val gsmBer: Int?,
    @Json(name = "gsm_timing_adv")      val gsmTimingAdv: Int?,
    @Json(name = "umts_rscp")           val umtsRscp: Int?,
    @Json(name = "umts_ec_no")          val umtsEcNo: Int?,
    @Json(name = "lte_cqi")             val lteCqi: Int?,
    @Json(name = "lte_timing_adv")      val lteTimingAdv: Int?,
    @Json(name = "nr_csi_rsrp")         val nrCsiRsrp: Int?,
    @Json(name = "nr_csi_rsrq")         val nrCsiRsrq: Int?,
    @Json(name = "nr_csi_sinr")         val nrCsiSinr: Int?,
    @Json(name = "min_rtt_us")          val minRttUs: Long?,
    @Json(name = "mean_rtt_us")         val meanRttUs: Long?,
    @Json(name = "rtt_var_us")          val rttVarUs: Long?,
    @Json(name = "retransmit_rate")     val retransmitRate: Double?,
    @Json(name = "bbr_bandwidth_bps")   val bbrBandwidthBps: Long?,
    @Json(name = "bbr_min_rtt_us")      val bbrMinRttUs: Long?,
    @Json(name = "server_uuid")         val serverUuid: String?,
    @Json(name = "app_version")         val appVersion: String?
)

@JsonClass(generateAdapter = true)
data class MeasurementBatchRequest(
    @Json(name = "measurements") val measurements: List<CreateMeasurementRequest>
)

@JsonClass(generateAdapter = true)
data class MeasurementItemResult(
    @Json(name = "index")   val index: Int,
    @Json(name = "status")  val status: String,   // "success" | "rejected"
    @Json(name = "id")      val id: String?,
    @Json(name = "code")    val code: String?,
    @Json(name = "message") val message: String?
)

@JsonClass(generateAdapter = true)
data class MeasurementBatchResponse(
    @Json(name = "results") val results: List<MeasurementItemResult>
)

// ─── Dead Zones ───────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class CreateDeadZoneRequest(
    @Json(name = "device_id")           val deviceId: String,
    @Json(name = "lat")                 val lat: Double,
    @Json(name = "lon")                 val lon: Double,
    @Json(name = "gps_accuracy_meters") val gpsAccuracyMeters: Double?,
    @Json(name = "note")                val note: String?,
    @Json(name = "photo_count")         val photoCount: Int,
    @Json(name = "timestamp")           val timestamp: String           // ISO-8601
)

@JsonClass(generateAdapter = true)
data class CreateDeadZoneResponse(
    @Json(name = "remoteId")    val remoteId: String,
    @Json(name = "uploadUrls")  val uploadUrls: List<String>
)

// ─── Coverage ─────────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class CoverageCell(
    @Json(name = "h3Index")              val h3Index: String,
    @Json(name = "centroidLat")          val centroidLat: Double,
    @Json(name = "centroidLon")          val centroidLon: Double,
    @Json(name = "measurementCount")     val measurementCount: Int,
    @Json(name = "avgRsrp")             val avgRsrp: Double?,
    @Json(name = "avgRssi")             val avgRssi: Double?,
    @Json(name = "avgDownloadMbps")      val avgDownloadMbps: Double?,
    @Json(name = "avgUploadMbps")        val avgUploadMbps: Double?,
    @Json(name = "dominantNetworkType")  val dominantNetworkType: String,
    @Json(name = "dominantCarrier")      val dominantCarrier: String,
    @Json(name = "signalTier")           val signalTier: String,
    @Json(name = "lastUpdatedAt")        val lastUpdatedAt: String
)

@JsonClass(generateAdapter = true)
data class CoverageHexResponse(
    @Json(name = "cells") val cells: List<CoverageCell>
)

// ─── Results History ──────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class RemoteMeasurementItem(
    @Json(name = "id")                  val id: String,
    @Json(name = "timestamp")           val timestamp: String,
    @Json(name = "lat")                 val lat: Double,
    @Json(name = "lon")                 val lon: Double,
    @Json(name = "networkType")         val networkType: String,
    @Json(name = "carrierName")         val carrierName: String?,
    @Json(name = "signalTier")          val signalTier: String?,
    @Json(name = "rsrp")                val rsrp: Int?,
    @Json(name = "downloadSpeedMbps")   val downloadSpeedMbps: Double?,
    @Json(name = "uploadSpeedMbps")     val uploadSpeedMbps: Double?,
    @Json(name = "latencyMs")           val latencyMs: Int?,
    @Json(name = "sampleType")          val sampleType: String
)

@JsonClass(generateAdapter = true)
data class ResultsResponse(
    @Json(name = "results")     val results: List<RemoteMeasurementItem>,
    @Json(name = "total")       val total: Int,
    @Json(name = "page")        val page: Int,
    @Json(name = "limit")       val limit: Int,
    @Json(name = "hasNextPage") val hasNextPage: Boolean
)

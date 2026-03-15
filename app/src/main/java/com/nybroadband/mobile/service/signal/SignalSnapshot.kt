package com.nybroadband.mobile.service.signal

/**
 * Point-in-time snapshot of the device's telephony signal state.
 *
 * Assembled by [SignalReader] on every [TelephonyCallback] / [PhoneStateListener]
 * callback and exposed via [SignalReader.snapshot] StateFlow.
 *
 * All signal metrics are nullable because:
 *   - OEMs are not required to report every metric.
 *   - Values outside the spec-valid range are normalised to null by [validOrNull].
 *   - API 26–28 devices cannot disaggregate per-cell metrics from SignalStrength.
 *
 * Network type strings:
 *   "2G" | "3G" | "4G" | "5G_NSA" | "5G_NSA_MMWAVE" | "5G_SA" | "UNKNOWN"
 *
 * Signal tier strings (stored in Room / shown in UI):
 *   "GOOD" | "FAIR" | "WEAK" | "POOR" | "NONE"
 */
data class SignalSnapshot(

    val networkType: String,

    // Carrier identity
    val carrierName: String?,
    val mcc: String?,
    val mnc: String?,

    // LTE / NR primary metrics (null if unavailable or invalid)
    val rsrp: Int?,             // dBm  — Reference Signal Received Power
    val rsrq: Int?,             // dB   — Reference Signal Received Quality
    val rssi: Int?,             // dBm  — fallback for 2G/3G, or when rsrp absent
    val sinr: Int?,             // dB   — Signal-to-Interference-plus-Noise Ratio

    // Derived / computed
    val signalBars: Int,        // 0–4 (computed from rsrp or rssi)
    val signalTier: String,     // GOOD | FAIR | WEAK | POOR | NONE
    val isNoService: Boolean,   // true when no cell detected at all

    val capturedAtMs: Long = System.currentTimeMillis()
)

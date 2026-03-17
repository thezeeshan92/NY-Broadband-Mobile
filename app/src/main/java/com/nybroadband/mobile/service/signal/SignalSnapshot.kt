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
 * ─────────────────────────────────────────────────────────────────────────────
 * Network type strings:
 *   "2G" | "3G" | "4G" | "5G_NSA" | "5G_NSA_MMWAVE" | "5G_SA" | "UNKNOWN"
 *
 * Signal tier strings (stored in Room / shown in UI):
 *   "GOOD" | "FAIR" | "WEAK" | "POOR" | "NONE"
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Per-technology RF coverage:
 *
 *  2G (GSM/CDMA)
 *    rssi          — Received Signal Strength Indicator, dBm
 *    gsmBer        — Bit Error Rate, 0–7 (99 = unavailable)
 *    gsmTimingAdv  — Timing Advance, 0–219 (distance proxy, ~550 m per unit)
 *
 *  3G (UMTS/WCDMA/HSPA)
 *    rssi          — RSSI, dBm
 *    umtsRscp      — Received Signal Code Power, dBm  (–120 to –25)
 *    umtsEcNo      — Energy per Chip / Noise, dB      (–24 to 0)
 *
 *  4G (LTE)
 *    rsrp          — Reference Signal Received Power, dBm  (–140 to –44)
 *    rsrq          — Reference Signal Received Quality, dB (–20 to –3)
 *    rssi          — Received Signal Strength Indicator, dBm
 *    sinr          — Signal/Interference+Noise Ratio, dB   (–23 to 40)
 *    lteCqi        — Channel Quality Indicator, 0–15
 *    lteTimingAdv  — Timing Advance, 0–1282 (0.0521 µs resolution → distance)
 *
 *  5G (NR — both NSA and SA)
 *    rsrp          — SS-RSRP, dBm   (–156 to –31)
 *    rsrq          — SS-RSRQ, dB    (–43 to 20)
 *    sinr          — SS-SINR, dB    (–23 to 40)
 *    nrCsiRsrp     — CSI-RSRP, dBm  (–156 to –31)
 *    nrCsiRsrq     — CSI-RSRQ, dB   (–43 to 20)
 *    nrCsiSinr     — CSI-SINR, dB   (–23 to 40)
 */
data class SignalSnapshot(

    val networkType: String,

    // ── Carrier identity ────────────────────────────────────────────────────
    val carrierName: String?,
    val mcc: String?,
    val mnc: String?,

    // ── Universal primary metrics ───────────────────────────────────────────
    // rsrp = LTE RSRP / NR SS-RSRP / CDMA signal. rssi = 2G/3G fallback or LTE RSSI.
    val rsrp: Int?,
    val rsrq: Int?,
    val rssi: Int?,
    val sinr: Int?,

    // ── 2G (GSM / CDMA / EDGE) ──────────────────────────────────────────────
    val gsmBer: Int?,           // Bit Error Rate 0–7 (99 = unavailable)
    val gsmTimingAdv: Int?,     // Timing Advance 0–219 (~550 m/unit)

    // ── 3G (UMTS / WCDMA / HSPA) ────────────────────────────────────────────
    val umtsRscp: Int?,         // Received Signal Code Power, dBm
    val umtsEcNo: Int?,         // Ec/No (chip energy over noise), dB

    // ── 4G (LTE) ────────────────────────────────────────────────────────────
    val lteCqi: Int?,           // Channel Quality Indicator 0–15
    val lteTimingAdv: Int?,     // Timing Advance 0–1282

    // ── 5G (NR — CSI beam set, separate from SS metrics above) ─────────────
    val nrCsiRsrp: Int?,        // CSI Reference Signal Received Power, dBm
    val nrCsiRsrq: Int?,        // CSI Reference Signal Received Quality, dB
    val nrCsiSinr: Int?,        // CSI Signal/Interference+Noise Ratio, dB

    // ── Derived / computed ──────────────────────────────────────────────────
    val signalBars: Int,        // 0–4 (computed from rsrp or rssi)
    val signalTier: String,     // GOOD | FAIR | WEAK | POOR | NONE
    val isNoService: Boolean,   // true when no cell detected at all

    val capturedAtMs: Long = System.currentTimeMillis()
)

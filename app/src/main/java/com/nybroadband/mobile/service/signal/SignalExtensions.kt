package com.nybroadband.mobile.service.signal

import android.os.Build
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.SignalStrength
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

// ─────────────────────────────────────────────────────────────────────────────
// Range-validity guard
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Returns the Int if it falls within [min]..[max], otherwise null.
 *
 * TelephonyManager APIs return Integer.MAX_VALUE for "not available", and many
 * OEMs return out-of-range values on error. This guard normalises both to null.
 */
fun Int.validOrNull(min: Int, max: Int): Int? = if (this in min..max) this else null

// ─────────────────────────────────────────────────────────────────────────────
// Signal tier and bar computation
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Classify signal strength into a plain-language tier.
 *
 * Prefers [rsrp] (LTE/5G Reference Signal Received Power) as the primary metric
 * since it reflects actual link quality better than RSSI on modern networks.
 * Falls back to [rssi] for 2G/3G where RSRP is unavailable.
 *
 * Thresholds derived from GSMA recommendations and Android's own SignalStrength.level:
 *   LTE RSRP:  GOOD  > -95   FAIR -95..-105   WEAK -105..-115   POOR < -115
 *   2G/3G RSSI: GOOD > -75   FAIR  -75..-85   WEAK  -85..-95   POOR < -95
 */
fun computeSignalTier(rsrp: Int?, rssi: Int?): String = when {
    rsrp != null -> when {
        rsrp > -95  -> "GOOD"
        rsrp > -105 -> "FAIR"
        rsrp > -115 -> "WEAK"
        else        -> "POOR"
    }
    rssi != null -> when {
        rssi > -75 -> "GOOD"
        rssi > -85 -> "FAIR"
        rssi > -95 -> "WEAK"
        else       -> "POOR"
    }
    else -> "NONE"
}

/**
 * Maps signal tier to a 0–4 bar count for the signal card icon.
 * Mirrors the mapping used by Android's own signal bar display.
 */
fun computeSignalBars(rsrp: Int?, rssi: Int?): Int = when (computeSignalTier(rsrp, rssi)) {
    "GOOD" -> 4
    "FAIR" -> 3
    "WEAK" -> 2
    "POOR" -> 1
    else   -> 0
}

// ─────────────────────────────────────────────────────────────────────────────
// Network type strings
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Maps [TelephonyManager.NETWORK_TYPE_*] constants to human-readable generation strings.
 *
 * Used on API 26–30 where [TelephonyDisplayInfo] is unavailable or unreliable.
 * Suppressed because NETWORK_TYPE_CDMA and NETWORK_TYPE_1xRTT are deprecated.
 */
@Suppress("DEPRECATION")
fun Int.toNetworkTypeString(): String = when (this) {
    TelephonyManager.NETWORK_TYPE_LTE                              -> "4G"
    TelephonyManager.NETWORK_TYPE_NR                               -> "5G_SA"
    TelephonyManager.NETWORK_TYPE_HSDPA,
    TelephonyManager.NETWORK_TYPE_HSUPA,
    TelephonyManager.NETWORK_TYPE_HSPA,
    TelephonyManager.NETWORK_TYPE_HSPAP,
    TelephonyManager.NETWORK_TYPE_UMTS,
    TelephonyManager.NETWORK_TYPE_EVDO_0,
    TelephonyManager.NETWORK_TYPE_EVDO_A,
    TelephonyManager.NETWORK_TYPE_EVDO_B,
    TelephonyManager.NETWORK_TYPE_EHRPD                            -> "3G"
    TelephonyManager.NETWORK_TYPE_GPRS,
    TelephonyManager.NETWORK_TYPE_EDGE,
    TelephonyManager.NETWORK_TYPE_CDMA,
    TelephonyManager.NETWORK_TYPE_1xRTT,
    TelephonyManager.NETWORK_TYPE_IDEN,
    TelephonyManager.NETWORK_TYPE_GSM                              -> "2G"
    else                                                           -> "UNKNOWN"
}

/**
 * Resolves the network generation string from [TelephonyDisplayInfo] on API 30+.
 *
 * [TelephonyDisplayInfo] provides the *displayed* network type, including 5G NSA
 * override (e.g., when the device is on LTE-Advanced with an NR carrier aggregated).
 * Without this, 5G NSA appears as "4G" using [toNetworkTypeString] alone.
 */
@RequiresApi(Build.VERSION_CODES.R)
fun TelephonyDisplayInfo.toNetworkTypeString(): String = when (overrideNetworkType) {
    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA         -> "5G_NSA"
    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE  -> "5G_NSA_MMWAVE"
    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED    -> "5G_SA"
    else                                                       -> networkType.toNetworkTypeString()
}

// ─────────────────────────────────────────────────────────────────────────────
// Full RF metrics container (covers all network generations)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * All raw RF measurements extracted from a [SignalStrength] callback.
 *
 * Fields are nullable for three reasons:
 *  1. They are technology-specific (e.g. lteCqi only exists on LTE cells).
 *  2. OEMs may not populate every field even when the technology is active.
 *  3. Range validation via [validOrNull] converts out-of-spec OEM values to null.
 *
 * CQI_UNAVAILABLE = Integer.MAX_VALUE per 3GPP; TA_UNKNOWN = Integer.MAX_VALUE.
 * Both are normalised to null by the range guards applied in [extractRfMetrics].
 */
internal data class RfMetrics(
    // ── Universal (present regardless of network generation) ─────────────────
    val rsrp: Int?,         // LTE RSRP / NR SS-RSRP / CDMA dBm
    val rsrq: Int?,         // LTE RSRQ / NR SS-RSRQ
    val rssi: Int?,         // RSSI for 2G/3G, or LTE RSSI on API 29+
    val sinr: Int?,         // LTE SINR / NR SS-SINR

    // ── 2G (GSM / CDMA) ──────────────────────────────────────────────────────
    val gsmBer: Int?,       // Bit Error Rate 0–7 (99 = unavailable → null)
    val gsmTimingAdv: Int?, // Timing advance 0–219

    // ── 3G (UMTS / WCDMA / HSPA) ─────────────────────────────────────────────
    val umtsRscp: Int?,     // Received Signal Code Power, dBm
    val umtsEcNo: Int?,     // Ec/No dB

    // ── 4G (LTE) ─────────────────────────────────────────────────────────────
    val lteCqi: Int?,       // Channel Quality Indicator 0–15
    val lteTimingAdv: Int?, // Timing Advance 0–1282

    // ── 5G NR (CSI beam metrics, separate from SS metrics in rsrp/rsrq/sinr) ─
    val nrCsiRsrp: Int?,
    val nrCsiRsrq: Int?,
    val nrCsiSinr: Int?
)

// ─────────────────────────────────────────────────────────────────────────────
// SignalStrength → RfMetrics  (API-level dispatch)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Extracts all available RF metrics from a [SignalStrength] callback result.
 *
 * API 29+ : Uses [SignalStrength.getCellSignalStrengths] to find the strongest
 *           cell per technology and reads all disaggregated metrics.
 * API 26–28: Falls back to coarse accessors (gsmSignalStrength → RSSI;
 *            cdmaDbm). RSRQ, CQI, timing advance are unavailable on these levels.
 */
internal fun SignalStrength.extractRfMetrics(): RfMetrics =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) extractRfMetricsModern()
    else extractRfMetricsLegacy()

@RequiresApi(Build.VERSION_CODES.Q)
private fun SignalStrength.extractRfMetricsModern(): RfMetrics {

    // ── 5G NR ─────────────────────────────────────────────────────────────────
    val nr = getCellSignalStrengths(CellSignalStrengthNr::class.java).firstOrNull()
    if (nr != null) {
        return RfMetrics(
            rsrp       = nr.ssRsrp.validOrNull(-156, -31),
            rsrq       = nr.ssRsrq.validOrNull(-43, 20),
            rssi       = null,
            sinr       = nr.ssSinr.validOrNull(-23, 40),
            gsmBer     = null,
            gsmTimingAdv = null,
            umtsRscp   = null,
            umtsEcNo   = null,
            lteCqi     = null,
            lteTimingAdv = null,
            nrCsiRsrp  = nr.csiRsrp.validOrNull(-156, -31),
            nrCsiRsrq  = nr.csiRsrq.validOrNull(-43, 20),
            nrCsiSinr  = nr.csiSinr.validOrNull(-23, 40)
        )
    }

    // ── LTE ──────────────────────────────────────────────────────────────────
    val lte = getCellSignalStrengths(CellSignalStrengthLte::class.java).firstOrNull()
    if (lte != null) {
        return RfMetrics(
            rsrp       = lte.rsrp.validOrNull(-140, -44),
            rsrq       = lte.rsrq.validOrNull(-20, -3),
            rssi       = lte.rssi.validOrNull(-113, -51),
            sinr       = lte.rssnr.validOrNull(-23, 40),
            gsmBer     = null,
            gsmTimingAdv = null,
            umtsRscp   = null,
            umtsEcNo   = null,
            lteCqi     = lte.cqi.validOrNull(0, 15),
            lteTimingAdv = lte.timingAdvance.validOrNull(0, 1282),
            nrCsiRsrp  = null,
            nrCsiRsrq  = null,
            nrCsiSinr  = null
        )
    }

    // ── WCDMA / UMTS / HSPA ──────────────────────────────────────────────────
    val wcdma = getCellSignalStrengths(CellSignalStrengthWcdma::class.java).firstOrNull()
    if (wcdma != null) {
        val rscp = wcdma.dbm.validOrNull(-120, -25)
        val rssi = wcdma.dbm.validOrNull(-113, -51)
        return RfMetrics(
            rsrp       = null,
            rsrq       = null,
            rssi       = rssi,
            sinr       = null,
            gsmBer     = null,
            gsmTimingAdv = null,
            umtsRscp   = rscp,
            umtsEcNo   = null,
            lteCqi     = null,
            lteTimingAdv = null,
            nrCsiRsrp  = null,
            nrCsiRsrq  = null,
            nrCsiSinr  = null
        )
    }

    // ── GSM ───────────────────────────────────────────────────────────────────
    val gsm = getCellSignalStrengths(CellSignalStrengthGsm::class.java).firstOrNull()
    if (gsm != null) {
        val rssi = gsm.dbm.validOrNull(-113, -51)
        return RfMetrics(
            rsrp       = null,
            rsrq       = null,
            rssi       = rssi,
            sinr       = null,
            gsmBer     = gsm.bitErrorRate.validOrNull(0, 7),
            gsmTimingAdv = gsm.timingAdvance.validOrNull(0, 219),
            umtsRscp   = null,
            umtsEcNo   = null,
            lteCqi     = null,
            lteTimingAdv = null,
            nrCsiRsrp  = null,
            nrCsiRsrq  = null,
            nrCsiSinr  = null
        )
    }

    // ── CDMA ─────────────────────────────────────────────────────────────────
    val cdma = getCellSignalStrengths(CellSignalStrengthCdma::class.java).firstOrNull()
    if (cdma != null) {
        return RfMetrics(
            rsrp       = cdma.dbm.validOrNull(-140, -44),
            rsrq       = null,
            rssi       = null,
            sinr       = null,
            gsmBer     = null,
            gsmTimingAdv = null,
            umtsRscp   = null,
            umtsEcNo   = null,
            lteCqi     = null,
            lteTimingAdv = null,
            nrCsiRsrp  = null,
            nrCsiRsrq  = null,
            nrCsiSinr  = null
        )
    }

    // ── Fallback: use first available CellSignalStrength ─────────────────────
    val fallbackRssi = getCellSignalStrengths(CellSignalStrength::class.java)
        .firstOrNull()?.dbm?.validOrNull(-113, -51)
    return RfMetrics(null, null, fallbackRssi, null, null, null, null, null, null, null, null, null, null)
}

@Suppress("DEPRECATION")
private fun SignalStrength.extractRfMetricsLegacy(): RfMetrics {
    return if (!isGsm) {
        // CDMA — cdmaDbm() is the signal level in dBm
        RfMetrics(
            rsrp = cdmaDbm.validOrNull(-140, -44),
            rsrq = null, rssi = null, sinr = null,
            gsmBer = null, gsmTimingAdv = null,
            umtsRscp = null, umtsEcNo = null,
            lteCqi = null, lteTimingAdv = null,
            nrCsiRsrp = null, nrCsiRsrq = null, nrCsiSinr = null
        )
    } else {
        // GSM / WCDMA / HSPA — gsmSignalStrength() returns ASU (0–31, 99=unknown)
        // RSSI = 2 × ASU − 113  per 3GPP TS 27.007 AT+CSQ
        val asu  = gsmSignalStrength
        val rssi = if (asu in 0..31) (2 * asu - 113).validOrNull(-113, -51) else null
        RfMetrics(
            rsrp = null,
            rsrq = null,
            rssi = rssi,
            sinr = null,
            gsmBer = null,
            gsmTimingAdv = null,
            umtsRscp = null,
            umtsEcNo = null,
            lteCqi = null,
            lteTimingAdv = null,
            nrCsiRsrp = null,
            nrCsiRsrq = null,
            nrCsiSinr = null
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SignalStrength → SignalSnapshot  (primary assembly point)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Converts a [SignalStrength] callback result into a [SignalSnapshot].
 *
 * Caller must supply [networkType], [carrierName], [mcc], and [mnc] from
 * [TelephonyManager] since [SignalStrength] itself does not carry identity info.
 */
fun SignalStrength.toSnapshot(
    networkType: String,
    carrierName: String?,
    mcc: String?,
    mnc: String?
): SignalSnapshot {
    val rf = extractRfMetrics()
    return SignalSnapshot(
        networkType  = networkType,
        carrierName  = carrierName,
        mcc          = mcc,
        mnc          = mnc,
        rsrp         = rf.rsrp,
        rsrq         = rf.rsrq,
        rssi         = rf.rssi,
        sinr         = rf.sinr,
        gsmBer       = rf.gsmBer,
        gsmTimingAdv = rf.gsmTimingAdv,
        umtsRscp     = rf.umtsRscp,
        umtsEcNo     = rf.umtsEcNo,
        lteCqi       = rf.lteCqi,
        lteTimingAdv = rf.lteTimingAdv,
        nrCsiRsrp    = rf.nrCsiRsrp,
        nrCsiRsrq    = rf.nrCsiRsrq,
        nrCsiSinr    = rf.nrCsiSinr,
        signalBars   = computeSignalBars(rf.rsrp, rf.rssi),
        signalTier   = computeSignalTier(rf.rsrp, rf.rssi),
        isNoService  = networkType == "UNKNOWN" && rf.rsrp == null && rf.rssi == null
    )
}

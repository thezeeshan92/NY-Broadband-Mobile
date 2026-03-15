package com.nybroadband.mobile.service.signal

import android.os.Build
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
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
// SignalStrength → raw metrics
// ─────────────────────────────────────────────────────────────────────────────

internal data class RawMetrics(
    val rsrp: Int?,
    val rsrq: Int?,
    val rssi: Int?,
    val sinr: Int?
)

/**
 * Extracts per-metric values from a [SignalStrength] callback result.
 *
 * API 29+: Uses [SignalStrength.getCellSignalStrengths] to find the strongest
 *          LTE or NR cell and read its disaggregated RSRP/RSRQ/SINR.
 * API 26–28: Falls back to [SignalStrength.dbm] (which Android reports as RSRP on
 *            LTE and RSSI on 2G/3G) and [SignalStrength.level] for bar count.
 *            RSRQ and SINR are not available on these API levels.
 *
 * All values are range-validated with [validOrNull] to filter OEM garbage values.
 */
internal fun SignalStrength.extractMetrics(): RawMetrics {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        extractMetricsModern()
    } else {
        extractMetricsLegacy()
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun SignalStrength.extractMetricsModern(): RawMetrics {
    // Prefer LTE metrics (most common network type in NY)
    val lte = getCellSignalStrengths(CellSignalStrengthLte::class.java).firstOrNull()
    if (lte != null) {
        return RawMetrics(
            rsrp = lte.rsrp.validOrNull(-140, -44),
            rsrq = lte.rsrq.validOrNull(-20, -3),
            rssi = lte.rssi.validOrNull(-113, -51),
            sinr = lte.rssnr.validOrNull(-23, 40)
        )
    }

    // 5G NR SA — or NR layer in NSA deployment
    val nr = getCellSignalStrengths(CellSignalStrengthNr::class.java).firstOrNull()
    if (nr != null) {
        return RawMetrics(
            rsrp = nr.ssRsrp.validOrNull(-156, -31),
            rsrq = nr.ssRsrq.validOrNull(-43, 20),
            rssi = null,
            sinr = nr.ssSinr.validOrNull(-23, 40)
        )
    }

    // 2G/3G fallback — use first available CellSignalStrength dBm as RSSI
    val rssi = getCellSignalStrengths(CellSignalStrength::class.java)
        .firstOrNull()?.dbm?.let { it.validOrNull(-113, -51) }
    return RawMetrics(rsrp = null, rsrq = null, rssi = rssi, sinr = null)
}

@Suppress("DEPRECATION")
private fun SignalStrength.extractMetricsLegacy(): RawMetrics {
    // On API 26–28, SignalStrength.getDbm() is not in the public SDK stub.
    // Use the type-specific legacy accessors that are still available.
    return if (!isGsm) {
        // CDMA / EVDO — getCdmaDbm() returns signal in dBm
        RawMetrics(rsrp = cdmaDbm.validOrNull(-140, -44), rsrq = null, rssi = null, sinr = null)
    } else {
        // GSM / WCDMA / HSPA — getGsmSignalStrength() returns ASU (0–31, 99=unknown)
        // RSSI = 2 × ASU − 113  per 3GPP TS 27.007 AT+CSQ
        val asu = gsmSignalStrength
        val rssi = if (asu in 0..31) (2 * asu - 113).validOrNull(-113, -51) else null
        RawMetrics(rsrp = null, rsrq = null, rssi = rssi, sinr = null)
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
    val metrics = extractMetrics()
    return SignalSnapshot(
        networkType  = networkType,
        carrierName  = carrierName,
        mcc          = mcc,
        mnc          = mnc,
        rsrp         = metrics.rsrp,
        rsrq         = metrics.rsrq,
        rssi         = metrics.rssi,
        sinr         = metrics.sinr,
        signalBars   = computeSignalBars(metrics.rsrp, metrics.rssi),
        signalTier   = computeSignalTier(metrics.rsrp, metrics.rssi),
        isNoService  = networkType == "UNKNOWN" && metrics.rsrp == null && metrics.rssi == null
    )
}

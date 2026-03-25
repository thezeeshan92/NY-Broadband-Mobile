package com.nybroadband.mobile.service.signal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.*
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads visible-cell information from [TelephonyManager] and publishes a
 * [CellularSnapshot] via [snapshot].
 *
 * Call [requestRefresh] any time you want a fresh snapshot.
 * On API 29+ the snapshot is updated asynchronously via
 * [TelephonyManager.requestCellInfoUpdate]; on older APIs it is populated
 * synchronously from [TelephonyManager.getAllCellInfo].
 */
@Singleton
class CellularInfoReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val tm: TelephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private val _snapshot = MutableStateFlow<CellularSnapshot?>(null)
    val snapshot: StateFlow<CellularSnapshot?> = _snapshot.asStateFlow()

    // ── Public API ────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun requestRefresh() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tm.requestCellInfoUpdate(
                    ContextCompat.getMainExecutor(context),
                    @RequiresApi(Build.VERSION_CODES.Q)
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cells: MutableList<CellInfo>) {
                            _snapshot.value = buildSnapshot(cells)
                        }

                        override fun onError(errorCode: Int, detail: Throwable?) {
                            Timber.w("CellularInfoReader: requestCellInfoUpdate error $errorCode: ${detail?.message}")
                            // Fall back to synchronous read
                            _snapshot.value = buildSnapshot(tm.allCellInfo ?: emptyList())
                        }
                    }
                )
            } else {
                _snapshot.value = buildSnapshot(tm.allCellInfo ?: emptyList())
            }
        } catch (e: SecurityException) {
            Timber.w("CellularInfoReader: permission denied — ${e.message}")
        } catch (e: Exception) {
            Timber.w("CellularInfoReader: unexpected error — ${e.message}")
        }
    }

    // ── Snapshot assembly ─────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun buildSnapshot(rawCells: List<CellInfo>): CellularSnapshot {
        val connection = buildConnectionInfo()
        val cells = rawCells.mapNotNull(::parseCellInfo)
        // Sort: registered/primary cells first
        val sorted = cells.sortedWith(compareByDescending { it.isPrimary })
        return CellularSnapshot(connection = connection, cells = sorted)
    }

    @SuppressLint("MissingPermission")
    private fun buildConnectionInfo(): CellConnectionInfo {
        val networkType = try {
            tm.dataNetworkType
        } catch (_: SecurityException) {
            TelephonyManager.NETWORK_TYPE_UNKNOWN
        }
        val plmn = tm.networkOperator ?: ""
        return CellConnectionInfo(
            networkOperator = tm.networkOperatorName.takeIf { it.isNotBlank() },
            simOperator     = tm.simOperatorName.takeIf { it.isNotBlank() },
            networkGen      = networkType.toGenString(),
            tech            = networkType.toTechString(),
            isRoaming       = tm.isNetworkRoaming,
            mcc             = plmn.take(3).takeIf { it.length == 3 },
            mnc             = plmn.drop(3).takeIf { it.isNotBlank() },
        )
    }

    // ── Per-cell dispatch ─────────────────────────────────────────────────────

    private fun parseCellInfo(cell: CellInfo): CellEntry? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is CellInfoNr -> parseNr(cell)
        cell is CellInfoLte   -> parseLte(cell)
        cell is CellInfoWcdma -> parseWcdma(cell)
        cell is CellInfoGsm   -> parseGsm(cell)
        else                  -> null
    }

    // ── LTE ───────────────────────────────────────────────────────────────────

    private fun parseLte(cell: CellInfoLte): CellEntry {
        val id  = cell.cellIdentity
        val sig = cell.cellSignalStrength

        val earfcn = id.earfcn.validInt()
        val bandResult: BandResult? = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && id.bands.isNotEmpty() ->
                lteBandFromNumber(id.bands[0])
            earfcn != null -> earfcnToLteBand(earfcn)
            else -> null
        }

        val bwKhz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            id.bandwidth.validInt() else null

        val ci    = id.ci.validInt()?.toLong()
        val rsrp  = sig.rsrp.validInt()
        val rsrq  = sig.rsrq.validInt()
        val rssi  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            sig.rssi.validInt() else null
        val snr   = sig.rssnr.validInt()
        val cqi   = sig.cqi.validInt()
        val ta    = sig.timingAdvance.validInt()

        return CellEntry.Lte(
            data = LteCellData(
                isPrimary      = cell.isRegistered,
                rsrp           = rsrp,
                rsrq           = rsrq,
                rssi           = rssi,
                snr            = snr,
                cqi            = cqi,
                timingAdv      = ta,
                band           = bandResult?.band,
                freqDlLowMhz   = bandResult?.dlLow,
                freqDlHighMhz  = bandResult?.dlHigh,
                bandwidthKhz   = bwKhz,
                eNb            = ci?.let { it shr 8 },
                cellId         = ci?.let { (it and 0xFF).toInt() },
                ci             = ci,
                pci            = id.pci.validInt(),
                mcc            = id.mccString,
                mnc            = id.mncString,
            ),
            signalBars = lteSignalBars(rsrp)
        )
    }

    // ── NR (5G) ───────────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun parseNr(cell: CellInfoNr): CellEntry {
        val id  = cell.cellIdentity as CellIdentityNr
        val sig = cell.cellSignalStrength as CellSignalStrengthNr

        val band: Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            id.bands.firstOrNull() else null
        val (freqLow, freqHigh) = band?.let { nrBandFrequency(it) } ?: (null to null)

        val ssRsrp  = sig.ssRsrp.validInt()
        val ssRsrq  = sig.ssRsrq.validInt()
        val ssSinr  = sig.ssSinr.validInt()
        val csiRsrp = sig.csiRsrp.validInt()
        val csiRsrq = sig.csiRsrq.validInt()
        val csiSinr = sig.csiSinr.validInt()

        return CellEntry.Nr(
            data = NrCellData(
                isPrimary   = cell.isRegistered,
                ssRsrp      = ssRsrp,
                ssRsrq      = ssRsrq,
                ssSinr      = ssSinr,
                csiRsrp     = csiRsrp,
                csiRsrq     = csiRsrq,
                csiSinr     = csiSinr,
                band        = band,
                freqLowMhz  = freqLow,
                freqHighMhz = freqHigh,
                nci         = id.nci.takeIf { it != Long.MAX_VALUE },
                pci         = id.pci.validInt(),
                mcc         = id.mccString,
                mnc         = id.mncString,
            ),
            signalBars = nr5gSignalBars(ssRsrp)
        )
    }

    // ── WCDMA ─────────────────────────────────────────────────────────────────

    private fun parseWcdma(cell: CellInfoWcdma): CellEntry {
        val id  = cell.cellIdentity
        val sig = cell.cellSignalStrength

        val cid32 = id.cid.validInt()
        val rscp  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            sig.dbm.validInt() else null
        val ecNo  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            @Suppress("DEPRECATION") sig.ecNo.validInt() else null

        val uarfcn = id.uarfcn.validInt()

        return CellEntry.Wcdma(
            data = WcdmaCellData(
                isPrimary = cell.isRegistered,
                dbm       = sig.dbm.validInt(),
                rscp      = rscp,
                ecNo      = ecNo,
                uarfcn    = uarfcn,
                band      = uarfcn?.let { uarfcnToBand(it) },
                lac       = id.lac.validInt(),
                cid       = cid32?.and(0xFFFF),
                rnc       = cid32?.let { it ushr 16 }?.takeIf { it > 0 },
                psc       = id.psc.validInt(),
                mcc       = id.mccString,
                mnc       = id.mncString,
            ),
            signalBars = wcdmaSignalBars(rscp ?: sig.dbm.validInt())
        )
    }

    // ── GSM ───────────────────────────────────────────────────────────────────

    private fun parseGsm(cell: CellInfoGsm): CellEntry {
        val id  = cell.cellIdentity
        val sig = cell.cellSignalStrength

        val arfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            id.arfcn.validInt() else null
        val ta = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            sig.timingAdvance.validInt() else null

        return CellEntry.Gsm(
            data = GsmCellData(
                isPrimary = cell.isRegistered,
                dbm       = sig.dbm.validInt(),
                ber       = sig.bitErrorRate.takeIf { it in 0..7 },
                timingAdv = ta,
                arfcn     = arfcn,
                band      = arfcn?.let { gsmArfcnToBand(it) },
                lac       = id.lac.validInt(),
                cid       = id.cid.validInt(),
                mcc       = id.mccString,
                mnc       = id.mncString,
            ),
            signalBars = gsmSignalBars(sig.dbm.validInt())
        )
    }

    // ── Signal bar helpers ────────────────────────────────────────────────────

    private fun lteSignalBars(rsrp: Int?): Int = when {
        rsrp == null -> 0
        rsrp >= -80  -> 4
        rsrp >= -90  -> 3
        rsrp >= -100 -> 2
        rsrp >= -110 -> 1
        else         -> 0
    }

    private fun nr5gSignalBars(ssRsrp: Int?): Int = when {
        ssRsrp == null -> 0
        ssRsrp >= -80  -> 4
        ssRsrp >= -90  -> 3
        ssRsrp >= -100 -> 2
        ssRsrp >= -110 -> 1
        else           -> 0
    }

    private fun wcdmaSignalBars(rscp: Int?): Int = when {
        rscp == null -> 0
        rscp >= -70  -> 4
        rscp >= -80  -> 3
        rscp >= -90  -> 2
        rscp >= -100 -> 1
        else         -> 0
    }

    private fun gsmSignalBars(dbm: Int?): Int = when {
        dbm == null -> 0
        dbm >= -70  -> 4
        dbm >= -80  -> 3
        dbm >= -90  -> 2
        dbm >= -100 -> 1
        else        -> 0
    }

    // ── Network-type helpers ──────────────────────────────────────────────────

    private fun Int.toGenString(): String = when (this) {
        TelephonyManager.NETWORK_TYPE_GSM,
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_EDGE    -> "2G"
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B  -> "3G"
        TelephonyManager.NETWORK_TYPE_LTE     -> "4G"
        TelephonyManager.NETWORK_TYPE_NR      -> "5G"
        else                                  -> "N/A"
    }

    private fun Int.toTechString(): String = when (this) {
        TelephonyManager.NETWORK_TYPE_GPRS       -> "GPRS"
        TelephonyManager.NETWORK_TYPE_GSM        -> "GSM"
        TelephonyManager.NETWORK_TYPE_EDGE       -> "EDGE"
        TelephonyManager.NETWORK_TYPE_UMTS       -> "UMTS"
        TelephonyManager.NETWORK_TYPE_HSDPA      -> "HSDPA"
        TelephonyManager.NETWORK_TYPE_HSUPA      -> "HSUPA"
        TelephonyManager.NETWORK_TYPE_HSPA       -> "HSPA"
        TelephonyManager.NETWORK_TYPE_HSPAP      -> "HSPA+"
        TelephonyManager.NETWORK_TYPE_LTE        -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR         -> "NR"
        TelephonyManager.NETWORK_TYPE_CDMA       -> "CDMA"
        TelephonyManager.NETWORK_TYPE_1xRTT      -> "1xRTT"
        TelephonyManager.NETWORK_TYPE_EVDO_0     -> "EVDO r0"
        TelephonyManager.NETWORK_TYPE_EVDO_A     -> "EVDO rA"
        TelephonyManager.NETWORK_TYPE_TD_SCDMA   -> "TD-SCDMA"
        else                                     -> "Unknown"
    }

    // ── Band lookup tables ────────────────────────────────────────────────────

    private data class BandResult(val band: Int, val dlLow: Int, val dlHigh: Int)

    private data class LteBandRow(
        val band: Int, val dlLow: Int, val dlHigh: Int,
        val earfcnStart: Int, val earfcnEnd: Int,
    )

    private val LTE_BANDS = listOf(
        LteBandRow(1,  2110, 2170,  0,     599),
        LteBandRow(2,  1930, 1990,  600,   1199),
        LteBandRow(3,  1805, 1880,  1200,  1949),
        LteBandRow(4,  2110, 2155,  1950,  2399),
        LteBandRow(5,  869,  894,   2400,  2649),
        LteBandRow(7,  2620, 2690,  2750,  3449),
        LteBandRow(8,  925,  960,   3450,  3799),
        LteBandRow(12, 729,  746,   5010,  5179),
        LteBandRow(13, 746,  756,   5180,  5279),
        LteBandRow(14, 758,  768,   5280,  5379),
        LteBandRow(17, 734,  746,   5730,  5849),
        LteBandRow(18, 860,  875,   5850,  5999),
        LteBandRow(19, 875,  890,   6000,  6149),
        LteBandRow(20, 791,  821,   6150,  6449),
        LteBandRow(21, 1496, 1511,  6450,  6599),
        LteBandRow(25, 1930, 1995,  8040,  8689),
        LteBandRow(26, 859,  894,   8690,  9039),
        LteBandRow(28, 758,  803,   9210,  9659),
        LteBandRow(30, 2350, 2360,  9770,  9869),
        LteBandRow(38, 2570, 2620, 37750, 38249),
        LteBandRow(39, 1880, 1920, 38250, 38649),
        LteBandRow(40, 2300, 2400, 38650, 39649),
        LteBandRow(41, 2496, 2690, 39650, 41589),
        LteBandRow(42, 3400, 3600, 41590, 43589),
        LteBandRow(43, 3600, 3800, 43590, 45589),
        LteBandRow(48, 3550, 3700, 55240, 56739),
        LteBandRow(66, 2110, 2200, 66436, 67335),
        LteBandRow(71, 617,  652,  68586, 68935),
    )

    private val LTE_BY_NUMBER: Map<Int, LteBandRow> = LTE_BANDS.associateBy { it.band }

    private fun earfcnToLteBand(earfcn: Int): BandResult? =
        LTE_BANDS.find { earfcn in it.earfcnStart..it.earfcnEnd }
            ?.let { BandResult(it.band, it.dlLow, it.dlHigh) }

    private fun lteBandFromNumber(band: Int): BandResult? =
        LTE_BY_NUMBER[band]?.let { BandResult(it.band, it.dlLow, it.dlHigh) }

    private fun nrBandFrequency(band: Int): Pair<Int?, Int?> = when (band) {
        1    -> 2110 to 2170;  2    -> 1930 to 1990
        3    -> 1805 to 1880;  5    -> 869  to 894
        7    -> 2620 to 2690;  8    -> 925  to 960
        20   -> 791  to 821;   28   -> 758  to 803
        38   -> 2570 to 2620;  40   -> 2300 to 2400
        41   -> 2496 to 2690;  66   -> 2110 to 2200
        71   -> 617  to 652;   77   -> 3300 to 4200
        78   -> 3300 to 3800;  79   -> 4400 to 5000
        257  -> 26500 to 29500
        258  -> 24250 to 27500
        260  -> 37000 to 40000
        261  -> 27500 to 28350
        else -> null to null
    }

    private fun uarfcnToBand(uarfcn: Int): Int? = when {
        uarfcn in 10562..10838 -> 1
        uarfcn in 9662..9938   -> 2
        uarfcn in 1162..1513   -> 3
        uarfcn in 1537..1738   -> 4
        uarfcn in 4357..4458   -> 5
        uarfcn in 2712..2863   -> 8
        uarfcn in 2850..2938   -> 9
        uarfcn in 3112..3388   -> 19
        else                   -> null
    }

    private fun gsmArfcnToBand(arfcn: Int): Int? = when {
        arfcn in 1..124       -> 900
        arfcn in 975..1023    -> 900
        arfcn in 512..885     -> 1800
        arfcn in 128..251     -> 850
        arfcn in 259..293     -> 450
        arfcn in 306..340     -> 480
        arfcn in 438..511     -> 750
        arfcn in 512..810     -> 1900
        else                  -> null
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /** Returns null if the value equals [CellInfo.UNAVAILABLE] (Integer.MAX_VALUE). */
    private fun Int.validInt(): Int? = takeIf { it != CellInfo.UNAVAILABLE }
}

package com.nybroadband.mobile.service.signal

// ─────────────────────────────────────────────────────────────────────────────
// Per-technology cell parameter records
// ─────────────────────────────────────────────────────────────────────────────

data class LteCellData(
    val isPrimary: Boolean,
    val rsrp: Int?,           // Reference Signal Received Power, dBm (–140 to –44)
    val rsrq: Int?,           // Reference Signal Received Quality, dB  (–20 to –3)
    val rssi: Int?,           // RSSI, dBm
    val snr: Int?,            // Signal/Noise Ratio, dB (rssnr field)
    val cqi: Int?,            // Channel Quality Indicator, 0–15
    val timingAdv: Int?,      // Timing Advance, 0–1282
    val band: Int?,           // Band number, e.g. 3
    val freqDlLowMhz: Int?,   // DL band low edge, MHz
    val freqDlHighMhz: Int?,  // DL band high edge, MHz
    val bandwidthKhz: Int?,   // Channel bandwidth, kHz (e.g. 15000 = 15 MHz)
    val eNb: Long?,           // eNodeB ID (ci >> 8)
    val cellId: Int?,         // Cell-within-eNB ID (ci & 0xFF)
    val ci: Long?,            // 28-bit Cell Identity
    val pci: Int?,            // Physical Cell ID, 0–503
    val mcc: String?,
    val mnc: String?,
)

data class NrCellData(
    val isPrimary: Boolean,
    val ssRsrp: Int?,         // SS-RSRP, dBm  (–156 to –31)
    val ssRsrq: Int?,         // SS-RSRQ, dB   (–43 to 20)
    val ssSinr: Int?,         // SS-SINR, dB   (–23 to 40)
    val csiRsrp: Int?,        // CSI-RSRP, dBm
    val csiRsrq: Int?,        // CSI-RSRQ, dB
    val csiSinr: Int?,        // CSI-SINR, dB
    val band: Int?,
    val freqLowMhz: Int?,
    val freqHighMhz: Int?,
    val nci: Long?,           // NR Cell Identity
    val pci: Int?,
    val mcc: String?,
    val mnc: String?,
)

data class WcdmaCellData(
    val isPrimary: Boolean,
    val dbm: Int?,            // Combined signal, dBm
    val rscp: Int?,           // Received Signal Code Power, dBm
    val ecNo: Int?,           // Ec/No ratio, dB (×10 in raw API)
    val uarfcn: Int?,
    val band: Int?,
    val lac: Int?,
    val cid: Int?,            // Lower 16 bits of cell identity
    val rnc: Int?,            // RNC ID (upper 16 bits)
    val psc: Int?,            // Primary Scrambling Code
    val mcc: String?,
    val mnc: String?,
)

data class GsmCellData(
    val isPrimary: Boolean,
    val dbm: Int?,            // Signal strength, dBm
    val ber: Int?,            // Bit Error Rate, 0–7 (99 = unavailable)
    val timingAdv: Int?,      // Timing Advance, 0–219
    val arfcn: Int?,
    val band: Int?,           // Band MHz (900 / 1800 / 850 / 1900)
    val lac: Int?,
    val cid: Int?,
    val mcc: String?,
    val mnc: String?,
)

// ─────────────────────────────────────────────────────────────────────────────
// Unified sealed cell entry
// ─────────────────────────────────────────────────────────────────────────────

sealed class CellEntry {
    abstract val isPrimary: Boolean
    abstract val signalDbm: Int   // primary signal metric for bar display
    abstract val signalBars: Int  // 0–4

    data class Lte(val data: LteCellData, override val signalBars: Int) : CellEntry() {
        override val isPrimary get() = data.isPrimary
        override val signalDbm get() = data.rsrp ?: data.rssi ?: -140
    }

    data class Nr(val data: NrCellData, override val signalBars: Int) : CellEntry() {
        override val isPrimary get() = data.isPrimary
        override val signalDbm get() = data.ssRsrp ?: -156
    }

    data class Wcdma(val data: WcdmaCellData, override val signalBars: Int) : CellEntry() {
        override val isPrimary get() = data.isPrimary
        override val signalDbm get() = data.rscp ?: data.dbm ?: -120
    }

    data class Gsm(val data: GsmCellData, override val signalBars: Int) : CellEntry() {
        override val isPrimary get() = data.isPrimary
        override val signalDbm get() = data.dbm ?: -113
    }

    /** Human-readable technology label: "LTE", "NR", "UMTS", "GSM" */
    val techLabel: String get() = when (this) {
        is Lte   -> "LTE"
        is Nr    -> "NR"
        is Wcdma -> "UMTS"
        is Gsm   -> "GSM"
    }

    /** Band string like "b3", "n78", "B1" */
    val bandLabel: String? get() = when (this) {
        is Lte   -> data.band?.let { "b$it" }
        is Nr    -> data.band?.let { "n$it" }
        is Wcdma -> data.band?.let { "B$it" }
        is Gsm   -> data.band?.let { "${it}M" }
    }

    /** Formatted frequency range like "1805 MHz – 1880 MHz" */
    val freqRangeLabel: String? get() = when (this) {
        is Lte   -> if (data.freqDlLowMhz != null && data.freqDlHighMhz != null)
            "${data.freqDlLowMhz} MHz – ${data.freqDlHighMhz} MHz" else null
        is Nr    -> if (data.freqLowMhz != null && data.freqHighMhz != null)
            "${data.freqLowMhz} MHz – ${data.freqHighMhz} MHz" else null
        is Wcdma -> null
        is Gsm   -> null
    }

    /** Signal tier for colour-coding */
    val signalTier: SignalTier get() = when {
        signalBars >= 4 -> SignalTier.GOOD
        signalBars == 3 -> SignalTier.FAIR
        signalBars == 2 -> SignalTier.WEAK
        signalBars == 1 -> SignalTier.POOR
        else            -> SignalTier.NONE
    }
}

enum class SignalTier { GOOD, FAIR, WEAK, POOR, NONE }

// ─────────────────────────────────────────────────────────────────────────────
// Connection-level metadata
// ─────────────────────────────────────────────────────────────────────────────

data class CellConnectionInfo(
    val networkOperator: String?,   // Current network name, e.g. "ZONG"
    val simOperator: String?,       // SIM issuer name, e.g. "Jazz"
    val networkGen: String,         // "2G" | "3G" | "4G" | "5G" | "N/A"
    val tech: String,               // "LTE" | "NR" | "HSPA+" | "GSM" | …
    val isRoaming: Boolean,
    val mcc: String?,               // 3-digit Mobile Country Code
    val mnc: String?,               // 2–3 digit Mobile Network Code
)

// ─────────────────────────────────────────────────────────────────────────────
// Top-level snapshot
// ─────────────────────────────────────────────────────────────────────────────

data class CellularSnapshot(
    val connection: CellConnectionInfo,
    val cells: List<CellEntry>,
    val capturedAtMs: Long = System.currentTimeMillis(),
)

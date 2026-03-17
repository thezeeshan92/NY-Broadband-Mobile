package com.nybroadband.mobile.domain.model

/**
 * Full state machine for a single active test run.
 *
 * Emitted by [TestOrchestrator.run] and collected by [TestInProgressViewModel].
 *
 *   Idle ──→ Running(LATENCY) ──→ Running(DOWNLOAD) ──→ Running(UPLOAD)
 *                                                               │
 *                                                ┌─────────────┴─────────────┐
 *                                                ▼                           ▼
 *                                           Completed                     Failed
 *
 * Cancelled by the user is represented as Failed(CANCELLED).
 */
sealed interface ActiveTestState {

    /** No test active. Initial state of [TestInProgressViewModel]. */
    data object Idle : ActiveTestState

    /**
     * Test is actively running.
     *
     * [progressFraction]: 0f..1f within the current phase (used for progress bar).
     * [latencyMs]: null until LATENCY phase completes; populated in DOWNLOAD/UPLOAD phases.
     * [downloadMbps] / [uploadMbps]: latest instant reading during their respective phases.
     * [downloadSamples] / [uploadSamples]: accumulated time-series for future charting.
     */
    data class Running(
        val phase: TestPhase,
        val progressFraction: Float,
        val elapsedMs: Long,
        val latencyMs: Int?                         = null,
        val downloadMbps: Double?                   = null,
        val uploadMbps: Double?                     = null,
        val downloadSamples: List<SpeedSample>      = emptyList(),
        val uploadSamples: List<SpeedSample>        = emptyList(),
        /** Live retransmit rate (BytesRetrans / BytesSent) as a packet-loss proxy; 0.0–1.0. */
        val retransmitRate: Double?                 = null
    ) : ActiveTestState

    /**
     * Test finished successfully.
     *
     * [measurementId]: Room primary key of the saved [MeasurementEntity].
     *                  Navigate to TestResultFragment with this ID.
     */
    data class Completed(
        val measurementId: String,
        val downloadMbps: Double,
        val uploadMbps: Double,
        val latencyMs: Int,
        val jitterMs: Int,
        val serverName: String,
        val signalTier: String,
        val networkType: String,
        val carrierName: String?,
        val timestampMs: Long
    ) : ActiveTestState

    /** Test ended without a result. */
    data class Failed(
        val reason: FailureReason,
        val message: String? = null
    ) : ActiveTestState
}

// ─────────────────────────────────────────────────────────────────────────────

enum class TestPhase(val displayLabel: String) {
    LATENCY("Measuring latency"),
    DOWNLOAD("Testing download"),
    UPLOAD("Testing upload")
}

enum class FailureReason {
    NO_NETWORK,
    SERVER_UNREACHABLE,
    TIMEOUT,
    PERMISSION_DENIED,
    CANCELLED,
    UNKNOWN
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Raw output from [SpeedTestEngine] after a successful test run.
 * Internal to the engine/orchestrator boundary — not exposed to the UI layer.
 *
 * NDT7 extended metrics (all nullable — only populated by [Ndt7SpeedTestEngine]):
 *   [minRttUs]        — Minimum RTT from TCPInfo, microseconds.
 *   [meanRttUs]       — Smoothed mean RTT from TCPInfo, microseconds.
 *   [rttVarUs]        — RTT variance (jitter proxy) from TCPInfo, microseconds.
 *   [retransmitRate]  — BytesRetrans / BytesSent; NDT7's packet-loss proxy (0.0–1.0).
 *   [bbrBandwidthBps] — BBR bandwidth estimate, bits/sec.
 *   [bbrMinRttUs]     — BBR minimum RTT, microseconds.
 *   [serverUuid]      — NDT7 test UUID from the locate API (links DL + UL runs).
 */
data class RawTestResult(
    val downloadMbps: Double,
    val uploadMbps: Double,
    val latencyMs: Int,
    val jitterMs: Int,
    val bytesDownloaded: Long,
    val bytesUploaded: Long,
    val testDurationSec: Int,
    val serverName: String,
    val serverLocation: String?,

    // ── NDT7 TCP/BBR extended metrics ─────────────────────────────────────
    val minRttUs: Long?        = null,
    val meanRttUs: Long?       = null,
    val rttVarUs: Long?        = null,
    val retransmitRate: Double? = null,
    val bbrBandwidthBps: Long? = null,
    val bbrMinRttUs: Long?     = null,
    val serverUuid: String?    = null
)

/**
 * Update type emitted by [SpeedTestEngine.execute].
 * [EngineProgress] carries UI state updates; [EngineComplete] carries the raw metrics.
 */
sealed interface EngineUpdate
data class EngineProgress(val state: ActiveTestState.Running) : EngineUpdate
data class EngineComplete(val result: RawTestResult) : EngineUpdate

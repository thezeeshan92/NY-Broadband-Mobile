package com.nybroadband.mobile.domain.model

/**
 * A single time-series data point captured during a download or upload phase.
 *
 * Collected every ~500 ms by [SpeedTestEngine.execute] and accumulated in
 * [ActiveTestState.Running.downloadSamples] / [uploadSamples].
 *
 * Intended for a future real-time speed graph on the Test In Progress screen.
 * For MVP, only the latest value is rendered — the list is reserved for charting.
 */
data class SpeedSample(
    val elapsedMs: Long,            // ms since phase start
    val cumulativeBytesTransferred: Long,
    val instantSpeedMbps: Double    // bandwidth at this sample point
)

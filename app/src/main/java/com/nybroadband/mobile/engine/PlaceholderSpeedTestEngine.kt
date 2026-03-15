package com.nybroadband.mobile.engine

import com.nybroadband.mobile.domain.model.ActiveTestState
import com.nybroadband.mobile.domain.model.EngineComplete
import com.nybroadband.mobile.domain.model.EngineProgress
import com.nybroadband.mobile.domain.model.EngineUpdate
import com.nybroadband.mobile.domain.model.RawTestResult
import com.nybroadband.mobile.domain.model.SpeedSample
import com.nybroadband.mobile.domain.model.TestConfig
import com.nybroadband.mobile.domain.model.TestPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import kotlin.math.pow
import kotlin.random.Random

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  PLACEHOLDER IMPLEMENTATION — NOT REAL SPEED TEST DATA                 │
 * │                                                                         │
 * │  This class simulates a speed test using coroutine delays and random    │
 * │  numbers. It produces realistic-looking but entirely fabricated values. │
 * │                                                                         │
 * │  Replace with Ndt7SpeedTestEngine or OoklaSpeedTestEngine when the      │
 * │  speed test engine integration is explicitly scheduled.                 │
 * │                                                                         │
 * │  The [SpeedTestEngine] interface and [TestOrchestrator] pipeline are    │
 * │  real — only the measurements emitted by this class are fake.           │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Simulation parameters are tuned to resemble typical rural NY LTE results:
 *   Download:  15–75 Mbps (mean ~35 Mbps)
 *   Upload:     5–30 Mbps (mean ~12 Mbps)
 *   Latency:   20–80 ms
 *   Jitter:     2–15 ms
 */
class PlaceholderSpeedTestEngine @Inject constructor() : SpeedTestEngine {

    override fun execute(config: TestConfig): Flow<EngineUpdate> = flow {

        // ── LATENCY phase (1.5 s, 15 ticks × 100 ms) ─────────────────────────
        // PLACEHOLDER: random values, no real network probe
        val latencyMs = Random.nextInt(20, 81)
        val jitterMs  = Random.nextInt(2, 16)
        val phaseStart = System.currentTimeMillis()

        repeat(LATENCY_TICKS) { tick ->
            delay(LATENCY_TICK_MS)
            emit(EngineProgress(
                ActiveTestState.Running(
                    phase            = TestPhase.LATENCY,
                    progressFraction = (tick + 1).toFloat() / LATENCY_TICKS,
                    elapsedMs        = System.currentTimeMillis() - phaseStart
                )
            ))
        }

        // ── DOWNLOAD phase (config.durationSeconds, sample every 500 ms) ──────
        // PLACEHOLDER: ramp-curve formula, no actual data transfer
        val peakDownloadMbps = Random.nextDouble(15.0, 75.0)
        val dlDurationMs     = config.durationSeconds * 1_000L
        val downloadSamples  = mutableListOf<SpeedSample>()
        val dlStart          = System.currentTimeMillis()
        var bytesDownloaded  = 0L

        while (System.currentTimeMillis() - dlStart < dlDurationMs) {
            delay(SAMPLE_INTERVAL_MS)
            val elapsed  = System.currentTimeMillis() - dlStart
            val fraction = (elapsed.toFloat() / dlDurationMs).coerceIn(0f, 1f)

            // Ramp: slow start, plateau, slight drop at end — mimics TCP slow-start
            val ramp         = 1.0 - (1.0 - fraction.toDouble()).pow(2.2)
            val instantMbps  = peakDownloadMbps * (ramp * 0.88 + 0.12) +
                               Random.nextDouble(-2.0, 2.0)  // ±2 Mbps noise
            val bytes        = (instantMbps * MEGABITS_TO_BYTES * (SAMPLE_INTERVAL_MS / 1000.0)).toLong()
            bytesDownloaded += bytes.coerceAtLeast(0)

            downloadSamples += SpeedSample(elapsed, bytesDownloaded, instantMbps.coerceAtLeast(0.0))

            emit(EngineProgress(
                ActiveTestState.Running(
                    phase            = TestPhase.DOWNLOAD,
                    progressFraction = fraction,
                    elapsedMs        = elapsed,
                    latencyMs        = latencyMs,
                    downloadMbps     = instantMbps.coerceAtLeast(0.0),
                    downloadSamples  = downloadSamples.toList()
                )
            ))
        }

        val avgDownloadMbps = downloadSamples.map { it.instantSpeedMbps }.average()
            .coerceAtLeast(0.1)

        // ── UPLOAD phase (half of download duration) ──────────────────────────
        // PLACEHOLDER: same ramp formula, lower peak ceiling
        val peakUploadMbps = Random.nextDouble(5.0, 30.0)
        val ulDurationMs   = (config.durationSeconds * 500L).coerceAtLeast(3_500L) // min 3.5 s
        val uploadSamples  = mutableListOf<SpeedSample>()
        val ulStart        = System.currentTimeMillis()
        var bytesUploaded  = 0L

        while (System.currentTimeMillis() - ulStart < ulDurationMs) {
            delay(SAMPLE_INTERVAL_MS)
            val elapsed  = System.currentTimeMillis() - ulStart
            val fraction = (elapsed.toFloat() / ulDurationMs).coerceIn(0f, 1f)

            val ramp        = 1.0 - (1.0 - fraction.toDouble()).pow(2.2)
            val instantMbps = peakUploadMbps * (ramp * 0.88 + 0.12) +
                              Random.nextDouble(-1.0, 1.0)
            val bytes       = (instantMbps * MEGABITS_TO_BYTES * (SAMPLE_INTERVAL_MS / 1000.0)).toLong()
            bytesUploaded  += bytes.coerceAtLeast(0)

            uploadSamples += SpeedSample(elapsed, bytesUploaded, instantMbps.coerceAtLeast(0.0))

            emit(EngineProgress(
                ActiveTestState.Running(
                    phase            = TestPhase.UPLOAD,
                    progressFraction = fraction,
                    elapsedMs        = elapsed,
                    latencyMs        = latencyMs,
                    downloadMbps     = avgDownloadMbps,
                    uploadMbps       = instantMbps.coerceAtLeast(0.0),
                    uploadSamples    = uploadSamples.toList()
                )
            ))
        }

        val avgUploadMbps = uploadSamples.map { it.instantSpeedMbps }.average()
            .coerceAtLeast(0.1)

        val totalDurationSec =
            ((System.currentTimeMillis() - phaseStart) / 1_000).toInt()

        // ── Final result ──────────────────────────────────────────────────────
        // PLACEHOLDER: server info is fabricated; real impl returns actual server metadata
        emit(EngineComplete(
            RawTestResult(
                downloadMbps    = avgDownloadMbps,
                uploadMbps      = avgUploadMbps,
                latencyMs       = latencyMs,
                jitterMs        = jitterMs,
                bytesDownloaded = bytesDownloaded,
                bytesUploaded   = bytesUploaded,
                testDurationSec = totalDurationSec,
                serverName      = "Placeholder Test Server",   // PLACEHOLDER
                serverLocation  = null                          // PLACEHOLDER
            )
        ))
    }

    companion object {
        private const val LATENCY_TICKS     = 15
        private const val LATENCY_TICK_MS   = 100L
        private const val SAMPLE_INTERVAL_MS = 500L
        private const val MEGABITS_TO_BYTES  = 125_000.0  // 1 Mbps = 125,000 bytes/s
    }
}

package com.nybroadband.mobile.engine

import com.nybroadband.mobile.domain.model.EngineUpdate
import com.nybroadband.mobile.domain.model.TestConfig
import kotlinx.coroutines.flow.Flow

/**
 * Domain interface for speed test execution.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Contract:
 *   [execute] returns a [Flow<EngineUpdate>] that emits:
 *     1. One or more [EngineProgress] updates (Active UI state for each phase).
 *     2. Exactly one [EngineComplete] as the final emission on success.
 *     3. OR throws an exception on unrecoverable failure (network, timeout, etc.).
 *        [TestOrchestrator] catches this and emits [ActiveTestState.Failed].
 *
 *   Cancelling the flow cancels the test cleanly. Implementations must be
 *   coroutine-cancellation-aware (use [delay] / cooperative cancellation).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Implementations:
 *   [PlaceholderSpeedTestEngine] — simulated data, used for MVP and UI development.
 *   Ndt7SpeedTestEngine         — real NDT7 implementation (DEFERRED — not yet scheduled).
 *   OoklaSpeedTestEngine        — real Ookla implementation (DEFERRED — not yet scheduled).
 *
 * The active implementation is bound in [AppModule] and swapped when NDT7/Ookla
 * integration is explicitly scheduled. No other code needs to change.
 */
interface SpeedTestEngine {

    /**
     * Executes a full speed test (latency → download → upload) with [config].
     *
     * @param config  Test parameters (duration, server, type).
     * @return        A cold [Flow] of [EngineUpdate] events.
     */
    fun execute(config: TestConfig): Flow<EngineUpdate>
}

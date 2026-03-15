package com.nybroadband.mobile.domain.model

import java.util.UUID

/**
 * Immutable configuration for a single active test run.
 *
 * Passed from [ManualTestFragment] → [TestInProgressViewModel] → [TestOrchestrator] → [SpeedTestEngine].
 *
 * [durationSeconds] is enforced to the range [MIN_SECONDS]..[MAX_SECONDS] at construction.
 * This range is configurable via Firebase Remote Config (KEY_TEST_DURATION_MIN/MAX) in production.
 * For MVP the range is hardcoded to 7–15 s here as a fallback.
 */
data class TestConfig(
    val durationSeconds: Int = DEFAULT_SECONDS,
    val serverId: String? = null,                       // null = auto-select highest-priority active server
    val testType: String = TYPE_MANUAL,                 // sampleType stored in MeasurementEntity
    val sessionId: String = UUID.randomUUID().toString()
) {
    init {
        require(durationSeconds in MIN_SECONDS..MAX_SECONDS) {
            "durationSeconds must be in $MIN_SECONDS..$MAX_SECONDS, got $durationSeconds"
        }
    }

    companion object {
        const val MIN_SECONDS     = 7
        const val MAX_SECONDS     = 15
        const val DEFAULT_SECONDS = 10

        /** sampleType value stored in [MeasurementEntity] for user-triggered tests. */
        const val TYPE_MANUAL     = "ACTIVE_MANUAL"

        /** sampleType value stored in [MeasurementEntity] for automated recurring tests. */
        const val TYPE_RECURRING  = "ACTIVE_RECURRING"
    }
}

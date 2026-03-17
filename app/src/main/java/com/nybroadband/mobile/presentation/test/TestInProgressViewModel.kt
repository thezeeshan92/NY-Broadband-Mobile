package com.nybroadband.mobile.presentation.test

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nybroadband.mobile.domain.model.ActiveTestState
import com.nybroadband.mobile.domain.model.FailureReason
import com.nybroadband.mobile.domain.model.TestConfig
import com.nybroadband.mobile.service.active.TestOrchestrator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// One-time events (ViewModel → Fragment)
// ─────────────────────────────────────────────────────────────────────────────

sealed interface TestInProgressEvent {
    /** Navigate to TestResultFragment with this Room measurement ID. */
    data class NavigateToResult(val measurementId: String) : TestInProgressEvent

    /**
     * Navigate back (user cancelled or test failed before any result).
     * [errorMessage] is non-null when the test failed with a known error, null on user cancel.
     */
    data class NavigateBack(val errorMessage: String? = null) : TestInProgressEvent
}

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Drives the Test In Progress screen ([TestInProgressFragment]).
 *
 * Manual tests run entirely in [viewModelScope] — the ViewModel survives
 * fragment recreation (rotation), so the test continues through configuration changes.
 *
 * If the user navigates back during a test, the Fragment intercepts the back press
 * and calls [cancelTest] after a confirmation dialog.
 *
 * The Fragment receives the [TestConfig] as a SafeArgs nav argument (duration + serverId)
 * and calls [startTest] in [onViewCreated].
 */
@HiltViewModel
class TestInProgressViewModel @Inject constructor(
    private val orchestrator: TestOrchestrator
) : ViewModel() {

    private val _state = MutableStateFlow<ActiveTestState>(ActiveTestState.Idle)
    val state: StateFlow<ActiveTestState> = _state.asStateFlow()

    private val _events = Channel<TestInProgressEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var testJob: Job? = null

    /**
     * Start the test with the given config.
     *
     * Guards against double-start: a second call while a test is active is ignored.
     * The config should come from [ManualTestViewModel.buildConfig] via SafeArgs.
     */
    fun startTest(config: TestConfig) {
        if (testJob?.isActive == true) return

        testJob = viewModelScope.launch {
            orchestrator.run(config).collect { state ->
                _state.value = state

                when (state) {
                    is ActiveTestState.Completed ->
                        _events.send(TestInProgressEvent.NavigateToResult(state.measurementId))

                    is ActiveTestState.Failed -> {
                        // Brief pause so the "Failed" state is visible before we leave
                        delay(1_500)
                        val msg = when (state.reason) {
                            FailureReason.NO_NETWORK         ->
                                "No internet connection — check your network and try again"
                            FailureReason.SERVER_UNREACHABLE ->
                                "Could not reach the test server — try again in a moment"
                            FailureReason.TIMEOUT            ->
                                "Test timed out — your connection may be too slow or unstable"
                            FailureReason.PERMISSION_DENIED  ->
                                "Location permission is required to run a test"
                            FailureReason.CANCELLED          -> null
                            FailureReason.UNKNOWN            ->
                                "Speed test failed — please try again"
                        }
                        _events.send(TestInProgressEvent.NavigateBack(msg))
                    }

                    else -> { /* Running / Idle — no nav event needed */ }
                }
            }
        }
    }

    /**
     * Cancel the running test. Called after the user confirms the cancellation dialog.
     *
     * Sets state to Failed(CANCELLED) so the Fragment can render a brief "Cancelled" state
     * before navigating back. The Navigation event is emitted immediately.
     */
    fun cancelTest() {
        testJob?.cancel()
        _state.value = ActiveTestState.Failed(FailureReason.CANCELLED, "Test cancelled")
        viewModelScope.launch {
            _events.send(TestInProgressEvent.NavigateBack(null))
        }
    }

    override fun onCleared() {
        super.onCleared()
        testJob?.cancel()
    }
}

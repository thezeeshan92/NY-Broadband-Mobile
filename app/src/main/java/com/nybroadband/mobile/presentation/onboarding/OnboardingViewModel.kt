package com.nybroadband.mobile.presentation.onboarding

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nybroadband.mobile.data.local.prefs.OnboardingPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------------------------------------------------------------------------
// One-time events
// ---------------------------------------------------------------------------

sealed interface OnboardingEvent {
    /** All steps complete — Activity should launch MainActivity and finish. */
    data object LaunchMain : OnboardingEvent
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val prefs: OnboardingPrefs
) : ViewModel() {

    private val _events = Channel<OnboardingEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // ── API-level gates ───────────────────────────────────────────────────────

    /**
     * Background location (ACCESS_BACKGROUND_LOCATION) was added in API 29.
     * On API 26–28 it is automatically included with foreground location — no extra step needed.
     *
     * Note: On API 30+ Android no longer shows a permission dialog; instead the user is
     * sent directly to system Settings. The fragment handles this branching.
     */
    val needsBackgroundLocationStep: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q  // API 29

    /**
     * POST_NOTIFICATIONS is a runtime permission only on API 33+.
     * On API 26–32 the notification channel is created and shown automatically
     * by the foreground service — no explicit user grant is required.
     */
    val needsNotificationStep: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU  // API 33

    /**
     * Total number of permission steps shown, used to render "Step X of Y" labels.
     * Base steps: Location + PhoneState = 2
     * +1 if API 29+ (background location)
     * +1 if API 33+ (notifications)
     */
    val totalPermissionSteps: Int
        get() = 2 + (if (needsBackgroundLocationStep) 1 else 0) + (if (needsNotificationStep) 1 else 0)

    // ── Completion ────────────────────────────────────────────────────────────

    /**
     * Called by the last permission fragment when the user taps Allow or Skip.
     * Persists the completion flag and emits [OnboardingEvent.LaunchMain].
     */
    fun onOnboardingComplete() {
        prefs.markComplete()
        viewModelScope.launch {
            _events.send(OnboardingEvent.LaunchMain)
        }
    }
}

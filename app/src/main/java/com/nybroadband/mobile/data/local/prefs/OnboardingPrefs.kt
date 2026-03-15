package com.nybroadband.mobile.data.local.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight SharedPreferences wrapper for onboarding completion state.
 *
 * Not encrypted — the only value stored is a boolean flag, not PII.
 * Hilt provides this automatically via constructor injection.
 */
@Singleton
class OnboardingPrefs @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isComplete: Boolean
        get() = prefs.getBoolean(KEY_COMPLETE, false)

    fun markComplete() {
        prefs.edit().putBoolean(KEY_COMPLETE, true).apply()
    }

    /** For development / testing — resets the flag so onboarding shows again. */
    fun reset() {
        prefs.edit().remove(KEY_COMPLETE).apply()
    }

    companion object {
        private const val PREFS_NAME = "onboarding"
        private const val KEY_COMPLETE = "complete"
    }
}

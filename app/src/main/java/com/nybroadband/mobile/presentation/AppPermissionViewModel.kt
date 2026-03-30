package com.nybroadband.mobile.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Activity-scoped ViewModel that tracks runtime permission state for the app.
 *
 * Shared via [activityViewModels()] so every Fragment sees the same live state.
 * Call [refresh] from [onResume] whenever a Fragment becomes visible so the
 * state stays accurate after the user returns from system Settings.
 */
@HiltViewModel
class AppPermissionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(queryPermissions())
    val state: StateFlow<AppPermissionState> = _state.asStateFlow()

    val locationGranted: Boolean get() = _state.value.locationGranted
    val phoneStateGranted: Boolean get() = _state.value.phoneStateGranted

    /** Re-query the OS. Call from any Fragment's onResume(). */
    fun refresh() {
        _state.value = queryPermissions()
    }

    private fun queryPermissions() = AppPermissionState(
        locationGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED,

        phoneStateGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED,
    )
}

data class AppPermissionState(
    val locationGranted: Boolean,
    val phoneStateGranted: Boolean,
)
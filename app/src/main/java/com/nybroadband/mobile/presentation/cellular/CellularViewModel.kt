package com.nybroadband.mobile.presentation.cellular

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nybroadband.mobile.service.signal.CellularInfoReader
import com.nybroadband.mobile.service.signal.CellularSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CellularViewModel @Inject constructor(
    private val reader: CellularInfoReader,
    private val prefs: CellularPrefs,
) : ViewModel() {

    /** Latest cellular snapshot from TelephonyManager. */
    val snapshot: StateFlow<CellularSnapshot?> = reader.snapshot

    /** Current display/behaviour settings. */
    private val _settings = MutableStateFlow(prefs.load())
    val settings: StateFlow<CellularSettings> = _settings.asStateFlow()

    private var refreshJob: Job? = null

    init {
        startRefreshing()
        // Restart refresh loop whenever the interval changes
        viewModelScope.launch {
            settings
                .map { it.refreshIntervalSeconds }
                .distinctUntilChanged()
                .collect { startRefreshing() }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun updateSettings(settings: CellularSettings) {
        prefs.save(settings)
        _settings.value = settings
    }

    fun forceRefresh() {
        reader.requestRefresh()
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun startRefreshing() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (true) {
                reader.requestRefresh()
                delay(_settings.value.refreshIntervalSeconds * 1_000L)
            }
        }
    }

    override fun onCleared() {
        refreshJob?.cancel()
        super.onCleared()
    }
}

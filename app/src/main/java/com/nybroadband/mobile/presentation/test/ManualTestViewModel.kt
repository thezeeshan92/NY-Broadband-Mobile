package com.nybroadband.mobile.presentation.test

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nybroadband.mobile.data.local.db.dao.ServerDefinitionDao
import com.nybroadband.mobile.data.local.db.entity.ServerDefinitionEntity
import com.nybroadband.mobile.domain.model.TestConfig
import com.nybroadband.mobile.service.signal.SignalReader
import com.nybroadband.mobile.service.signal.SignalSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Run Test configuration screen ([ManualTestFragment]).
 *
 * State:
 *   [durationSeconds]   — slider value (7–15 s), default [TestConfig.DEFAULT_SECONDS].
 *   [selectedServer]    — first active entry from [ServerDefinitionDao]; null while loading.
 *   [currentSignal]     — live signal snapshot from [SignalReader] for the signal indicator.
 *
 * [buildConfig] assembles the current selections into a [TestConfig] for the orchestrator.
 * The Fragment calls this when the user taps "Run Test", then passes the config to
 * [TestInProgressViewModel] via a nav argument (or directly if Fragment is in the same nav graph).
 */
@HiltViewModel
class ManualTestViewModel @Inject constructor(
    private val serverDefinitionDao: ServerDefinitionDao,
    private val signalReader: SignalReader
) : ViewModel() {

    private val _durationSeconds = MutableStateFlow(TestConfig.DEFAULT_SECONDS)
    val durationSeconds: StateFlow<Int> = _durationSeconds.asStateFlow()

    private val _selectedServer = MutableStateFlow<ServerDefinitionEntity?>(null)
    val selectedServer: StateFlow<ServerDefinitionEntity?> = _selectedServer.asStateFlow()

    /** Live telephony signal — exposed for the signal quality row on the config screen. */
    val currentSignal: StateFlow<SignalSnapshot?> = signalReader.snapshot

    init {
        loadServer()
        // The passive collection service may not be running when the user opens
        // this screen, so start the reader here to ensure live signal data.
        // SignalReader is ref-counted, so this pairs safely with the service's own start/stop.
        signalReader.start()
    }

    override fun onCleared() {
        super.onCleared()
        signalReader.stop()
    }

    fun setDuration(seconds: Int) {
        _durationSeconds.value = seconds.coerceIn(TestConfig.MIN_SECONDS, TestConfig.MAX_SECONDS)
    }

    /**
     * Assembles the current configuration for passing to [TestInProgressViewModel].
     * Called by the Fragment immediately before navigating to the in-progress screen.
     */
    fun buildConfig(): TestConfig = TestConfig(
        durationSeconds = _durationSeconds.value,
        serverId        = _selectedServer.value?.id,    // null = auto-select in orchestrator
        testType        = TestConfig.TYPE_MANUAL
    )

    /** Estimated data use for the configured duration. REAL arithmetic, not a fixed string. */
    fun estimatedDataMb(): Double {
        // Rough upper bound: 50 Mbps average × duration for download + half for upload
        val downloadMb = 50.0 * _durationSeconds.value / 8.0
        val uploadMb   = 25.0 * (_durationSeconds.value / 2.0) / 8.0
        return downloadMb + uploadMb
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun loadServer() {
        viewModelScope.launch {
            _selectedServer.value = serverDefinitionDao.getActive().firstOrNull()
        }
    }
}

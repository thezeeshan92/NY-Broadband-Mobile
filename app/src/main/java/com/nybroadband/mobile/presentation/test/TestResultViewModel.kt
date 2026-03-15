package com.nybroadband.mobile.presentation.test

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nybroadband.mobile.data.local.db.dao.MeasurementDao
import com.nybroadband.mobile.data.local.db.entity.MeasurementEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// UI State
// ─────────────────────────────────────────────────────────────────────────────

sealed interface TestResultUiState {
    data object Loading : TestResultUiState
    data object NotFound : TestResultUiState
    data class Ready(val measurement: MeasurementEntity) : TestResultUiState
}

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Loads and exposes a single [MeasurementEntity] for the Test Result screen.
 *
 * The [measurementId] arrives via SafeArgs from [TestInProgressFragment] (after a
 * new test) or from [ResultsFragment] (when drilling into a historical result).
 *
 * [load] is idempotent — safe to call from [onViewCreated] on rotation.
 */
@HiltViewModel
class TestResultViewModel @Inject constructor(
    private val measurementDao: MeasurementDao
) : ViewModel() {

    private val _uiState = MutableStateFlow<TestResultUiState>(TestResultUiState.Loading)
    val uiState: StateFlow<TestResultUiState> = _uiState.asStateFlow()

    private var loadedId: String? = null

    fun load(measurementId: String) {
        if (loadedId == measurementId) return   // already loaded — survive rotation
        loadedId = measurementId

        viewModelScope.launch {
            val entity = measurementDao.getById(measurementId)
            _uiState.value = if (entity != null) {
                TestResultUiState.Ready(entity)
            } else {
                TestResultUiState.NotFound
            }
        }
    }
}

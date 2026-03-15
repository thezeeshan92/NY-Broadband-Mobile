package com.nybroadband.mobile.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nybroadband.mobile.data.local.db.dao.MeasurementDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SettingsUiState(
    val totalMeasurements: Int = 0,
    val monthlyDataUsageMb: Float = 0f,
    val autoTestEnabled: Boolean = false,
    val passiveCollectionEnabled: Boolean = true,
    val dataCap: DataCapOption = DataCapOption.MB_500
)

enum class DataCapOption(val displayMb: Int) {
    MB_100(100),
    MB_250(250),
    MB_500(500),
    MB_1000(1000),
    UNLIMITED(Int.MAX_VALUE)
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dao: MeasurementDao
) : ViewModel() {

    val totalCount: StateFlow<Int> = dao.observeTotalCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
        )

    val monthlyDataBytes: StateFlow<Long> = dao.observeMonthlyDataUsageBytes(currentMonthStartMs())
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0L
        )

    // TODO: load/save prefs via DataStore in a future iteration
    val autoTestEnabled = androidx.lifecycle.MutableLiveData(false)
    val passiveCollectionEnabled = androidx.lifecycle.MutableLiveData(true)

    private fun currentMonthStartMs(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}

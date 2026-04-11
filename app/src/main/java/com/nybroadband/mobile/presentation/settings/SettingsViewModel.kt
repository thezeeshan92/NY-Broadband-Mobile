package com.nybroadband.mobile.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nybroadband.mobile.data.local.db.dao.MeasurementDao
import com.nybroadband.mobile.domain.repository.SyncRepository
import com.nybroadband.mobile.service.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed class SyncState {
    object Idle    : SyncState()
    object Syncing : SyncState()
    object Error   : SyncState()
}

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
    private val dao: MeasurementDao,
    private val syncRepository: SyncRepository,
    private val workManager: WorkManager
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

    val queueDepth: StateFlow<Int> = syncRepository.observeQueueDepth()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
        )

    val syncState: StateFlow<SyncState> = workManager
        .getWorkInfosByTagFlow(SyncWorker.TAG)
        .map { workInfos ->
            when (workInfos.lastOrNull()?.state) {
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.RUNNING,
                WorkInfo.State.BLOCKED -> SyncState.Syncing
                WorkInfo.State.FAILED  -> SyncState.Error
                else                   -> SyncState.Idle
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SyncState.Idle
        )

    fun syncNow() {
        SyncWorker.enqueue(workManager)
    }

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

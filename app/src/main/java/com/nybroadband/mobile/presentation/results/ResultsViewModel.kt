package com.nybroadband.mobile.presentation.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nybroadband.mobile.data.local.db.dao.MeasurementDao
import com.nybroadband.mobile.data.local.db.entity.MeasurementEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class ResultsFilter(val label: String) {
    ALL("All"),
    SPEED_TESTS("Speed Tests"),
    PASSIVE("Passive"),
    DEAD_ZONES("Dead Zones")
}

sealed interface ResultsUiState {
    data object Loading : ResultsUiState
    data object Empty : ResultsUiState
    data class Ready(val measurements: List<MeasurementEntity>) : ResultsUiState
}

@HiltViewModel
class ResultsViewModel @Inject constructor(
    private val dao: MeasurementDao
) : ViewModel() {

    val activeFilter = MutableStateFlow(ResultsFilter.ALL)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ResultsUiState> = activeFilter
        .flatMapLatest { filter -> dao.observeFiltered(filter.name) }
        .map { list ->
            if (list.isEmpty()) ResultsUiState.Empty
            else ResultsUiState.Ready(list)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ResultsUiState.Loading
        )

    fun setFilter(filter: ResultsFilter) {
        activeFilter.value = filter
    }
}

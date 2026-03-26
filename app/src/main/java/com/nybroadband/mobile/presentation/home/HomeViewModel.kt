package com.nybroadband.mobile.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nybroadband.mobile.data.local.db.dao.MapPointProjection
import com.nybroadband.mobile.data.local.db.dao.MeasurementDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------------------------------------------------------------------------
// UI State
// ---------------------------------------------------------------------------

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object LocationPermissionRequired : HomeUiState
    data class Ready(
        val collectionStatus: CollectionStatus = CollectionStatus.IDLE,
        val signalState: SignalState = SignalState.empty(),
        val autoTestActive: Boolean = false
    ) : HomeUiState
}

data class SignalState(
    val qualityLabel: String,
    val qualityColorRes: Int,
    val networkType: String,
    val carrierName: String,
    val bars: Int                   // 0–4
) {
    companion object {
        fun empty() = SignalState(
            qualityLabel = "--",
            qualityColorRes = android.R.color.darker_gray,
            networkType = "--",
            carrierName = "--",
            bars = 0
        )
    }
}

enum class CollectionStatus(val label: String) {
    IDLE("Idle"),
    MEASURING("Measuring quietly"),
    RUNNING_TEST("Running speed test"),
    AUTO_TESTING("Auto testing"),
    NO_SERVICE("No signal detected"),
    QUEUED("Saving · will sync later")
}

// ---------------------------------------------------------------------------
// One-time UI events  (ViewModel → Fragment via Channel)
// ---------------------------------------------------------------------------

sealed interface HomeUiEvent {
    /** Camera should animate to this GPS fix. */
    data class FlyToLocation(val lat: Double, val lon: Double) : HomeUiEvent

    /** Fragment must trigger the foreground-location permission flow. */
    data object RequestLocationPermission : HomeUiEvent
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val measurementDao: MeasurementDao
) : ViewModel() {

    // ── Map points ──────────────────────────────────────────────────────────
    // Room → GeoJSON CircleLayer, limited to 500 GPS-accurate records.
    val measurementPoints: StateFlow<List<MapPointProjection>> = measurementDao
        .observeMapPoints()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /** Toolbar + filter sheet settings; drives [mapPointsForDisplay] with mock data when enabled. */
    private val _mapFilterState = MutableStateFlow(MapFilterState())
    val mapFilterState: StateFlow<MapFilterState> = _mapFilterState.asStateFlow()

    /**
     * Points sent to Mapbox after applying [mapFilterState] (merges mock measurements when [MapMockConfig.ENABLED]).
     *
     * Uses [SharingStarted.Lazily] so leaving the map tab does not reset to [emptyList] after the
     * WhileSubscribed timeout — that was clearing the GeoJSON source while Signal Strength showed no dots.
     * When mocks are on, [initialValue] matches a zero-DB filter pass so the first read is never empty.
     */
    val mapPointsForDisplay: StateFlow<List<MapPointProjection>> = combine(
        measurementDao.observeMapPoints(),
        _mapFilterState,
    ) { real, filters ->
        MapMockData.combineAndFilter(real, filters)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = if (MapMockConfig.ENABLED) {
            MapMockData.combineAndFilter(emptyList(), MapFilterState())
        } else {
            emptyList()
        },
    )

    fun resetMapFilter() {
        _mapFilterState.value = MapFilterState()
    }

    fun updateMapFilter(transform: MapFilterState.() -> MapFilterState) {
        _mapFilterState.update(transform)
    }

    fun setCarrierFilter(c: MapCarrierFilter) = updateMapFilter { copy(carrier = c) }

    fun setNetworkFilter(n: MapNetworkFilter) = updateMapFilter { copy(network = n) }

    fun setMetricFilter(m: MapMetricFilter) = updateMapFilter { copy(metric = m) }

    fun setCountryFilter(c: MapCountryFilter) = updateMapFilter { copy(country = c) }

    fun setMyDataOnly(v: Boolean) = updateMapFilter { copy(myDataOnly = v) }

    fun setShowMapValues(v: Boolean) = updateMapFilter { copy(showMapValues = v) }

    fun setShowLegend(v: Boolean) = updateMapFilter { copy(showLegend = v) }

    fun setColorMode(m: MapColorMode) = updateMapFilter { copy(colorMode = m) }

    fun setSheetMetricKind(k: MapSheetMetricKind) = updateMapFilter { copy(mapMetricKind = k) }

    fun setSpeedFilters(
        minDownloadMbps: Int,
        maxDownloadMbps: Int,
        minUploadMbps: Int,
        maxUploadMbps: Int,
    ) = updateMapFilter {
        copy(
            minDownloadMbps = minDownloadMbps,
            maxDownloadMbps = maxDownloadMbps,
            minUploadMbps = minUploadMbps,
            maxUploadMbps = maxUploadMbps,
        )
    }

    // ── UI state ─────────────────────────────────────────────────────────────
    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // ── One-time events ──────────────────────────────────────────────────────
    private val _events = Channel<HomeUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // Shell: emit Ready immediately.
        // Real: check permission status, observe PassiveCollectionService binder.
        _uiState.value = HomeUiState.Ready()
    }

    // ── Public API called by Fragment ────────────────────────────────────────

    fun onLocationPermissionGranted() {
        // Transition from any non-Ready state (Loading or LocationPermissionRequired).
        // A no-op when already Ready so repeated calls on onResume are harmless.
        if (_uiState.value !is HomeUiState.Ready) {
            _uiState.value = HomeUiState.Ready()
        }
    }

    fun onLocationPermissionDenied() {
        _uiState.value = HomeUiState.LocationPermissionRequired
    }

    /**
     * FAB tapped and the fragment resolved a GPS fix.
     * Emits [HomeUiEvent.FlyToLocation] so the map animates there.
     */
    fun onRecenterRequested(lat: Double, lon: Double) {
        viewModelScope.launch {
            _events.send(HomeUiEvent.FlyToLocation(lat, lon))
        }
    }

    // ── Callbacks from bound services (future wiring) ────────────────────────

    /** PassiveCollectionService binder callback. */
    fun onCollectionStatusChanged(status: CollectionStatus) {
        updateReadyState { copy(collectionStatus = status) }
    }

    /** PassiveCollectionService binder callback. */
    fun onNewSignalState(signal: SignalState) {
        updateReadyState { copy(signalState = signal) }
    }

    /** ActiveTestService binder callback. */
    fun onAutoTestActiveChanged(active: Boolean) {
        updateReadyState { copy(autoTestActive = active) }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private inline fun updateReadyState(
        transform: HomeUiState.Ready.() -> HomeUiState.Ready
    ) {
        val current = _uiState.value
        if (current is HomeUiState.Ready) {
            _uiState.value = current.transform()
        }
    }
}

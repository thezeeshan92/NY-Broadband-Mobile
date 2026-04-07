package com.nybroadband.mobile.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.nybroadband.mobile.data.local.db.dao.MapPointProjection
import com.nybroadband.mobile.data.local.db.dao.MeasurementDao
import com.nybroadband.mobile.data.remote.NyuBroadbandApi
import com.nybroadband.mobile.data.remote.model.CoverageCell
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
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.sin

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
// Coverage hex state
// ---------------------------------------------------------------------------

sealed interface CoverageHexUiState {
    data object Idle : CoverageHexUiState
    data object Loading : CoverageHexUiState
    data class Loaded(val collection: FeatureCollection) : CoverageHexUiState
    data class Error(val message: String) : CoverageHexUiState
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
    private val measurementDao: MeasurementDao,
    private val api: NyuBroadbandApi
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

    /** Toolbar + filter sheet settings; drives [mapPointsForDisplay]. */
    private val _mapFilterState = MutableStateFlow(MapFilterState())
    val mapFilterState: StateFlow<MapFilterState> = _mapFilterState.asStateFlow()

    /**
     * Points sent to Mapbox after applying [mapFilterState].
     *
     * Uses [SharingStarted.Lazily] so leaving the map tab does not reset to [emptyList] after the
     * WhileSubscribed timeout — that was clearing the GeoJSON source while Signal Strength showed no dots.
     */
    val mapPointsForDisplay: StateFlow<List<MapPointProjection>> = combine(
        measurementDao.observeMapPoints(),
        _mapFilterState,
    ) { real, _ ->
        real
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = emptyList(),
    )

    // ── Coverage hex ─────────────────────────────────────────────────────────

    private val _coverageHexState = MutableStateFlow<CoverageHexUiState>(CoverageHexUiState.Idle)
    val coverageHexState: StateFlow<CoverageHexUiState> = _coverageHexState.asStateFlow()

    /**
     * Fetches coverage hex cells from the API and converts them to a Mapbox-ready FeatureCollection.
     * Each [CoverageCell] is approximated as a hexagonal polygon around its centroid — no H3 library needed.
     * Idempotent: a second call while loading is a no-op.
     */
    fun loadCoverageHex(
        resolution: Int = 7,
        carrier: String? = null,
        networkType: String? = null
    ) {
        if (_coverageHexState.value is CoverageHexUiState.Loading) return
        viewModelScope.launch {
            _coverageHexState.value = CoverageHexUiState.Loading
            try {
                val response = api.getCoverageHex(
                    resolution  = resolution,
                    carrier     = carrier,
                    networkType = networkType
                )
                val features = response.cells.map { cell -> coverageCellToFeature(cell, resolution) }
                val collection = FeatureCollection.fromFeatures(features)
                _coverageHexState.value = CoverageHexUiState.Loaded(collection)
                Timber.i("Coverage hex loaded: ${features.size} cells")
            } catch (e: Exception) {
                Timber.e(e, "Failed to load coverage hex")
                _coverageHexState.value = CoverageHexUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    // ── Coverage hex helpers ─────────────────────────────────────────────────

    private fun coverageCellToFeature(cell: CoverageCell, resolution: Int): Feature {
        // Approximate H3 hex as a regular hexagon from centroid.
        // H3 resolution 7 circumradius ≈ 1.4 km ≈ 0.0126°;
        // H3 resolution 9 circumradius ≈ 0.2 km ≈ 0.0018°.
        val radiusDegLat = if (resolution <= 7) 0.013 else 0.002
        val ring = hexRing(cell.centroidLat, cell.centroidLon, radiusDegLat)
        val score = signalTierToScore(cell.signalTier)
        return Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply {
            addNumberProperty("signalScore", score)
            addStringProperty("signalTier", cell.signalTier)
            addStringProperty("dominantCarrier", cell.dominantCarrier)
            addStringProperty("dominantNetworkType", cell.dominantNetworkType)
            addNumberProperty("measurementCount", cell.measurementCount)
            cell.avgDownloadMbps?.let { addNumberProperty("avgDl", it) }
            cell.avgUploadMbps?.let  { addNumberProperty("avgUl", it) }
            cell.avgRsrp?.let        { addNumberProperty("avgRsrp", it) }
        }
    }

    /** Pointy-top regular hexagon vertices (6 + closing point) around [lat]/[lon]. */
    private fun hexRing(lat: Double, lon: Double, radiusDegLat: Double): List<Point> {
        val lonScale = cos(Math.toRadians(lat)).coerceAtLeast(0.001)
        val radiusDegLon = radiusDegLat / lonScale
        val vertices = (0 until 6).map { i ->
            val angle = Math.toRadians(60.0 * i)   // 0° = north vertex
            Point.fromLngLat(
                lon + radiusDegLon * sin(angle),
                lat + radiusDegLat * cos(angle)
            )
        }
        return vertices + vertices[0]   // close the GeoJSON ring
    }

    private fun signalTierToScore(tier: String): Double = when (tier.uppercase()) {
        "GOOD" -> 1.00
        "FAIR" -> 0.75
        "WEAK" -> 0.40
        "POOR" -> 0.15
        else   -> 0.05
    }

    // ── Filter helpers ────────────────────────────────────────────────────────

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
        _uiState.value = HomeUiState.Ready()
    }

    // ── Public API called by Fragment ────────────────────────────────────────

    fun onLocationPermissionGranted() {
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

    // ── Callbacks from bound services ────────────────────────────────────────

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

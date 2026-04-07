package com.nybroadband.mobile.presentation.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nybroadband.mobile.data.DeviceManager
import com.nybroadband.mobile.data.local.db.dao.MeasurementDao
import com.nybroadband.mobile.data.local.db.entity.MeasurementEntity
import com.nybroadband.mobile.data.remote.NyuBroadbandApi
import com.nybroadband.mobile.data.remote.model.RemoteMeasurementItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
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
    private val dao: MeasurementDao,
    private val api: NyuBroadbandApi,
    private val deviceManager: DeviceManager
) : ViewModel() {

    val activeFilter = MutableStateFlow(ResultsFilter.ALL)

    /** True while a background remote refresh is in-flight. */
    val isRefreshing = MutableStateFlow(false)

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

    /**
     * Fetches the latest measurement history for this device from the remote API and
     * inserts any new records into Room. The local [observeFiltered] flow picks them
     * up automatically — no extra wiring needed in the Fragment.
     *
     * Uses IGNORE conflict strategy so local records already in Room are never overwritten.
     */
    fun refresh() {
        val deviceId = deviceManager.deviceId ?: return   // not registered yet — skip
        viewModelScope.launch {
            isRefreshing.value = true
            try {
                val response = api.getResults(deviceId = deviceId)
                val entities = response.results.mapNotNull { it.toEntity() }
                if (entities.isNotEmpty()) {
                    dao.insertAll(entities)
                    Timber.i("Results refresh: inserted ${entities.size} remote records")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh results from remote")
            } finally {
                isRefreshing.value = false
            }
        }
    }

    // ── Conversion ────────────────────────────────────────────────────────────

    private fun RemoteMeasurementItem.toEntity(): MeasurementEntity? = try {
        val tsMs = Instant.parse(timestamp).toEpochMilli()
        MeasurementEntity(
            id                  = id,
            timestamp           = tsMs,
            lat                 = lat,
            lon                 = lon,
            gpsAccuracyMeters   = 0f,
            mcc                 = null,
            mnc                 = null,
            carrierName         = carrierName,
            networkType         = networkType,
            rsrp                = rsrp,
            rsrq                = null,
            rssi                = null,
            sinr                = null,
            signalBars          = 0,
            signalTier          = signalTier ?: "UNKNOWN",
            gsmBer              = null,
            gsmTimingAdv        = null,
            umtsRscp            = null,
            umtsEcNo            = null,
            lteCqi              = null,
            lteTimingAdv        = null,
            nrCsiRsrp           = null,
            nrCsiRsrq           = null,
            nrCsiSinr           = null,
            downloadSpeedMbps   = downloadSpeedMbps,
            uploadSpeedMbps     = uploadSpeedMbps,
            latencyMs           = latencyMs,
            jitterMs            = null,
            bytesDownloaded     = null,
            bytesUploaded       = null,
            testDurationSec     = null,
            testServerName      = null,
            testServerLocation  = null,
            minRttUs            = null,
            meanRttUs           = null,
            rttVarUs            = null,
            retransmitRate      = null,
            bbrBandwidthBps     = null,
            bbrMinRttUs         = null,
            serverUuid          = null,
            sampleType          = sampleType,
            activityMode        = null,
            sessionId           = null,
            isNoService         = false,
            deadZoneType        = null,
            deadZoneNote        = null,
            deviceModel         = "",
            androidVersion      = 0,
            appVersion          = "",
            uploadStatus        = "UPLOADED"
        )
    } catch (e: Exception) {
        Timber.w(e, "Skipping malformed remote result id=$id")
        null
    }
}

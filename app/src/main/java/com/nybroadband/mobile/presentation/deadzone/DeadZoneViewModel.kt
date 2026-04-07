package com.nybroadband.mobile.presentation.deadzone

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nybroadband.mobile.data.DeviceManager
import com.nybroadband.mobile.data.local.db.entity.DeadZoneReportEntity
import com.nybroadband.mobile.data.remote.NyuBroadbandApi
import com.nybroadband.mobile.data.remote.model.CreateDeadZoneRequest
import com.nybroadband.mobile.domain.repository.DeadZoneRepository
import com.nybroadband.mobile.service.location.LocationReader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// Dead zone type constants
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Dead zone types for user-submitted reports.
 * Stored as strings in [DeadZoneReportEntity.note] prefix or as a separate
 * field if the schema is extended. For MVP, the selected type is prepended
 * to the note text before saving.
 */
object DeadZoneType {
    const val NO_SIGNAL    = "NO_SIGNAL"     // Complete loss of signal
    const val WEAK_INDOOR  = "WEAK_INDOOR"   // Weak signal indoors (building penetration)
    const val TUNNEL       = "TUNNEL"        // Tunnel, underpass, or underground
    const val DEAD_STRETCH = "DEAD_STRETCH"  // Stretch of road with persistent dead zone
    const val OTHER        = "OTHER"

    val default = NO_SIGNAL
}

// ─────────────────────────────────────────────────────────────────────────────
// UI state
// ─────────────────────────────────────────────────────────────────────────────

sealed interface DeadZoneUiState {
    /** Location is being acquired — show spinner. */
    data object Locating : DeadZoneUiState

    /**
     * Location acquired — form is ready to fill in.
     *
     * Raw coordinates are intentionally NOT exposed to the UI. Plain-language
     * accuracy text ("±25 m") is sufficient for user confirmation without
     * displaying precise GPS values (privacy, NY SHIELD Act compliance).
     */
    data class Ready(
        val accuracyMeters: Float,
        val isOnline: Boolean,
        /** Cached internally; not shown on screen. Attached to the report on submit. */
        val lat: Double,
        val lon: Double
    ) : DeadZoneUiState

    /** Submitting to Room / sync queue — disable form. */
    data object Submitting : DeadZoneUiState

    /** Location could not be acquired. Show error + retry affordance. */
    data class LocationError(val message: String) : DeadZoneUiState
}

sealed interface DeadZoneEvent {
    /** Report saved successfully — navigate back and show success snackbar. */
    data class SubmitSuccess(val isOnline: Boolean) : DeadZoneEvent
}

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class DeadZoneViewModel @Inject constructor(
    private val repository: DeadZoneRepository,
    private val locationReader: LocationReader,
    private val api: NyuBroadbandApi,
    private val deviceManager: DeviceManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<DeadZoneUiState>(DeadZoneUiState.Locating)
    val uiState: StateFlow<DeadZoneUiState> = _uiState.asStateFlow()

    private val _events = Channel<DeadZoneEvent>(Channel.BUFFERED)
    val events: Flow<DeadZoneEvent> = _events.receiveAsFlow()

    // Form state — mutated by the Fragment as the user edits
    private var selectedType: String = DeadZoneType.default
    private var note: String = ""

    init {
        acquireLocation()
    }

    // ── Public form mutators ──────────────────────────────────────────────────

    fun setType(type: String) {
        selectedType = type
    }

    fun setNote(text: String) {
        note = text
    }

    // ── Location ──────────────────────────────────────────────────────────────

    fun retryLocation() = acquireLocation()

    private fun acquireLocation() {
        viewModelScope.launch {
            _uiState.value = DeadZoneUiState.Locating
            // 10s timeout — longer than passive collection to maximize accuracy
            val location = locationReader.getLocation(timeoutMs = 10_000L)
            _uiState.value = if (location != null) {
                DeadZoneUiState.Ready(
                    lat           = location.latitude,
                    lon           = location.longitude,
                    accuracyMeters = location.accuracy,
                    isOnline      = isNetworkAvailable()
                )
            } else {
                DeadZoneUiState.LocationError(
                    "Could not determine your location. Make sure location services are on."
                )
            }
        }
    }

    // ── Submit ────────────────────────────────────────────────────────────────

    /**
     * Assembles and persists a [DeadZoneReportEntity].
     *
     * The call is offline-safe: [repository.submit] writes to Room synchronously
     * and enqueues for later upload. If the device is online, SyncWorker will
     * pick it up on its next run; if offline, it will retry when connectivity
     * resumes.
     *
     * Photo capture is deferred to a future iteration — [photoUris] is null for MVP.
     */
    fun submit() {
        val state = _uiState.value as? DeadZoneUiState.Ready ?: return
        _uiState.value = DeadZoneUiState.Submitting

        viewModelScope.launch {
            val appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrDefault("unknown")

            // Note includes the type label so backend can distinguish intent clearly.
            // Format: "[DEAD_STRETCH] No signal on Route 9 near Lake Placid"
            val fullNote = buildString {
                append("[$selectedType]")
                val trimmedNote = note.trim()
                if (trimmedNote.isNotEmpty()) {
                    append(" ")
                    append(trimmedNote)
                }
            }

            val report = DeadZoneReportEntity(
                id               = UUID.randomUUID().toString(),
                timestamp        = System.currentTimeMillis(),
                lat              = state.lat,
                lon              = state.lon,
                gpsAccuracyMeters = state.accuracyMeters,
                note             = fullNote,
                photoUris        = null,    // photo capture deferred (MVP)
                deviceModel      = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                androidVersion   = Build.VERSION.SDK_INT,
                appVersion       = appVersion ?: "unknown"
            )

            // Always persist locally first — guarantees offline safety.
            repository.submit(report)

            // If online: attempt immediate upload and delete the local record on success.
            // If offline or upload fails: the record stays PENDING and SyncWorker will retry.
            if (state.isOnline) {
                val deviceId = deviceManager.ensureRegistered()
                if (deviceId != null) {
                    try {
                        api.submitDeadZone(report.toApiRequest(deviceId))
                        repository.delete(report.id)
                        Timber.i("DeadZone: uploaded and removed from local DB id=${report.id}")
                    } catch (e: Exception) {
                        Timber.w(e, "DeadZone: immediate upload failed — SyncWorker will retry id=${report.id}")
                    }
                }
            }

            _events.send(DeadZoneEvent.SubmitSuccess(isOnline = state.isOnline))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun DeadZoneReportEntity.toApiRequest(deviceId: String) = CreateDeadZoneRequest(
        deviceId          = deviceId,
        lat               = lat,
        lon               = lon,
        gpsAccuracyMeters = gpsAccuracyMeters.toDouble().takeIf { it > 0 },
        note              = note,
        photoCount        = photoUris?.split("|")?.filter { it.isNotBlank() }?.size ?: 0,
        timestamp         = java.time.Instant.ofEpochMilli(timestamp)
                               .toString()   // ISO-8601
    )
}

package com.nybroadband.mobile.service.active

import android.content.Context
import android.os.Build
import com.nybroadband.mobile.data.local.db.dao.MeasurementDao
import com.nybroadband.mobile.data.local.db.dao.SyncQueueDao
import com.nybroadband.mobile.data.local.db.entity.MeasurementEntity
import com.nybroadband.mobile.data.local.db.entity.SyncQueueEntity
import com.nybroadband.mobile.domain.model.ActiveTestState
import com.nybroadband.mobile.domain.model.EngineComplete
import com.nybroadband.mobile.domain.model.EngineProgress
import com.nybroadband.mobile.domain.model.FailureReason
import com.nybroadband.mobile.domain.model.RawTestResult
import com.nybroadband.mobile.domain.model.TestConfig
import com.nybroadband.mobile.domain.repository.SyncRepository
import com.nybroadband.mobile.engine.SpeedTestEngine
import com.nybroadband.mobile.service.location.LocationReader
import com.nybroadband.mobile.service.signal.SignalReader
import com.nybroadband.mobile.service.signal.SignalSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates a full active test run end-to-end.
 *
 * Responsibilities:
 *   1. Snapshot the current signal state at the moment the test starts (pre-test baseline).
 *   2. Start a location fix request in parallel while the engine runs.
 *   3. Drive [SpeedTestEngine.execute] and forward [EngineProgress] as [ActiveTestState.Running].
 *   4. On [EngineComplete]: build [MeasurementEntity] (including full RF + NDT7 fields),
 *      persist to Room, enqueue for sync.
 *   5. Emit [ActiveTestState.Completed] with the saved measurement's Room ID.
 *   6. Catch any exception and emit [ActiveTestState.Failed].
 *
 * Called by:
 *   - [TestInProgressViewModel] for manual tests (runs in viewModelScope).
 *   - [ActiveTestService] for recurring/auto tests (runs in service coroutine scope).
 */
@Singleton
class TestOrchestrator @Inject constructor(
    private val engine: SpeedTestEngine,
    private val signalReader: SignalReader,
    private val locationReader: LocationReader,
    private val measurementDao: MeasurementDao,
    private val syncQueueDao: SyncQueueDao,
    @ApplicationContext private val context: Context
) {
    private val appVersion: String by lazy {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }
    }

    /**
     * Execute a test and persist results.
     *
     * Returns a cold [Flow] that:
     *   - Emits [ActiveTestState.Running] on each engine progress update.
     *   - Emits [ActiveTestState.Completed] (with a valid Room ID) on success.
     *   - Emits [ActiveTestState.Failed] on any error.
     *
     * Cancelling the flow cancels the test; no result is saved.
     */
    fun run(config: TestConfig): Flow<ActiveTestState> = flow {
        // Capture signal before the test loads the radio (more accurate pre-test baseline)
        val initialSignal: SignalSnapshot? = signalReader.snapshot.value

        var rawResult: RawTestResult? = null

        // Begin location fetch in parallel — it overlaps with the latency/download phase
        coroutineScope {
            val locationDeferred = async { locationReader.getLocation(timeoutMs = 8_000L) }

            engine.execute(config).collect { update ->
                when (update) {
                    is EngineProgress -> emit(update.state)
                    is EngineComplete -> {
                        rawResult = update.result
                        val location = locationDeferred.await()

                        val measurement = buildMeasurementEntity(
                            raw      = update.result,
                            signal   = initialSignal,
                            lat      = location?.latitude,
                            lon      = location?.longitude,
                            accuracy = location?.accuracy,
                            config   = config
                        )

                        // Persist to Room
                        measurementDao.insert(measurement)

                        // Enqueue for SyncWorker
                        syncQueueDao.enqueue(
                            SyncQueueEntity(
                                entityType    = SyncRepository.ENTITY_MEASUREMENT,
                                entityId      = measurement.id,
                                createdAtMs   = measurement.timestamp,
                                nextAttemptMs = measurement.timestamp
                            )
                        )

                        Timber.i(
                            "TestOrchestrator: saved ${measurement.id} " +
                            "↓${update.result.downloadMbps.fmt()} " +
                            "↑${update.result.uploadMbps.fmt()} Mbps  " +
                            "rtt=${update.result.latencyMs}ms  " +
                            "loss=${update.result.retransmitRate?.let { "%.2f%%".format(it * 100) } ?: "n/a"}"
                        )

                        emit(
                            ActiveTestState.Completed(
                                measurementId = measurement.id,
                                downloadMbps  = update.result.downloadMbps,
                                uploadMbps    = update.result.uploadMbps,
                                latencyMs     = update.result.latencyMs,
                                jitterMs      = update.result.jitterMs,
                                serverName    = update.result.serverName,
                                signalTier    = initialSignal?.signalTier ?: "NONE",
                                networkType   = initialSignal?.networkType ?: "UNKNOWN",
                                carrierName   = initialSignal?.carrierName,
                                timestampMs   = measurement.timestamp
                            )
                        )
                    }
                }
            }
        }

        if (rawResult == null) {
            emit(ActiveTestState.Failed(FailureReason.UNKNOWN, "Engine exited without result"))
        }
    }
        .catch { e ->
            Timber.w(e, "TestOrchestrator: test failed")
            // Map common network exceptions to typed FailureReasons so the UI
            // can show actionable messages instead of raw exception text.
            val reason = when (e) {
                is UnknownHostException  -> FailureReason.NO_NETWORK
                is ConnectException      -> FailureReason.SERVER_UNREACHABLE
                is SocketTimeoutException -> FailureReason.TIMEOUT
                else                     -> FailureReason.UNKNOWN
            }
            emit(ActiveTestState.Failed(reason, e.message))
        }
        .flowOn(Dispatchers.IO)

    // ── Entity builder ────────────────────────────────────────────────────────

    private fun buildMeasurementEntity(
        raw:      RawTestResult,
        signal:   SignalSnapshot?,
        lat:      Double?,
        lon:      Double?,
        accuracy: Float?,
        config:   TestConfig
    ): MeasurementEntity = MeasurementEntity(
        id                  = UUID.randomUUID().toString(),
        timestamp           = System.currentTimeMillis(),

        // Location — fallback to 0.0 if GPS timed out (gpsAccuracyMeters=999 flags this)
        lat                 = lat ?: 0.0,
        lon                 = lon ?: 0.0,
        gpsAccuracyMeters   = accuracy ?: 999f,

        // Network identity from signal snapshot at test start
        mcc                 = signal?.mcc,
        mnc                 = signal?.mnc,
        carrierName         = signal?.carrierName,
        networkType         = signal?.networkType ?: "UNKNOWN",

        // ── Universal signal metrics ──────────────────────────────────────────
        rsrp                = signal?.rsrp,
        rsrq                = signal?.rsrq,
        rssi                = signal?.rssi,
        sinr                = signal?.sinr,
        signalBars          = signal?.signalBars ?: 0,
        signalTier          = signal?.signalTier ?: "NONE",

        // ── 2G-specific RF ────────────────────────────────────────────────────
        gsmBer              = signal?.gsmBer,
        gsmTimingAdv        = signal?.gsmTimingAdv,

        // ── 3G-specific RF ────────────────────────────────────────────────────
        umtsRscp            = signal?.umtsRscp,
        umtsEcNo            = signal?.umtsEcNo,

        // ── 4G-specific RF ────────────────────────────────────────────────────
        lteCqi              = signal?.lteCqi,
        lteTimingAdv        = signal?.lteTimingAdv,

        // ── 5G-specific RF (CSI metrics) ─────────────────────────────────────
        nrCsiRsrp           = signal?.nrCsiRsrp,
        nrCsiRsrq           = signal?.nrCsiRsrq,
        nrCsiSinr           = signal?.nrCsiSinr,

        // ── Speed test throughput ─────────────────────────────────────────────
        downloadSpeedMbps   = raw.downloadMbps,
        uploadSpeedMbps     = raw.uploadMbps,
        latencyMs           = raw.latencyMs,
        jitterMs            = raw.jitterMs,
        bytesDownloaded     = raw.bytesDownloaded,
        bytesUploaded       = raw.bytesUploaded,
        testDurationSec     = raw.testDurationSec,
        testServerName      = raw.serverName,
        testServerLocation  = raw.serverLocation,

        // ── NDT7 TCP/BBR extended metrics ─────────────────────────────────────
        minRttUs            = raw.minRttUs,
        meanRttUs           = raw.meanRttUs,
        rttVarUs            = raw.rttVarUs,
        retransmitRate      = raw.retransmitRate,
        bbrBandwidthBps     = raw.bbrBandwidthBps,
        bbrMinRttUs         = raw.bbrMinRttUs,
        serverUuid          = raw.serverUuid,

        // ── Collection context ────────────────────────────────────────────────
        sampleType          = config.testType,
        activityMode        = null,
        sessionId           = config.sessionId,
        isNoService         = signal?.isNoService ?: false,
        deadZoneType        = null,
        deadZoneNote        = null,

        // ── Device metadata ───────────────────────────────────────────────────
        deviceModel         = Build.MODEL,
        androidVersion      = Build.VERSION.SDK_INT,
        appVersion          = appVersion,

        // ── Sync state ────────────────────────────────────────────────────────
        uploadStatus        = "PENDING",
        uploadAttempts      = 0,
        uploadedAt          = null
    )

    private fun Double.fmt() = "%.1f".format(this)
}

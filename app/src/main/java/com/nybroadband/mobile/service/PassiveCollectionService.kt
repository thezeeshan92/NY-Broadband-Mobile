package com.nybroadband.mobile.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.nybroadband.mobile.R
import androidx.work.WorkManager
import com.nybroadband.mobile.data.local.db.dao.MeasurementDao
import com.nybroadband.mobile.data.local.db.dao.SyncQueueDao
import com.nybroadband.mobile.data.local.db.entity.SyncQueueEntity
import com.nybroadband.mobile.domain.repository.SyncRepository
import com.nybroadband.mobile.presentation.home.CollectionStatus
import com.nybroadband.mobile.service.location.LocationReader
import com.nybroadband.mobile.service.passive.SampleAssembler
import com.nybroadband.mobile.service.signal.SignalReader
import com.nybroadband.mobile.service.signal.SignalSnapshot
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/**
 * Foreground service that passively collects signal + location samples on a
 * 60-second cadence while the user has passive collection enabled.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Sampling loop:
 *   1. [SignalReader.start] registers a telephony listener immediately.
 *   2. After [INITIAL_DELAY_MS] (2 s) for the first callback to arrive, the
 *      loop runs: take sample → save to Room → enqueue in sync_queue → delay 60 s.
 *   3. Each sample is skipped if no signal snapshot is available yet (loop retries
 *      on the next tick) or if the location fix times out.
 *   4. On [onDestroy], the coroutine scope is cancelled and [SignalReader.stop]
 *      is called.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Binder:
 *   Clients (HomeMapFragment) bind to this service to receive real-time
 *   [SignalSnapshot] updates and [CollectionStatus] changes for the signal card.
 *   Binding is optional — the service runs and collects data regardless.
 *
 *   TODO (HomeMapFragment): bind in onStart(), unbind in onStop(), observe
 *   binder.latestSignal and call viewModel.onNewSignalState() + onCollectionStatusChanged().
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * extends LifecycleService (not plain Service) so injected components can use
 * Lifecycle-aware coroutines if needed in future.
 */
@AndroidEntryPoint
class PassiveCollectionService : LifecycleService() {

    @Inject lateinit var signalReader: SignalReader
    @Inject lateinit var locationReader: LocationReader
    @Inject lateinit var assembler: SampleAssembler
    @Inject lateinit var measurementDao: MeasurementDao
    @Inject lateinit var syncQueueDao: SyncQueueDao
    @Inject lateinit var workManager: WorkManager

    // Coroutine scope for the sampling loop — cancelled in onDestroy
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var samplingJob: Job? = null

    // Session ID shared across all samples in this service start
    private val sessionId = UUID.randomUUID().toString()

    // Live status exposed to bound clients
    private val _status = kotlinx.coroutines.flow.MutableStateFlow(CollectionStatus.IDLE)

    // ── Binder ───────────────────────────────────────────────────────────────

    private val binder = PassiveCollectionBinder()

    inner class PassiveCollectionBinder : Binder() {
        /** Most recent signal snapshot from the telephony listener. */
        val latestSignal: StateFlow<SignalSnapshot?> get() = signalReader.snapshot

        /** Current sampling loop status. */
        val collectionStatus: StateFlow<CollectionStatus> get() = _status

        fun getService(): PassiveCollectionService = this@PassiveCollectionService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            Timber.d("PassiveCollectionService: stop action received")
            stopSelf()
            return START_NOT_STICKY
        }

        Timber.d("PassiveCollectionService: starting (session $sessionId)")
        startForeground(NOTIFICATION_ID, buildNotification())
        signalReader.start()
        startSamplingLoop()

        return START_STICKY
    }

    override fun onDestroy() {
        Timber.d("PassiveCollectionService: destroying")
        samplingJob?.cancel()
        serviceScope.cancel()
        signalReader.stop()
        _status.value = CollectionStatus.IDLE
        super.onDestroy()
    }

    // ── Sampling loop ─────────────────────────────────────────────────────────

    private fun startSamplingLoop() {
        samplingJob = serviceScope.launch {
            // Brief delay for the telephony callback to fire and populate snapshot
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                takeSample()
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private suspend fun takeSample() {
        val signal = signalReader.snapshot.value
        if (signal == null) {
            Timber.d("PassiveCollection: no signal snapshot yet — skipping tick")
            return
        }

        val location = try {
            locationReader.getLocation(timeoutMs = LOCATION_TIMEOUT_MS)
        } catch (e: SecurityException) {
            // Permission was revoked while the service was running
            Timber.w("PassiveCollection: location permission revoked — skipping sample")
            _status.value = CollectionStatus.IDLE
            return
        }

        if (location == null) {
            Timber.d("PassiveCollection: location timeout — skipping sample")
            return
        }

        if (location.accuracy > ACCURACY_FILTER_METERS) {
            Timber.d("PassiveCollection: GPS accuracy ${location.accuracy} m > filter — skipping")
            return
        }

        val measurement = assembler.assemble(signal, location, sessionId)

        measurementDao.insert(measurement)

        syncQueueDao.enqueue(
            SyncQueueEntity(
                entityType     = SyncRepository.ENTITY_MEASUREMENT,
                entityId       = measurement.id,
                createdAtMs    = measurement.timestamp,
                nextAttemptMs  = measurement.timestamp   // eligible for upload immediately
            )
        )

        // Trigger an immediate upload attempt (unique work — multiple samples collapse into one job)
        SyncWorker.enqueue(workManager)

        _status.value = if (signal.isNoService) CollectionStatus.NO_SERVICE
                        else CollectionStatus.MEASURING

        Timber.d(
            "PassiveCollection: saved ${measurement.id} " +
            "tier=${signal.signalTier} net=${signal.networkType} " +
            "acc=${location.accuracy} m"
        )
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, PassiveCollectionService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_arrow_back)   // TODO: replace with ic_notif_signal
            .setContentTitle(getString(R.string.notif_passive_title))
            .setContentText(getString(R.string.notif_passive_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notif_passive_action_stop),
                stopIntent
            )
            .build()
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "passive_collection"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_STOP = "com.nybroadband.mobile.ACTION_STOP_PASSIVE"

        /** Wait for the first telephony callback before sampling. */
        private const val INITIAL_DELAY_MS = 2_000L

        /** Sample every 60 seconds. */
        const val SAMPLE_INTERVAL_MS = 60_000L

        /** Location fix must arrive within 5 seconds or the sample is skipped. */
        private const val LOCATION_TIMEOUT_MS = 5_000L

        /**
         * Skip samples where GPS accuracy is worse than 100 m.
         *
         * The map renderer already filters to <= 50 m for display, but we allow
         * up to 100 m in storage so network-only fixes (cell/WiFi) are still
         * captured in areas with no GPS signal (e.g., deep rural or indoor).
         * The accuracy value is stored and the backend applies its own filter.
         */
        private const val ACCURACY_FILTER_METERS = 100f

        /** Helper for components that want to start this service. */
        fun startIntent(context: Context) =
            Intent(context, PassiveCollectionService::class.java)

        /** Helper for components that want to stop this service. */
        fun stopIntent(context: Context) =
            Intent(context, PassiveCollectionService::class.java).apply {
                action = ACTION_STOP
            }
    }
}

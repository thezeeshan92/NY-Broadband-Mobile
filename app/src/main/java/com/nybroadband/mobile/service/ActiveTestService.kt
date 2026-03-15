package com.nybroadband.mobile.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.nybroadband.mobile.R
import com.nybroadband.mobile.domain.model.ActiveTestState
import com.nybroadband.mobile.domain.model.TestConfig
import com.nybroadband.mobile.presentation.MainActivity
import com.nybroadband.mobile.service.ActiveTestService.Companion.ACTION_START_TEST
import com.nybroadband.mobile.service.active.TestOrchestrator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service for active speed tests.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * MVP scope:
 *   Manual tests (ACTIVE_MANUAL) run entirely in [TestInProgressViewModel.viewModelScope]
 *   and do NOT use this service. The ViewModel scope survives fragment navigation
 *   and is cancelled if the user explicitly presses back during a test.
 *
 *   This service is scaffolded for ACTIVE_RECURRING tests driven by
 *   [RecurringSetupFragment] / WorkManager, where the test must survive
 *   the user leaving the app. The recurring test logic (looping, scheduling)
 *   is a TODO for the next implementation task.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Binder:
 *   Clients ([TestInProgressFragment] when started by the service) bind to
 *   read [testState] for live UI updates.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Starting:
 *   Send an Intent with [ACTION_START_TEST] and a serialized [TestConfig] in extras.
 *   For recurring tests, WorkManager starts this service via the extra action.
 */
@AndroidEntryPoint
class ActiveTestService : LifecycleService() {

    @Inject
    lateinit var orchestrator: TestOrchestrator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var testJob: Job? = null

    private val _testState = MutableStateFlow<ActiveTestState>(ActiveTestState.Idle)

    // ── Binder ───────────────────────────────────────────────────────────────

    inner class ActiveTestBinder : Binder() {
        val testState: StateFlow<ActiveTestState> get() = _testState.asStateFlow()
        fun getService(): ActiveTestService = this@ActiveTestService
    }

    private val binder = ActiveTestBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                testJob?.cancel()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START_TEST -> {
                val durationSec = intent.getIntExtra(EXTRA_DURATION_SEC, TestConfig.DEFAULT_SECONDS)
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
                val testType = intent.getStringExtra(EXTRA_TEST_TYPE) ?: TestConfig.TYPE_RECURRING

                startForeground(NOTIFICATION_ID, buildNotification())
                runTest(TestConfig(durationSec, serverId, testType))
            }

            else -> {
                Timber.w("ActiveTestService: unknown action ${intent?.action}")
            }
        }

        return START_NOT_STICKY  // don't restart automatically — tests are on-demand
    }

    override fun onDestroy() {
        testJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Test execution ────────────────────────────────────────────────────────

    /**
     * Runs a test via [TestOrchestrator] and updates [_testState].
     *
     * TODO (recurring mode): wrap this in a loop driven by RecurringSetupConfig;
     *   emit each result, wait for the configured interval, then run again.
     */
    private fun runTest(config: TestConfig) {
        testJob = serviceScope.launch {
            orchestrator.run(config).collect { state ->
                _testState.value = state
                updateNotification(state)
                if (state is ActiveTestState.Completed || state is ActiveTestState.Failed) {
                    stopSelf()
                }
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(label: String = "Running test…"): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ActiveTestService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_arrow_back)   // TODO: replace with ic_notif_test
            .setContentTitle(getString(R.string.notif_active_test_title))
            .setContentText(label)
            .setContentIntent(contentIntent)
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

    private fun updateNotification(state: ActiveTestState) {
        val label = when (state) {
            is ActiveTestState.Running -> state.phase.displayLabel
            is ActiveTestState.Completed -> getString(R.string.notif_active_test_done)
            is ActiveTestState.Failed -> getString(R.string.notif_active_test_failed)
            ActiveTestState.Idle -> return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(label))
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "active_test"
        private const val NOTIFICATION_ID = 1002

        const val ACTION_START_TEST = "com.nybroadband.mobile.ACTION_START_ACTIVE_TEST"
        const val ACTION_STOP = "com.nybroadband.mobile.ACTION_STOP_ACTIVE_TEST"

        const val EXTRA_DURATION_SEC = "duration_sec"
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_TEST_TYPE = "test_type"

        fun startIntent(context: Context, config: TestConfig): Intent =
            Intent(context, ActiveTestService::class.java).apply {
                action = ACTION_START_TEST
                putExtra(EXTRA_DURATION_SEC, config.durationSeconds)
                putExtra(EXTRA_SERVER_ID, config.serverId)
                putExtra(EXTRA_TEST_TYPE, config.testType)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, ActiveTestService::class.java).apply { action = ACTION_STOP }
    }
}

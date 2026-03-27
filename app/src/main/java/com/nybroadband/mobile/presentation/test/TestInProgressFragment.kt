package com.nybroadband.mobile.presentation.test

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.nybroadband.mobile.R
import com.nybroadband.mobile.databinding.FragmentTestInProgressBinding
import com.nybroadband.mobile.domain.model.ActiveTestState
import com.nybroadband.mobile.domain.model.TestConfig
import com.nybroadband.mobile.domain.model.TestPhase
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Displays real-time progress for a running speed test using a CoverageMap-style UI:
 *   • Gradient download/upload banner at the top
 *   • Ping + data used row
 *   • Overall progress bar (phone → wifi)
 *   • Semi-circular [SpeedometerView] animated to current speed
 *   • Large speed number + unit label
 *   • Context tabs at the bottom
 *
 * Receives test config as bundle args ([ARG_DURATION_SEC] + [ARG_SERVER_ID]).
 * Back press while running shows a confirmation dialog.
 */
@AndroidEntryPoint
class TestInProgressFragment : Fragment() {

    @Inject lateinit var settingsStore: SpeedTestSettingsStore

    private var _binding: FragmentTestInProgressBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TestInProgressViewModel by viewModels()

    // TrafficStats snapshot taken just before the test starts (per-app UID bytes)
    private var testStartTrafficBytes = 0L
    private var testStartedOnWifi    = false
    // Guard to prevent double-counting on repeated Completed state emissions
    private var dataUsageRecorded    = false

    companion object {
        const val ARG_DURATION_SEC = "arg_duration_sec"
        const val ARG_SERVER_ID    = "arg_server_id"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTestInProgressBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        populateHeaderInfo()
        setupBackPressInterception()
        observeViewModel()

        binding.btnClose.setOnClickListener { confirmCancel() }

        // Snapshot traffic stats before the test so we can measure bytes used
        val uid = Process.myUid()
        val rx  = TrafficStats.getUidRxBytes(uid)
        val tx  = TrafficStats.getUidTxBytes(uid)
        val unsupported = TrafficStats.UNSUPPORTED.toLong()
        testStartTrafficBytes = if (rx != unsupported && tx != unsupported) rx + tx else 0L
        testStartedOnWifi = isOnWifi()
        dataUsageRecorded = false

        val config = TestConfig(
            durationSeconds = arguments?.getInt(ARG_DURATION_SEC, TestConfig.DEFAULT_SECONDS)
                ?.coerceIn(TestConfig.MIN_SECONDS, TestConfig.MAX_SECONDS)
                ?: TestConfig.DEFAULT_SECONDS,
            serverId = arguments?.getString(ARG_SERVER_ID),
        )
        viewModel.startTest(config)
    }

    // ── Header: carrier + device info ─────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun populateHeaderInfo() {
        val telephony = requireContext().getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val carrier = telephony.networkOperatorName.takeIf { it.isNotBlank() }
            ?: getString(R.string.speed_detecting)
        binding.tvCarrierName.text = carrier

        val deviceModel = Build.MODEL
        val connection = connectionLabel()
        binding.tvDeviceInfo.text = "$deviceModel · $connection"
    }

    private fun isOnWifi(): Boolean {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nc = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun connectionLabel(): String {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nc = cm.getNetworkCapabilities(cm.activeNetwork) ?: return getString(R.string.speed_cellular)
        return when {
            nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> getString(R.string.speed_wifi)
            nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getString(R.string.speed_cellular)
            else -> getString(R.string.speed_cellular)
        }
    }

    // ── Back press interception ───────────────────────────────────────────────

    private fun setupBackPressInterception() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.state.value is ActiveTestState.Running) {
                        confirmCancel()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    private fun confirmCancel() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.test_cancel_title)
            .setMessage(R.string.test_cancel_message)
            .setPositiveButton(R.string.test_cancel_confirm) { _, _ -> viewModel.cancelTest() }
            .setNegativeButton(R.string.test_cancel_dismiss, null)
            .show()
    }

    // ── ViewModel observation ─────────────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.state.collect { state -> renderState(state) }
                }

                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is TestInProgressEvent.NavigateToResult ->
                                findNavController().navigate(
                                    R.id.action_testInProgress_to_testResult,
                                    bundleOf("measurementId" to event.measurementId),
                                )
                            is TestInProgressEvent.NavigateBack -> {
                                event.errorMessage?.let { msg ->
                                    Snackbar.make(
                                        requireActivity().window.decorView,
                                        msg,
                                        Snackbar.LENGTH_LONG,
                                    ).show()
                                }
                                findNavController().navigateUp()
                            }
                        }
                    }
                }
            }
        }
    }

    // ── State rendering ───────────────────────────────────────────────────────

    private fun renderState(state: ActiveTestState) {
        when (state) {
            ActiveTestState.Idle -> {
                binding.tvPhaseLabel.text = getString(R.string.test_phase_starting)
                binding.tvSpeedNumber.text = "--"
                binding.tvSpeedUnit.text = getString(R.string.test_speed_unit_mbps)
                binding.progressBar.isIndeterminate = true
                binding.progressBarDownload.progress = 0
                binding.progressBarUpload.progress = 0
                binding.speedometerView.reset()
            }

            is ActiveTestState.Running -> renderRunning(state)

            is ActiveTestState.Completed -> {
                binding.tvPhaseLabel.text = getString(R.string.test_phase_done)
                binding.tvSpeedNumber.text = "%.1f".format(state.downloadMbps)
                binding.tvSpeedUnit.text = getString(R.string.test_speed_unit_mbps)
                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = 100
                binding.progressBarDownload.progress = 100
                binding.progressBarUpload.progress = 100
                binding.speedometerView.setSpeed(state.downloadMbps.toFloat())
                recordDataUsage()
            }

            is ActiveTestState.Failed -> {
                binding.tvPhaseLabel.text = getString(R.string.test_phase_failed)
                binding.tvSpeedNumber.text = "--"
                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = 0
                binding.speedometerView.reset()
            }
        }
    }

    private fun renderRunning(state: ActiveTestState.Running) {
        // Phase label
        binding.tvPhaseLabel.text = getString(
            when (state.phase) {
                TestPhase.LATENCY  -> R.string.test_phase_latency
                TestPhase.DOWNLOAD -> R.string.test_phase_download
                TestPhase.UPLOAD   -> R.string.test_phase_upload
            },
        )

        // Phase dots
        binding.dotLatency.isActivated  = state.phase != TestPhase.LATENCY
        binding.dotDownload.isActivated = state.phase == TestPhase.DOWNLOAD || state.phase == TestPhase.UPLOAD
        binding.dotUpload.isActivated   = state.phase == TestPhase.UPLOAD

        // Current speed for the big number and speedometer
        val activeSpeed = when (state.phase) {
            TestPhase.LATENCY  -> null
            TestPhase.DOWNLOAD -> state.downloadMbps
            TestPhase.UPLOAD   -> state.uploadMbps
        }

        binding.tvSpeedNumber.text = activeSpeed?.let { "%.1f".format(it) } ?: "--"
        binding.tvSpeedUnit.text = if (activeSpeed != null) {
            getString(R.string.test_speed_unit_mbps)
        } else {
            getString(R.string.test_speed_unit_latency)
        }

        // Drive the speedometer needle
        val speedForGauge = activeSpeed?.toFloat() ?: 0f
        binding.speedometerView.setSpeed(speedForGauge)

        // Download banner value + mini progress bar
        binding.tvDownloadResult.text = state.downloadMbps?.let { "%.1f".format(it) } ?: "--"
        binding.progressBarDownload.isIndeterminate = false
        binding.progressBarDownload.progress = when (state.phase) {
            TestPhase.LATENCY  -> 0
            TestPhase.DOWNLOAD -> (state.progressFraction * 100).toInt()
            TestPhase.UPLOAD   -> 100
        }

        // Upload banner value + mini progress bar
        binding.tvUploadResult.text = state.uploadMbps?.let { "%.1f".format(it) } ?: "--"
        binding.progressBarUpload.isIndeterminate = false
        binding.progressBarUpload.progress = when (state.phase) {
            TestPhase.LATENCY, TestPhase.DOWNLOAD -> 0
            TestPhase.UPLOAD -> (state.progressFraction * 100).toInt()
        }

        // Latency / ping row
        binding.tvLatency.text = state.latencyMs
            ?.let { getString(R.string.speed_ping_fmt, it) }
            ?: getString(R.string.speed_ping_placeholder)

        // Packet loss (hidden view — kept for binding compatibility)
        binding.tvPacketLoss.text = state.retransmitRate
            ?.let { getString(R.string.test_packet_loss_fmt, it * 100.0) } ?: "--"

        // Overall progress bar (phone → wifi)
        binding.progressBar.isIndeterminate = state.phase == TestPhase.LATENCY
        if (state.phase != TestPhase.LATENCY) {
            val overall = when (state.phase) {
                TestPhase.DOWNLOAD -> 33 + (state.progressFraction * 34).toInt()
                TestPhase.UPLOAD   -> 67 + (state.progressFraction * 33).toInt()
                else -> 0
            }
            binding.progressBar.progress = overall
        }
    }

    /** Calculates bytes used during this test and saves them to [settingsStore]. */
    private fun recordDataUsage() {
        if (dataUsageRecorded || testStartTrafficBytes == 0L) return
        val uid = Process.myUid()
        val rx  = TrafficStats.getUidRxBytes(uid)
        val tx  = TrafficStats.getUidTxBytes(uid)
        val unsupported = TrafficStats.UNSUPPORTED.toLong()
        if (rx == unsupported || tx == unsupported) return
        val delta = (rx + tx - testStartTrafficBytes).coerceAtLeast(0L)
        settingsStore.addTestDataBytes(delta, testStartedOnWifi)
        dataUsageRecorded = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
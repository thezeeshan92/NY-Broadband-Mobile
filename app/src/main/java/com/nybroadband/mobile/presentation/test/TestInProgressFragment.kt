package com.nybroadband.mobile.presentation.test

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
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
import kotlinx.coroutines.launch

/**
 * Displays real-time progress for a running speed test.
 *
 * Receives the test config as bundle args ([ARG_DURATION_SEC] + [ARG_SERVER_ID])
 * and passes it to [TestInProgressViewModel.startTest].
 *
 * Back press is intercepted while a test is active — the user sees a confirmation
 * dialog before cancelling, because results are discarded on cancel.
 *
 * Navigation:
 *   - On [TestInProgressEvent.NavigateToResult]: pushes TestResultFragment with measurementId.
 *   - On [TestInProgressEvent.NavigateBack]: pops back to ManualTestFragment.
 */
@AndroidEntryPoint
class TestInProgressFragment : Fragment() {

    private var _binding: FragmentTestInProgressBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TestInProgressViewModel by viewModels()

    companion object {
        const val ARG_DURATION_SEC = "arg_duration_sec"
        const val ARG_SERVER_ID    = "arg_server_id"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTestInProgressBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupBackPressInterception()
        observeViewModel()

        binding.tvCancel.setOnClickListener { confirmCancel() }

        // Start test — guard handles double-start on rotation
        val config = TestConfig(
            durationSeconds = arguments?.getInt(ARG_DURATION_SEC, TestConfig.DEFAULT_SECONDS)
                ?: TestConfig.DEFAULT_SECONDS,
            serverId = arguments?.getString(ARG_SERVER_ID)
        )
        viewModel.startTest(config)
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
            }
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
                                    bundleOf("measurementId" to event.measurementId)
                                )
                            is TestInProgressEvent.NavigateBack -> {
                                // Show error snackbar on the parent (ManualTestFragment)
                                // after popping back to it, so the message is visible.
                                event.errorMessage?.let { msg ->
                                    Snackbar.make(requireActivity().window.decorView, msg, Snackbar.LENGTH_LONG).show()
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
                binding.tvPhaseLabel.text    = getString(R.string.test_phase_starting)
                binding.tvSpeedNumber.text   = "—"
                binding.progressBar.isIndeterminate = true
            }

            is ActiveTestState.Running -> {
                renderRunning(state)
            }

            is ActiveTestState.Completed -> {
                // Navigation event will fire immediately — just show a brief "Done"
                binding.tvPhaseLabel.text  = getString(R.string.test_phase_done)
                binding.tvSpeedNumber.text = "%.1f".format(state.downloadMbps)
                binding.tvSpeedUnit.text   = getString(R.string.test_speed_unit_mbps)
                binding.progressBar.progress = 100
                binding.progressBar.isIndeterminate = false
            }

            is ActiveTestState.Failed -> {
                binding.tvPhaseLabel.text  = getString(R.string.test_phase_failed)
                binding.tvSpeedNumber.text = "—"
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
            }
        )

        // Phase step dots
        binding.dotLatency.isActivated  = state.phase != TestPhase.LATENCY ||
                                           state.progressFraction >= 1f
        binding.dotDownload.isActivated = state.phase == TestPhase.DOWNLOAD ||
                                           state.phase == TestPhase.UPLOAD
        binding.dotUpload.isActivated   = state.phase == TestPhase.UPLOAD

        // Big speed number — show the active phase metric
        val speed = when (state.phase) {
            TestPhase.LATENCY  -> null
            TestPhase.DOWNLOAD -> state.downloadMbps
            TestPhase.UPLOAD   -> state.uploadMbps
        }
        binding.tvSpeedNumber.text = speed?.let { "%.1f".format(it) } ?: "—"
        binding.tvSpeedUnit.text   = if (speed != null) {
            getString(R.string.test_speed_unit_mbps)
        } else {
            getString(R.string.test_speed_unit_latency)
        }

        // ── Metric cards (always visible, updated as values arrive) ──────────

        // Download card
        binding.tvDownloadResult.text = state.downloadMbps
            ?.let { "%.1f".format(it) } ?: "--"

        // Upload card
        binding.tvUploadResult.text = state.uploadMbps
            ?.let { "%.1f".format(it) } ?: "--"

        // Latency (ping) card
        binding.tvLatency.text = state.latencyMs
            ?.let { getString(R.string.test_latency_fmt, it) } ?: "--"

        // Packet loss card — shown as a percentage; "--" until TCP data arrives
        binding.tvPacketLoss.text = state.retransmitRate
            ?.let { getString(R.string.test_packet_loss_fmt, it * 100.0) } ?: "--"

        // Progress bar
        binding.progressBar.isIndeterminate = state.phase == TestPhase.LATENCY
        if (state.phase != TestPhase.LATENCY) {
            binding.progressBar.progress = (state.progressFraction * 100).toInt()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

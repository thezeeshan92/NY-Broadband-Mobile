package com.nybroadband.mobile.presentation.test

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.nybroadband.mobile.R
import com.nybroadband.mobile.data.local.db.entity.MeasurementEntity
import com.nybroadband.mobile.databinding.FragmentTestResultBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Displays the full result of a completed speed test or passive sample.
 *
 * CoverageMap dark-style layout:
 *   • Header:  back button · carrier name + device info · tier pill
 *   • Banner:  Download / Upload Mbps (gradient card)
 *   • Pills:   Ping · Jitter · Data Used
 *   • Details: Network, Carrier, Latency, Jitter, Server, Duration, Measured at
 *   • Technical details (collapsed) — RSRP, signal bars, GPS, device, version, session
 *   • Bottom: Close (outlined) + Test Again (gradient)
 */
@AndroidEntryPoint
class TestResultFragment : Fragment() {

    private var _binding: FragmentTestResultBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TestResultViewModel by viewModels()

    private var advancedExpanded = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTestResultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClose.setOnClickListener { findNavController().navigateUp() }
        binding.btnDone.setOnClickListener { findNavController().navigateUp() }
        binding.btnTestAgain.setOnClickListener { navigateToTestAgain() }
        binding.tvAdvancedToggle.setOnClickListener { toggleAdvanced() }
        binding.btnShare.setOnClickListener { shareResult() }

        val id = arguments?.getString("measurementId") ?: ""
        viewModel.load(id)

        observeViewModel()
    }

    private fun navigateToTestAgain() {
        findNavController().navigate(
            R.id.speedTestFragment,
            null,
            NavOptions.Builder()
                .setPopUpTo(R.id.speedTestFragment, true)
                .build()
        )
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        TestResultUiState.Loading  -> showLoading()
                        TestResultUiState.NotFound -> showNotFound()
                        is TestResultUiState.Ready -> showResult(state.measurement)
                    }
                }
            }
        }
    }

    // ── State rendering ───────────────────────────────────────────────────────

    private fun showLoading() {
        binding.contentGroup.isVisible = false
    }

    private fun showNotFound() {
        binding.contentGroup.isVisible = true
        binding.tvTierBadge.text     = "--"
        binding.tvTierSubLabel.text  = getString(R.string.result_not_found)
        binding.tvDownloadSpeed.text = "--"
        binding.tvUploadSpeed.text   = "--"
        binding.tvLatencyPill.text   = "--"
        binding.tvJitterPill.text    = "--"
        binding.tvDataUsed.text      = "--"
        binding.tvLatencyValue.text  = "--"
        binding.tvJitterValue.text   = "--"
        binding.tvNetworkType.text   = "--"
        binding.tvCarrierDetail.text = "--"
        binding.tvServerName.text    = "--"
        binding.tvDuration.text      = "--"
        binding.tvTimestamp.text     = "--"
    }

    private fun showResult(m: MeasurementEntity) {
        binding.contentGroup.isVisible = true

        // ── Header: carrier + device ──────────────────────────────────────────
        binding.tvCarrierName.text = m.carrierName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.speed_detecting)
        binding.tvDeviceInfo.text = buildString {
            append(m.deviceModel)
            val net = m.networkType.takeIf { it != "UNKNOWN" }
            if (net != null) append(" · $net")
        }

        // ── Tier badge ────────────────────────────────────────────────────────
        val (tierLabel, tierColorRes) = when (m.signalTier) {
            "GOOD" -> getString(R.string.signal_good) to R.color.signal_good
            "FAIR" -> getString(R.string.signal_fair) to R.color.signal_fair
            "WEAK" -> getString(R.string.signal_weak) to R.color.signal_weak
            "POOR" -> getString(R.string.signal_poor) to R.color.signal_poor
            else   -> getString(R.string.signal_unknown) to R.color.signal_none
        }
        binding.tvTierBadge.text = tierLabel
        binding.tvTierBadge.backgroundTintList =
            requireContext().getColorStateList(tierColorRes)

        // ── Sub-label ─────────────────────────────────────────────────────────
        binding.tvTierSubLabel.text = buildString {
            when {
                m.deadZoneType != null -> append(getString(R.string.result_sub_dead_zone))
                m.sampleType in setOf("ACTIVE_MANUAL", "ACTIVE_RECURRING") -> {
                    append(getString(R.string.result_sub_speed_test))
                    if (m.networkType != "UNKNOWN") append(" · ${m.networkType}")
                }
                else -> {
                    append(getString(R.string.result_sub_passive))
                    if (m.networkType != "UNKNOWN") append(" · ${m.networkType}")
                }
            }
        }

        // ── Speed banner ──────────────────────────────────────────────────────
        binding.tvDownloadSpeed.text = m.downloadSpeedMbps?.let { "%.1f".format(it) } ?: "--"
        binding.tvUploadSpeed.text   = m.uploadSpeedMbps?.let { "%.1f".format(it) }   ?: "--"

        // ── Ping / Jitter / Data pills ────────────────────────────────────────
        binding.tvLatencyPill.text = m.latencyMs?.toString() ?: "--"
        binding.tvJitterPill.text  = m.jitterMs?.toString()  ?: "--"

        val totalBytes = ((m.bytesDownloaded ?: 0L) + (m.bytesUploaded ?: 0L))
        binding.tvDataUsed.text = if (totalBytes > 0L) {
            "%.1f".format(totalBytes / (1024.0 * 1024.0))
        } else "--"

        // ── Detail rows ───────────────────────────────────────────────────────
        binding.tvNetworkType.text  = m.networkType.takeIf { it != "UNKNOWN" } ?: "--"
        binding.tvCarrierDetail.text = m.carrierName ?: "--"
        binding.tvLatencyValue.text = m.latencyMs?.let {
            getString(R.string.test_result_latency_fmt, it)
        } ?: "--"
        binding.tvJitterValue.text  = m.jitterMs?.let {
            getString(R.string.test_result_jitter_fmt, it)
        } ?: "--"
        binding.tvServerName.text   = m.testServerName ?: "--"
        binding.tvDuration.text     = m.testDurationSec?.let {
            getString(R.string.test_result_duration_fmt, it)
        } ?: "--"
        binding.tvTimestamp.text = SimpleDateFormat(
            "MMM d, yyyy · h:mm a", Locale.getDefault()
        ).format(Date(m.timestamp))

        // ── Placeholder badge ─────────────────────────────────────────────────
        binding.tvPlaceholderBadge.isVisible = (m.testServerName == "Placeholder Test Server")

        // ── Sync status ───────────────────────────────────────────────────────
        val (syncText, showSync) = when (m.uploadStatus) {
            "PENDING" -> getString(R.string.sync_pending) to true
            "FAILED"  -> getString(R.string.sync_failed)  to true
            else      -> ""                               to false
        }
        binding.tvSyncStatus.isVisible = showSync
        binding.tvSyncStatus.text = syncText

        // ── Interpretation ────────────────────────────────────────────────────
        val interpResId = when {
            (m.downloadSpeedMbps ?: 0.0) >= 100.0 -> R.string.result_interp_excellent
            (m.downloadSpeedMbps ?: 0.0) >= 25.0  -> R.string.result_interp_good
            (m.downloadSpeedMbps ?: 0.0) >= 10.0  -> R.string.result_interp_moderate
            (m.downloadSpeedMbps ?: 0.0) >= 3.0   -> R.string.result_interp_limited
            else -> R.string.result_interp_poor
        }
        binding.tvInterpretation.setText(interpResId)

        // ── Advanced section ──────────────────────────────────────────────────
        binding.tvRsrp.text        = m.rsrp?.let { getString(R.string.result_adv_rsrp_fmt, it) } ?: "--"
        binding.tvSignalBars.text  = "${m.signalBars} / 4"
        binding.tvGpsAccuracy.text = "±%.0f m".format(m.gpsAccuracyMeters)
        binding.tvDevice.text      = m.deviceModel
        binding.tvAppVersion.text  = m.appVersion
        binding.tvSessionId.text   = m.sessionId ?: "--"
    }

    // ── Share ─────────────────────────────────────────────────────────────────

    private fun shareResult() {
        val state = (viewModel.uiState.value as? TestResultUiState.Ready)?.measurement ?: return
        val down = state.downloadSpeedMbps?.let { "%.1f".format(it) } ?: "--"
        val up   = state.uploadSpeedMbps?.let { "%.1f".format(it) } ?: "--"
        val ping = state.latencyMs?.toString() ?: "--"
        val text = "My NY Broadband speed test result:\n" +
                "↓ Download: $down Mbps\n" +
                "↑ Upload:   $up Mbps\n" +
                "Ping:       $ping ms\n\n" +
                "Tested via NY Broadband Mobile"
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            },
            getString(R.string.action_share)
        ))
    }

    // ── Advanced toggle ───────────────────────────────────────────────────────

    private fun toggleAdvanced() {
        advancedExpanded = !advancedExpanded
        binding.layoutAdvanced.isVisible = advancedExpanded
        binding.tvAdvancedToggle.text = getString(
            if (advancedExpanded) R.string.result_advanced_hide
            else R.string.result_advanced_show
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

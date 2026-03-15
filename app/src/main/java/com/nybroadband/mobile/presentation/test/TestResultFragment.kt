package com.nybroadband.mobile.presentation.test

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
import androidx.navigation.fragment.findNavController
import com.nybroadband.mobile.R
import com.nybroadband.mobile.data.local.db.entity.MeasurementEntity
import com.nybroadband.mobile.databinding.FragmentTestResultBinding
import com.nybroadband.mobile.presentation.results.qualityLabel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Displays the full result of a completed speed test or passive sample.
 *
 * Receives [measurementId] as a bundle argument — from [TestInProgressFragment] after a
 * new test, or from [ResultsFragment] when the user taps a history row.
 *
 * Screen sections:
 *   1. Tier badge card     — color-coded quality label ("Good", "Fair", …)
 *   2. Speed cards         — Download and Upload Mbps
 *   3. Primary detail rows — Latency, Jitter, Network, Carrier, Server, Duration, Timestamp
 *   4. Advanced section    — Raw diagnostic data (RSRP, GPS accuracy, device, etc.)
 *                            Collapsed by default; tapping tvAdvancedToggle reveals it.
 *
 * The advanced section is intentionally opt-in — raw dBm values and session IDs are
 * diagnostic data, not intended for general users.
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

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.btnDone.setOnClickListener { findNavController().navigateUp() }

        binding.tvAdvancedToggle.setOnClickListener { toggleAdvanced() }

        val id = arguments?.getString("measurementId") ?: ""
        viewModel.load(id)

        observeViewModel()
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
        binding.tvTierBadge.text      = "--"
        binding.tvTierSubLabel.text   = getString(R.string.result_not_found)
        binding.tvDownloadSpeed.text  = "--"
        binding.tvUploadSpeed.text    = "--"
        binding.tvLatencyValue.text   = "--"
        binding.tvJitterValue.text    = "--"
        binding.tvNetworkType.text    = "--"
        binding.tvCarrierName.text    = "--"
        binding.tvServerName.text     = "--"
        binding.tvDuration.text       = "--"
        binding.tvTimestamp.text      = "--"
    }

    private fun showResult(m: MeasurementEntity) {
        binding.contentGroup.isVisible = true

        // ── Tier badge ────────────────────────────────────────────────────────
        val (tierLabel, tierColorRes) = when (m.signalTier) {
            "GOOD" -> getString(R.string.signal_good) to R.color.signal_good
            "FAIR" -> getString(R.string.signal_fair) to R.color.signal_fair
            "WEAK" -> getString(R.string.signal_weak) to R.color.signal_weak
            "POOR" -> getString(R.string.signal_poor) to R.color.signal_poor
            else   -> getString(R.string.signal_unknown) to R.color.signal_none
        }
        binding.tvTierBadge.text = tierLabel
        binding.tierBadgeCard.setCardBackgroundColor(requireContext().getColor(tierColorRes))

        // Sub-label: "Speed test · 4G LTE" or "Passive reading" or "Dead zone"
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

        // Placeholder badge
        binding.tvPlaceholderBadge.isVisible = (m.testServerName == "Placeholder Test Server")

        // ── Speed metrics ─────────────────────────────────────────────────────
        binding.tvDownloadSpeed.text = m.downloadSpeedMbps?.let { "%.1f".format(it) } ?: "--"
        binding.tvUploadSpeed.text   = m.uploadSpeedMbps?.let { "%.1f".format(it) }   ?: "--"

        // ── Primary detail rows ───────────────────────────────────────────────
        binding.tvLatencyValue.text = m.latencyMs?.let {
            getString(R.string.test_result_latency_fmt, it)
        } ?: "--"
        binding.tvJitterValue.text  = m.jitterMs?.let {
            getString(R.string.test_result_jitter_fmt, it)
        } ?: "--"
        binding.tvNetworkType.text  = m.networkType.takeIf { it != "UNKNOWN" } ?: "--"
        binding.tvCarrierName.text  = m.carrierName ?: "--"
        binding.tvServerName.text   = m.testServerName ?: "--"
        binding.tvDuration.text     = m.testDurationSec?.let {
            getString(R.string.test_result_duration_fmt, it)
        } ?: "--"
        binding.tvTimestamp.text = SimpleDateFormat(
            "MMM d, yyyy · h:mm a", Locale.getDefault()
        ).format(Date(m.timestamp))

        // ── Sync status note ──────────────────────────────────────────────────
        val (syncText, showSync) = when (m.uploadStatus) {
            "PENDING" -> getString(R.string.sync_pending) to true
            "FAILED"  -> getString(R.string.sync_failed)  to true
            else      -> ""                               to false
        }
        binding.tvSyncStatus.isVisible = showSync
        binding.tvSyncStatus.text = syncText

        // ── Advanced section (pre-populate; stays hidden until toggled) ───────
        binding.tvRsrp.text        = m.rsrp?.let { getString(R.string.result_adv_rsrp_fmt, it) } ?: "--"
        binding.tvSignalBars.text  = "${m.signalBars} / 4"
        binding.tvGpsAccuracy.text = "±%.0f m".format(m.gpsAccuracyMeters)
        binding.tvDevice.text      = m.deviceModel
        binding.tvAppVersion.text  = m.appVersion
        binding.tvSessionId.text   = m.sessionId ?: "--"
    }

    // ── Advanced toggle ───────────────────────────────────────────────────────

    /**
     * Expands or collapses the technical details section.
     * Uses simple show/hide — no animation needed for MVP.
     */
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

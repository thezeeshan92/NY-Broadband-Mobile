package com.nybroadband.mobile.presentation.cellular

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.nybroadband.mobile.R
import com.nybroadband.mobile.databinding.BottomSheetCellularSettingsBinding
import dagger.hilt.android.AndroidEntryPoint

/**
 * Settings bottom sheet for the Cellular screen.
 * Mirrors the CoverageMap.com "Cellular Info Settings" dialog.
 *
 * Must be shown via [childFragmentManager] of [CellularFragment] so that
 * [activityViewModels] returns the same [CellularViewModel] instance.
 */
@AndroidEntryPoint
class CellularSettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetCellularSettingsBinding? = null
    private val binding get() = _binding!!

    // Same ViewModel instance shared with CellularFragment
    private val viewModel: CellularViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetCellularSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyCurrentSettings()
        setupListeners()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // ── Populate UI from current settings ────────────────────────────────────

    private fun applyCurrentSettings() {
        val s = viewModel.settings.value
        binding.switchAdvancedView.isChecked   = s.advancedView
        binding.switchHideNeighbors.isChecked  = s.hideNeighborCells
        binding.sliderRefreshFreq.value        = s.refreshIntervalSeconds.toFloat()
        updateRefreshLabel(s.refreshIntervalSeconds)
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    private fun setupListeners() {
        binding.btnClose.setOnClickListener { dismiss() }

        binding.switchAdvancedView.setOnCheckedChangeListener { _, checked ->
            viewModel.updateSettings(viewModel.settings.value.copy(advancedView = checked))
        }

        binding.switchHideNeighbors.setOnCheckedChangeListener { _, checked ->
            viewModel.updateSettings(viewModel.settings.value.copy(hideNeighborCells = checked))
        }

        binding.sliderRefreshFreq.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val secs = value.toInt()
                updateRefreshLabel(secs)
                viewModel.updateSettings(viewModel.settings.value.copy(refreshIntervalSeconds = secs))
            }
        }
    }

    private fun updateRefreshLabel(seconds: Int) {
        binding.tvRefreshValue.text =
            getString(R.string.cellular_setting_refresh_seconds_fmt, seconds)
    }
}

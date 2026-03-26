package com.nybroadband.mobile.presentation.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.SeekBar
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.ChipGroup
import com.nybroadband.mobile.R
import com.nybroadband.mobile.databinding.BottomSheetMapFiltersBinding
import kotlinx.coroutines.launch

/**
 * Full-screen style filters sheet; state is stored in [HomeViewModel] with mock data for QA.
 */
class MapFiltersBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetMapFiltersBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by viewModels(
        ownerProducer = { requireParentFragment() },
    )

    private var suppressSheetSync = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetMapFiltersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindSheetFromState(homeViewModel.mapFilterState.value)

        binding.btnFilterDoneTop.setOnClickListener { dismiss() }
        binding.btnFilterDoneBottom.setOnClickListener { dismiss() }
        binding.btnFilterReset.setOnClickListener {
            homeViewModel.resetMapFilter()
            bindSheetFromState(homeViewModel.mapFilterState.value)
        }

        binding.btnCountrySelect.setOnClickListener { anchor ->
            PopupMenu(requireContext(), anchor).apply {
                menu.add(0, 1, 0, R.string.map_country_us)
                menu.add(0, 2, 0, R.string.map_country_pk)
                setOnMenuItemClickListener { item ->
                    homeViewModel.setCountryFilter(
                        if (item.itemId == 2) MapCountryFilter.PK else MapCountryFilter.US
                    )
                    binding.btnCountrySelect.text = item.title
                    true
                }
            }.show()
        }

        binding.switchShowLegend.setOnCheckedChangeListener { _, checked ->
            if (suppressSheetSync) return@setOnCheckedChangeListener
            homeViewModel.setShowLegend(checked)
        }
        binding.switchMyDataOnly.setOnCheckedChangeListener { _, checked ->
            if (suppressSheetSync) return@setOnCheckedChangeListener
            homeViewModel.setMyDataOnly(checked)
        }
        binding.switchShowValues.setOnCheckedChangeListener { _, checked ->
            if (suppressSheetSync) return@setOnCheckedChangeListener
            homeViewModel.setShowMapValues(checked)
        }
        binding.switch3dView.setOnCheckedChangeListener { _, _ ->
            // Placeholder until Mapbox 3D terrain is wired.
        }

        binding.chipGroupCarrierMain.setOnCheckedStateChangeListener(
            ChipGroup.OnCheckedStateChangeListener { _, checkedIds ->
                if (suppressSheetSync || checkedIds.isEmpty()) return@OnCheckedStateChangeListener
                val id = checkedIds.first()
                homeViewModel.setCarrierFilter(
                    when (id) {
                        R.id.chipCarrierAtt -> MapCarrierFilter.ATT
                        R.id.chipCarrierTmo -> MapCarrierFilter.TMO
                        R.id.chipCarrierVzw -> MapCarrierFilter.VZW
                        else -> MapCarrierFilter.ALL
                    }
                )
            }
        )

        binding.chipGroupTechnology.setOnCheckedStateChangeListener(
            ChipGroup.OnCheckedStateChangeListener { _, checkedIds ->
                if (suppressSheetSync || checkedIds.isEmpty()) return@OnCheckedStateChangeListener
                when (checkedIds.first()) {
                    R.id.chipTech5g -> homeViewModel.setNetworkFilter(MapNetworkFilter.NR_ONLY)
                    R.id.chipTechLte -> homeViewModel.setNetworkFilter(MapNetworkFilter.LTE_5G)
                    else -> homeViewModel.setNetworkFilter(MapNetworkFilter.ALL)
                }
            }
        )

        binding.chipGroupColor.setOnCheckedStateChangeListener(
            ChipGroup.OnCheckedStateChangeListener { _, checkedIds ->
                if (suppressSheetSync || checkedIds.isEmpty()) return@OnCheckedStateChangeListener
                homeViewModel.setColorMode(
                    when (checkedIds.first()) {
                        R.id.chipColorHeatmap -> MapColorMode.HEATMAP
                        R.id.chipColorBestCarrier -> MapColorMode.BEST_CARRIER
                        else -> MapColorMode.CARRIER
                    }
                )
            }
        )

        binding.chipGroupMapAggregate.setOnCheckedStateChangeListener(
            ChipGroup.OnCheckedStateChangeListener { _, checkedIds ->
                if (suppressSheetSync || checkedIds.isEmpty()) return@OnCheckedStateChangeListener
                homeViewModel.setMetricFilter(
                    when (checkedIds.first()) {
                        R.id.chipMapBest -> MapMetricFilter.BEST
                        R.id.chipMapMedian -> MapMetricFilter.MEDIAN
                        else -> MapMetricFilter.AVERAGE
                    }
                )
            }
        )

        binding.chipGroupMapMetric.setOnCheckedStateChangeListener(
            ChipGroup.OnCheckedStateChangeListener { _, checkedIds ->
                if (suppressSheetSync || checkedIds.isEmpty()) return@OnCheckedStateChangeListener
                homeViewModel.setSheetMetricKind(
                    when (checkedIds.first()) {
                        R.id.chipMetricUpload -> MapSheetMetricKind.UPLOAD
                        R.id.chipMetricLatency -> MapSheetMetricKind.LATENCY
                        else -> MapSheetMetricKind.DOWNLOAD
                    }
                )
            }
        )

        bindSpeedSeek(binding.seekMinDownload, binding.valueMinDownload)
        bindSpeedSeek(binding.seekMaxDownload, binding.valueMaxDownload)
        bindSpeedSeek(binding.seekMinUpload, binding.valueMinUpload)
        bindSpeedSeek(binding.seekMaxUpload, binding.valueMaxUpload)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                homeViewModel.mapFilterState.collect { bindSheetFromState(it) }
            }
        }
    }

    private fun bindSpeedSeek(seek: SeekBar, valueView: android.widget.TextView) {
        seek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!suppressSheetSync) {
                        valueView.text = getString(R.string.map_filter_mbps_fmt, progress)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (!suppressSheetSync) pushSpeedFilters()
                }
            }
        )
        valueView.text = getString(R.string.map_filter_mbps_fmt, seek.progress)
    }

    private fun pushSpeedFilters() {
        homeViewModel.setSpeedFilters(
            minDownloadMbps = binding.seekMinDownload.progress,
            maxDownloadMbps = binding.seekMaxDownload.progress,
            minUploadMbps = binding.seekMinUpload.progress,
            maxUploadMbps = binding.seekMaxUpload.progress,
        )
    }

    private fun bindSheetFromState(s: MapFilterState) {
        if (_binding == null) return
        suppressSheetSync = true
        try {
            binding.switchShowLegend.isChecked = s.showLegend
            binding.switchMyDataOnly.isChecked = s.myDataOnly
            binding.switchShowValues.isChecked = s.showMapValues

            binding.btnCountrySelect.text = when (s.country) {
                MapCountryFilter.PK -> getString(R.string.map_country_pk)
                MapCountryFilter.US -> getString(R.string.map_country_us)
            }

            when (s.carrier) {
                MapCarrierFilter.ALL -> binding.chipCarrierAll.isChecked = true
                MapCarrierFilter.ATT -> binding.chipCarrierAtt.isChecked = true
                MapCarrierFilter.TMO -> binding.chipCarrierTmo.isChecked = true
                MapCarrierFilter.VZW -> binding.chipCarrierVzw.isChecked = true
            }

            when (s.network) {
                MapNetworkFilter.ALL -> binding.chipTechAll.isChecked = true
                MapNetworkFilter.NR_ONLY -> binding.chipTech5g.isChecked = true
                MapNetworkFilter.LTE_5G,
                MapNetworkFilter.LTE_ONLY,
                -> binding.chipTechLte.isChecked = true
            }

            when (s.colorMode) {
                MapColorMode.CARRIER -> binding.chipColorCarrier.isChecked = true
                MapColorMode.HEATMAP -> binding.chipColorHeatmap.isChecked = true
                MapColorMode.BEST_CARRIER -> binding.chipColorBestCarrier.isChecked = true
            }

            when (s.metric) {
                MapMetricFilter.BEST -> binding.chipMapBest.isChecked = true
                MapMetricFilter.MEDIAN -> binding.chipMapMedian.isChecked = true
                MapMetricFilter.AVERAGE -> binding.chipMapAverage.isChecked = true
            }

            when (s.mapMetricKind) {
                MapSheetMetricKind.UPLOAD -> binding.chipMetricUpload.isChecked = true
                MapSheetMetricKind.LATENCY -> binding.chipMetricLatency.isChecked = true
                MapSheetMetricKind.DOWNLOAD -> binding.chipMetricDownload.isChecked = true
            }

            binding.seekMinDownload.progress = s.minDownloadMbps.coerceIn(0, binding.seekMinDownload.max)
            binding.seekMaxDownload.progress = s.maxDownloadMbps.coerceIn(0, binding.seekMaxDownload.max)
            binding.seekMinUpload.progress = s.minUploadMbps.coerceIn(0, binding.seekMinUpload.max)
            binding.seekMaxUpload.progress = s.maxUploadMbps.coerceIn(0, binding.seekMaxUpload.max)

            binding.valueMinDownload.text = getString(R.string.map_filter_mbps_fmt, binding.seekMinDownload.progress)
            binding.valueMaxDownload.text = getString(R.string.map_filter_mbps_fmt, binding.seekMaxDownload.progress)
            binding.valueMinUpload.text = getString(R.string.map_filter_mbps_fmt, binding.seekMinUpload.progress)
            binding.valueMaxUpload.text = getString(R.string.map_filter_mbps_fmt, binding.seekMaxUpload.progress)
        } finally {
            suppressSheetSync = false
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}

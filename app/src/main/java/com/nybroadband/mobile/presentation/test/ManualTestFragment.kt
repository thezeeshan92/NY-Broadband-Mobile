package com.nybroadband.mobile.presentation.test

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.slider.Slider
import com.nybroadband.mobile.R
import com.nybroadband.mobile.databinding.FragmentManualTestBinding
import com.nybroadband.mobile.domain.model.TestConfig
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Configuration screen for a single manual speed test.
 *
 * Converted from DialogFragment to Fragment so the nav graph can forward-navigate
 * to [TestInProgressFragment] without the dialog stack being unwound.
 *
 * The user sets:
 *   • Test duration (7–15 s, slider with 1-second step)
 *   • Server (auto-selected from ServerDefinitionDao, read-only for MVP)
 *
 * On "Run Test": builds a [TestConfig] from [ManualTestViewModel] and navigates
 * to [TestInProgressFragment] with duration + serverId as bundle args.
 */
@AndroidEntryPoint
class ManualTestFragment : Fragment() {

    private var _binding: FragmentManualTestBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ManualTestViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentManualTestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        setupSlider()
        observeViewModel()

        binding.btnStartTest.setOnClickListener {
            val config = viewModel.buildConfig()
            findNavController().navigate(
                R.id.action_manualTest_to_testInProgress,
                bundleOf(
                    TestInProgressFragment.ARG_DURATION_SEC to config.durationSeconds,
                    TestInProgressFragment.ARG_SERVER_ID    to config.serverId
                )
            )
        }
    }

    private fun setupSlider() {
        binding.sliderDuration.apply {
            valueFrom = TestConfig.MIN_SECONDS.toFloat()
            valueTo   = TestConfig.MAX_SECONDS.toFloat()
            stepSize  = 1f
            value     = viewModel.durationSeconds.value.toFloat()

            addOnChangeListener { _: Slider, newValue: Float, fromUser: Boolean ->
                if (fromUser) viewModel.setDuration(newValue.toInt())
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.durationSeconds.collect { sec ->
                        binding.tvDurationValue.text = resources.getQuantityString(
                            R.plurals.test_duration_seconds, sec, sec
                        )
                        binding.tvDataEstimate.text = getString(
                            R.string.test_data_estimate_fmt,
                            viewModel.estimatedDataMb()
                        )
                    }
                }

                launch {
                    viewModel.selectedServer.collect { server ->
                        binding.tvServerName.text   = server?.baseUrl
                            ?: getString(R.string.test_server_auto)
                        binding.tvServerRegion.text = server?.region
                            ?: getString(R.string.test_server_region_auto)
                    }
                }

                launch {
                    viewModel.currentSignal.collect { snapshot ->
                        if (snapshot != null && !snapshot.isNoService) {
                            binding.tvSignalTier.text    = snapshot.signalTier
                                .lowercase().replaceFirstChar { it.uppercase() }
                            binding.tvSignalNetwork.text = snapshot.networkType
                        } else {
                            binding.tvSignalTier.text    = getString(R.string.signal_unknown)
                            binding.tvSignalNetwork.text = getString(R.string.signal_unknown)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

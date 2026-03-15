package com.nybroadband.mobile.presentation.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.nybroadband.mobile.R
import com.nybroadband.mobile.databinding.FragmentSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
        observeState()
    }

    private fun setupClickListeners() {
        binding.rowProfile.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_profile)
        }

        binding.switchPassiveCollection.setOnCheckedChangeListener { _, isChecked ->
            viewModel.passiveCollectionEnabled.value = isChecked
            // TODO: start/stop PassiveCollectionService
        }

        binding.switchAutoTest.setOnCheckedChangeListener { _, isChecked ->
            viewModel.autoTestEnabled.value = isChecked
            // TODO: start/stop RecurringTestService
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.totalCount.collect { count ->
                    binding.tvTotalMeasurements.text =
                        resources.getQuantityString(R.plurals.total_measurements, count, count)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.monthlyDataBytes.collect { bytes ->
                    val mb = bytes / (1024f * 1024f)
                    binding.tvDataUsage.text = getString(R.string.data_usage_fmt, mb)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

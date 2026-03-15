package com.nybroadband.mobile.presentation.results

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
import androidx.recyclerview.widget.LinearLayoutManager
import com.nybroadband.mobile.R
import com.nybroadband.mobile.databinding.FragmentResultsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Results / history list screen.
 *
 * Displays all [MeasurementEntity] records from Room, grouped by date,
 * with filter chips (All / Speed Tests / Passive / Dead Zones).
 *
 * Tapping any row navigates to [TestResultFragment] via [action_results_to_resultDetail].
 * The detail screen handles all sampleType variants (active / passive / dead zone) by
 * showing  for fields that are not applicable to that record type.
 *
 * Data flow:
 *   MeasurementDao.observeFiltered() → ResultsViewModel.uiState → adapter.submitGrouped()
 */
@AndroidEntryPoint
class ResultsFragment : Fragment() {

    private var _binding: FragmentResultsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ResultsViewModel by viewModels()

    private val adapter = MeasurementAdapter { measurementId ->
        findNavController().navigate(
            R.id.action_results_to_resultDetail,
            bundleOf("measurementId" to measurementId)
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentResultsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupFilterChips()
        observeState()
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ResultsFragment.adapter
            setHasFixedSize(false)
        }
    }

    // ── Filter chips ──────────────────────────────────────────────────────────

    private fun setupFilterChips() {
        // Use the ChipGroup's single-selection listener so state is driven by chip check,
        // not by tap (avoids double-calls if the currently active chip is tapped again).
        binding.filterChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                R.id.chipSpeedTests -> ResultsFilter.SPEED_TESTS
                R.id.chipPassive    -> ResultsFilter.PASSIVE
                R.id.chipDeadZones  -> ResultsFilter.DEAD_ZONES
                else                -> ResultsFilter.ALL
            }
            viewModel.setFilter(filter)
        }
    }

    // ── ViewModel observation ─────────────────────────────────────────────────

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.uiState.collect { state ->
                        when (state) {
                            is ResultsUiState.Loading -> showLoading()
                            is ResultsUiState.Empty   -> showEmpty()
                            is ResultsUiState.Ready   -> showResults(state)
                        }
                    }
                }

                launch {
                    viewModel.activeFilter.collect { filter -> updateChipSelection(filter) }
                }
            }
        }
    }

    // ── State rendering ───────────────────────────────────────────────────────

    private fun showLoading() {
        binding.progressBar.visibility  = View.VISIBLE
        binding.emptyState.visibility   = View.GONE
        binding.recyclerView.visibility = View.GONE
    }

    private fun showEmpty() {
        binding.progressBar.visibility  = View.GONE
        binding.emptyState.visibility   = View.VISIBLE
        binding.recyclerView.visibility = View.GONE
        adapter.submitList(emptyList())
    }

    private fun showResults(state: ResultsUiState.Ready) {
        binding.progressBar.visibility  = View.GONE
        binding.emptyState.visibility   = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        adapter.submitGrouped(state.measurements)
    }

    private fun updateChipSelection(filter: ResultsFilter) {
        binding.chipAll.isChecked        = filter == ResultsFilter.ALL
        binding.chipSpeedTests.isChecked = filter == ResultsFilter.SPEED_TESTS
        binding.chipPassive.isChecked    = filter == ResultsFilter.PASSIVE
        binding.chipDeadZones.isChecked  = filter == ResultsFilter.DEAD_ZONES
    }

    override fun onDestroyView() {
        binding.recyclerView.adapter = null  // prevent leak via LayoutManager → adapter ref
        _binding = null
        super.onDestroyView()
    }
}

package com.nybroadband.mobile.presentation.deadzone

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.nybroadband.mobile.R
import com.nybroadband.mobile.databinding.FragmentDeadZoneBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Dead zone reporting screen — user-initiated flow.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  THREE DISTINCT FLOWS IN THE DEAD ZONE MODULE                          │
 * │                                                                         │
 * │  1. AUTO-DETECTED (no user action):                                     │
 * │     PassiveCollectionService records a SignalSnapshot with              │
 * │     isNoService=true. SampleAssembler writes MeasurementEntity          │
 * │     (deadZoneType="AUTO"). Appears as a grey dot on the map.            │
 * │     The user is never interrupted — collection is silent.               │
 * │                                                                         │
 * │  2. USER-CONFIRMED (this screen):                                       │
 * │     User presses the "Dead Zone" button on the home map, picks a        │
 * │     type, optionally adds a note, and submits. A DeadZoneReportEntity   │
 * │     is written to Room immediately — offline-safe. SyncWorker uploads   │
 * │     it when connectivity is available.                                  │
 * │                                                                         │
 * │  3. QUEUED UPLOAD (background, transparent to user):                   │
 * │     DeadZoneRepositoryImpl inserts a SyncQueueEntity with              │
 * │     nextAttemptMs = now. SyncWorker (future) polls the queue,           │
 * │     uploads in batches, and marks each report UPLOADED or reschedules   │
 * │     with exponential backoff on failure.                                │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * On successful submit, result is signalled back to [HomeMapFragment] via
 * Navigation's SavedStateHandle so the home screen can show a Snackbar.
 */
@AndroidEntryPoint
class DeadZoneFragment : Fragment() {

    private var _binding: FragmentDeadZoneBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DeadZoneViewModel by viewModels()

    companion object {
        /** SavedStateHandle key — HomeMapFragment listens for this after back navigation. */
        const val RESULT_SUBMITTED = "dead_zone_submitted"
        const val RESULT_ONLINE    = "dead_zone_submitted_online"
    }

    // ── View lifecycle ────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDeadZoneBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupTypeChips()
        setupNoteField()
        setupLocationRetry()
        setupSubmitButton()
        observeViewModel()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
    }

    /**
     * Single-selection ChipGroup.
     * Each Chip stores its [DeadZoneType] constant in [View.setTag] so the
     * ViewModel never needs to reference R.id values.
     */
    private fun setupTypeChips() {
        binding.chipNoSignal.tag   = DeadZoneType.NO_SIGNAL
        binding.chipWeakIndoor.tag = DeadZoneType.WEAK_INDOOR
        binding.chipTunnel.tag     = DeadZoneType.TUNNEL
        binding.chipDeadStretch.tag = DeadZoneType.DEAD_STRETCH
        binding.chipOther.tag      = DeadZoneType.OTHER

        binding.chipGroupType.setOnCheckedStateChangeListener { group, _ ->
            val checkedId = group.checkedChipId
            if (checkedId != View.NO_ID) {
                val chip = group.findViewById<Chip>(checkedId)
                viewModel.setType(chip.tag as? String ?: DeadZoneType.default)
            }
        }
        // Default selection: "No signal at all"
        binding.chipNoSignal.isChecked = true
    }

    private fun setupNoteField() {
        binding.etNote.doAfterTextChanged { text ->
            viewModel.setNote(text?.toString() ?: "")
        }
    }

    private fun setupLocationRetry() {
        binding.cardLocation.setOnClickListener {
            if (viewModel.uiState.value is DeadZoneUiState.LocationError) {
                viewModel.retryLocation()
            }
        }
    }

    private fun setupSubmitButton() {
        binding.btnSubmit.setOnClickListener { viewModel.submit() }
    }

    // ── ViewModel observation ─────────────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch { viewModel.uiState.collect { renderState(it) } }

                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is DeadZoneEvent.SubmitSuccess -> onSubmitSuccess(event.isOnline)
                        }
                    }
                }
            }
        }
    }

    /**
     * Signal the result back to the previous screen via SavedStateHandle, then
     * navigate up. HomeMapFragment observes this key and shows the success Snackbar.
     * This avoids showing a Snackbar from a Fragment whose view is being destroyed.
     */
    private fun onSubmitSuccess(isOnline: Boolean) {
        findNavController().previousBackStackEntry?.savedStateHandle?.apply {
            set(RESULT_SUBMITTED, true)
            set(RESULT_ONLINE, isOnline)
        }
        findNavController().navigateUp()
    }

    // ── State rendering ───────────────────────────────────────────────────────

    private fun renderState(state: DeadZoneUiState) {
        when (state) {

            DeadZoneUiState.Locating -> {
                binding.progressLocation.isVisible = true
                binding.tvLocationStatus.setText(R.string.dead_zone_locating)
                binding.tvLocationAccuracy.isVisible = false
                binding.tvLocationRetry.isVisible = false
                binding.cardLocation.isClickable = false
                setFormEnabled(false)
                binding.bannerOffline.isVisible = false
            }

            is DeadZoneUiState.Ready -> {
                binding.progressLocation.isVisible = false
                binding.tvLocationStatus.setText(R.string.dead_zone_location_acquired)
                binding.tvLocationAccuracy.isVisible = true
                binding.tvLocationAccuracy.text = getString(
                    R.string.dead_zone_location_accuracy_fmt,
                    state.accuracyMeters
                )
                binding.tvLocationRetry.isVisible = false
                binding.cardLocation.isClickable = false
                setFormEnabled(true)
                binding.bannerOffline.isVisible = !state.isOnline
                binding.btnSubmit.text = getString(R.string.dead_zone_btn_submit)
            }

            DeadZoneUiState.Submitting -> {
                binding.progressLocation.isVisible = false
                binding.tvLocationRetry.isVisible = false
                setFormEnabled(false)
                binding.btnSubmit.text = getString(R.string.dead_zone_submitting)
            }

            is DeadZoneUiState.LocationError -> {
                binding.progressLocation.isVisible = false
                binding.tvLocationStatus.text = state.message
                binding.tvLocationAccuracy.isVisible = false
                binding.tvLocationRetry.isVisible = true
                binding.cardLocation.isClickable = true  // tap to retry
                setFormEnabled(false)
                binding.bannerOffline.isVisible = false
            }
        }
    }

    /** Enable or disable all interactive form elements. */
    private fun setFormEnabled(enabled: Boolean) {
        binding.chipGroupType.isEnabled = enabled
        for (i in 0 until binding.chipGroupType.childCount) {
            binding.chipGroupType.getChildAt(i).isEnabled = enabled
        }
        binding.etNote.isEnabled = enabled
        binding.btnSubmit.isEnabled = enabled
    }
}

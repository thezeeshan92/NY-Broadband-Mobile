package com.nybroadband.mobile.presentation.settings

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.nybroadband.mobile.R
import com.nybroadband.mobile.databinding.FragmentSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
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

        binding.btnSyncNow.setOnClickListener {
            viewModel.syncNow()
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

        // Combine queue depth + sync state so the card reacts to both
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.queueDepth, viewModel.syncState) { pending, state ->
                    pending to state
                }.collect { (pending, state) ->
                    updateSyncCard(pending, state)
                }
            }
        }
    }

    // ── Sync card rendering ────────────────────────────────────────────────────

    private fun updateSyncCard(pendingCount: Int, syncState: SyncState) {
        if (pendingCount == 0 && syncState !is SyncState.Syncing) {
            renderSyncedState()
        } else {
            renderPendingState(pendingCount, syncState)
        }
    }

    private fun renderSyncedState() {
        val ctx = requireContext()
        val iconBg    = ContextCompat.getColor(ctx, R.color.sync_synced_bg)
        val iconTint  = ContextCompat.getColor(ctx, R.color.signal_good)
        val stroke    = ContextCompat.getColor(ctx, R.color.sync_synced_stroke)

        binding.layoutSyncIconBg.backgroundTintList = ColorStateList.valueOf(iconBg)
        binding.ivSyncIcon.setImageResource(R.drawable.ic_check_circle_24)
        ImageViewCompat.setImageTintList(binding.ivSyncIcon, ColorStateList.valueOf(iconTint))
        binding.tvSyncTitle.text    = getString(R.string.settings_sync_up_to_date_title)
        binding.tvSyncSubtitle.text = getString(R.string.settings_sync_up_to_date_desc)
        binding.progressSync.visibility = View.GONE
        binding.btnSyncNow.visibility   = View.GONE
        binding.cardSync.setStrokeColor(ColorStateList.valueOf(stroke))
    }

    private fun renderPendingState(pendingCount: Int, syncState: SyncState) {
        val ctx = requireContext()
        val iconBg   = ContextCompat.getColor(ctx, R.color.sync_pending_bg)
        val iconTint = ContextCompat.getColor(ctx, R.color.status_queued)
        val stroke   = ContextCompat.getColor(ctx, R.color.sync_pending_stroke)

        binding.layoutSyncIconBg.backgroundTintList = ColorStateList.valueOf(iconBg)
        binding.ivSyncIcon.setImageResource(R.drawable.ic_upload_24)
        ImageViewCompat.setImageTintList(binding.ivSyncIcon, ColorStateList.valueOf(iconTint))
        binding.tvSyncTitle.text = getString(R.string.settings_sync_pending_title)
        binding.cardSync.setStrokeColor(ColorStateList.valueOf(stroke))

        when (syncState) {
            is SyncState.Syncing -> {
                binding.tvSyncSubtitle.text     = getString(R.string.settings_sync_uploading)
                binding.progressSync.visibility = View.VISIBLE
                binding.btnSyncNow.visibility   = View.GONE
            }
            is SyncState.Error -> {
                binding.tvSyncSubtitle.text     = getString(R.string.settings_sync_error)
                binding.progressSync.visibility = View.GONE
                binding.btnSyncNow.visibility   = View.VISIBLE
            }
            else -> {
                binding.tvSyncSubtitle.text = resources.getQuantityString(
                    R.plurals.settings_sync_pending_subtitle, pendingCount, pendingCount
                )
                binding.progressSync.visibility = View.GONE
                binding.btnSyncNow.visibility   = View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

package com.nybroadband.mobile.presentation.results

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nybroadband.mobile.R
import com.nybroadband.mobile.data.local.db.entity.MeasurementEntity
import com.nybroadband.mobile.databinding.ItemDateHeaderBinding
import com.nybroadband.mobile.databinding.ItemMeasurementBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// List item model
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Two-type list: date group headers and measurement rows.
 *
 * The adapter receives a flat [List<MeasurementEntity>] (already sorted newest-first
 * by the DAO) and inserts [Header] items wherever the date changes. This keeps
 * grouping logic out of the ViewModel and makes the DiffCallback straightforward.
 */
sealed class ResultsItem {

    /** Date section divider — e.g. "Today", "Yesterday", "Mar 8, 2026". */
    data class Header(val dateLabel: String) : ResultsItem()

    /** A single measurement row. */
    data class Row(val measurement: MeasurementEntity) : ResultsItem()

    object Diff : DiffUtil.ItemCallback<ResultsItem>() {
        override fun areItemsTheSame(old: ResultsItem, new: ResultsItem): Boolean = when {
            old is Header && new is Header -> old.dateLabel == new.dateLabel
            old is Row   && new is Row    -> old.measurement.id == new.measurement.id
            else                          -> false
        }
        override fun areContentsTheSame(old: ResultsItem, new: ResultsItem): Boolean = old == new
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Adapter
// ─────────────────────────────────────────────────────────────────────────────

class MeasurementAdapter(
    private val onRowClick: (measurementId: String) -> Unit
) : ListAdapter<ResultsItem, RecyclerView.ViewHolder>(ResultsItem.Diff) {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ROW    = 1
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ResultsItem.Header -> VIEW_TYPE_HEADER
        is ResultsItem.Row    -> VIEW_TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(
                ItemDateHeaderBinding.inflate(inflater, parent, false)
            )
            else -> RowViewHolder(
                ItemMeasurementBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ResultsItem.Header -> (holder as HeaderViewHolder).bind(item)
            is ResultsItem.Row    -> (holder as RowViewHolder).bind(item.measurement)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Groups [measurements] by date and submits to DiffUtil.
     * Call this from the Fragment whenever [ResultsUiState.Ready.measurements] changes.
     */
    fun submitGrouped(measurements: List<MeasurementEntity>) {
        val items = buildList {
            var lastDate: String? = null
            for (m in measurements) {
                val dateLabel = formatDateLabel(m.timestamp)
                if (dateLabel != lastDate) {
                    add(ResultsItem.Header(dateLabel))
                    lastDate = dateLabel
                }
                add(ResultsItem.Row(m))
            }
        }
        submitList(items)
    }

    // ── View holders ──────────────────────────────────────────────────────────

    inner class HeaderViewHolder(
        private val binding: ItemDateHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(header: ResultsItem.Header) {
            binding.tvDateHeader.text = header.dateLabel
        }
    }

    inner class RowViewHolder(
        private val binding: ItemMeasurementBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(m: MeasurementEntity) {
            val ctx = binding.root.context

            // ── Signal dot color ──────────────────────────────────────────
            val dotColorRes = when (m.signalTier) {
                "GOOD" -> R.color.signal_good
                "FAIR" -> R.color.signal_fair
                "WEAK" -> R.color.signal_weak
                "POOR" -> R.color.signal_poor
                else   -> R.color.signal_none
            }
            binding.signalDot.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(ctx, dotColorRes))

            // ── Primary summary label ─────────────────────────────────────
            // Public-facing plain language: no dBm values, no internal codes.
            val isSpeedTest = m.sampleType in setOf("ACTIVE_MANUAL", "ACTIVE_RECURRING")
            val isDeadZone  = m.deadZoneType != null

            binding.tvSignalQuality.text = when {
                isDeadZone -> ctx.getString(R.string.results_dead_zone_label)
                isSpeedTest && m.downloadSpeedMbps != null ->
                    ctx.getString(R.string.results_item_speed_fmt, m.downloadSpeedMbps)
                else -> qualityLabel(m.signalTier, ctx)
            }

            // ── Secondary: network type · carrier ─────────────────────────
            binding.tvNetworkInfo.text = buildString {
                if (m.networkType != "UNKNOWN") append(m.networkType)
                val carrier = m.carrierName?.takeIf { it.isNotBlank() }
                if (carrier != null) {
                    if (isNotEmpty()) append(" · ")
                    append(carrier)
                }
                if (isEmpty()) append("--")
            }

            // ── Speed summary (speed tests only) ──────────────────────────
            // e.g. "↓ 23.4  ↑ 8.1 Mbps"
            val showSpeed = isSpeedTest && !isDeadZone
            binding.tvSpeedSummary.isVisible = showSpeed
            if (showSpeed) {
                val dl = m.downloadSpeedMbps?.let { "↓ %.1f".format(it) } ?: "↓ --"
                val ul = m.uploadSpeedMbps?.let { "↑ %.1f".format(it) }   ?: "↑ --"
                binding.tvSpeedSummary.text = ctx.getString(R.string.results_item_speed_detail, dl, ul)
            }

            // ── Timestamp (time only — date is in the section header) ─────
            binding.tvTimestamp.text =
                SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(m.timestamp))

            // ── Sync status badge ─────────────────────────────────────────
            // Only shown when data is queued or failed — not after successful upload.
            val (syncText, showSync) = when (m.uploadStatus) {
                "PENDING"  -> ctx.getString(R.string.sync_pending) to true
                "FAILED"   -> ctx.getString(R.string.sync_failed)  to true
                else       -> ""                                    to false
            }
            binding.tvSyncStatus.isVisible = showSync
            binding.tvSyncStatus.text = syncText

            // ── Row click ─────────────────────────────────────────────────
            binding.root.setOnClickListener { onRowClick(m.id) }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Formats an epoch-ms timestamp as a human-readable date label.
     * "Today", "Yesterday", or "MMM d, yyyy" for older dates.
     */
    private fun formatDateLabel(timestampMs: Long): String {
        val today     = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val yesterday = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.DAY_OF_YEAR, -1) }
        val item      = Calendar.getInstance().apply { timeInMillis = timestampMs }

        return when {
            item >= today     -> "Today"
            item >= yesterday -> "Yesterday"
            else              -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestampMs))
        }
    }
}

// ── Module-level helper — also used by TestResultFragment ─────────────────────

/**
 * Converts a signalTier string to a plain-language quality label.
 * Never shows raw dBm values — per project UX policy.
 */
fun qualityLabel(tier: String, ctx: android.content.Context): String = when (tier) {
    "GOOD" -> ctx.getString(R.string.signal_good)
    "FAIR" -> ctx.getString(R.string.signal_fair)
    "WEAK" -> ctx.getString(R.string.signal_weak)
    "POOR" -> ctx.getString(R.string.signal_poor)
    else   -> ctx.getString(R.string.signal_unknown)
}

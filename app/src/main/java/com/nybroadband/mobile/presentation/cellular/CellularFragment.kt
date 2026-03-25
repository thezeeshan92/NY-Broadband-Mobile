package com.nybroadband.mobile.presentation.cellular

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.nybroadband.mobile.R
import com.nybroadband.mobile.databinding.FragmentCellularBinding
import com.nybroadband.mobile.service.signal.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CellularFragment : Fragment() {

    private var _binding: FragmentCellularBinding? = null
    private val binding get() = _binding!!

    // Shared with CellularSettingsBottomSheet via activityViewModels scope
    private val viewModel: CellularViewModel by activityViewModels()

    // ── View lifecycle ────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCellularBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
        observeViewModel()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // ── Click listeners ───────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.btnSettings.setOnClickListener {
            CellularSettingsBottomSheet().show(childFragmentManager, "cellular_settings")
        }
        binding.btnHelp.setOnClickListener {
            showHelpDialog()
        }
    }

    // ── ViewModel observation ─────────────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.snapshot, viewModel.settings) { snap, settings ->
                    snap to settings
                }.collect { (snap, settings) ->
                    render(snap, settings)
                }
            }
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private fun render(snap: CellularSnapshot?, settings: CellularSettings) {
        if (snap == null) {
            showLoading()
            return
        }
        renderConnection(snap.connection)

        val cells = if (settings.hideNeighborCells)
            snap.cells.filter { it.isPrimary }
        else
            snap.cells

        if (cells.isEmpty()) {
            showNoCells()
        } else {
            showCells(cells, settings.advancedView)
        }
    }

    private fun showLoading() {
        binding.layoutEmpty.isVisible = true
        binding.progressBar.isVisible = true
        binding.tvEmptyMessage.text = getString(R.string.cellular_scanning)
        binding.cellsContainer.removeAllViews()
        binding.tvCellsHeader.isVisible = false
    }

    private fun showNoCells() {
        binding.layoutEmpty.isVisible = true
        binding.progressBar.isVisible = false
        binding.tvEmptyMessage.text = getString(R.string.cellular_no_cells)
        binding.cellsContainer.removeAllViews()
        binding.tvCellsHeader.isVisible = false
    }

    private fun showCells(cells: List<CellEntry>, advancedView: Boolean) {
        binding.layoutEmpty.isVisible = false
        binding.tvCellsHeader.isVisible = true
        binding.cellsContainer.removeAllViews()
        cells.forEach { cell ->
            val cardView = buildCellCard(cell, advancedView)
            binding.cellsContainer.addView(cardView)
        }
    }

    // ── Connection section ────────────────────────────────────────────────────

    private fun renderConnection(info: CellConnectionInfo) {
        binding.tvCarrierName.text = info.networkOperator ?: "--"
        binding.connectionGrid.removeAllViews()

        val plmn = if (info.mcc != null && info.mnc != null) "${info.mcc} ${info.mnc}" else null

        val pairs = listOf(
            getString(R.string.cellular_conn_carrier) to (info.networkOperator ?: "--"),
            getString(R.string.cellular_conn_gen)     to info.networkGen,
            getString(R.string.cellular_conn_network) to (info.simOperator ?: "--"),
            getString(R.string.cellular_conn_tech)    to info.tech,
            getString(R.string.cellular_conn_roaming) to if (info.isRoaming)
                getString(R.string.cellular_roaming_true) else getString(R.string.cellular_roaming_false),
            getString(R.string.cellular_conn_plmn)    to (plmn ?: "--"),
        )

        // Build rows: 2 pairs per row, equal-width columns
        val chunked = pairs.chunked(2)
        chunked.forEachIndexed { rowIdx, chunk ->
            val row = inflateRow()
            chunk.forEach { (label, value) ->
                val col = inflateKeyValueColumn(label, value)
                (col.layoutParams as LinearLayout.LayoutParams).apply {
                    weight = 1f
                    width  = 0
                }
                row.addView(col)
            }
            // Filler if odd number of items in last chunk
            if (chunk.size == 1) {
                row.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                })
            }
            binding.connectionGrid.addView(row)
            if (rowIdx < chunked.lastIndex) {
                binding.connectionGrid.addView(inflateRowDivider())
            }
        }
    }

    // ── Cell card builder ─────────────────────────────────────────────────────

    private fun buildCellCard(cell: CellEntry, advancedView: Boolean): View {
        val card = MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(12) }
            radius = resources.getDimension(R.dimen.card_corner_radius)
            cardElevation = 0f
            strokeWidth = dp(1)
            setStrokeColor(
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.outline_light)
                )
            )
        }

        val inner = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        // ── Band header row ──────────────────────────────────────────────────
        inner.addView(buildBandHeader(cell))

        // ── Signal strength bar ──────────────────────────────────────────────
        inner.addView(buildSignalBar(cell))

        // ── Parameter table ──────────────────────────────────────────────────
        inner.addView(buildParamTable(cell, advancedView))

        card.addView(inner)
        return card
    }

    /** Header row: [P badge] [band label] [freq range] [signal dBm] */
    private fun buildBandHeader(cell: CellEntry): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(8))
        }

        // Primary / neighbour badge
        val badge = TextView(requireContext()).apply {
            text = if (cell.isPrimary) "P" else "N"
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_cell_badge)
            backgroundTintList = ColorStateList.valueOf(
                if (cell.isPrimary)
                    ContextCompat.getColor(requireContext(), R.color.signal_good)
                else
                    ContextCompat.getColor(requireContext(), R.color.signal_none)
            )
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        }
        row.addView(badge)

        // Band label (e.g. "b3")
        val bandLabel = cell.bandLabel ?: cell.techLabel
        val tvBand = TextView(requireContext()).apply {
            text = bandLabel
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface_light))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = dp(8) }
        }
        row.addView(tvBand)

        // Frequency range (flexible, takes remaining space)
        val tvFreq = TextView(requireContext()).apply {
            text = cell.freqRangeLabel ?: ""
            textSize = 12f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.signal_none))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginStart = dp(8) }
        }
        row.addView(tvFreq)

        // Signal dBm
        val tvDbm = TextView(requireContext()).apply {
            text = getString(R.string.cellular_dbm_fmt, cell.signalDbm)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(signalTierColor(cell.signalTier))
        }
        row.addView(tvDbm)

        return row
    }

    /** Coloured signal strength bar (0–100 progress mapped to 0–4 bars). */
    private fun buildSignalBar(cell: CellEntry): View {
        val progress = (cell.signalBars * 25).coerceIn(0, 100)
        val color = signalTierColor(cell.signalTier)
        return LinearProgressIndicator(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
            ).also {
                it.marginStart  = dp(12)
                it.marginEnd    = dp(12)
                it.bottomMargin = dp(4)
            }
            isIndeterminate = false
            setProgress(progress, false)
            setIndicatorColor(color)
        }
    }

    /** Grid of key=value parameter pairs based on cell technology and advanced-view setting. */
    private fun buildParamTable(cell: CellEntry, advancedView: Boolean): View {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }

        val params: List<Pair<String, String?>> = when (cell) {
            is CellEntry.Lte -> buildLteParams(cell.data, advancedView)
            is CellEntry.Nr  -> buildNrParams(cell.data, advancedView)
            is CellEntry.Wcdma -> buildWcdmaParams(cell.data, advancedView)
            is CellEntry.Gsm   -> buildGsmParams(cell.data, advancedView)
        }

        // Render as 2-column grid
        params.chunked(2).forEach { chunk ->
            val row = inflateRow()
            chunk.forEach { (label, value) ->
                val col = inflateKeyValueColumn(label, value ?: "--")
                (col.layoutParams as LinearLayout.LayoutParams).apply {
                    weight = 1f
                    width = 0
                }
                row.addView(col)
            }
            // If odd number of items, add empty filler
            if (chunk.size == 1) {
                row.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                })
            }
            container.addView(row)
        }

        return container
    }

    // ── Parameter sets per technology ─────────────────────────────────────────

    private fun buildLteParams(d: LteCellData, advanced: Boolean): List<Pair<String, String?>> {
        val bwStr = d.bandwidthKhz?.let { "${it / 1000} MHz" }
        val basic = listOf(
            "Band"   to d.band?.let { "b$it" },
            "RSRP"   to d.rsrp?.let { "$it dBm" },
            "Status" to if (d.isPrimary) "Primary" else "Neighbor",
            "RSSI"   to d.rssi?.let { "$it dBm" },
            "BW"     to bwStr,
            "RSRQ"   to d.rsrq?.let { "$it dB" },
        )
        if (!advanced) return basic
        return basic + listOf(
            "eNb"    to d.eNb?.toString(),
            "SNR"    to d.snr?.toString(),
            "CID"    to d.cellId?.toString(),
            "CQI"    to d.cqi?.toString(),
            "CI"     to d.ci?.toString(),
            "TA"     to d.timingAdv?.toString(),
            "PCI"    to d.pci?.toString(),
            "PLMN"   to if (d.mcc != null && d.mnc != null) "${d.mcc} ${d.mnc}" else null,
        )
    }

    private fun buildNrParams(d: NrCellData, advanced: Boolean): List<Pair<String, String?>> {
        val basic = listOf(
            "Band"     to d.band?.let { "n$it" },
            "SS-RSRP"  to d.ssRsrp?.let { "$it dBm" },
            "Status"   to if (d.isPrimary) "Primary" else "Neighbor",
            "SS-RSRQ"  to d.ssRsrq?.let { "$it dB" },
            "SS-SINR"  to d.ssSinr?.let { "$it dB" },
            "PCI"      to d.pci?.toString(),
        )
        if (!advanced) return basic
        return basic + listOf(
            "CSI-RSRP" to d.csiRsrp?.let { "$it dBm" },
            "CSI-RSRQ" to d.csiRsrq?.let { "$it dB" },
            "CSI-SINR" to d.csiSinr?.let { "$it dB" },
            "NCI"      to d.nci?.toString(),
            "PLMN"     to if (d.mcc != null && d.mnc != null) "${d.mcc} ${d.mnc}" else null,
        )
    }

    private fun buildWcdmaParams(d: WcdmaCellData, advanced: Boolean): List<Pair<String, String?>> {
        val basic = listOf(
            "Band"   to d.band?.toString(),
            "RSCP"   to d.rscp?.let { "$it dBm" },
            "Status" to if (d.isPrimary) "Primary" else "Neighbor",
            "Ec/No"  to d.ecNo?.let { "$it dB" },
            "LAC"    to d.lac?.toString(),
            "CID"    to d.cid?.toString(),
        )
        if (!advanced) return basic
        return basic + listOf(
            "RNC"    to d.rnc?.toString(),
            "PSC"    to d.psc?.toString(),
            "UARFCN" to d.uarfcn?.toString(),
            "PLMN"   to if (d.mcc != null && d.mnc != null) "${d.mcc} ${d.mnc}" else null,
        )
    }

    private fun buildGsmParams(d: GsmCellData, advanced: Boolean): List<Pair<String, String?>> {
        val basic = listOf(
            "Band"   to d.band?.let { "${it}M" },
            "Signal" to d.dbm?.let { "$it dBm" },
            "Status" to if (d.isPrimary) "Primary" else "Neighbor",
            "BER"    to d.ber?.toString(),
            "LAC"    to d.lac?.toString(),
            "CID"    to d.cid?.toString(),
        )
        if (!advanced) return basic
        return basic + listOf(
            "ARFCN"  to d.arfcn?.toString(),
            "TA"     to d.timingAdv?.toString(),
            "PLMN"   to if (d.mcc != null && d.mnc != null) "${d.mcc} ${d.mnc}" else null,
        )
    }

    // ── View helper factories ─────────────────────────────────────────────────

    /** Horizontal row for use inside a vertical LinearLayout. */
    private fun inflateRow(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).also { it.bottomMargin = dp(6) }
    }

    /** A thin 1dp horizontal divider line. */
    private fun inflateRowDivider(): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).also {
            it.bottomMargin = dp(6)
            it.topMargin = dp(2)
        }
        setBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.outline_light)
        )
    }

    /**
     * A vertical LinearLayout with two TextViews: a small grey label on top
     * and a bold value below — matching the CoverageMap.com "Key: Value" style.
     */
    private fun inflateKeyValueColumn(label: String, value: String): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            addView(TextView(requireContext()).apply {
                text = label
                textSize = 11f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.signal_none))
            })
            addView(TextView(requireContext()).apply {
                text = value
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface_light))
            })
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun signalTierColor(tier: SignalTier): Int = ContextCompat.getColor(
        requireContext(),
        when (tier) {
            SignalTier.GOOD -> R.color.signal_good
            SignalTier.FAIR -> R.color.signal_fair
            SignalTier.WEAK -> R.color.signal_weak
            SignalTier.POOR -> R.color.signal_poor
            SignalTier.NONE -> R.color.signal_none
        }
    )

    /** Converts dp value to pixels. */
    private fun dp(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun dp(dp: Float): Float =
        dp * resources.displayMetrics.density

    private fun showHelpDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.cellular_help_title)
            .setMessage(R.string.cellular_help_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}

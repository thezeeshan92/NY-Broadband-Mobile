package com.nybroadband.mobile.presentation.cellular

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DimenRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.nybroadband.mobile.R
import com.nybroadband.mobile.databinding.FragmentCellularBinding
import com.nybroadband.mobile.presentation.AppPermissionViewModel
import com.nybroadband.mobile.service.signal.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

@AndroidEntryPoint
class CellularFragment : Fragment() {

    private var _binding: FragmentCellularBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CellularViewModel by activityViewModels()
    private val permissionViewModel: AppPermissionViewModel by activityViewModels()

    // Track whether we sent the user to Settings so onResume can re-check.
    private var awaitingSettingsReturn = false

    // ── Permission launcher ───────────────────────────────────────────────────

    private val phonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionViewModel.refresh()
        updatePhonePermissionOverlay()
        if (granted) {
            // Reader will start emitting snapshots once permission is held.
            viewModel.forceRefresh()
        }
    }

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
        applyWindowInsetsForHeader()
        setupClickListeners()
        updatePhonePermissionOverlay()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        permissionViewModel.refresh()

        if (awaitingSettingsReturn) {
            awaitingSettingsReturn = false
        }
        updatePhonePermissionOverlay()
        if (hasPhonePermission()) {
            viewModel.forceRefresh()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // ── Permission helpers ────────────────────────────────────────────────────

    private fun hasPhonePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED

    private fun isPermanentlyDenied(): Boolean =
        !hasPhonePermission() &&
            !shouldShowRequestPermissionRationale(Manifest.permission.READ_PHONE_STATE)

    /**
     * Shows/hides the phone permission overlay and configures the CTA button.
     * When permanently denied, the button opens app Settings instead of re-requesting.
     */
    private fun updatePhonePermissionOverlay() {
        val b = _binding ?: return
        val granted = hasPhonePermission()

        b.phonePermissionOverlay.isVisible = !granted
        b.scrollContent.isVisible = granted

        if (!granted) {
            if (isPermanentlyDenied()) {
                b.btnGrantPhonePermission.text = getString(R.string.perm_overlay_open_settings)
                b.btnGrantPhonePermission.setOnClickListener {
                    awaitingSettingsReturn = true
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", requireContext().packageName, null)
                        }
                    )
                }
            } else {
                b.btnGrantPhonePermission.text = getString(R.string.perm_overlay_grant_phone)
                b.btnGrantPhonePermission.setOnClickListener {
                    phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                }
            }
        }
    }

    /** Keeps help / carrier / settings clear of status bar and display cutout. */
    private fun applyWindowInsetsForHeader() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(
                left = maxOf(sys.left, cut.left),
                top = maxOf(sys.top, cut.top),
                right = maxOf(sys.right, cut.right),
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupClickListeners() {
        binding.btnSettings.setOnClickListener {
            CellularSettingsBottomSheet().show(childFragmentManager, "cellular_settings")
        }
        binding.btnHelp.setOnClickListener { showHelpDialog() }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.snapshot,
                    viewModel.settings,
                    viewModel.ipTransitAsn,
                ) { snap, settings, ip -> Triple(snap, settings, ip) }
                    .collect { (snap, settings, ip) ->
                        // Only render cell data when the phone permission is held.
                        if (hasPhonePermission()) {
                            render(snap, settings, ip)
                        }
                    }
            }
        }
    }

    private fun placeholder(): String = getString(R.string.cellular_value_placeholder)

    // ── Rendering ─────────────────────────────────────────────────────────────

    private fun render(
        snap: CellularSnapshot?,
        settings: CellularSettings,
        ip: IpTransitAsn?,
    ) {
        if (snap == null) {
            showLoading()
            return
        }
        renderConnection(snap.connection, ip)

        val cells = if (settings.hideNeighborCells) snap.cells.filter { it.isPrimary } else snap.cells

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
    }

    private fun showNoCells() {
        binding.layoutEmpty.isVisible = true
        binding.progressBar.isVisible = false
        binding.tvEmptyMessage.text = getString(R.string.cellular_no_cells)
        binding.cellsContainer.removeAllViews()
    }

    private fun showCells(cells: List<CellEntry>, advancedView: Boolean) {
        binding.layoutEmpty.isVisible = false
        binding.cellsContainer.removeAllViews()
        cells.forEach { cell ->
            binding.cellsContainer.addView(buildCellCard(cell, advancedView))
        }
    }

    // ── Connection (reference order: 4 rows × 2 columns, spacing only) ─────

    private fun renderConnection(info: CellConnectionInfo, ip: IpTransitAsn?) {
        binding.tvCarrierName.text = info.networkOperator ?: placeholder()
        binding.connectionGrid.removeAllViews()

        val plmnStr = if (info.mcc != null && info.mnc != null) "${info.mcc} ${info.mnc}" else placeholder()
        val ipTransit = ip?.ipTransitLabel ?: placeholder()
        val asn = ip?.asnLabel ?: placeholder()

        val networkVal = info.simOperator ?: placeholder()
        val showNetworkDot = networkVal != placeholder()
        val showIpDot = ip?.ipTransitLabel != null

        val rowDefs = listOf(
            RowDef(
                getString(R.string.cellular_conn_carrier) to (info.networkOperator ?: placeholder()),
                getString(R.string.cellular_conn_gen) to info.networkGen,
                false, false,
            ),
            RowDef(
                getString(R.string.cellular_conn_network) to networkVal,
                getString(R.string.cellular_conn_tech) to info.tech,
                showNetworkDot, false,
            ),
            RowDef(
                getString(R.string.cellular_conn_roaming) to if (info.isRoaming)
                    getString(R.string.cellular_roaming_true) else getString(R.string.cellular_roaming_false),
                getString(R.string.cellular_conn_plmn) to plmnStr,
                false, false,
            ),
            RowDef(
                getString(R.string.cellular_conn_ip_transit) to ipTransit,
                getString(R.string.cellular_conn_asn) to asn,
                showIpDot, false,
            ),
        )

        rowDefs.forEach { def ->
            binding.connectionGrid.addView(buildConnectionRow(def))
        }
    }

    private data class RowDef(
        val left: Pair<String, String>,
        val right: Pair<String, String>,
        val leftDot: Boolean,
        val rightDot: Boolean,
    )

    private fun buildConnectionRow(def: RowDef): LinearLayout {
        val row = inflateRow(connectionRowBottomMarginDp = 6)
        row.addView(inflateKeyValueColumn(def.left.first, def.left.second, def.leftDot))
        row.addView(inflateKeyValueColumn(def.right.first, def.right.second, def.rightDot))
        return row
    }

    // ── Cell card ────────────────────────────────────────────────────────────

    private fun buildCellCard(cell: CellEntry, advancedView: Boolean): View {
        val card = MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also {
                it.bottomMargin = dp(8)
                it.marginStart = dp(12)
                it.marginEnd = dp(12)
            }
            radius = resources.getDimension(R.dimen.card_corner_radius)
            cardElevation = 0f
            strokeWidth = dp(1)
            setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.cellular_surface))
            setStrokeColor(
                ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.cellular_divider),
                ),
            )
        }

        val inner = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        inner.addView(buildBandHeader(cell))
        inner.addView(buildSignalBarReference(cell))
        inner.addView(buildParamTable(cell, advancedView))

        card.addView(inner)
        return card
    }

    /** [P]  b3   freq   -96 dBm */
    private fun buildBandHeader(cell: CellEntry): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(6))
        }

        val badge = TextView(requireContext()).apply {
            text = if (cell.isPrimary) "P" else "N"
            applySpDimen(R.dimen.cellular_badge_text_sp)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_cell_badge)
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    requireContext(),
                    if (cell.isPrimary) R.color.cellular_badge_primary else R.color.cellular_badge_neighbor,
                ),
            )
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        }
        row.addView(badge)

        val bandLabel = cell.bandLabel ?: cell.techLabel
        row.addView(TextView(requireContext()).apply {
            text = bandLabel
            applySpDimen(R.dimen.cellular_text_band_sp)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(requireContext(), R.color.cellular_text_primary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.marginStart = dp(8) }
        })

        row.addView(TextView(requireContext()).apply {
            text = cell.freqRangeLabel ?: ""
            applySpDimen(R.dimen.cellular_text_freq_sp)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.cellular_text_label))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginStart = dp(8) }
        })

        row.addView(TextView(requireContext()).apply {
            text = getString(R.string.cellular_dbm_fmt, cell.signalDbm)
            applySpDimen(R.dimen.cellular_text_dbm_sp)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(requireContext(), R.color.cellular_text_primary))
        })

        return row
    }

    private fun buildSignalBarReference(cell: CellEntry): View {
        val fillFraction = when (cell.signalBars) {
            0 -> 0.06f
            else -> (cell.signalBars / 4f).coerceIn(0.12f, 1f) * 0.42f
        }
        val orange = ContextCompat.getColor(requireContext(), R.color.signal_fair)
        val track = ContextCompat.getColor(requireContext(), R.color.cellular_divider)

        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(8),
            ).also {
                it.marginStart = dp(10)
                it.marginEnd = dp(10)
                it.bottomMargin = dp(4)
            }
            clipChildren = true

            val gdOrange = GradientDrawable().apply {
                setColor(orange)
                cornerRadius = dp(4).toFloat()
            }
            val gdTrack = GradientDrawable().apply {
                setColor(track)
                cornerRadius = dp(4).toFloat()
            }

            addView(View(requireContext()).apply {
                background = gdOrange
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, fillFraction.coerceAtLeast(0.08f))
            })
            addView(View(requireContext()).apply {
                background = gdTrack
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (1f - fillFraction).coerceAtLeast(0.08f))
            })
        }
    }

    private fun buildParamTable(cell: CellEntry, advancedView: Boolean): View {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(10))
        }

        when (cell) {
            is CellEntry.Lte -> buildLteParamGrid(container, cell.data, advancedView)
            else -> buildGenericParamGrid(container, cell, advancedView)
        }
        return container
    }

    private fun buildLteParamGrid(container: LinearLayout, d: LteCellData, advanced: Boolean) {
        val rows = if (advanced) lteRowsAdvanced(d) else lteRowsBasic(d)
        rows.forEach { (left, right) ->
            val row = inflateRow(connectionRowBottomMarginDp = 5)
            row.addView(inflateKeyValueColumn(left.first, left.second, false))
            row.addView(inflateKeyValueColumn(right.first, right.second, false))
            container.addView(row)
        }
    }

    private fun lteRowsBasic(d: LteCellData): List<Pair<Pair<String, String>, Pair<String, String>>> {
        val l1 = "Band" to formatBand(d)
        val r1 = "RSRP" to orBlank(d.rsrp?.let { "$it dBm" })
        val l2 = "Status" to if (d.isPrimary) "Primary" else "Neighbor"
        val r2 = "RSSI" to orBlank(d.rssi?.let { "$it dBm" })
        val l3 = "BW" to orBlank(d.bandwidthKhz?.let { "${it / 1000} MHz" })
        val r3 = "RSRQ" to orBlank(d.rsrq?.let { "$it dB" })
        val l4 = "eNb" to orBlank(d.eNb?.toString())
        val r4 = "SNR" to orBlank(formatLteSnr(d.snr))
        return listOf(l1 to r1, l2 to r2, l3 to r3, l4 to r4)
    }

    private fun lteRowsAdvanced(d: LteCellData): List<Pair<Pair<String, String>, Pair<String, String>>> {
        val dist = d.timingAdv?.let { ta -> "~${ta * 78} m" }
        val left = listOf(
            "Band" to formatBand(d),
            "Status" to if (d.isPrimary) "Primary" else "Neighbor",
            "BW" to orBlank(d.bandwidthKhz?.let { "${it / 1000} MHz" }),
            "eNb" to orBlank(d.eNb?.toString()),
            "CID" to orBlank(d.cellId?.toString()),
            "CI" to orBlank(d.ci?.toString()),
            "PCI" to orBlank(d.pci?.toString()),
        )
        val right = listOf(
            "RSRP" to orBlank(d.rsrp?.let { "$it dBm" }),
            "RSSI" to orBlank(d.rssi?.let { "$it dBm" }),
            "RSRQ" to orBlank(d.rsrq?.let { "$it dB" }),
            "SNR" to orBlank(formatLteSnr(d.snr)),
            "CQI" to orBlank(d.cqi?.toString()),
            "TA" to orBlank(d.timingAdv?.toString()),
            "Distance" to orBlank(dist),
        )
        return left.zip(right) { a, b -> a to b }
    }

    private fun formatBand(d: LteCellData): String = d.band?.let { "b$it" } ?: placeholder()

    private fun formatLteSnr(raw: Int?): String? {
        if (raw == null) return null
        val v = if (abs(raw) > 40) raw / 10 else raw
        return v.toString()
    }

    private fun orBlank(s: String?): String = s ?: ""

    private fun buildGenericParamGrid(container: LinearLayout, cell: CellEntry, advanced: Boolean) {
        val params: List<Pair<String, String?>> = when (cell) {
            is CellEntry.Nr -> buildNrParams(cell.data, advanced)
            is CellEntry.Wcdma -> buildWcdmaParams(cell.data, advanced)
            is CellEntry.Gsm -> buildGsmParams(cell.data, advanced)
            else -> emptyList()
        }
        params.chunked(2).forEach { chunk ->
            val row = inflateRow(connectionRowBottomMarginDp = 5)
            chunk.forEach { (label, value) ->
                val col = inflateKeyValueColumn(label, value ?: "", false)
                (col.layoutParams as LinearLayout.LayoutParams).apply { weight = 1f; width = 0 }
                row.addView(col)
            }
            if (chunk.size == 1) {
                row.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                })
            }
            container.addView(row)
        }
    }

    private fun buildNrParams(d: NrCellData, advanced: Boolean): List<Pair<String, String?>> {
        val basic = listOf(
            "Band" to d.band?.let { "n$it" },
            "SS-RSRP" to d.ssRsrp?.let { "$it dBm" },
            "Status" to if (d.isPrimary) "Primary" else "Neighbor",
            "SS-RSRQ" to d.ssRsrq?.let { "$it dB" },
            "SS-SINR" to d.ssSinr?.let { "$it dB" },
            "PCI" to d.pci?.toString(),
        )
        if (!advanced) return basic
        return basic + listOf(
            "CSI-RSRP" to d.csiRsrp?.let { "$it dBm" },
            "CSI-RSRQ" to d.csiRsrq?.let { "$it dB" },
            "CSI-SINR" to d.csiSinr?.let { "$it dB" },
            "NCI" to d.nci?.toString(),
        )
    }

    private fun buildWcdmaParams(d: WcdmaCellData, advanced: Boolean): List<Pair<String, String?>> {
        val basic = listOf(
            "Band" to d.band?.toString(),
            "RSCP" to d.rscp?.let { "$it dBm" },
            "Status" to if (d.isPrimary) "Primary" else "Neighbor",
            "Ec/No" to d.ecNo?.let { "$it dB" },
            "LAC" to d.lac?.toString(),
            "CID" to d.cid?.toString(),
        )
        if (!advanced) return basic
        return basic + listOf(
            "RNC" to d.rnc?.toString(),
            "PSC" to d.psc?.toString(),
            "UARFCN" to d.uarfcn?.toString(),
        )
    }

    private fun buildGsmParams(d: GsmCellData, advanced: Boolean): List<Pair<String, String?>> {
        val basic = listOf(
            "Band" to d.band?.let { "${it}M" },
            "Signal" to d.dbm?.let { "$it dBm" },
            "Status" to if (d.isPrimary) "Primary" else "Neighbor",
            "BER" to d.ber?.toString(),
            "LAC" to d.lac?.toString(),
            "CID" to d.cid?.toString(),
        )
        if (!advanced) return basic
        return basic + listOf(
            "ARFCN" to d.arfcn?.toString(),
            "TA" to d.timingAdv?.toString(),
        )
    }

    // ── View helpers ──────────────────────────────────────────────────────────

    private fun inflateRow(connectionRowBottomMarginDp: Int): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).also { it.bottomMargin = dp(connectionRowBottomMarginDp) }
    }

    private fun inflateKeyValueColumn(
        label: String,
        value: String,
        showStatusDot: Boolean,
    ): LinearLayout {
        val labelColor = ContextCompat.getColor(requireContext(), R.color.cellular_text_label)
        val valueColor = ContextCompat.getColor(requireContext(), R.color.cellular_text_primary)

        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            addView(TextView(requireContext()).apply {
                text = label
                applySpDimen(R.dimen.cellular_text_label_sp)
                setTextColor(labelColor)
            })

            val valueRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (showStatusDot && value.isNotBlank() && value != placeholder()) {
                valueRow.addView(View(requireContext()).apply {
                    background = ContextCompat.getDrawable(requireContext(), R.drawable.cellular_status_dot)
                    layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).also { it.marginEnd = dp(6) }
                })
            }
            valueRow.addView(TextView(requireContext()).apply {
                text = value
                applySpDimen(R.dimen.cellular_text_value_sp)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(valueColor)
                minHeight = dp(18)
            })
            addView(valueRow)
        }
    }

    private fun dp(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun TextView.applySpDimen(@DimenRes id: Int) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(id))
    }

    private fun showHelpDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.cellular_help_title)
            .setMessage(R.string.cellular_help_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
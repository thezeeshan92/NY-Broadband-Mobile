package com.nybroadband.mobile.presentation.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.updateLayoutParams
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.nybroadband.mobile.R
import com.nybroadband.mobile.databinding.BottomSheetGridCellDetailBinding
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * CoverageMap-style bottom sheet for a tapped grid / hex cell: peek (metrics) + scroll (summary, chart, actions).
 */
class GridCellDetailBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetGridCellDetailBinding? = null
    private val binding get() = _binding!!

    private lateinit var detail: GridCellDetail

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        detail = requireArguments().getParcelable(ARG_DETAIL)!!
    }

    private val numberFormat: NumberFormat = NumberFormat.getNumberInstance(Locale.US).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
    }

    override fun getTheme(): Int = R.style.ThemeOverlay_GridCellBottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetGridCellDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bind(detail)
        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnExpandToggle.setOnClickListener { toggleExpanded() }
        binding.actionDirections.setOnClickListener { openDirections() }
        binding.actionCopyLink.setOnClickListener { copyLink() }

        val dialog = dialog as? BottomSheetDialog
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        if (bottomSheet != null) {
            val behavior = BottomSheetBehavior.from(bottomSheet)
            behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(sheet: View, newState: Int) {
                    val expanded = newState == BottomSheetBehavior.STATE_EXPANDED
                    binding.btnExpandToggle.rotation = if (expanded) 180f else 0f
                }

                override fun onSlide(sheet: View, slideOffset: Float) = Unit
            })
        }
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.peekHeight = resources.getDimensionPixelSize(R.dimen.grid_cell_sheet_peek_height)
        behavior.skipCollapsed = false
        behavior.isFitToContents = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun bind(d: GridCellDetail) {
        binding.tvPlaceTitle.text = d.placeTitle
        binding.chipCarrier.text = d.carrierPill
        binding.chipDate.text = d.dateLabel

        binding.tvFastestDlValue.text = formatMbpsValue(d.fastestDownloadMbps)
        binding.tvFastestUlValue.text = formatMbpsValue(d.fastestUploadMbps)

        setMetricBar(binding.barDlTrack, binding.barDlFill, d.fastestDownloadMbps, isDownload = true)
        setMetricBar(binding.barUlTrack, binding.barUlFill, d.fastestUploadMbps, isDownload = false)

        binding.pillFastDl.text = formatMbpsPill(d.fastestDownloadMbps)
        binding.pillFastUl.text = formatMbpsPill(d.fastestUploadMbps)
        binding.pillFastLat.text = formatLatencyPill(d.latencyFastMs)

        binding.pillAvgDl.text = formatMbpsPill(d.avgDownloadMbps)
        binding.pillAvgUl.text = formatMbpsPill(d.avgUploadMbps)
        binding.pillAvgLat.text = formatLatencyPill(d.latencyAvgMs)

        binding.pillSlowDl.text = formatMbpsPill(d.slowDownloadMbps)
        binding.pillSlowUl.text = formatMbpsPill(d.slowUploadMbps)
        binding.pillSlowLat.text = formatLatencyPill(d.latencySlowMs)

        val chartMax = maxOf(250, d.chartAttMbps, d.chartTmoMbps, d.chartVzwMbps)
        postChartBar(binding.chartTrackAtt, binding.chartBarAtt, d.chartAttMbps, chartMax)
        postChartBar(binding.chartTrackTmo, binding.chartBarTmo, d.chartTmoMbps, chartMax)
        postChartBar(binding.chartTrackVzw, binding.chartBarVzw, d.chartVzwMbps, chartMax)
    }

    private fun formatMbpsValue(v: Double): String =
        if (v >= 1000) numberFormat.format(v.roundToInt()) else numberFormat.format(v)

    private fun formatMbpsPill(v: Double): String =
        getString(R.string.map_grid_cell_pill_mbps_fmt, formatMbpsValue(v))

    private fun formatLatencyPill(ms: Int): String =
        getString(R.string.map_grid_cell_pill_ms_fmt, numberFormat.format(ms))

    private fun setMetricBar(track: View, fill: View, mbps: Double, isDownload: Boolean) {
        val cap = maxOf(1200.0, mbps * 1.25)
        val ratio = (mbps / cap).toFloat().coerceIn(0.05f, 1f)
        val good = mbps >= 50.0
        fill.setBackgroundResource(
            if (good) R.drawable.bg_metric_bar_fill_good
            else if (isDownload) R.drawable.bg_metric_bar_fill_download
            else R.drawable.bg_metric_bar_fill_upload
        )
        track.post {
            val w = (track.width * ratio).toInt().coerceAtLeast(4)
            fill.updateLayoutParams<ViewGroup.LayoutParams> { width = w }
        }
    }

    private fun postChartBar(track: View, bar: View, mbps: Int, chartMax: Int) {
        track.post {
            val ratio = if (chartMax <= 0) 0.05f else mbps.toFloat() / chartMax.toFloat()
            val w = (track.width * ratio.coerceIn(0.04f, 1f)).toInt().coerceAtLeast(4)
            bar.updateLayoutParams<ViewGroup.LayoutParams> { width = w }
        }
    }

    private fun toggleExpanded() {
        val dialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val behavior = BottomSheetBehavior.from(bottomSheet)
        if (behavior.state == BottomSheetBehavior.STATE_EXPANDED) {
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        } else {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun openDirections() {
        val d = detail
        val uri = Uri.parse("geo:${d.tapLat},${d.tapLon}?q=${d.tapLat},${d.tapLon}")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(intent)
        }
    }

    private fun copyLink() {
        val d = detail
        val link = "https://maps.example.com/cell?lat=${d.tapLat}&lon=${d.tapLon}"
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("cell", link))
        android.widget.Toast.makeText(
            requireContext(),
            R.string.map_grid_cell_copy_toast,
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    companion object {
        private const val ARG_DETAIL = "detail"

        fun newInstance(detail: GridCellDetail): GridCellDetailBottomSheet =
            GridCellDetailBottomSheet().apply {
                arguments = bundleOf(ARG_DETAIL to detail)
            }
    }
}

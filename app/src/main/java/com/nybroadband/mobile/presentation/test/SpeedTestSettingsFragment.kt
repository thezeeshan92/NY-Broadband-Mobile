package com.nybroadband.mobile.presentation.test

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.slider.Slider
import com.nybroadband.mobile.R
import com.nybroadband.mobile.databinding.FragmentSpeedTestSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Test Settings bottom sheet — mirrors the CoverageMap.com Test Settings UI.
 *
 * Sections:
 *   1. Data Use   — all-time / period totals, cellular limit toggle, reset
 *   2. Default Server — auto-select indicator
 *   3. Default Tag — Indoors / Outdoors / Driving / Other
 *   4. Test Duration — download + upload sliders (7–15 s), linked by default
 *   5. Theme       — Default / Glow / Stealth
 *   6. Units       — Kbps|Mbps  and  Miles|Kilometers
 *
 * All settings are immediately persisted via [SpeedTestSettingsStore].
 */
@AndroidEntryPoint
class SpeedTestSettingsFragment : BottomSheetDialogFragment() {

    @Inject lateinit var store: SpeedTestSettingsStore

    private var _binding: FragmentSpeedTestSettingsBinding? = null
    private val binding get() = _binding!!

    // Whether the download/upload sliders move together
    private var durationsLinked = true

    override fun getTheme(): Int = R.style.ThemeOverlay_TestSettingsBottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSpeedTestSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Expand to full height so the sheet is fully visible without dragging
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }

        loadAndDisplay()
        setupListeners()
    }

    // ── Load stored values into UI ─────────────────────────────────────────────

    private fun loadAndDisplay() {
        // Data usage
        binding.tvAllTimeTotal.text    = formatMb(store.allTimeDataBytes)
        binding.tvPeriodTotal.text     = formatMb(store.periodDataBytes)
        binding.tvPeriodCellular.text  =
            if (store.periodCellularBytes == 0L) getString(R.string.settings_data_none)
            else formatMb(store.periodCellularBytes)
        binding.tvPeriodWifi.text      = formatMb(store.periodWifiBytes)

        binding.switchCellularLimit.isChecked = store.setCellularDataLimit
        binding.switchResetMonthly.isChecked  = store.resetUsageMonthly

        // Default Tag
        selectTagTab(store.defaultTag)
        binding.switchUseTags.isChecked  = store.useTags
        binding.switchAskBefore.isChecked = store.askBeforeTest

        // Duration sliders
        val dl = store.downloadDurationSeconds.toFloat().coerceIn(7f, 15f)
        val ul = store.uploadDurationSeconds.toFloat().coerceIn(7f, 15f)
        binding.sliderDownload.value     = dl
        binding.sliderUpload.value       = ul
        binding.tvDownloadDuration.text  = dl.roundToInt().toString()
        binding.tvUploadDuration.text    = ul.roundToInt().toString()

        // Theme
        selectThemeTab(store.theme)

        // Units
        selectSpeedUnit(store.speedUnit)
        selectDistanceUnit(store.distanceUnit)
    }

    // ── Click / change listeners ───────────────────────────────────────────────

    private fun setupListeners() {
        binding.btnCloseSettings.setOnClickListener { dismiss() }

        // Data Use toggles
        binding.switchCellularLimit.setOnCheckedChangeListener { _, checked ->
            store.setCellularDataLimit = checked
        }
        binding.switchResetMonthly.setOnCheckedChangeListener { _, checked ->
            store.resetUsageMonthly = checked
        }
        binding.btnResetUsage.setOnClickListener {
            store.resetPeriodUsage()
            binding.tvPeriodTotal.text    = formatMb(0L)
            binding.tvPeriodCellular.text = getString(R.string.settings_data_none)
            binding.tvPeriodWifi.text     = formatMb(0L)
        }

        // Default Tag tabs
        listOf(
            binding.tabTagIndoors  to SpeedTestSettingsStore.TAG_INDOORS,
            binding.tabTagOutdoors to SpeedTestSettingsStore.TAG_OUTDOORS,
            binding.tabTagDriving  to SpeedTestSettingsStore.TAG_DRIVING,
            binding.tabTagOther    to SpeedTestSettingsStore.TAG_OTHER,
        ).forEach { (tab, tag) ->
            tab.setOnClickListener {
                store.defaultTag = tag
                selectTagTab(tag)
            }
        }
        binding.switchUseTags.setOnCheckedChangeListener { _, checked ->
            store.useTags = checked
        }
        binding.switchAskBefore.setOnCheckedChangeListener { _, checked ->
            store.askBeforeTest = checked
        }

        // Link/unlink icon toggles linked slider behaviour
        binding.ivLinkDurations.setOnClickListener {
            durationsLinked = !durationsLinked
            binding.ivLinkDurations.alpha = if (durationsLinked) 1f else 0.35f
            if (durationsLinked) {
                // Snap upload to download value
                val v = binding.sliderDownload.value
                binding.sliderUpload.value    = v
                binding.tvUploadDuration.text = v.roundToInt().toString()
                store.uploadDurationSeconds   = v.roundToInt()
            }
        }

        // Duration sliders — listener fires for both user and programmatic changes
        binding.sliderDownload.addOnChangeListener(Slider.OnChangeListener { _, value, fromUser ->
            if (!fromUser) return@OnChangeListener
            val secs = value.roundToInt()
            binding.tvDownloadDuration.text = secs.toString()
            store.downloadDurationSeconds   = secs
            if (durationsLinked) {
                binding.sliderUpload.value    = value
                binding.tvUploadDuration.text = secs.toString()
                store.uploadDurationSeconds   = secs
            }
        })
        binding.sliderUpload.addOnChangeListener(Slider.OnChangeListener { _, value, fromUser ->
            if (!fromUser) return@OnChangeListener
            val secs = value.roundToInt()
            binding.tvUploadDuration.text = secs.toString()
            store.uploadDurationSeconds   = secs
            if (durationsLinked) {
                binding.sliderDownload.value    = value
                binding.tvDownloadDuration.text = secs.toString()
                store.downloadDurationSeconds   = secs
            }
        })

        // Theme tabs
        listOf(
            binding.tabThemeDefault  to SpeedTestSettingsStore.THEME_DEFAULT,
            binding.tabThemeGlow     to SpeedTestSettingsStore.THEME_GLOW,
            binding.tabThemeStealth  to SpeedTestSettingsStore.THEME_STEALTH,
        ).forEach { (tab, theme) ->
            tab.setOnClickListener {
                store.theme = theme
                selectThemeTab(theme)
            }
        }

        // Speed unit tabs
        binding.tabUnitKbps.setOnClickListener {
            store.speedUnit = SpeedTestSettingsStore.UNIT_KBPS
            selectSpeedUnit(SpeedTestSettingsStore.UNIT_KBPS)
        }
        binding.tabUnitMbps.setOnClickListener {
            store.speedUnit = SpeedTestSettingsStore.UNIT_MBPS
            selectSpeedUnit(SpeedTestSettingsStore.UNIT_MBPS)
        }

        // Distance unit tabs
        binding.tabUnitMiles.setOnClickListener {
            store.distanceUnit = SpeedTestSettingsStore.UNIT_MILES
            selectDistanceUnit(SpeedTestSettingsStore.UNIT_MILES)
        }
        binding.tabUnitKilometers.setOnClickListener {
            store.distanceUnit = SpeedTestSettingsStore.UNIT_KM
            selectDistanceUnit(SpeedTestSettingsStore.UNIT_KM)
        }
    }

    // ── Tab selection helpers ──────────────────────────────────────────────────

    private fun selectTagTab(tag: String) {
        val map = mapOf(
            SpeedTestSettingsStore.TAG_INDOORS  to binding.tabTagIndoors,
            SpeedTestSettingsStore.TAG_OUTDOORS to binding.tabTagOutdoors,
            SpeedTestSettingsStore.TAG_DRIVING  to binding.tabTagDriving,
            SpeedTestSettingsStore.TAG_OTHER    to binding.tabTagOther,
        )
        applyTabSelection(map, tag)
    }

    private fun selectThemeTab(theme: String) {
        val map = mapOf(
            SpeedTestSettingsStore.THEME_DEFAULT to binding.tabThemeDefault,
            SpeedTestSettingsStore.THEME_GLOW    to binding.tabThemeGlow,
            SpeedTestSettingsStore.THEME_STEALTH to binding.tabThemeStealth,
        )
        applyTabSelection(map, theme)
    }

    private fun selectSpeedUnit(unit: String) {
        val map = mapOf(
            SpeedTestSettingsStore.UNIT_KBPS to binding.tabUnitKbps,
            SpeedTestSettingsStore.UNIT_MBPS to binding.tabUnitMbps,
        )
        applyTabSelection(map, unit)
    }

    private fun selectDistanceUnit(unit: String) {
        val map = mapOf(
            SpeedTestSettingsStore.UNIT_MILES to binding.tabUnitMiles,
            SpeedTestSettingsStore.UNIT_KM    to binding.tabUnitKilometers,
        )
        applyTabSelection(map, unit)
    }

    private fun applyTabSelection(tabs: Map<String, TextView>, selectedKey: String) {
        tabs.forEach { (key, tab) ->
            val selected = key == selectedKey
            tab.setBackgroundResource(
                if (selected) R.drawable.bg_speed_context_tab_selected
                else R.drawable.bg_speed_context_tab_unselected
            )
            tab.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt())
            tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    // ── Formatting ─────────────────────────────────────────────────────────────

    private fun formatMb(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return getString(R.string.settings_data_mb_fmt, mb)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
package com.nybroadband.mobile.presentation.test

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.nybroadband.mobile.R
import com.nybroadband.mobile.databinding.FragmentSpeedTestBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Speed Test tab — CoverageMap-style launcher screen.
 *
 * Shows carrier/ISP name, device model, connection type, and the large gradient
 * "Start Test" circle. Context tabs (Indoors / Outdoors / Driving / Other) set
 * the measurement context label passed to [TestInProgressFragment].
 *
 * Tapping "Start Test" navigates directly to [TestInProgressFragment] with
 * default duration and auto-selected server — no configuration screen needed.
 */
@AndroidEntryPoint
class SpeedTestFragment : Fragment() {

    @Inject lateinit var settingsStore: SpeedTestSettingsStore

    private var _binding: FragmentSpeedTestBinding? = null
    private val binding get() = _binding!!

    // Currently selected context tab: 0=Indoors, 1=Outdoors, 2=Driving, 3=Other
    private var selectedContextTab = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSpeedTestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        populateDeviceInfo()
        setupContextTabs()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        // Refresh connection info when returning to screen
        populateDeviceInfo()
    }

    // ── Device / network info ─────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun populateDeviceInfo() {
        // Carrier name
        val telephony = requireContext().getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val carrier = telephony.networkOperatorName.takeIf { it.isNotBlank() }
            ?: getString(R.string.speed_detecting)
        binding.tvCarrierName.text = carrier

        // Device model + connection type label
        val deviceModel = Build.MODEL
        val connection = connectionLabel()
        binding.tvDeviceInfo.text = "$deviceModel · $connection"

        // Connection icon
        binding.ivConnectionIcon.setImageResource(
            if (isOnWifi()) R.drawable.ic_wifi_24
            else R.drawable.ic_cellular
        )
    }

    private fun connectionLabel(): String {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nc = cm.getNetworkCapabilities(cm.activeNetwork) ?: return getString(R.string.speed_cellular)
        return when {
            nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> getString(R.string.speed_wifi)
            nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getString(R.string.speed_cellular)
            nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> getString(R.string.speed_cellular)
        }
    }

    private fun isOnWifi(): Boolean {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nc = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    // ── Context tabs ──────────────────────────────────────────────────────────

    private val contextTabs: List<TextView> by lazy {
        listOf(binding.tabIndoors, binding.tabOutdoors, binding.tabDriving, binding.tabOther)
    }

    private fun setupContextTabs() {
        contextTabs.forEachIndexed { index, tab ->
            tab.setOnClickListener { selectTab(index) }
        }
        selectTab(selectedContextTab)
    }

    private fun selectTab(index: Int) {
        selectedContextTab = index
        contextTabs.forEachIndexed { i, tab ->
            if (i == index) {
                tab.setBackgroundResource(R.drawable.bg_speed_context_tab_selected)
                tab.setTextColor(0xFFFFFFFF.toInt())
                tab.setTypeface(null, Typeface.BOLD)
            } else {
                tab.setBackgroundResource(R.drawable.bg_speed_context_tab_unselected)
                tab.setTextColor(0xFFAAAAAA.toInt())
                tab.setTypeface(null, Typeface.NORMAL)
            }
        }
    }

    // ── Click listeners ───────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.btnStartTest.setOnClickListener {
            findNavController().navigate(
                R.id.action_speedTest_to_testInProgress,
                bundleOf(
                    TestInProgressFragment.ARG_DURATION_SEC to settingsStore.downloadDurationSeconds,
                    TestInProgressFragment.ARG_SERVER_ID    to settingsStore.selectedServerId,
                )
            )
        }

        binding.btnRunMultiple.setOnClickListener {
            findNavController().navigate(R.id.action_speedTest_to_recurringSetup)
        }

        // Gear button opens Test Settings bottom sheet
        binding.btnSpeedSettings.setOnClickListener {
            SpeedTestSettingsFragment().show(childFragmentManager, "test_settings")
        }

        binding.btnSignalInfo.setOnClickListener {
            requireActivity().findViewById<BottomNavigationView>(R.id.bottomNav)
                .selectedItemId = R.id.cellularFragment
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
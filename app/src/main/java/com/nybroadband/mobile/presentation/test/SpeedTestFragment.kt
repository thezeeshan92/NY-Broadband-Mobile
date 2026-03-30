package com.nybroadband.mobile.presentation.test

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nybroadband.mobile.R
import com.nybroadband.mobile.databinding.FragmentSpeedTestBinding
import com.nybroadband.mobile.presentation.AppPermissionViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Speed Test tab — CoverageMap-style launcher screen.
 *
 * Checks location permission before starting a test so the result can be
 * tagged to the user's current position and appear on the coverage map.
 */
@AndroidEntryPoint
class SpeedTestFragment : Fragment() {

    @Inject lateinit var settingsStore: SpeedTestSettingsStore

    private var _binding: FragmentSpeedTestBinding? = null
    private val binding get() = _binding!!

    private val permissionViewModel: AppPermissionViewModel by activityViewModels()

    // Selected context tab: 0=Indoors, 1=Outdoors, 2=Driving, 3=Other
    private var selectedContextTab = 0

    // Set when the user taps "Open Settings" so we can re-check on resume.
    private var awaitingSettingsReturn = false

    // ── Permission launcher ───────────────────────────────────────────────────

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        permissionViewModel.refresh()
        if (granted) {
            // Permission just granted — proceed to the test.
            startTest()
        }
        // If still denied or permanently denied, user sees no further prompt.
        // They can tap "Start Test" again to see the rationale dialog.
    }

    // ── View lifecycle ────────────────────────────────────────────────────────

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
        permissionViewModel.refresh()
        populateDeviceInfo()

        if (awaitingSettingsReturn) {
            awaitingSettingsReturn = false
            // If user granted location in Settings, try to start the test.
            if (hasLocationPermission()) startTest()
        }
    }

    // ── Device / network info ─────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun populateDeviceInfo() {
        val telephony = requireContext().getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val carrier = telephony.networkOperatorName.takeIf { it.isNotBlank() }
            ?: getString(R.string.speed_detecting)
        binding.tvCarrierName.text = carrier

        val deviceModel = Build.MODEL
        val connection = connectionLabel()
        binding.tvDeviceInfo.text = "$deviceModel · $connection"

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
            checkPermissionThenStart()
        }

        binding.btnRunMultiple.setOnClickListener {
            findNavController().navigate(R.id.action_speedTest_to_recurringSetup)
        }

        binding.btnSpeedSettings.setOnClickListener {
            SpeedTestSettingsFragment().show(childFragmentManager, "test_settings")
        }

        binding.btnSignalInfo.setOnClickListener {
            requireActivity().findViewById<BottomNavigationView>(R.id.bottomNav)
                .selectedItemId = R.id.cellularFragment
        }
    }

    // ── Permission gate ───────────────────────────────────────────────────────

    /**
     * Checks location permission before starting a test.
     *
     * Flow:
     *  - Granted → start test immediately.
     *  - Denied, can ask → show rationale dialog → launch system dialog → start on grant.
     *  - Permanently denied → show rationale dialog with "Open Settings" CTA.
     */
    private fun checkPermissionThenStart() {
        if (hasLocationPermission()) {
            startTest()
            return
        }

        if (isPermanentlyDenied()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.perm_speed_test_dialog_title)
                .setMessage(R.string.perm_speed_test_settings_body)
                .setNegativeButton(R.string.perm_speed_test_dialog_cancel, null)
                .setPositiveButton(R.string.perm_overlay_open_settings) { _, _ ->
                    awaitingSettingsReturn = true
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", requireContext().packageName, null)
                        }
                    )
                }
                .show()
        } else {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.perm_speed_test_dialog_title)
                .setMessage(R.string.perm_speed_test_dialog_body)
                .setNegativeButton(R.string.perm_speed_test_dialog_cancel, null)
                .setPositiveButton(R.string.perm_speed_test_dialog_ok) { _, _ ->
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                }
                .show()
        }
    }

    private fun startTest() {
        findNavController().navigate(
            R.id.action_speedTest_to_testInProgress,
            bundleOf(
                TestInProgressFragment.ARG_DURATION_SEC to settingsStore.downloadDurationSeconds,
                TestInProgressFragment.ARG_SERVER_ID    to settingsStore.selectedServerId,
            )
        )
    }

    // ── Permission helpers ────────────────────────────────────────────────────

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Safe to call only after at least one dialog attempt has fired.
     * Returns true when the system will no longer show a dialog.
     */
    private fun isPermanentlyDenied(): Boolean =
        !hasLocationPermission() &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
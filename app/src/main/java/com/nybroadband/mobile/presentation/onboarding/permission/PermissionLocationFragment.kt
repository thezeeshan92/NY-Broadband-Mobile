package com.nybroadband.mobile.presentation.onboarding.permission

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.nybroadband.mobile.R
import com.nybroadband.mobile.databinding.FragmentPermissionLocationBinding
import com.nybroadband.mobile.presentation.onboarding.OnboardingViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Requests ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION (foreground only).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Android permission model — three distinct states for this screen:
 *
 *  1. FIRST REQUEST
 *     shouldShowRequestPermissionRationale() = false (never asked before)
 *     → Launch dialog. System shows "Allow / Deny / Allow only while using".
 *
 *  2. DENIED ONCE ("Deny" tapped, no "Don't ask again")
 *     shouldShowRequestPermissionRationale() = true
 *     → Can ask again. Launch dialog again.
 *
 *  3. PERMANENTLY DENIED ("Don't ask again" was checked on API 26–29,
 *     or denied twice on API 30+)
 *     shouldShowRequestPermissionRationale() = false AND permission still DENIED
 *     → Dialog will not show. Must send user to app Settings.
 *     → Detected in the launcher CALLBACK (not before), where we swap the
 *       button to "Open Settings".
 *
 * Key rule: we cannot distinguish case 1 from case 3 BEFORE the dialog fires.
 * Always launch the dialog. Detect permanent denial in the result callback.
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * On grant:  navigate to background location step (API 29+) or phone state (API ≤28).
 * On deny/skip: navigate forward anyway — app works with reduced functionality.
 */
@AndroidEntryPoint
class PermissionLocationFragment : Fragment() {

    private var _binding: FragmentPermissionLocationBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OnboardingViewModel by activityViewModels()

    // Track whether we've sent the user to Settings so onResume can re-check
    private var awaitingSettingsReturn = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      results[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        when {
            granted -> navigateNext(granted = true)

            isPermanentlyDenied() -> {
                // "Don't ask again" path — swap button to open Settings instead
                binding.btnAllow.text = getString(R.string.action_open_settings)
                binding.btnAllow.setOnClickListener { openAppSettings() }
                // tvSkip remains visible so user can still proceed without location
            }

            else -> {
                // Denied once but can ask again — button stays as-is for a second attempt
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPermissionLocationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.tvStep.text = getString(
            R.string.onboarding_step_of, 1, viewModel.totalPermissionSteps
        )

        // Already granted (e.g. re-running onboarding in dev) — skip immediately
        if (isLocationGranted()) {
            navigateNext(granted = true)
            return
        }

        // "Don't ask again" was set in a previous session — go straight to Settings button
        if (isPermanentlyDenied()) {
            binding.btnAllow.text = getString(R.string.action_open_settings)
            binding.btnAllow.setOnClickListener { openAppSettings() }
        } else {
            binding.btnAllow.setOnClickListener {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }

        binding.tvSkip.setOnClickListener { navigateNext(granted = false) }
    }

    override fun onResume() {
        super.onResume()
        if (awaitingSettingsReturn) {
            awaitingSettingsReturn = false
            navigateNext(granted = isLocationGranted())
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun navigateNext(granted: Boolean) {
        if (granted && viewModel.needsBackgroundLocationStep) {
            findNavController().navigate(R.id.action_location_to_background)
        } else {
            findNavController().navigate(R.id.action_location_to_phoneState)
        }
    }

    private fun isLocationGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * True only after the callback fires with denial AND
     * shouldShowRequestPermissionRationale() returns false — meaning the system
     * will no longer show the dialog.
     *
     * NOT safe to call before the first dialog attempt (returns false for both
     * first-time requests and permanent denials before a dialog has been shown).
     * Call only from the launcher callback or after a known prior denial.
     */
    private fun isPermanentlyDenied(): Boolean =
        !isLocationGranted() &&
        !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) &&
        !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun openAppSettings() {
        awaitingSettingsReturn = true
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", requireContext().packageName, null)
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

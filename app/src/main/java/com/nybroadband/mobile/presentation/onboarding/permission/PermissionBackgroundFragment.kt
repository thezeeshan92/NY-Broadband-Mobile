package com.nybroadband.mobile.presentation.onboarding.permission

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.nybroadband.mobile.R
import com.nybroadband.mobile.databinding.FragmentPermissionBackgroundBinding
import com.nybroadband.mobile.presentation.onboarding.OnboardingViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Requests ACCESS_BACKGROUND_LOCATION — shown only on API 29+ (see nav_onboarding.xml).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Android version differences — critical:
 *
 *  API 29 (Android 10):
 *    • ACCESS_BACKGROUND_LOCATION was introduced.
 *    • MUST be requested in a separate call — bundling with FINE/COARSE silently drops it.
 *    • System shows a standard dialog with "Allow all the time" / "Allow only while using"
 *      / "Deny". Selecting "Allow all the time" grants background access.
 *
 *  API 30+ (Android 11+):
 *    • requestPermissions() for BACKGROUND_LOCATION NO LONGER shows a dialog.
 *      The call is silently ignored — Android forces the user to grant via Settings.
 *    • Our UX: show an explanation card and a "Open Location Settings" button.
 *    • We detect the return from Settings in onResume() using a flag.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * On success or skip: navigate to permissionPhoneStateFragment.
 */
@AndroidEntryPoint
class PermissionBackgroundFragment : Fragment() {

    private var _binding: FragmentPermissionBackgroundBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OnboardingViewModel by activityViewModels()

    // Set to true after we open Settings, so onResume() knows to re-check
    private var awaitingSettingsReturn = false

    @RequiresApi(Build.VERSION_CODES.Q)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        navigateNext()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPermissionBackgroundBinding.inflate(inflater, container, false)
        return binding.root
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.tvStep.text = getString(R.string.onboarding_step_of, 2, viewModel.totalPermissionSteps)

        // Already granted — move on without showing this screen
        if (isBackgroundLocationGranted()) {
            navigateNext()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: no dialog — must go to Settings
            binding.tvBody.text = getString(R.string.perm_background_body_v30)
            binding.settingsNote.isVisible = true
            binding.btnAllow.text = getString(R.string.perm_background_open_settings)
            binding.btnAllow.setOnClickListener { openLocationSettings() }
        } else {
            // API 29: standard permission dialog available
            binding.tvBody.text = getString(R.string.perm_background_body)
            binding.settingsNote.isVisible = false
            binding.btnAllow.text = getString(R.string.perm_background_allow)
            binding.btnAllow.setOnClickListener {
                permissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }

        binding.tvSkip.setOnClickListener { navigateNext() }
    }

    /**
     * Called when the user returns from the system Settings screen (API 30+ path).
     * We re-check the permission and navigate forward automatically if granted.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onResume() {
        super.onResume()
        if (awaitingSettingsReturn) {
            awaitingSettingsReturn = false
            // Navigate whether granted or not — user has made their choice
            navigateNext()
        }
    }

    private fun navigateNext() {
        findNavController().navigate(R.id.action_background_to_phoneState)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun isBackgroundLocationGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Opens the app's location permission settings page.
     * This is the only way to grant background location on API 30+.
     */
    private fun openLocationSettings() {
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

package com.nybroadband.mobile.presentation.onboarding.permission

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
 * Android version differences:
 *
 *  API 29 (Android 10):
 *    • System shows a standard permission dialog with "Allow all the time" / "Allow only
 *      while using" / "Deny".
 *
 *  API 30+ (Android 11+):
 *    • requestPermissions() triggers an OS-managed interstitial that routes the user to
 *      the app's specific Location permission settings page — more targeted than opening
 *      the general app settings manually.
 *
 * Both paths use the same permissionLauncher; the OS handles the appropriate UX per version.
 * ─────────────────────────────────────────────────────────────────────────────
 * On success or skip: navigate to permissionPhoneStateFragment.
 */
@AndroidEntryPoint
class PermissionBackgroundFragment : Fragment() {

    private var _binding: FragmentPermissionBackgroundBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OnboardingViewModel by activityViewModels()

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

        // On all supported API levels (29+), launch the permission request directly.
        // API 29: shows the standard dialog.
        // API 30+: OS shows its own interstitial and routes to the app's location settings.
        binding.settingsNote.isVisible = false
        binding.btnAllow.setOnClickListener {
            permissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        binding.tvSkip.setOnClickListener { navigateNext() }
    }

    private fun navigateNext() {
        findNavController().navigate(R.id.action_background_to_phoneState)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun isBackgroundLocationGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

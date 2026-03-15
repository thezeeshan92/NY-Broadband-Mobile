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
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.nybroadband.mobile.R
import com.nybroadband.mobile.databinding.FragmentPermissionNotificationsBinding
import com.nybroadband.mobile.presentation.onboarding.OnboardingViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Requests POST_NOTIFICATIONS — shown only on API 33+ (Android 13+).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Android version notes:
 *
 *  API 26–32 (Android 8–12):
 *    Notification permission is implicitly granted when the app creates a
 *    NotificationChannel. No runtime permission dialog exists.
 *    This Fragment is NEVER shown on these API levels (nav graph skips it;
 *    PermissionPhoneStateFragment calls onOnboardingComplete() directly).
 *
 *  API 33+ (Android 13+):
 *    POST_NOTIFICATIONS was added as a new DANGEROUS runtime permission.
 *    The app MUST request it at runtime. Without it, foreground service
 *    notifications are silently suppressed — the service still runs, but
 *    the user has no visible control over it.
 *
 *  Why we ask:
 *    The foreground service notification is required by Android when
 *    PassiveCollectionService runs. It is not a marketing or alert channel.
 *    The user can dismiss it from the notification shade at any time.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * This is always the LAST permission step in the onboarding flow.
 * On completion (allow OR skip), calls [OnboardingViewModel.onOnboardingComplete]
 * which persists the flag and emits [OnboardingEvent.LaunchMain].
 */
@AndroidEntryPoint
class PermissionNotificationsFragment : Fragment() {

    private var _binding: FragmentPermissionNotificationsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OnboardingViewModel by activityViewModels()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Proceed whether granted or not — the service runs either way,
        // notifications are simply suppressed if denied on API 33+.
        completeOnboarding()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPermissionNotificationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.tvStep.text = getString(
            R.string.onboarding_step_of,
            viewModel.totalPermissionSteps,   // notifications is always the last step
            viewModel.totalPermissionSteps
        )

        // Already granted — skip straight to completion
        if (isNotificationGranted()) {
            completeOnboarding()
            return
        }

        binding.btnAllow.setOnClickListener {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.tvSkip.setOnClickListener { completeOnboarding() }
    }

    private fun completeOnboarding() {
        viewModel.onOnboardingComplete()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun isNotificationGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

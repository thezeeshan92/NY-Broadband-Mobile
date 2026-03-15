package com.nybroadband.mobile.presentation.onboarding.permission

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.nybroadband.mobile.R
import com.nybroadband.mobile.databinding.FragmentPermissionPhoneStateBinding
import com.nybroadband.mobile.presentation.onboarding.OnboardingViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Requests READ_PHONE_STATE for TelephonyManager signal access.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Android version notes:
 *
 *  All APIs (26+):
 *    READ_PHONE_STATE is in the PHONE permission group (DANGEROUS category).
 *    It requires a runtime request on API 23+.
 *
 *  API 26–29:
 *    Required for getAllCellInfo() / getSignalStrength() / PhoneStateListener.
 *
 *  API 30+:
 *    Still required for TelephonyManager APIs. Additionally, on API 31+
 *    we use TelephonyCallback (replaces the deprecated PhoneStateListener).
 *    Both still require READ_PHONE_STATE to return non-null signal values.
 *
 *  What we READ:   signal strength (dBm), network type (LTE/NR/etc.), carrier name.
 *  What we DON'T:  call state, call log, phone number, IMEI/device ID.
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * On API 26–32: this is the last permission step — calls onOnboardingComplete().
 * On API 33+:  navigates to PermissionNotificationsFragment.
 */
@AndroidEntryPoint
class PermissionPhoneStateFragment : Fragment() {

    private var _binding: FragmentPermissionPhoneStateBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OnboardingViewModel by activityViewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Proceed regardless of grant — signal reading degrades gracefully
        navigateNext()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPermissionPhoneStateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        // Step counter: location=1, background=2 (if shown), phone=next
        val phoneStep = if (viewModel.needsBackgroundLocationStep) 3 else 2
        binding.tvStep.text = getString(R.string.onboarding_step_of, phoneStep, viewModel.totalPermissionSteps)

        if (isPhoneStateGranted()) {
            navigateNext()
            return
        }

        binding.btnAllow.setOnClickListener {
            permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        }

        binding.tvSkip.setOnClickListener { navigateNext() }
    }

    private fun navigateNext() {
        if (viewModel.needsNotificationStep) {
            findNavController().navigate(R.id.action_phoneState_to_notifications)
        } else {
            viewModel.onOnboardingComplete()
        }
    }

    private fun isPhoneStateGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

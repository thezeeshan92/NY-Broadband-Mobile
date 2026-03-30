package com.nybroadband.mobile.presentation.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.nybroadband.mobile.databinding.FragmentSignInBinding
import dagger.hilt.android.AndroidEntryPoint

/**
 * Sign-in / guest decision screen.
 *
 * Currently stubs both paths and navigates straight to the permission flow.
 * In a future iteration:
 *   - Google sign-in: launch GoogleSignInClient intent, handle result in
 *     ActivityResultLauncher, call FirebaseAuth.signInWithCredential()
 *   - Guest: call FirebaseAuth.signInAnonymously()
 */
@AndroidEntryPoint
class SignInFragment : Fragment() {

    private var _binding: FragmentSignInBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSignInBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.btnSignInGoogle.setOnClickListener {
            // TODO: launch GoogleSignInClient and handle credential in next iteration
            proceedToPermissions()
        }

        binding.btnGuest.setOnClickListener {
            // TODO: FirebaseAuth.getInstance().signInAnonymously() in next iteration
            proceedToPermissions()
        }
    }

    private fun proceedToPermissions() {
        // Sign-in screen is no longer part of the active onboarding flow.
        // WelcomeFragment navigates directly to PermissionLocationFragment.
        // This method is kept as a stub in case sign-in is re-introduced later.
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

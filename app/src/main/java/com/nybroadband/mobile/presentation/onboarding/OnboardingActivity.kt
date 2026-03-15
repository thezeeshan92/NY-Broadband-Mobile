package com.nybroadband.mobile.presentation.onboarding

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nybroadband.mobile.databinding.ActivityOnboardingBinding
import com.nybroadband.mobile.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Hosts the onboarding nav graph (nav_onboarding.xml).
 * Launched by MainActivity if [OnboardingPrefs.isComplete] is false.
 *
 * On completion the Activity starts MainActivity and finishes itself —
 * the user never sees the back stack returning to onboarding.
 */
@AndroidEntryPoint
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private val viewModel: OnboardingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        observeEvents()
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        OnboardingEvent.LaunchMain -> launchMain()
                    }
                }
            }
        }
    }

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

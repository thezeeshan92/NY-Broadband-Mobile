package com.nybroadband.mobile.presentation

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.nybroadband.mobile.R
import com.nybroadband.mobile.data.local.prefs.OnboardingPrefs
import com.nybroadband.mobile.databinding.ActivityMainBinding
import com.nybroadband.mobile.presentation.onboarding.OnboardingActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var onboardingPrefs: OnboardingPrefs

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    // Destinations where the bottom nav bar should be hidden
    private val hiddenNavDestinations = setOf(
        R.id.manualTestFragment,
        R.id.recurringSetupFragment,
        R.id.deadZoneFragment,
        R.id.testResultFragment,
        R.id.profileFragment
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Onboarding gate ───────────────────────────────────────────────────
        // First launch: redirect to onboarding and finish this Activity so the
        // back button cannot return to it before onboarding is complete.
        if (!onboardingPrefs.isComplete) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupNavigation()
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        navController = navHostFragment.navController

        // Item IDs in bottom_nav_menu.xml must match fragment IDs in nav_graph.xml
        binding.bottomNav.setupWithNavController(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.bottomNav.visibility =
                if (destination.id in hiddenNavDestinations) View.GONE else View.VISIBLE
        }
    }

    // Allows child fragments to access the NavController without going through
    // supportFragmentManager themselves.
    fun getNavController(): NavController = navController
}

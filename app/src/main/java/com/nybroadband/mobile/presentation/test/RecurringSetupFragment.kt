package com.nybroadband.mobile.presentation.test

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.nybroadband.mobile.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * Recurring auto-test setup screen.
 * User configures interval and data cap before enabling the background test service.
 * TODO: implement interval picker and service start/stop logic in a future iteration.
 */
@AndroidEntryPoint
class RecurringSetupFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_recurring_setup, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { findNavController().navigateUp() }
    }
}

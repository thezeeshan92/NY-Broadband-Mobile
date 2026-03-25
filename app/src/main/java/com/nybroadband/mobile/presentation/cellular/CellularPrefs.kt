package com.nybroadband.mobile.presentation.cellular

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class CellularSettings(
    val advancedView: Boolean = true,
    val hideNeighborCells: Boolean = false,
    val refreshIntervalSeconds: Int = 7,
)

@Singleton
class CellularPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("cellular_settings", Context.MODE_PRIVATE)

    fun load(): CellularSettings = CellularSettings(
        advancedView          = prefs.getBoolean(KEY_ADVANCED_VIEW, true),
        hideNeighborCells     = prefs.getBoolean(KEY_HIDE_NEIGHBORS, false),
        refreshIntervalSeconds = prefs.getInt(KEY_REFRESH_INTERVAL, 7),
    )

    fun save(settings: CellularSettings) {
        prefs.edit()
            .putBoolean(KEY_ADVANCED_VIEW, settings.advancedView)
            .putBoolean(KEY_HIDE_NEIGHBORS, settings.hideNeighborCells)
            .putInt(KEY_REFRESH_INTERVAL, settings.refreshIntervalSeconds)
            .apply()
    }

    companion object {
        private const val KEY_ADVANCED_VIEW    = "advanced_view"
        private const val KEY_HIDE_NEIGHBORS   = "hide_neighbors"
        private const val KEY_REFRESH_INTERVAL = "refresh_interval"

        const val REFRESH_MIN = 1
        const val REFRESH_MAX = 60
    }
}

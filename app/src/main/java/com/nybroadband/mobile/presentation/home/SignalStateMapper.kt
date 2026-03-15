package com.nybroadband.mobile.presentation.home

import com.nybroadband.mobile.R
import com.nybroadband.mobile.service.signal.SignalSnapshot

/**
 * Maps a [SignalSnapshot] from the service layer to the presentation [SignalState]
 * consumed by [HomeViewModel] and rendered by [HomeMapFragment].
 *
 * Keeps the service layer free of any dependency on presentation types, and the
 * presentation layer free of raw signal metric logic.
 */
fun SignalSnapshot.toSignalState(): SignalState = SignalState(
    qualityLabel    = tierLabel(),
    qualityColorRes = tierColorRes(),
    networkType     = if (isNoService) "--" else networkType,
    carrierName     = carrierName ?: "--",
    bars            = if (isNoService) 0 else signalBars
)

private fun SignalSnapshot.tierLabel(): String = when (signalTier) {
    "GOOD" -> "Good"        // R.string.signal_good
    "FAIR" -> "Fair"        // R.string.signal_fair
    "WEAK" -> "Weak"        // R.string.signal_weak
    "POOR" -> "No Signal"   // R.string.signal_poor
    else   -> "--"          // R.string.signal_unknown — NONE or initialising
}

private fun SignalSnapshot.tierColorRes(): Int = when (signalTier) {
    "GOOD" -> R.color.signal_good
    "FAIR" -> R.color.signal_fair
    "WEAK" -> R.color.signal_weak
    "POOR" -> R.color.signal_poor
    else   -> R.color.signal_none
}

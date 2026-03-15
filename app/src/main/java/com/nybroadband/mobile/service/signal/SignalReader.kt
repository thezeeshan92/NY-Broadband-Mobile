package com.nybroadband.mobile.service.signal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Telephony signal listener. Exposes the most recent [SignalSnapshot] as a
 * [StateFlow] so both [PassiveCollectionService] (for writes) and
 * [HomeViewModel] (via binder) can observe it reactively.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Android version handling:
 *
 *  API 31+ (Android 12+):
 *    [TelephonyCallback] — current API. Single object implementing multiple
 *    listener interfaces registered with a background Executor.
 *    Listens for: SignalStrengths, DisplayInfo (5G NSA), ServiceState.
 *
 *  API 30 (Android 11):
 *    [PhoneStateListener] — deprecated but functional. Includes
 *    LISTEN_DISPLAY_INFO_CHANGED so 5G NSA override is available.
 *    Registered on main thread (executor not supported).
 *
 *  API 26–29 (Android 8–10):
 *    [PhoneStateListener] with LISTEN_SIGNAL_STRENGTHS + LISTEN_SERVICE_STATE.
 *    No DisplayInfo → 5G NSA is not distinguishable from LTE on these versions.
 *    LISTEN_DISPLAY_INFO_CHANGED (API 30) is deliberately excluded.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Lifecycle: [start] is called by [PassiveCollectionService.onStartCommand].
 *            [stop]  is called by [PassiveCollectionService.onDestroy].
 *
 * READ_PHONE_STATE permission is required by the caller before [start].
 * This class does not perform its own permission check.
 */
@Singleton
class SignalReader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private val _snapshot = MutableStateFlow<SignalSnapshot?>(null)
    val snapshot: StateFlow<SignalSnapshot?> = _snapshot.asStateFlow()

    // API 31+ callback (held so we can unregister it)
    private var modernCallback: TelephonyCallback? = null

    // API 26–30 listener (held so we can listen(NONE))
    @Suppress("DEPRECATION")
    private var legacyListener: LegacyPhoneStateListener? = null

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun start() {
        Timber.d("SignalReader: start (API ${Build.VERSION.SDK_INT})")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startModern()
        } else {
            startLegacy()
        }
    }

    fun stop() {
        Timber.d("SignalReader: stop")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modernCallback?.let {
                telephonyManager.unregisterTelephonyCallback(it)
                modernCallback = null
            }
        } else {
            @Suppress("DEPRECATION")
            legacyListener?.let {
                telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
                legacyListener = null
            }
        }
        // Do not clear _snapshot — callers may read the last known value after stop.
    }

    // ── API 31+ path ─────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission")
    private fun startModern() {
        val cb = ModernTelephonyCallback()
        // Register on a dedicated background thread — callbacks must not block main.
        telephonyManager.registerTelephonyCallback(
            Executors.newSingleThreadExecutor(),
            cb
        )
        modernCallback = cb
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private inner class ModernTelephonyCallback :
        TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.DisplayInfoListener,
        TelephonyCallback.ServiceStateListener {

        private var lastSignalStrength: SignalStrength? = null
        private var lastDisplayInfo: TelephonyDisplayInfo? = null

        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            lastSignalStrength = signalStrength
            rebuild()
        }

        @RequiresApi(Build.VERSION_CODES.R)
        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
            lastDisplayInfo = telephonyDisplayInfo
            rebuild()
        }

        override fun onServiceStateChanged(serviceState: ServiceState) {
            if (serviceState.state == ServiceState.STATE_OUT_OF_SERVICE ||
                serviceState.state == ServiceState.STATE_POWER_OFF
            ) {
                _snapshot.value = noServiceSnapshot()
            }
            // In-service: wait for the next onSignalStrengthsChanged to update snapshot.
        }

        @SuppressLint("MissingPermission")
        private fun rebuild() {
            val signal = lastSignalStrength ?: return
            val networkType = lastDisplayInfo
                ?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.toNetworkTypeString() else null }
                ?: resolveNetworkType()
            _snapshot.value = signal.toSnapshot(
                networkType = networkType,
                carrierName = carrierName(),
                mcc = mcc(),
                mnc = mnc()
            )
        }
    }

    // ── API 26–30 path ───────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun startLegacy() {
        val listener = LegacyPhoneStateListener()
        var flags = PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or
                    PhoneStateListener.LISTEN_SERVICE_STATE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // LISTEN_DISPLAY_INFO_CHANGED was added in API 30
            flags = flags or PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED
        }
        telephonyManager.listen(listener, flags)
        legacyListener = listener
    }

    @Suppress("DEPRECATION")
    private inner class LegacyPhoneStateListener : PhoneStateListener() {

        private var lastDisplayInfo: TelephonyDisplayInfo? = null

        @RequiresApi(Build.VERSION_CODES.R)
        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
            lastDisplayInfo = telephonyDisplayInfo
        }

        @SuppressLint("MissingPermission")
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            val networkType = lastDisplayInfo
                ?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.toNetworkTypeString() else null }
                ?: resolveNetworkType()
            _snapshot.value = signalStrength.toSnapshot(
                networkType = networkType,
                carrierName = carrierName(),
                mcc = mcc(),
                mnc = mnc()
            )
        }

        override fun onServiceStateChanged(serviceState: ServiceState?) {
            if (serviceState?.state == ServiceState.STATE_OUT_OF_SERVICE ||
                serviceState?.state == ServiceState.STATE_POWER_OFF
            ) {
                _snapshot.value = noServiceSnapshot()
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Resolves network type string from [TelephonyManager.getDataNetworkType] (API 24+).
     *
     * Requires READ_PHONE_STATE. Returns "UNKNOWN" if permission is absent or the
     * operator string is empty. Uses @SuppressLint because the permission check
     * is the caller's responsibility before invoking [start].
     */
    @SuppressLint("MissingPermission")
    private fun resolveNetworkType(): String = try {
        telephonyManager.dataNetworkType.toNetworkTypeString()
    } catch (e: SecurityException) {
        Timber.w("SignalReader: READ_PHONE_STATE not granted, can't resolve network type")
        "UNKNOWN"
    }

    private fun carrierName(): String? =
        telephonyManager.networkOperatorName.takeIf { it.isNotEmpty() }

    private fun mcc(): String? =
        telephonyManager.networkOperator.take(3).takeIf { it.length == 3 }

    private fun mnc(): String? =
        telephonyManager.networkOperator.drop(3).takeIf { it.isNotEmpty() }

    private fun noServiceSnapshot() = SignalSnapshot(
        networkType  = "UNKNOWN",
        carrierName  = null,
        mcc          = null,
        mnc          = null,
        rsrp         = null,
        rsrq         = null,
        rssi         = null,
        sinr         = null,
        signalBars   = 0,
        signalTier   = "NONE",
        isNoService  = true
    )
}

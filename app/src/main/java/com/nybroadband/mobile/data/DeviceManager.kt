package com.nybroadband.mobile.data

import android.os.Build
import com.nybroadband.mobile.BuildConfig
import com.nybroadband.mobile.data.local.prefs.DevicePrefs
import com.nybroadband.mobile.data.remote.NyuBroadbandApi
import com.nybroadband.mobile.data.remote.model.RegisterDeviceRequest
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages device registration with the backend.
 *
 * Registration is a one-time operation: [ensureRegistered] contacts
 * POST /v1/devices/register the very first time, stores the returned
 * device_id in [DevicePrefs] (encrypted), and returns it immediately
 * on all subsequent calls without any network round-trip.
 *
 * The server is idempotent on device_fingerprint, so calling register
 * again after an app reinstall returns the same device_id.
 *
 * Usage: call [ensureRegistered] from [SyncWorker] and any background
 * task that needs to identify the device to the backend.
 */
@Singleton
class DeviceManager @Inject constructor(
    private val api: NyuBroadbandApi,
    private val devicePrefs: DevicePrefs
) {
    /** The stored device_id, or null if the device has never been registered. */
    val deviceId: String? get() = devicePrefs.deviceId

    /**
     * Ensures this device is registered and returns the device_id.
     * Returns null if registration fails (network error, server error).
     * Safe to call from any coroutine — returns immediately on subsequent calls.
     */
    suspend fun ensureRegistered(): String? {
        devicePrefs.deviceId?.let { return it }

        return try {
            val response = api.registerDevice(
                RegisterDeviceRequest(
                    deviceModel       = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    androidVersion    = Build.VERSION.SDK_INT.toString(),
                    appVersion        = BuildConfig.VERSION_NAME,
                    deviceFingerprint = fingerprintSha256()
                )
            )
            devicePrefs.deviceId = response.deviceId
            Timber.i("Device registered: id=${response.deviceId} created=${response.created}")
            response.deviceId
        } catch (e: Exception) {
            Timber.e(e, "Device registration failed")
            null
        }
    }

    /**
     * SHA-256 hash of Android's Build.FINGERPRINT.
     * Provides a stable, de-identified hardware fingerprint — no raw device
     * identifiers are ever sent to the backend.
     */
    private fun fingerprintSha256(): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(Build.FINGERPRINT.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

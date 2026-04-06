package com.nybroadband.mobile.data.local.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent, encrypted storage for device-level identifiers.
 *
 * Uses EncryptedSharedPreferences (AES256-GCM) so the server-assigned
 * device_id is never stored in plaintext on the filesystem.
 *
 * Lifecycle: device_id is written once after a successful POST /v1/devices/register
 * and read by every subsequent API call. [DeviceManager.ensureRegistered] is the
 * only writer; all other callers should read [deviceId] directly.
 */
@Singleton
class DevicePrefs @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** Server-assigned device ID, or null if this device has not been registered yet. */
    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    val isRegistered: Boolean get() = deviceId != null

    companion object {
        private const val FILE_NAME    = "device_secure_prefs"
        private const val KEY_DEVICE_ID = "device_id"
    }
}

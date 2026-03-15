package com.nybroadband.mobile.service.passive

import android.content.Context
import android.location.Location
import android.os.Build
import com.nybroadband.mobile.data.local.db.entity.MeasurementEntity
import com.nybroadband.mobile.service.signal.SignalSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles a [MeasurementEntity] from a [SignalSnapshot] and a [Location] fix.
 *
 * Responsibilities:
 *   - Combines telephony metrics and GPS fix into a single flat entity.
 *   - Marks [MeasurementEntity.sampleType] = "PASSIVE".
 *   - Auto-flags dead zones: if no signal is detected, sets [deadZoneType] = "AUTO".
 *   - Fills device metadata (model, API level, app version) from the application context.
 *   - Sets initial sync state: uploadStatus = "PENDING".
 *
 * Not responsible for:
 *   - Permission checks (caller's responsibility).
 *   - Persisting to Room (caller's responsibility).
 *   - Enqueuing in sync_queue (caller's responsibility).
 */
@Singleton
class SampleAssembler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** App version string, resolved once and cached. */
    private val appVersion: String by lazy {
        try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
                ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Build a [MeasurementEntity] for a passive background sample.
     *
     * @param signal    Signal snapshot from [SignalReader.snapshot].
     * @param location  GPS fix from [LocationReader.getLocation].
     * @param sessionId UUID string identifying this service start session.
     *                  All samples within one service lifecycle share the same ID,
     *                  which lets the backend de-duplicate and group sessions.
     */
    fun assemble(
        signal: SignalSnapshot,
        location: Location,
        sessionId: String
    ): MeasurementEntity {
        val now = System.currentTimeMillis()
        return MeasurementEntity(
            id = UUID.randomUUID().toString(),
            timestamp = now,

            // Location
            lat = location.latitude,
            lon = location.longitude,
            gpsAccuracyMeters = location.accuracy,

            // Network identity
            mcc = signal.mcc,
            mnc = signal.mnc,
            carrierName = signal.carrierName,
            networkType = signal.networkType,

            // Signal metrics
            rsrp = signal.rsrp,
            rsrq = signal.rsrq,
            rssi = signal.rssi,
            sinr = signal.sinr,
            signalBars = signal.signalBars,
            signalTier = signal.signalTier,

            // Speed test fields — null for passive samples (no speed test performed)
            downloadSpeedMbps = null,
            uploadSpeedMbps = null,
            latencyMs = null,
            jitterMs = null,
            bytesDownloaded = null,
            bytesUploaded = null,
            testDurationSec = null,
            testServerName = null,
            testServerLocation = null,

            // Collection context
            sampleType = "PASSIVE",
            activityMode = null,            // TODO: ActivityRecognition integration
            sessionId = sessionId,
            isNoService = signal.isNoService,
            deadZoneType = if (signal.isNoService) "AUTO" else null,
            deadZoneNote = null,

            // Device metadata
            deviceModel = Build.MODEL,
            androidVersion = Build.VERSION.SDK_INT,
            appVersion = appVersion,

            // Sync state
            uploadStatus = "PENDING",
            uploadAttempts = 0,
            uploadedAt = null
        )
    }
}

package com.nybroadband.mobile.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nybroadband.mobile.data.DeviceManager
import com.nybroadband.mobile.data.remote.NyuBroadbandApi
import com.nybroadband.mobile.data.remote.model.CreateDeadZoneRequest
import com.nybroadband.mobile.data.remote.model.CreateMeasurementRequest
import com.nybroadband.mobile.data.remote.model.MeasurementBatchRequest
import com.nybroadband.mobile.data.local.db.entity.MeasurementEntity
import com.nybroadband.mobile.data.local.db.entity.DeadZoneReportEntity
import com.nybroadband.mobile.domain.repository.DeadZoneRepository
import com.nybroadband.mobile.domain.repository.MeasurementRepository
import com.nybroadband.mobile.domain.repository.SyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that uploads pending measurements and dead zone reports
 * to the backend in batches.
 *
 * Run conditions: requires any network connection. WorkManager handles
 * scheduling; [enqueue] creates a one-time request and WorkManager will
 * retry using exponential backoff if the worker itself returns [Result.retry].
 *
 * Batch logic:
 *   1. Ensure device is registered (no-op after first run).
 *   2. Fetch up to BATCH_SIZE pending measurements → POST /v1/measurements.
 *      Per-item results from the 207 response determine markUploaded/markFailed.
 *   3. Fetch up to BATCH_SIZE pending dead zones → POST /v1/dead-zones individually.
 *   4. Prune exhausted sync_queue rows.
 *   5. Return [Result.success] always (individual failures are tracked in DB,
 *      not escalated to WorkManager backoff — WorkManager retry is reserved
 *      for device registration failures or total network outages).
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val api: NyuBroadbandApi,
    private val deviceManager: DeviceManager,
    private val measurementRepo: MeasurementRepository,
    private val deadZoneRepo: DeadZoneRepository,
    private val syncRepo: SyncRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deviceId = deviceManager.ensureRegistered()
        if (deviceId == null) {
            Timber.w("SyncWorker: device not registered, retrying later")
            return Result.retry()
        }

        uploadMeasurements(deviceId)
        uploadDeadZones(deviceId)
        syncRepo.pruneExhausted()

        return Result.success()
    }

    // ── Measurements ──────────────────────────────────────────────────────────

    private suspend fun uploadMeasurements(deviceId: String) {
        val pending = measurementRepo.getPendingForSync(BATCH_SIZE)
        if (pending.isEmpty()) return

        Timber.d("SyncWorker: uploading ${pending.size} measurements")
        try {
            val response = api.uploadMeasurements(
                MeasurementBatchRequest(pending.map { it.toRequest(deviceId) })
            )

            val uploaded = mutableListOf<String>()
            val failed   = mutableListOf<String>()

            response.results.forEachIndexed { index, result ->
                val entity = pending.getOrNull(index) ?: return@forEachIndexed
                if (result.status == "success") uploaded.add(entity.id)
                else failed.add(entity.id)
            }

            if (uploaded.isNotEmpty()) measurementRepo.markUploaded(uploaded)
            if (failed.isNotEmpty())   measurementRepo.markFailed(failed)

            Timber.d("SyncWorker: measurements uploaded=${uploaded.size} rejected=${failed.size}")
        } catch (e: Exception) {
            Timber.e(e, "SyncWorker: measurement upload failed")
            measurementRepo.markFailed(pending.map { it.id })
        }
    }

    // ── Dead Zones ────────────────────────────────────────────────────────────

    private suspend fun uploadDeadZones(deviceId: String) {
        val pending = deadZoneRepo.getPendingForSync(BATCH_SIZE)
        if (pending.isEmpty()) return

        Timber.d("SyncWorker: uploading ${pending.size} dead zones")
        pending.forEach { report ->
            try {
                val response = api.submitDeadZone(report.toRequest(deviceId))
                deadZoneRepo.markUploaded(report.id, response.remoteId)
                Timber.d("SyncWorker: dead zone uploaded remoteId=${response.remoteId}")
            } catch (e: Exception) {
                Timber.e(e, "SyncWorker: dead zone upload failed id=${report.id}")
                deadZoneRepo.markFailed(listOf(report.id))
            }
        }
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private fun MeasurementEntity.toRequest(deviceId: String) = CreateMeasurementRequest(
        deviceId          = deviceId,
        timestamp         = epochMsToIso(timestamp),
        lat               = lat,
        lon               = lon,
        gpsAccuracyMeters = gpsAccuracyMeters.toDouble().takeIf { it > 0 },
        mcc               = mcc,
        mnc               = mnc,
        carrierName       = carrierName,
        networkType       = networkType,
        rsrp              = rsrp,
        rsrq              = rsrq,
        rssi              = rssi,
        sinr              = sinr,
        signalBars        = signalBars,
        signalTier        = signalTier,
        downloadSpeedMbps = downloadSpeedMbps,
        uploadSpeedMbps   = uploadSpeedMbps,
        latencyMs         = latencyMs,
        jitterMs          = jitterMs,
        testServerName    = testServerName,
        sampleType        = sampleType,
        isNoService       = isNoService,
        gsmBer            = gsmBer,
        gsmTimingAdv      = gsmTimingAdv,
        umtsRscp          = umtsRscp,
        umtsEcNo          = umtsEcNo,
        lteCqi            = lteCqi,
        lteTimingAdv      = lteTimingAdv,
        nrCsiRsrp         = nrCsiRsrp,
        nrCsiRsrq         = nrCsiRsrq,
        nrCsiSinr         = nrCsiSinr,
        minRttUs          = minRttUs,
        meanRttUs         = meanRttUs,
        rttVarUs          = rttVarUs,
        retransmitRate    = retransmitRate,
        bbrBandwidthBps   = bbrBandwidthBps,
        bbrMinRttUs       = bbrMinRttUs,
        serverUuid        = serverUuid,
        appVersion        = appVersion
    )

    private fun DeadZoneReportEntity.toRequest(deviceId: String) = CreateDeadZoneRequest(
        deviceId          = deviceId,
        lat               = lat,
        lon               = lon,
        gpsAccuracyMeters = gpsAccuracyMeters.toDouble().takeIf { it > 0 },
        note              = note,
        photoCount        = photoUris?.split("|")?.filter { it.isNotBlank() }?.size ?: 0,
        timestamp         = epochMsToIso(timestamp)
    )

    private fun epochMsToIso(epochMs: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs))

    // ── Scheduling ────────────────────────────────────────────────────────────

    companion object {
        const val TAG = "SyncWorker"
        private const val BATCH_SIZE = 100

        /**
         * Enqueues a one-time sync run.
         * Requires any network connection; WorkManager handles scheduling and
         * retries with exponential backoff if [Result.retry] is returned.
         */
        fun enqueue(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            workManager.enqueue(request)
        }
    }
}

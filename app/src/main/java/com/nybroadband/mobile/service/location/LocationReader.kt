package com.nybroadband.mobile.service.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Suspend-based wrapper around [FusedLocationProviderClient].
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Strategy:
 *   1. Try [lastLocation] first — it is instant (no GPS activation cost).
 *      Accept it if it is less than [LOCATION_STALE_MS] (60 s) old.
 *   2. If stale or null, call [getCurrentLocation] with BALANCED_POWER accuracy
 *      (good for passive collection — avoids draining GPS while still yielding
 *      a network/cell-triangulated fix within a few seconds).
 *   3. Both attempts are capped by [timeoutMs] (default 5 s) via [withTimeoutOrNull].
 *      Returns null if the fix cannot be obtained within the deadline.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Permission:
 *   ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION is required before calling
 *   [getLocation]. This class does not perform a permission check — the caller
 *   ([PassiveCollectionService]) is responsible. A [SecurityException] is caught
 *   and logged; null is returned.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * GPS accuracy note:
 *   [Priority.PRIORITY_BALANCED_POWER_ACCURACY] yields cell/WiFi accuracy
 *   (~50–100 m). This is sufficient for broadband coverage mapping at the
 *   census-tract level. [PassiveCollectionService] stores [Location.accuracy]
 *   in [MeasurementEntity.gpsAccuracyMeters]; the map renderer filters points
 *   with accuracy > 50 m from display.
 */
@Singleton
class LocationReader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    /**
     * Returns the best available location fix, or null if none is obtainable
     * within [timeoutMs] milliseconds.
     */
    @SuppressLint("MissingPermission")
    suspend fun getLocation(timeoutMs: Long = 5_000L): Location? {
        return try {
            withTimeoutOrNull(timeoutMs) {
                val last = getLastLocation()
                if (last != null && !last.isStale()) {
                    Timber.v("LocationReader: using cached lastLocation (age ${System.currentTimeMillis() - last.time} ms)")
                    last
                } else {
                    Timber.v("LocationReader: lastLocation stale/null, requesting fresh fix")
                    getFreshLocation()
                }
            }
        } catch (e: SecurityException) {
            Timber.w("LocationReader: location permission missing — ${e.message}")
            null
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun getLastLocation(): Location? =
        suspendCancellableCoroutine { cont ->
            fusedClient.lastLocation
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { e ->
                    Timber.w("LocationReader: lastLocation failed — ${e.message}")
                    cont.resume(null)
                }
        }

    private suspend fun getFreshLocation(): Location? =
        suspendCancellableCoroutine { cont ->
            fusedClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                null    // no CancellationSignal — coroutine cancellation handles timeout
            )
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { e ->
                    Timber.w("LocationReader: getCurrentLocation failed — ${e.message}")
                    cont.resume(null)
                }
        }

    private fun Location.isStale(): Boolean =
        System.currentTimeMillis() - time > LOCATION_STALE_MS

    companion object {
        /** Accept last known location if it is within 60 seconds. */
        private const val LOCATION_STALE_MS = 60_000L
    }
}

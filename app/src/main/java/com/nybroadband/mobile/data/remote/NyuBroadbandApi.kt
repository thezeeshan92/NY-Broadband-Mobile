package com.nybroadband.mobile.data.remote

import com.nybroadband.mobile.data.remote.model.CoverageHexResponse
import com.nybroadband.mobile.data.remote.model.CreateDeadZoneRequest
import com.nybroadband.mobile.data.remote.model.CreateDeadZoneResponse
import com.nybroadband.mobile.data.remote.model.MeasurementBatchRequest
import com.nybroadband.mobile.data.remote.model.MeasurementBatchResponse
import com.nybroadband.mobile.data.remote.model.RegisterDeviceRequest
import com.nybroadband.mobile.data.remote.model.RegisterDeviceResponse
import com.nybroadband.mobile.data.remote.model.ResultsResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Retrofit interface for the NYU Broadband backend API.
 *
 * Base URL is injected from BuildConfig.API_BASE_URL (ends with /v1/).
 * All paths below are relative to that base.
 *
 * Public endpoints (no auth required):
 *   - devices/register
 *   - measurements
 *   - dead-zones
 *   - coverage/hex
 *   - results
 */
interface NyuBroadbandApi {

    // ── Device ────────────────────────────────────────────────────────────────

    /**
     * Register or re-register this device. Idempotent on device_fingerprint.
     * Returns 201 on first creation, 200 on subsequent calls.
     */
    @POST("devices/register")
    suspend fun registerDevice(@Body request: RegisterDeviceRequest): RegisterDeviceResponse

    // ── Measurements ──────────────────────────────────────────────────────────

    /**
     * Batch ingest 1–100 measurements.
     * Returns HTTP 207 with per-item success/rejected status.
     */
    @POST("measurements")
    suspend fun uploadMeasurements(@Body request: MeasurementBatchRequest): MeasurementBatchResponse

    // ── Dead Zones ────────────────────────────────────────────────────────────

    /**
     * Submit a user-reported dead zone.
     * Returns a remoteId and presigned S3 PUT URLs for photo uploads.
     */
    @POST("dead-zones")
    suspend fun submitDeadZone(@Body request: CreateDeadZoneRequest): CreateDeadZoneResponse

    // ── Coverage ──────────────────────────────────────────────────────────────

    /**
     * Aggregated H3 hex coverage cells (public, cached 15 min on server).
     * @param resolution H3 resolution level (7 or 9)
     * @param bbox       optional bounding box: "minLon,minLat,maxLon,maxLat"
     * @param carrier    optional carrier name filter
     * @param networkType optional network type filter (2G/3G/4G/5G_NSA/5G_SA)
     */
    @GET("coverage/hex")
    suspend fun getCoverageHex(
        @Query("resolution") resolution: Int = 7,
        @Query("bbox")        bbox: String? = null,
        @Query("carrier")     carrier: String? = null,
        @Query("networkType") networkType: String? = null
    ): CoverageHexResponse

    // ── Results ───────────────────────────────────────────────────────────────

    /**
     * Paginated measurement history for a given device.
     */
    @GET("results")
    suspend fun getResults(
        @Query("deviceId")    deviceId: String,
        @Query("page")        page: Int = 1,
        @Query("limit")       limit: Int = 50,
        @Query("from")        from: String? = null,
        @Query("to")          to: String? = null,
        @Query("sampleType")  sampleType: String? = null,
        @Query("networkType") networkType: String? = null
    ): ResultsResponse
}

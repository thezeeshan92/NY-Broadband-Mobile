package com.nybroadband.mobile.presentation.cellular

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches public IP transit / ASN labels from ipapi.co (HTTPS) for the Connection section.
 * Values are best-effort; failures leave fields empty.
 */
@Singleton
class CellularIpInfoFetcher @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {

    fun fetch(): IpTransitAsn? {
        val client = okHttpClient.newBuilder()
            .readTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder()
            .url("https://ipapi.co/json/")
            .header("User-Agent", "NYBroadbandMobile/1.0")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                if (json.optBoolean("error", false)) return null
                val org = jsonString(json, "org")
                val asn = jsonString(json, "asn")
                if (org == null && asn == null) return null
                IpTransitAsn(ipTransitLabel = org, asnLabel = asn)
            }
        } catch (e: Exception) {
            Timber.d("CellularIpInfoFetcher: ${e.message}")
            null
        }
    }

    private fun jsonString(json: JSONObject, key: String): String? {
        val v = json.opt(key) ?: return null
        if (v === JSONObject.NULL) return null
        return when (v) {
            is String -> v.trim().takeIf { it.isNotEmpty() }
            is Number -> v.toString()
            else -> v.toString().trim().takeIf { it.isNotEmpty() }
        }
    }
}

data class IpTransitAsn(
    val ipTransitLabel: String?,
    val asnLabel: String?,
)

package com.nybroadband.mobile.presentation.home

import android.os.Parcelable
import com.mapbox.geojson.Feature
import kotlinx.parcelize.Parcelize

/**
 * UI model for the CoverageMap-style grid cell bottom sheet (normal + expanded).
 * Populated from GeoJSON feature properties ([attachCellDetailProperties]) or API later.
 */
@Parcelize
data class GridCellDetail(
    val tapLat: Double,
    val tapLon: Double,
    val placeTitle: String,
    val dateLabel: String,
    val carrierPill: String,
    val fastestDownloadMbps: Double,
    val fastestUploadMbps: Double,
    val avgDownloadMbps: Double,
    val avgUploadMbps: Double,
    val slowDownloadMbps: Double,
    val slowUploadMbps: Double,
    val latencyFastMs: Int,
    val latencyAvgMs: Int,
    val latencySlowMs: Int,
    val chartAttMbps: Int,
    val chartTmoMbps: Int,
    val chartVzwMbps: Int,
) : Parcelable {
    companion object {
        fun fromFeature(
            feature: Feature,
            tapLat: Double = 0.0,
            tapLon: Double = 0.0,
        ): GridCellDetail {
            fun num(name: String, default: Double = 0.0): Double =
                when (val v = feature.getNumberProperty(name)) {
                    null -> default
                    else -> v.toDouble()
                }

            fun int(name: String, default: Int = 0): Int =
                when (val v = feature.getNumberProperty(name)) {
                    null -> default
                    else -> v.toInt()
                }

            return GridCellDetail(
                tapLat = tapLat,
                tapLon = tapLon,
                placeTitle = feature.getStringProperty("placeName") ?: "Area",
                dateLabel = feature.getStringProperty("dateLabel") ?: "",
                carrierPill = feature.getStringProperty("carrierPill") ?: "All",
                fastestDownloadMbps = num("fastestDl", 1.0),
                fastestUploadMbps = num("fastestUl", 1.0),
                avgDownloadMbps = num("avgDl", 0.0),
                avgUploadMbps = num("avgUl", 0.0),
                slowDownloadMbps = num("slowDl", 0.0),
                slowUploadMbps = num("slowUl", 0.0),
                latencyFastMs = int("latFast", 14),
                latencyAvgMs = int("latAvg", 150),
                latencySlowMs = int("latSlow", 1550),
                chartAttMbps = int("chartAtt", 120),
                chartTmoMbps = int("chartTmo", 200),
                chartVzwMbps = int("chartVzw", 45),
            )
        }
    }
}

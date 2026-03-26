package com.nybroadband.mobile.presentation.home

/**
 * In-memory map filter state (mock + future API). Synced from toolbar chips and filter sheet.
 */
data class MapFilterState(
    val carrier: MapCarrierFilter = MapCarrierFilter.ALL,
    val network: MapNetworkFilter = MapNetworkFilter.LTE_5G,
    val metric: MapMetricFilter = MapMetricFilter.AVERAGE,
    val colorMode: MapColorMode = MapColorMode.CARRIER,
    /** Download vs upload vs latency — reserved for API; mock uses download/upload ranges only. */
    val mapMetricKind: MapSheetMetricKind = MapSheetMetricKind.DOWNLOAD,
    val country: MapCountryFilter = MapCountryFilter.US,
    val myDataOnly: Boolean = false,
    val showLegend: Boolean = true,
    val showMapValues: Boolean = true,
    val minDownloadMbps: Int = 0,
    val maxDownloadMbps: Int = 5000,
    val minUploadMbps: Int = 0,
    val maxUploadMbps: Int = 1000,
)

enum class MapColorMode {
    CARRIER,
    HEATMAP,
    BEST_CARRIER,
}

enum class MapSheetMetricKind {
    DOWNLOAD,
    UPLOAD,
    LATENCY,
}

enum class MapCarrierFilter {
    ALL,
    ATT,
    TMO,
    VZW,
}

enum class MapNetworkFilter {
    ALL,
    LTE_5G,
    LTE_ONLY,
    NR_ONLY,
}

enum class MapMetricFilter {
    AVERAGE,
    MEDIAN,
    BEST,
}

enum class MapCountryFilter {
    US,
    PK,
}

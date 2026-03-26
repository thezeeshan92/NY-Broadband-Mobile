package com.nybroadband.mobile.presentation.home

import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.nybroadband.mobile.data.local.db.dao.MapPointProjection
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.floor
import kotlin.random.Random

/**
 * Deterministic mock measurements + hex coverage for map QA until APIs are wired.
 */
object MapMockData {

    private const val SAMPLE_TYPE = "PASSIVE"
    private val tiers = listOf("GOOD", "FAIR", "WEAK", "POOR", "NONE")
    private val carriers = listOf("ATT", "TMO", "VZW")
    private val networks = listOf("LTE", "LTE_5G", "NR")

    private val mockPlaceNames = listOf(
        "Snow, OK",
        "Lake Creek, TX",
        "Alester, OK",
        "Tulsa, OK",
        "Austin, TX",
        "Denver, CO",
        "Chicago, IL",
        "Buffalo, NY",
    )

    private val dateLabel: String by lazy {
        LocalDate.now().format(DateTimeFormatter.ofPattern("M/d/yyyy"))
    }

    /**
     * Per-cell metadata for the CoverageMap-style bottom sheet (tapped polygon features).
     */
    fun attachCellDetailProperties(f: Feature, idx: Int, score: Double) {
        val seed = ((idx * 1_103_515_245 + 12_345) and 0x7fff_ffff)
        f.addStringProperty("placeName", mockPlaceNames[idx % mockPlaceNames.size])
        f.addStringProperty("dateLabel", dateLabel)
        f.addStringProperty("carrierPill", "All")

        val fastestDl = 1.0 + score * 1_000.0 + (seed % 200) / 100.0
        val fastestUl = 1.0 + score * 130.0 + (seed % 100) / 50.0
        val avgDl = fastestDl * 0.14 + (seed % 50)
        val avgUl = fastestUl * 0.13 + (seed % 10) / 10.0
        val slowDl = ((seed % 5) * 0.0).coerceAtMost(1.0)
        val slowUl = ((seed % 3) * 0.0).coerceAtMost(1.0)

        f.addNumberProperty("fastestDl", fastestDl)
        f.addNumberProperty("fastestUl", fastestUl)
        f.addNumberProperty("avgDl", avgDl)
        f.addNumberProperty("avgUl", avgUl)
        f.addNumberProperty("slowDl", slowDl)
        f.addNumberProperty("slowUl", slowUl)

        f.addNumberProperty("latFast", (10 + seed % 20).toDouble())
        f.addNumberProperty("latAvg", (100 + seed % 100).toDouble())
        f.addNumberProperty("latSlow", (500 + seed % 2_000).toDouble())

        f.addNumberProperty("chartAtt", (40 + seed % 180).toDouble())
        f.addNumberProperty("chartTmo", (80 + seed % 200).toDouble())
        f.addNumberProperty("chartVzw", (10 + seed % 80).toDouble())
    }

    /** Mock rows used for carrier / network / country / speed filtering. */
    data class MockMapPoint(
        val projection: MapPointProjection,
        val carrier: String,
        val network: String,
        val country: MapCountryFilter,
        val downloadMbps: Int,
        val uploadMbps: Int,
    )

    private val mockRows: List<MockMapPoint> by lazy { generateRows() }

    fun allMockRows(): List<MockMapPoint> = mockRows

    /**
     * Applies [state] to real Room points + mock rows. Real points pass through when included;
     * mock rows are filtered by carrier, network, country, and speed sliders.
     */
    fun combineAndFilter(
        realPoints: List<MapPointProjection>,
        state: MapFilterState,
    ): List<MapPointProjection> {
        if (!MapMockConfig.ENABLED) return realPoints

        if (state.myDataOnly) return realPoints

        val mockFiltered = mockRows
            .asSequence()
            .filter { it.country == state.country }
            .filter { matchesCarrier(it.carrier, state.carrier) }
            .filter { matchesNetwork(it.network, state.network) }
            .filter {
                it.downloadMbps in state.minDownloadMbps..state.maxDownloadMbps &&
                    it.uploadMbps in state.minUploadMbps..state.maxUploadMbps
            }
            .map { it.projection }
            .toList()

        val byId = LinkedHashMap<String, MapPointProjection>()
        for (p in realPoints) byId[p.id] = p
        for (p in mockFiltered) byId[p.id] = p
        return byId.values.toList()
    }

    private fun matchesCarrier(carrier: String, filter: MapCarrierFilter): Boolean {
        return when (filter) {
            MapCarrierFilter.ALL -> true
            MapCarrierFilter.ATT -> carrier == "ATT"
            MapCarrierFilter.TMO -> carrier == "TMO"
            MapCarrierFilter.VZW -> carrier == "VZW"
        }
    }

    private fun matchesNetwork(network: String, filter: MapNetworkFilter): Boolean {
        return when (filter) {
            MapNetworkFilter.ALL -> true
            MapNetworkFilter.LTE_5G -> network == "LTE" || network == "LTE_5G" || network == "NR"
            MapNetworkFilter.LTE_ONLY -> network == "LTE" || network == "LTE_5G"
            MapNetworkFilter.NR_ONLY -> network == "NR"
        }
    }

    private fun generateRows(): List<MockMapPoint> {
        val rnd = Random(42)
        val out = ArrayList<MockMapPoint>(320)

        // New York State–ish bbox
        var id = 0
        repeat(90) {
            val lat = 40.5 + rnd.nextDouble() * 4.2
            val lon = -79.8 + rnd.nextDouble() * 5.5
            out += mockPoint(++id, lat, lon, MapCountryFilter.US, rnd)
        }

        // Pakistan bbox (Islamabad / Lahore corridor — illustrative)
        repeat(25) {
            val lat = 30.2 + rnd.nextDouble() * 3.5
            val lon = 67.5 + rnd.nextDouble() * 4.0
            out += mockPoint(++id, lat, lon, MapCountryFilter.PK, rnd)
        }

        // US Central / Eastern — same rough viewport as [mockUsCentralGridHeatmap] and default mock camera
        // so Signal Strength (clustered dots) is visible without panning.
        repeat(140) {
            val lat = 33.6 + rnd.nextDouble() * 14.0
            val lon = -101.0 + rnd.nextDouble() * 23.0
            out += mockPoint(++id, lat, lon, MapCountryFilter.US, rnd)
        }

        return out
    }

    private fun mockPoint(
        id: Int,
        lat: Double,
        lon: Double,
        country: MapCountryFilter,
        rnd: Random,
    ): MockMapPoint {
        val tier = tiers[rnd.nextInt(tiers.size)]
        val carrier = carriers[rnd.nextInt(carriers.size)]
        val network = networks[rnd.nextInt(networks.size)]
        val dl = rnd.nextInt(5, 450)
        val ul = rnd.nextInt(2, 120)
        return MockMapPoint(
            projection = MapPointProjection(
                id = "mock-$id",
                lat = lat,
                lon = lon,
                signalTier = tier,
                sampleType = SAMPLE_TYPE,
                timestamp = 1_700_000_000_000L + id,
            ),
            carrier = carrier,
            network = network,
            country = country,
            downloadMbps = dl,
            uploadMbps = ul,
        )
    }

    /**
     * Dense square grid over the central / eastern US (CoverageMap-style heatmap at low zoom).
     * Purple → green ramp via [signalScore] on each cell.
     */
    fun mockUsCentralGridHeatmap(): FeatureCollection {
        val features = ArrayList<Feature>()
        val cell = 0.2
        var lon = -104.5
        var idx = 0
        while (lon < -77.5) {
            var lat = 33.5
            while (lat < 48.5) {
                val i = idx++
                val score = pseudoScore(lon, lat, i)
                val ring = cellRing(lon, lat, cell * 0.92, cell * 0.88)
                features.add(
                    Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply {
                        addNumberProperty("signalScore", score)
                        attachCellDetailProperties(this, i, score)
                    }
                )
                lat += cell * 0.88
            }
            lon += cell * 0.92
        }
        return FeatureCollection.fromFeatures(features)
    }

    /**
     * Grid of polygons with [signalScore] for Coverage Report mode (mock backend).
     * Includes a dense NY region plus a coarser US-central grid so hexes are visible at the default mock camera.
     */
    fun mockCoverageHexCollection(): FeatureCollection {
        val features = ArrayList<Feature>()
        var idx = 0
        // Upstate / NE — tighter cells (regional QA)
        idx = appendHexRegion(
            features,
            lonMin = -79.4,
            lonMax = -73.8,
            latMin = 41.2,
            latMax = 44.6,
            cellW = 0.55,
            cellH = 0.42,
            startIdx = idx,
        )
        // US Central / Eastern — matches heatmap bbox; larger cells to keep feature count reasonable
        appendHexRegion(
            features,
            lonMin = -101.0,
            lonMax = -78.0,
            latMin = 33.5,
            latMax = 48.0,
            cellW = 1.05,
            cellH = 0.82,
            startIdx = idx,
        )
        return FeatureCollection.fromFeatures(features)
    }

    private fun appendHexRegion(
        features: ArrayList<Feature>,
        lonMin: Double,
        lonMax: Double,
        latMin: Double,
        latMax: Double,
        cellW: Double,
        cellH: Double,
        startIdx: Int,
    ): Int {
        var idx = startIdx
        var lon = lonMin
        while (lon < lonMax) {
            var lat = latMin
            while (lat < latMax) {
                val i = idx++
                val score = pseudoScore(lon, lat, i)
                val ring = cellRing(lon, lat, cellW, cellH)
                features.add(
                    Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply {
                        addNumberProperty("signalScore", score)
                        attachCellDetailProperties(this, i, score)
                    }
                )
                lat += cellH * 0.95
            }
            lon += cellW * 0.9
        }
        return idx
    }

    private fun pseudoScore(lon: Double, lat: Double, i: Int): Double {
        val n = cos(lat * 0.17) * 0.25 + (i % 7) * 0.08 + lon * 0.01
        return (0.15 + (n - floor(n))).coerceIn(0.15, 0.98)
    }

    private fun cellRing(lon: Double, lat: Double, w: Double, h: Double): List<Point> {
        val l1 = lon
        val l2 = lon + w
        val a1 = lat
        val a2 = lat + h
        return listOf(
            Point.fromLngLat(l1, a1),
            Point.fromLngLat(l2, a1),
            Point.fromLngLat(l2, a2),
            Point.fromLngLat(l1, a2),
            Point.fromLngLat(l1, a1),
        )
    }
}

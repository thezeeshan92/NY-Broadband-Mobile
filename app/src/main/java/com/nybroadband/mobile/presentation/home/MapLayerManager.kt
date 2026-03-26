package com.nybroadband.mobile.presentation.home

import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.maps.QueriedRenderedFeature
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.FillLayer
import com.mapbox.maps.extension.style.layers.generated.LineLayer
import com.mapbox.maps.extension.style.layers.generated.circleLayer
import com.mapbox.maps.extension.style.layers.generated.fillLayer
import com.mapbox.maps.extension.style.layers.generated.heatmapLayer
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.generated.symbolLayer
import com.mapbox.maps.extension.style.layers.properties.generated.Visibility
import com.mapbox.maps.extension.style.layers.getLayer
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.nybroadband.mobile.data.local.db.dao.MapPointProjection

/**
 * MapLayerManager — single source of truth for all Mapbox sources and layers.
 *
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  LAYER STACK  (Mapbox renders bottom → top)                             │
 * │                                                                          │
 * │  SOURCE_BACKEND         GeoJsonSource or VectorSource (future)          │
 * │    LAYER_HEX_FILL       FillLayer   — crowd-sourced hex polygons        │
 * │    LAYER_HEX_OUTLINE    LineLayer   — hex cell borders                  │
 * │                                                                          │
 * │  SOURCE_LOCAL           GeoJsonSource — clustered Room data             │
 * │    LAYER_HEATMAP        HeatmapLayer — density view      (post-MVP)     │
 * │    LAYER_CLUSTER_BG     CircleLayer  — grouped cluster bubbles          │
 * │    LAYER_CLUSTER_COUNT  SymbolLayer  — count label on clusters          │
 * │    LAYER_DOTS           CircleLayer  — individual point dots            │
 * └──────────────────────────────────────────────────────────────────────────┘
 *
 * ── LOCAL vs BACKEND DATA ────────────────────────────────────────────────────
 *
 * LOCAL (SOURCE_LOCAL):
 *   • Room → MapPointProjection → GeoJSON Feature properties
 *   • Always present; the user's own measurements collected on this device
 *   • Clustered so dense areas don't visually overwhelm the map
 *   • Sits ON TOP of backend layers — the user's own data is always legible
 *
 * BACKEND (SOURCE_BACKEND):
 *   • Pre-aggregated crowd-sourced data from the NY Broadband API
 *   • Represented as hex-bin polygons (H3 grid or similar)
 *   • Each polygon carries a "signalScore" property (0.0–1.0) from the backend
 *   • Layers are added invisibly at startup; made visible by [connectBackendSource]
 *   • Two integration options — see [connectBackendSource] for details
 *
 * ── HEX-BIN LAYER ARCHITECTURE (post-MVP) ───────────────────────────────────
 *
 * When the backend is ready, hex polygons can be delivered two ways:
 *
 * Option A — GeoJSON URL (simpler, good for < 50k polygons):
 *   Backend endpoint returns a FeatureCollection of Polygon features.
 *   Client: style.getSourceAs<GeoJsonSource>(SOURCE_BACKEND)?.url(apiUrl)
 *   Pros: dead simple, no Mapbox account dependency.
 *   Cons: entire FeatureCollection downloaded at once; no zoom-level tiling.
 *
 * Option B — Mapbox Vector Tiles (preferred at scale):
 *   Backend runs an MVT server (PostGIS + Martin, or Mapbox Tilesets API).
 *   Client: replace SOURCE_BACKEND at loadStyle() time with a VectorSource.
 *   The fill/line layers reference a "layer-name" from the tileset.
 *   Pros: streaming by zoom/viewport, handles millions of polygons.
 *   Cons: requires Mapbox Studio tileset or self-hosted tile server.
 *
 *   Example (add to loadStyle() before MapLayerManager is constructed):
 *   ```
 *   style.addSource(vectorSource(SOURCE_BACKEND) {
 *       tiles(listOf("https://tiles.nybroadband.ny.gov/{z}/{x}/{y}.mvt"))
 *       minzoom(4)
 *       maxzoom(14)
 *   })
 *   ```
 *   Then in addHexLayers(), use sourceLayer("hex_coverage") on fill/line layers.
 *
 * ── LIFECYCLE NOTE ───────────────────────────────────────────────────────────
 *
 * Create one instance per Style load (inside loadStyle() callback).
 * The Style object is invalidated on process death / style reload.
 * HomeMapFragment stores the instance in a nullable var and nulls it in
 * onDestroyView() to prevent dangling references to a dead Style.
 */
enum class MapVisualizationMode {
    /** Density-weighted heatmap (speed-style view). */
    SPEED_HEATMAP,

    /** Clustered dots — default signal-strength style. */
    SIGNAL_DOTS,

    /** Backend hex polygons — coverage report style. */
    COVERAGE_HEX
}

class MapLayerManager(private val style: Style) {

    private var visualizationMode: MapVisualizationMode = MapVisualizationMode.SIGNAL_DOTS
    private var backendHexConnected: Boolean = false
    private var mockHeatmapGridLoaded: Boolean = false

    // ── Source / Layer ID constants ───────────────────────────────────────────

    companion object {
        // Local data (Room → GeoJSON)
        const val SOURCE_LOCAL        = "source_measurements_local"
        const val LAYER_HEATMAP       = "layer_heatmap_local"        // post-MVP
        const val LAYER_CLUSTER_BG    = "layer_cluster_circle"
        const val LAYER_CLUSTER_COUNT = "layer_cluster_count"
        const val LAYER_DOTS          = "layer_measurements"

        // Backend aggregated data (future)
        const val SOURCE_BACKEND      = "source_hex_backend"
        const val LAYER_HEX_FILL      = "layer_hex_fill_backend"
        const val LAYER_HEX_OUTLINE   = "layer_hex_outline_backend"

        /** CoverageMap-style square grid (mock QA) — sits above heatmap, below clusters. */
        const val SOURCE_MOCK_GRID    = "source_mock_heatmap_grid"
        const val LAYER_MOCK_GRID_FILL = "layer_mock_heatmap_grid_fill"

        // Clustering parameters
        private const val CLUSTER_MAX_ZOOM  = 13   // above this zoom, show individual dots
        private const val CLUSTER_RADIUS_PX = 50   // merge points within this pixel radius
    }

    // ── Initialise — order matters (Mapbox renders layers in insertion order) ──

    init {
        addBackendSource()      // backend layers sit below local layers
        addLocalSource()        // clustered GeoJSON source
        addHeatmapLayer()       // under clusters/dots; toggled via [setVisualizationMode]
        addMockHeatmapGridLayerStub()
        addClusterLayers()
        addDotLayer()
        setVisualizationMode(MapVisualizationMode.SIGNAL_DOTS)
    }

    // ── 1. Backend source + hex layers (invisible stubs) ─────────────────────

    /**
     * Registers the backend source and hex layers at startup.
     * Layers are invisible (opacity = 0) until [connectBackendSource] is called.
     *
     * The stub source uses an empty FeatureCollection so the map loads cleanly.
     * Replace with a VectorSource when migrating to Option B (see class KDoc).
     */
    private fun addBackendSource() {
        style.addSource(
            geoJsonSource(SOURCE_BACKEND) {
                featureCollection(FeatureCollection.fromFeatures(emptyList()))
            }
        )

        // Color each hex cell by the "signalScore" property (0.0 = bad, 1.0 = good).
        // Interpolate across the tier palette so colors stay consistent with the dots.
        val hexFillColor = Expression.fromRaw(
            """
            ["interpolate", ["linear"], ["coalesce", ["get", "signalScore"], 0],
                0.00, "rgba(220,38,38,1)",
                0.25, "rgba(234,88,12,1)",
                0.50, "rgba(217,119,6,1)",
                0.75, "rgba(22,163,74,1)",
                1.00, "rgba(21,128,61,1)"
            ]
            """.trimIndent()
        )

        style.addLayer(
            fillLayer(LAYER_HEX_FILL, SOURCE_BACKEND) {
                fillColor(hexFillColor)
                fillOpacity(0.0)        // invisible; set to ~0.5 when backend connects
                fillAntialias(true)
            }
        )

        style.addLayer(
            lineLayer(LAYER_HEX_OUTLINE, SOURCE_BACKEND) {
                lineColor("#FFFFFF")
                lineWidth(0.5)
                lineOpacity(0.0)        // invisible; set to ~0.4 when backend connects
            }
        )
    }

    // ── 2. Local clustered GeoJSON source ─────────────────────────────────────

    /**
     * Creates a GeoJSON source for Room data with clustering enabled.
     *
     * Clustering behaviour:
     *   • Points within [CLUSTER_RADIUS_PX] pixels at the current zoom are merged
     *     into a single cluster Feature with a "point_count" property.
     *   • Clustering stops above zoom [CLUSTER_MAX_ZOOM] — individual dots appear
     *     at street level for precise inspection.
     *
     * Feature properties set per point (used by filter expressions in layers):
     *   "signalTier"  — GOOD / FAIR / WEAK / POOR / NONE  (drives dot color)
     *   "sampleType"  — PASSIVE / ACTIVE_MANUAL / ACTIVE_RECURRING (future filter)
     *   "id"          — measurement UUID (for tap-to-detail navigation)
     */
    private fun addLocalSource() {
        style.addSource(
            geoJsonSource(SOURCE_LOCAL) {
                featureCollection(FeatureCollection.fromFeatures(emptyList()))
                cluster(true)
                clusterMaxZoom(CLUSTER_MAX_ZOOM.toLong())
                clusterRadius(CLUSTER_RADIUS_PX.toLong())
            }
        )
    }

    // ── 3. Cluster layers ─────────────────────────────────────────────────────

    private fun addClusterLayers() {
        val isCluster    = Expression.has("point_count")

        // Cluster background circle — color steps by count, larger when more points
        style.addLayer(
            circleLayer(LAYER_CLUSTER_BG, SOURCE_LOCAL) {
                filter(isCluster)
                circleColor(Expression.fromRaw(
                    """
                    ["step", ["get", "point_count"],
                        "rgba(22,163,74,0.90)",    10,
                        "rgba(217,119,6,0.90)",    50,
                        "rgba(220,38,38,0.90)"
                    ]
                    """.trimIndent()
                ))
                circleRadius(Expression.fromRaw(
                    """
                    ["step", ["get", "point_count"],
                        18,    10,
                        24,    50,
                        32
                    ]
                    """.trimIndent()
                ))
                circleStrokeColor("#FFFFFF")
                circleStrokeWidth(2.5)
            }
        )

        // Count label — always white, bold, centred on the cluster circle
        style.addLayer(
            symbolLayer(LAYER_CLUSTER_COUNT, SOURCE_LOCAL) {
                filter(isCluster)
                textField(Expression.toString(Expression.get("point_count")))
                textFont(listOf("Open Sans Bold", "Arial Unicode MS Bold"))
                textSize(13.0)
                textColor("#FFFFFF")
                textIgnorePlacement(true)
                textAllowOverlap(true)
            }
        )
    }

    // ── 4. Individual dot layer ───────────────────────────────────────────────

    private fun addDotLayer() {
        val isNotCluster = Expression.not(Expression.has("point_count"))

        // Tier → circle color (no raw dBm exposed on the map, per UX policy)
        val tierColor = Expression.fromRaw(
            """
            ["match", ["get", "signalTier"],
                "GOOD", "rgba(22,163,74,1)",
                "FAIR", "rgba(217,119,6,1)",
                "WEAK", "rgba(234,88,12,1)",
                "POOR", "rgba(220,38,38,1)",
                "rgba(107,114,128,1)"
            ]
            """.trimIndent()
        )

        // Include low zoom stops so dots stay visible at country scale (clusters dominate; dots at high zoom).
        val zoomRadius = Expression.fromRaw(
            """
            ["interpolate", ["linear"], ["zoom"],
                2,  5,
                4,  6,
                6,  8,
                10, 10,
                14, 12,
                18, 14
            ]
            """.trimIndent()
        )

        style.addLayer(
            circleLayer(LAYER_DOTS, SOURCE_LOCAL) {
                filter(isNotCluster)
                circleColor(tierColor)
                circleRadius(zoomRadius)
                circleStrokeWidth(1.5)
                circleStrokeColor("#FFFFFF")
                circleOpacity(0.90)
            }
        )
    }

    // ── 5. Heatmap layer (under clusters/dots; shown in SPEED_HEATMAP mode) ───

    private fun addHeatmapLayer() {
        style.addLayer(
            heatmapLayer(LAYER_HEATMAP, SOURCE_LOCAL) {
                heatmapWeight(
                    Expression.fromRaw(
                        """
                        ["match", ["get", "signalTier"],
                            "GOOD", 1.0,
                            "FAIR", 0.7,
                            "WEAK", 0.4,
                            "POOR", 0.2,
                            0.1
                        ]
                        """.trimIndent()
                    )
                )
                heatmapIntensity(
                    Expression.fromRaw(
                        """["interpolate", ["linear"], ["zoom"], 6, 0.6, 12, 1.4]"""
                    )
                )
                heatmapRadius(
                    Expression.fromRaw(
                        """["interpolate", ["linear"], ["zoom"], 4, 28, 6, 48, 10, 55, 14, 40]"""
                    )
                )
                heatmapOpacity(
                    Expression.fromRaw(
                        """["interpolate", ["linear"], ["zoom"], 4, 0.85, 10, 0.88, 14, 0.82]"""
                    )
                )
            }
        )
    }

    /**
     * Square-cell grid layer (initially empty). [loadMockHeatmapGrid] fills GeoJSON for QA.
     * Rendered between point-heatmap and clusters so it is visible at country zoom.
     */
    private fun addMockHeatmapGridLayerStub() {
        style.addSource(
            geoJsonSource(SOURCE_MOCK_GRID) {
                featureCollection(FeatureCollection.fromFeatures(emptyList()))
            }
        )
        val gridFillColor = Expression.fromRaw(
            """
            ["interpolate", ["linear"], ["coalesce", ["get", "signalScore"], 0],
                0.00, "rgba(76,29,149,0.92)",
                0.35, "rgba(147,51,234,0.88)",
                0.55, "rgba(168,85,247,0.82)",
                0.75, "rgba(34,197,94,0.78)",
                1.00, "rgba(22,163,74,0.82)"
            ]
            """.trimIndent()
        )
        style.addLayer(
            fillLayer(LAYER_MOCK_GRID_FILL, SOURCE_MOCK_GRID) {
                fillColor(gridFillColor)
                fillOpacity(0.0)
                fillAntialias(true)
            }
        )
    }

    /** Loads square-grid coverage (CoverageMap-style) for mock heatmap mode. */
    fun loadMockHeatmapGrid(collection: FeatureCollection) {
        style.getSourceAs<GeoJsonSource>(SOURCE_MOCK_GRID)?.featureCollection(collection)
        val feats = collection.features()
        mockHeatmapGridLoaded = feats != null && feats.isNotEmpty()
        if (visualizationMode == MapVisualizationMode.SPEED_HEATMAP) {
            applyMockHeatmapGridVisibilityForCurrentMode()
        }
    }

    private fun applyMockHeatmapGridVisibilityForCurrentMode() {
        val grid = style.getLayer(LAYER_MOCK_GRID_FILL) as? FillLayer ?: return
        if (visualizationMode != MapVisualizationMode.SPEED_HEATMAP) {
            grid.fillOpacity(0.0)
            return
        }
        if (mockHeatmapGridLoaded) {
            grid.fillOpacity(0.52)
            setLayerVisibility(LAYER_HEATMAP, false)
        } else {
            grid.fillOpacity(0.0)
            setLayerVisibility(LAYER_HEATMAP, true)
        }
    }

    private fun setLayerVisibility(layerId: String, visible: Boolean) {
        style.getLayer(layerId)?.visibility(
            if (visible) Visibility.VISIBLE else Visibility.NONE
        )
    }

    /**
     * Switches between CoverageMap-style views: speed heatmap, signal dots, or hex coverage.
     */
    fun setVisualizationMode(mode: MapVisualizationMode) {
        visualizationMode = mode
        when (mode) {
            MapVisualizationMode.SPEED_HEATMAP -> {
                setLayerVisibility(LAYER_CLUSTER_BG, visible = false)
                setLayerVisibility(LAYER_CLUSTER_COUNT, visible = false)
                setLayerVisibility(LAYER_DOTS, visible = false)
                applyHexOpacity(0.0)
                applyMockHeatmapGridVisibilityForCurrentMode()
            }
            MapVisualizationMode.SIGNAL_DOTS -> {
                (style.getLayer(LAYER_MOCK_GRID_FILL) as? FillLayer)?.fillOpacity(0.0)
                setLayerVisibility(LAYER_HEATMAP, visible = false)
                setLayerVisibility(LAYER_CLUSTER_BG, visible = true)
                setLayerVisibility(LAYER_CLUSTER_COUNT, visible = true)
                setLayerVisibility(LAYER_DOTS, visible = true)
                applyHexOpacity(0.0)
            }
            MapVisualizationMode.COVERAGE_HEX -> {
                (style.getLayer(LAYER_MOCK_GRID_FILL) as? FillLayer)?.fillOpacity(0.0)
                setLayerVisibility(LAYER_HEATMAP, visible = false)
                setLayerVisibility(LAYER_CLUSTER_BG, visible = false)
                setLayerVisibility(LAYER_CLUSTER_COUNT, visible = false)
                setLayerVisibility(LAYER_DOTS, visible = false)
                if (backendHexConnected) {
                    applyHexOpacity(0.45)
                } else {
                    applyHexOpacity(0.0)
                }
            }
        }
    }

    private fun applyHexOpacity(fillOpacity: Double) {
        (style.getLayer(LAYER_HEX_FILL) as? FillLayer)?.fillOpacity(fillOpacity)
        val outline = if (fillOpacity > 0.0) 0.35 else 0.0
        (style.getLayer(LAYER_HEX_OUTLINE) as? LineLayer)?.lineOpacity(outline)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Push a fresh Room projection list into the local GeoJSON source.
     *
     * Called from [HomeMapFragment] whenever [HomeViewModel.measurementPoints] emits.
     * Mapbox re-renders the affected tiles automatically after the source update.
     *
     * Thread safety: Mapbox requires all style mutations on the main thread.
     * The caller (Fragment) guarantees this via its lifecycle-aware coroutine dispatcher.
     */
    fun updateLocalPoints(points: List<MapPointProjection>) {
        val features = points.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                addStringProperty("signalTier", p.signalTier)
                addStringProperty("sampleType", p.sampleType)
                addStringProperty("id", p.id)
            }
        }
        style.getSourceAs<GeoJsonSource>(SOURCE_LOCAL)
            ?.featureCollection(FeatureCollection.fromFeatures(features))
    }

    /**
     * Connects the backend GeoJSON URL and makes hex layers visible.
     *
     * Call this when Remote Config provides a non-empty backend data URL.
     * Safe to call before or after the user opens the map — the Style object
     * is already loaded by the time this is called (HomeMapFragment checks).
     *
     * For GeoJSON URL approach (Option A):
     *   The Mapbox SDK fetches and caches the URL automatically.
     *   Set tileSize / tolerance as needed for large polygon counts.
     *
     * For Mapbox Vector Tiles (Option B):
     *   Remove this method. Instead, replace the SOURCE_BACKEND geoJsonSource
     *   in addBackendSource() with a vectorSource before the style loads.
     *   Vector sources cannot be swapped post-style-load in Mapbox v11.
     */
    fun connectBackendSource(geoJsonUrl: String) {
        style.getSourceAs<GeoJsonSource>(SOURCE_BACKEND)?.url(geoJsonUrl)
        backendHexConnected = true
        if (visualizationMode == MapVisualizationMode.COVERAGE_HEX) {
            applyHexOpacity(0.45)
        }
    }

    /** Loads hex coverage from in-memory GeoJSON (e.g. mock data) instead of a URL. */
    fun loadMockHexFeatureCollection(collection: FeatureCollection) {
        style.getSourceAs<GeoJsonSource>(SOURCE_BACKEND)?.featureCollection(collection)
        backendHexConnected = true
        if (visualizationMode == MapVisualizationMode.COVERAGE_HEX) {
            applyHexOpacity(0.45)
        }
    }

    // ── Tap query helpers ─────────────────────────────────────────────────────

    /**
     * Returns true if [feature] is a cluster (has "point_count" property).
     * Use alongside [MapboxMap.queryRenderedFeatures] in the Fragment's tap handler.
     */
    fun isCluster(feature: QueriedRenderedFeature): Boolean =
        feature.queriedFeature.feature.getNumberProperty("point_count") != null

    /**
     * Returns the measurement UUID from an individual dot feature.
     * Returns null if the feature has no "id" property (e.g., it's a cluster).
     */
    fun dotId(feature: QueriedRenderedFeature): String? =
        feature.queriedFeature.feature.getStringProperty("id")

    /** The layer IDs to include in a rendered-feature query for tap detection. */
    val tappableLayers: List<String> get() = listOf(LAYER_CLUSTER_BG, LAYER_DOTS)

    /**
     * Fill layers for grid / hex cells — queried first on tap when the mode shows tappable polygons.
     */
    fun polygonTapLayerIdsForCurrentMode(): List<String> = when (visualizationMode) {
        MapVisualizationMode.SPEED_HEATMAP ->
            if (mockHeatmapGridLoaded) listOf(LAYER_MOCK_GRID_FILL) else emptyList()
        MapVisualizationMode.COVERAGE_HEX ->
            if (backendHexConnected) listOf(LAYER_HEX_FILL) else emptyList()
        MapVisualizationMode.SIGNAL_DOTS -> emptyList()
    }
}

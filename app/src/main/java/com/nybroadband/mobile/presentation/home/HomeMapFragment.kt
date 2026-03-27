package com.nybroadband.mobile.presentation.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraBoundsOptions
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.RenderedQueryGeometry
import com.mapbox.maps.RenderedQueryOptions
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.flyTo
import com.mapbox.maps.plugin.gestures.addOnMapClickListener
import com.mapbox.maps.plugin.locationcomponent.location
import com.nybroadband.mobile.R
import com.nybroadband.mobile.databinding.FragmentHomeMapBinding
import com.nybroadband.mobile.service.PassiveCollectionService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeMapFragment : Fragment() {

    private var _binding: FragmentHomeMapBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()

    private lateinit var mapboxMap: MapboxMap
    private lateinit var fusedLocation: FusedLocationProviderClient

    private lateinit var bottomSheet: BottomSheetBehavior<LinearLayout>

    // Guards source/layer writes — set true inside loadStyle() callback
    private var mapStyleReady = false

    // Single source of truth for all Mapbox sources and layers.
    // Created inside loadStyle() callback; nulled in onDestroyView().
    private var layerManager: MapLayerManager? = null

    private var layerPanelVisible = false
    private var mapVisualizationMode: MapVisualizationMode =
        if (MapMockConfig.ENABLED) MapVisualizationMode.SPEED_HEATMAP
        else MapVisualizationMode.SIGNAL_DOTS

    // ── Service binding ───────────────────────────────────────────────────────
    // Bound opportunistically in onStart/onStop — no BIND_AUTO_CREATE so the
    // service is only started by the Settings toggle, not by opening the map screen.

    private var serviceConnection: ServiceConnection? = null
    private var binderObservationJob: Job? = null

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        // Default camera — center of New York State
        private const val DEFAULT_LAT = 42.965
        private const val DEFAULT_LON = -75.526
        private const val DEFAULT_ZOOM = 6.5

        /** Mock data: wider view over US so grid / heatmap matches reference apps. */
        private const val MOCK_DEFAULT_LAT = 39.5
        private const val MOCK_DEFAULT_LON = -93.0
        private const val MOCK_DEFAULT_ZOOM = 4.2

        /**
         * Zoom range aligned with typical coverage-map style apps (e.g. coveragemap.com):
         * zoom out to ~0 shows the full world in Web Mercator; zoom in to street/building level.
         */
        private const val MAP_MIN_ZOOM_WORLD = 0.0
        private const val MAP_MAX_ZOOM_STREET = 22.0

        private const val TAG_GRID_CELL_SHEET = "grid_cell_detail"
    }

    // ── Permission launcher ───────────────────────────────────────────────────

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            viewModel.onLocationPermissionGranted()
            if (mapStyleReady) enableLocationPuck()
        } else {
            viewModel.onLocationPermissionDenied()
            showPermissionDeniedSnackbar()
        }
    }

    // ── View lifecycle ────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fusedLocation = LocationServices.getFusedLocationProviderClient(requireActivity())
        setupMap()
        setupBottomSheet()
        setupMapChrome()
        setupClickListeners()
        // observeViewModel MUST be registered before checkLocationPermission.
        // The coroutine waits for STARTED lifecycle, so it subscribes to the
        // StateFlow (which holds Ready() from ViewModel.init) at onStart().
        // If checkLocationPermission ran first it could push LocationPermissionRequired
        // before the collector ever subscribes, silently skipping renderReadyState().
        observeViewModel()
        checkLocationPermission()
        applyBottomOffset()
    }

    // ── Map ───────────────────────────────────────────────────────────────────

    private fun setupMap() {
        mapboxMap = binding.mapView.mapboxMap
        setupMapZoomLimits()

        if (MapMockConfig.ENABLED) {
            mapboxMap.setCamera(
                CameraOptions.Builder()
                    .center(Point.fromLngLat(MOCK_DEFAULT_LON, MOCK_DEFAULT_LAT))
                    .zoom(MOCK_DEFAULT_ZOOM)
                    .build()
            )
        } else {
            mapboxMap.setCamera(
                CameraOptions.Builder()
                    .center(Point.fromLngLat(DEFAULT_LON, DEFAULT_LAT))
                    .zoom(DEFAULT_ZOOM)
                    .build()
            )
        }

        val styleUri = if (isDarkMode()) Style.DARK else Style.LIGHT
        mapboxMap.loadStyle(styleUri) { style ->
            mapStyleReady = true
            layerManager = MapLayerManager(style).also { lm ->
                if (MapMockConfig.ENABLED) {
                    lm.loadMockHexFeatureCollection(MapMockData.mockCoverageHexCollection())
                    lm.loadMockHeatmapGrid(MapMockData.mockUsCentralGridHeatmap())
                }
                lm.setVisualizationMode(mapVisualizationMode)
                // Points may have been emitted before the style finished loading; StateFlow will not re-emit.
                applyLatestMapPoints(lm)
            }
            setupMapTapListener()
            if (hasLocationPermission()) enableLocationPuck()
        }
    }

    /** Pushes [HomeViewModel.mapPointsForDisplay] into the GeoJSON source (call whenever style is ready). */
    private fun applyLatestMapPoints(lm: MapLayerManager) {
        lm.updateLocalPoints(viewModel.mapPointsForDisplay.value)
    }

    /**
     * Allows pinch-zoom from world scale (min) to building detail (max), similar to coveragemap.com.
     * Without this, Mapbox defaults can feel overly restricted when zooming out.
     */
    private fun setupMapZoomLimits() {
        mapboxMap.setBounds(
            CameraBoundsOptions.Builder()
                .minZoom(MAP_MIN_ZOOM_WORLD)
                .maxZoom(MAP_MAX_ZOOM_STREET)
                .build()
        )
    }

    private fun setupMapChrome() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.mapTopChrome) { v, insets ->
            val top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top + 4)
            insets
        }
        ViewCompat.requestApplyInsets(binding.mapTopChrome)

        binding.etMapSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                Toast.makeText(requireContext(), R.string.map_search_not_connected, Toast.LENGTH_SHORT).show()
                true
            } else false
        }

        binding.btnMapLayers.setOnClickListener {
            layerPanelVisible = !layerPanelVisible
            binding.layerSelectionPanel.isVisible = layerPanelVisible
        }

        binding.btnMapMyLocation.setOnClickListener { recenterMap() }

        binding.btnMapFilters.setOnClickListener {
            MapFiltersBottomSheet().show(childFragmentManager, "map_filters")
        }

        binding.btnMapCountry.setOnClickListener { anchor ->
            PopupMenu(requireContext(), anchor).apply {
                menu.add(0, 1, 0, R.string.map_country_us)
                menu.add(0, 2, 0, R.string.map_country_pk)
                setOnMenuItemClickListener { item ->
                    viewModel.setCountryFilter(
                        if (item.itemId == 2) MapCountryFilter.PK else MapCountryFilter.US
                    )
                    true
                }
            }.show()
        }

        binding.chipCarriers.setOnClickListener { anchor ->
            PopupMenu(requireContext(), anchor).apply {
                menuInflater.inflate(R.menu.menu_map_carriers, menu)
                setOnMenuItemClickListener { item ->
                    viewModel.setCarrierFilter(
                        when (item.itemId) {
                            R.id.carrier_att -> MapCarrierFilter.ATT
                            R.id.carrier_tmo -> MapCarrierFilter.TMO
                            R.id.carrier_vzw -> MapCarrierFilter.VZW
                            else -> MapCarrierFilter.ALL
                        }
                    )
                    true
                }
            }.show()
        }
        binding.chipNetwork.setOnClickListener { anchor ->
            PopupMenu(requireContext(), anchor).apply {
                menuInflater.inflate(R.menu.menu_map_network, menu)
                setOnMenuItemClickListener { item ->
                    viewModel.setNetworkFilter(
                        when (item.itemId) {
                            R.id.net_all -> MapNetworkFilter.ALL
                            R.id.net_lte_5g -> MapNetworkFilter.LTE_5G
                            R.id.net_lte -> MapNetworkFilter.LTE_ONLY
                            R.id.net_5g -> MapNetworkFilter.NR_ONLY
                            else -> MapNetworkFilter.LTE_5G
                        }
                    )
                    true
                }
            }.show()
        }
        binding.chipMetric.setOnClickListener { anchor ->
            PopupMenu(requireContext(), anchor).apply {
                menuInflater.inflate(R.menu.menu_map_metric, menu)
                setOnMenuItemClickListener { item ->
                    viewModel.setMetricFilter(
                        when (item.itemId) {
                            R.id.metric_median -> MapMetricFilter.MEDIAN
                            R.id.metric_best -> MapMetricFilter.BEST
                            else -> MapMetricFilter.AVERAGE
                        }
                    )
                    true
                }
            }.show()
        }

        binding.cardLayerSpeed.setOnClickListener {
            applyVisualizationMode(MapVisualizationMode.SPEED_HEATMAP)
        }
        binding.cardLayerSignal.setOnClickListener {
            applyVisualizationMode(MapVisualizationMode.SIGNAL_DOTS)
        }
        binding.cardLayerCoverage.setOnClickListener {
            applyVisualizationMode(MapVisualizationMode.COVERAGE_HEX)
        }

        binding.btnLegend.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.map_legend_title)
                .setMessage(R.string.map_legend_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        binding.fabMapInfo.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.map_info_title)
                .setMessage(R.string.map_info_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        updateLayerCardSelection()
    }

    private fun bindToolbarFilters(s: MapFilterState) {
        binding.chipCarriers.text = when (s.carrier) {
            MapCarrierFilter.ALL -> getString(R.string.map_filter_carriers)
            MapCarrierFilter.ATT -> getString(R.string.map_carrier_att)
            MapCarrierFilter.TMO -> getString(R.string.map_carrier_tmobile)
            MapCarrierFilter.VZW -> getString(R.string.map_carrier_verizon)
        }
        binding.chipNetwork.text = when (s.network) {
            MapNetworkFilter.ALL -> getString(R.string.map_network_all)
            MapNetworkFilter.LTE_5G -> getString(R.string.map_network_lte_5g)
            MapNetworkFilter.LTE_ONLY -> getString(R.string.map_network_lte_only)
            MapNetworkFilter.NR_ONLY -> getString(R.string.map_network_5g_only)
        }
        binding.chipMetric.text = when (s.metric) {
            MapMetricFilter.AVERAGE -> getString(R.string.map_metric_average)
            MapMetricFilter.MEDIAN -> getString(R.string.map_metric_median)
            MapMetricFilter.BEST -> getString(R.string.map_metric_best)
        }
        binding.btnMapCountry.setImageResource(
            if (s.country == MapCountryFilter.PK) R.drawable.flag_pk_badge else R.drawable.flag_us_badge
        )
        binding.btnLegend.isVisible = s.showLegend
    }

    private fun applyVisualizationMode(mode: MapVisualizationMode) {
        mapVisualizationMode = mode
        if (mapStyleReady) {
            layerManager?.setVisualizationMode(mode)
            // Re-push points so clustered source is never left empty after mode / lifecycle races.
            if (mode == MapVisualizationMode.SIGNAL_DOTS) {
                layerManager?.updateLocalPoints(viewModel.mapPointsForDisplay.value)
            }
        }
        updateLayerCardSelection()
    }

    private fun updateLayerCardSelection() {
        val mode = mapVisualizationMode
        val stroke = R.drawable.bg_layer_card_border_selected
        binding.frameLayerSpeed.background = if (mode == MapVisualizationMode.SPEED_HEATMAP) {
            ContextCompat.getDrawable(requireContext(), stroke)
        } else null
        binding.frameLayerSignal.background = if (mode == MapVisualizationMode.SIGNAL_DOTS) {
            ContextCompat.getDrawable(requireContext(), stroke)
        } else null
        binding.frameLayerCoverage.background = if (mode == MapVisualizationMode.COVERAGE_HEX) {
            ContextCompat.getDrawable(requireContext(), stroke)
        } else null
    }

    /**
     * Registers a single map click listener that handles two tap targets:
     *
     *   • Cluster bubble → fly to a closer zoom so the cluster expands into
     *     individual dots (zoom +2, capped at street level).
     *
     *   • Individual dot  → navigate to the result detail screen for that
     *     measurement (action_home_to_resultDetail, passes "measurementId").
     *
     * Uses [MapLayerManager.tappableLayers] so the query is scoped only to
     * cluster and dot layers — base map labels are not accidentally hit.
     */
    private fun setupMapTapListener() {
        mapboxMap.addOnMapClickListener { point ->
            val lm = layerManager ?: return@addOnMapClickListener false
            val screenCoord = mapboxMap.pixelForCoordinate(point)
            val polyLayers = lm.polygonTapLayerIdsForCurrentMode()

            fun handleClusterOrDot() {
                mapboxMap.queryRenderedFeatures(
                    RenderedQueryGeometry(screenCoord),
                    RenderedQueryOptions(lm.tappableLayers, null)
                ) { result ->
                    val hit = result.value?.firstOrNull() ?: return@queryRenderedFeatures
                    if (lm.isCluster(hit)) {
                        val targetZoom = (mapboxMap.cameraState.zoom + 2.0).coerceAtMost(14.0)
                        mapboxMap.flyTo(
                            CameraOptions.Builder()
                                .center(point)
                                .zoom(targetZoom)
                                .build(),
                            MapAnimationOptions.mapAnimationOptions { duration(400L) }
                        )
                    } else {
                        lm.dotId(hit)?.let { measurementId ->
                            if (measurementId.startsWith("mock-")) {
                                Snackbar.make(
                                    binding.root,
                                    R.string.map_mock_no_detail,
                                    Snackbar.LENGTH_SHORT
                                ).show()
                                return@queryRenderedFeatures
                            }
                            findNavController().navigate(
                                R.id.action_home_to_resultDetail,
                                bundleOf("measurementId" to measurementId)
                            )
                        }
                    }
                }
            }

            if (polyLayers.isEmpty()) {
                handleClusterOrDot()
            } else {
                mapboxMap.queryRenderedFeatures(
                    RenderedQueryGeometry(screenCoord),
                    RenderedQueryOptions(polyLayers, null)
                ) { result ->
                    val polyHit = result.value?.firstOrNull()
                    if (polyHit != null) {
                        val detail = GridCellDetail.fromFeature(
                            polyHit.queriedFeature.feature,
                            tapLat = point.latitude(),
                            tapLon = point.longitude(),
                        )
                        GridCellDetailBottomSheet.newInstance(detail)
                            .show(childFragmentManager, TAG_GRID_CELL_SHEET)
                    } else {
                        handleClusterOrDot()
                    }
                }
            }
            true
        }
    }

    private fun enableLocationPuck() {
        binding.mapView.location.apply {
            enabled = true
            pulsingEnabled = true
        }
    }

    // ── Bottom sheet ──────────────────────────────────────────────────────────

    private fun setupBottomSheet() {
        bottomSheet = BottomSheetBehavior.from(binding.bottomSheet).apply {
            peekHeight = resources.getDimensionPixelSize(R.dimen.bottom_sheet_peek_height)
            isHideable = false
            isDraggable = true
            halfExpandedRatio = 0.40f
            state = BottomSheetBehavior.STATE_COLLAPSED
        }

        // Show/hide expanded-only content based on sheet state
        bottomSheet.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(sheet: View, newState: Int) {
                val aboveCollapsed = newState != BottomSheetBehavior.STATE_COLLAPSED &&
                        newState != BottomSheetBehavior.STATE_DRAGGING
                binding.signalCard.isVisible = aboveCollapsed
                binding.tvViewResults.isVisible =
                    newState == BottomSheetBehavior.STATE_EXPANDED
            }

            override fun onSlide(sheet: View, slideOffset: Float) { /* no-op */
            }
        })
    }

    // ── Click listeners ───────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.btnDeadZone.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_deadZone)
        }

        // Observe result from DeadZoneFragment — show confirmation Snackbar on return
        findNavController().currentBackStackEntry?.savedStateHandle?.apply {
            getLiveData<Boolean>(com.nybroadband.mobile.presentation.deadzone.DeadZoneFragment.RESULT_SUBMITTED)
                .observe(viewLifecycleOwner) { submitted ->
                    if (submitted != true) return@observe
                    remove<Boolean>(com.nybroadband.mobile.presentation.deadzone.DeadZoneFragment.RESULT_SUBMITTED)
                    val isOnline = get<Boolean>(
                        com.nybroadband.mobile.presentation.deadzone.DeadZoneFragment.RESULT_ONLINE
                    ) == true
                    val msg =
                        if (isOnline) R.string.dead_zone_success_online else R.string.dead_zone_success
                    Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                }
        }
        binding.tvViewResults.setOnClickListener {
            // Navigate to Results tab by selecting it in the bottom nav
            requireActivity()
                .findViewById<BottomNavigationView>(R.id.bottomNav)
                .selectedItemId = R.id.resultsFragment
        }
    }

    // ── Location helpers ──────────────────────────────────────────────────────

    private fun checkLocationPermission() {
        if (hasLocationPermission()) {
            viewModel.onLocationPermissionGranted()
        } else {
            // Do NOT push onLocationPermissionDenied() here — the launcher result
            // callback is always async (even for permanently-denied permissions).
            // Calling it synchronously here would set LocationPermissionRequired
            // before the StateFlow collector subscribes at onStart(), causing the
            // collector to never see Ready() and skipping renderReadyState().
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")  // always guarded by hasLocationPermission()
    private fun recenterMap() {
        if (!hasLocationPermission()) {
            checkLocationPermission()
            return
        }
        fusedLocation.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                viewModel.onRecenterRequested(location.latitude, location.longitude)
            } else {
                Snackbar.make(binding.root, R.string.location_unavailable, Snackbar.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun flyToLocation(lat: Double, lon: Double) {
        mapboxMap.flyTo(
            CameraOptions.Builder()
                .center(Point.fromLngLat(lon, lat))
                .zoom(14.0)
                .build(),
            MapAnimationOptions.mapAnimationOptions { duration(800L) }
        )
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionDeniedSnackbar() {
        Snackbar.make(binding.root, R.string.location_unavailable, Snackbar.LENGTH_LONG)
            .setAction(R.string.action_open_settings) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", requireContext().packageName, null)
                    }
                )
            }
            .show()
    }

    // ── ViewModel observation ─────────────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Status chip, signal card, auto-test banner
                launch {
                    viewModel.uiState.collect { state -> renderState(state) }
                }

                launch {
                    viewModel.mapFilterState.collect { bindToolbarFilters(it) }
                }

                // Measurement + mock dots on the map
                launch {
                    viewModel.mapPointsForDisplay.collect { points ->
                        if (mapStyleReady) {
                            layerManager?.updateLocalPoints(points)
                        }
                    }
                }

                // One-time events (flyTo, permission request)
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is HomeUiEvent.FlyToLocation ->
                                flyToLocation(event.lat, event.lon)

                            HomeUiEvent.RequestLocationPermission ->
                                checkLocationPermission()
                        }
                    }
                }
            }
        }
    }

    // ── State rendering ───────────────────────────────────────────────────────

    private fun renderState(state: HomeUiState) {
        when (state) {
            HomeUiState.Loading -> {
                binding.statusChip.text = getString(R.string.status_idle)
            }

            HomeUiState.LocationPermissionRequired -> {
                binding.statusChip.text = getString(R.string.location_unavailable)
                binding.statusChip.setChipBackgroundColorResource(R.color.status_no_service)
            }

            is HomeUiState.Ready -> renderReadyState(state)
        }
    }

    private fun renderReadyState(state: HomeUiState.Ready) {
        // Status chip color
        val chipColorRes = when (state.collectionStatus) {
            CollectionStatus.MEASURING,
            CollectionStatus.AUTO_TESTING -> R.color.status_measuring

            CollectionStatus.RUNNING_TEST -> R.color.status_testing
            CollectionStatus.NO_SERVICE -> R.color.status_no_service
            CollectionStatus.QUEUED -> R.color.status_queued
            CollectionStatus.IDLE -> R.color.status_idle
        }
        binding.statusChip.text = state.collectionStatus.label
        binding.statusChip.setChipBackgroundColorResource(chipColorRes)

        // Signal card
        binding.tvSignalQuality.text = state.signalState.qualityLabel
        binding.tvNetworkInfo.text = buildNetworkInfo(state.signalState)

        // Auto-test banner (full-width strip at top)
        binding.autoTestBanner.isVisible = state.autoTestActive
    }

    private fun buildNetworkInfo(signal: SignalState): String = buildString {
        if (signal.networkType != "--") append(signal.networkType)
        if (signal.carrierName.isNotBlank() && signal.carrierName != "--") {
            if (isNotEmpty()) append(" · ")
            append(signal.carrierName)
        }
        if (isEmpty()) append("--")
    }

    private fun isDarkMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    // ── Bottom offset ─────────────────────────────────────────────────────────

    /**
     * Offsets the bottom sheet and the FAB so they sit above the BottomNavigationView
     * and the system navigation bar.
     *
     * Why two mechanisms:
     *  • CoordinatorLayout.paddingBottom  — moves gravity="bottom" views (FAB) up.
     *    CoordinatorLayout respects padding for gravity-based children.
     *  • bottomSheet.peekHeight += totalOffset  — BottomSheetBehavior ignores the
     *    parent's padding entirely; it positions the sheet using raw parent.height.
     *    Adding the nav offset to peekHeight makes the visible 88 dp peek area appear
     *    exactly above the navigation bar; the rest of the sheet body is hidden behind
     *    it (drawing is allowed by android:clipToPadding="false" on the root).
     *
     * Why NOT setOnApplyWindowInsetsListener / requestApplyInsets:
     *  • activity_main.xml has android:fitsSystemWindows="false", which breaks the
     *    standard insets-dispatch chain so the listener is never called reliably.
     *  • Instead we read dimensions directly once the bottomNav is laid out, using
     *    ViewCompat.getRootWindowInsets() for the synchronous system-bar height.
     */
    private fun applyBottomOffset() {
        val bottomNav = requireActivity().findViewById<View>(R.id.bottomNav)
        val basePeekHeight = resources.getDimensionPixelSize(R.dimen.bottom_sheet_peek_height)

        fun applyNow() {
            val b = _binding ?: return
            val sysNavBottom = ViewCompat.getRootWindowInsets(b.root)
                ?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
            val totalOffset = bottomNav.height + sysNavBottom
            b.root.updatePadding(bottom = totalOffset)
            bottomSheet.peekHeight = basePeekHeight + totalOffset
        }

        if (bottomNav.isLaidOut && bottomNav.height > 0) {
            applyNow()
        } else {
            bottomNav.doOnLayout { applyNow() }
        }
    }

    // ── MapView lifecycle ─────────────────────────────────────────────────────
    // MapView in Mapbox SDK v10+ handles its own lifecycle automatically via
    // a LifecycleObserver — no manual onStart/onStop/onDestroy calls needed.

    override fun onStart() {
        super.onStart()
        bindToCollectionService()
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate permission every time the fragment becomes visible.
        // This handles the case where the user tapped "Open Settings" via the
        // snackbar, granted location permission there, and returned to the app.
        // Without this, the state stays permanently at LocationPermissionRequired.
        if (hasLocationPermission()) {
            viewModel.onLocationPermissionGranted()
            if (mapStyleReady) enableLocationPuck()
        }
    }

    override fun onStop() {
        unbindFromCollectionService()
        super.onStop()
    }

    override fun onDestroyView() {
        mapStyleReady = false
        layerManager = null   // release Style reference before the MapView is destroyed
        _binding = null
        super.onDestroyView()
    }

    // ── Service binding helpers ───────────────────────────────────────────────

    /**
     * Attempts to bind to [PassiveCollectionService] if it is already running.
     *
     * No [Context.BIND_AUTO_CREATE] flag — the service is only started by the
     * Settings passive-collection toggle, not by opening the map screen. If the
     * service is not running, [bindService] returns false silently; the signal
     * card retains its empty/last state until the service starts.
     */
    private fun bindToCollectionService() {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val binder = service as PassiveCollectionService.PassiveCollectionBinder

                binderObservationJob?.cancel()
                binderObservationJob = viewLifecycleOwner.lifecycleScope.launch {
                    // Forward live signal to ViewModel — drives signal card in bottom sheet
                    launch {
                        binder.latestSignal.collect { snapshot ->
                            viewModel.onNewSignalState(
                                snapshot?.toSignalState() ?: SignalState.empty()
                            )
                        }
                    }
                    // Forward collection status — drives status chip text/color
                    launch {
                        binder.collectionStatus.collect { status ->
                            viewModel.onCollectionStatusChanged(status)
                        }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // Service process died — clear to empty state
                binderObservationJob?.cancel()
                binderObservationJob = null
                viewModel.onNewSignalState(SignalState.empty())
                viewModel.onCollectionStatusChanged(CollectionStatus.IDLE)
            }
        }

        serviceConnection = connection
        requireContext().bindService(
            PassiveCollectionService.startIntent(requireContext()),
            connection,
            Context.BIND_ADJUST_WITH_ACTIVITY   // no BIND_AUTO_CREATE
        )
    }

    private fun unbindFromCollectionService() {
        binderObservationJob?.cancel()
        binderObservationJob = null
        serviceConnection?.let {
            try {
                requireContext().unbindService(it)
            } catch (_: IllegalArgumentException) {
                // Not bound — bindService returned false; nothing to unbind
            }
        }
        serviceConnection = null
    }
}

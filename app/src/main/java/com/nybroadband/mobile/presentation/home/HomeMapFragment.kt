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
import android.widget.LinearLayout
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
import com.google.android.material.snackbar.Snackbar
import com.mapbox.geojson.Point
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

        // Frame New York State immediately — no GPS fix needed
        mapboxMap.setCamera(
            CameraOptions.Builder()
                .center(Point.fromLngLat(DEFAULT_LON, DEFAULT_LAT))
                .zoom(DEFAULT_ZOOM)
                .build()
        )

        val styleUri = if (isDarkMode()) Style.DARK else Style.LIGHT
        mapboxMap.loadStyle(styleUri) { style ->
            mapStyleReady = true
            layerManager = MapLayerManager(style)
            setupMapTapListener()
            if (hasLocationPermission()) enableLocationPuck()
        }
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
            mapboxMap.queryRenderedFeatures(
                RenderedQueryGeometry(screenCoord),
                RenderedQueryOptions(lm.tappableLayers, null)
            ) { result ->
                val hit = result.value?.firstOrNull() ?: return@queryRenderedFeatures
                if (lm.isCluster(hit)) {
                    // Zoom in so the cluster can expand into individual dots.
                    // CLUSTER_MAX_ZOOM is 13 — beyond that, only dots render.
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
                        findNavController().navigate(
                            R.id.action_home_to_resultDetail,
                            bundleOf("measurementId" to measurementId)
                        )
                    }
                }
            }
            true // consume — prevents camera pan on dot/cluster tap
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
        binding.btnRunTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_manualTest)
        }
        binding.btnAutoTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_recurringSetup)
        }
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
        binding.fabRecenter.setOnClickListener { recenterMap() }
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

                // Measurement dots on the map
                launch {
                    viewModel.measurementPoints.collect { points ->
                        if (mapStyleReady) layerManager?.updateLocalPoints(points)
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

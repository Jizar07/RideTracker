package com.stoffeltech.ridetracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.MarkerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.stoffeltech.ridetracker.uber.UberActivity
import com.stoffeltech.ridetracker.services.FloatingOverlayService
import com.stoffeltech.ridetracker.services.FloatingOverlayService.Companion.hideOverlay
//import com.stoffeltech.ridetracker.services.ScreenCaptureService
import com.stoffeltech.ridetracker.services.clusterPlaces
import com.stoffeltech.ridetracker.services.fetchNearbyPOIs
import com.stoffeltech.ridetracker.utils.DirectionsHelper
import com.stoffeltech.ridetracker.utils.DistanceTracker
import com.stoffeltech.ridetracker.utils.RevenueTracker
import com.stoffeltech.ridetracker.utils.TimeTracker
import com.stoffeltech.ridetracker.MediaProjectionLifecycleManager // NEW: centralized MP manager
import com.stoffeltech.ridetracker.services.ClusterData
import com.stoffeltech.ridetracker.services.HistoryManager
import com.stoffeltech.ridetracker.services.LearningData
import com.stoffeltech.ridetracker.services.LearningManager
import com.stoffeltech.ridetracker.services.ScreenCaptureService
import com.stoffeltech.ridetracker.services.clusterLearningDataWithMerging
import com.stoffeltech.ridetracker.uber.UberApiTest
import com.stoffeltech.ridetracker.uber.UberParser
import com.stoffeltech.ridetracker.utils.FileLogger
import com.stoffeltech.ridetracker.utils.LocationSender
import com.stoffeltech.ridetracker.utils.PickupLocationGeoCoder.getCoordinatesAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

fun isNotificationAccessEnabled(context: Context): Boolean {
    val pkgName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return !TextUtils.isEmpty(flat) && flat.contains(pkgName)
}

fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
    val expectedComponentName = ComponentName(context, service)
    val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    return enabledServices?.contains(expectedComponentName.flattenToString()) ?: false
}

class RideTrackerApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        HistoryManager.loadHistory(this)
    }
}

fun LatLng.toLocation(): android.location.Location = android.location.Location("").apply {
    latitude = this@toLocation.latitude
    longitude = this@toLocation.longitude
}

/**
 * Returns a color with the specified opacity percentage.
 */
fun getColorWithOpacity(color: Int, opacityPercent: Int): Int {
    val alpha = (opacityPercent / 100.0 * 255).toInt().coerceIn(0, 255)
    return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}

private val LOCATION_PERMISSION_REQUEST_CODE = 1001
private val STORAGE_PERMISSION_REQUEST_CODE = 1001

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var mMap: GoogleMap
//    private lateinit var placesClient: PlacesClient
    private lateinit var tvCurrentSpeed: TextView
    private lateinit var ivModeSwitch: ImageView

    // Earnings views.
    private lateinit var tvDailyEarning: TextView
    private lateinit var tvWeeklyEarning: TextView
    private lateinit var tvMonthlyEarning: TextView
    private lateinit var tvYearlyEarning: TextView
    private lateinit var tvEarningPerHour: TextView
    private lateinit var tvEarningPerMile: TextView

    private lateinit var tvTimeTravelled: TextView
    private lateinit var tvDistanceTravelled: TextView

    private var shouldUpdateCamera = true
    private var currentBearing: Float = 0f

    // Inactivity detection variables.
    private var lastMovementTime: Long = 0L
    private var previousLocation: android.location.Location? = null
    private var poiTriggered: Boolean = false
    private val MOVEMENT_THRESHOLD = 30f
    private val INACTIVITY_DURATION_MS = 1 * 1000
    private var lastPoiFetchLocation: LatLng? = null
    private val POI_FETCH_DISTANCE_THRESHOLD = 1609.34
    private val rideHistoryViewModel: RideHistoryViewModel by viewModels()

    // *** NEW: Maintain a map of cluster overlays (p/hour clusters) for differential updates ***
    // Keyed by a unique identifier derived from the cluster center.
    private val clusterOverlays = mutableMapOf<String, Pair<com.google.android.gms.maps.model.Circle, com.google.android.gms.maps.model.Marker>>()

    // Helper function to generate a unique ID for a cluster overlay using the cluster's center coordinates.
    private fun getClusterId(center: LatLng): String {
        return "${"%.4f".format(center.latitude)}_${"%.4f".format(center.longitude)}"
    }
    // At the top of MainActivity.kt (inside the MainActivity class)
    private var isInitialCameraSet = false
    // In MainActivity.kt (inside the MainActivity class)
    private var currentClusters: List<ClusterData> = emptyList()



    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            super.onLocationResult(locationResult)
            locationResult.lastLocation?.let { location ->
//                LogHelper.logDebug("MainActivity", "Location received: ${location.latitude}, ${location.longitude}")
                val currentLatLng = LatLng(location.latitude, location.longitude)
                DistanceTracker.updateLocation(location)

                val speedMps = location.speed
                val speedMph = speedMps * 2.23694
                tvCurrentSpeed.text = String.format("%.2f mph", speedMph)

                if (speedMps >= 1f) currentBearing = location.bearing

                if (::mMap.isInitialized && shouldUpdateCamera) {
                    // Use 16f only for the first camera update, thereafter preserve the current zoom level.
                    val zoomLevel = if (!isInitialCameraSet) {
                        isInitialCameraSet = true
                        15f
                    } else {
                        mMap.cameraPosition.zoom
                    }
                    val cameraPosition = com.google.android.gms.maps.model.CameraPosition.Builder()
                        .target(currentLatLng)
                        .zoom(zoomLevel)
                        .bearing(currentBearing)
                        .build()
                    mMap.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
                }


                if (previousLocation == null) {
                    previousLocation = location
                    lastMovementTime = System.currentTimeMillis()
                    lastPoiFetchLocation = currentLatLng
                    return@let
                }

                val distance = previousLocation!!.distanceTo(location)
                if (distance > MOVEMENT_THRESHOLD) {
                    previousLocation = location
                    lastMovementTime = System.currentTimeMillis()
                    poiTriggered = false
                } else {
                    val currentTime = System.currentTimeMillis()
                    if (!poiTriggered && (currentTime - lastMovementTime) >= INACTIVITY_DURATION_MS) {
                        poiTriggered = true
                        onRideFinished()
                    }
                }

                lastPoiFetchLocation?.let { lastFetch ->
                    if (currentLatLng.toLocation().distanceTo(lastFetch.toLocation()) > POI_FETCH_DISTANCE_THRESHOLD) {
                        lastPoiFetchLocation = currentLatLng
                        onRideFinished()
                    }
                }
                // === NEW: Send the current location to your site ===
                LocationSender.sendLocation(location.latitude, location.longitude)
            }
        }
    }

    private fun startOverlayService() {
        val overlayIntent = Intent(this, FloatingOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(overlayIntent)
        } else {
            startService(overlayIntent)
        }
        LogHelper.logDebug("MainActivity", "FloatingOverlayService started.")
    }

    private lateinit var handler: Handler
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateTimeUI()
            updateEarningsUI()
            handler.postDelayed(this, 60000)
        }
    }

    @SuppressLint("MissingInflatedId", "UseSwitchCompatOrMaterialCode")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        // Inside onCreate() in MainActivity.kt, after setContentView(...)
        val screenshotSwitch = findViewById<Switch>(R.id.switchScreenshotOptions)
        // Use a SharedPreferences file named "settings" (or your preferred name)
        val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        screenshotSwitch.isChecked = settingsPrefs.getBoolean("screenshot_slider", false) // Initialize from saved value
        screenshotSwitch.setOnCheckedChangeListener { _, isChecked ->
            // Save the new state to SharedPreferences
            settingsPrefs.edit().putBoolean("screenshot_slider", isChecked).apply()
            FileLogger.log("MainActivity", "Screenshot slider set to $isChecked")
        }


        // Initialize FileLogger here
        FileLogger.init(this) // Now your logger is ready for use after MainActivity is running

        val btnRequestHistory = findViewById<Button>(R.id.btnRequestHistory)
        btnRequestHistory.setOnClickListener {
            val intent = Intent(this, RequestHistoryActivity::class.java)
            startActivity(intent)
        }
        // Inside onCreate() after setContentView(...)
        val btnUpdateClusters = findViewById<Button>(R.id.btnUpdateClusters)
        btnUpdateClusters.setOnClickListener {
            // Ensure the learning data is refreshed from history
            lifecycleScope.launch {
                LearningManager.updateLearningDataFromHistory(this@MainActivity)
                updateClusterMap()  // Now update clusters based on full history
            }
            Toast.makeText(this, "Cluster circles updated", Toast.LENGTH_SHORT).show()
        }




        // STEP 1: Check for overlay permission
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please enable overlay permission", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            // Optionally finish to force a restart later
            finish()
            return
        }

        tvCurrentSpeed = findViewById(R.id.tvCurrentSpeed)
        ivModeSwitch = findViewById(R.id.ivModeSwitch)
        tvDailyEarning = findViewById(R.id.tvDailyEarning)
        tvWeeklyEarning = findViewById(R.id.tvWeeklyEarning)
        tvMonthlyEarning = findViewById(R.id.tvMonthlyEarning)
        tvYearlyEarning = findViewById(R.id.tvYearlyEarning)
        tvEarningPerHour = findViewById(R.id.tvEarningPerHour)
        tvEarningPerMile = findViewById(R.id.tvEarningPerMile)
        tvTimeTravelled = findViewById(R.id.tvTimeTravelled)
        tvDistanceTravelled = findViewById(R.id.tvDistanceTravelled)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ivModeSwitch.setOnClickListener {
            if (AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
        }

        if (!isNotificationAccessEnabled(this)) {
            Toast.makeText(this, "Please grant notification access...", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (!isAccessibilityServiceEnabled(this, com.stoffeltech.ridetracker.services.AccessibilityService::class.java)) {
            Toast.makeText(this, "Please enable RideTracker Accessibility Service...", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        if (!com.stoffeltech.ridetracker.utils.hasUsageStatsPermission(this)) {
            Toast.makeText(this, "Please grant usage access permission...", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST_CODE)
            }
        }

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val drawerLayout = findViewById<DrawerLayout>(R.id.drawer_layout)
        val navView = findViewById<com.google.android.material.navigation.NavigationView>(R.id.nav_view)
        val toggle = ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        // ----- Updated Navigation Item Selection in MainActivity.kt -----
        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_home -> {
                    // Handle Home...
                    drawerLayout.closeDrawers()
                    true
                }
                R.id.nav_shift_information -> {
                    startActivity(Intent(this, ShiftInformationActivity::class.java))
                    drawerLayout.closeDrawers()
                    true
                }
                R.id.nav_gallery -> {
                    // Handle Gallery...
                    drawerLayout.closeDrawers()
                    true
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    drawerLayout.closeDrawers()
                    true
                }
                // New case for Uber
                R.id.nav_uber -> {
                    startActivity(Intent(this, UberActivity::class.java))
                    drawerLayout.closeDrawers()
                    true
                }
                // New case for Lyft Settings
                R.id.nav_lyft -> {
                    startActivity(Intent(this, com.stoffeltech.ridetracker.lyft.LyftActivity::class.java))
                    drawerLayout.closeDrawers()
                    true
                }
                else -> false
            }
        }


        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)


        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
        } else {
            startLocationUpdates()
        }

        findViewById<ImageView>(R.id.ivUber).setOnClickListener {
            launchApp("com.ubercab.driver", "com.ubercab.carbon.core.CarbonActivity")
        }
        findViewById<ImageView>(R.id.ivLyft).setOnClickListener {
            launchApp("com.lyft.android.driver", "com.lyft.android.driver.app.ui.DriverMainActivity")
        }

        getCoordinatesAsync(this, "37th Ave & 5th St, Naples") { coordinates ->
            if (coordinates != null) {
                // Use the coordinates (this code runs on the main thread)
                FileLogger.log("MainActivity", "Coordinates: ${coordinates.latitude}, ${coordinates.longitude}")
            } else {
                // Handle the error or absence of coordinates
                FileLogger.log("MainActivity", "No coordinates found")
            }
        }
        rideHistoryViewModel.rideHistory.observe(this) {
            // When rideHistory changes, trigger update of learning data.
            // Launch a coroutine in the Main scope.
            lifecycleScope.launch {
                // Call updateLearningDataFromHistory off the main thread.
                // Ensure updateLearningDataFromHistory is a suspend function (as previously updated).
                LearningManager.updateLearningDataFromHistory(this@MainActivity)
                // Optionally update any UI that depends on learning data here.
            }
        }

        startOverlayService()
        TimeTracker.loadIntervals(this)
        TimeTracker.startTracking(this)
        DistanceTracker.loadIntervals(this)
        DistanceTracker.startTracking()

        updateTimeUI()
    }

    private fun launchApp(packageName: String, activityClassName: String? = null) {
        val intent: Intent? = if (activityClassName != null) {
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(packageName, activityClassName)
            }
        } else {
            packageManager.getLaunchIntentForPackage(packageName)
        }
        LogHelper.logDebug("LaunchApp", "Launching app: $packageName, intent: $intent")
        if (intent != null && intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "App not installed or cannot be launched", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestScreenCapturePermission() {
        // If an old MediaProjection session is still active, release it.
        ScreenCaptureService.releasePersistentCapture()
        MediaProjectionLifecycleManager.stopMediaProjection()
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        val screenCaptureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(screenCaptureIntent)
        startOverlayService()
    }


    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.setTrafficEnabled(true)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap.isMyLocationEnabled = true
        }
        mMap.uiSettings.isZoomControlsEnabled = true
        val uiMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            try {
                val success = mMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.dark_map_style))
                if (!success) {
                    LogHelper.logDebug("MainActivity", "Failed to apply dark map style.")
                }
            } catch (e: Exception) {
                LogHelper.logError("MainActivity", "Error applying dark map style: ${e.message}", e)
            }
        }
        mMap.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                shouldUpdateCamera = false
                Handler(Looper.getMainLooper()).postDelayed({
                    shouldUpdateCamera = true
                }, 15000)
            }
        }
        // NEW: Check for an existing MediaProjection session and release it.
        if (MediaProjectionLifecycleManager.isMediaProjectionValid()) {
            FileLogger.log("MainActivity", "MediaProjection detected at onCreate is valid.")
            startOverlayService()
        } else {
            FileLogger.log("MainActivity", "MediaProjection detected at onCreate is invalid..")
            requestScreenCapturePermission()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates()
            }
        }
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val filePath = getPathFromUri(this, it)
            if (filePath != null) {
                processStoredRideRequest(filePath)
            }
        }
    }

    // ---------------------- UPDATED SCREEN CAPTURE LAUNCHER ----------------------
    // This callback delays the MediaProjection start until after the FloatingOverlayService is running.
    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
        result.data?.let { data ->
            // Delay MediaProjection start by 500 ms to ensure FloatingOverlayService is running.
            Handler(Looper.getMainLooper()).postDelayed({
                MediaProjectionLifecycleManager.startMediaProjection(this, result.resultCode, data)
                FileLogger.log("MainActivity", "MediaProjection set successfully after delay")

                val mediaProjection = MediaProjectionLifecycleManager.getMediaProjection()
                if (mediaProjection != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        delay(500)
                        ScreenCaptureService.initPersistentCapture(this@MainActivity, mediaProjection)
                    }
//                    startOverlayService()

                    } else {
                        FileLogger.log("MainActivity", "MediaProjection token is not valid after delay")
                    }
                }, 1000)
            } ?: run {
                FileLogger.log("MainActivity", "Screen capture permission granted but result.data is null")
            }
        } else {
            FileLogger.log("MainActivity", "Screen capture permission denied")
        }
    }



    private fun selectFileForOCR() {
        filePickerLauncher.launch("image/*")
    }
    private fun getPathFromUri(context: Context, uri: Uri): String? {
        var filePath: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                filePath = cursor.getString(columnIndex)
            }
        }
        return filePath
    }
    private fun processStoredRideRequest(selectedFilePath: String) {
        val file = File(selectedFilePath)
        if (file.exists()) {
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            recognizeTextFromImage(bitmap)
        }
    }
    private fun recognizeTextFromImage(bitmap: android.graphics.Bitmap) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener {
//                val extractedText = visionText.text
//                LogHelper.logDebug("RideTracker", "Extracted OCR Text: $extractedText")
            }
            .addOnFailureListener { e ->
                LogHelper.logError("RideTracker", "OCR Failed: ${e.message}", e)
            }
    }
    private var isTrackingLocation = false
    private fun startLocationUpdates() {
        if (isTrackingLocation) return
        val locationRequest = LocationRequest.Builder(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 10000L)
            .setMinUpdateIntervalMillis(5000L)
            .build()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
        isTrackingLocation = true
    }
    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isTrackingLocation = false
    }
    @SuppressLint("SetTextI18n", "CutPasteId")
    private fun onRideFinished() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
//                LogHelper.logDebug("MainActivity", "Retrieved last location: ${location.latitude}, ${location.longitude}")
                val currentLatLng = LatLng(location.latitude, location.longitude)
                lifecycleScope.launch {
                    val poiResults = fetchNearbyPOIs(currentLatLng, 25.0)
                    val clusters = clusterPlaces(poiResults)
                    runOnUiThread {
                        updateClusterMap()
                        for (cluster in clusters) {
                            mMap.addCircle(
                                CircleOptions()
                                    .center(LatLng(cluster.lat, cluster.lng))
                                    .radius(800.0)
                                    .strokeColor(getColorWithOpacity(Color.GREEN, 30))
                                    .strokeWidth(2f)
                                    .fillColor(getColorWithOpacity(Color.GREEN, 10))
                            )
                        }
                    }
                    updateClusterMap()
                    val aiResponseText = findViewById<TextView>(R.id.aiResponseText)
                    if (clusters.isNotEmpty()) {
                        val nearestCluster = clusters.minByOrNull { cluster ->
                            val hotspotLocation = LatLng(cluster.lat, cluster.lng).toLocation()
                            currentLatLng.toLocation().distanceTo(hotspotLocation)
                        }
                        val btnMapToHotspot = findViewById<Button>(R.id.btnMapToHotspot)
                        if (nearestCluster != null) {
                            val hotspotLatLng = LatLng(nearestCluster.lat, nearestCluster.lng)
                            val currentLocation = currentLatLng.toLocation()
                            val hotspotLocation = hotspotLatLng.toLocation()
                            val distanceMeters = currentLocation.distanceTo(hotspotLocation)
                            val distanceMiles = distanceMeters * 0.000621371
                            aiResponseText.text = "You are %.2f miles away from the closest hotspot".format(distanceMiles)
                            btnMapToHotspot.visibility = View.VISIBLE
                            runOnUiThread {
                                findViewById<TextView>(R.id.aiInitialMessage).visibility = View.GONE
                                findViewById<LinearLayout>(R.id.aiSplitContainer).visibility = View.VISIBLE
                            }
//                            val navigationContainer = findViewById<LinearLayout>(R.id.navigationContainer)
//                            val tvInstruction = findViewById<TextView>(R.id.tvNavigationInstruction)
//                            val btnNextInstruction = findViewById<Button>(R.id.btnNextInstruction)
//                            val btnCancelNavigation = findViewById<Button>(R.id.btnCancelNavigation)
//                            btnCancelNavigation.visibility = View.GONE
                            var navigationPolyline: com.google.android.gms.maps.model.Polyline? = null
//                            var instructionIndex = 0
                            btnMapToHotspot.setOnClickListener {
                                // Check the current state by inspecting the button's text
                                if (btnMapToHotspot.text.toString() == "Go") {
                                    // "Go" mode: Fetch and display the route.
                                    lifecycleScope.launch {
                                        val route = DirectionsHelper.fetchRoute(currentLatLng, hotspotLatLng)
                                        if (route != null) {
                                            runOnUiThread {
                                                // Remove any existing polyline
                                                navigationPolyline?.remove()
                                                navigationPolyline = mMap.addPolyline(
                                                    com.google.android.gms.maps.model.PolylineOptions()
                                                        .addAll(route.polylinePoints)
                                                        .color(Color.BLUE)
                                                        .width(10f)
                                                )
                                                // Change the button text to "Cancel"
                                                btnMapToHotspot.text = "Cancel"
                                            }
                                        } else {
                                            runOnUiThread {
                                                Toast.makeText(this@MainActivity, "Unable to fetch route", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                } else {
                                    // "Cancel" mode: Remove the route and update UI accordingly.
                                    navigationPolyline?.remove()
                                    navigationPolyline = null
                                    // Optionally hide any navigation container or update instructions if needed.
                                    // For example:
                                    // navigationContainer.visibility = View.GONE
                                    // btnNextInstruction.text = "Navigation canceled."
                                    btnMapToHotspot.text = "Go"
                                }
                            }

                        } else {
                            aiResponseText.text = "No hotspots found nearby."
                            findViewById<Button>(R.id.btnMapToHotspot).visibility = View.GONE

                            runOnUiThread {
                                findViewById<TextView>(R.id.aiInitialMessage).visibility = View.GONE
                                findViewById<LinearLayout>(R.id.aiSplitContainer).visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }
        }
    }
    // --- New: Method to update clusters on the map with earnings labels ---
    @SuppressLint("DefaultLocale")
    private fun updateClusterMap() {
        // Launch heavy computations off the main thread
        lifecycleScope.launch(Dispatchers.Default) {
            val learningDataList = LearningManager.getAllLearningData()
            val clusters: List<ClusterData> = clusterLearningDataWithMerging(learningDataList, 5.0)
                .sortedBy { it.averageEarningsPerHour ?: 0.0 }

            // Compute dynamic thresholds based on the clusters’ average earnings.
            val clusterEarningsValues = clusters.mapNotNull { it.averageEarningsPerHour }
            val globalMin = clusterEarningsValues.minOrNull() ?: 0.0
            val globalMax = clusterEarningsValues.maxOrNull() ?: globalMin

            // Prepare new overlay data keyed by a unique cluster ID
            val newOverlays = mutableMapOf<String, Triple<LatLng, Double, Pair<Int, Bitmap>>>()
            for (cluster in clusters) {
                val center = LatLng(cluster.centerLatitude, cluster.centerLongitude)
                val dynamicRadius = calculateClusterRadius(center, cluster.learningDataEntries)
                val circleColor = if (cluster.averageEarningsPerHour != null && cluster.averageEarningsPerHour > 0.0) {
                    getColorForEarnings(cluster.averageEarningsPerHour, globalMin, globalMax)
                } else {
                    Color.GRAY
                }
                val labelBitmap = createTextBitmap(String.format("$%.2f/hr", cluster.averageEarningsPerHour ?: 0.0))
                val id = getClusterId(center)
                newOverlays[id] = Triple(center, dynamicRadius, Pair(circleColor, labelBitmap))
            }

            // Switch back to the main thread for UI updates
            withContext(Dispatchers.Main) {
                // Update existing overlays or add new ones
                for ((id, overlayData) in newOverlays) {
                    val (center, radius, colorAndBitmap) = overlayData
                    val (circleColor, bitmap) = colorAndBitmap
                    if (clusterOverlays.containsKey(id)) {
                        // Overlay exists; update its properties if they differ
                        val (existingCircle, existingMarker) = clusterOverlays[id]!!
                        existingCircle.center = center
                        existingCircle.radius = radius
                        existingCircle.strokeColor = circleColor
                        existingCircle.fillColor = getColorWithOpacity(circleColor, 40)
                        existingMarker.position = center
                        existingMarker.setIcon(BitmapDescriptorFactory.fromBitmap(bitmap))
                    } else {
                        // Create new overlays and add them to the map and our tracking map
                        val newCircle = mMap.addCircle(
                            CircleOptions()
                                .center(center)
                                .radius(radius)
                                .strokeColor(circleColor)
                                .strokeWidth(2f)
                                .fillColor(getColorWithOpacity(circleColor, 45))
                        )
                        val newMarker = mMap.addMarker(
                            MarkerOptions()
                                .position(center)
                                .icon(BitmapDescriptorFactory.fromBitmap(bitmap))
                                .anchor(0.5f, 0.5f)
                                .flat(true)
                        )
                        newMarker?.alpha = 0.5f  // 0.0f is fully transparent, 1.0f is fully opaque

                        if (newMarker != null) {
                            clusterOverlays[id] = Pair(newCircle, newMarker)
                        }
                    }
                }
                // Remove overlays that are no longer present in the new cluster data
                val obsoleteKeys = clusterOverlays.keys.filter { it !in newOverlays.keys }
                for (key in obsoleteKeys) {
                    val (circle, marker) = clusterOverlays[key]!!
                    circle.remove()
                    marker.remove()
                    clusterOverlays.remove(key)
                }
            }
        }
    }

    private fun calculateClusterRadius(center: LatLng, entries: List<LearningData>): Double {
        // If only one entry, use a default radius (e.g., 8047 meters ~5 miles).
        if (entries.size < 2) return 8047.0

        var maxDistance = 0.0
        for (entry in entries) {
            val entryLatLng = LatLng(entry.latitude, entry.longitude)
            val results = FloatArray(1)
            android.location.Location.distanceBetween(
                center.latitude, center.longitude,
                entryLatLng.latitude, entryLatLng.longitude,
                results
            )
            if (results[0] > maxDistance) {
                maxDistance = results[0].toDouble()
            }
        }
        // Compute a dynamic radius: use the cluster spread plus padding,
        // but don't go below a small minimum (e.g., 500 meters).
        return maxOf(maxDistance + 100.0, 500.0)
    }


    // Helper function to interpolate between red and green based on earnings.
    // AFTER: Thresholds are passed as parameters (dynamic values)
    private fun getColorForEarnings(
        earnings: Double?,
        minEarnings: Double,
        maxEarnings: Double
    ): Int {
        if (earnings == null) return Color.GRAY
        val range = maxEarnings - minEarnings
        // If the range is effectively zero, use a default color (or compute a fallback)
        if (range < 0.0001) return Color.GREEN

        val clamped = earnings.coerceIn(minEarnings, maxEarnings)
        val normalized = (clamped - minEarnings) / range
        val red = (255 * (1 - normalized)).toInt()
        val green = (255 * normalized).toInt()
        return Color.rgb(red, green, 0)
    }



    private fun createTextBitmap(text: String): Bitmap {
        // Convert a desired text size from dp to pixels.
        val scale = resources.displayMetrics.density
        val desiredTextSizeDp = 20f
        val textSize = desiredTextSizeDp * scale  // Now textSize is scaled based on screen density.

        // Optionally, adjust the padding as well.
        val padding = (30 * scale).toInt()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            textAlign = Paint.Align.LEFT
        }
        val textBounds = Rect()
        paint.getTextBounds(text, 0, text.length, textBounds)
        val width = textBounds.width() + 2 * padding
        val height = textBounds.height() + 2 * padding

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = Color.TRANSPARENT })
        val x = padding.toFloat()
        // Adjust y so that the text is vertically centered.
        val y = height - padding.toFloat()
        canvas.drawText(text, x, y, paint)
        return bitmap
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            LearningManager.updateLearningDataFromHistory(this@MainActivity)
        }
        TimeTracker.loadIntervals(this)
        updateTimeUI()
        updateEarningsUI()
        updateEarningsRatesUI()
        handler = Handler(Looper.getMainLooper())
        handler.post(updateRunnable)
        if (AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES) {
            ivModeSwitch.setImageResource(R.drawable.ic_moon)
        } else {
            ivModeSwitch.setImageResource(R.drawable.ic_sun)
        }
//        UberApiTest.fetchCurrentRideRequest(this) { rideInfoString ->
//            FileLogger.log("MainActivity", "API call result: $rideInfoString")
//        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    @SuppressLint("SetTextI18n")
    private fun updateTimeUI() {
        val totalMillis = TimeTracker.getTotalTimeForToday()
        val hours = totalMillis / (1000 * 60 * 60)
        val minutes = (totalMillis / (1000 * 60)) % 60
        val timeText = "${hours}h ${minutes}m"
        val tvTimeTravelled: TextView = findViewById(R.id.tvTimeTravelled)
        tvTimeTravelled.text = "Time: $timeText"

        // Update the distance TextView on the main screen
        val dailyDistanceMeters = DistanceTracker.getTotalDistanceForToday()
        val dailyMiles = dailyDistanceMeters / 1609.34
        val tvDistanceTravelled: TextView = findViewById(R.id.tvDistanceTravelled)
        tvDistanceTravelled.text = "Distance: %.2f miles".format(dailyMiles)
    }

    @SuppressLint("SetTextI18n")
    private fun updateEarningsUI() {
        RevenueTracker.loadIntervals(this)
        val dailyRevenue = RevenueTracker.getDailyRevenue()
        val weeklyRevenue = RevenueTracker.getWeeklyRevenue()
        val monthlyRevenue = RevenueTracker.getMonthlyRevenue()
        val yearlyRevenue = RevenueTracker.getYearlyRevenue()
        tvDailyEarning.text = "Daily: $%.2f".format(dailyRevenue)
        tvWeeklyEarning.text = "Weekly: $%.2f".format(weeklyRevenue)
        tvMonthlyEarning.text = "Monthly: $%.2f".format(monthlyRevenue)
        tvYearlyEarning.text = "Yearly: $%.2f".format(yearlyRevenue)
        LogHelper.logDebug("MainActivity", "Earnings updated: Daily $dailyRevenue, Weekly $weeklyRevenue, Monthly $monthlyRevenue, Yearly $yearlyRevenue")
    }

    private fun updateEarningsRatesUI() {
        RevenueTracker.loadIntervals(this)
        val dailyEarnings = RevenueTracker.getDailyRevenue()
        val timeText = tvTimeTravelled.text.toString()
        val timeRegex = Regex("Time:\\s*(\\d+)h\\s*(\\d+)m")
        val timeMatch = timeRegex.find(timeText)
        val hours = if (timeMatch != null) {
            val h = timeMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val m = timeMatch.groupValues[2].toDoubleOrNull() ?: 0.0
            h + (m / 60.0)
        } else 0.0
        val distanceText = tvDistanceTravelled.text.toString()
        val distanceRegex = Regex("Distance:\\s*([\\d.]+)\\s*miles")
        val distanceMatch = distanceRegex.find(distanceText)
        val miles = distanceMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val earningsPerHour = if (hours > 0) dailyEarnings / hours else 0.0
        val earningsPerMile = if (miles > 0) dailyEarnings / miles else 0.0
        tvEarningPerHour.text = "$/Hour: %.2f".format(earningsPerHour)
        tvEarningPerMile.text = "$/Mile: %.2f".format(earningsPerMile)
        LogHelper.logDebug("MainActivity", "Parsed Time: $hours hours from '$timeText'")
        LogHelper.logDebug("MainActivity", "Parsed Distance: $miles miles from '$distanceText'")
        LogHelper.logDebug("MainActivity", "Daily Earnings: $dailyEarnings, Earnings per Hour: $earningsPerHour, Earnings per Mile: $earningsPerMile")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            TimeTracker.stopTracking(this)
            DistanceTracker.stopTracking(this)
            hideOverlay()
            updateEarningsUI()

            // ----- STEP 6: Clean up ScreenCaptureService resources -----
            // Release the text recognizer to free up OCR resources.
            ScreenCaptureService.releaseTextRecognizer()

            // Recycle and clear the last captured bitmap to avoid stale references.
            ScreenCaptureService.lastCapturedBitmap?.recycle()
            ScreenCaptureService.lastCapturedBitmap = null

            // Recycle and clear the last processed bitmap if it exists.
            ScreenCaptureService.lastProcessedBitmap?.recycle()
            ScreenCaptureService.lastProcessedBitmap = null

        }

        // Only stop MediaProjection if the activity is finishing and not merely undergoing a configuration change.
        if (isFinishing && !isChangingConfigurations) {
            // Stop MediaProjection and associated VirtualDisplay(s) via the centralized manager.
            ScreenCaptureService.releasePersistentCapture()
            MediaProjectionLifecycleManager.stopMediaProjection()
            LogHelper.logDebug("MainActivity", "MediaProjection stopped on activity destroy.")
        } else {
            LogHelper.logDebug("MainActivity", "Activity destroyed due to configuration change; keeping MediaProjection alive.")
        }
    }

    // ============================================================
    // LOGGING SECTION
    // ============================================================
    /**
     * LogHelper centralizes all logging for the app.
     * Toggle LOG_ENABLED to turn logging on or off.
     */
    object LogHelper {
        private const val LOG_ENABLED = true

        fun logInfo(tag: String, message: String) {
            if (LOG_ENABLED) android.util.Log.i(tag, message)
        }
        fun logDebug(tag: String, message: String) {
            if (LOG_ENABLED) android.util.Log.e(tag, message)
        }
        fun logError(tag: String, message: String, throwable: Throwable?) {
            if (LOG_ENABLED) android.util.Log.e(tag, message, throwable)
        }
    }
}

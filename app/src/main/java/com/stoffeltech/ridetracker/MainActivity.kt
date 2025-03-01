package com.stoffeltech.ridetracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.net.PlacesClient
import kotlinx.coroutines.launch
import android.graphics.Color
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import com.stoffeltech.ridetracker.services.FloatingOverlayService
import com.stoffeltech.ridetracker.services.ScreenCaptureService
import com.stoffeltech.ridetracker.services.clusterPlaces
import com.stoffeltech.ridetracker.services.fetchNearbyPOIs
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import com.stoffeltech.ridetracker.utils.DirectionsHelper
import android.provider.Settings
import android.view.WindowManager
import com.stoffeltech.ridetracker.utils.hasUsageStatsPermission
import android.text.TextUtils
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import android.content.ComponentName
import android.os.Looper
import com.stoffeltech.ridetracker.utils.TimeTracker
import android.os.Handler
import android.widget.ImageView
import com.stoffeltech.ridetracker.utils.DistanceTracker
import androidx.appcompat.app.AppCompatDelegate
import com.stoffeltech.ridetracker.utils.RevenueTracker

fun isNotificationAccessEnabled(context: Context): Boolean {
    val pkgName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return !TextUtils.isEmpty(flat) && flat.contains(pkgName)
}
fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
    val expectedComponentName = ComponentName(context, service)
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    )
    return enabledServices?.contains(expectedComponentName.flattenToString()) ?: false
}

class RideTrackerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("RideTrackerApplication", "Application initialized")
    }
}
fun LatLng.toLocation(): Location = Location("").apply {
    latitude = this@toLocation.latitude
    longitude = this@toLocation.longitude
}

/**
 * Returns a color with the specified opacity percentage.
 * @param color The base color (without alpha) e.g. Color.GREEN.
 * @param opacityPercent The opacity percentage (0 to 100). 100 means fully opaque.
 */
fun getColorWithOpacity(color: Int, opacityPercent: Int): Int {
    val alpha = (opacityPercent / 100.0 * 255).toInt().coerceIn(0, 255)
    val red = Color.red(color)
    val green = Color.green(color)
    val blue = Color.blue(color)
    return Color.argb(alpha, red, green, blue)
}

private val LOCATION_PERMISSION_REQUEST_CODE = 1001
private val SCREEN_CAPTURE_REQUEST_CODE = 2001
private const val STORAGE_PERMISSION_REQUEST_CODE = 1001

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    // FusedLocationProviderClient for device location tracking
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // GoogleMap instance to control the map
    private lateinit var mMap: GoogleMap

    // Single PlacesClient instance (reused throughout the activity)
    private lateinit var placesClient: PlacesClient
    private lateinit var tvCurrentSpeed: TextView
    private lateinit var ivModeSwitch: ImageView

    // Earnings row views (new)
    private lateinit var tvDailyEarning: TextView
    private lateinit var tvWeeklyEarning: TextView
    private lateinit var tvMonthlyEarning: TextView
    private lateinit var tvYearlyEarning: TextView
    private lateinit var tvEarningPerHour: TextView
    private lateinit var tvEarningPerMile: TextView

    private lateinit var tvTimeTravelled: TextView
    private lateinit var tvDistanceTravelled: TextView

    // Flag to control automatic camera updates
    private var shouldUpdateCamera = true
    private var currentBearing: Float = 0f

    // Variables for inactivity detection
    private var lastMovementTime: Long = 0L
    private var previousLocation: Location? = null
    private var poiTriggered: Boolean = false
    // Define a movement threshold (in meters) to consider as significant movement
    private val MOVEMENT_THRESHOLD = 30f
    // Inactivity period set to 1 minute (for testing; change to 5 minutes for production)
    private val INACTIVITY_DURATION_MS = 1 * 1000
    // Tracks the location at which POIs were last fetched.
    private var lastPoiFetchLocation: LatLng? = null
    // Threshold in meters (1 mile) to trigger a new POI fetch when driving.
    private val POI_FETCH_DISTANCE_THRESHOLD = 1609.34 // 1 mile in meters

    // Callback to receive and process location updates
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            super.onLocationResult(locationResult)
            locationResult.lastLocation?.let { location ->
                val currentLatLng = LatLng(location.latitude, location.longitude)

                // Update the DistanceTracker with the new location.
                DistanceTracker.updateLocation(location)


                // Update speed display
                val speedMps = location.speed  // speed in meters per second
                val speedMph = speedMps * 2.23694  // convert m/s to mph
                tvCurrentSpeed.text = String.format("%.2f mph", speedMph)

                // Update the bearing only if speed is above a threshold (e.g., 1 m/s)
                if (speedMps >= 1f) {
                    currentBearing = location.bearing
                }

                // Create a new camera position using the stored currentBearing
                if (::mMap.isInitialized && shouldUpdateCamera) {
                    val cameraPosition = com.google.android.gms.maps.model.CameraPosition.Builder()
                        .target(currentLatLng)
                        .zoom(18f)
                        .bearing(currentBearing) // Use last known bearing
                        .build()
                    mMap.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
                }



                // Inactivity detection logic.
                if (previousLocation == null) {
                    previousLocation = location
                    lastMovementTime = System.currentTimeMillis()
                    // Initialize lastPoiFetchLocation at the first location update.
                    lastPoiFetchLocation = currentLatLng
                    return@let
                }

                // Compute the distance from the previous significant location.
                val distance = previousLocation!!.distanceTo(location)
                if (distance > MOVEMENT_THRESHOLD) {
                    // Significant movement detected; update previous location and reset the inactivity timer.
                    previousLocation = location
                    lastMovementTime = System.currentTimeMillis()
                    poiTriggered = false
                } else {
                    // No significant movement; check the inactivity duration.
                    val currentTime = System.currentTimeMillis()
//                    Log.d("MainActivity", "No significant movement. Inactivity duration: ${currentTime - lastMovementTime} ms")
                    if (!poiTriggered && (currentTime - lastMovementTime) >= INACTIVITY_DURATION_MS) {
                        poiTriggered = true
                        onRideFinished()
                    }
                }

                // Check if the driver has moved far from the last POI fetch location.
                lastPoiFetchLocation?.let { lastFetch ->
                    if (currentLatLng.toLocation().distanceTo(lastFetch.toLocation()) > POI_FETCH_DISTANCE_THRESHOLD) {
                        lastPoiFetchLocation = currentLatLng
                        onRideFinished()
                    }
                }
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
//        Log.d("MainActivity", "FloatingOverlayService started.")
    }
    private lateinit var handler: Handler
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateTimeUI()
            updateEarningsUI() // Call earnings update periodically
            handler.postDelayed(this, 60000)
        }
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
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
            Toast.makeText(this, "Please grant notification access to enable ride forwarding.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (!isAccessibilityServiceEnabled(this, com.stoffeltech.ridetracker.services.AccessibilityService::class.java)) {
            Toast.makeText(this, "Please enable RideTracker Accessibility Service in Settings.", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        if (!hasUsageStatsPermission(this)) {
            Toast.makeText(this, "Please grant usage access permission for proper functionality.", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(intent)
        }
        // Check for WRITE_EXTERNAL_STORAGE permission (for API < Q)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    STORAGE_PERMISSION_REQUEST_CODE
                )
            }
        }

        // Set up the toolbar and enable the drawer toggle
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val drawerLayout = findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawer_layout)
        val navView = findViewById<com.google.android.material.navigation.NavigationView>(R.id.nav_view)
        val toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()


// Handle navigation drawer item selections.
        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_settings -> {
                    // Launch SettingsActivity when the "Settings" item is selected.
                    startActivity(Intent(this, SettingsActivity::class.java))
                    drawerLayout.closeDrawers()
                    true
                }
                R.id.nav_shift_information -> {
                    startActivity(Intent(this, ShiftInformationActivity::class.java))
                    drawerLayout.closeDrawers()
                    true
                }
                else -> false
            }
        }

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val isScreenRecordingGranted = prefs.getBoolean("screen_recording_granted", false)
//        Log.d("MainActivity", "isScreenRecordingGranted: $isScreenRecordingGranted")
        if (!isScreenRecordingGranted) {
            // Request permission only if not already granted
            requestScreenCapturePermission()
        } else {
            // ✅ Start ScreenCaptureService immediately if permission was previously granted
            startScreenCaptureService()
        }
        if (ScreenCaptureService.isRunning) {
            startScreenCaptureService()
        } else {
            // If not running, then request permission
            requestScreenCapturePermission()
        }
        // Initialize the map fragment
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        // Initialize Places API once and reuse the client
        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, BuildConfig.GOOGLE_PLACES_API_KEY)
        }
        placesClient = Places.createClient(this)
        // Check for location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            startLocationUpdates()
        }

        val ivUber = findViewById<ImageView>(R.id.ivUber)
        ivUber.setOnClickListener {
            launchApp("com.ubercab.driver", "com.ubercab.carbon.core.CarbonActivity")
        }


        val ivLyft = findViewById<ImageView>(R.id.ivLyft)
        ivLyft.setOnClickListener {
            launchApp("com.lyft.android.driver", "com.lyft.android.driver.app.ui.DriverMainActivity")
        }



        // [NEW] Start the floating overlay service so the overlay is available
        startOverlayService()
        // Load saved intervals (if any)
        TimeTracker.loadIntervals(this)
        // Start tracking once (only call once per app launch)
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
        Log.d("LaunchApp", "Launching app with package: $packageName, intent: $intent")
        if (intent != null && intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "App not installed or cannot be launched", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestScreenCapturePermission() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val screenCaptureIntent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(screenCaptureIntent, SCREEN_CAPTURE_REQUEST_CODE)
    }
    private fun startScreenCaptureService() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val isScreenRecordingGranted = prefs.getBoolean("screen_recording_granted", false)

        if (isScreenRecordingGranted) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            mMap.isMyLocationEnabled = true
        }
        mMap.uiSettings.isZoomControlsEnabled = true
        // Apply dark mode style if needed
        val uiMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            try {
                val success = mMap.setMapStyle(
                    MapStyleOptions.loadRawResourceStyle(this, R.raw.dark_map_style)
                )
                if (!success) { }
            } catch (e: Exception) { }
        }
        // Disable auto camera updates if user interacts
        mMap.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                shouldUpdateCamera = false
            }
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

    // Launch file picker
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
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            recognizeTextFromImage(bitmap)
        }
    }
    private fun recognizeTextFromImage(bitmap: Bitmap) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val extractedText = visionText.text
//                Log.d("RideTracker", "Extracted OCR Text: $extractedText")
            }
            .addOnFailureListener { e ->
                Log.e("RideTracker", "OCR Failed: ${e.message}")
            }
    }

    private var isTrackingLocation = false
    private fun startLocationUpdates() {
        if (isTrackingLocation) return
        val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 10000L
        ).setMinUpdateIntervalMillis(5000L).build()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)

        isTrackingLocation = true  // Set flag to true after starting updates
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isTrackingLocation = false // Reset flag when stopping updates
    }


    // Called when inactivity is detected (ride is finished)
    private fun onRideFinished() {
//        Log.d("MainActivity", "onRideFinished() called")
//        Log.d("MainActivity", "Inactivity detected. Fetching POIs.")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
//                Log.d("MainActivity", "Retrieved last location: ${location.latitude}, ${location.longitude}")
                val currentLatLng = LatLng(location.latitude, location.longitude)

                // Launch a coroutine to call the suspend function fetchNearbyPOIs
                lifecycleScope.launch {
                    val poiResults = fetchNearbyPOIs(currentLatLng, 5.0)
                    val clusters = clusterPlaces(poiResults)
                    runOnUiThread {
                        mMap.clear()

                        // For each cluster, add a green circle overlay...
                        for (cluster in clusters) {
                            mMap.addCircle(
                                CircleOptions()
                                    .center(LatLng(cluster.lat, cluster.lng))
                                    .radius(800.0) // adjust as needed
                                    .strokeColor(getColorWithOpacity(Color.GREEN, 30)) // 30% opaque stroke
                                    .strokeWidth(2f)
                                    .fillColor(getColorWithOpacity(Color.GREEN, 10)) // 20% opaque fill (i.e., more transparent)
                            )
                        }
                    }
                    val aiResponseText = findViewById<TextView>(R.id.aiResponseText)

                    if (clusters.isNotEmpty()) {
                        // Find the cluster (hotspot) with the smallest distance from the current location
                        val nearestCluster = clusters.minByOrNull { cluster ->
                            val hotspotLocation = LatLng(cluster.lat, cluster.lng).toLocation()
                            currentLatLng.toLocation().distanceTo(hotspotLocation)
                        }
                        // Assume currentLatLng is your current location (LatLng)
                        // and nearestCluster has been determined.
                        val btnMapToHotspot = findViewById<Button>(R.id.btnMapToHotspot)

                        if (nearestCluster != null) {
                            val hotspotLatLng = LatLng(nearestCluster.lat, nearestCluster.lng)
                            val currentLocation = currentLatLng.toLocation()
                            val hotspotLocation = hotspotLatLng.toLocation()
                            val distanceMeters = currentLocation.distanceTo(hotspotLocation)
                            val distanceMiles = distanceMeters * 0.000621371 // Convert meters to miles

                            aiResponseText.text = "You are %.2f miles away from the closest hotspot".format(distanceMiles)

                            // Make the navigation button visible
                            btnMapToHotspot.visibility = View.VISIBLE
                            val navigationContainer = findViewById<LinearLayout>(R.id.navigationContainer)
                            val tvInstruction = findViewById<TextView>(R.id.tvNavigationInstruction)
                            val btnNextInstruction = findViewById<Button>(R.id.btnNextInstruction)
                            val btnCancelNavigation = findViewById<Button>(R.id.btnCancelNavigation)
                            btnCancelNavigation.visibility = View.GONE
                            var navigationPolyline: com.google.android.gms.maps.model.Polyline? = null
                            var instructionIndex = 0

                            // ✅ Single Click Listener for btnMapToHotspot
                            btnMapToHotspot.setOnClickListener {
                                val apiKey = BuildConfig.GOOGLE_PLACES_API_KEY

                                lifecycleScope.launch {
                                    val route = DirectionsHelper.fetchRoute(currentLatLng, hotspotLatLng, apiKey)
                                    if (route != null) {
                                        runOnUiThread {
                                            // Clear only previous navigation, keep POIs
                                            navigationPolyline?.remove()

                                            // Draw the new route
                                            navigationPolyline = mMap.addPolyline(
                                                com.google.android.gms.maps.model.PolylineOptions()
                                                    .addAll(route.polylinePoints)
                                                    .color(Color.BLUE)
                                                    .width(10f)
                                            )

                                            // Animate the camera to show the route
                                            val boundsBuilder = com.google.android.gms.maps.model.LatLngBounds.Builder()
                                            route.polylinePoints.forEach { boundsBuilder.include(it) }
                                            val bounds = boundsBuilder.build()
                                            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))

                                            // ✅ Display Navigation UI
                                            navigationContainer.visibility = View.VISIBLE
                                            btnCancelNavigation.visibility = View.VISIBLE // Show cancel button

                                            // ✅ Handle Instructions
                                            if (route.steps.isNotEmpty()) {
                                                tvInstruction.text = route.steps[instructionIndex].instruction
                                            } else {
                                                tvInstruction.text = "No navigation instructions available."
                                            }

                                            btnNextInstruction.setOnClickListener {
                                                if (++instructionIndex < route.steps.size) {
                                                    tvInstruction.text = route.steps[instructionIndex].instruction
                                                    mMap.animateCamera(
                                                        CameraUpdateFactory.newLatLngZoom(route.steps[instructionIndex].startLocation, 17f)
                                                    )
                                                } else {
                                                    Toast.makeText(this@MainActivity, "End of instructions.", Toast.LENGTH_SHORT).show()
                                                    instructionIndex = 0
                                                }
                                            }

                                            // ✅ Cancel Navigation
                                            btnCancelNavigation.setOnClickListener {
                                                navigationPolyline?.remove() // Remove route
                                                btnCancelNavigation.visibility = View.GONE // Hide cancel button
                                                navigationContainer.visibility = View.GONE // Hide navigation UI
                                                btnNextInstruction.text = "Navigation canceled."
                                            }
                                        }
                                    } else {
                                        runOnUiThread {
                                            Toast.makeText(this@MainActivity, "Unable to fetch route", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        } else {
                            aiResponseText.text = "No hotspots found nearby."
                            findViewById<Button>(R.id.btnMapToHotspot).visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Load saved intervals and update the UI immediately.
        TimeTracker.loadIntervals(this)
        updateTimeUI()
        updateEarningsUI()  // Update earnings row on resume
        updateEarningsRatesUI()
        handler = Handler(Looper.getMainLooper())
        handler.post(updateRunnable)
        if (AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES) {
            ivModeSwitch.setImageResource(R.drawable.ic_moon)
        } else {
            ivModeSwitch.setImageResource(R.drawable.ic_sun)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    @SuppressLint("SetTextI18n")
    private fun updateTimeUI() {
        // Get total time for today in milliseconds
        val totalMillis = TimeTracker.getTotalTimeForToday()
        // Convert milliseconds to hours and minutes
        val hours = totalMillis / (1000 * 60 * 60)
        val minutes = (totalMillis / (1000 * 60)) % 60
        val timeText = "${hours}h ${minutes}m"
        // Update the TextView for time
        val tvTimeTravelled: TextView = findViewById(R.id.tvTimeTravelled)
        tvTimeTravelled.text = "Time: $timeText"
    }

    private fun updateEarningsUI() {
        // Load revenue intervals from RevenueTracker.
        RevenueTracker.loadIntervals(this)
        val dailyRevenue = RevenueTracker.getDailyRevenue()
        val weeklyRevenue = RevenueTracker.getWeeklyRevenue()
        val monthlyRevenue = RevenueTracker.getMonthlyRevenue()
        val yearlyRevenue = RevenueTracker.getYearlyRevenue()

        // Update the earnings TextViews.
        tvDailyEarning.text = "Daily: $%.2f".format(dailyRevenue)
        tvWeeklyEarning.text = "Weekly: $%.2f".format(weeklyRevenue)
        tvMonthlyEarning.text = "Monthly: $%.2f".format(monthlyRevenue)
        tvYearlyEarning.text = "Yearly: $%.2f".format(yearlyRevenue)

        // For debugging:
        Log.d("MainActivity", "Earnings updated: Daily $dailyRevenue, Weekly $weeklyRevenue, Monthly $monthlyRevenue, Yearly $yearlyRevenue")
    }

    private fun updateEarningsRatesUI() {
        // Get the daily earnings from RevenueTracker (should be updated via OCR elsewhere)
        RevenueTracker.loadIntervals(this)
        val dailyEarnings = RevenueTracker.getDailyRevenue()  // e.g., 395.75

        // Get the current time text from tvTimeTravelled
        val timeText = tvTimeTravelled.text.toString()  // Expected format: "Time: Xh Ym"
        // Use a regex to extract hours and minutes
        val timeRegex = Regex("Time:\\s*(\\d+)h\\s*(\\d+)m")
        val timeMatch = timeRegex.find(timeText)
        val hours = if (timeMatch != null) {
            val h = timeMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val m = timeMatch.groupValues[2].toDoubleOrNull() ?: 0.0
            h + (m / 60.0)
        } else {
            0.0
        }

        // Get the distance text from tvDistanceTravelled
        val distanceText = tvDistanceTravelled.text.toString()  // Expected format: "Distance: X.XX miles"
        val distanceRegex = Regex("Distance:\\s*([\\d.]+)\\s*miles")
        val distanceMatch = distanceRegex.find(distanceText)
        val miles = distanceMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

        // Calculate earnings per hour and per mile
        val earningsPerHour = if (hours > 0) dailyEarnings / hours else 0.0
        val earningsPerMile = if (miles > 0) dailyEarnings / miles else 0.0

        // Update the respective TextViews with formatted values.
        tvEarningPerHour.text = "$/Hour: %.2f".format(earningsPerHour)
        tvEarningPerMile.text = "$/Mile: %.2f".format(earningsPerMile)

        // Debug logs for verification:
        Log.d("MainActivity", "Parsed Time: $hours hours from '$timeText'")
        Log.d("MainActivity", "Parsed Distance: $miles miles from '$distanceText'")
        Log.d("MainActivity", "Daily Earnings: $dailyEarnings, Earnings per Hour: $earningsPerHour, Earnings per Mile: $earningsPerMile")
    }




    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                // ✅ Save permission state
                val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("screen_recording_granted", true).apply()

                // Start the ScreenCaptureService with the result data
                val serviceIntent = Intent(this, ScreenCaptureService::class.java)
                serviceIntent.putExtra("resultCode", resultCode)
                serviceIntent.putExtra("data", data)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
//                Log.e("MainActivity", "Screen capture permission denied or no data")
            }
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            stopService(Intent(this, ScreenCaptureService::class.java))
            // Stop tracking session when leaving app
            TimeTracker.stopTracking(this)
            DistanceTracker.stopTracking(this)
            updateEarningsUI()
//            Log.d("MainActivity", "ScreenCaptureService stopped because app is closing.")
        }
    }
}


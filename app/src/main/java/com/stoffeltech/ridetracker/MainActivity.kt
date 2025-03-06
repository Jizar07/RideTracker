package com.stoffeltech.ridetracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.stoffeltech.ridetracker.future.LoginActivity
import com.stoffeltech.ridetracker.future.UberApiTest
import com.stoffeltech.ridetracker.services.FloatingOverlayService
import com.stoffeltech.ridetracker.services.FloatingOverlayService.Companion.hideOverlay
import com.stoffeltech.ridetracker.services.ScreenshotService
//import com.stoffeltech.ridetracker.services.ScreenCaptureService
import com.stoffeltech.ridetracker.services.clusterPlaces
import com.stoffeltech.ridetracker.services.fetchNearbyPOIs
import com.stoffeltech.ridetracker.utils.DirectionsHelper
import com.stoffeltech.ridetracker.utils.DistanceTracker
import com.stoffeltech.ridetracker.utils.RevenueTracker
import com.stoffeltech.ridetracker.utils.TimeTracker
import com.stoffeltech.ridetracker.media.MediaProjectionLifecycleManager // NEW: centralized MP manager
import com.stoffeltech.ridetracker.services.AccessibilityService
import com.stoffeltech.ridetracker.services.ScreenCaptureService
import com.stoffeltech.ridetracker.uber.UberParser
import com.stoffeltech.ridetracker.utils.LogHelper
import kotlinx.coroutines.Dispatchers
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
    private lateinit var placesClient: PlacesClient
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
                    val cameraPosition = com.google.android.gms.maps.model.CameraPosition.Builder()
                        .target(currentLatLng)
                        .zoom(18f)
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
            Toast.makeText(this, "Please grant notification access...", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        // Disabled for testing: Accessibility Service check
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

        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_settings -> {
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

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, BuildConfig.GOOGLE_PLACES_API_KEY)
        }
        placesClient = Places.createClient(this)

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
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        val screenCaptureIntent = mediaProjectionManager.createScreenCaptureIntent()
        // Uncomment the following line to request permission:
        screenCaptureLauncher.launch(screenCaptureIntent)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
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
            }
        }
        // ---------------- REQUEST MEDIA PROJECTION PERMISSION - Start ----------------
        // Check if MediaProjection is valid. If not, request screen capture permission.
        if (!com.stoffeltech.ridetracker.media.MediaProjectionLifecycleManager.isMediaProjectionValid()) {
            requestScreenCapturePermission()  // This method exists in MainActivity and initiates the permission request.
            Log.d("MainActivity", "Requested MediaProjection permission after map load.")
        }
// ---------------- REQUEST MEDIA PROJECTION PERMISSION - End ----------------

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
    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                // Use the centralized MediaProjectionLifecycleManager to start MediaProjection.
                com.stoffeltech.ridetracker.media.MediaProjectionLifecycleManager.startMediaProjection(this, result.resultCode, data)
                Log.d("MainActivity", "MediaProjection set successfully")
                // ---- Launch OCR within a coroutine ----
                lifecycleScope.launch(Dispatchers.IO) {
                    ScreenCaptureService.continuouslyCaptureAndSendOcr(
                        this@MainActivity,
                        MediaProjectionLifecycleManager.getMediaProjection()!!
                    ) { ocrText ->
//                        Log.d("MainActivity", "OCR callback fired, received text: $ocrText")

                        // Retrieve the valid ride types from UberParser
                        val rideTypes = UberParser.getRideTypes()
                        var filteredText = ocrText
                        // Look for the earliest occurrence of any ride type (ignoring case)
                        var earliestIndex = Int.MAX_VALUE
                        for (ride in rideTypes) {
                            val idx = ocrText.indexOf(ride, ignoreCase = true)
                            if (idx != -1 && idx < earliestIndex) {
                                earliestIndex = idx
                            }
                        }
                        if (earliestIndex != Int.MAX_VALUE) {
                            filteredText = ocrText.substring(earliestIndex)
                        }
//                        Log.d("MainActivity", "Filtered OCR text (from first ride type onward): $filteredText")

                        // Forward the filtered text to UberParser for further processing
                        lifecycleScope.launch(Dispatchers.IO) {
                            UberParser.processUberRideRequest(filteredText, this@MainActivity)
                        }
                    }
                }

                // ------------------------------------------------

                startOverlayService()
            } ?: run {
                Log.e("MainActivity", "Screen capture permission granted but result.data is null")
            }
        } else {
            Log.e("MainActivity", "Screen capture permission denied")
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
            .addOnSuccessListener { visionText ->
                val extractedText = visionText.text
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
                        mMap.clear()
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
                            val navigationContainer = findViewById<LinearLayout>(R.id.navigationContainer)
                            val tvInstruction = findViewById<TextView>(R.id.tvNavigationInstruction)
                            val btnNextInstruction = findViewById<Button>(R.id.btnNextInstruction)
                            val btnCancelNavigation = findViewById<Button>(R.id.btnCancelNavigation)
                            btnCancelNavigation.visibility = View.GONE
                            var navigationPolyline: com.google.android.gms.maps.model.Polyline? = null
                            var instructionIndex = 0
                            btnMapToHotspot.setOnClickListener {
                                val apiKey = BuildConfig.GOOGLE_PLACES_API_KEY
                                lifecycleScope.launch {
                                    val route = DirectionsHelper.fetchRoute(currentLatLng, hotspotLatLng, apiKey)
                                    if (route != null) {
                                        runOnUiThread {
                                            navigationPolyline?.remove()
                                            navigationPolyline = mMap.addPolyline(
                                                com.google.android.gms.maps.model.PolylineOptions()
                                                    .addAll(route.polylinePoints)
                                                    .color(Color.BLUE)
                                                    .width(10f)
                                            )
                                            val boundsBuilder = com.google.android.gms.maps.model.LatLngBounds.Builder()
                                            route.polylinePoints.forEach { boundsBuilder.include(it) }
                                            val bounds = boundsBuilder.build()
                                            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
                                            navigationContainer.visibility = View.VISIBLE
                                            btnCancelNavigation.visibility = View.VISIBLE
                                            if (route.steps.isNotEmpty()) {
                                                tvInstruction.text = route.steps[instructionIndex].instruction
                                            } else {
                                                tvInstruction.text = "No navigation instructions available."
                                            }
                                            btnNextInstruction.setOnClickListener {
                                                if (++instructionIndex < route.steps.size) {
                                                    tvInstruction.text = route.steps[instructionIndex].instruction
                                                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(route.steps[instructionIndex].startLocation, 17f))
                                                } else {
                                                    Toast.makeText(this@MainActivity, "End of instructions.", Toast.LENGTH_SHORT).show()
                                                    instructionIndex = 0
                                                }
                                            }
                                            btnCancelNavigation.setOnClickListener {
                                                navigationPolyline?.remove()
                                                btnCancelNavigation.visibility = View.GONE
                                                navigationContainer.visibility = View.GONE
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
        }
        // Only stop MediaProjection if the activity is finishing and not merely undergoing a configuration change.
        if (isFinishing && !isChangingConfigurations) {
            // Instead of directly stopping MediaProjection here, we now delegate to our centralized manager.
            com.stoffeltech.ridetracker.media.MediaProjectionLifecycleManager.stopMediaProjection()
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
            if (LOG_ENABLED) android.util.Log.d(tag, message)
        }
        fun logError(tag: String, message: String, throwable: Throwable?) {
            if (LOG_ENABLED) android.util.Log.e(tag, message, throwable)
        }
    }
}

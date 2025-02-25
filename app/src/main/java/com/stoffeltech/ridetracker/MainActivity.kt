package com.stoffeltech.ridetracker

import com.stoffeltech.ridetracker.BuildConfig
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
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import com.stoffeltech.ridetracker.utils.DirectionsHelper
import android.provider.Settings
import android.view.WindowManager
import com.stoffeltech.ridetracker.hasUsageStatsPermission
import android.service.notification.NotificationListenerService
import android.text.TextUtils

fun isNotificationAccessEnabled(context: Context): Boolean {
    val pkgName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return !TextUtils.isEmpty(flat) && flat.contains(pkgName)
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
    // Convert percentage to an alpha value between 0 and 255.
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

    // Flag to control automatic camera updates
    private var shouldUpdateCamera = true

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

                // Update the map camera if allowed.
                if (shouldUpdateCamera) {
                    mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
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
                    Log.d("MainActivity", "No significant movement. Inactivity duration: ${currentTime - lastMovementTime} ms")
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
        Log.d("MainActivity", "FloatingOverlayService started.")
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (!isNotificationAccessEnabled(this)) {
            Toast.makeText(this, "Please grant notification access to enable ride forwarding.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        if (!hasUsageStatsPermission(this)) {
            Toast.makeText(
                this,
                "Please grant usage access permission for proper functionality.",
                Toast.LENGTH_LONG
            ).show()
            // Launch the Usage Access Settings so the user can enable it.
            val intent = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
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
                    drawerLayout.closeDrawers()  // Close the drawer after selection
                    true  // Indicate that we handled this selection
                }
                else -> false  // For other items, do nothing here.
            }
        }

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val isScreenRecordingGranted = prefs.getBoolean("screen_recording_granted", false)
        Log.d("MainActivity", "isScreenRecordingGranted: $isScreenRecordingGranted")
        if (!isScreenRecordingGranted) {
            // Request permission only if not already granted
            requestScreenCapturePermission()
        } else {
            // ✅ Start ScreenCaptureService immediately if permission was previously granted
            startScreenCaptureService()
        }
        if (ScreenCaptureService.isRunning) {
            // Service is already active; no need to request permission again.
            Log.d("MainActivity", "ScreenCaptureService is running; skipping permission request.")
            startScreenCaptureService() // Optionally, ensure it's running
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

        // [NEW] Start the floating overlay service so the overlay is available
        startOverlayService()
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
                if (!success) {
                    Log.e("MainActivity", "Style parsing failed.")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Can't find style. Error: ", e)
            }
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

    private var isTrackingLocation = false  // Flag to track location updates

    private fun startLocationUpdates() {
        if (isTrackingLocation) return // Prevents duplicate requests

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
        Log.d("MainActivity", "onRideFinished() called")
        Log.d("MainActivity", "Inactivity detected. Fetching POIs.")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                Log.d("MainActivity", "Retrieved last location: ${location.latitude}, ${location.longitude}")
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

                            btnCancelNavigation.visibility = View.GONE // Hide initially

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
                            btnMapToHotspot.visibility = View.GONE
                        }



                    }
                }
            } else {
                Log.d("MainActivity", "Current location is null.")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates() // Uses the new method we added
    }
    override fun onResume() {
        super.onResume()
        startLocationUpdates() // Restart location tracking when resuming
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
                Log.e("MainActivity", "Screen capture permission denied or no data")
            }
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            stopService(Intent(this, ScreenCaptureService::class.java))
            Log.d("MainActivity", "ScreenCaptureService stopped because app is closing.")
        }
    }
}


package com.stoffeltech.ridetracker

import android.Manifest
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
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

    private fun startLocationUpdates() {
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
                }
            } else {
                Log.d("MainActivity", "Current location is null.")
            }
        }
        // NEW: Request screen capture permission
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val screenCaptureIntent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(screenCaptureIntent, SCREEN_CAPTURE_REQUEST_CODE)
    }

    override fun onPause() {
        super.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
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

}


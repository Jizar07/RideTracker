// File: DirectionsHelper.kt
package com.stoffeltech.ridetracker.utils

import android.util.Log
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

object DirectionsHelper {

    // Existing decodePoly function remains unchanged.
    fun decodePoly(encoded: String): List<LatLng> {
        val poly = mutableListOf<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0
        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            poly.add(LatLng(lat / 1E5, lng / 1E5))
        }
        return poly
    }

    // Updated fetchRoute function using OSRM instead of Mapbox.
    // Replace your current fetchRoute function (starting around line 40) with the following code:
    // In DirectionsHelper.kt, update your fetchRoute function (e.g., starting at line 40) to:

    // In DirectionsHelper.kt, update the fetchRoute function (e.g., starting around line 40) with the following code:
    suspend fun fetchRoute(origin: LatLng, destination: LatLng): Route? {
        return withContext(Dispatchers.IO) {
            // Build the OSRM API URL using HTTPS
            val url = "https://router.project-osrm.org/route/v1/driving/" +
                    "${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}" +
                    "?overview=full&geometries=polyline&steps=true"
            Log.d("DirectionsHelper", "OSRM Request URL: $url")
            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()

            try {
                val response = client.newCall(request).execute()
                Log.d("DirectionsHelper", "OSRM response code: ${response.code}")
                val jsonString = response.body?.string()
                Log.d("DirectionsHelper", "OSRM response: $jsonString")
                if (jsonString != null) {
                    val jsonObj = JSONObject(jsonString)
                    val routes = jsonObj.getJSONArray("routes")
                    if (routes.length() > 0) {
                        val routeObj = routes.getJSONObject(0)
                        // OSRM returns geometry directly as a polyline string.
                        val encodedPoints = routeObj.getString("geometry")
                        val polylinePoints = decodePoly(encodedPoints)

                        // Parse step-by-step instructions from the first leg.
                        val legs = routeObj.getJSONArray("legs")
                        val stepsList = mutableListOf<RouteStep>()
                        if (legs.length() > 0) {
                            val leg = legs.getJSONObject(0)
                            val steps = leg.getJSONArray("steps")
                            for (i in 0 until steps.length()) {
                                val stepObj = steps.getJSONObject(i)
                                // Retrieve the maneuver JSON object.
                                val maneuver = stepObj.getJSONObject("maneuver")
                                // Safely obtain the "instruction" value; default to empty string if missing.
                                val instruction = if (maneuver.has("instruction")) {
                                    maneuver.getString("instruction")
                                } else {
                                    ""
                                }
                                // OSRM returns location as an array: [longitude, latitude]
                                val locationArray = maneuver.getJSONArray("location")
                                val startLocation = LatLng(locationArray.getDouble(1), locationArray.getDouble(0))
                                // Use startLocation as a placeholder for endLocation; adjust if needed.
                                stepsList.add(RouteStep(instruction, startLocation, startLocation))
                            }
                        }
                        return@withContext Route(polylinePoints, stepsList)
                    } else {
                        Log.e("DirectionsHelper", "No routes found in OSRM response.")
                    }
                }
                null
            } catch (e: IOException) {
                Log.e("DirectionsHelper", "Error fetching route: ${e.message}")
                null
            }
        }
    }
}

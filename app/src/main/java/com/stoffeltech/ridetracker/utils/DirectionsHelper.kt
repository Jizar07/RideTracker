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

    // Function to decode the polyline string from the Directions API into a list of LatLng points.
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

    // Function to fetch directions from the Google Directions API.
    // This function takes the origin, destination, and your API key as parameters,
    // then returns a list of LatLng points representing the route.
    suspend fun fetchDirections(origin: LatLng, destination: LatLng, apiKey: String): List<LatLng>? {
        return withContext(Dispatchers.IO) {
            // Build the URL for the directions request
            val url = "https://maps.googleapis.com/maps/api/directions/json?" +
                    "origin=${origin.latitude},${origin.longitude}" +
                    "&destination=${destination.latitude},${destination.longitude}" +
                    "&mode=driving&key=$apiKey"
            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()

            try {
                val response = client.newCall(request).execute()
                val jsonString = response.body?.string()
                if (jsonString != null) {
                    val jsonObj = JSONObject(jsonString)
                    val routes = jsonObj.getJSONArray("routes")
                    if (routes.length() > 0) {
                        val route = routes.getJSONObject(0)
                        val overviewPolyline = route.getJSONObject("overview_polyline")
                        val encodedPoints = overviewPolyline.getString("points")
                        return@withContext decodePoly(encodedPoints)
                    }
                }
                null
            } catch (e: IOException) {
                Log.e("DirectionsHelper", "Error fetching directions: ${e.message}")
                null
            }
        }
    }
    // New function: fetchRoute returns a Route (polyline + turn-by-turn steps)
    suspend fun fetchRoute(origin: LatLng, destination: LatLng, apiKey: String): Route? {
        return withContext(Dispatchers.IO) {
            // Build the URL for driving directions
            val url = "https://maps.googleapis.com/maps/api/directions/json?" +
                    "origin=${origin.latitude},${origin.longitude}" +
                    "&destination=${destination.latitude},${destination.longitude}" +
                    "&mode=driving&key=$apiKey"
            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()
            try {
                val response = client.newCall(request).execute()
                val jsonString = response.body?.string()
                if (jsonString != null) {
                    val jsonObj = JSONObject(jsonString)
                    val routes = jsonObj.getJSONArray("routes")
                    if (routes.length() > 0) {
                        val routeObj = routes.getJSONObject(0)

                        // Get the overall polyline for the route
                        val overviewPolyline = routeObj.getJSONObject("overview_polyline")
                        val encodedPoints = overviewPolyline.getString("points")
                        val polylinePoints = decodePoly(encodedPoints)

                        // Parse step-by-step instructions from the first leg of the route
                        val legs = routeObj.getJSONArray("legs")
                        val stepsList = mutableListOf<RouteStep>()
                        if (legs.length() > 0) {
                            val leg = legs.getJSONObject(0)
                            val steps = leg.getJSONArray("steps")
                            for (i in 0 until steps.length()) {
                                val stepObj = steps.getJSONObject(i)
                                val htmlInstruction = stepObj.getString("html_instructions")
                                // Remove HTML tags from the instruction
                                val instruction = htmlInstruction.replace(Regex("<.*?>"), "")
                                val startLocObj = stepObj.getJSONObject("start_location")
                                val endLocObj = stepObj.getJSONObject("end_location")
                                val startLocation = LatLng(startLocObj.getDouble("lat"), startLocObj.getDouble("lng"))
                                val endLocation = LatLng(endLocObj.getDouble("lat"), endLocObj.getDouble("lng"))
                                stepsList.add(RouteStep(instruction, startLocation, endLocation))
                            }
                        }
                        return@withContext Route(polylinePoints, stepsList)
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

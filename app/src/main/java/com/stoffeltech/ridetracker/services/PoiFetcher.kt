package com.stoffeltech.ridetracker.services

import android.net.Uri
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.stoffeltech.ridetracker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

data class PlaceResult(
    val name: String,
    val lat: Double,
    val lng: Double,
    val types: List<String>?
)
// A simple data class representing a cluster of POIs.
data class Cluster(
    val lat: Double,
    val lng: Double,
    val count: Int
)

suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) return
            cont.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { response.close() }
        }
    })
    cont.invokeOnCancellation {
        try {
            cancel()
        } catch (ex: Exception) { }
    }
}

suspend fun fetchNearbyPOIs(currentLocation: LatLng, radiusMiles: Double): List<PlaceResult> {
    return withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val radiusMeters = (radiusMiles * 1609.34).toInt()
            val lat = currentLocation.latitude
            val lon = currentLocation.longitude

            // Overpass QL query to fetch multiple POI categories in one go.
            val query = """
                [out:json];
                (
                  node["amenity"="restaurant"](around:$radiusMeters,$lat,$lon);
                  node["amenity"="bar"](around:$radiusMeters,$lat,$lon);
                  node["shop"="mall"](around:$radiusMeters,$lat,$lon);
                  node["shop"="department_store"](around:$radiusMeters,$lat,$lon);
                  node["amenity"="university"](around:$radiusMeters,$lat,$lon);
                  node["aeroway"="aerodrome"](around:$radiusMeters,$lat,$lon);
                  node["amenity"="nightclub"](around:$radiusMeters,$lat,$lon);
                  node["amenity"="car_rental"](around:$radiusMeters,$lat,$lon);
                  node["amenity"="casino"](around:$radiusMeters,$lat,$lon);
                  node["amenity"="courthouse"](around:$radiusMeters,$lat,$lon);
                  node["amenity"="pub"](around:$radiusMeters,$lat,$lon);
                );
                out body;
            """.trimIndent()

            val url = "https://overpass-api.de/api/interpreter?data=${Uri.encode(query)}"
//            Log.d("PoiFetcher", "Fetching URL: $url")
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).awaitResponse()
            val allResults = mutableListOf<PlaceResult>()
            if (response.isSuccessful) {
                val jsonData = response.body?.string()
                if (jsonData != null) {
                    val jsonObj = JSONObject(jsonData)
                    val elements = jsonObj.getJSONArray("elements")
                    for (i in 0 until elements.length()) {
                        val element = elements.getJSONObject(i)
                        val tags = element.optJSONObject("tags")
                        // Use the 'name' tag if available; otherwise, fallback to "Unknown"
                        val name = tags?.optString("name") ?: "Unknown"
                        val lat = element.getDouble("lat")
                        val lon = element.getDouble("lon")
                        // Use available tags to extract types (e.g., "amenity" or "shop")
                        val types = mutableListOf<String>()
                        tags?.let {
                            if (it.has("amenity")) types.add(it.getString("amenity"))
                            if (it.has("shop")) types.add(it.getString("shop"))
                        }
                        allResults.add(PlaceResult(name, lat, lon, types))
                    }
                }
            }
            // Remove duplicate entries based on name
            allResults.distinctBy { it.name }
        } catch (e: Exception) {
            Log.e("PoiFetcher", "Error fetching nearby POIs", e)
            emptyList()
        }
    }
}

/**
 * Clusters the provided list of PlaceResult objects by grouping those that are within roughly half a mile of each other.
 * Only clusters containing more than three places are returned.
 */
fun clusterPlaces(places: List<PlaceResult>): List<Cluster> {
    val clusterRadiusMiles = 1 // for half a mile; adjust as needed
    val factor = clusterRadiusMiles / 69.0
    val clusters = mutableMapOf<Pair<Int, Int>, MutableList<PlaceResult>>()
    for (place in places) {
        // Generate a key by dividing the coordinates by the factor and converting to integer.
        val key = Pair((place.lat / factor).toInt(), (place.lng / factor).toInt())
        clusters.getOrPut(key) { mutableListOf() }.add(place)
    }
    // Filter clusters to only include those with more than 3 places and compute an average coordinate for the cluster.
    return clusters.filter { it.value.size > 3 }
        .map { entry ->
            val list = entry.value
            val avgLat = list.map { it.lat }.average()
            val avgLng = list.map { it.lng }.average()
            Cluster(avgLat, avgLng, list.size)
        }
}
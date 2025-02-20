package com.stoffeltech.ridetracker

import android.util.Log
import com.google.android.gms.maps.model.LatLng
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
import kotlin.coroutines.resume
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
            val locationStr = "${currentLocation.latitude},${currentLocation.longitude}"
            val placeCategories = listOf(
                "restaurant",
                "bar",
                "shopping_mall",
                "department_store",
                "university",
                "airport",
                "night_club",
                "car_rental",
                "casino",
                "courthouse",
                "pub"
            )
            val allResults = mutableListOf<PlaceResult>()
            for (category in placeCategories) {
                val url = "https://maps.googleapis.com/maps/api/place/nearbysearch/json?" +
                        "location=$locationStr" +
                        "&radius=$radiusMeters" +
                        "&type=$category" +
                        "&opennow=true" +
                        "&key=${BuildConfig.GOOGLE_PLACES_API_KEY}"
                Log.d("PoiFetcher", "Fetching URL: $url")
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).awaitResponse()
                if (response.isSuccessful) {
                    val jsonData = response.body?.string()
                    if (jsonData != null) {
                        val jsonObj = JSONObject(jsonData)
                        val results = jsonObj.getJSONArray("results")
                        for (i in 0 until results.length()) {
                            val place = results.getJSONObject(i)
                            val name = place.getString("name")
                            val geometry = place.getJSONObject("geometry")
                            val locationObj = geometry.getJSONObject("location")
                            val lat = locationObj.getDouble("lat")
                            val lng = locationObj.getDouble("lng")
                            val typesList = mutableListOf<String>()
                            if (place.has("types")) {
                                val typesArray = place.getJSONArray("types")
                                for (j in 0 until typesArray.length()) {
                                    typesList.add(typesArray.getString(j))
                                }
                            }
                            allResults.add(PlaceResult(name, lat, lng, typesList))
                        }
                    }
                }
            }
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
    return clusters.filter { it.value.size > 5 }
        .map { entry ->
            val list = entry.value
            val avgLat = list.map { it.lat }.average()
            val avgLng = list.map { it.lng }.average()
            Cluster(avgLat, avgLng, list.size)
        }
}
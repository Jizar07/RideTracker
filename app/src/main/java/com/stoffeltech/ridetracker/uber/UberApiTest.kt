package com.stoffeltech.ridetracker.uber

import android.content.Context
import android.util.Log
import android.widget.TextView
import com.stoffeltech.ridetracker.R
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject

object UberApiTest {
//    private const val TEST_URL = "https://cn-geo1.uber.com"
    // ----- HARDCODED ACCESS TOKEN -----
    // This constant holds the token returned from Uber.
    private const val HARDCODED_ACCESS_TOKEN = "IA.VUNmGAAAAAAAEgASAAAABwAIAAwAAAAAAAAAEgAAAAAAAAGwAAAAFAAAAAAADgAQAAQAAAAIAAwAAAAOAAAAhAAAABwAAAAEAAAAEAAAAAnV6nWnxrrEvEjcudU-0qdfAAAAJPFB0nsxzUwrVGKbJe_2By9a3buXM1JQJq5LNIHJe6nGP7qtpGgfUBXAKlPQF7fZb06BcIrgOI7a5QYoH0VYbR7Aa-qioWDvqasVHn0uBPPr-q-O4UTbQXKmF8_7eNEADAAAAHmGhk2_w6Ph3eqrKCQAAABiMGQ4NTgwMy0zOGEwLTQyYjMtODA2ZS03YTRjZjhlMTk2ZWU"


    // ----- UberApiTest.kt: Fetch Current Ride Request Data -----
    // Place this near the top of the file:
    private const val RIDE_REQUEST_URL = "https://api.uber.com/v1.2/requests/current"

    // Function to fetch current ride request data
    // ----- UberApiTest.kt: Updated fetchCurrentRideRequest() with a callback -----
    fun fetchCurrentRideRequest(context: Context, callback: (rideInfoString: String) -> Unit) {
        Log.d("UberApiTest", "Entering fetchCurrentRideRequest()")

    val prefs = context.getSharedPreferences("uber_prefs", Context.MODE_PRIVATE)
    val token = prefs.getString("API_TOKEN", null)
    Log.d("UberApiTest", "Retrieved token: $token")

        if (token.isNullOrEmpty()) {
            Log.e("UberApiTest", "No API token found, cannot fetch ride request data!")
            callback("No API token available")
            return
        }

        // Create an OkHttp client and build the request.
        // Create a logging interceptor for OkHttp
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        // Build the OkHttpClient with the logging interceptor
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        val request = Request.Builder()
            .url(RIDE_REQUEST_URL)  // "https://api.uber.com/v1/requests/current"
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .build()

    Log.d("UberApiTest", "Sending request: $request") // Log the request details

    Thread {
        try {
            val response = client.newCall(request).execute()
            Log.d("UberApiTest", "HTTP Response Code: ${response.code}")
            val bodyString = response.body?.string()
            Log.d("UberApiTest", "Raw Response Body: $bodyString")

            if (response.code == 204 || bodyString.isNullOrEmpty()) {
                Log.d("UberApiTest", "No active ride request found.")
                callback("No active ride request")
                return@Thread
            }

            try {
                val json = org.json.JSONObject(bodyString)
                Log.d("UberApiTest", "Parsed JSON: $json")
                val status = json.optString("status", "unknown")
                val requestId = json.optString("request_id", "N/A")
                val pickupObj = json.optJSONObject("pickup")
                val pickupAddress = pickupObj?.optString("address", "N/A") ?: "N/A"
                val dropoffObj = json.optJSONObject("dropoff")
                val dropoffAddress = dropoffObj?.optString("address", "N/A") ?: "N/A"

                val rideInfoString = """
                    Status: $status
                    Request ID: $requestId
                    Pickup: $pickupAddress
                    Dropoff: $dropoffAddress
                """.trimIndent()

                Log.d("UberApiTest", "Formatted ride info: $rideInfoString")
                callback(rideInfoString)
            } catch (jsonEx: Exception) {
                Log.e("UberApiTest", "JSON parsing error: ${jsonEx.message}", jsonEx)
                callback("Error parsing ride data")
            }
        } catch (e: Exception) {
            Log.e("UberApiTest", "Error fetching ride request data: ${e.message}", e)
            callback("Error fetching ride data")
        }
    }.start()
}

    // The testToken() function remains commented out.
    // fun testToken(context: Context) { ... }
}

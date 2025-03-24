// ----- LocationSender.kt - sends location data to your REST endpoint -----
package com.stoffeltech.ridetracker.utils

import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

object LocationSender {
    // Create a shared OkHttpClient instance.
    private val client = OkHttpClient()

    /**
     * Sends the current location (latitude and longitude) to the custom REST endpoint.
     *
     * @param latitude The latitude of the device.
     * @param longitude The longitude of the device.
     * @param callback An optional OkHttp Callback to handle response or error.
     */
    fun sendLocation(latitude: Double, longitude: Double, callback: Callback? = null) {
        // Build the URL with query parameters for latitude and longitude.
        val url = "https://www.stoffeltech.com/wp-json/driving/v1/location?latitude=${latitude}&longitude=${longitude}"

        // Build an HTTP POST request.
        // Since we're sending parameters via the URL, the POST body is empty.
        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .build()

        // Enqueue the request asynchronously.
        client.newCall(request).enqueue(callback ?: object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Log the error (replace with your logging mechanism)
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        // Handle unexpected response, for example by logging it.
                        throw IOException("Unexpected response code: $response")
                    }
                    // Optionally process the response
//                    println("Location update response: ${response.body?.string()}")
                }
            }
        })
    }
}

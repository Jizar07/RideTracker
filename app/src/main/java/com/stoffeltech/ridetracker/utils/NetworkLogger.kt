package com.stoffeltech.ridetracker.utils

import android.util.Log
import okhttp3.*
import java.io.IOException

object NetworkLogger {
    // Initialize the OkHttpClient instance.
    private val client = OkHttpClient()
    // Replace with the actual URL of your WordPress endpoint.
    private const val LOG_ENDPOINT = "https://stoffeltech.com/ridetracker/wp-json/ridetracker/v1/logs"

    // Function to send a log message.
    fun sendLog(message: String) {
        // Build a POST form body with the "message" parameter.
        val formBody = FormBody.Builder()
            .add("message", message)
            .build()

        // Build the HTTP request.
        val request = Request.Builder()
            .url(LOG_ENDPOINT)
            .post(formBody)
            .build()

        // Enqueue the request asynchronously.
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("NetworkLogger", "Failed to send log: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e("NetworkLogger", "Error sending log: ${response.message}")
                }
                response.close()
            }
        })
    }
}

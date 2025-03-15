package com.stoffeltech.ridetracker.uber

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

/**
 * Authenticates with Uber's server using the provided credentials.
 *
 * @param username The Uber driver username.
 * @param password The Uber driver password.
 * @param callback A callback function that receives the API token (or null on failure).
 */
fun authenticateUber(username: String, password: String, callback: (String?) -> Unit) {
    // Placeholder endpoint; replace with the correct Uber authentication URL
    val url = "https://auth.uber.com/login/session"

    // Build the form parameters. You may need to add additional fields (device info, etc.)
    val formBody = FormBody.Builder()
        .add("username", username)
        .add("password", password)
        .add("device", "android") // Example parameter; adjust if necessary
        .add("uberClientId", "com.ubercab.driver")
        // Add other required parameters here...
        .build()

    // Build the request and add example headers.
    val request = Request.Builder()
        .url(url)
        .post(formBody)
        .header("x-uber-client-id", "com.ubercab.driver")
        .header("x-uber-client-version", "1.278.0") // Adjust version as needed
        .header("x-uber-device", "android")
        .header("x-uber-device-id", "YOUR_DEVICE_ID") // Replace with your device ID or generate one
        // Add any additional headers observed from the official Uber app
        .build()

    // Create the OkHttpClient
    val client = OkHttpClient()

    // Send the request asynchronously
    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            e.printStackTrace()
            // Return null in the callback if the request fails
            callback(null)
        }
        override fun onResponse(call: Call, response: Response) {
            response.use {
                val responseBody = response.body?.string()
                Log.d("UberAuth", "Response: $responseBody")
                try {
                    val json = JSONObject(responseBody)
                    val apiToken = json.optString("apiToken", null)
                    callback(apiToken)
                } catch (e: Exception) {
                    e.printStackTrace()
                    callback(null)
                }
            }
        }

    })
}

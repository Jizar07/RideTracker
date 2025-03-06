package com.stoffeltech.ridetracker.future

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request

object UberApiTest {
    private const val TEST_URL = "https://cn-geo1.uber.com"

    fun testToken(context: Context) {
        val prefs = context.getSharedPreferences("uber_prefs", Context.MODE_PRIVATE)
        val token = prefs.getString("API_TOKEN", null)

        if (token.isNullOrEmpty()) {
            Log.e("UberAPI", "No API token found!")
            return
        }

        val client = OkHttpClient()
        val request = Request.Builder()
            .url(TEST_URL)
            .addHeader("Cookie", "jwt-session=$token")
            .addHeader("Authorization", "Bearer $token")  // Some APIs require Bearer token
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("Accept-Encoding", "gzip, deflate")
            .addHeader("X-Uber-Client-ID", "passenger-web")  // Simulate the Uber driver app
            .build()

        Thread {
            try {
                val response = client.newCall(request).execute()
                Log.d("UberAPI", "Response Code: ${response.code}")
                Log.d("UberAPI", "Response Body: ${response.body?.string()}")
            } catch (e: Exception) {
                Log.e("UberAPI", "Error: ${e.message}")
            }
        }.start()
    }
}

package com.stoffeltech.ridetracker.future

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.stoffeltech.ridetracker.R

class RideDataActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ride_data)

        // Retrieve the API token from the intent extras
        val apiToken = intent.getStringExtra("API_TOKEN")

        // Find the TextView and display the token for debugging
        val tvData = findViewById<TextView>(R.id.tvRideData)
        tvData.text = "API Token: $apiToken"
    }
}

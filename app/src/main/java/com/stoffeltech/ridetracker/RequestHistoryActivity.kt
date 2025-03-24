package com.stoffeltech.ridetracker

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.stoffeltech.ridetracker.adapters.RequestHistoryAdapter
import com.stoffeltech.ridetracker.services.CSVExportManager
import com.stoffeltech.ridetracker.services.HistoryManager
import com.stoffeltech.ridetracker.services.RideInfo
import java.text.NumberFormat
import java.util.Locale
import android.graphics.Color
import android.Manifest
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.Settings
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi


private val REQUEST_WRITE_STORAGE_PERMISSION_CODE = 100


class RequestHistoryActivity : AppCompatActivity() {

    private lateinit var rvHistory: RecyclerView
    private lateinit var historyAdapter: RequestHistoryAdapter

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_request_history)

        supportActionBar?.title = "Request History"

        // Reference the RecyclerView from the layout
        rvHistory = findViewById(R.id.rvHistory)
        historyAdapter = RequestHistoryAdapter(listOf())
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = historyAdapter

// ... within onCreate() after updating adapter with history data:
        val allHistory = HistoryManager.getAllHistory() as? List<RideInfo> ?: emptyList()
        historyAdapter.updateData(allHistory)

// Retrieve Uber thresholds from shared preferences.
        val uberPrefs = getSharedPreferences("uber_prefs", Context.MODE_PRIVATE)
        val fareLow = uberPrefs.getFloat("pref_fare_low", 5.0f).toDouble()
        val fareHigh = uberPrefs.getFloat("pref_fare_high", 10.0f).toDouble()

// Initialize accumulators.
        var totalFare = 0.0
        var totalDistance = 0.0  // Sum of pickupDistance and tripDistance.
        var totalTime = 0.0      // Sum of pickupTime and tripTime (in minutes).

        allHistory.forEach { ride ->
            val fare = ride.fare ?: 0.0
            totalFare += fare
            totalDistance += (ride.pickupDistance ?: 0.0) + (ride.tripDistance ?: 0.0)
            totalTime += (ride.pickupTime ?: 0.0) + (ride.tripTime ?: 0.0)
        }

// Compute average fare per mile and per hour.
        val avgFarePerMile = if (totalDistance > 0) totalFare / totalDistance else 0.0
        val avgFarePerHour = if (totalTime > 0) totalFare / (totalTime / 60.0) else 0.0

// Prepare currency formatter.
        val currencyFormatter = NumberFormat.getCurrencyInstance(Locale.getDefault())

        // Helper function to determine color based on value and thresholds.
        fun getColor(value: Double): Int {
            return when {
                value < fareLow -> Color.RED
                value < fareHigh -> Color.YELLOW
                else -> Color.GREEN
            }
        }

// Update summary TextViews.
        findViewById<TextView>(R.id.tvTotalFareValue).apply {
            text = currencyFormatter.format(totalFare)
            setTextColor(getColor(totalFare))
        }
        findViewById<TextView>(R.id.tvFarePerMileValue).apply {
            text = currencyFormatter.format(avgFarePerMile)
            setTextColor(getColor(avgFarePerMile))
        }
        findViewById<TextView>(R.id.tvFarePerHourValue).apply {
            text = currencyFormatter.format(avgFarePerHour)
            setTextColor(getColor(avgFarePerHour))
        }

        // In your onCreate() method, after initializing the clear button:
        val btnClearHistory = findViewById<Button>(R.id.btnClearHistory)
        btnClearHistory.setOnClickListener {
            HistoryManager.clearHistory(this)
            historyAdapter.updateData(listOf())
        }

        // Add the export button initialization and listener:
        val btnExportHistory = findViewById<Button>(R.id.btnExportHistory)
        btnExportHistory.setOnClickListener {
            if (Environment.isExternalStorageManager()) {
                // Permission is granted, proceed with export
                exportHistoryToExcel()
            } else {
                // Inform the user and open system settings for MANAGE_EXTERNAL_STORAGE
                Toast.makeText(this, "Please grant manage storage permission", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }


    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WRITE_STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, proceed with export
                exportHistoryToExcel()
            } else {
                // Permission denied, notify the user
                Toast.makeText(this, "Storage permission is required to export history.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ----- Updated exportHistoryToExcel function in RequestHistoryActivity.kt -----
    // This version calls the updated ExportManager which handles data retrieval internally.
    private fun exportHistoryToExcel() {
        // Call the export function from CSVExportManager
        val success = CSVExportManager.exportToCSV(this)
        if (success) {
            Toast.makeText(this, "Export successful", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
        }
    }


}


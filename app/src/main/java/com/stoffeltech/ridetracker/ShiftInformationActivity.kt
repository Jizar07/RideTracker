package com.stoffeltech.ridetracker

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.stoffeltech.ridetracker.utils.DistanceTracker
import com.stoffeltech.ridetracker.utils.TimeTracker
import java.text.SimpleDateFormat
import java.util.*

class ShiftInformationActivity : AppCompatActivity() {

    private lateinit var tvDailyTime: TextView
    private lateinit var tvWeeklyTime: TextView
    private lateinit var tvMonthlyTime: TextView
    private lateinit var tvYearlyTime: TextView
    private lateinit var btnExportData: Button
    private lateinit var btnSeeShifts: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shift_information)

        // Bind the views
        tvDailyTime = findViewById(R.id.tvDailyTime)
        tvWeeklyTime = findViewById(R.id.tvWeeklyTime)
        tvMonthlyTime = findViewById(R.id.tvMonthlyTime)
        tvYearlyTime = findViewById(R.id.tvYearlyTime)
        btnExportData = findViewById(R.id.btnExportData)
        btnSeeShifts = findViewById(R.id.btnSeeShifts)

        // Update the shift info UI with aggregated totals
        updateShiftInfoUI()

        // Set up the Export Data button
        btnExportData.setOnClickListener {
            val exportData = TimeTracker.exportData()
            // For demonstration, we're showing the export data in a Toast.
            // You can modify this to share the data via an Intent or save to a file.
            Toast.makeText(this, exportData, Toast.LENGTH_LONG).show()
        }

        // Set up the See Shifts button to show the detailed shift list
        btnSeeShifts.setOnClickListener {
            val dialog = ShiftListDialogFragment()
            dialog.show(supportFragmentManager, "ShiftListDialog")
        }
    }

    private fun updateShiftInfoUI() {
        // Get aggregated totals from TimeTracker
        val dailyMillis = TimeTracker.getTotalTimeForToday()
        val weeklyMillis = TimeTracker.getTotalTimeForWeek()   // Make sure to implement this in TimeTracker
        val monthlyMillis = TimeTracker.getTotalTimeForMonth() // Make sure to implement this in TimeTracker
        val yearlyMillis = TimeTracker.getTotalTimeForYear()   // Make sure to implement this in TimeTracker

        // For distance, get total distance in meters.
        val dailyDistanceMeters = DistanceTracker.getTotalDistanceForToday()
        // Convert to miles (1 mile ≈ 1609.34 meters)
        val dailyMiles = dailyDistanceMeters / 1609.34
        val tvDailyDistance: TextView = findViewById(R.id.tvDailyDistance)

        tvDailyDistance.text = "Daily Distance: %.2f miles".format(dailyMiles)
        tvDailyTime.text = "Daily: ${formatTime(dailyMillis)}"
        tvWeeklyTime.text = "Weekly: ${formatTime(weeklyMillis)}"
        tvMonthlyTime.text = "Monthly: ${formatTime(monthlyMillis)}"
        tvYearlyTime.text = "Yearly: ${formatTime(yearlyMillis)}"
    }

    private fun formatTime(millis: Long): String {
        val hours = millis / (1000 * 60 * 60)
        val minutes = (millis / (1000 * 60)) % 60
        return "${hours}h ${minutes}m"
    }
}

package com.stoffeltech.ridetracker

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.stoffeltech.ridetracker.utils.DistanceTracker
import com.stoffeltech.ridetracker.utils.RevenueTracker
import com.stoffeltech.ridetracker.utils.TimeTracker
import java.text.SimpleDateFormat
import java.util.*

class ShiftInformationActivity : AppCompatActivity() {

    private lateinit var tvDailyTime: TextView
    private lateinit var tvWeeklyTime: TextView
    private lateinit var tvMonthlyTime: TextView
    private lateinit var tvYearlyTime: TextView
    private lateinit var tvDailyDistance: TextView
    private lateinit var tvEarningPerHour: TextView
    private lateinit var tvEarningPerMile: TextView
    private lateinit var tvDailyEarning: TextView
    private lateinit var tvWeeklyEarning: TextView
    private lateinit var tvMonthlyEarning: TextView
    private lateinit var tvYearlyEarning: TextView
    private lateinit var btnExportData: Button
    private lateinit var btnSeeShifts: Button

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shift_information)

        // Bind the views
        tvDailyTime = findViewById(R.id.tvDailyTime)
        tvWeeklyTime = findViewById(R.id.tvWeeklyTime)
        tvMonthlyTime = findViewById(R.id.tvMonthlyTime)
        tvYearlyTime = findViewById(R.id.tvYearlyTime)
        tvDailyDistance = findViewById(R.id.tvDailyDistance)
        tvEarningPerHour = findViewById(R.id.tvEarningPerHour)
        tvEarningPerMile = findViewById(R.id.tvEarningPerMile)
        tvDailyEarning = findViewById(R.id.tvDailyEarning)
        tvWeeklyEarning = findViewById(R.id.tvWeeklyEarning)
        tvMonthlyEarning = findViewById(R.id.tvMonthlyEarning)
        tvYearlyEarning = findViewById(R.id.tvYearlyEarning)
        btnExportData = findViewById(R.id.btnExportData)
        btnSeeShifts = findViewById(R.id.btnSeeShifts)

        // Update the shift info UI with aggregated totals
        updateShiftInfoUI()

        // Set up the Export Data button
        btnExportData.setOnClickListener {
            // Combine exported data from TimeTracker, DistanceTracker, and RevenueTracker.
            val exportData = "Time Data:\n" + TimeTracker.exportData() +
                    "\nDistance Data:\n" + DistanceTracker.exportData() +
                    "\nRevenue Data:\n" + RevenueTracker.exportData()
            Toast.makeText(this, exportData, Toast.LENGTH_LONG).show()
        }

        // Set up the See Shifts button to show the detailed shift list
        btnSeeShifts.setOnClickListener {
            val dialog = ShiftListDialogFragment()
            dialog.show(supportFragmentManager, "ShiftListDialog")
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateShiftInfoUI() {
        // ---------------------------
        // Existing Time and Distance Calculations
        // ---------------------------
        // Get aggregated totals from TimeTracker
        val dailyMillis = TimeTracker.getTotalTimeForToday()
        val weeklyMillis = TimeTracker.getTotalTimeForWeek()   // Ensure this is implemented in TimeTracker
        val monthlyMillis = TimeTracker.getTotalTimeForMonth() // Ensure this is implemented in TimeTracker
        val yearlyMillis = TimeTracker.getTotalTimeForYear()   // Ensure this is implemented in TimeTracker

        // For distance, get total distance in meters and convert to miles.
        val dailyDistanceMeters = DistanceTracker.getTotalDistanceForToday()
        val dailyMiles = dailyDistanceMeters / 1609.34

        tvDailyDistance.text = "Daily Distance: %.2f miles".format(dailyMiles)
        tvDailyTime.text = "Daily: ${formatTime(dailyMillis)}"
        tvWeeklyTime.text = "Weekly: ${formatTime(weeklyMillis)}"
        tvMonthlyTime.text = "Monthly: ${formatTime(monthlyMillis)}"
        tvYearlyTime.text = "Yearly: ${formatTime(yearlyMillis)}"

        // ---------------------------
        // New Earnings Calculations (Daily/Weekly/Monthly/Yearly)
        // ---------------------------
        // Load the latest revenue intervals from RevenueTracker.
        RevenueTracker.loadIntervals(this)
        val dailyRevenue = RevenueTracker.getDailyRevenue()
        val weeklyRevenue = RevenueTracker.getWeeklyRevenue()
        val monthlyRevenue = RevenueTracker.getMonthlyRevenue()
        val yearlyRevenue = RevenueTracker.getYearlyRevenue()

        // Update the earnings TextViews with revenue totals.
        tvDailyEarning.text = "Daily Earnings: $%.2f".format(dailyRevenue)
        tvWeeklyEarning.text = "Weekly Earnings: $%.2f".format(weeklyRevenue)
        tvMonthlyEarning.text = "Monthly Earnings: $%.2f".format(monthlyRevenue)
        tvYearlyEarning.text = "Yearly Earnings: $%.2f".format(yearlyRevenue)

        // ---------------------------
        // Earnings per Hour and per Mile Calculations
        // ---------------------------
        // Convert dailyMillis (milliseconds) to fractional hours.
        val dailyHours = dailyMillis.toDouble() / (1000 * 60 * 60)
        // Calculate earnings per hour, ensuring division is not by zero.
        val earningsPerHour = if (dailyHours > 0) dailyRevenue / dailyHours else 0.0
        // Calculate earnings per mile, ensuring division is not by zero.
        val earningsPerMile = if (dailyMiles > 0) dailyRevenue / dailyMiles else 0.0

        tvEarningPerHour.text = "$/Hour: %.2f".format(earningsPerHour)
        tvEarningPerMile.text = "$/Miles: %.2f".format(earningsPerMile)

        // ---------------------------
        // Log Values for Debugging
        // ---------------------------
        Log.d("ShiftInfo", "Daily Revenue: $dailyRevenue")
        Log.d("ShiftInfo", "Daily Time (ms): $dailyMillis, which is $dailyHours hours")
        Log.d("ShiftInfo", "Daily Distance (m): $dailyDistanceMeters, which is $dailyMiles miles")
        Log.d("ShiftInfo", "Earnings per Hour: $earningsPerHour")
        Log.d("ShiftInfo", "Earnings per Mile: $earningsPerMile")
    }

    private fun formatTime(millis: Long): String {
        val hours = millis / (1000 * 60 * 60)
        val minutes = (millis / (1000 * 60)) % 60
        return "${hours}h ${minutes}m"
    }
}

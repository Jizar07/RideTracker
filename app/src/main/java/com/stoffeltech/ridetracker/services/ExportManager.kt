package com.stoffeltech.ridetracker.services

import android.content.Context
import android.os.Environment
import android.preference.PreferenceManager
import com.stoffeltech.ridetracker.SettingsActivity
import com.stoffeltech.ridetracker.services.HistoryManager
import com.stoffeltech.ridetracker.services.RideInfo
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

object CSVExportManager {

    /**
     * Exports request history to a CSV file in the public Documents folder.
     *
     * Columns:
     * 1. Timestamp (formatted "MM/dd/yyyy hh:mm a")
     * 2. Ride Type
     * 3. Fare (currency formatted)
     * 4. Profit (currency formatted, computed as fare minus driving cost)
     * 5. Total Distance (pickupDistance + tripDistance, in miles)
     * 6. Total Time (in minutes, per ride)
     * 7. Price per Mile (currency formatted)
     * 8. Price per Hour (currency formatted)
     *
     * The summary row sums fare, profit, distance, and time but converts the overall time (in minutes)
     * into hours (e.g., 1298 minutes becomes 21.6 hours). It also computes average price per mile and per hour.
     *
     * Note: Writing to a public folder requires runtime permissions.
     */
    fun exportToCSV(context: Context): Boolean {
        return try {
            // Retrieve the history as a List of RideInfo.
            val historyData = HistoryManager.getAllHistory() as? List<RideInfo> ?: emptyList()

            // Retrieve driving cost from shared preferences.
            val mainPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            val costDriving = mainPrefs.getFloat(SettingsActivity.KEY_COST_DRIVING, 0.5f).toDouble()

            // Prepare date formatter.
            val dateFormat = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.getDefault())
            dateFormat.timeZone = TimeZone.getDefault()

            // Prepare currency formatter.
            val currencyFormatter = NumberFormat.getCurrencyInstance(Locale.getDefault())

            // Define the output file in the public Documents folder.
            val fileName = "RequestHistory.csv"
            val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val file = File(documentsDir, fileName)

            // Initialize accumulators.
            var totalFare = 0.0
            var totalProfit = 0.0
            var totalDistance = 0.0
            var totalTime = 0.0  // in minutes
            var totalPricePerMile = 0.0
            var totalPricePerHour = 0.0
            var recordCount = 0

            file.bufferedWriter().use { writer ->
                // Write header row.
                val headers = listOf(
                    "Timestamp",
                    "Ride Type",
                    "Fare",
                    "Profit",
                    "Total Distance",
                    "Total Time (min)",
                    "P/mile",
                    "P/hour"
                )
                writer.write(headers.joinToString(","))
                writer.newLine()

                // Process each RideInfo record.
                historyData.forEach { ride ->
                    // Format timestamp.
                    val timestamp = if (ride.timestamp > 0) dateFormat.format(Date(ride.timestamp * 1000)) else ""
                    val rideType = ride.rideType ?: "Unknown"
                    // Assume fare is stored as Double.
                    val fare = ride.fare ?: 0.0
                    // Total distance: sum of pickupDistance and tripDistance.
                    val totalMiles = (ride.pickupDistance ?: 0.0) + (ride.tripDistance ?: 0.0)
                    // Total time: sum of pickupTime and tripTime (in minutes).
                    val totalMinutes = (ride.pickupTime ?: 0.0) + (ride.tripTime ?: 0.0)
                    // Driving cost.
                    val drivingCost = costDriving * totalMiles
                    // Profit.
                    val profit = fare - drivingCost
                    // Price per mile.
                    val pricePerMile = if (totalMiles > 0) fare / totalMiles else 0.0
                    // Price per hour.
                    val pricePerHour = if (totalMinutes > 0) fare / (totalMinutes / 60.0) else 0.0

                    // Accumulate totals.
                    totalFare += fare
                    totalProfit += profit
                    totalDistance += totalMiles
                    totalTime += totalMinutes
                    totalPricePerMile += pricePerMile
                    totalPricePerHour += pricePerHour
                    recordCount++

                    // Build CSV row.
                    val row = listOf(
                        timestamp,
                        rideType,
                        currencyFormatter.format(fare),
                        currencyFormatter.format(profit),
                        String.format("%.1f", totalMiles),
                        String.format("%.1f", totalMinutes),
                        currencyFormatter.format(pricePerMile),
                        currencyFormatter.format(pricePerHour)
                    )
                    writer.write(row.joinToString(","))
                    writer.newLine()
                }

                // Write summary row if records exist.
                if (recordCount > 0) {
                    // Convert total time from minutes to hours.
                    val totalTimeInHours = totalTime / 60.0
                    val avgPricePerMile = totalPricePerMile / recordCount
                    val avgPricePerHour = totalPricePerHour / recordCount

                    val summaryRow = listOf(
                        "Calculation:",
                        "",
                        currencyFormatter.format(totalFare),
                        currencyFormatter.format(totalProfit),
                        String.format("%.1f", totalDistance),
                        String.format("%.1f hours", totalTimeInHours),
                        currencyFormatter.format(avgPricePerMile),
                        currencyFormatter.format(avgPricePerHour)
                    )
                    writer.write(summaryRow.joinToString(","))
                    writer.newLine()
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

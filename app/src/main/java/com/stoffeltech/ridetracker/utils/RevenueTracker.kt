// ----- RevenueTracker.kt - Updated for a 4:00 AM Daily Reset -----
package com.stoffeltech.ridetracker.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

// Updated data model: Added a 'source' field to distinguish between different revenue types.
data class RevenueInterval(
    val start: Long,
    val end: Long,
    val revenue: Double,
    val source: String  // e.g., "Uber", "Private", "Tip", etc.
)

object RevenueTracker {
    private const val PREFS_NAME = "RevenueTrackerPrefs"
    private const val KEY_INTERVALS = "revenue_intervals"

    // List holding all revenue intervals. Historical data is preserved.
    private val intervals = mutableListOf<RevenueInterval>()

    /**
     * Adds a new revenue interval.
     *
     * @param context The context used to save intervals.
     * @param revenueDelta The revenue amount.
     * @param source The revenue source (default is "Uber").
     *
     * This method is called each time ACC (Accessibility Service) or manual input
     * extracts a new revenue update. Historical data is preserved.
     */
    fun addRevenue(context: Context, revenueDelta: Double, source: String = "Uber") {
        val now = System.currentTimeMillis()
        // Add a new interval with the current timestamp, revenue delta, and source.
        intervals.add(RevenueInterval(now, now, revenueDelta, source))
        saveIntervals(context)
    }

    /**
     * For Uber revenue updates, update today's value to the current value from ACC.
     * This method removes any existing Uber intervals for today and adds a new interval
     * with the current value, ensuring that the daily Uber revenue always reflects
     * the latest value.
     *
     * @param context The context used to save intervals.
     * @param currentValue The current Uber revenue from ACC.
     */
    fun updateUberRevenue(context: Context, currentValue: Double) {
        val threshold = getStartOfDay(System.currentTimeMillis())
        // Remove any Uber intervals recorded today.
        intervals.removeAll { it.source == "Uber" && it.start >= threshold }
        // Add new interval with the current value.
        val now = System.currentTimeMillis()
        intervals.add(RevenueInterval(now, now, currentValue, "Uber"))
        saveIntervals(context)
    }

    fun getIntervals(): List<RevenueInterval> = intervals.toList()

    /**
     * Sums revenue intervals that have a start time greater than or equal to the provided threshold.
     * Optionally, you can filter by revenue source if 'source' is not null.
     */
    private fun getTotalRevenueSince(threshold: Long, source: String? = null): Double {
        return intervals.filter {
            it.start >= threshold && (source == null || it.source == source)
        }.sumOf { it.revenue }
    }

    /**
     * Returns the daily revenue by summing intervals from today only.
     * With the new logic, the "day" resets at 4:00 AM.
     * That means, at exactly 4:00 AM, daily revenue is 0.
     */
    fun getDailyRevenue(): Double {
        val startOfDay = getStartOfDay(System.currentTimeMillis())
        return getTotalRevenueSince(startOfDay)
    }

    /**
     * Returns the weekly revenue by summing intervals from the start of the week.
     */
    fun getWeeklyRevenue(): Double {
        val startOfWeek = getStartOfWeek(System.currentTimeMillis())
        return getTotalRevenueSince(startOfWeek)
    }

    /**
     * Returns the monthly revenue by summing intervals from the start of the month.
     */
    fun getMonthlyRevenue(): Double {
        val startOfMonth = getStartOfMonth(System.currentTimeMillis())
        return getTotalRevenueSince(startOfMonth)
    }

    /**
     * Returns the yearly revenue by summing intervals from the start of the year.
     */
    fun getYearlyRevenue(): Double {
        val startOfYear = getStartOfYear(System.currentTimeMillis())
        return getTotalRevenueSince(startOfYear)
    }

    /**
     * Exports revenue data for debugging or sharing purposes.
     */
    fun exportData(): String {
        val sb = StringBuilder()
        for (interval in intervals) {
            sb.append("Revenue: \$${interval.revenue} from ${interval.source} at ${interval.start}\n")
        }
        return sb.toString()
    }

    /**
     * Returns the start of the day based on a reset at 4:00 AM.
     * If the current time is before today's 4:00 AM, then the "day" is considered to have started at 4:00 AM of the previous day.
     *
     * For example:
     * - At 3:59 AM, getStartOfDay() returns yesterday at 4:00 AM (thus, revenue from yesterday is still counted).
     * - At 4:00 AM, getStartOfDay() returns today's 4:00 AM (so daily revenue resets to 0).
     */
    private fun getStartOfDay(timeMillis: Long): Long {
        val calendar = Calendar.getInstance(TimeZone.getDefault())
        calendar.timeInMillis = timeMillis
        // Set the reset time to 4:00 AM.
        calendar.set(Calendar.HOUR_OF_DAY, 4)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        // If current time is before today's 4:00 AM, subtract one day.
        if (timeMillis < calendar.timeInMillis) {
            calendar.add(Calendar.DAY_OF_MONTH, -1)
        }
        return calendar.timeInMillis
    }

    /**
     * Returns the start of the week for a given time in milliseconds.
     */
    private fun getStartOfWeek(timeMillis: Long): Long {
        val calendar = Calendar.getInstance(TimeZone.getDefault())
        calendar.timeInMillis = timeMillis
        calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
        calendar.set(Calendar.HOUR_OF_DAY, 4)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /**
     * Returns the start of the month for a given time in milliseconds.
     */
    private fun getStartOfMonth(timeMillis: Long): Long {
        val calendar = Calendar.getInstance(TimeZone.getDefault())
        calendar.timeInMillis = timeMillis
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 4)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /**
     * Returns the start of the year for a given time in milliseconds.
     */
    private fun getStartOfYear(timeMillis: Long): Long {
        val calendar = Calendar.getInstance(TimeZone.getDefault())
        calendar.timeInMillis = timeMillis
        calendar.set(Calendar.DAY_OF_YEAR, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 4)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /**
     * Saves the revenue intervals to SharedPreferences as a JSON array.
     */
    private fun saveIntervals(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        for (interval in intervals) {
            val obj = JSONObject()
            obj.put("start", interval.start)
            obj.put("end", interval.end)
            obj.put("revenue", interval.revenue)
            obj.put("source", interval.source) // Save the revenue source.
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_INTERVALS, jsonArray.toString()).apply()
    }

    /**
     * Loads revenue intervals from SharedPreferences.
     * Historical intervals are preserved for proper weekly/monthly/yearly calculations.
     */
    fun loadIntervals(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_INTERVALS, null) ?: return
        try {
            val jsonArray = JSONArray(jsonString)
            intervals.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                intervals.add(
                    RevenueInterval(
                        obj.getLong("start"),
                        obj.getLong("end"),
                        obj.getDouble("revenue"),
                        obj.getString("source")  // Load the revenue source.
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

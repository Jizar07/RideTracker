package com.stoffeltech.ridetracker.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Data model for a revenue interval.
data class RevenueInterval(val start: Long, val end: Long, val revenue: Double)

object RevenueTracker {
    private const val PREFS_NAME = "RevenueTrackerPrefs"
    private const val KEY_INTERVALS = "revenue_intervals"

    // List holding all revenue intervals.
    private val intervals = mutableListOf<RevenueInterval>()

    /**
     * Adds a new revenue interval. This method is called each time ACC (Accessibility Service)
     * extracts a new revenue update.
     *
     * Note: We do NOT remove old intervals here so that historical data is preserved
     * for weekly, monthly, and yearly revenue calculations.
     */
    fun addRevenue(context: Context, revenueDelta: Double) {
        val now = System.currentTimeMillis()
        // Add a new interval with the current timestamp and revenue delta.
        intervals.add(RevenueInterval(now, now, revenueDelta))
        saveIntervals(context)
    }

    fun getIntervals(): List<RevenueInterval> = intervals.toList()

    /**
     * Sums revenue intervals that have a start time greater than or equal to the provided threshold.
     */
    private fun getTotalRevenueSince(threshold: Long): Double {
        return intervals.filter { it.start >= threshold }
            .sumOf { it.revenue }
    }

    /**
     * Returns the daily revenue by summing intervals from today only.
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
            sb.append("Revenue: \$${interval.revenue} at ${interval.start}\n")
        }
        return sb.toString()
    }

    /**
     * Returns the start of the day (midnight) for a given time in milliseconds.
     */
    private fun getStartOfDay(timeMillis: Long): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = timeMillis
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 4)
        calendar.set(java.util.Calendar.MINUTE, 15)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /**
     * Returns the start of the week for a given time in milliseconds.
     */
    private fun getStartOfWeek(timeMillis: Long): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = timeMillis
        calendar.set(java.util.Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /**
     * Returns the start of the month for a given time in milliseconds.
     */
    private fun getStartOfMonth(timeMillis: Long): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = timeMillis
        calendar.set(java.util.Calendar.DAY_OF_MONTH, 1)
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /**
     * Returns the start of the year for a given time in milliseconds.
     */
    private fun getStartOfYear(timeMillis: Long): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = timeMillis
        calendar.set(java.util.Calendar.DAY_OF_YEAR, 1)
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /**
     * Saves the revenue intervals to SharedPreferences as a JSON array.
     */
    fun saveIntervals(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        for (interval in intervals) {
            val obj = JSONObject()
            obj.put("start", interval.start)
            obj.put("end", interval.end)
            obj.put("revenue", interval.revenue)
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_INTERVALS, jsonArray.toString()).apply()
    }

    /**
     * Loads revenue intervals from SharedPreferences.
     * Note: We do not remove any intervals here, ensuring historical data is preserved.
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
                        obj.getDouble("revenue")
                    )
                )
            }
            // Historical intervals are preserved for proper weekly/monthly/yearly calculations.
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

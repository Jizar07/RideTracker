package com.stoffeltech.ridetracker.utils

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class RevenueInterval(val start: Long, val end: Long, val revenue: Double)

object RevenueTracker {
    private const val PREFS_NAME = "RevenueTrackerPrefs"
    private const val KEY_INTERVALS = "revenue_intervals"

    private val intervals = mutableListOf<RevenueInterval>()

    // Add a new revenue interval (called each time OCR extracts new revenue)
    fun addRevenue(context: Context, revenueDelta: Double) {
        // Use current time as end time and previous interval's end or current time as start.
        val now = System.currentTimeMillis()
        // For simplicity, add a new interval with the revenueDelta.
        intervals.add(RevenueInterval(now, now, revenueDelta))
        saveIntervals(context)
    }

    fun getIntervals(): List<RevenueInterval> = intervals.toList()

    // Calculate total revenue for a given start time threshold.
    private fun getTotalRevenueSince(threshold: Long): Double {
        return intervals.filter { it.start >= threshold }
            .sumOf { it.revenue }
    }

    fun getDailyRevenue(): Double {
        val startOfDay = getStartOfDay(System.currentTimeMillis())
        return getTotalRevenueSince(startOfDay)
    }

    fun getWeeklyRevenue(): Double {
        val startOfWeek = getStartOfWeek(System.currentTimeMillis())
        return getTotalRevenueSince(startOfWeek)
    }

    fun getMonthlyRevenue(): Double {
        val startOfMonth = getStartOfMonth(System.currentTimeMillis())
        return getTotalRevenueSince(startOfMonth)
    }

    fun getYearlyRevenue(): Double {
        val startOfYear = getStartOfYear(System.currentTimeMillis())
        return getTotalRevenueSince(startOfYear)
    }

    fun exportData(): String {
        val sb = StringBuilder()
        for (interval in intervals) {
            sb.append("Revenue: \$${interval.revenue} at ${interval.start}\n")
        }
        return sb.toString()
    }

    private fun getStartOfDay(timeMillis: Long): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = timeMillis
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

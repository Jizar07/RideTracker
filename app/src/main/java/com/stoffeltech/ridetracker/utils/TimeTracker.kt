package com.stoffeltech.ridetracker.utils

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

data class TimeInterval(val start: Long, val end: Long)

object TimeTracker {
    private const val PREFS_NAME = "TimeTrackerPrefs"
    private const val KEY_INTERVALS = "intervals"

    private var currentStart: Long? = null
    private val intervals = mutableListOf<TimeInterval>()

    // Call this when the app comes to the foreground.
    fun startTracking(context: Context) {
        if (currentStart == null) {
            currentStart = System.currentTimeMillis()
        }
    }

    // Call this when the app goes to the background.
    fun stopTracking(context: Context) {
        currentStart?.let { start ->
            val end = System.currentTimeMillis()
            intervals.add(TimeInterval(start, end))
            currentStart = null
            saveIntervals(context)
        }
    }

    // Returns total milliseconds for today (using system day boundaries).
    fun getTotalTimeForToday(): Long {
        val todayStart = getStartOfDay(System.currentTimeMillis())
        // Sum intervals that started today.
        val storedTime = intervals.filter { it.start >= todayStart }
            .sumOf { it.end - it.start }
        // If a session is currently ongoing and started today, add its elapsed time.
        val currentTime = if (currentStart != null && currentStart!! >= todayStart) {
            System.currentTimeMillis() - currentStart!!
        } else {
            0L
        }
        return storedTime + currentTime
    }


    // Helper function to get the start of the day (midnight)
    private fun getStartOfDay(timeMillis: Long): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = timeMillis
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun getTotalTimeForWeek(): Long {
        val calendar = Calendar.getInstance()
        // Set to the first day of the week.
        calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val weekStart = calendar.timeInMillis

        val storedTime = intervals.filter { it.start >= weekStart }
            .sumOf { it.end - it.start }
        val currentTime = if (currentStart != null && currentStart!! >= weekStart) {
            System.currentTimeMillis() - currentStart!!
        } else {
            0L
        }
        return storedTime + currentTime
    }

    fun getTotalTimeForMonth(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val monthStart = calendar.timeInMillis

        val storedTime = intervals.filter { it.start >= monthStart }
            .sumOf { it.end - it.start }
        val currentTime = if (currentStart != null && currentStart!! >= monthStart) {
            System.currentTimeMillis() - currentStart!!
        } else {
            0L
        }
        return storedTime + currentTime
    }

    fun getTotalTimeForYear(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_YEAR, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val yearStart = calendar.timeInMillis

        val storedTime = intervals.filter { it.start >= yearStart }
            .sumOf { it.end - it.start }
        val currentTime = if (currentStart != null && currentStart!! >= yearStart) {
            System.currentTimeMillis() - currentStart!!
        } else {
            0L
        }
        return storedTime + currentTime
    }

    // Helper to filter shifts by date.
    fun getShiftsForDate(date: Date): List<TimeInterval> {
        val calendar = Calendar.getInstance()
        calendar.time = date
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val dayStart = calendar.timeInMillis

        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val dayEnd = calendar.timeInMillis

        return intervals.filter { it.start in dayStart until dayEnd }
    }



    // Persist intervals to SharedPreferences as a JSON array
    private fun saveIntervals(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        for (interval in intervals) {
            val obj = JSONObject()
            obj.put("start", interval.start)
            obj.put("end", interval.end)
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_INTERVALS, jsonArray.toString()).apply()
    }

    // Load intervals from SharedPreferences (call during app start if needed)
    fun loadIntervals(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_INTERVALS, null) ?: return
        try {
            val jsonArray = JSONArray(jsonString)
            intervals.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                intervals.add(TimeInterval(obj.getLong("start"), obj.getLong("end")))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Export function can return the intervals list as a formatted string
    fun exportData(): String {
        val sb = StringBuilder()
        for (interval in intervals) {
            val startTime = java.text.SimpleDateFormat("hh:mma", java.util.Locale.getDefault()).format(interval.start)
            val endTime = java.text.SimpleDateFormat("hh:mma", java.util.Locale.getDefault()).format(interval.end)
            val durationHours = (interval.end - interval.start) / (1000 * 60 * 60).toDouble()
            sb.append("Worked for %.2f hrs between $startTime and $endTime\n".format(durationHours))
        }
        return sb.toString()
    }
    fun getShifts(): List<TimeInterval> {
        return intervals.toList()  // Return a copy of the intervals list.
    }

}

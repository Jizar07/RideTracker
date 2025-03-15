package com.stoffeltech.ridetracker.services

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A simple thread-safe manager that stores and retrieves historical ride requests.
 * Now updated to persist data in SharedPreferences using JSON, so the list won't reset
 * when the app closes.
 */
object HistoryManager {

    // In-memory list to hold RideInfo entries.
    private val rideHistory = CopyOnWriteArrayList<RideInfo>()

    // SharedPreferences file and key
    private const val PREFS_NAME = "history_prefs"
    private const val KEY_RIDE_HISTORY = "ride_history_json"

    /**
     * Call this once at app startup (e.g., in MainActivity or in Application)
     * to load previously saved history into memory.
     */
    fun loadHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_RIDE_HISTORY, null) ?: return

        val type = object : TypeToken<List<RideInfo>>() {}.type
        val loadedList = Gson().fromJson<List<RideInfo>>(json, type)
        rideHistory.clear()
        rideHistory.addAll(loadedList)
    }

    /**
     * Save the current in-memory history list to SharedPreferences as JSON.
     */
    private fun saveHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        val json = Gson().toJson(rideHistory)  // Convert the entire list to JSON
        editor.putString(KEY_RIDE_HISTORY, json)
        editor.apply()
    }

    /**
     * Adds a new ride request to the history, then saves to disk.
     * @param rideInfo the RideInfo object to store.
     * @param context pass in the Activity or app context
     */
    fun addRideRequest(rideInfo: RideInfo, context: Context) {
        rideHistory.add(rideInfo)
        saveHistory(context)
    }

    /**
     * Returns a snapshot (unmodifiable list) of the stored requests, newest first.
     */
    fun getAllHistory(): List<RideInfo> {
        // Return a reversed copy so the most recent is at the top.
        return rideHistory.asReversed().toList()
    }

    /**
     * Clears all stored history in memory and on disk.
     */
    fun clearHistory(context: Context) {
        rideHistory.clear()
        saveHistory(context)
    }
}

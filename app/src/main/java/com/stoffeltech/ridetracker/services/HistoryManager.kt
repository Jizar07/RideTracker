package com.stoffeltech.ridetracker.services

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stoffeltech.ridetracker.utils.PickupLocationGeoCoder // Import the geo coder
import com.stoffeltech.ridetracker.utils.FileLogger
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
     * Before persisting, attempts to obtain coordinates from the pickup location.
     *
     * @param rideInfo the RideInfo object to store.
     * @param context pass in the Activity or app context.
     */
    suspend fun addRideRequest(rideInfo: RideInfo, context: Context) {
        // Log ride info details for debugging.
        FileLogger.log("HistoryManager", "addRideRequest called with rideInfo: $rideInfo")

        // Retrieve coordinates from the pickup location string.
        val coords = rideInfo.pickupLocation?.let {
            FileLogger.log("HistoryManager", "Attempting geocoding for pickupLocation: $it")
            PickupLocationGeoCoder.getCoordinates(context, it)
        }
        if (coords != null) {
            FileLogger.log("HistoryManager", "Geocoding returned coordinates: lat=${coords.latitude}, lng=${coords.longitude}")
            // Create a LearningData entry using the coordinates and ride fare.
            val learningData = LearningData(
                fare = rideInfo.fare,
                earningsPerHour = null, // To be computed later.
                earningsPerMile = null, // To be computed later.
                latitude = coords.latitude,
                longitude = coords.longitude
            )
            LearningManager.addLearningData(learningData, context)
            FileLogger.log("HistoryManager", "Learning data stored: lat=${coords.latitude}, lng=${coords.longitude}")
        } else {
            FileLogger.log("HistoryManager", "Unable to fetch coordinates for pickupLocation: ${rideInfo.pickupLocation}")
        }

        // Persist the ride request.
        rideHistory.add(rideInfo)
        saveHistory(context)
        FileLogger.log("HistoryManager", "RideInfo persisted. Total rideHistory count: ${rideHistory.size}")
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

package com.stoffeltech.ridetracker.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {
    private const val LOG_FILE_NAME = "app_logs.txt"
    private var logFile: File? = null

    /**
     * Initializes the log file in the public Documents folder under "ridetracker/logs".
     * Note: For Android versions below Q, you might need to add the WRITE_EXTERNAL_STORAGE permission in your Manifest.
     */
    fun init(context: Context) {
        // Use the app's external files directory which is private to your app and doesn't require extra permissions.
        val logsDir = File(context.getExternalFilesDir(null), "logs")
        if (!logsDir.exists()) {
            logsDir.mkdirs() // Create the logs directory if it doesn't exist.
        }
        // Initialize the log file in the app's external files directory.
        logFile = File(logsDir, LOG_FILE_NAME)
        if (!logFile!!.exists()) {
            try {
                logFile!!.createNewFile()
            } catch (e: IOException) {
                e.printStackTrace() // Error creating log file
            }
        }
    }

    /**
     * Appends a log message with a timestamp and tag to the log file.
     */
    fun log(tag: String, message: String) {
        val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val logMessage = "$timeStamp [$tag]: $message\n"
        // Log to Logcat for immediate debugging output.
        Log.d(tag, message)
        try {
            logFile?.appendText(logMessage)
        } catch (e: IOException) {
            e.printStackTrace() // Error writing log message
        }
    }
}

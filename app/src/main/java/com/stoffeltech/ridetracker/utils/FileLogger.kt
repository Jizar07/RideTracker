package com.stoffeltech.ridetracker.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URLEncoder


object FileLogger {
    private var logFile: File? = null
    private val client = OkHttpClient()  // OkHttpClient to send HTTP requests

    /**
     * Initializes the log file in the public Documents folder under "ridetracker/logs".
     * A unique file is created for each session using a timestamp in its name.
     * Note: For Android versions below Q, you might need to add the WRITE_EXTERNAL_STORAGE permission.
     */
    fun init(context: Context) {
        // Get the public Documents directory.
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        // Create the folder "ridetracker/logs" inside Documents.
        val logsDir = File(documentsDir, "ridetracker/logs")
        if (!logsDir.exists()) {
            logsDir.mkdirs() // Create the logs directory if it doesn't exist.
        }
        // Create a unique log file name using a timestamp.
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "app_logs_$timeStamp.txt"
        logFile = File(logsDir, fileName)
        if (!logFile!!.exists()) {
            try {
                logFile!!.createNewFile()
            } catch (e: IOException) {
                e.printStackTrace() // Error creating log file
            }
        }
        // Log the absolute path for debugging.
        Log.d("FileLogger", "Log file created at: ${logFile?.absolutePath}")
    }

    /**
     * Appends a log message with a timestamp and tag to the log file.
     */
    fun log(tag: String, message: String) {
        val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val logMessage = "$timeStamp [$tag]: $message\n"
        Log.d(tag, message)
        try {
            logFile?.appendText(logMessage)
        } catch (e: IOException) {
            e.printStackTrace() // Error writing log message
        }

        // Send the log message to the server.
        sendLogToServer(logMessage)
    }

    /**
     * Sends the log message to the REST API endpoint via a POST request.
     */
    private fun sendLogToServer(logMessage: String) {
        val url = "https://stoffeltech.com/wp-json/ridetracker/v1/logs"
        val requestBody = FormBody.Builder()
            .add("message", logMessage)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        // Send the request asynchronously.
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Log failure to send the log to the server.
                Log.e("FileLogger", "Failed to send log to server", e)
            }

            override fun onResponse(call: Call, response: Response) {
                // Close the response to avoid resource leaks.
                response.close()
            }
        })
    }
}

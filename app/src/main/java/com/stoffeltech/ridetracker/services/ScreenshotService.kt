package com.stoffeltech.ridetracker.services

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import com.stoffeltech.ridetracker.uber.UberParser
import com.stoffeltech.ridetracker.utils.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * ---------------- SCREENSHOT SERVICE ----------------
 * This service handles capturing a full-screen screenshot when the overlay pops up.
 * The screenshot is saved into a specific folder structure under the Pictures directory.
 *
 * Changes implemented:
 * 1. Refactored captureFullScreen to use the centralized MediaProjection from MediaProjectionLifecycleManager.
 * 2. Prevents duplicate screenshots by using a timestamp check.
 * 3. Keeps the existing file organization and screenshot saving logic.
 */
object ScreenshotService {

    // ---------------- GLOBAL VARIABLE FOR DUPLICATE SCREENSHOT PREVENTION -----------------
    // Prevents taking multiple screenshots in quick succession.
    private var lastScreenshotTime: Long = 0
    private const val SCREENSHOT_INTERVAL_MS = 5000L // 5 seconds between screenshots

    // Delay before taking the screenshot (milliseconds) to ensure the overlay is visible.
    private const val CAPTURE_DELAY_MS = 1000L  // 1 second delay

    /**
     * ---------------- SAVE SCREENSHOT - START -----------------
     * Saves the provided bitmap as a PNG file under a folder named "Ride Tracker".
     * The screenshot will be stored in a subfolder for the given ride type.
     */
    fun saveScreenshot(bitmap: Bitmap, rideType: String) {
        // Get the public Pictures directory.
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        // Create (or reuse) the main folder "Ride Tracker".
        val appFolder = File(picturesDir, "Ride Tracker")
        if (!appFolder.exists()) {
            appFolder.mkdirs()
        }
        // Create (or reuse) the subfolder for the ride type.
        val rideFolder = File(appFolder, rideType)
        if (!rideFolder.exists()) {
            rideFolder.mkdirs()
        }
        // Generate a unique file name with a timestamp.
        val dateFormat = SimpleDateFormat("MM-dd-yy_hhmmssa", Locale.getDefault())
        val timestamp = dateFormat.format(Date())
        val filename = "${rideType}_${timestamp}.png"
        val file = File(rideFolder, filename)
        try {
            FileOutputStream(file).use { fos ->
                bitmap.compress(CompressFormat.PNG, 100, fos)
            }
            FileLogger.log("ScreenshotService", "✅ Screenshot saved to ${file.absolutePath}")
        } catch (e: Exception) {
            FileLogger.log("ScreenshotService", "❌ Error saving screenshot: ${e.message}")
        }
    }
    // ---------------- SAVE SCREENSHOT - END -----------------

    /**
     * ---------------- CAPTURE FULL SCREEN SCREENSHOT - START -----------------
     * Captures a full-screen screenshot using the centralized MediaProjection.
     * The screenshot is saved in a specific folder based on rideType.
     *
     * @param context The application context.
     * @param rideType The ride type string used for naming the screenshot.
     */
    // ----- CAPTURE FULL SCREEN SCREENSHOT - UPDATED -----
// Captures a full-screen screenshot using the persistent capture function and saves it.
    fun captureFullScreen(context: Context, rideType: String) {

        // Duplicate screenshot prevention
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastScreenshotTime < SCREENSHOT_INTERVAL_MS) {
            FileLogger.log("ScreenshotService", "Screenshot recently taken. Skipping duplicate capture.")
            return
        }
        lastScreenshotTime = currentTime

        // Wait for a short delay to allow the overlay to fully render.
        Handler(Looper.getMainLooper()).postDelayed({
            // Launch a coroutine to capture a fresh bitmap using persistent resources.
            CoroutineScope(Dispatchers.IO).launch {
                val settingsPrefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                val isScreenshotSliderOn = settingsPrefs.getBoolean("screenshot_slider", false)
                val capturedBitmap = ScreenCaptureService.captureBitmapPersistent(context)
                if (capturedBitmap != null) {
                    FileLogger.log("ScreenshotService", "Capturing screenshot after delay.")
                    if (isScreenshotSliderOn) {
                        saveScreenshot(capturedBitmap, rideType)
                    } else {
                        FileLogger.log("ScreenshotService", "Screenshot slider is off. Not saving screenshot.")
                    }

                } else {
                    FileLogger.log("ScreenshotService", "No captured bitmap available for screenshot.")
                }
            }
        }, CAPTURE_DELAY_MS)
    }

    // ---------------- CAPTURE FULL SCREEN SCREENSHOT - END -----------------
}

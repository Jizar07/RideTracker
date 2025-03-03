package com.stoffeltech.ridetracker.services

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object ScreenshotService {
    /**
     * Saves the provided bitmap as a PNG file under a folder named "Ride Tracker".
     * The screenshot will be stored in a subfolder for the given ride type.
     */
    var mediaProjection: android.media.projection.MediaProjection? = null


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
            Log.d("ScreenshotService", "✅ Screenshot saved to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("ScreenshotService", "❌ Error saving screenshot: ${e.message}")
        }
    }
    @SuppressLint("ServiceCast")
    fun captureFullScreen(context: Context, mp: android.media.projection.MediaProjection, rideType: String) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // Create an ImageReader to capture the screen
        val imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)

        // Create a VirtualDisplay using MediaProjection
        val virtualDisplay = mp.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null
        )

        // Delay slightly to ensure an image is available
        Handler(Looper.getMainLooper()).postDelayed({
            val image = imageReader.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width
                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()
                virtualDisplay.release()
                imageReader.close()

                // Save the captured screenshot
                saveScreenshot(bitmap, rideType)
            } else {
                Log.e("ScreenshotService", "Failed to capture image from ImageReader")
            }
        }, 2000)
    }

}

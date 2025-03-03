package com.stoffeltech.ridetracker.services

import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object ScreenshotService {
    var mediaProjection: android.media.projection.MediaProjection? = null

    fun setMediaProjection(mp: android.media.projection.MediaProjection) {
        mediaProjection = mp
    }

    // Existing saveScreenshot function
    fun saveScreenshot(bitmap: Bitmap, rideType: String) {
        // ... existing code ...
    }

    // New captureFullScreen function must be inside this block.
    fun captureFullScreen(context: android.content.Context, mediaProjection: android.media.projection.MediaProjection, rideType: String) {
        val windowManager = context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        val display: android.view.Display = windowManager.defaultDisplay
        val metrics = android.util.DisplayMetrics()
        display.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // Create an ImageReader instance that matches the screen size.
        val imageReader = android.media.ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        // Create a VirtualDisplay with the ImageReader's surface.
        val virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null
        )

        // Wait a short time for the image to be available.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val image = imageReader.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width
                // Create a bitmap with the proper dimensions.
                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()
                virtualDisplay.release()
                // Save the captured screenshot using the saveScreenshot method.
                saveScreenshot(bitmap, rideType)
            } else {
                Log.e("ScreenshotService", "❌ Failed to acquire screen image")
            }
        }, 1000) // Delay in milliseconds; adjust if needed.
    }
}

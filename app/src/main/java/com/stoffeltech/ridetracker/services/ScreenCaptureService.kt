package com.stoffeltech.ridetracker.services

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.app.NotificationCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.hardware.display.VirtualDisplay
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.channels.Channel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.stoffeltech.ridetracker.uber.UberParser
import com.stoffeltech.ridetracker.utils.FileLogger
import kotlin.coroutines.coroutineContext


/**
 * ---------------- SCREEN CAPTURE SERVICE ----------------
 * This object contains essential functions for continuous OCR capture.
 *
 * Added Global Variable:
 * - lastCapturedBitmap: Stores the latest captured frame for use by ScreenshotService.
 */
object ScreenCaptureService {

    // ---------------- GLOBAL VARIABLE -----------------
    // Stores the last captured bitmap for screenshot purposes.
    @Volatile
    var lastCapturedBitmap: Bitmap? = null
    var lastProcessedBitmap: Bitmap? = null

    // Region Of Interest (ROI) for OCR fallback capture.
    var ocrRoi: Rect? = null

    // ----- FLAG TO PREVENT OVERLAPPING OCR TASKS -----
    private val isOcrProcessing = java.util.concurrent.atomic.AtomicBoolean(false)


    // ----- CACHE TEXT RECOGNIZER INSTANCE - single instance for reuse -----
    // ----- UPDATED: Mutable detector with getter -----
    private var textRecognizerInstance: com.google.mlkit.vision.text.TextRecognizer? = null

    private fun getTextRecognizer(): com.google.mlkit.vision.text.TextRecognizer {
        if (textRecognizerInstance == null) {
            val options = com.google.mlkit.vision.text.latin.TextRecognizerOptions.Builder().build()
            textRecognizerInstance = com.google.mlkit.vision.text.TextRecognition.getClient(options)
        }
        return textRecognizerInstance!!
    }

    // ----- PERSISTENT CAPTURE RESOURCES -----
    private var globalVirtualDisplay: VirtualDisplay? = null
    private var globalImageReader: ImageReader? = null
    private var globalHandlerThread: HandlerThread? = null

    // ----- INITIALIZE PERSISTENT CAPTURE RESOURCES -----
// Call this function once when MediaProjection is granted.
    fun initPersistentCapture(context: Context, mediaProjection: MediaProjection) {
        if (globalVirtualDisplay == null || globalImageReader == null || globalHandlerThread == null) {
            globalHandlerThread = HandlerThread("PersistentOcrThread").apply { start() }
            val handler = Handler(globalHandlerThread!!.looper)
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            // Create an ImageReader with a capacity of 2 for continuous availability.
            globalImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            globalVirtualDisplay = mediaProjection.createVirtualDisplay(
                "PersistentOcrDisplay",
                width,
                height,
                metrics.densityDpi,
                0,
                globalImageReader!!.surface,
                null,
                handler
            )
            FileLogger.log("ScreenCaptureService", "Persistent capture resources initialized.")
        }
    }

    // ----- RELEASE PERSISTENT CAPTURE RESOURCES -----
// Call this function when ending the session (e.g., when MediaProjection stops).
    fun releasePersistentCapture() {
        globalVirtualDisplay?.release()
        globalImageReader?.close()
        globalHandlerThread?.quitSafely()
        globalVirtualDisplay = null
        globalImageReader = null
        globalHandlerThread = null
        FileLogger.log("ScreenCaptureService", "Persistent capture resources released.")
    }




    // ----- RELEASE TEXT RECOGNIZER INSTANCE -----
    fun releaseTextRecognizer() {
        try {
            textRecognizerInstance?.close()
            textRecognizerInstance = null // Reset so a new instance is created next time
            FileLogger.log("ScreenCaptureService", "TextRecognizer released")
        } catch (e: Exception) {
            FileLogger.log("ScreenCaptureService", "Error releasing TextRecognizer: ${e.message}")
        }
    }



    // -------------- NEW GLOBALS FOR BOUNDING BOX --------------
    @Volatile
    var lockedRideTypeTop: Int? = null
    @Volatile
    var boundingBoxLockActive: Boolean = false
    // ----------------------------------------------------------

    /**
     * ---------------- RUN OCR ON BITMAP - START ----------------
     * Processes the provided bitmap using ML Kit's OCR and returns the extracted text.
     */
    private suspend fun runOcrOnBitmap(bitmap: Bitmap): String? {
        return withContext(Dispatchers.Default) {
            try {
                val startTime = System.currentTimeMillis()  // --- PROFILE: Start time ---
                val image = InputImage.fromBitmap(bitmap, 0)
                // Use our fresh (or reinitialized) detector
                val result = getTextRecognizer().process(image).await()
                val duration = System.currentTimeMillis() - startTime  // --- PROFILE: End time ---
                val rawText = result.text  // Capture the raw OCR text
                // Log the raw OCR text for debugging purposes
//                FileLogger.log("ScreenCaptureService", "Raw OCR text captured: $rawText")
                rawText  // Return the captured text
            } catch (e: Exception) {
                FileLogger.log("ScreenCaptureService", "Error during OCR processing: ${e.message}")
                null
            }
        }
    }

    // ----- HELPER FUNCTION: Send Notification After Multiple Timeouts -----
    private fun sendCaptureTimeoutNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "CaptureTimeoutChannel",
                "Capture Timeout Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when bitmap capture fails repeatedly."
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(context, "CaptureTimeoutChannel")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Screen Capture Error")
            .setContentText("Bitmap capture timed out repeatedly. Consider restarting the app.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1001, notification)
    }
    // ----- HELPER FUNCTION: Convert Bitmap to Grayscale -----
    // This function creates a new Bitmap in grayscale using a ColorMatrix.
    private fun toGrayscale(src: Bitmap): Bitmap {
        // Create a mutable bitmap with the same dimensions and configuration as src.
        val config = src.config ?: Bitmap.Config.ARGB_8888
        val grayscaleBitmap = Bitmap.createBitmap(src.width, src.height, config)

        // Create a Canvas to draw onto the new bitmap.
        val canvas = android.graphics.Canvas(grayscaleBitmap)
        // Create a ColorMatrix that converts colors to grayscale.
        val colorMatrix = android.graphics.ColorMatrix().apply {
            setSaturation(0f) // 0 means fully desaturated (grayscale)
        }
        // Create a Paint object with the ColorMatrixColorFilter.
        val paint = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
        }
        // Draw the original bitmap onto the grayscale bitmap.
        canvas.drawBitmap(src, 0f, 0f, paint)
        return grayscaleBitmap
    }
    // ----- HELPER FUNCTION: Convert Grayscale Bitmap to Binary using Thresholding -----
    // This function converts a grayscale bitmap into a binary (black and white) image
    // using a simple threshold. Adjust the 'threshold' value (default is 128) if needed.
    fun toBinary(src: Bitmap, threshold: Int = 128): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        // Retrieve all pixel data from the source bitmap
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            // Calculate the luminance using standard weights
            val gray = (0.299 * red + 0.587 * green + 0.114 * blue).toInt()
            // Set the pixel to black if below the threshold; otherwise, white
            pixels[i] = if (gray < threshold) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val binaryBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        binaryBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return binaryBitmap
    }
    // ----- HELPER FUNCTION: Compare Two Bitmaps for Similarity -----
    // This function down samples both bitmaps and calculates a difference metric.
    // If the total difference is below 'threshold', they are considered similar.
    fun areBitmapsSimilar(bmp1: Bitmap, bmp2: Bitmap, sampleSize: Int = 10, threshold: Int = 1000): Boolean {
        // Downscale both bitmaps to reduce computation.
        val scaledBmp1 = Bitmap.createScaledBitmap(bmp1, sampleSize, sampleSize, true)
        val scaledBmp2 = Bitmap.createScaledBitmap(bmp2, sampleSize, sampleSize, true)
        var diff = 0
        for (x in 0 until sampleSize) {
            for (y in 0 until sampleSize) {
                val pixel1 = scaledBmp1.getPixel(x, y)
                val pixel2 = scaledBmp2.getPixel(x, y)
                // Extract RGB components.
                val r1 = (pixel1 shr 16) and 0xFF
                val g1 = (pixel1 shr 8) and 0xFF
                val b1 = pixel1 and 0xFF
                val r2 = (pixel2 shr 16) and 0xFF
                val g2 = (pixel2 shr 8) and 0xFF
                val b2 = pixel2 and 0xFF
                diff += kotlin.math.abs(r1 - r2) + kotlin.math.abs(g1 - g2) + kotlin.math.abs(b1 - b2)
            }
        }
        return diff < threshold
    }
    // ----- PERSISTENT ON-DEMAND OCR CAPTURE FUNCTION (Polling Approach) -----
    // This function reuses the persistent VirtualDisplay and ImageReader by polling for the latest image.
    suspend fun captureOcrOnDemandPersistent(
        context: Context,
        captureTimeoutMillis: Long = 1000  // Adjust timeout as needed
    ): String? {
        // Wait until persistent resources are initialized (poll up to captureTimeoutMillis)
        val startWait = System.currentTimeMillis()
        while (globalImageReader == null && System.currentTimeMillis() - startWait < captureTimeoutMillis) {
            delay(50)
        }
        if (globalImageReader == null || globalVirtualDisplay == null) {
            FileLogger.log("ScreenCaptureService", "Persistent capture resources not initialized.")
            return null
        }
        // Clear out any stale images from previous captures.
        while (true) {
            val staleImage = globalImageReader!!.acquireLatestImage()
            if (staleImage != null) {
                staleImage.close()
            } else {
                break
            }
        }

        // Poll for a new image up to captureTimeoutMillis
        var image = globalImageReader!!.acquireLatestImage()
        val startTime = System.currentTimeMillis()
        while (image == null && System.currentTimeMillis() - startTime < captureTimeoutMillis) {
            delay(20) // Poll every 50ms
            image = globalImageReader!!.acquireLatestImage()
        }

        if (image == null) {
            FileLogger.log("ScreenCaptureService", "Persistent on-demand bitmap capture timed out or failed.")
            return null
        }

        // Process the captured image
        return try {
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val fullBitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                metrics.heightPixels,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)
            image.close()
            // Convert the full bitmap to grayscale.
            val grayscaleBitmap = toGrayscale(fullBitmap)

            // Perform a quick OCR pass to obtain the fullScreenResult for enhanced cropping.
            val fullScreenResult = try {
                getTextRecognizer().process(InputImage.fromBitmap(fullBitmap, 0)).await()
            } catch (e: Exception) {
//                FileLogger.log("ScreenCaptureService", "Quick OCR for enhanced cropping failed: ${e.message}")
                null
            }

            // Use the enhanced cropping function if a quick OCR result is available.
            val croppedBitmap = if (fullScreenResult != null) {
                applyEnhancedDynamicCroppingForUber(grayscaleBitmap, fullScreenResult)
            } else {
                null
            }

            // Check if cropping succeeded.
            if (croppedBitmap == null) {
//                FileLogger.log("ScreenCaptureService", "Cropping failed; skipping OCR on cropped bitmap.")
                return null
            }

            // Run final OCR on the cropped bitmap.
            val capturedText = runOcrOnBitmap(croppedBitmap)
            FileLogger.log("ScreenCaptureService", "Enhanced OCR Captured Text: $capturedText")
            return capturedText
        } catch (e: Exception) {
            FileLogger.log("ScreenCaptureService", "Error processing image: ${e.message}")
            image.close()
            null
        }
    }
    // ----- PERSISTENT BITMAP CAPTURE FUNCTION -----
    // This function captures a raw Bitmap using the persistent VirtualDisplay and ImageReader.
    suspend fun captureBitmapPersistent(
        context: Context,
        captureTimeoutMillis: Long = 1000  // Adjust as needed
    ): Bitmap? {
        if (globalImageReader == null || globalVirtualDisplay == null) {
            FileLogger.log("ScreenCaptureService", "Persistent capture resources not initialized.")
            return null
        }

        // Drain any stale images
        while (true) {
            val staleImage = globalImageReader!!.acquireLatestImage()
            if (staleImage != null) {
                staleImage.close()
            } else {
                break
            }
        }

        // Poll for a fresh image
        var image = globalImageReader!!.acquireLatestImage()
        val startTime = System.currentTimeMillis()
        while (image == null && System.currentTimeMillis() - startTime < captureTimeoutMillis) {
            delay(20) // Poll every 20ms
            image = globalImageReader!!.acquireLatestImage()
        }

        if (image == null) {
            FileLogger.log("ScreenCaptureService", "Persistent bitmap capture timed out or failed.")
            return null
        }

        return try {
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            // Create the bitmap based on screen dimensions.
            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                metrics.heightPixels,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()
            bitmap
        } catch (e: Exception) {
            FileLogger.log("ScreenCaptureService", "Error capturing bitmap: ${e.message}")
            image.close()
            null
        }
    }
    // ----- NEW FUNCTION: applyEnhancedDynamicCroppingForUber (with 5px padding) -----
    // File: ScreenCaptureService.kt
    // This function applies enhanced dynamic cropping for Uber requests with a 5px margin on each side.
    // - Top boundary: 5px above the ride type text
    // - Bottom boundary: 5px below the "Accept" or "Match" button
    // - Left boundary: 5px to the left of the "$" fare price
    // - Right boundary: 5px to the right of the "x" close button
    @SuppressLint("SuspiciousIndentation")
    fun applyEnhancedDynamicCroppingForUber(
        fullBitmap: Bitmap,
        fullScreenResult: com.google.mlkit.vision.text.Text
    ): Bitmap? {
        var topBoundary: Int? = null
        var bottomBoundary: Int? = null
        var leftBoundary: Int? = null
        var rightBoundary: Int? = null

        // Retrieve Uber-specific ride types
        val uberRideTypes = UberParser.getRideTypes()

        // Iterate over each text block from the OCR result
        for (block in fullScreenResult.textBlocks) {
            val box = block.boundingBox ?: continue
            val blockText = block.text

        // Determine top boundary: look for Uber ride type text.
        for (ride in uberRideTypes) {
            if (blockText.contains(ride, ignoreCase = true)) {
                topBoundary = if (topBoundary == null) box.top else minOf(topBoundary, box.top)
                break
            }
        }

        // Determine bottom boundary: look for "Accept" or "Match"
        if (blockText.contains("Accept", ignoreCase = true) || blockText.contains("Match", ignoreCase = true)) {
            bottomBoundary = if (bottomBoundary == null) box.bottom else maxOf(bottomBoundary, box.bottom)
        }

        // Determine left boundary: look for fare price indicated by "$"
        if (blockText.contains("$")) {
            leftBoundary = if (leftBoundary == null) box.left else minOf(leftBoundary, box.left)
        }

        // Determine right boundary: look for a block that is exactly "x" or "X" (close button)
        if (blockText.trim().equals("x", ignoreCase = true)) {
            rightBoundary = if (rightBoundary == null) box.right else maxOf(rightBoundary, box.right)
        }
    }

    // If any boundary is missing, log a message and fail the cropping.
    if (topBoundary == null || bottomBoundary == null || leftBoundary == null || rightBoundary == null) {
//        FileLogger.log("ScreenCaptureService", "Incomplete boundaries detected; cropping failed.")
        return null
    }

    // Apply 5px padding adjustments:
    // Subtract 5 from topBoundary and leftBoundary (but not below 0).
    topBoundary = (topBoundary - 5).coerceAtLeast(0)
    leftBoundary = (leftBoundary - 5).coerceAtLeast(0)
    // Add 5 to bottomBoundary and rightBoundary, but don't exceed bitmap dimensions.
    bottomBoundary = (bottomBoundary + 5).coerceAtMost(fullBitmap.height)
    rightBoundary = (rightBoundary + 5).coerceAtMost(fullBitmap.width)

    // Log the computed boundaries for debugging.
//    FileLogger.log("ScreenCaptureService", "Enhanced Crop Boundaries with Padding: top=$topBoundary, bottom=$bottomBoundary, left=$leftBoundary, right=$rightBoundary")

    // Compute width and height.
    val cropWidth = rightBoundary - leftBoundary
    val cropHeight = bottomBoundary - topBoundary

        // Log the computed boundaries for debugging
//        FileLogger.log("ScreenCaptureService", "Enhanced Crop Boundaries: top=$topBoundary, bottom=$bottomBoundary, left=$leftBoundary, right=$rightBoundary")

        // Return the cropped bitmap
        return Bitmap.createBitmap(fullBitmap, leftBoundary, topBoundary, cropWidth, cropHeight)
    }
}

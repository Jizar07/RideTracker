package com.stoffeltech.ridetracker.services

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.stoffeltech.ridetracker.LyftParser
import com.stoffeltech.ridetracker.R
import com.stoffeltech.ridetracker.SettingsActivity
import com.stoffeltech.ridetracker.uber.UberParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.graphics.Bitmap
import android.os.Environment
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.stoffeltech.ridetracker.services.RideInfo



class ScreenCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "ScreenCaptureServiceChannel"
        private const val NOTIFICATION_ID = 1
        var isRunning = false
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenshotTaken = false
    private var lastRequestFingerprint: String? = null
    private var isProcessingFrame: Boolean = false
    private var pendingScreenshot: Boolean = false
    private var pendingRideType: String = "Unknown"

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())




    // Throttle OCR calls to once every second.
    private var lastAIUpdateTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Capture")
            .setContentText("Capturing screen in background")
            .setSmallIcon(R.drawable.logo)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")
        if (resultCode == Activity.RESULT_OK && data != null) {
            startScreenCapture(resultCode, data)
        } else {
            Log.e("ScreenCaptureService", "Invalid result code or data")
            stopSelf()
        }
        return START_STICKY
    }
    private fun saveBitmapToFile(bitmap: Bitmap, rideType: String) {
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
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            Log.d("ScreenCaptureService", "Screenshot saved to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error saving screenshot: ${e.message}")
        }
    }

    // --- New ML Kit OCR function ---
    @SuppressLint("UnsafeOptInUsageError")
    private suspend fun extractTextMLKit(bitmap: Bitmap): String {
        return withContext(Dispatchers.IO) {  // Ensures ML Kit runs in the background
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            try {
                val visionText = recognizer.process(image).await()
                visionText.text
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "MLKit OCR processing error: ${e.message}")
                "Error processing text"
            }
        }
    }

    // --- End ML Kit OCR function ---

    @SuppressLint("DefaultLocale")
    private fun startScreenCapture(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d("ScreenCaptureService", "MediaProjection has stopped")
                stopSelf()
            }
        }, null)

        // Obtain full screen dimensions via DisplayManager.
        val metrics = DisplayMetrics()
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays
        if (displays.isNotEmpty()) {
            displays[0].getRealMetrics(metrics)
        } else {
            Log.e("ScreenCaptureService", "No displays found!")
            stopSelf()
            return
        }
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        Log.d("ScreenCaptureService", "Full screen dimensions: ${width}x$height, density: $density")

        // Create an ImageReader to capture frames.
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            while (true) {
                val image = reader.acquireLatestImage() ?: break  // Break if no image available
                try {
                    val imgWidth = image.width
                    val imgHeight = image.height
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * imgWidth

                    // Create the raw bitmap directly with no cropping, scaling, or any other processing.
                    val rawBitmap = Bitmap.createBitmap(
                        imgWidth + rowPadding / pixelStride,
                        imgHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    rawBitmap.copyPixelsFromBuffer(buffer)
                    if (pendingScreenshot) {
                        // Save the current frame with overlay
                        saveBitmapToFile(rawBitmap, pendingRideType)
                        pendingScreenshot = false  // Clear the flag so we don't save repeatedly
                        // (Optionally, you could continue here if you do not want further processing on this frame)
                    }
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastAIUpdateTime > 1_000) {
                        // If a frame is already processing, skip processing this one.
                        if (isProcessingFrame) {
                            continue
                        }
                        lastAIUpdateTime = currentTime
                        isProcessingFrame = true

                        serviceScope.launch {
                            try {
                                // Create an InputImage for ML Kit.
                                val imageForText = InputImage.fromBitmap(rawBitmap, 0)
                                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                                val prefs = PreferenceManager.getDefaultSharedPreferences(this@ScreenCaptureService)
                                val visionText = recognizer.process(imageForText).await()
                                val ocrLines = visionText.text.split("\n")
                                ocrLines.forEachIndexed { index, line ->
                                    Log.d("OCROutput", "OCR Line ${index + 1}: $line")
                                }
                                val rideTypeBlocks = visionText.textBlocks.filter { block ->
                                    block.text.contains("Uber", ignoreCase = true) ||
                                            block.text.contains("Delivery", ignoreCase = true)
                                }
                                val minTop = if (rideTypeBlocks.isNotEmpty()) {
                                    rideTypeBlocks.minOf { it.boundingBox?.top ?: Int.MAX_VALUE }
                                } else {
                                    0
                                }
                                val filteredText = visionText.textBlocks
                                    .filter { (it.boundingBox?.top ?: 0) >= minTop }
                                    .joinToString("\n") { it.text }
                                val fixedText = filteredText.replace("l", "1").replace("L", "1")

                                // --- Improved block extraction logic ---
                                val blocks = fixedText.split("\n").fold(mutableListOf<StringBuilder>()) { acc, line ->
                                    if (acc.isEmpty() || line.trim().isEmpty()) {
                                        acc.add(StringBuilder())
                                    } else {
                                        acc.last().appendLine(line)
                                    }
                                    acc
                                }.map { it.toString().trim() }.filter { it.isNotEmpty() }

                                val requiredKeywords = listOf("\\$", "mi", "mins", "trip", "away", "Verified", "Accept", "Match")
                                val candidateBlocks = blocks.filter { block ->
                                    requiredKeywords.all { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(block) }
                                }
                                val tripRequestText = candidateBlocks.maxByOrNull { block ->
                                    requiredKeywords.sumOf { Regex(it, RegexOption.IGNORE_CASE).findAll(block).count() }
                                } ?: fixedText
                                // --- End improved block extraction logic ---

                                val rideInfo = parseRideInfo(tripRequestText)
                                if (rideInfo == null) {
                                    FloatingOverlayService.hideOverlay()
                                    return@launch
                                }
                                val fareVal = rideInfo.fare ?: 0.0
                                val pickupDistanceVal = rideInfo.pickupDistance ?: 0.0
                                val tripDistanceVal = rideInfo.tripDistance ?: 0.0
                                val totalMiles = pickupDistanceVal + tripDistanceVal

                                val pickupTimeVal = rideInfo.pickupTime ?: 0.0
                                val tripTimeVal = rideInfo.tripTime ?: 0.0
                                val totalMinutes = pickupTimeVal + tripTimeVal

                                val validAction = tripRequestText.contains("Accept", ignoreCase = true) ||
                                        tripRequestText.contains("Match", ignoreCase = true)

                                if (rideInfo.rideType?.equals("Delivery", ignoreCase = true) == true) {
                                    if (totalMiles <= 0.0 || totalMinutes <= 0.0 || !validAction) {
                                        FloatingOverlayService.hideOverlay()
                                        return@launch
                                    }
                                } else {
                                    if (fareVal <= 0.0 || totalMiles <= 0.0 || totalMinutes <= 0.0 || !validAction) {
                                        FloatingOverlayService.hideOverlay()
                                        return@launch
                                    }
                                }

                                UberParser.logUberRideRequest(rideInfo, "", "", "Accept", false)

                                val bonus = prefs.getFloat(SettingsActivity.KEY_BONUS_RIDE, 0.0f).toDouble()
                                val adjustedFare = fareVal + bonus
                                val adjustedFareStr = String.format("%.2f", adjustedFare)
                                val currentFingerprint = adjustedFareStr

                                if (currentFingerprint != lastRequestFingerprint) {
                                    pendingRideType = rideInfo.rideType ?: "Unknown"
                                    pendingScreenshot = true
                                    lastRequestFingerprint = currentFingerprint
                                }

                                if (totalMiles <= 0.0 && totalMinutes <= 0.0 && adjustedFare <= 0.0) {
                                    FloatingOverlayService.hideOverlay()
                                    return@launch
                                }

                                val pricePerMile = if (totalMiles > 0) adjustedFare / totalMiles else 0.0
                                val totalHours = totalMinutes / 60.0
                                val pricePerHour = if (totalHours > 0) adjustedFare / totalHours else 0.0
                                val formattedPricePerMile = String.format("%.2f", pricePerMile)
                                val formattedPricePerHour = String.format("%.2f", pricePerHour)
                                val formattedTotalMiles = String.format("%.1f", totalMiles)
                                val formattedTotalMinutes = String.format("%.1f", totalMinutes)

                                val acceptPerMile = prefs.getFloat(SettingsActivity.KEY_ACCEPT_MILE, 1.0f).toDouble()
                                val declinePerMile = prefs.getFloat(SettingsActivity.KEY_DECLINE_MILE, 0.75f).toDouble()
                                val acceptPerHour = prefs.getFloat(SettingsActivity.KEY_ACCEPT_HOUR, 25.0f).toDouble()
                                val declinePerHour = prefs.getFloat(SettingsActivity.KEY_DECLINE_HOUR, 20.0f).toDouble()
                                val fareLow = prefs.getFloat(SettingsActivity.KEY_FARE_LOW, 5.0f).toDouble()
                                val fareHigh = prefs.getFloat(SettingsActivity.KEY_FARE_HIGH, 10.0f).toDouble()
                                val costPerMile = prefs.getFloat(SettingsActivity.KEY_COST_DRIVING, 0.20f).toDouble()
                                val profit = adjustedFare - (costPerMile * totalMiles)
                                val profitStr = String.format("$%.2f", profit)
                                val profitColorInt = if (profit >= 0) Color.GREEN else Color.RED

                                val pmileColor = when {
                                    pricePerMile < declinePerMile -> "red"
                                    pricePerMile < acceptPerMile  -> "yellow"
                                    else                          -> "green"
                                }
                                val phourColor = when {
                                    pricePerHour < declinePerHour -> "red"
                                    pricePerHour < acceptPerHour  -> "yellow"
                                    else                          -> "green"
                                }
                                val fareColor = when {
                                    adjustedFare < fareLow  -> "red"
                                    adjustedFare < fareHigh -> "yellow"
                                    else                    -> "green"
                                }

                                val fareColorInt = when (fareColor) {
                                    "red" -> Color.RED
                                    "yellow" -> Color.YELLOW
                                    else -> Color.GREEN
                                }
                                val pMileColorInt = when (pmileColor) {
                                    "red" -> Color.RED
                                    "yellow" -> Color.YELLOW
                                    else -> Color.GREEN
                                }
                                val pHourColorInt = when (phourColor) {
                                    "red" -> Color.RED
                                    "yellow" -> Color.YELLOW
                                    else -> Color.GREEN
                                }

                                FloatingOverlayService.updateOverlay(
                                    rideType = rideInfo.rideType ?: "Unknown",
                                    fare = "${'$'}$adjustedFareStr",
                                    fareColor = fareColorInt,
                                    pMile = "${'$'}$formattedPricePerMile",
                                    pMileColor = pMileColorInt,
                                    pHour = "${'$'}$formattedPricePerHour",
                                    pHourColor = pHourColorInt,
                                    miles = formattedTotalMiles,
                                    minutes = formattedTotalMinutes,
                                    profit = profitStr,
                                    profitColor = profitColorInt,
                                    rating = rideInfo.rating?.toString() ?: "N/A",
                                    stops = rideInfo.stops ?: ""
                                )
                            } catch (e: Exception) {
                                // Optionally log the error.
                            } finally {
                                // Reset the flag once processing is complete.
                                isProcessingFrame = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Optionally log error processing this image.
                } finally {
                    image.close()
                }
            }
        }, Handler(Looper.getMainLooper()))


        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
        Log.d("ScreenCaptureService", "Screen capture started")
    }

    private fun parseRideInfo(text: String): com.stoffeltech.ridetracker.services.RideInfo? {
        // Remove header labels.
        val headerKeywords = listOf("p/Mi1e", "Minutes", "Per Mi1e", "Per Minute")
        val cleanedText = text.lines()
            .filter { line -> headerKeywords.none { header -> line.trim().equals(header, ignoreCase = true) } }
            .joinToString("\n")
        // Determine if this is a Lyft request by checking for "yft" (ignoring case).
        return if (cleanedText.contains("yft", ignoreCase = true)) {
            LyftParser.parse(cleanedText)
        } else {
            UberParser.parse(cleanedText)
        }
    }

    // --- End ride details parsing ---

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        isRunning = false
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        screenshotTaken = false
        Log.d("ScreenCaptureService", "Screen capture stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }
}

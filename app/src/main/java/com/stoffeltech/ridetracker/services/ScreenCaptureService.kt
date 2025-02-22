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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.stoffeltech.ridetracker.LyftParser
import com.stoffeltech.ridetracker.R
import com.stoffeltech.ridetracker.uber.UberParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException



class ScreenCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "ScreenCaptureServiceChannel"
        private const val NOTIFICATION_ID = 1
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Throttle OCR calls to once every second.
    private var lastAIUpdateTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
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

    // --- New ML Kit OCR function ---
    @SuppressLint("UnsafeOptInUsageError")
    private suspend fun extractTextMLKit(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                cont.resume(visionText.text)
            }
            .addOnFailureListener { exception ->
                cont.resumeWithException(exception)
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
            val image = reader.acquireLatestImage()
            if (image != null) {
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

                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastAIUpdateTime > 1_000) {
                        lastAIUpdateTime = currentTime

                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                // Extract text from the raw high-resolution bitmap.
                                val extractedText = extractTextMLKit(rawBitmap)
                                // Replace all occurrences of 'l' and 'L' with '1'
                                val fixedText = extractedText.replace("l", "1").replace("L", "1")

                                // --- Improved block extraction logic ---
                                val blocks = fixedText.split("\n").fold(mutableListOf<StringBuilder>()) { acc, line ->
                                    if (acc.isEmpty() || line.trim().isEmpty()) {
                                        acc.add(StringBuilder())
                                    } else {
                                        acc.last().appendLine(line)
                                    }
                                    acc
                                }.map { it.toString().trim() }.filter { it.isNotEmpty() }

                                // Define required keywords for the trip request block.
                                val requiredKeywords = listOf("\\$", "mi", "mins", "trip", "away", "Verified", "Accept", "Match")
                                val candidateBlocks = blocks.filter { block ->
                                    requiredKeywords.all { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(block) }
                                }
                                // Choose the candidate block with the highest total count of required keywords.
                                val tripRequestText = candidateBlocks.maxByOrNull { block ->
                                    requiredKeywords.sumOf { Regex(it, RegexOption.IGNORE_CASE).findAll(block).count() }
                                } ?: fixedText
                                Log.d("ScreenCaptureService", "Trip request block text: $tripRequestText")
                                // --- End improved block extraction logic ---

                                val rideInfo = parseRideInfo(tripRequestText)
                                if (rideInfo == null) {
                                    FloatingOverlayService.hideOverlay()
                                    return@launch
                                }
                                // At this point, you have a valid rideInfo object.
                                // Here you’d retrieve or extract the pickup and dropoff location strings, and the action button text.
                                val isVerified = tripRequestText.contains("Verified", ignoreCase = true)
                                val pickupAddress = rideInfo.pickupLocation ?: "N/A"
                                val dropoffAddress = rideInfo.tripLocation ?: "N/A"
                                val actionButtonText = "Accept"

                                // Log the structured Uber ride request using your helper.
                                UberParser.logUberRideRequest(rideInfo, pickupAddress, dropoffAddress, actionButtonText, isVerified)

                                // Continue with updating the overlay if needed.

                                val pickupDistanceVal = rideInfo.pickupDistance ?: 0.0
                                val tripDistanceVal = rideInfo.tripDistance ?: 0.0
                                val totalMiles = pickupDistanceVal + tripDistanceVal

                                val pickupTimeVal = rideInfo.pickupTime ?: 0.0
                                val tripTimeVal = rideInfo.tripTime ?: 0.0
                                val totalMinutes = pickupTimeVal + tripTimeVal

                                val fareVal = rideInfo.fare ?: 0.0

                                // If no valid ride is detected, auto-vanish the overlay.
                                if (totalMiles <= 0.0 && totalMinutes <= 0.0 && fareVal <= 0.0) {
                                    FloatingOverlayService.hideOverlay()
                                    return@launch
                                }

                                val pricePerMile = if (totalMiles > 0) fareVal / totalMiles else 0.0
                                val totalHours = totalMinutes / 60.0
                                val pricePerHour = if (totalHours > 0) fareVal / totalHours else 0.0

                                val formattedPricePerMile = String.format("%.2f", pricePerMile)
                                val formattedPricePerHour = String.format("%.2f", pricePerHour)
                                val formattedTotalMiles = String.format("%.1f", totalMiles)
                                val formattedTotalMinutes = String.format("%.1f", totalMinutes)

                                val pmileColor = when {
                                    pricePerMile < 0.75 -> "red"
                                    pricePerMile < 1.0  -> "yellow"
                                    else                -> "green"
                                }
                                val phourColor = when {
                                    pricePerHour < 20   -> "red"
                                    pricePerHour < 25   -> "yellow"
                                    else                -> "green"
                                }
                                val fareColor = when {
                                    fareVal < 5 -> "red"
                                    fareVal < 10 -> "yellow"
                                    else -> "green"
                                }

                                // Convert color string to actual color int
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

                                // Update the overlay with the new separate values:
                                FloatingOverlayService.updateOverlay(
                                    rideType = rideInfo.rideType ?: "Unknown",
                                    fare = "${'$'}$fareVal",
                                    fareColor = fareColorInt,
                                    pMile = "${'$'}$formattedPricePerMile",
                                    pMileColor = pMileColorInt,
                                    pHour = "${'$'}$formattedPricePerHour",
                                    pHourColor = pHourColorInt,
                                    miles = formattedTotalMiles,
                                    minutes = formattedTotalMinutes
                                )

                            } catch (e: Exception) {
                                Log.e("ScreenCaptureService", "MLKit OCR processing error: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ScreenCaptureService", "Error processing image: ${e.message}")
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

    // --- Ride Info Parsing ---
    data class RideInfo(
        val rideType: String?,
        val fare: Double?,
        val rating: Double?,
        val pickupTime: Double?,
        val pickupDistance: Double?,
        val tripTime: Double?,
        val tripDistance: Double?
    )

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
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
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

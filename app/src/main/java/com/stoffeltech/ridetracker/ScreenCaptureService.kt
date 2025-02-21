package com.stoffeltech.ridetracker

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d("ScreenCaptureService", "MediaProjection has stopped")
                stopSelf()
            }
        }, null)

        // Obtain full screen dimensions via DisplayManager.
        val metrics = DisplayMetrics()
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
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
                                Log.d("ScreenCaptureService", "Fixed OCR text: $fixedText")

                                // For simplicity, we use the fixed text as our filtered text.
                                Log.d("ScreenCaptureService", "Filtered text: $fixedText")

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
                                val requiredKeywords = listOf("\\$","mi", "mins", "trip", "away", "Verified", "Accept", "Match")
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
                                    fare = "${'$'}$fareVal", fareColor = fareColorInt,
                                    pMile = "${'$'}$formattedPricePerMile", pMileColor = pMileColorInt,
                                    pHour = "${'$'}$formattedPricePerHour", pHourColor = pHourColorInt,
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

    private fun parseRideInfo(text: String): RideInfo? {
        // Remove header labels.
        val headerKeywords = listOf("p/Mi1e", "Minutes", "Per Mi1e", "Per Minute")
        val cleanedText = text.lines()
            .filter { line -> headerKeywords.none { header -> line.trim().equals(header, ignoreCase = true) } }
            .joinToString("\n")
        val lines = cleanedText.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // Determine if this is a Lyft request by checking for "yft" (case-insensitive).
        val isLyft = lines.any { it.contains("yft", ignoreCase = true) }
        if (isLyft) {
            // Parsing for Lyft.
            var fare: Double? = null
            var pickupTime: Double? = null
            var pickupDistance: Double? = null
            var tripTime: Double? = null
            var tripDistance: Double? = null
            val rideType = "Lyft"

            // Find the fare/ride type line: must contain both "$" and "yft" (ignoring case).
            val fareLine = lines.find { it.contains("$") && it.contains("yft", ignoreCase = true) }
            if (fareLine != null) {
                // Use the existing fare extraction regex.
                val fareRegex = Regex("""\$(\d+(?:\.\d+)?)""")
                val fareMatch = fareRegex.find(fareLine)
                if (fareMatch != null) {
                    fare = fareMatch.groupValues[1].toDoubleOrNull()
                }
            }
            // Assume pickup info is the line immediately following the fare line.
            val fareLineIndex = lines.indexOf(fareLine)
            if (fareLineIndex != -1) {
                // Instead of fixed positions, look for the first two lines after the fare line that contain pickup/trip info.
                val infoLines = lines.drop(fareLineIndex + 1)
                    .filter { it.contains("min", ignoreCase = true) && it.contains("mi", ignoreCase = true) }
                if (infoLines.size >= 2) {
                    var pickupLine = infoLines[0]
                    var tripLine = infoLines[1]
                    // Remove any leading non-digit characters (ignore stray symbols, but do not convert them to digits).
                    pickupLine = pickupLine.replace(Regex("^[^0-9]+"), "")
                    tripLine = tripLine.replace(Regex("^[^0-9]+"), "")
                    val pickupRegex = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*min.*?([0-9]+(?:\\.[0-9]+)?)\\s*mi", RegexOption.IGNORE_CASE)
                    val pickupMatch = pickupRegex.find(pickupLine)
                    if (pickupMatch != null) {
                        pickupTime = pickupMatch.groupValues[1].toDoubleOrNull()
                        pickupDistance = pickupMatch.groupValues[2].toDoubleOrNull()
                    }
                    val tripRegex = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*min.*?([0-9]+(?:\\.[0-9]+)?)\\s*mi", RegexOption.IGNORE_CASE)
                    val tripMatch = tripRegex.find(tripLine)
                    if (tripMatch != null) {
                        tripTime = tripMatch.groupValues[1].toDoubleOrNull()
                        tripDistance = tripMatch.groupValues[2].toDoubleOrNull()
                    }
                }
            }
            return RideInfo(
                rideType = rideType,
                fare = fare,
                rating = null,
                pickupTime = pickupTime,
                pickupDistance = pickupDistance,
                tripTime = tripTime,
                tripDistance = tripDistance
            )
        } else {
            // Parsing for Uber (or other) requests.
            var fare: Double? = null
            var rideType: String? = null
            val rideTypeRegex = Regex("(Uber|Lyft|Comfort|Premier|Exclusive|Pet)", RegexOption.IGNORE_CASE)
            for (line in lines) {
                if (line.contains("$") && rideTypeRegex.containsMatchIn(line)) {
                    rideType = rideTypeRegex.find(line)?.groupValues?.get(1)
                    val dollarIndex = line.indexOf("$")
                    if (dollarIndex != -1) {
                        val fareSubstring = line.substring(dollarIndex)
                        val numericFare = fareSubstring.filter { it.isDigit() || it == '.' || it == ',' }
                        fare = numericFare.replace(",", ".").toDoubleOrNull()
                        if (fare != null) break
                    }
                }
            }
            if (fare == null) {
                for (i in lines.indices) {
                    if (rideTypeRegex.containsMatchIn(lines[i])) {
                        rideType = rideTypeRegex.find(lines[i])?.groupValues?.get(1)
                        if (i + 1 < lines.size && lines[i + 1].startsWith("$")) {
                            val rawFareLine = lines[i + 1].removePrefix("$").trim()
                            val numericFare = rawFareLine.filter { it.isDigit() || it == '.' || it == ',' }
                            fare = numericFare.replace(",", ".").toDoubleOrNull()
                            if (fare != null) break
                        }
                    }
                }
            }
            if (fare == null) {
                val pickupRegex = Regex(".*\\d+\\s*mins.*?\\(.*?mi.*?away", RegexOption.IGNORE_CASE)
                val pickupIndex = lines.indexOfFirst { pickupRegex.containsMatchIn(it) }
                if (pickupIndex > 0) {
                    for (j in (pickupIndex - 1) downTo 0) {
                        if (lines[j].contains("$")) {
                            val rawFareLine = lines[j].replace("$", "").trim()
                            val numericFare = rawFareLine.filter { it.isDigit() || it == '.' || it == ',' }
                            fare = numericFare.replace(",", ".").toDoubleOrNull()
                            if (fare != null) break
                        }
                    }
                }
            }
            val ratingRegex = Regex("★\\s*([0-9]+(?:\\.[0-9]+)?)")
            val rating = ratingRegex.find(cleanedText)?.groupValues?.get(1)?.toDoubleOrNull()
            val pickupTimeRegex = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*mins.*?\\(([0-9]+(?:\\.[0-9]+)?)\\s*mi\\).*?away", RegexOption.IGNORE_CASE)
            val pickupMatch = pickupTimeRegex.find(cleanedText)
            val pickupTime = pickupMatch?.groupValues?.get(1)?.toDoubleOrNull()
            val pickupDistance = pickupMatch?.groupValues?.get(2)?.toDoubleOrNull()
            val tripRegex = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*mins.*?\\(([0-9]+(?:\\.[0-9]+)?)\\s*mi\\).*?trip", RegexOption.IGNORE_CASE)
            val tripMatch = tripRegex.find(cleanedText)
            val tripTime = tripMatch?.groupValues?.get(1)?.toDoubleOrNull()
            val tripDistance = tripMatch?.groupValues?.get(2)?.toDoubleOrNull()

            if (fare == null && pickupTime == null && tripTime == null) return null
            return RideInfo(
                rideType = rideType,
                fare = fare,
                rating = rating,
                pickupTime = pickupTime,
                pickupDistance = pickupDistance,
                tripTime = tripTime,
                tripDistance = tripDistance
            )
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
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }
}

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject  // <-- Make sure this import is present

class ScreenCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "ScreenCaptureServiceChannel"
        private const val NOTIFICATION_ID = 1
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Create our AI and OCR helper instances.
    private val geminiAI = GeminiAI()
    private val screenTextReader = ScreenTextReader()

    // Throttle AI calls to once every 10 seconds.
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

                    val bitmap = Bitmap.createBitmap(
                        imgWidth + rowPadding / pixelStride,
                        imgHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)

                    val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, imgWidth, imgHeight)
                    val mutableBitmap = croppedBitmap.copy(Bitmap.Config.ARGB_8888, true)

                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastAIUpdateTime > 1_000) {
                        lastAIUpdateTime = currentTime

                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val extractedText = screenTextReader.extractText(mutableBitmap)
                                val allLines = extractedText.split("\n")
                                // Distinguish between Uber and Lyft keywords
                                val uberPatterns = listOf("Uber", "UberX", "UberX Priority", "Priority", "Uber Green", "Uber Pet", "Comfort", "Premier", "Exclusive")
                                val lyftPatterns = listOf("Lyft", "Extra Confort", "Lyft XL", "Lux", "Shared")
                                val generalPatterns = listOf("mi", "mins", "trip", "away", "★", "Verified", "Accept", "Match", Regex("""\$\d"""))
                                val blackBoxPatterns = uberPatterns + lyftPatterns + generalPatterns
                                val filteredLines = allLines.filter { line ->
                                    blackBoxPatterns.any { pattern ->
                                        when (pattern) {
                                            is String -> line.contains(pattern, ignoreCase = true)
                                            is Regex  -> pattern.containsMatchIn(line)
                                            else      -> false
                                        }
                                    }
                                }
                                val blackBoxText = filteredLines.joinToString("\n")
                                Log.d("ScreenCaptureService", "Filtered blackBoxText: $blackBoxText")

                                val prompt = """
                                    You are a rideshare offer calculator. The fair information is inside a black box with blue borders and it contains the following information:
                                    - rideType, in-cased in a capsule styled background inside the box
                                    - $, This indicates the fare with a dollar sign in front of it, may or may not include surge icon
                                    - ★, this indicates the rating, is only a number (example: ★ 5.00)
                                    - pickupTime, pickupDistance for example: 3 mins (1.7 mi) away
                                    - tripTime, tripDistance for example: 5 mins (1.5 mi) trip
                                    - addresses or locations for pickup and dropoff
                                
                                    Ignore any text that is not part of that black box with blue borders. From the entire screen text below, 
                                    extract only that black-box content and return valid JSON with keys:
                                    {
                                      "rideType": string,
                                      "$": number,
                                      "★": number,
                                      "pickupTime": number,
                                      "pickupDistance": number,
                                      "tripTime": number,
                                      "tripDistance": number
                                    }
                                    Calculate the following:
                                    - totalMiles (miles) by adding pickup miles and trip miles
                                    - totalMinutes (minutes) by adding pickup minutes and trip minutes
                                    - pricePerMile (dollars per mile)
                                    - pricePerHour (dollars per hour)
                                    If something is missing, set it to null. No commentary or code blocks.
                                    The black box lines are: "$blackBoxText"
                                """.trimIndent()

                                val aiResponse = geminiAI.analyzeText(prompt)
                                Log.d("ScreenCaptureService", "AI Response: $aiResponse")

                                val cleanResponse = aiResponse
                                    .replace("```json", "", ignoreCase = true)
                                    .replace("```", "")
                                    .trim()

                                Log.d("ScreenCaptureService", "Cleaned AI Response: $cleanResponse")

                                val noTrailingCommas = cleanResponse
                                    .replace(Regex(",\\s*\\}"), "}")
                                    .replace(Regex(",\\s*\\]"), "]")

                                val jsonObject = JSONObject(noTrailingCommas)

                                val pickupDistance = if (!jsonObject.isNull("pickupDistance")) jsonObject.getDouble("pickupDistance") else 0.0
                                val tripDistance = if (!jsonObject.isNull("tripDistance")) jsonObject.getDouble("tripDistance") else 0.0
                                val totalMiles = pickupDistance + tripDistance

                                val pickupTime = if (!jsonObject.isNull("pickupTime")) jsonObject.getDouble("pickupTime") else 0.0
                                val tripTime = if (!jsonObject.isNull("tripTime")) jsonObject.getDouble("tripTime") else 0.0
                                val totalMinutes = pickupTime + tripTime

                                // Parse "fare" from AI. If missing, assume 0.0
                                val fare = if (!jsonObject.isNull("$")) jsonObject.getDouble("$") else 0.0

                                // If no valid ride is detected, auto-vanish the overlay.
                                if (totalMiles <= 0.0 && totalMinutes <= 0.0 && fare <= 0.0) {
                                    FloatingOverlayService.hideOverlay()
                                    return@launch
                                }

                                val pricePerMile = if (totalMiles > 0) fare / totalMiles else 0.0
                                val totalHours = totalMinutes / 60.0
                                val pricePerHour = if (totalHours > 0) fare / totalHours else 0.0

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
                                    fare < 5   -> "red"
                                    fare < 10  -> "yellow"
                                    else       -> "green"
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
                                    fare = "${'$'}$fare", fareColor = fareColorInt,
                                    pMile = "${'$'}$formattedPricePerMile", pMileColor = pMileColorInt,
                                    pHour = "${'$'}$formattedPricePerHour", pHourColor = pHourColorInt,
                                    miles = formattedTotalMiles,
                                    minutes = formattedTotalMinutes
                                )

                            } catch (e: Exception) {
                                Log.e("ScreenCaptureService", "AI processing error: ${e.message}")
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

package com.stoffeltech.ridetracker.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Environment
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.preference.PreferenceManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.stoffeltech.ridetracker.BuildConfig
import com.stoffeltech.ridetracker.LyftParser
import com.stoffeltech.ridetracker.SettingsActivity
import com.stoffeltech.ridetracker.uber.UberParser
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class AccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastRequestFingerprint: String? = null // ✅ Add this to track last request

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            val packageName = event.packageName?.toString() ?: ""
            val eventType = event.eventType

            // Dump the entire node tree for debugging
            dumpNodeTree(event.source)

            // Also get the text directly from the event
            val eventText = event.text.joinToString(" ")

            // Extract text from the accessibility node
            val sourceText = extractTextFromNode(event.source)
            val detectedText = if (sourceText.isNotBlank()) sourceText else eventText

//            Log.d("RideTracker", "Event source text: $sourceText")
//            Log.d("RideTracker", "Event.getText(): $eventText")
//            Log.d("RideTracker", "🔍 Detected App: $packageName | Event Type: $eventType | Text: $detectedText")


            // 🚖 Detect Uber/Lyft ride requests
            if (packageName.contains("uber") || packageName.contains("lyft")) {
                analyzeRideRequest(detectedText)
            }

            // 📂 Detect screenshots being opened in Google Files or other file managers
            if (packageName.contains("documentsui") || packageName.contains("filemanager")) {
//                Log.d("RideTracker", "📂 My Files is open. Checking if ride request is displayed.")
                analyzeRideRequest(detectedText)
            }
            if (packageName == "com.google.android.apps.photos") {
//                Log.d("RideTracker", "📂 Google Photos is open. Checking if ride request is displayed.")
                analyzeRideRequest(detectedText)
            }

        }
    }

    private fun analyzeRideRequest(text: String) {
        // Use UberParser's advanced parsing to check if the text contains a valid ride request.
        val rideInfo = UberParser.parse(text)
        if (rideInfo != null) {
//            Log.d("RideTracker", "🟢 Detected possible ride request: $text")
            processRideRequest(text)
        } else {
//            Log.d("RideTracker", "⚠️ No valid ride request detected on screen.")
        }
    }


    private fun extractTextFromNode(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val builder = StringBuilder()

        // Prefer node.text, but fallback to contentDescription if text is null.
        when {
            node.text != null -> builder.append(node.text.toString()).append(" ")
            node.contentDescription != null -> builder.append(node.contentDescription.toString()).append(" ")
        }

        for (i in 0 until node.childCount) {
            builder.append(extractTextFromNode(node.getChild(i)))
        }
        return builder.toString().trim()
    }


    private fun processRideRequest(text: String) {
        serviceScope.launch {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@AccessibilityService)
            val cleanedText = text.replace("l", "1").replace("L", "1")
            val requiredKeywords = listOf("\\$", "mi", "mins", "trip", "away", "Verified", "Accept", "Match")
            val candidateBlocks = cleanedText.split("\n").map { it.trim() }
                .filter { block -> requiredKeywords.all { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(block) } }
            val tripRequestText = if (candidateBlocks.isNotEmpty()) {
                candidateBlocks.maxByOrNull { block ->
                    requiredKeywords.sumOf { Regex(it, RegexOption.IGNORE_CASE).findAll(block).count() }
                } ?: cleanedText
            } else {
                cleanedText
            }

//            Log.d("RideTracker", "Full OCR Text: $text")

            val rideInfo = parseRideInfo(tripRequestText)
//            Log.d("RideTracker", "Parsed ride info: $rideInfo")
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

            // ✅ **Fingerprint Logic: Prevent duplicate requests**
            val adjustedFare = (rideInfo.fare ?: 0.0) + prefs.getFloat(SettingsActivity.KEY_BONUS_RIDE, 0.0f).toDouble()
            val fingerprint = "$adjustedFare-$totalMiles-$totalMinutes"

            if (fingerprint == lastRequestFingerprint) {
//                Log.d("RideTracker", "⚠️ Duplicate ride request detected. Skipping processing.")
                return@launch
            }
            lastRequestFingerprint = fingerprint // ✅ **Update fingerprint to prevent reprocessing**

            if (rideInfo != null) {
                // ✅ **Log Ride Request**
                UberParser.logUberRideRequest(rideInfo, "", "", "Accept", false)

                val bonus = prefs.getFloat(SettingsActivity.KEY_BONUS_RIDE, 0.0f).toDouble()
                val adjustedFare = (rideInfo.fare ?: 0.0) + bonus
                val totalMiles = (rideInfo.pickupDistance ?: 0.0) + (rideInfo.tripDistance ?: 0.0)
                val totalMinutes = (rideInfo.pickupTime ?: 0.0) + (rideInfo.tripTime ?: 0.0)

                // ✅ **Critical Coloring Logic**
                val pricePerMile = if (totalMiles > 0) adjustedFare / totalMiles else 0.0
                val pricePerHour = if (totalMinutes > 0) adjustedFare / (totalMinutes / 60.0) else 0.0

                val pmileColor = when {
                    pricePerMile < prefs.getFloat(SettingsActivity.KEY_DECLINE_MILE, 0.75f) -> Color.RED
                    pricePerMile < prefs.getFloat(SettingsActivity.KEY_ACCEPT_MILE, 1.0f) -> Color.YELLOW
                    else -> Color.GREEN
                }
                val phourColor = when {
                    pricePerHour < prefs.getFloat(SettingsActivity.KEY_DECLINE_HOUR, 20.0f) -> Color.RED
                    pricePerHour < prefs.getFloat(SettingsActivity.KEY_ACCEPT_HOUR, 25.0f) -> Color.YELLOW
                    else -> Color.GREEN
                }
                val fareColor = when {
                    adjustedFare < prefs.getFloat(SettingsActivity.KEY_FARE_LOW, 5.0f) -> Color.RED
                    adjustedFare < prefs.getFloat(SettingsActivity.KEY_FARE_HIGH, 10.0f) -> Color.YELLOW
                    else -> Color.GREEN
                }

                // ✅ **Final Validation: Prevent invalid rides from being processed**
                if (totalMiles <= 0.0 && totalMinutes <= 0.0 && adjustedFare <= 0.0) {
//                    Log.d("RideTracker", "❌ Invalid ride request detected (zero values). Hiding overlay.")
                    FloatingOverlayService.hideOverlay()
                    return@launch
                }

                // ✅ **Update Overlay When Ride is Detected**
                FloatingOverlayService.updateOverlay(
                    rideType = rideInfo.rideType ?: "Unknown",
                    fare = "$${String.format("%.2f", adjustedFare)}",
                    fareColor = fareColor,
                    pMile = "$${String.format("%.2f", pricePerMile)}",
                    pMileColor = pmileColor,
                    pHour = "$${String.format("%.2f", pricePerHour)}",
                    pHourColor = phourColor,
                    miles = String.format("%.1f", totalMiles),
                    minutes = String.format("%.1f", totalMinutes),
                    profit = "$${String.format("%.2f", adjustedFare - (prefs.getFloat(SettingsActivity.KEY_COST_DRIVING, 0.20f) * totalMiles))}",
                    profitColor = if (adjustedFare - (prefs.getFloat(SettingsActivity.KEY_COST_DRIVING, 0.20f) * totalMiles) >= 0) Color.GREEN else Color.RED,
                    rating = rideInfo.rating?.toString() ?: "N/A",
                    stops = rideInfo.stops ?: ""
                )
            } else {
//                Log.d("RideTracker", "⚠️ Detected text does NOT match a ride request.")
            }
        }
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
            Log.d("AccessibilityService", "✅ Screenshot saved to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("AccessibilityService", "❌ Error saving screenshot: ${e.message}")
        }
    }


    override fun onServiceConnected() {
        super.onServiceConnected()
        // Update the service configuration to listen to more event types.
        val info = serviceInfo
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 100
        serviceInfo = info
//        Log.d("RideTracker", "Accessibility Service Connected with updated config")

        // (Optional) Debug: simulate a request in debug mode.
//        if (BuildConfig.DEBUG) {
//            val testRideText = """
//            UberX
//            $23.45
//            3 mins (1.2 mi) away
//            5 mins (2.3 mi) trip
//            Pickup Location: 123 Main St
//            Dropoff Location: 456 Elm St
//        """.trimIndent()
//            analyzeRideRequest(testRideText)
//        }
    }
    private fun dumpNodeTree(node: AccessibilityNodeInfo?, indent: String = "") {
        if (node == null) return
        val nodeInfo = "Class: ${node.className} | Text: ${node.text} | ContentDesc: ${node.contentDescription}"
//        Log.d("RideTracker", "$indent$nodeInfo")
        for (i in 0 until node.childCount) {
            dumpNodeTree(node.getChild(i), "$indent  ")
        }
    }



    override fun onInterrupt() {
//        Log.w("RideTracker", "Accessibility Service Interrupted")
    }
}
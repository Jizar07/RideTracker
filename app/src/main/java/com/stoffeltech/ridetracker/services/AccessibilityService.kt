package com.stoffeltech.ridetracker.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
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

// ------------------ New Data Class Definition ------------------

/**
 * Data class to hold detailed information extracted from a ride request.
 * Used for both Uber and Delivery requests.
 */
data class UberRideDetails(
    val rideType: String?,         // e.g., "UberX" or "Delivery (2)"
    val isExclusive: Boolean,      // true if "Exclusive" is present
    val fare: Double?,             // ride price, e.g., 12.63 or 5.07
    val rating: Double?,           // rider rating, e.g., 5.00 (might be null for Delivery)
    val isVerified: Boolean,       // true if "Verified" is present (for Uber)
    val pickupTime: String?,       // e.g., "9 mins" or total time for Delivery
    val pickupDistance: String?,   // e.g., "3.3 mi" or total distance for Delivery
    val pickupAddress: String?,    // e.g., "Leeland Heights Blvd & Robert Ave, Lehigh Acres" (or delivery address)
    val dropoffTime: String?,      // e.g., "22 mins" (for Uber)
    val dropoffDistance: String?,  // e.g., "12.6 mi" (for Uber)
    val dropoffAddress: String?,   // e.g., "25th St SW & Gretchen Ave S, Lehigh Acres" (for Uber)
    val actionButton: String?      // e.g., "Accept" or "Match"
)

// ------------------ End New Data Class Definition ------------------

class AccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastRequestFingerprint: String? = null // ✅ Add this to track last request

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            val packageName = event.packageName?.toString() ?: ""
            val eventType = event.eventType
            val fullText = extractTextFromNode(event.source)

            // Dump the entire node tree for debugging
            dumpNodeTree(event.source)

            // Also get the text directly from the event
            val eventText = event.text.joinToString(" ")

            // Extract text from the accessibility node
            val sourceText = extractTextFromNode(event.source)
            val detectedText = sourceText.ifBlank { eventText }

            // Log the entire detected text for debugging
            Log.d("AccessibilityService", "Detected text: $detectedText")

//            if (packageName.equals("com.ubercab.driver", ignoreCase = true)) {
//                UberParser.debugTest()
//            }

            // Earnings extraction:
            // If the screen shows "last trip", skip daily earnings extraction.
//            if (packageName.contains("uber", ignoreCase = true)) {
//                processRideRequest(detectedText)
//                return
//            } else
            if (fullText.toLowerCase(Locale.getDefault()).contains("last trip")) {
                Log.d("AccessibilityService", "Detected 'last trip' in text; ignoring earnings extraction.")
            }
            // Also ignore if the text contains ride request keywords (such as away, total, trip, verified, accept, match)
            else if (listOf("away", "total", "verified", "accept", "match")
                        .any { fullText.lowercase(Locale.getDefault()).contains(it) }) {
                Log.d("AccessibilityService", "Detected ride request keywords in text; ignoring earnings extraction.")
            }
            else if (isSummaryScreenText(fullText)) {
                Log.d("AccessibilityService", "Summary screen detected; ignoring earnings node extraction.")
            } else {
                val earningsNode = findDailyEarningsNode(event.source)
                if (earningsNode != null) {
                    Log.d("AccessibilityService", "Found daily earnings node with text: ${earningsNode.text}")
                    val regex = "\\$(\\d+(?:\\.\\d{2})?)".toRegex()
                    val match = regex.find(earningsNode.text.toString())
                    val dailyEarnings = match?.groupValues?.get(1)?.toDoubleOrNull()
                    Log.d("AccessibilityService", "Extracted daily earnings: $dailyEarnings")
                    // Update RevenueTracker or perform further processing...
                } else {
                    Log.d("AccessibilityService", "No daily earnings node found with proper context.")
                }
            }

            // ------------------ Ride Request Detection ------------------

            // For Delivery requests: if detected text starts with "Delivery"
            if (detectedText.trim().startsWith("Delivery", ignoreCase = true)) {
                processDeliveryRideRequestWithDetails(detectedText)
            } else if (packageName.contains("uber", ignoreCase = true)) {
                processRideRequest(detectedText)
            } else if (packageName.contains("lyft", ignoreCase = true)) {
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
            // ------------------ End Ride Request Detection ------------------
        }
    }

    private fun isSummaryScreenText(fullText: String): Boolean {
        val lower = fullText.toLowerCase(Locale.getDefault())
        // List of summary keywords to ignore
        val summaryKeywords = listOf("stats", "breakdown", "earnings activity", "date picker", "filtertype", "back")
        if (summaryKeywords.any { lower.contains(it) }) {
            return true
        }
        // Check for multiple date ranges (e.g., "Feb 24 - Mar 3")
        val dateRangeRegex = Regex("[A-Za-z]{3}\\s*\\d{1,2}\\s*-\\s*[A-Za-z]{3}\\s*\\d{1,2}")
        val dateMatches = dateRangeRegex.findAll(fullText).toList()

        // Check for multiple day-of-week abbreviations.
        val dayRegex = Regex("\\b(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)[,]?\\b", RegexOption.IGNORE_CASE)
        val dayMatches = dayRegex.findAll(fullText).toList()

        return (dateMatches.size > 1) || (dayMatches.isNotEmpty())
    }

    // This function traverses upward from the candidate node to check context.
    private fun isDailyEarningsNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val text = node.text?.toString() ?: ""
        if (!text.matches(Regex("^\\$\\d+(\\.\\d{2})?\$"))) {
            return false
        }
        var current = node.parent
        val combinedParentText = StringBuilder()
        var levels = 0
        while (current != null && levels < 5) {
            current.text?.let { combinedParentText.append(it).append(" ") }
            current = current.parent
            levels++
        }
        val parentTextStr = combinedParentText.toString().trim()
        // Log the combined parent text for debugging
//        Log.d("AccessibilityService", "Combined parent text: '$parentTextStr'")
        val summaryPattern = Regex("(?i)\\b(stats|breakdown)\\b")
        if (summaryPattern.containsMatchIn(parentTextStr)) {
            Log.d("AccessibilityService", "Parent text contains summary keywords; rejecting node.")
            return false
        }
        Log.d("AccessibilityService", "Node accepted as daily earnings candidate.")
        return true
    }

    private fun findDailyEarningsNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.className == "android.widget.TextView") {
            val text = node.text?.toString() ?: ""
            if (text.matches(Regex("^\\$\\d+(\\.\\d{2})?\$"))) {
                if (isDailyEarningsNode(node)) {
                    return node
                }
            }
        }
        for (i in 0 until node.childCount) {
            val result = findDailyEarningsNode(node.getChild(i))
            if (result != null) return result
        }
        return null
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
        when {
            node.text != null -> builder.append(node.text.toString()).append(" ")
            node.contentDescription != null -> builder.append(node.contentDescription.toString()).append(" ")
        }
        for (i in 0 until node.childCount) {
            builder.append(extractTextFromNode(node.getChild(i)))
        }
        return builder.toString().trim()
    }

    @SuppressLint("DefaultLocale")
    private fun processRideRequest(text: String) {
        serviceScope.launch {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@AccessibilityService)
            val trimmedText = text.trim()
            if (!trimmedText.endsWith("Accept", ignoreCase = true) && !trimmedText.endsWith("Match", ignoreCase = true)) {
                Log.d("AccessibilityService", "Incomplete ride request text received; skipping processing: $text")
                return@launch
            }

//            val cleanedText = text.replace("l", "1").replace("L", "1")
            val requiredKeywords = listOf("\\$", "mi", "mins", "trip", "away", "Verified", "Accept", "Match")
            val candidateBlocks = text.split("\n").map { it.trim() }
                .filter { block -> requiredKeywords.all { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(block) } }
            val tripRequestText = if (candidateBlocks.isNotEmpty()) {
                candidateBlocks.maxByOrNull { block ->
                    requiredKeywords.sumOf { Regex(it, RegexOption.IGNORE_CASE).findAll(block).count() }
                } ?: text
            } else {
                text
            }
            val rideInfo = parseRideInfo(text)
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
            Log.d("AccessibilityService", "Validation Check: fare=$fareVal, totalMiles=$totalMiles, totalMinutes=$totalMinutes")


            val validAction = tripRequestText.contains("Accept", ignoreCase = true) ||
                    tripRequestText.contains("Match", ignoreCase = true)
            Log.d("AccessibilityService", "Valid Action Check: validAction=$validAction, tripRequestText='$tripRequestText'")


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

            val bonus = prefs.getFloat(SettingsActivity.KEY_BONUS_RIDE, 0.0f).toDouble()
            val adjustedFare = (rideInfo.fare ?: 0.0) + bonus
            val fingerprint = "$adjustedFare-$totalMiles-$totalMinutes"
            Log.d("AccessibilityService", "Valid ride request accepted, updating overlay with rideInfo: $rideInfo")


            if (fingerprint == lastRequestFingerprint) {
//                Log.d("RideTracker", "⚠️ Duplicate ride request detected. Skipping processing.")
                return@launch
            }
            lastRequestFingerprint = fingerprint // ✅ **Update fingerprint to prevent reprocessing**

            if (rideInfo != null) {
                UberParser.logUberRideRequest(rideInfo, "", "", rideInfo.actionButton ?: "N/A", false)

                val bonus = prefs.getFloat(SettingsActivity.KEY_BONUS_RIDE, 0.0f).toDouble()
                val adjustedFare = (rideInfo.fare ?: 0.0) + bonus
                val totalMiles = (rideInfo.pickupDistance ?: 0.0) + (rideInfo.tripDistance ?: 0.0)
                val totalMinutes = (rideInfo.pickupTime ?: 0.0) + (rideInfo.tripTime ?: 0.0)

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
        val headerKeywords = listOf("p/Mi1e", "Minutes", "Per Mi1e", "Per Minute")
        val cleanedText = text.lines()
            .filter { line -> headerKeywords.none { header -> line.trim().equals(header, ignoreCase = true) } }
            .joinToString("\n")
        // Determine if this is a Lyft request by checking for "yft" (ignoring case).
        return if (cleanedText.contains("Lyft", ignoreCase = true)) {
            LyftParser.parse(cleanedText)
        } else {
            UberParser.parse(cleanedText)
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

//        // TEMPORARY: Simulate a ride request for debugging
//        val testText = "UberX PriorityExclusive$7.555.005% Advantage included5 mins (1.3 mi) awayLakeside Dr & Zimmerman Ave, Lehigh Acres13 mins (7.8 mi) tripPennfield Ave & Theodore Vail St E, Lehigh AcresAccept"
//        Log.d("AccessibilityService", "Simulating ride request with testText: $testText")
//        processRideRequest(testText)
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

    // -------------------------- New Functions for Delivery Detailed Extraction --------------------------

    /**
     * Extracts detailed Delivery ride request information from the provided text.
     * This function adapts the block extraction logic from ScreenCaptureService for Delivery format.
     */
    private fun extractDeliveryRideDetails(text: String): UberRideDetails {
        val rideTypePattern = Regex("^(Delivery(?:\\s*\\((\\d+)\\))?)(Exclusive)?", RegexOption.IGNORE_CASE)
        val rideTypeMatch = rideTypePattern.find(text)
        val rideType = rideTypeMatch?.groups?.get(1)?.value
        val isExclusive = rideTypeMatch?.groups?.get(3) != null

        // 2. Extract fare using the $ sign.
        val farePattern = Regex("\\$([0-9]+\\.[0-9]{2})")
        val fareMatch = farePattern.find(text)
        val fare = fareMatch?.groups?.get(1)?.value?.toDoubleOrNull()

        val includesTip = text.contains("Includes expected tip", ignoreCase = true)

        val totalPattern = Regex("([0-9]+\\s*min)\\s*\\(\\s*([0-9]+\\.?[0-9]*)\\s*mi\\)", RegexOption.IGNORE_CASE)
        val totalMatch = totalPattern.find(text)
        val totalTime = totalMatch?.groups?.get(1)?.value
        val totalDistance = totalMatch?.groups?.get(2)?.value

        val addressPattern = Regex("total\\s*(.*?)\\s*(Accept|Match)", RegexOption.IGNORE_CASE)
        val addressMatch = addressPattern.find(text)
        val address = addressMatch?.groups?.get(1)?.value?.trim()

        val actionButtonPattern = Regex("(Accept|Match)\\b", RegexOption.IGNORE_CASE)
        val actionButtonMatch = actionButtonPattern.find(text)
        val actionButton = actionButtonMatch?.groups?.get(1)?.value

        return UberRideDetails(
            rideType = rideType,
            isExclusive = isExclusive,
            fare = fare,
            rating = null,
            isVerified = false,
            pickupTime = totalTime,
            pickupDistance = totalDistance,
            pickupAddress = address,
            dropoffTime = null,
            dropoffDistance = null,
            dropoffAddress = null,
            actionButton = actionButton
        )
    }

    /**
     * Processes a Delivery ride request using detailed extraction logic.
     * This method extracts the Delivery details from the accessibility event text,
     * logs the extracted details, and updates the floating overlay.
     */
    @SuppressLint("DefaultLocale")
    private fun processDeliveryRideRequestWithDetails(text: String) {
        serviceScope.launch {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@AccessibilityService)
            val details = extractDeliveryRideDetails(text)
            Log.d("AccessibilityService", "Extracted Delivery Ride Details: $details")
            if (details.actionButton.isNullOrEmpty()) {
                FloatingOverlayService.hideOverlay()
                return@launch
            }
            val adjustedFare = details.fare ?: 0.0
            FloatingOverlayService.updateOverlay(
                rideType = "${details.rideType ?: "Unknown"}${if(details.isExclusive) " Exclusive" else ""}",
                fare = "$${String.format("%.2f", adjustedFare)}",
                fareColor = Color.GREEN,
                pMile = "",
                pMileColor = Color.GREEN,
                pHour = "",
                pHourColor = Color.GREEN,
                miles = details.pickupDistance ?: "",
                minutes = details.pickupTime ?: "",
                profit = "",
                profitColor = Color.GREEN,
                rating = details.rating?.toString() ?: "N/A",
                stops = details.pickupAddress ?: ""
            )
        }
    }
}

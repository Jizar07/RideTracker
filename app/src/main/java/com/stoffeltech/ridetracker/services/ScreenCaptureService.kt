//package com.stoffeltech.ridetracker.services
//
//import android.accessibilityservice.AccessibilityService
//import android.accessibilityservice.AccessibilityServiceInfo
//import android.graphics.Bitmap
//import android.graphics.BitmapFactory
//import android.graphics.Color
//import android.os.Environment
//import android.util.Log
//import android.view.accessibility.AccessibilityEvent
//import android.view.accessibility.AccessibilityNodeInfo
//import androidx.preference.PreferenceManager
//import com.google.mlkit.vision.common.InputImage
//import com.google.mlkit.vision.text.TextRecognition
//import com.google.mlkit.vision.text.latin.TextRecognizerOptions
//import com.stoffeltech.ridetracker.BuildConfig
//import com.stoffeltech.ridetracker.LyftParser
//import com.stoffeltech.ridetracker.SettingsActivity
//import com.stoffeltech.ridetracker.uber.UberParser
//import kotlinx.coroutines.*
//import kotlinx.coroutines.tasks.await
//import java.io.File
//import java.io.FileOutputStream
//import java.text.SimpleDateFormat
//import java.util.Date
//import java.util.Locale
//
//class AccessibilityService : AccessibilityService() {
//
//    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
//    private var lastRequestFingerprint: String? = null // ✅ Add this to track last request
//
//    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
//        event?.let {
//            val packageName = event.packageName?.toString() ?: ""
//            val eventType = event.eventType
//            val fullText = extractTextFromNode(event.source)
//
//            // Dump the entire node tree for debugging
//            dumpNodeTree(event.source)
//
//            // Also get the text directly from the event
//            val eventText = event.text.joinToString(" ")
//
//            // Extract text from the accessibility node
//            val sourceText = extractTextFromNode(event.source)
//            val detectedText = if (sourceText.isNotBlank()) sourceText else eventText
//
//            // Log the entire detected text for debugging
//            Log.d("AccessibilityService", "Detected text: $detectedText")
//
//            // If the screen shows "last trip", skip daily earnings extraction.
//            if (fullText.toLowerCase(Locale.getDefault()).contains("last trip")) {
//                Log.d("AccessibilityService", "Detected 'last trip' in text; ignoring earnings extraction.")
//            } else if (isSummaryScreenText(fullText)) {
//                Log.d("AccessibilityService", "Summary screen detected; ignoring earnings node extraction.")
//            } else {
//                val earningsNode = findDailyEarningsNode(event.source)
//                if (earningsNode != null) {
//                    Log.d("AccessibilityService", "Found daily earnings node with text: ${earningsNode.text}")
//                    val regex = "\\$(\\d+(?:\\.\\d{2})?)".toRegex()
//                    val match = regex.find(earningsNode.text.toString())
//                    val dailyEarnings = match?.groupValues?.get(1)?.toDoubleOrNull()
//                    Log.d("AccessibilityService", "Extracted daily earnings: $dailyEarnings")
//                    // Update RevenueTracker or perform further processing...
//                } else {
//                    Log.d("AccessibilityService", "No daily earnings node found with proper context.")
//                }
//            }
//
//            // 🚖 Detect Uber/Lyft ride requests.
//            // For Uber, use the detailed extraction logic; for Lyft, keep existing logic.
//            if (packageName.contains("uber")) {
//                processUberRideRequestWithDetails(detectedText)
//            } else if (packageName.contains("lyft")) {
//                analyzeRideRequest(detectedText)
//            }
//
//            // 📂 Detect screenshots being opened in Google Files or other file managers
//            if (packageName.contains("documentsui") || packageName.contains("filemanager")) {
////                Log.d("RideTracker", "📂 My Files is open. Checking if ride request is displayed.")
//                analyzeRideRequest(detectedText)
//            }
//            if (packageName == "com.google.android.apps.photos") {
////                Log.d("RideTracker", "📂 Google Photos is open. Checking if ride request is displayed.")
//                analyzeRideRequest(detectedText)
//            }
//        }
//    }
//
//    private fun isSummaryScreenText(fullText: String): Boolean {
//        val lower = fullText.toLowerCase(Locale.getDefault())
//        // List of summary keywords to ignore
//        val summaryKeywords = listOf("stats", "breakdown", "earnings activity", "date picker", "filtertype", "back")
//        if (summaryKeywords.any { lower.contains(it) }) {
//            return true
//        }
//        // Check for multiple date ranges (e.g., "Feb 24 - Mar 3")
//        // This regex matches a simple date range pattern: three-letter month, a space, one or two digits, a dash, then another three-letter month and one or two digits.
//        val dateRangeRegex = Regex("[A-Za-z]{3}\\s*\\d{1,2}\\s*-\\s*[A-Za-z]{3}\\s*\\d{1,2}")
//        val dateMatches = dateRangeRegex.findAll(fullText).toList()
//
//        // Check for multiple day-of-week abbreviations.
//        val dayRegex = Regex("\\b(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)[,]?\\b", RegexOption.IGNORE_CASE)
//        val dayMatches = dayRegex.findAll(fullText).toList()
//
//        // If there are more than one date range, this is likely a summary page.
//        return (dateMatches.size > 1) || (dayMatches.isNotEmpty())
//    }
//
//    // This function traverses upward from the candidate node to check context.
//    private fun isDailyEarningsNode(node: AccessibilityNodeInfo?): Boolean {
//        if (node == null) return false
//        // Check that the node's text matches the earnings pattern (e.g., "$12.34")
//        val text = node.text?.toString() ?: ""
//        if (!text.matches(Regex("^\\$\\d+(\\.\\d{2})?\$"))) {
//            return false
//        }
//        // Traverse upward from the node (up to 5 levels) and combine parent text
//        var current = node.parent
//        val combinedParentText = StringBuilder()
//        var levels = 0
//        while (current != null && levels < 5) {
//            current.text?.let { combinedParentText.append(it).append(" ") }
//            current = current.parent
//            levels++
//        }
//        val parentTextStr = combinedParentText.toString().trim()
//        // Log the combined parent text for debugging
////        Log.d("AccessibilityService", "Combined parent text: '$parentTextStr'")
//
//        // Create a regex for summary keywords (case-insensitive)
//        val summaryPattern = Regex("(?i)\\b(stats|breakdown)\\b")
//        // If any parent text contains these keywords, then reject this node.
//        if (summaryPattern.containsMatchIn(parentTextStr)) {
//            Log.d("AccessibilityService", "Parent text contains summary keywords; rejecting node.")
//            return false
//        }
//        Log.d("AccessibilityService", "Node accepted as daily earnings candidate.")
//        return true
//    }
//
//    private fun findDailyEarningsNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
//        if (node == null) return null
//        if (node.className == "android.widget.TextView") {
//            val text = node.text?.toString() ?: ""
//            // Check for a simple earnings pattern; adjust as needed.
//            if (text.matches(Regex("^\\$\\d+(\\.\\d{2})?\$"))) {
//                // Only return this node if the context indicates it's from the main screen.
//                if (isDailyEarningsNode(node)) {
//                    return node
//                }
//            }
//        }
//        for (i in 0 until node.childCount) {
//            val result = findDailyEarningsNode(node.getChild(i))
//            if (result != null) return result
//        }
//        return null
//    }
//
//    private fun analyzeRideRequest(text: String) {
//        // Use UberParser's advanced parsing to check if the text contains a valid ride request.
//        val rideInfo = UberParser.parse(text)
//        if (rideInfo != null) {
////            Log.d("RideTracker", "🟢 Detected possible ride request: $text")
//            processRideRequest(text)
//        } else {
////            Log.d("RideTracker", "⚠️ No valid ride request detected on screen.")
//        }
//    }
//
//    private fun extractTextFromNode(node: AccessibilityNodeInfo?): String {
//        if (node == null) return ""
//        val builder = StringBuilder()
//
//        // Prefer node.text, but fallback to contentDescription if text is null.
//        when {
//            node.text != null -> builder.append(node.text.toString()).append(" ")
//            node.contentDescription != null -> builder.append(node.contentDescription.toString()).append(" ")
//        }
//
//        for (i in 0 until node.childCount) {
//            builder.append(extractTextFromNode(node.getChild(i)))
//        }
//        return builder.toString().trim()
//    }
//
//    private fun processRideRequest(text: String) {
//        serviceScope.launch {
//            val prefs = PreferenceManager.getDefaultSharedPreferences(this@AccessibilityService)
//            val cleanedText = text.replace("l", "1").replace("L", "1")
//            val requiredKeywords = listOf("\\$", "mi", "mins", "trip", "away", "Verified", "Accept", "Match")
//            val candidateBlocks = cleanedText.split("\n").map { it.trim() }
//                .filter { block -> requiredKeywords.all { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(block) } }
//            val tripRequestText = if (candidateBlocks.isNotEmpty()) {
//                candidateBlocks.maxByOrNull { block ->
//                    requiredKeywords.sumOf { Regex(it, RegexOption.IGNORE_CASE).findAll(block).count() }
//                } ?: cleanedText
//            } else {
//                cleanedText
//            }
//            val rideInfo = parseRideInfo(tripRequestText)
//            if (rideInfo == null) {
//                FloatingOverlayService.hideOverlay()
//                return@launch
//            }
//
//            val fareVal = rideInfo.fare ?: 0.0
//            val pickupDistanceVal = rideInfo.pickupDistance ?: 0.0
//            val tripDistanceVal = rideInfo.tripDistance ?: 0.0
//            val totalMiles = pickupDistanceVal + tripDistanceVal
//
//            val pickupTimeVal = rideInfo.pickupTime ?: 0.0
//            val tripTimeVal = rideInfo.tripTime ?: 0.0
//            val totalMinutes = pickupTimeVal + tripTimeVal
//
//            val validAction = tripRequestText.contains("Accept", ignoreCase = true) ||
//                    tripRequestText.contains("Match", ignoreCase = true)
//
//            if (rideInfo.rideType?.equals("Delivery", ignoreCase = true) == true) {
//                if (totalMiles <= 0.0 || totalMinutes <= 0.0 || !validAction) {
//                    FloatingOverlayService.hideOverlay()
//                    return@launch
//                }
//            } else {
//                if (fareVal <= 0.0 || totalMiles <= 0.0 || totalMinutes <= 0.0 || !validAction) {
//                    FloatingOverlayService.hideOverlay()
//                    return@launch
//                }
//            }
//
//            val bonus = prefs.getFloat(SettingsActivity.KEY_BONUS_RIDE, 0.0f).toDouble()
//            val adjustedFare = (rideInfo.fare ?: 0.0) + bonus
//            val fingerprint = "$adjustedFare-$totalMiles-$totalMinutes"
//
//            if (fingerprint == lastRequestFingerprint) {
////                Log.d("RideTracker", "⚠️ Duplicate ride request detected. Skipping processing.")
//                return@launch
//            }
//            lastRequestFingerprint = fingerprint // ✅ **Update fingerprint to prevent reprocessing**
//
//            if (rideInfo != null) {
//                UberParser.logUberRideRequest(rideInfo, "", "", "Accept", false)
//
//                val bonus = prefs.getFloat(SettingsActivity.KEY_BONUS_RIDE, 0.0f).toDouble()
//                val adjustedFare = (rideInfo.fare ?: 0.0) + bonus
//                val totalMiles = (rideInfo.pickupDistance ?: 0.0) + (rideInfo.tripDistance ?: 0.0)
//                val totalMinutes = (rideInfo.pickupTime ?: 0.0) + (rideInfo.tripTime ?: 0.0)
//
//                val pricePerMile = if (totalMiles > 0) adjustedFare / totalMiles else 0.0
//                val pricePerHour = if (totalMinutes > 0) adjustedFare / (totalMinutes / 60.0) else 0.0
//
//                val pmileColor = when {
//                    pricePerMile < prefs.getFloat(SettingsActivity.KEY_DECLINE_MILE, 0.75f) -> Color.RED
//                    pricePerMile < prefs.getFloat(SettingsActivity.KEY_ACCEPT_MILE, 1.0f) -> Color.YELLOW
//                    else -> Color.GREEN
//                }
//                val phourColor = when {
//                    pricePerHour < prefs.getFloat(SettingsActivity.KEY_DECLINE_HOUR, 20.0f) -> Color.RED
//                    pricePerHour < prefs.getFloat(SettingsActivity.KEY_ACCEPT_HOUR, 25.0f) -> Color.YELLOW
//                    else -> Color.GREEN
//                }
//                val fareColor = when {
//                    adjustedFare < prefs.getFloat(SettingsActivity.KEY_FARE_LOW, 5.0f) -> Color.RED
//                    adjustedFare < prefs.getFloat(SettingsActivity.KEY_FARE_HIGH, 10.0f) -> Color.YELLOW
//                    else -> Color.GREEN
//                }
//
//                FloatingOverlayService.updateOverlay(
//                    rideType = rideInfo.rideType ?: "Unknown",
//                    fare = "$${String.format("%.2f", adjustedFare)}",
//                    fareColor = fareColor,
//                    pMile = "$${String.format("%.2f", pricePerMile)}",
//                    pMileColor = pmileColor,
//                    pHour = "$${String.format("%.2f", pricePerHour)}",
//                    pHourColor = phourColor,
//                    miles = String.format("%.1f", totalMiles),
//                    minutes = String.format("%.1f", totalMinutes),
//                    profit = "$${String.format("%.2f", adjustedFare - (prefs.getFloat(SettingsActivity.KEY_COST_DRIVING, 0.20f) * totalMiles))}",
//                    profitColor = if (adjustedFare - (prefs.getFloat(SettingsActivity.KEY_COST_DRIVING, 0.20f) * totalMiles) >= 0) Color.GREEN else Color.RED,
//                    rating = rideInfo.rating?.toString() ?: "N/A",
//                    stops = rideInfo.stops ?: ""
//                )
//            } else {
////                Log.d("RideTracker", "⚠️ Detected text does NOT match a ride request.")
//            }
//        }
//    }
//
//    private fun parseRideInfo(text: String): com.stoffeltech.ridetracker.services.RideInfo? {
//        // Remove header labels.
//        val headerKeywords = listOf("p/Mi1e", "Minutes", "Per Mi1e", "Per Minute")
//        val cleanedText = text.lines()
//            .filter { line -> headerKeywords.none { header -> line.trim().equals(header, ignoreCase = true) } }
//            .joinToString("\n")
//        // Determine if this is a Lyft request by checking for "yft" (ignoring case).
//        return if (cleanedText.contains("yft", ignoreCase = true)) {
//            LyftParser.parse(cleanedText)
//        } else {
//            UberParser.parse(cleanedText)
//        }
//    }
//
//    private fun saveBitmapToFile(bitmap: Bitmap, rideType: String) {
//        // Get the public Pictures directory.
//        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
//
//        // Create (or reuse) the main folder "Ride Tracker".
//        val appFolder = File(picturesDir, "Ride Tracker")
//        if (!appFolder.exists()) {
//            appFolder.mkdirs()
//        }
//
//        // Create (or reuse) the subfolder for the ride type.
//        val rideFolder = File(appFolder, rideType)
//        if (!rideFolder.exists()) {
//            rideFolder.mkdirs()
//        }
//
//        // Generate a unique file name with a timestamp.
//        val dateFormat = SimpleDateFormat("MM-dd-yy_hhmmssa", Locale.getDefault())
//        val timestamp = dateFormat.format(Date())
//        val filename = "${rideType}_${timestamp}.png"
//        val file = File(rideFolder, filename)
//
//        try {
//            FileOutputStream(file).use { fos ->
//                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
//            }
//            Log.d("AccessibilityService", "✅ Screenshot saved to ${file.absolutePath}")
//        } catch (e: Exception) {
//            Log.e("AccessibilityService", "❌ Error saving screenshot: ${e.message}")
//        }
//    }
//
//    override fun onServiceConnected() {
//        super.onServiceConnected()
//        // Update the service configuration to listen to more event types.
//        val info = serviceInfo
//        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
//                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
//                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
//        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
//        info.notificationTimeout = 100
//        serviceInfo = info
////        Log.d("RideTracker", "Accessibility Service Connected with updated config")
//
//        // (Optional) Debug: simulate a request in debug mode.
////        if (BuildConfig.DEBUG) {
////            val testRideText = """
////            UberX
////            $23.45
////            3 mins (1.2 mi) away
////            5 mins (2.3 mi) trip
////            Pickup Location: 123 Main St
////            Dropoff Location: 456 Elm St
////        """.trimIndent()
////            analyzeRideRequest(testRideText)
////        }
//    }
//
//    private fun dumpNodeTree(node: AccessibilityNodeInfo?, indent: String = "") {
//        if (node == null) return
//        val nodeInfo = "Class: ${node.className} | Text: ${node.text} | ContentDesc: ${node.contentDescription}"
////        Log.d("RideTracker", "$indent$nodeInfo")
//        for (i in 0 until node.childCount) {
//            dumpNodeTree(node.getChild(i), "$indent  ")
//        }
//    }
//
//    override fun onInterrupt() {
////        Log.w("RideTracker", "Accessibility Service Interrupted")
//    }
//
//    // -------------------------- New Functions for Uber Detailed Extraction --------------------------
//
//    /**
//     * Data class to hold detailed information extracted from an Uber ride request.
//     */
//    data class UberRideDetails(
//        val rideType: String?,         // e.g., "UberX"
//        val isExclusive: Boolean,      // true if "Exclusive" is present
//        val fare: Double?,             // ride price, e.g., 12.63
//        val rating: Double?,           // rider rating, e.g., 5.00
//        val isVerified: Boolean,       // true if "Verified" is present
//        val pickupTime: String?,       // e.g., "9 mins"
//        val pickupDistance: String?,   // e.g., "3.3 mi"
//        val pickupAddress: String?,    // e.g., "Leeland Heights Blvd & Robert Ave, Lehigh Acres"
//        val dropoffTime: String?,      // e.g., "22 mins"
//        val dropoffDistance: String?,  // e.g., "12.6 mi"
//        val dropoffAddress: String?,   // e.g., "25th St SW & Gretchen Ave S, Lehigh Acres"
//        val actionButton: String?      // e.g., "Accept" or "Match"
//    )
//
//    /**
//     * Extracts detailed Uber ride request information from the provided text.
//     */
//    private fun extractUberRideDetails(text: String): UberRideDetails {
//        // 1. Extract ride type and whether it is "Exclusive".
//        val rideTypePattern = Regex("^(Uber\\w+)(Exclusive)?", RegexOption.IGNORE_CASE)
//        val rideTypeMatch = rideTypePattern.find(text)
//        val rideType = rideTypeMatch?.groups?.get(1)?.value
//        val isExclusive = rideTypeMatch?.groups?.get(2) != null
//
//        // 2. Extract fare using the $ sign.
//        val farePattern = Regex("\\$([0-9]+\\.[0-9]{2})")
//        val fareMatch = farePattern.find(text)
//        val fare = fareMatch?.groups?.get(1)?.value?.toDoubleOrNull()
//
//        // 3. Extract rating.
//        val ratingPattern = Regex("\\$[0-9]+\\.[0-9]{2}([0-9]+\\.[0-9]{2})")
//        val ratingMatch = ratingPattern.find(text)
//        val rating = ratingMatch?.groups?.get(1)?.value?.toDoubleOrNull()
//
//        // 4. Check if "Verified" is present.
//        val isVerified = text.contains("Verified", ignoreCase = true)
//
//        // 5. Extract pickup information: e.g., "9 mins (3.3 mi) away"
//        val pickupPattern = Regex("([0-9]+\\s*mins)\\s*\\(\\s*([0-9]+\\.?[0-9]*)\\s*mi\\)\\s*away", RegexOption.IGNORE_CASE)
//        val pickupMatch = pickupPattern.find(text)
//        val pickupTime = pickupMatch?.groups?.get(1)?.value
//        val pickupDistance = pickupMatch?.groups?.get(2)?.value
//
//        // 6. Extract pickup address: text between "away" and the next time/distance block.
//        val pickupAddressPattern = Regex("away\\s*(.*?)\\s*([0-9]+\\s*mins\\s*\\()", RegexOption.IGNORE_CASE)
//        val pickupAddressMatch = pickupAddressPattern.find(text)
//        val pickupAddress = pickupAddressMatch?.groups?.get(1)?.value?.trim()
//
//        // 7. Extract dropoff information: e.g., "22 mins (12.6 mi) trip"
//        val dropoffPattern = Regex("([0-9]+\\s*mins)\\s*\\(\\s*([0-9]+\\.?[0-9]*)\\s*mi\\)\\s*trip", RegexOption.IGNORE_CASE)
//        val dropoffMatch = dropoffPattern.find(text)
//        val dropoffTime = dropoffMatch?.groups?.get(1)?.value
//        val dropoffDistance = dropoffMatch?.groups?.get(2)?.value
//
//        // 8. Extract dropoff address: text between "trip" and a button indicator ("Accept" or "Match").
//        val dropoffAddressPattern = Regex("trip\\s*(.*?)\\s*(Accept|Match)", RegexOption.IGNORE_CASE)
//        val dropoffAddressMatch = dropoffAddressPattern.find(text)
//        val dropoffAddress = dropoffAddressMatch?.groups?.get(1)?.value?.trim()
//
//        // 9. Extract the action button text ("Accept" or "Match").
//        val actionButtonPattern = Regex("(Accept|Match)\\b", RegexOption.IGNORE_CASE)
//        val actionButtonMatch = actionButtonPattern.find(text)
//        val actionButton = actionButtonMatch?.groups?.get(1)?.value
//
//        return UberRideDetails(
//            rideType = rideType,
//            isExclusive = isExclusive,
//            fare = fare,
//            rating = rating,
//            isVerified = isVerified,
//            pickupTime = pickupTime,
//            pickupDistance = pickupDistance,
//            pickupAddress = pickupAddress,
//            dropoffTime = dropoffTime,
//            dropoffDistance = dropoffDistance,
//            dropoffAddress = dropoffAddress,
//            actionButton = actionButton
//        )
//    }
//
//    /**
//     * Processes an Uber ride request using detailed extraction logic.
//     * This method extracts the ride details from the accessibility event text,
//     * logs the extracted details, and updates the floating overlay.
//     */
//    private fun processUberRideRequestWithDetails(text: String) {
//        serviceScope.launch {
//            val prefs = PreferenceManager.getDefaultSharedPreferences(this@AccessibilityService)
//            val details = extractUberRideDetails(text)
//            Log.d("AccessibilityService", "Extracted Uber Ride Details: $details")
//
//            // Validate that we have an action button (e.g., Accept or Match).
//            if (details.actionButton.isNullOrEmpty()) {
//                FloatingOverlayService.hideOverlay()
//                return@launch
//            }
//
//            // For now, use the extracted fare as the ride price.
//            val adjustedFare = details.fare ?: 0.0
//
//            // Update the overlay with the extracted details.
//            // You can further extend this logic to incorporate pickup/dropoff times/distances.
//            FloatingOverlayService.updateOverlay(
//                rideType = "${details.rideType ?: "Unknown"}${if(details.isExclusive) " Exclusive" else ""}",
//                fare = "$${String.format("%.2f", adjustedFare)}",
//                fareColor = Color.GREEN, // Default color; refine based on thresholds if needed.
//                pMile = "",             // Not extracted in detail here.
//                pMileColor = Color.GREEN,
//                pHour = "",             // Not extracted in detail here.
//                pHourColor = Color.GREEN,
//                miles = "",             // Not extracted in detail.
//                minutes = "",           // Not extracted in detail.
//                profit = "",            // Profit not calculated here.
//                profitColor = Color.GREEN,
//                rating = details.rating?.toString() ?: "N/A",
//                stops = "Pickup: ${details.pickupAddress ?: "N/A"}\nDropoff: ${details.dropoffAddress ?: "N/A"}"
//            )
//        }
//    }
//}

package com.stoffeltech.ridetracker.uber

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import com.stoffeltech.ridetracker.SettingsActivity
import com.stoffeltech.ridetracker.services.FloatingOverlayService
import com.stoffeltech.ridetracker.services.RideInfo
import kotlin.math.abs

// Uber-specific duplicate tracking variables
private var lastDeliveryRawFare: Double? = null
private var lastDeliveryAdjustedFare: Double? = null
private var lastDeliveryRequestHash: Int? = null

// New fingerprint variable for Uber ride requests to avoid duplicates.
private var lastUberRequestFingerprint: String? = null



object UberParser {
    // ---------------- LAST VALID REQUEST TIMESTAMP -----------------
// This variable is updated each time a valid ride request is processed.
// It is later used by the overlay to determine whether to hide itself.
    var lastValidRequestTime: Long = 0

    // Note: The longer alternatives are placed first so that, for example,
    // "UberX Priority" is matched before "UberX".
    private val rideTypes = listOf(
        "UberX Priority",
        "UberX Reserve",
        "Uber Black XL",
        "Uber Connect XL",
        "Uber Green",
        "Green Comfort",
        "Uber Black",
        "UberX",
        "UberXL",
        "UberPool",
        "Uber Connect",
        "Uber Share",
        "Comfort",
        "Premier",
        "Pet",
        "Delivery"
    )

    private val rideTypeRegex: Regex by lazy {
        Regex(rideTypes.joinToString(separator = "|"), RegexOption.IGNORE_CASE)
    }

    // ---------------- NORMALIZE OCR TEXT FUNCTION ----------------
    // This function manually scans the OCR text and replaces any occurrence
    // of 'l' or 'L' (misinterpreted instead of '1') that immediately follows a digit.
    private fun normalizeOcrText(text: String): String {
        val sb = StringBuilder()
        for (i in text.indices) {
            val c = text[i]
            // If the current character is 'l' or 'L' and the previous character is a digit, replace it with '1'
            if ((c == 'l' || c == 'L') && i > 0 && text[i - 1].isDigit()) {
                sb.append('1')
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    fun extractRideRequestText(fullText: String): String {
        val match = rideTypeRegex.find(fullText)
        return if (match != null) fullText.substring(match.range.first) else fullText
    }

    // ------------------------------------------------------------------------
    //  A) Text Preprocessing Routines
    // ------------------------------------------------------------------------
    private fun preprocessAccessibilityText(text: String): String {
        var result = text
        // List of ride types we expect
        val rideTypes = listOf(
            "UberX Priority", "UberX Reserve", "Uber Black XL", "Uber Connect XL",
            "Uber Green", "Green Comfort", "Uber Black", "UberX", "UberXL",
            "UberPool", "Uber Connect", "Uber Share", "Comfort", "Premier", "Pet", "Delivery"
        )
        // Insert newline before each ride type occurrence if not already preceded by one
        for (ride in rideTypes) {
            result = result.replace(Regex("(?<!\\n)\\b($ride)"), "\n$1")
        }
        // Insert newline before fare values (e.g., "$7.55")
        result = result.replace(Regex("(?<!\\n)(\\$\\d+(?:\\.\\d{2})?)(?!\\d)"), "\n$1")
        // NEW: Insert newline before a "$" if immediately preceded by a letter.
        result = result.replace(Regex("(?<=[A-Za-z])\\$(\\d)"), "\n\$$1")
        // Insert newline before time info (e.g., "5 mins" or "39 min")
        result = result.replace(Regex("(?<!\\n)(\\d+\\s*min)"), "\n$1")
        // Insert newline before action buttons like "Accept" or "Match"
        result = result.replace(Regex("(?<!\\n)\\b(Accept|Match)"), "\n$1")
        // Insert newline AFTER "away" and "trip" to isolate addresses
        result = result.replace(Regex("(?i)(away)(?!\\n)"), "$1\n")
        result = result.replace(Regex("(?i)(trip)(?!\\n)"), "$1\n")
        return result.trim()
    }

    private fun prepareOcrTextForParsing(ocrText: String): String {
        var text = ocrText

        // Add newlines before ride types
        for (rideType in rideTypes) {
            text = text.replace(rideTypeRegex, "\n$0\n")
        }

        // Add newlines before fare amounts ($x.xx)
        text = text.replace(Regex("(\\$\\d+\\.\\d{2})"), "\n$1\n")

        // Add newlines around pickup/trip info (e.g., "10 mins (3.5 mi) away")
        text = text.replace(Regex("(\\d+ mins? \\(.*?mi\\) away)"), "\n$1\n")
        text = text.replace(Regex("(\\d+ mins? \\(.*?mi\\) trip)"), "\n$1\n")

        // Add newlines before action buttons (Accept/Match)
        text = text.replace(Regex("(Accept|Match)"), "\n$1\n")

        // Trim extra spaces and lines
        return text.lines().joinToString("\n") { it.trim() }.trim()
    }

    // ------------------------------------------------------------------------
    //  B) Logging for debugging (now revised to your specified layout order)
    // ------------------------------------------------------------------------
    fun logUberRideRequest(
        rideInfo: RideInfo,
        pickupLocation: String,
        dropoffLocation: String,
        actionButton: String,
        verified: Boolean
    ) {
        val sb = StringBuilder()

        // 1) Ride Type
        sb.appendLine("1) Ride Type: ${rideInfo.rideType ?: "Unknown"}")

        // 2) Subtype (Exclusive)
        if (!rideInfo.rideSubtype.isNullOrBlank()) {
            sb.appendLine("2) Subtype: ${rideInfo.rideSubtype}")
        } else {
            sb.appendLine("2) Subtype: N/A")
        }

        // 3) Fare Price
        sb.appendLine("3) Fare Price: \$${rideInfo.fare ?: "N/A"}")

        // 4) Rating (append " (Verified)" if verified == true)
        val ratingText = rideInfo.rating?.toString() ?: "N/A"
        val verifiedText = if (verified) " (Verified)" else ""
        sb.appendLine("4) Rating: $ratingText$verifiedText")

        // 5) Bonuses
        if (!rideInfo.bonuses.isNullOrBlank()) {
            sb.appendLine("5) Bonuses: ${rideInfo.bonuses}")
        } else {
            sb.appendLine("5) Bonuses: None")
        }

        // 6) Pickup time and distance
        sb.appendLine(
            "6) Pickup Time/Distance: ${rideInfo.pickupTime ?: "N/A"} mins" +
                    " (${rideInfo.pickupDistance ?: "N/A"} mi) away"
        )

        // 7) Pickup Location
        sb.appendLine("7) Pickup Location: $pickupLocation")

        // 8) Dropoff time and distance
        sb.appendLine(
            "8) Dropoff Time/Distance: ${rideInfo.tripTime ?: "N/A"} mins" +
                    " (${rideInfo.tripDistance ?: "N/A"} mi) trip"
        )

        // 9) Dropoff Location
        sb.appendLine("9) Dropoff Location: $dropoffLocation")

        // 10) Special events (Stops)
        val stopsText = if (rideInfo.stops?.isNotEmpty() == true) rideInfo.stops else "None"
        sb.appendLine("10) Special Events: $stopsText")

        // 11) Action Button
        sb.appendLine("11) Action Button: $actionButton")

        // Debug example:
        // Log.d("UberRideRequest", sb.toString())
    }
    fun getRideTypes(): List<String> = rideTypes

    // ------------------------------------------------------------------------
    //  C) The parse(...) function, now capturing rideSubtype & bonuses
    // ------------------------------------------------------------------------
    fun parse(cleanedText: String): RideInfo? {
        // First, normalize the OCR text to correct misinterpretations (e.g., 'l' misread instead of '1')
        val normalizedInput = normalizeOcrText(cleanedText)

        // Log the normalized OCR text
//        Log.d("UberParser", "Normalized OCR text in parse(): $normalizedInput")

        // Then, preprocess the normalized text as usual
        val text = normalizeOcrText(cleanedText)

        // Log the preprocessed text for further clarity
//        Log.d("UberParser", "Preprocessed text in parse(): $text")

        // --- Extract Ride Type ---
        val rideTypeMatch = rideTypeRegex.find(text)
        var rideType = rideTypeMatch?.value ?: "Unknown"

        /* Checks if the ride type contains "Exclusive" (case-insensitive).
         * We'll store it in rideSubtype, but also remove it from the main rideType text
         * so it doesn't break "Uber Black", "UberX", etc.
         */
        // Determine if the text contains "Exclusive" anywhere.
        val isExclusive = text.contains("Exclusive", ignoreCase = true)
        // If found, remove the word "Exclusive" from the rideType string.
        if (isExclusive) {
            rideType = rideType.replace("Exclusive", "", ignoreCase = true).trim()
        }


        // --- Parse "bonuses" lines, e.g. "5% Advantage included", "tip included", etc. ---
        var bonuses: String? = null
        // Example pattern matches anything that has "included" or "tip included" or "Advantage"
        val bonusRegex = Regex("(\\d+%.*included|tip included|advantage included)", RegexOption.IGNORE_CASE)
        val bonusMatches = bonusRegex.findAll(text).map { it.value.trim() }.toList()
        if (bonusMatches.isNotEmpty()) {
            // Combine all bonus lines into a single comma-separated string
            bonuses = bonusMatches.joinToString(", ")
        }

        // --- Extract Fare ---
        val fareRegex = Regex("\\$(\\d+)[.,](\\d{2})")
        val fareMatch = fareRegex.find(text)
        val fare = fareMatch?.let {
            val wholePart = it.groupValues[1]
            val decimalPart = it.groupValues[2]
            "$wholePart.$decimalPart".toDoubleOrNull()
        }

        // -------------------- ROBUST RATING EXTRACTION ---------------------
        // Matches ratings with optional star and "Verified" suffix.
        val ratingRegex = Regex("[★\\*]\\s*(\\d\\.\\d{2})(?=[^0-9]|$)", RegexOption.IGNORE_CASE)
        val ratingMatch = ratingRegex.find(text)
        val extractedRating = ratingRegex.find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.let {
            if (it <= 5.0) it else 5.0
        }
        // Check explicitly for "Verified" separately
        val verified = text.contains("Verified", ignoreCase = true)
        // --------------------------------------------------------------------

        // For Delivery requests, rating should be null.
        val rating = if (rideType.contains("Delivery", ignoreCase = true)) null else extractedRating

        // --- Declare mutable variables for Time, Distance, and Location ---
        var pickupTime: Double? = null
        var pickupDistance: Double? = null
        var tripTime: Double? = null
        var tripDistance: Double? = null
        var pickupLocation: String = "N/A"
        var dropoffLocation: String = "N/A"

        // --- If the request is for Delivery, use a delivery-specific extraction ---
        if (rideType.contains("Delivery", ignoreCase = true)) {
            // Use a regex to extract total time/distance using the "total" keyword.
            val totalPattern = Regex("(\\d+)\\s*min[s]?\\s*\\((\\d+(?:\\.\\d+)?)\\s*mi\\)\\s*total", RegexOption.IGNORE_CASE)
            val totalMatch = totalPattern.find(text)
            pickupTime = totalMatch?.groupValues?.get(1)?.toDoubleOrNull()
            pickupDistance = totalMatch?.groupValues?.get(2)?.toDoubleOrNull()
            // Delivery requests don't have separate trip info.
            tripTime = null
            tripDistance = null
            // Extract delivery location from text following "total" and before Accept/Match.
            val addressPattern = Regex("total\\s*(.*?)\\s*(Accept|Match)", RegexOption.IGNORE_CASE)
            pickupLocation = addressPattern.find(text)?.groupValues?.get(1)?.trim() ?: "N/A"
            dropoffLocation = pickupLocation
        } else {
            // --- For Ride requests, use the ride-specific extraction ---
            val pickupInfoRegex = Regex("(\\d+)\\s*mins?\\s*\\((\\d+(?:\\.\\d+)?)\\s*mi\\)\\s*away", RegexOption.IGNORE_CASE)
            val pickupMatch = pickupInfoRegex.find(text)
            pickupTime = pickupMatch?.groupValues?.get(1)?.toDoubleOrNull()
            pickupDistance = pickupMatch?.groupValues?.get(2)?.toDoubleOrNull()

            val pickupLocationRegex = Regex("away\\s*([^\\d]+?)\\s*\\d+\\s*mins", RegexOption.IGNORE_CASE)
            pickupLocation = pickupLocationRegex.find(text)?.groupValues?.get(1)?.trim() ?: "N/A"

            val tripInfoRegex = Regex("(\\d+)\\s*mins?\\s*\\((\\d+(?:\\.\\d+)?)\\s*mi\\)\\s*trip", RegexOption.IGNORE_CASE)
            val tripMatch = tripInfoRegex.find(text)
            tripTime = tripMatch?.groupValues?.get(1)?.toDoubleOrNull()
            tripDistance = tripMatch?.groupValues?.get(2)?.toDoubleOrNull()

            val dropoffLocationRegex = Regex("trip\\s*(.*?)\\s*(Accept|Match)", RegexOption.IGNORE_CASE)
            dropoffLocation = dropoffLocationRegex.find(text)?.groupValues?.get(1)?.trim() ?: "N/A"
        }

        // --- Extract Action Button ---
        val actionButtonRegex = Regex("(?i)(Accept|Match)\\s*$")
        val actionButton = actionButtonRegex.find(text)?.groupValues?.get(1) ?: "N/A"

        // --- Combine extra fields (for stops) ---
        val stops = if (text.contains("Multiple stops", ignoreCase = true)) "Multiple stops" else ""
        // Extra info is available if needed: "Verified: $verified, Exclusive: $exclusive"

        // --- Validation: Ensure required fields are present ---
        // For non-Delivery requests, we expect a pickupTime, fare, and tripTime at minimum.
        if (fare == null || pickupTime == null ||
            (!rideType.contains("Delivery", ignoreCase = true) && tripTime == null)
        ) {
            return null
        }

        return RideInfo(
            rideType = rideType,
            fare = fare,
            rating = rating,
            pickupTime = pickupTime,
            pickupDistance = pickupDistance,
            pickupLocation = pickupLocation,
            tripTime = tripTime,
            tripDistance = tripDistance,
            tripLocation = dropoffLocation,
            stops = stops,
            actionButton = actionButton,
            // Pass the flag – true if "Exclusive" was found, false otherwise.
            isExclusive = isExclusive,
            bonuses = bonuses
        )
    }

    /**
     * Processes an Uber ride request.
     *
     * This function takes the raw accessibility text, validates it,
     * extracts and parses the ride information, and then branches to handle
     * either a Delivery or a Ride based on the ride type.
     *
     * Blocks are clearly separated below:
     *
     * Block 1: Validate input text format.
     * Block 2: Extract the candidate ride request block.
     * Block 3: Parse the ride information.
     * Block 4: Validate extracted ride fields.
     * Block 5: Check for duplicate ride requests.
     * Block 6A: For Delivery requests, delegate to processDeliveryRequest.
     * Block 6B: For Ride requests, log details, calculate metrics, and update the overlay.
     *
     * @param rawText The raw text extracted from accessibility events.
     * @param serviceContext The AccessibilityService context (for preferences and logging).
     */
    @SuppressLint("DefaultLocale")
    suspend fun processUberRideRequest(rawText: String, context: Context) {
        // Normalize and trim the raw OCR text
        val trimmedText = normalizeOcrText(rawText.trim())
//        Log.d("UberParser", "Normalized text in processUberRideRequest: $trimmedText")

        // Early return if text does not contain required keywords
        if (!trimmedText.contains("Accept", ignoreCase = true) && !trimmedText.contains("Match", ignoreCase = true)) {
            return
        }

        // Choose the proper preprocessor based on OS version (if needed)
        val preparedText = if (android.os.Build.VERSION.SDK_INT < 34) {
            Log.d("UberParser", "Using ACC processing (Android <= 13)")
            preprocessAccessibilityText(trimmedText)
        } else {
            Log.d("UberParser", "Using OCR processing (Android 14+)")
            prepareOcrTextForParsing(trimmedText)
        }

//        Log.d("UberParser", "Prepared text for parsing: $preparedText")

        // Parse the prepared text to extract ride information
        val rideInfo = parse(preparedText)
        if (rideInfo == null) {
            Log.d("UberParser", "Parsed ride info is null. Hiding overlay. Raw text: $preparedText")

            FloatingOverlayService.hideOverlay()
            return
        }

        // ---------------- UPDATE LAST VALID REQUEST TIME -----------------
        // We update the timestamp to the current time every time a valid ride request is received.
        lastValidRequestTime = System.currentTimeMillis()

        // --- Insert the debug log here ---
        Log.d("UberParser", """
        Parsed RideInfo:
        rideType = ${rideInfo.rideType},
        fare = ${rideInfo.fare},
        rating = ${rideInfo.rating},
        pickupTime = ${rideInfo.pickupTime},
        pickupDistance = ${rideInfo.pickupDistance},
        pickupLocation = ${rideInfo.pickupLocation},
        tripTime = ${rideInfo.tripTime},
        tripDistance = ${rideInfo.tripDistance},
        tripLocation = ${rideInfo.tripLocation},
        stops = ${rideInfo.stops},
        actionButton = ${rideInfo.actionButton},
        rideSubtype = ${rideInfo.rideSubtype},
        bonuses = ${rideInfo.bonuses}
    """.trimIndent())

        // Extract ride details for calculations
        val fareVal = rideInfo.fare ?: 0.0
        val pickupDistanceVal = rideInfo.pickupDistance ?: 0.0
        val tripDistanceVal = rideInfo.tripDistance ?: 0.0
        val totalMiles = pickupDistanceVal + tripDistanceVal
        val pickupTimeVal = rideInfo.pickupTime ?: 0.0
        val tripTimeVal = rideInfo.tripTime ?: 0.0
        val totalMinutes = pickupTimeVal + tripTimeVal

        // Retrieve SharedPreferences using the new context parameter
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val bonus = prefs.getFloat(SettingsActivity.KEY_BONUS_RIDE, 0.0f).toDouble()
        val adjustedFare = fareVal + bonus
        val fingerprint = "$adjustedFare-$totalMiles-$totalMinutes"

        if (fingerprint == lastUberRequestFingerprint) {
            return
        }
        lastUberRequestFingerprint = fingerprint

        // --- Compute colors for each metric (new additions) ---
        // Compute fare color
        val computedFareColor = when {
            adjustedFare < prefs.getFloat(SettingsActivity.KEY_FARE_LOW, 5.0f) -> Color.RED
            adjustedFare < prefs.getFloat(SettingsActivity.KEY_FARE_HIGH, 10.0f) -> Color.YELLOW
            else -> Color.GREEN
        }

        // Compute price per mile and its color
        val pricePerMile = if (totalMiles > 0) adjustedFare / totalMiles else 0.0
        val computedPMileColor = when {
            pricePerMile < prefs.getFloat(SettingsActivity.KEY_DECLINE_MILE, 0.75f) -> Color.RED
            pricePerMile < prefs.getFloat(SettingsActivity.KEY_ACCEPT_MILE, 1.0f) -> Color.YELLOW
            else -> Color.GREEN
        }

        // Compute price per hour and its color
        val pricePerHour = if (totalMinutes > 0) adjustedFare / (totalMinutes / 60.0) else 0.0
        val computedPHourColor = when {
            pricePerHour < prefs.getFloat(SettingsActivity.KEY_DECLINE_HOUR, 20.0f) -> Color.RED
            pricePerHour < prefs.getFloat(SettingsActivity.KEY_ACCEPT_HOUR, 25.0f) -> Color.YELLOW
            else -> Color.GREEN
        }

        // Compute profit and its color
        val drivingCost = prefs.getFloat(SettingsActivity.KEY_COST_DRIVING, 0.20f) * totalMiles
        val profit = adjustedFare - drivingCost
        val computedProfitColor = if (profit >= 0) Color.GREEN else Color.RED

        // Compute rating color
        val currentRating = rideInfo.rating?.toFloat() ?: 0f
        val computedRatingColor = if (currentRating >= prefs.getFloat(SettingsActivity.KEY_RATING_THRESHOLD, 4.70f)) Color.GREEN else Color.RED

        // --- Update the overlay using the computed colors ---
        FloatingOverlayService.updateOverlay(
            rideType = rideInfo.rideType ?: "Unknown",
            isExclusive = rideInfo.isExclusive, // Pass rideSubtype flag as needed
            fare = "$${String.format("%.2f", adjustedFare)}",
            fareColor = computedFareColor,
            pMile = "$${String.format("%.2f", if (totalMiles > 0) adjustedFare / totalMiles else 0.0)}",
            pMileColor = computedPMileColor,  // Use computed price-per-mile color
            pHour = "$${String.format("%.2f", if (totalMinutes > 0) adjustedFare / (totalMinutes / 60.0) else 0.0)}",
            pHourColor = computedPHourColor,  // Use computed price-per-hour color
            miles = String.format("%.1f", totalMiles),
            minutes = String.format("%.1f", totalMinutes),
            profit = "$${String.format("%.2f", profit)}",
            profitColor = computedProfitColor, // Use computed profit color
            rating = rideInfo.rating?.toString() ?: "N/A",
            stops = rideInfo.stops ?: ""
        )
    }


    /**
     * Processes Delivery requests separately.
     *
     * This function updates the overlay with basic Delivery details.
     *
     * @param rideInfo The parsed ride information.
     * @param bonus The bonus amount from user preferences.
     * @param prefs User preferences.
     */
    @SuppressLint("DefaultLocale")
    private fun processDeliveryRequest(rideInfo: RideInfo, bonus: Double, prefs: android.content.SharedPreferences) {
        val adjustedFare = rideInfo.fare?.plus(bonus) ?: 0.0
        // For deliveries, we use pickupTime and pickupDistance as total values.
        val totalMinutes = rideInfo.pickupTime ?: 0.0
        val totalMiles = rideInfo.pickupDistance ?: 0.0

        // Calculate per-mile and per-hour metrics.
        val pricePerMile = if (totalMiles > 0) adjustedFare / totalMiles else 0.0
        val pricePerHour = if (totalMinutes > 0) adjustedFare / (totalMinutes / 60.0) else 0.0

        // Calculate profit based on a driving cost per mile.
        val drivingCost = prefs.getFloat(SettingsActivity.KEY_COST_DRIVING, 0.20f) * totalMiles
        val profit = adjustedFare - drivingCost

        // Determine colors based on thresholds from user preferences.
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
        val profitColor = if (profit >= 0) Color.GREEN else Color.RED

        // Now update the overlay. Here, rideType, total minutes (time), and total miles (distance)
        // are all provided to the overlay, so that the user sees the ride type along with the time and distance.
        FloatingOverlayService.updateOverlay(
            rideType = rideInfo.rideType ?: "Unknown",
            isExclusive = rideInfo.isExclusive, // Pass rideSubtype here.
            fare = "$${String.format("%.2f", adjustedFare)}",
            fareColor = fareColor,
            pMile = "$${String.format("%.2f", if (totalMiles > 0) adjustedFare / totalMiles else 0.0)}",
            pMileColor = pmileColor,
            pHour = "$${String.format("%.2f", if (totalMinutes > 0) adjustedFare / (totalMinutes / 60.0) else 0.0)}",
            pHourColor = phourColor,
            miles = String.format("%.1f", totalMiles),
            minutes = String.format("%.1f", totalMinutes),
            profit = "$${String.format("%.2f", adjustedFare - (prefs.getFloat(SettingsActivity.KEY_COST_DRIVING, 0.20f) * totalMiles))}",
            profitColor = profitColor,
            rating = rideInfo.rating?.toString() ?: "N/A",
            stops = rideInfo.stops ?: ""
        )
        // Schedule the overlay to hide after 15 seconds.
        Handler(Looper.getMainLooper()).postDelayed({
            FloatingOverlayService.hideOverlay()
        }, 15000)
    }

    /**
     * Determines if text likely represents a valid Uber ride request
     * (based on minimal fields like fare, pickup time, and possibly trip time).
     */
    fun isValidRideRequest(text: String): Boolean {
        val rideInfo = parse(text)
        return rideInfo?.fare != null && rideInfo.fare > 0.0 &&
                rideInfo.pickupTime != null && rideInfo.pickupTime > 0.0 &&
                (rideInfo.rideType?.contains("Delivery", ignoreCase = true) == true ||
                        (rideInfo.tripTime != null && rideInfo.tripTime > 0.0))
    }

}

package com.stoffeltech.ridetracker.uber

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
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

    // Note: The longer alternatives are placed first so that, for example,
    // "UberX Priority" is matched before "UberX".
    private val rideTypeRegex = Regex(
        "(UberX Priority|UberX Reserve|Uber Black XL|Uber Connect XL|Uber Green|Green Comfort|Uber Black|UberX|UberXL|UberPool|Uber Connect|Uber Share|Comfort|Premier|Pet|Delivery)",
        RegexOption.IGNORE_CASE
    )

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

    fun logUberRideRequest(
        rideInfo: RideInfo,
        pickupLocation: String,
        dropoffLocation: String,
        actionButton: String,
        verified: Boolean
    ) {
        val sb = StringBuilder()
        // Line 1: Ride type and close button
        sb.appendLine("Ride Type: ${rideInfo.rideType ?: "Unknown"}    [Close: (x)]")
        // Line 2: Fare information (fare, surge/bonus info could be appended here if available)
        sb.appendLine("Fare: \$${rideInfo.fare ?: "N/A"}")
        // Line 3: Rating (append " (Verified)" if the flag is true)
        val ratingText = rideInfo.rating?.toString() ?: "N/A"
        val verifiedText = if (verified) " (Verified)" else ""
        sb.appendLine("Rating: $ratingText$verifiedText")
        // Line 4: Pickup info
        sb.appendLine("Pickup Info: ${rideInfo.pickupTime ?: "N/A"} mins (${rideInfo.pickupDistance ?: "N/A"} mi) away")
        // Line 5: Pickup location (could span more than one line if needed)
        sb.appendLine("Pickup Location: $pickupLocation")
        // Line 6: Dropoff info (always in the format: e.g. "13 mins (4.8 mi) trip")
        sb.appendLine("Dropoff Info: ${rideInfo.tripTime ?: "N/A"} mins (${rideInfo.tripDistance ?: "N/A"} mi) trip")
        // Line 7: Dropoff location (again, may be multi-line)
        sb.appendLine("Dropoff Location: $dropoffLocation")
        // Add stops info:
        val stopsText = if (rideInfo.stops?.isNotEmpty() == true) rideInfo.stops else "None"
        sb.appendLine("Stops: $stopsText")
        // Bottom line: Action button (Accept or Match)
        sb.appendLine("Action Button: $actionButton")
        // Uncomment the line below to log details for debugging
        // Log.d("UberRideRequest", sb.toString())
    }

    fun parse(cleanedText: String): RideInfo? {
        // Preprocess the text (for accessibility we use the raw text trimmed)
        val text = preprocessAccessibilityText(cleanedText)

        // --- Extract Ride Type ---
        val rideTypeMatch = rideTypeRegex.find(text)
        var rideType = rideTypeMatch?.value ?: "Unknown"

        /* Checks if the ride type contains "Exclusive" (case-insensitive). If so, it marks the request
         * as exclusive and removes that word from the ride type for clean processing.
         */
        val exclusive = rideType.contains("Exclusive", ignoreCase = true)
        if (exclusive) {
            rideType = rideType.replace("Exclusive", "", ignoreCase = true).trim()
        }

        // --- Extract Fare ---
        val fareRegex = Regex("\\$(\\d+)[.,](\\d{2})")
        val fareMatch = fareRegex.find(text)
        val fare = fareMatch?.let {
            val wholePart = it.groupValues[1]
            val decimalPart = it.groupValues[2]
            "$wholePart.$decimalPart".toDoubleOrNull()
        }

        // --- Extract Rating ---
        val ratingRegex = Regex("(\\d+(?:\\.\\d{2,3}))%")
        val extractedRating = ratingRegex.find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.let {
            if (it <= 5.0) it else 5.0
        }
        // For Delivery requests, rating should be null.
        val rating = if (rideType.contains("Delivery", ignoreCase = true)) null else extractedRating

        // --- Extract Verified flag ---
        val verified = text.contains("Verified", ignoreCase = true)

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
        if (fare == null || pickupTime == null || (!rideType.contains("Delivery", ignoreCase = true) && tripTime == null)) return null


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
            actionButton = actionButton
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
    suspend fun processUberRideRequest(rawText: String, serviceContext: AccessibilityService) {
        // -------------------- Block 1: Validate Input Text --------------------
        val trimmedText = rawText.trim()
        if (!trimmedText.endsWith("Accept", ignoreCase = true) && !trimmedText.endsWith("Match", ignoreCase = true)) {
            Log.d("UberParser", "Incomplete ride request text received; skipping processing: $rawText")
            return
        }

        // -------------------- Block 2: Extract Candidate Ride Block --------------------
        val requiredKeywords = listOf("\\$", "mi", "mins", "trip", "away", "Verified", "Accept", "Match")
        val candidateBlocks = trimmedText.split("\n").map { it.trim() }
            .filter { block -> requiredKeywords.all { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(block) } }
        val tripRequestText = if (candidateBlocks.isNotEmpty()) {
            candidateBlocks.maxByOrNull { block ->
                requiredKeywords.sumOf { Regex(it, RegexOption.IGNORE_CASE).findAll(block).count() }
            } ?: trimmedText
        } else {
            trimmedText
        }

        // -------------------- Block 3: Parse Ride Information --------------------
        val rideInfo = parse(trimmedText)
        if (rideInfo == null) {
            FloatingOverlayService.hideOverlay()
            return
        }

        // -------------------- Block 4: Validate Extracted Ride Fields --------------------
        val fareVal = rideInfo.fare ?: 0.0
        val pickupDistanceVal = rideInfo.pickupDistance ?: 0.0
        val tripDistanceVal = rideInfo.tripDistance ?: 0.0
        val totalMiles = pickupDistanceVal + tripDistanceVal
        val pickupTimeVal = rideInfo.pickupTime ?: 0.0
        val tripTimeVal = rideInfo.tripTime ?: 0.0
        val totalMinutes = pickupTimeVal + tripTimeVal

        val validAction = tripRequestText.contains("Accept", ignoreCase = true) ||
                          tripRequestText.contains("Match", ignoreCase = true)

        // -------------------- Block 5: Check for Duplicate Ride Requests --------------------
        val prefs = PreferenceManager.getDefaultSharedPreferences(serviceContext)
        val bonus = prefs.getFloat(SettingsActivity.KEY_BONUS_RIDE, 0.0f).toDouble()
        val adjustedFare = fareVal + bonus
        val fingerprint = "$adjustedFare-$totalMiles-$totalMinutes"
        if (fingerprint == lastUberRequestFingerprint) {
            Log.d("UberParser", "Duplicate ride request detected; skipping processing.")
            return
        }
        lastUberRequestFingerprint = fingerprint

        // -------------------- Block 6: Separate Delivery from Ride --------------------
        if (rideInfo.rideType.equals("Delivery", ignoreCase = true)) {
            // For Delivery requests, log and process using delivery-specific flow.
            logUberRideRequest(rideInfo, "", "", rideInfo.actionButton ?: "N/A", false)
            processDeliveryRequest(rideInfo, bonus, prefs)
            return
        }

        // -------------------- Block 7: Process Ride Requests --------------------
        // Log ride details
        logUberRideRequest(rideInfo, "", "", rideInfo.actionButton ?: "N/A", false)

        // Calculate additional metrics for ride requests
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

        // -------------------- Block 8: Update the Floating Overlay --------------------
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
            rideType = rideInfo.rideType ?: "Delivery",
            fare = "$${String.format("%.2f", adjustedFare)}",
            fareColor = fareColor,
            pMile = "$${String.format("%.2f", pricePerMile)}",
            pMileColor = pmileColor,
            pHour = "$${String.format("%.2f", pricePerHour)}",
            pHourColor = phourColor,
            miles = String.format("%.1f", totalMiles),   // Total distance
            minutes = String.format("%.1f", totalMinutes), // Total time
            profit = "$${String.format("%.2f", profit)}",
            profitColor = profitColor,
            rating = "N/A", // Delivery requests do not include a rating.
            stops = rideInfo.stops ?: ""
        )
    }
}

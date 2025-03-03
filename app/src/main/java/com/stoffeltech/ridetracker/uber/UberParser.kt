package com.stoffeltech.ridetracker.uber

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.stoffeltech.ridetracker.services.RideInfo
import kotlin.math.abs

private var lastDeliveryRawFare: Double? = null
private var lastDeliveryAdjustedFare: Double? = null
private var lastDeliveryRequestHash: Int? = null
// Add a fingerprint for Uber ride requests to prevent duplicate processing.
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

//    fun debugTest() {
//        val testText = "UberX PriorityExclusive$7.555.005% Advantage included5 mins (1.3 mi) awayLakeside Dr & Zimmerman Ave, Lehigh Acres13 mins (7.8 mi) tripPennfield Ave & Theodore Vail St E, Lehigh AcresAccept"
//        val rideInfo = parse(testText)
//        if (rideInfo == null) {
//            Log.d("UberParserDebug", "Debug RideInfo: null")
//            return
//        }
//        Log.d("UberParserDebug", "----- Debug RideInfo -----")
//        Log.d("UberParserDebug", "Ride Type: ${rideInfo.rideType}")
//        Log.d("UberParserDebug", "Fare: ${rideInfo.fare}")
//        Log.d("UberParserDebug", "Rating: ${rideInfo.rating}")
//        Log.d("UberParserDebug", "Verified: ${testText.contains("Verified", ignoreCase = true)}")
//        Log.d("UberParserDebug", "Exclusive: ${testText.contains("exclusive", ignoreCase = true)}")
//        Log.d("UberParserDebug", "Action Button: ${rideInfo.actionButton ?: "N/A"}")
//        Log.d("UberParserDebug", "Pickup Time (mins): ${rideInfo.pickupTime}")
//        Log.d("UberParserDebug", "Pickup Distance (mi): ${rideInfo.pickupDistance}")
//        Log.d("UberParserDebug", "Pickup Location: ${rideInfo.pickupLocation}")
//        Log.d("UberParserDebug", "Trip Time (mins): ${rideInfo.tripTime}")
//        Log.d("UberParserDebug", "Trip Distance (mi): ${rideInfo.tripDistance}")
//        Log.d("UberParserDebug", "Dropoff Location: ${rideInfo.tripLocation}")
//        Log.d("UberParserDebug", "Stops (Extra Info): ${rideInfo.stops}")
//        Log.d("UberParserDebug", "--------------------------")
//    }

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
        //        Log.d("UberRideRequest", sb.toString())
    }

    fun parse(cleanedText: String): RideInfo? {
        // Preprocess the text (for accessibility we use the raw text trimmed)
        val text = preprocessAccessibilityText(cleanedText)

        // --- Extract Ride Type ---
        val rideTypeMatch = rideTypeRegex.find(text)
        var rideType = rideTypeMatch?.value ?: "Unknown"

        /* This code checks if the extracted ride type string contains the word "Exclusive" (ignoring letter case). If it does, it sets a flag (stored in the variable exclusive) to true, and then removes the word "Exclusive" from the ride type string and trims any extra whitespace. In effect, it separates the "exclusive" status from the base ride type so you can handle the exclusivity separately while keeping the ride type string clean */
        val exclusive = rideType.contains("Exclusive", ignoreCase = true)
        if (exclusive) {
            rideType = rideType.replace("Exclusive", "", ignoreCase = true).trim()
        }

        // --- Extract Fare ---
        // Use a regex that captures a fare with exactly two decimals.
        val fareRegex = Regex("\\$(\\d+)[.,](\\d{2})")
        val fareMatch = fareRegex.find(text)
        val fare = fareMatch?.let {
            val wholePart = it.groupValues[1]
            val decimalPart = it.groupValues[2]
            "$wholePart.$decimalPart".toDoubleOrNull()
        }

        // --- Extract Rating ---
        // Look for a percentage number anywhere in the text.
        val ratingRegex = Regex("(\\d+(?:\\.\\d{2,3}))%")
        val rating = ratingRegex.find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.let { if (it <= 5.0) it else 5.0 }

        // --- Extract Verified flag ---
        val verified = text.contains("Verified", ignoreCase = true)

        // --- Extract Pickup Info (Time and Distance) ---
        // Expecting something like "5 mins (1.3 mi) away"
        val pickupInfoRegex = Regex("(\\d+)\\s*mins?\\s*\\((\\d+(?:\\.\\d+)?)\\s*mi\\)\\s*away", RegexOption.IGNORE_CASE)
        val pickupMatch = pickupInfoRegex.find(text)
        val pickupTime = pickupMatch?.groupValues?.get(1)?.toDoubleOrNull()
        val pickupDistance = pickupMatch?.groupValues?.get(2)?.toDoubleOrNull()

        // --- Extract Pickup Location ---
        // Look for text between "away" and the next occurrence of time info (for trip)
        val pickupLocationRegex = Regex("away\\s*([^\\d]+?)\\s*\\d+\\s*mins", RegexOption.IGNORE_CASE)
        val pickupLocation = pickupLocationRegex.find(text)?.groupValues?.get(1)?.trim() ?: "N/A"

        // --- Extract Trip Info (Time and Distance) ---
        // Expecting something like "13 mins (7.8 mi) trip"
        val tripInfoRegex = Regex("(\\d+)\\s*mins?\\s*\\((\\d+(?:\\.\\d+)?)\\s*mi\\)\\s*trip", RegexOption.IGNORE_CASE)
        val tripMatch = tripInfoRegex.find(text)
        val tripTime = tripMatch?.groupValues?.get(1)?.toDoubleOrNull()
        val tripDistance = tripMatch?.groupValues?.get(2)?.toDoubleOrNull()

        // --- Extract Dropoff Location ---
        // Look for text between "trip" and "Accept" or "Match"
        val dropoffLocationRegex = Regex("trip\\s*(.*?)\\s*(Accept|Match)", RegexOption.IGNORE_CASE)
        val dropoffLocation = dropoffLocationRegex.find(text)?.groupValues?.get(1)?.trim() ?: "N/A"

        // --- Extract Action Button ---
        // Use a regex anchored to the end to capture "Accept" or "Match" at the end.
        val actionButtonRegex = Regex("(?i)(Accept|Match)\\s*$")
        val actionButton = actionButtonRegex.find(text)?.groupValues?.get(1) ?: "N/A"

        // --- Combine extra fields into an extra info string (for stops) ---
        val stops = if (text.contains("Multiple stops", ignoreCase = true)) "Multiple stops" else ""
        val extraInfo = "Verified: $verified, Exclusive: $exclusive"

        // --- Validation: Ensure required fields are present ---
        if (fare == null || pickupTime == null || tripTime == null) return null

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
}

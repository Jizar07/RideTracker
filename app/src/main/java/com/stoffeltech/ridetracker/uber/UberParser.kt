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

object UberParser {

    // Note: The longer alternatives are placed first so that, for example,
    // "UberX Priority" is matched before "UberX".
    private val rideTypeRegex = Regex(
        "(UberX Priority|UberX Reserve|Uber Black XL|Uber Connect XL|Uber Green|Green Comfort|Uber Black|UberX|UberXL|UberPool|Uber Connect|Uber Share|Comfort|Premier|Pet|Delivery)",
        RegexOption.IGNORE_CASE
    )

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
    fun parseRevenueFromImage(bitmap: Bitmap, onResult: (Double?) -> Unit) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val extractedText = visionText.text
//                Log.d("UberParser", "OCR output for revenue region: $extractedText")
                // Regex to capture "$" followed by one or more digits, a period, and exactly two digits.
                val regex = "\\$(\\d+(?:\\.\\d{2})?)".toRegex()
                val matchResult = regex.find(extractedText)
                val revenue = matchResult?.groupValues?.get(1)?.toDoubleOrNull()
                onResult(revenue)
            }
            .addOnFailureListener { e ->
                Log.e("UberParser", "Failed to extract revenue: ${e.message}")
                onResult(null)
            }
    }

    fun parse(cleanedText: String): RideInfo? {
        // Split the cleaned text into non-empty lines.
        val lines = cleanedText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var fare: Double? = null
        var rideType: String? = null

        // Correct common OCR mistakes.
        val correctedText = cleanedText
            .replace("De1ivery", "Delivery", ignoreCase = true)
            .replace("delive1y", "Delivery", ignoreCase = true)
            .replace("deliv ery", "Delivery", ignoreCase = true) // new correction
            .replace("tota1", "total", ignoreCase = true)
            .replace(Regex("(?<=\\s|^)[^A-Za-z]*(Delivery)", RegexOption.IGNORE_CASE), "$1")


//        Log.d("UberParser", "Corrected Text: $correctedText")

        // --- Attempt 1 (Modified) ---
        // Define a strict pattern for a fare line (e.g. "$24.22")
        val farePattern = Regex("^\\$\\d+(?:[.,]\\d{2})?\$")
        for (i in lines.indices) {
            val line = lines[i]
            // Skip lines mentioning reservation fee.
            if (line.contains("reservation fee included", ignoreCase = true)) continue

            // Use the regex to search the whole line.
            val match = rideTypeRegex.find(line)
            if (match != null) {
                rideType = match.value
                // Only consider the very next line for the fare.
                if (i + 1 < lines.size) {
                    val nextLine = lines[i + 1].trim()
                    if (farePattern.matches(nextLine)) {
                        // Remove the "$" and convert to a Double (replacing commas with periods)
                        fare = nextLine.replace("$", "").replace(",", ".").toDoubleOrNull()
                        if (fare != null) break
                    }
                }
            }
        }

        // This regex matches an optional leading number and whitespace,
// then "Multiple stops" where each "l" can be either 'l' or '1'.
        val multipleStopsRegex = Regex("^(?:\\d+\\s*)?[Mm]u(?:l|1)tip(?:l|1)e stops\$", RegexOption.IGNORE_CASE)
        var stopsValue = ""
        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue
            if (multipleStopsRegex.matches(trimmedLine)) {
                stopsValue = "Multiple stops"
                break
            }
        }
//        Log.d("UberParser", "stops value calculated: '$stopsValue'")
        if (correctedText.contains("Delivery", ignoreCase = true)) {
            rideType = "Delivery"
//            Log.d("UberParserDebug", "Forced override: rideType set to Delivery because correctedText contains 'Delivery'")
        }


        // Fallback: If no ride type found above and corrected text contains "Delivery", set rideType.
        if (rideType == null && !rideTypeRegex.containsMatchIn(correctedText) && correctedText.contains("Delivery", ignoreCase = true)) {
            rideType = "Delivery"
//            Log.d("UberParser", "Fallback: Setting rideType to Delivery based on corrected text")
        }

        // --- Special handling for Delivery rides ---
        if (rideType?.equals("Delivery", ignoreCase = true) == true) {
            // Parse total time and distance.
            val timeRegex = Regex("([0-9]+)\\s*min", RegexOption.IGNORE_CASE)
            val totalTime = timeRegex.find(correctedText)?.groupValues?.get(1)?.toDoubleOrNull()
            val distanceRegex = Regex("\\(([0-9]+(?:\\.[0-9]+)?)\\s*mi\\)", RegexOption.IGNORE_CASE)
            val totalDistance = distanceRegex.find(correctedText)?.groupValues?.get(1)?.toDoubleOrNull()

            // Extract fare using all matches and take the last one.
            val fareRegex = Regex("\\$([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
            val fareMatches = fareRegex.findAll(correctedText).toList()
            val rawFare = fareMatches.lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()

            var fareVal: Double? = null
            if (rawFare != null) {
                // If we have a previous raw fare and the new raw fare is essentially the same (difference < 0.01), reuse the adjusted fare.
                if (lastDeliveryRawFare != null && abs(rawFare - lastDeliveryRawFare!!) < 0.01) {
                    fareVal = lastDeliveryAdjustedFare
                } else {
                    fareVal = rawFare
                    lastDeliveryRawFare = rawFare
                    lastDeliveryAdjustedFare = fareVal
                }
            }

            return RideInfo(
                rideType = rideType,
                fare = fareVal,
                rating = null,
                pickupTime = totalTime,
                pickupDistance = totalDistance,
                pickupLocation = null,
                tripTime = null,
                tripDistance = null,
                tripLocation = null,
                stops = stopsValue
            )
        }

        // --- Attempt 2: Look for a ride type line and then process the very next line for a fare ---
        if (fare == null) {
            for (i in lines.indices) {
                // Skip lines with reservation fee text
                if (lines[i].contains("reservation fee included", ignoreCase = true)) continue
                val match = rideTypeRegex.find(lines[i])
                if (match != null) {
                    rideType = match.value
                    if (i + 1 < lines.size) {
                        val nextLine = lines[i + 1].trim()
                        // Only accept the next line if it exactly matches the fare pattern.
                        if (farePattern.matches(nextLine)) {
                            fare = nextLine.replace("$", "").replace(",", ".").toDoubleOrNull()
                            if (fare != null) break
                        }
                    }
                }
            }
        }

        // --- Attempt 3: Fallback – search backwards from a pickup info line ---
        if (fare == null) {
            val pickupRegex = Regex(".*\\d+\\s*mins.*?\\(.*?mi.*?away", RegexOption.IGNORE_CASE)
            val pickupIndex = lines.indexOfFirst { pickupRegex.containsMatchIn(it) }
            if (pickupIndex > 0) {
                for (j in (pickupIndex - 1) downTo 0) {
                    if (lines[j].contains("reservation fee included", ignoreCase = true)) continue
                    val candidate = lines[j].trim()
                    // Only consider the candidate if it exactly matches a fare pattern.
                    if (farePattern.matches(candidate)) {
                        fare = candidate.replace("$", "").replace(",", ".").toDoubleOrNull()
                        if (fare != null) break
                    }
                }
            }
        }

        // --- Extract pickup and trip info ---
        val pickupTimeRegex = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*mins.*?\\(([0-9]+(?:\\.[0-9]+)?)\\s*mi\\).*?away", RegexOption.IGNORE_CASE)
        val pickupMatch = pickupTimeRegex.find(cleanedText)
        val pickupTimeVal = pickupMatch?.groupValues?.get(1)?.toDoubleOrNull()
        val pickupDistanceVal = pickupMatch?.groupValues?.get(2)?.toDoubleOrNull()

        val tripRegex = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*mins.*?\\(([0-9]+(?:\\.[0-9]+)?)\\s*mi\\).*?trip", RegexOption.IGNORE_CASE)
        val tripMatch = tripRegex.find(cleanedText)
        val tripTimeVal = tripMatch?.groupValues?.get(1)?.toDoubleOrNull()
        val tripDistanceVal = tripMatch?.groupValues?.get(2)?.toDoubleOrNull()

        // Determine pickup and dropoff addresses.
        val pickupInfoIndex = lines.indexOfLast { it.contains("mins", ignoreCase = true) && it.contains("away", ignoreCase = true) }
        val pickupAddress = if (pickupInfoIndex != -1 && pickupInfoIndex + 1 < lines.size)
            lines[pickupInfoIndex + 1] else "N/A"
        val tripInfoIndex = lines.indexOfLast { it.contains("mins", ignoreCase = true) && it.contains("trip", ignoreCase = true) }
        val dropoffAddress = if (tripInfoIndex != -1 && tripInfoIndex + 1 < lines.size)
            lines[tripInfoIndex + 1] else "N/A"

        // --- Fare validation using extracted tripDistanceVal ---
        if (fare != null && tripDistanceVal != null) {
            val farePerMile = fare / tripDistanceVal
            val lowerBound = 0.5
            val upperBound = 10.0
            if (farePerMile < lowerBound || farePerMile > upperBound) {
                val adjustedFare = fare / 100.0
                val adjustedFarePerMile = adjustedFare / tripDistanceVal
                if (adjustedFarePerMile in lowerBound..upperBound) {
                    fare = adjustedFare
                }
            }
        }

        // --- Rating extraction ---
        val ratingRegex = Regex("[★*]\\s*([0-9]+\\.[0-9]{2})")
        val ratingVal = ratingRegex.find(cleanedText)?.groupValues?.get(1)?.toDoubleOrNull()
        val finalRating = ratingVal?.takeIf { it in 0.0..5.0 }

        if (fare == null && pickupTimeVal == null && tripTimeVal == null) return null

        return RideInfo(
            rideType = rideType,
            fare = fare,
            rating = finalRating,
            pickupTime = pickupTimeVal,
            pickupDistance = pickupDistanceVal,
            pickupLocation = pickupAddress,
            tripTime = tripTimeVal,
            tripDistance = tripDistanceVal,
            tripLocation = dropoffAddress,
            stops = stopsValue
        )
    }
}

package com.stoffeltech.ridetracker.uber

import android.util.Log
import com.stoffeltech.ridetracker.services.RideInfo

/**
 * Log a formatted Uber ride request message.
 *
 * @param rideInfo Parsed ride request data.
 * @param pickupLocation The full pickup address (can be multi‑line).
 * @param dropoffLocation The full dropoff address (can be multi‑line).
 * @param actionButton A string representing the action (e.g. "Accept" in blue or "Match" in gray).
 */


object UberParser {
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

        // Bottom line: Action button (Accept or Match)
        sb.appendLine("Action Button: $actionButton")

        Log.d("UberRideRequest", sb.toString())
    }

    fun parse(cleanedText: String): RideInfo? {
        // Split the cleaned text into non-empty lines.
        val lines = cleanedText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var fare: Double? = null
        var rideType: String? = null
        // Adjust the regex as needed for Uber ride type keywords.
        val rideTypeRegex = Regex("(UberX|UberX Priority|UberXL|Uber Green|Green Comfort|Comfort|Premier|Exclusive|Pet|Uber Black|Uber Black XL|UberPool|Uber Connect|Uber Connect XL|Uber Share)", RegexOption.IGNORE_CASE)

        // --- Attempt 1: Look for a line that contains both "$" and a ride type ---
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
        // --- Attempt 2: Look for a ride type line and then check the following line for a fare ---
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
        // --- Attempt 3: Fallback – search backwards from a pickup info line
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

        // Primary regex: look for a star (★ or *) followed by a number.
        val ratingRegex = Regex("[★*]\\s*([0-9]+\\.[0-9]{2})")
        val rating = ratingRegex.find(cleanedText)?.groupValues?.get(1)?.toDoubleOrNull()
        val finalRating = rating?.takeIf { it in 0.0..5.0 }


        // Extract pickup info.
        val pickupTimeRegex = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*mins.*?\\(([0-9]+(?:\\.[0-9]+)?)\\s*mi\\).*?away", RegexOption.IGNORE_CASE)
        val pickupMatch = pickupTimeRegex.find(cleanedText)
        val pickupTime = pickupMatch?.groupValues?.get(1)?.toDoubleOrNull()
        val pickupDistance = pickupMatch?.groupValues?.get(2)?.toDoubleOrNull()
        val pickupInfoIndex = lines.indexOfLast { it.contains("mins", ignoreCase = true) && it.contains("away", ignoreCase = true) }
        val pickupAddress = if (pickupInfoIndex != -1 && pickupInfoIndex + 1 < lines.size)
            lines[pickupInfoIndex + 1] else "N/A"

        // Extract trip info.
        val tripRegex = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*mins.*?\\(([0-9]+(?:\\.[0-9]+)?)\\s*mi\\).*?trip", RegexOption.IGNORE_CASE)
        val tripMatch = tripRegex.find(cleanedText)
        val tripTime = tripMatch?.groupValues?.get(1)?.toDoubleOrNull()
        val tripDistance = tripMatch?.groupValues?.get(2)?.toDoubleOrNull()
        val tripInfoIndex = lines.indexOfLast { it.contains("mins", ignoreCase = true) && it.contains("trip", ignoreCase = true) }
        val dropoffAddress = if (tripInfoIndex != -1 && tripInfoIndex + 1 < lines.size)
            lines[tripInfoIndex + 1] else "N/A"

        if (fare == null && pickupTime == null && tripTime == null) return null

        return RideInfo(
            rideType = rideType,
            fare = fare,
            rating = finalRating,
            pickupTime = pickupTime,
            pickupDistance = pickupDistance,
            pickupLocation = pickupAddress,
            tripTime = tripTime,
            tripDistance = tripDistance,
            tripLocation = dropoffAddress
        )
    }
}

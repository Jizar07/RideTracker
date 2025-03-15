// File: LyftParser.kt
package com.stoffeltech.ridetracker.lyft

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import com.stoffeltech.ridetracker.SettingsActivity
import com.stoffeltech.ridetracker.services.FloatingOverlayService
import com.stoffeltech.ridetracker.services.RideInfo
import com.stoffeltech.ridetracker.utils.FileLogger

// Define Lyft-specific keys internally.
object LyftKeys {
    const val KEY_ACCEPT_MILE = "pref_accept_mile"       // default: 1.0
    const val KEY_DECLINE_MILE = "pref_decline_mile"       // default: 0.75
    const val KEY_ACCEPT_HOUR = "pref_accept_hour"         // default: 25.0
    const val KEY_DECLINE_HOUR = "pref_decline_hour"       // default: 20.0
    const val KEY_FARE_LOW = "pref_fare_low"               // default: 5.0
    const val KEY_FARE_HIGH = "pref_fare_high"             // default: 10.0
    const val KEY_BONUS_RIDE = "pref_bonus_ride"
    const val KEY_RATING_THRESHOLD = "pref_rating_threshold" // default: 4.70
}

object LyftParser {

    // ---------------- LAST VALID REQUEST TIMESTAMP -----------------
    var lastValidRequestTime: Long = 0

    // Existing variables for duplicate checks
    private var lastLyftRequestFingerprint: String? = null
    private var lastLyftForegroundRequestTime: Long = 0
    private var lastLyftFingerprint: String? = null
    private var lastLyftFingerprintTime: Long = 0

    // Updated ride types list (not used further in parsing below)
    private val rideTypes = listOf(
        "Lyft(?: request)?(?:\\s*[•])?"
    )
    private val rideTypeRegex: Regex by lazy {
        Regex(rideTypes.joinToString(separator = "|"), RegexOption.IGNORE_CASE)
    }

    // Since ACC text is now assumed clean, we simply trim it.
    private fun preprocessAccessibilityText(text: String): String {
        // Trim whitespace
        var cleanedText = text.trim()
        // Remove known extraneous tokens
        val extraneousTokens = listOf("Loading", "Profile image of the rider")
        extraneousTokens.forEach { token ->
            cleanedText = cleanedText.replace(token, "", ignoreCase = true)
        }
        return cleanedText
    }

    // Bring Lyft app to foreground.
    private fun bringLyftAppToForeground(context: Context) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastLyftForegroundRequestTime < 2000) {
            return
        }
        lastLyftForegroundRequestTime = currentTime

        val packageManager = context.packageManager
        var intent = packageManager.getLaunchIntentForPackage("com.lyft.android.driver")
        if (intent == null) {
            intent = Intent()
            intent.component = ComponentName("com.lyft.android.driver", "com.lyft.android.driver.app.ui.DriverMainActivity")
        }
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            FileLogger.log("LyftParser", "Lyft app not found on device.")
        }
    }

    /**
     * Main parse function that dispatches based on the starting token of the ACC text.
     * It supports two formats:
     * - Format 1: Starts with "Reject ride" (previous style).
     * - Format 2: Starts with "DismissRide Finder" (new, Match-style format).
     */
    fun parse(accText: String): RideInfo? {
        val text = preprocessAccessibilityText(accText)
        return when {
            text.startsWith("DismissRide Finder") -> parseMatchFormat(text)
            text.startsWith("Reject ride") -> parseRejectFormat(text)
            else -> null
        }
    }

    /**
     * Parse ACC text in the "Reject ride" format.
     * Expected token order:
     * 1. "Reject ride"                   - Skip.
     * 2. "$20.15"                        - Fare.
     * 3. "Pay includes pickup"           - Skip.
     * 4. "$21.98/hr est. rate for this ride" - Skip.
     * 5. "11 min • 5.5 mi"               - Pickup time & distance.
     * 6. "Umber & Unger, Ft. Myers"      - Pickup location.
     * 7. "44 min • 22 mi"                - Dropoff time & distance.
     * 8. "Char Ann & Juanita, Ft. Myers"   - Dropoff location.
     * 9. "Dulce"                         - Rider's Name.
     * 10. "5.0"                          - Rider's Rating (assigned to rating).
     * 11. "Accept"                      - Action Button.
     */
    private fun parseRejectFormat(text: String): RideInfo? {
        var currentIndex = 0

        // Step 1: Skip the initial token "Reject ride"
        val rejectToken = "Reject ride"
        if (text.startsWith(rejectToken)) {
            currentIndex = rejectToken.length
        } else {
            FileLogger.log("LyftParser", "Text does not start with 'Reject ride'.")
            return null
        }

        // Step 2: Extract Fare by finding the first occurrence of a dollar amount.
        val fareRegex = Regex("\\$(\\d+\\.\\d{2})")
        val fareMatch = fareRegex.find(text, currentIndex)
        val fare = fareMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        if (fareMatch != null) {
            currentIndex = fareMatch.range.last + 1
            FileLogger.log("LyftParser", "Extracted fare: $$fare")
        } else {
            FileLogger.log("LyftParser", "Fare not found.")
            return null
        }

        // Step 3: Skip "Pay includes pickup"
        // ----- UPDATED CODE: Skip token: accept multiple alternatives -----
        val payTokenCandidates = listOf("Pay includes pickup", "Scheduled ride", "Incl.", "Bonus")
        var foundToken: String? = null
        for (token in payTokenCandidates) {
            val tokenIndex = text.indexOf(token, currentIndex)
            if (tokenIndex != -1) {
                foundToken = token
                currentIndex = tokenIndex + token.length
                FileLogger.log("LyftParser", "Skipped token: '$token'")
                break
            }
        }
        if (foundToken == null) {
            FileLogger.log("LyftParser", "None of the expected tokens found after fare.")
//            return null
        }

        // Step 4: Skip the rate info token if it exists. Otherwise, just continue.
        // This rate info is not critical for calculations.
        val rateRegex = Regex("\\$\\d+\\.\\d+\\/hr est\\. rate for this ride", RegexOption.IGNORE_CASE)
        val rateMatch = rateRegex.find(text, currentIndex)
        if (rateMatch != null) {
            currentIndex = rateMatch.range.last + 1
        } else {
            Log.w("LyftParser", "Rate info not found, but proceeding as it is not required.")
            // Optionally, you could adjust currentIndex here if needed, or simply continue.
        }

        // Step 5: Extract Pickup Time and Distance using regex capturing time before "min" and distance before "mi"
        val timeDistRegex = Regex("(\\d+)\\s*min.*?(\\d+(?:\\.\\d+)?)\\s*mi", RegexOption.IGNORE_CASE)
        val pickupMatch = timeDistRegex.find(text, currentIndex)
        var pickupTime: Double? = null
        var pickupDistance: Double? = null
        var pickupInfoEnd = currentIndex
        if (pickupMatch != null) {
            pickupTime = pickupMatch.groupValues[1].trim().toDoubleOrNull()
            pickupDistance = pickupMatch.groupValues[2].trim().toDoubleOrNull()
            pickupInfoEnd = pickupMatch.range.last + 1
            FileLogger.log("LyftParser", "Pickup info: '${pickupMatch.value}', time: $pickupTime, distance: $pickupDistance")
        } else {
            FileLogger.log("LyftParser", "Pickup time/distance not found.")
            return null
        }
        currentIndex = pickupInfoEnd

        // Step 6: Extract Pickup Location.
        // Text between the end of pickup info and the next time-distance pattern (dropoff info)
        val dropoffMatch = timeDistRegex.find(text, currentIndex)
        var pickupLocation = "N/A"
        if (dropoffMatch != null) {
            pickupLocation = text.substring(currentIndex, dropoffMatch.range.first).trim()
            currentIndex = dropoffMatch.range.first
            FileLogger.log("LyftParser", "Pickup location: '$pickupLocation'")
        } else {
            FileLogger.log("LyftParser", "Dropoff time/distance not found; cannot determine pickup location.")
            return null
        }

        // Step 7: Extract Dropoff Time and Distance from the dropoff match.
        var tripTime: Double? = null
        var tripDistance: Double? = null
        var dropoffInfoEnd = currentIndex
        if (dropoffMatch != null) {
            tripTime = dropoffMatch.groupValues[1].trim().toDoubleOrNull()
            tripDistance = dropoffMatch.groupValues[2].trim().toDoubleOrNull()
            dropoffInfoEnd = dropoffMatch.range.last + 1
            FileLogger.log("LyftParser", "Dropoff info: '${dropoffMatch.value}', time: $tripTime, distance: $tripDistance")
        } else {
            FileLogger.log("LyftParser", "Dropoff info not found.")
            return null
        }
        currentIndex = dropoffInfoEnd

        // Step 8: The remaining text (from currentIndex up to the action button) should include:
        // [Dropoff location][Rider's Name][Rider's Rating]
        // Find the action button ("Accept" or "Match") at the end.
        val actionRegex = Regex("(Accept|Match)$", RegexOption.IGNORE_CASE)
        val actionMatch = actionRegex.find(text)
        if (actionMatch == null) {
            FileLogger.log("LyftParser", "Action button not found.")
            return null
        }
        val actionButton = actionMatch.groupValues[1]
        val remaining = text.substring(currentIndex, actionMatch.range.first).trim()
        FileLogger.log("LyftParser", "Remaining text for dropoff location, rider's name and rating: '$remaining'")

        // Preprocess remaining text:
        // Insert a space before any uppercase letter that follows a lowercase letter,
        // and also before a digit if missing.
        val cleanRemaining = remaining
            .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            .replace(Regex("(?<=[A-Za-z])(?=\\d)"), " ")
        FileLogger.log("LyftParser", "Cleaned remaining text: '$cleanRemaining'")

        // Step 9: Split the cleaned remaining text into dropoff location, rider's name, and rider's rating.
        // Expected format: "[Dropoff location] [Rider's Name] [Rider's Rating]"
        val remainingRegex = Regex("^(.*?)\\s+(?:Scheduled ride\\s+)?([A-Za-z]+)(?=Verified|\\s)\\s*(?:Verified\\s+)?(\\d+\\.\\d+)$")
        val remainingMatch = remainingRegex.find(cleanRemaining)
        var dropoffLocation = "N/A"
        var riderName = "N/A"
        var riderRating: Double? = null
        if (remainingMatch != null) {
            dropoffLocation = remainingMatch.groupValues[1].trim()
            riderName = remainingMatch.groupValues[2].trim()
            riderRating = remainingMatch.groupValues[3].toDoubleOrNull()
            FileLogger.log("LyftParser", "Extracted dropoff location: '$dropoffLocation', Rider's Name: '$riderName', Rider's Rating: $riderRating")
        } else {
            Log.w("LyftParser", "Optional details not fully parsed. Using default values.")
            dropoffLocation = "N/A"
            riderName = "N/A"
            riderRating = null
        }

        // Set ride type to "Lyft" and default other fields.
        val rideType = "Lyft"
        val rideSubtype: String? = null
        val bonuses: String? = null
        val isExclusive = false

        // Build and return the RideInfo object.
        val rideInfo = RideInfo(
            rideType = rideType,
            fare = fare,
            rating = riderRating,          // Assign rider's rating to the 'rating' field
            pickupTime = pickupTime,
            pickupDistance = pickupDistance,
            tripTime = tripTime,
            tripDistance = tripDistance,
            pickupLocation = pickupLocation,
            tripLocation = dropoffLocation, // Dropoff location
            stops = "",                     // Not provided via ACC; adjust if needed.
            actionButton = actionButton,
            rideSubtype = rideSubtype,
            bonuses = bonuses,
            isExclusive = isExclusive,
            riderName = riderName           // Rider's Name remains a separate field.
        )

        FileLogger.log("LyftParser", "Parsed ride info: $rideInfo")
        return rideInfo
    }

    /**
     * Parse ACC text in the "DismissRide Finder" (Match) format.
     * Expected token order:
     * 1. "DismissRide Finder"                - Skip.
     * 2. "$16.09"                           - Fare.
     * 3. "$25.40/hr est. rate for this ride"  - Skip.
     * 4. "Map image with pickup and drop-off route" - Skip.
     * 5. "7 min - 3.1 mi"                   - Pickup time & distance (hyphen or bullet allowed).
     * 6. "Ermine & Mayfield, Lehigh Acres"  - Pickup location.
     * 7. "31 min - 19.5 mi"                 - Dropoff time & distance.
     * 8. "Colonial & Walmart Plz, Ft. Myers"  - Dropoff location.
     * 9. "Samantha"                         - Rider's Name.
     * 10. "5.0"                             - Rider's Rating.
     * 11. "Request match"                   - Action Button.
     */
    private fun parseMatchFormat(text: String): RideInfo? {
        var currentIndex = 0
        val token = "DismissRide Finder"
        if (text.startsWith(token)) {
            currentIndex = token.length
        } else {
            FileLogger.log("LyftParser", "Text does not start with 'DismissRide Finder'.")
            return null
        }

        // Extract Fare.
        val fareRegex = Regex("\\$(\\d+\\.\\d{2})")
        val fareMatch = fareRegex.find(text, currentIndex)
        val fare = fareMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        if (fareMatch != null) {
            currentIndex = fareMatch.range.last + 1
            FileLogger.log("LyftParser", "Extracted fare (Match): $$fare")
        } else {
            FileLogger.log("LyftParser", "Fare not found in Match format.")
            return null
        }

        // Skip rate info: "$25.40/hr est. rate for this ride"
        val rateRegex = Regex("\\$\\d+\\.\\d+\\/hr est\\. rate for this ride", RegexOption.IGNORE_CASE)
        val rateMatch = rateRegex.find(text, currentIndex)
        if (rateMatch != null) {
            currentIndex = rateMatch.range.last + 1
        } else {
            FileLogger.log("LyftParser", "Rate info not found in Match format.")
            return null
        }

        // Skip "Map image with pickup and drop-off route"
        val mapToken = "Map image with pickup and drop-off route"
        val mapIndex = text.indexOf(mapToken, currentIndex)
        if (mapIndex != -1) {
            currentIndex = mapIndex + mapToken.length
        } else {
            FileLogger.log("LyftParser", "Map token not found in Match format.")
            return null
        }

        // Use updated time-distance regex that accepts hyphen or bullet.
        val timeDistRegex = Regex("(\\d+)\\s*min.*?(\\d+(?:\\.\\d+)?)\\s*mi", RegexOption.IGNORE_CASE)

        // Extract Pickup Time & Distance ("7 min - 3.1 mi")
        val pickupMatch = timeDistRegex.find(text, currentIndex)
        var pickupTime: Double? = null
        var pickupDistance: Double? = null
        var pickupInfoEnd = currentIndex
        if (pickupMatch != null) {
            pickupTime = pickupMatch.groupValues[1].trim().toDoubleOrNull()
            pickupDistance = pickupMatch.groupValues[2].trim().toDoubleOrNull()
            pickupInfoEnd = pickupMatch.range.last + 1
            FileLogger.log("LyftParser", "Pickup info (Match): '${pickupMatch.value}', time: $pickupTime, distance: $pickupDistance")
        } else {
            FileLogger.log("LyftParser", "Pickup time/distance not found in Match format.")
            return null
        }
        currentIndex = pickupInfoEnd

        // Extract Pickup Location: text from currentIndex until the next time-distance pattern (dropoff info)
        val dropoffMatch = timeDistRegex.find(text, currentIndex)
        var pickupLocation = "N/A"
        if (dropoffMatch != null) {
            pickupLocation = text.substring(currentIndex, dropoffMatch.range.first).trim()
            currentIndex = dropoffMatch.range.first
            FileLogger.log("LyftParser", "Pickup location (Match): '$pickupLocation'")
        } else {
            FileLogger.log("LyftParser", "Dropoff time/distance not found in Match format; cannot determine pickup location.")
            return null
        }

        // Extract Dropoff Time & Distance ("31 min - 19.5 mi")
        var tripTime: Double? = null
        var tripDistance: Double? = null
        var dropoffInfoEnd = currentIndex
        if (dropoffMatch != null) {
            tripTime = dropoffMatch.groupValues[1].trim().toDoubleOrNull()
            tripDistance = dropoffMatch.groupValues[2].trim().toDoubleOrNull()
            dropoffInfoEnd = dropoffMatch.range.last + 1
            FileLogger.log("LyftParser", "Dropoff info (Match): '${dropoffMatch.value}', time: $tripTime, distance: $tripDistance")
        } else {
            FileLogger.log("LyftParser", "Dropoff info not found in Match format.")
            return null
        }
        currentIndex = dropoffInfoEnd

        // Extract the Action Button: expected at the end ("Request match")
        val actionRegex = Regex("(Request match)$", RegexOption.IGNORE_CASE)
        val actionMatch = actionRegex.find(text)
        if (actionMatch == null) {
            FileLogger.log("LyftParser", "Action button not found in Match format.")
            return null
        }
        val actionButton = actionMatch.groupValues[1]

        // The remaining text (from currentIndex to the action button) should include:
        // [Dropoff location][Rider's Name][Rider's Rating]
        val remaining = text.substring(currentIndex, actionMatch.range.first).trim()
        FileLogger.log("LyftParser", "Remaining text (Match) for dropoff location, rider's name and rating: '$remaining'")
        val cleanRemaining = remaining
            .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            .replace(Regex("(?<=[A-Za-z])(?=\\d)"), " ")
        FileLogger.log("LyftParser", "Cleaned remaining text (Match): '$cleanRemaining'")
        val remainingRegex = Regex("^(.*)\\s+([A-Za-z]+)\\s+(\\d+\\.\\d+)$")
        val remainingMatch = remainingRegex.find(cleanRemaining)
        var dropoffLocation = "N/A"
        var riderName = "N/A"
        var riderRating: Double? = null
        if (remainingMatch != null) {
            dropoffLocation = remainingMatch.groupValues[1].trim()
            riderName = remainingMatch.groupValues[2].trim()
            riderRating = remainingMatch.groupValues[3].toDoubleOrNull()
            FileLogger.log("LyftParser", "Extracted (Match) dropoff location: '$dropoffLocation', Rider's Name: '$riderName', Rider's Rating: $riderRating")
        } else {
            FileLogger.log("LyftParser", "Failed to parse remaining text in Match format into dropoff location, rider's name, and rating.")
            return null
        }

        // Construct the RideInfo object for the Match format.
        val rideType = "Match"
        val rideSubtype: String? = null
        val bonuses: String? = null
        val isExclusive = false
        val rideInfo = RideInfo(
            rideType = rideType,
            fare = fare,
            rating = riderRating,
            pickupTime = pickupTime,
            pickupDistance = pickupDistance,
            tripTime = tripTime,
            tripDistance = tripDistance,
            pickupLocation = pickupLocation,
            tripLocation = dropoffLocation,
            stops = "",
            actionButton = actionButton,
            rideSubtype = rideSubtype,
            bonuses = bonuses,
            isExclusive = isExclusive,
            riderName = riderName
        )
        FileLogger.log("LyftParser", "Parsed ride info (Match format): $rideInfo")
        return rideInfo
    }

    /**
     * Log the ride request details in a structured format.
     * This function is modeled after UberParser's log function.
     */
    fun logLyftRideRequest(
        rideInfo: RideInfo,
        pickupLocation: String,
        dropoffLocation: String,
        actionButton: String,
        verified: Boolean
    ) {
        val sb = StringBuilder()
        sb.appendLine("1) Ride Type: ${rideInfo.rideType ?: "Undetected"}")
        if (!rideInfo.rideSubtype.isNullOrBlank()) {
            sb.appendLine("2) Subtype: ${rideInfo.rideSubtype}")
        } else {
            sb.appendLine("2) Subtype: N/A")
        }
        sb.appendLine("3) Fare Price: \$${rideInfo.fare ?: "N/A"}")
        val ratingText = rideInfo.rating?.toString() ?: "N/A"
        val verifiedText = if (verified) " (Verified)" else ""
        sb.appendLine("4) Rating: $ratingText$verifiedText")
        if (!rideInfo.bonuses.isNullOrBlank()) {
            sb.appendLine("5) Bonuses: ${rideInfo.bonuses}")
        } else {
            sb.appendLine("5) Bonuses: None")
        }
        sb.appendLine("6) Pickup Time/Distance: ${rideInfo.pickupTime ?: "N/A"} mins (${rideInfo.pickupDistance ?: "N/A"} mi) away")
        sb.appendLine("7) Pickup Location: $pickupLocation")
        sb.appendLine("8) Dropoff Time/Distance: ${rideInfo.tripTime ?: "N/A"} mins (${rideInfo.tripDistance ?: "N/A"} mi) trip")
        sb.appendLine("9) Dropoff Location: $dropoffLocation")
        val stopsText = if (rideInfo.stops?.isNotEmpty() == true) rideInfo.stops else "None"
        sb.appendLine("10) Special Events: $stopsText")
        sb.appendLine("11) Action Button: $actionButton")
        FileLogger.log("LyftParser", sb.toString())
    }

    /**
     * Process the ride request using ACC text.
     * Updated to accept a Context so that both accessibility services and activities can call this method.
     */
    fun processLyftRideRequest(accText: String, context: Context) {

        // ----- NEW: Guard clause to ignore empty events -----
        if (accText.trim().isEmpty()) {
//            FileLogger.log("LyftParser", "ACC text is empty, ignoring event.")
            return
        }

        val preprocessedText = preprocessAccessibilityText(accText)
        val rideInfo = parse(preprocessedText)
        if (rideInfo != null) {
            // ============ NEW DUPLICATE CHECK ============
            val adjustedFare = rideInfo.fare ?: 0.0
            val pickupDistanceVal = rideInfo.pickupDistance ?: 0.0
            val tripDistanceVal = rideInfo.tripDistance ?: 0.0
            val totalMiles = pickupDistanceVal + tripDistanceVal
            val pickupTimeVal = rideInfo.pickupTime ?: 0.0
            val tripTimeVal = rideInfo.tripTime ?: 0.0
            val totalMinutes = pickupTimeVal + tripTimeVal

            val fingerprint = "$adjustedFare-$totalMiles-$totalMinutes"
            val currentTime = System.currentTimeMillis()

            // If same fingerprint arrives within 30 seconds, skip
            if (fingerprint == lastLyftFingerprint &&
                (currentTime - lastLyftFingerprintTime) < 30_000
            ) {
                FileLogger.log("LyftParser", "Skipping duplicate Lyft request: $fingerprint")
                return
            }
            lastLyftFingerprint = fingerprint
            lastLyftFingerprintTime = currentTime
            // ============ END DUPLICATE CHECK ============

            lastValidRequestTime = System.currentTimeMillis()
            FileLogger.log("LyftParser", "Processed ride info: $rideInfo")
            // Example: Log the ride request details with a verification flag.
            // You can adjust the verified flag based on your own logic.
            logLyftRideRequest(
                rideInfo,
                pickupLocation = rideInfo.pickupLocation ?: "",
                dropoffLocation = rideInfo.tripLocation ?: "",
                actionButton = rideInfo.actionButton ?: "",
                verified = false
            )
            // ----- NEW: Bring Lyft app to foreground before parsing ACC text -----
            bringLyftAppToForeground(context)
            // Load Lyft-specific thresholds from "lyft_prefs"
            val lyftPrefs = context.getSharedPreferences("lyft_prefs", Context.MODE_PRIVATE)
            // For cost-to-drive, read from main settings.
            val mainPrefs = PreferenceManager.getDefaultSharedPreferences(context)

            val bonus = lyftPrefs.getFloat(LyftKeys.KEY_BONUS_RIDE, 0.0f).toDouble()
            val fareLow = lyftPrefs.getFloat(LyftKeys.KEY_FARE_LOW, 5.0f)
            val fareHigh = lyftPrefs.getFloat(LyftKeys.KEY_FARE_HIGH, 10.0f)
            val acceptMile = lyftPrefs.getFloat(LyftKeys.KEY_ACCEPT_MILE, 1.0f)
            val declineMile = lyftPrefs.getFloat(LyftKeys.KEY_DECLINE_MILE, 0.75f)
            val acceptHour = lyftPrefs.getFloat(LyftKeys.KEY_ACCEPT_HOUR, 25.0f)
            val declineHour = lyftPrefs.getFloat(LyftKeys.KEY_DECLINE_HOUR, 20.0f)
            val ratingThreshold = lyftPrefs.getFloat(LyftKeys.KEY_RATING_THRESHOLD, 4.70f)

            val adjustedFare2 = adjustedFare + bonus
            val computedFareColor = when {
                adjustedFare2 < fareLow -> Color.RED
                adjustedFare2 < fareHigh -> Color.YELLOW
                else -> Color.GREEN
            }
            val pricePerMile = if (totalMiles > 0) adjustedFare2 / totalMiles else 0.0
            val computedPMileColor = when {
                pricePerMile < declineMile -> Color.RED
                pricePerMile < acceptMile -> Color.YELLOW
                else -> Color.GREEN
            }
            val pricePerHour = if (totalMinutes > 0) adjustedFare2 / (totalMinutes / 60.0) else 0.0
            val computedPHourColor = when {
                pricePerHour < declineHour -> Color.RED
                pricePerHour < acceptHour -> Color.YELLOW
                else -> Color.GREEN
            }
            val currentRating = rideInfo.rating?.toFloat() ?: 0f
            val computedRatingColor = if (currentRating >= ratingThreshold) Color.GREEN else Color.RED

            val drivingCost = mainPrefs.getFloat(SettingsActivity.KEY_COST_DRIVING, 0.20f) * totalMiles
            val profit = adjustedFare2 - drivingCost
            val computedProfitColor = if (profit >= 0) Color.GREEN else Color.RED

            FloatingOverlayService.updateOverlay(
                rideType = rideInfo.rideType ?: "Undetected",
                isExclusive = rideInfo.isExclusive,
                fare = "$${String.format("%.2f", adjustedFare2)}",
                fareColor = computedFareColor,
                pMile = "$${String.format("%.2f", pricePerMile)}",
                pMileColor = computedPMileColor,
                pHour = "$${String.format("%.2f", pricePerHour)}",
                pHourColor = computedPHourColor,
                miles = String.format("%.1f", totalMiles),
                minutes = String.format("%.1f", totalMinutes),
                profit = "$${String.format("%.2f", profit)}",
                profitColor = computedProfitColor,
                rating = rideInfo.rating?.toString() ?: "N/A",
                ratingColor = computedRatingColor,  // Pass computed rating color here
                stops = rideInfo.stops ?: ""
            )

            // Finally, add the ride to history
            com.stoffeltech.ridetracker.services.HistoryManager.addRideRequest(rideInfo, context)
        } else {
//            FileLogger.log("LyftParser", "Failed to parse ride info from ACC text: $accText")
            // Schedule the overlay to hide after 15 seconds.
            Handler(Looper.getMainLooper()).postDelayed({
                FloatingOverlayService.hideOverlay()
            }, 10000)

        }
    }

    fun isValidRideRequest(text: String): Boolean {
        val rideInfo = parse(text)
        return rideInfo?.fare != null && rideInfo.fare > 0.0 &&
                rideInfo.pickupTime != null && rideInfo.pickupTime > 0.0 &&
                (rideInfo.rideType?.contains("Delivery", ignoreCase = true) == true ||
                        (rideInfo.tripTime != null && rideInfo.tripTime > 0.0))
    }




    // Add this function at the end of the LyftParser object for debugging purposes.
//    fun debugTestParse() {
//        // Define a hardcoded ACC text string for testing.
//        val testAccText = "Reject ride\$20.15Pay includes pickup\$21.98/hr est. rate for this ride11 min • 5.5 miUmber & Unger, Ft. Myers44 min • 22 miChar Ann & Juanita, Ft. MyersDulce5.0Accept"
//        // Log the test ACC text so you know what input is being parsed.
//        FileLogger.log("LyftParserDebug", "Test ACC text: $testAccText")
//        // Call the parse function on the test string.
//        val rideInfo = parse(testAccText)
//        // Log the parsed ride info result.
//        if (rideInfo != null) {
//            FileLogger.log("LyftParserDebug", "Debug parsed ride info: $rideInfo")
//            // Optionally, log detailed info:
//            logLyftRideRequest(
//                rideInfo,
//                pickupLocation = rideInfo.pickupLocation ?: "",
//                dropoffLocation = rideInfo.tripLocation ?: "",
//                actionButton = rideInfo.actionButton ?: "",
//                verified = false
//            )
//        } else {
//            FileLogger.log("LyftParserDebug", "Debug parsing failed for test ACC text.")
//        }
//    }

}

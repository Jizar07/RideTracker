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

object LyftParser {

    // ---------------- LAST VALID REQUEST TIMESTAMP -----------------
    var lastValidRequestTime: Long = 0
    private var lastLyftRequestFingerprint: String? = null

    // Updated ride types list (not used further in parsing below)
    private val rideTypes = listOf(
        "Lyft(?: request)?(?:\\s*[•])?"
    )
    private val rideTypeRegex: Regex by lazy {
        Regex(rideTypes.joinToString(separator = "|"), RegexOption.IGNORE_CASE)
    }

    // Since ACC text is now assumed clean, we simply trim it.
    private fun preprocessAccessibilityText(text: String): String {
        return text.trim()
    }
    // ----- NEW VARIABLE: Timestamp for the last foreground request -----
    private var lastLyftForegroundRequestTime: Long = 0


    /**
     * Main parse function that dispatches based on the starting token of the ACC text.
     * It supports two formats:
     * - Format 1: Starts with "Reject ride" (previous style).
     * - Format 2: Starts with "DismissRide Finder" (new, Match-style format).
     */
    fun parse(accText: String): RideInfo? {
        val text = preprocessAccessibilityText(accText)
        return if (text.startsWith("DismissRide Finder")) {
            parseMatchFormat(text)
        } else if (text.startsWith("Reject ride")) {
            parseRejectFormat(text)
        } else {
//            Log.d("LyftParser", "Unsupported ACC format")
            null
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
            Log.d("LyftParser", "Text does not start with 'Reject ride'.")
            return null
        }

        // Step 2: Extract Fare by finding the first occurrence of a dollar amount.
        val fareRegex = Regex("\\$(\\d+\\.\\d{2})")
        val fareMatch = fareRegex.find(text, currentIndex)
        val fare = fareMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        if (fareMatch != null) {
            currentIndex = fareMatch.range.last + 1
            Log.d("LyftParser", "Extracted fare: $$fare")
        } else {
            Log.d("LyftParser", "Fare not found.")
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
                Log.d("LyftParser", "Skipped token: '$token'")
                break
            }
        }
        if (foundToken == null) {
            Log.d("LyftParser", "None of the expected tokens found after fare.")
            return null
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
            Log.d("LyftParser", "Pickup info: '${pickupMatch.value}', time: $pickupTime, distance: $pickupDistance")
        } else {
            Log.d("LyftParser", "Pickup time/distance not found.")
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
            Log.d("LyftParser", "Pickup location: '$pickupLocation'")
        } else {
            Log.d("LyftParser", "Dropoff time/distance not found; cannot determine pickup location.")
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
            Log.d("LyftParser", "Dropoff info: '${dropoffMatch.value}', time: $tripTime, distance: $tripDistance")
        } else {
            Log.d("LyftParser", "Dropoff info not found.")
            return null
        }
        currentIndex = dropoffInfoEnd

        // Step 8: The remaining text (from currentIndex up to the action button) should include:
        // [Dropoff location][Rider's Name][Rider's Rating]
        // Find the action button ("Accept" or "Match") at the end.
        val actionRegex = Regex("(Accept|Match)$", RegexOption.IGNORE_CASE)
        val actionMatch = actionRegex.find(text)
        if (actionMatch == null) {
            Log.d("LyftParser", "Action button not found.")
            return null
        }
        val actionButton = actionMatch.groupValues[1]
        val remaining = text.substring(currentIndex, actionMatch.range.first).trim()
        Log.d("LyftParser", "Remaining text for dropoff location, rider's name and rating: '$remaining'")

        // Preprocess remaining text:
        // Insert a space before any uppercase letter that follows a lowercase letter,
        // and also before a digit if missing.
        val cleanRemaining = remaining
            .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            .replace(Regex("(?<=[A-Za-z])(?=\\d)"), " ")
        Log.d("LyftParser", "Cleaned remaining text: '$cleanRemaining'")

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
            Log.d("LyftParser", "Extracted dropoff location: '$dropoffLocation', Rider's Name: '$riderName', Rider's Rating: $riderRating")
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

        Log.d("LyftParser", "Parsed ride info: $rideInfo")
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
            Log.d("LyftParser", "Text does not start with 'DismissRide Finder'.")
            return null
        }

        // Extract Fare.
        val fareRegex = Regex("\\$(\\d+\\.\\d{2})")
        val fareMatch = fareRegex.find(text, currentIndex)
        val fare = fareMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        if (fareMatch != null) {
            currentIndex = fareMatch.range.last + 1
            Log.d("LyftParser", "Extracted fare (Match): $$fare")
        } else {
            Log.d("LyftParser", "Fare not found in Match format.")
            return null
        }

        // Skip rate info: "$25.40/hr est. rate for this ride"
        val rateRegex = Regex("\\$\\d+\\.\\d+\\/hr est\\. rate for this ride", RegexOption.IGNORE_CASE)
        val rateMatch = rateRegex.find(text, currentIndex)
        if (rateMatch != null) {
            currentIndex = rateMatch.range.last + 1
        } else {
            Log.d("LyftParser", "Rate info not found in Match format.")
            return null
        }

        // Skip "Map image with pickup and drop-off route"
        val mapToken = "Map image with pickup and drop-off route"
        val mapIndex = text.indexOf(mapToken, currentIndex)
        if (mapIndex != -1) {
            currentIndex = mapIndex + mapToken.length
        } else {
            Log.d("LyftParser", "Map token not found in Match format.")
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
            Log.d("LyftParser", "Pickup info (Match): '${pickupMatch.value}', time: $pickupTime, distance: $pickupDistance")
        } else {
            Log.d("LyftParser", "Pickup time/distance not found in Match format.")
            return null
        }
        currentIndex = pickupInfoEnd

        // Extract Pickup Location: text from currentIndex until the next time-distance pattern (dropoff info)
        val dropoffMatch = timeDistRegex.find(text, currentIndex)
        var pickupLocation = "N/A"
        if (dropoffMatch != null) {
            pickupLocation = text.substring(currentIndex, dropoffMatch.range.first).trim()
            currentIndex = dropoffMatch.range.first
            Log.d("LyftParser", "Pickup location (Match): '$pickupLocation'")
        } else {
            Log.d("LyftParser", "Dropoff time/distance not found in Match format; cannot determine pickup location.")
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
            Log.d("LyftParser", "Dropoff info (Match): '${dropoffMatch.value}', time: $tripTime, distance: $tripDistance")
        } else {
            Log.d("LyftParser", "Dropoff info not found in Match format.")
            return null
        }
        currentIndex = dropoffInfoEnd

        // Extract the Action Button: expected at the end ("Request match")
        val actionRegex = Regex("(Request match)$", RegexOption.IGNORE_CASE)
        val actionMatch = actionRegex.find(text)
        if (actionMatch == null) {
            Log.d("LyftParser", "Action button not found in Match format.")
            return null
        }
        val actionButton = actionMatch.groupValues[1]

        // The remaining text (from currentIndex to the action button) should include:
        // [Dropoff location][Rider's Name][Rider's Rating]
        val remaining = text.substring(currentIndex, actionMatch.range.first).trim()
        Log.d("LyftParser", "Remaining text (Match) for dropoff location, rider's name and rating: '$remaining'")
        val cleanRemaining = remaining
            .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            .replace(Regex("(?<=[A-Za-z])(?=\\d)"), " ")
        Log.d("LyftParser", "Cleaned remaining text (Match): '$cleanRemaining'")
        val remainingRegex = Regex("^(.*)\\s+([A-Za-z]+)\\s+(\\d+\\.\\d+)$")
        val remainingMatch = remainingRegex.find(cleanRemaining)
        var dropoffLocation = "N/A"
        var riderName = "N/A"
        var riderRating: Double? = null
        if (remainingMatch != null) {
            dropoffLocation = remainingMatch.groupValues[1].trim()
            riderName = remainingMatch.groupValues[2].trim()
            riderRating = remainingMatch.groupValues[3].toDoubleOrNull()
            Log.d("LyftParser", "Extracted (Match) dropoff location: '$dropoffLocation', Rider's Name: '$riderName', Rider's Rating: $riderRating")
        } else {
//            Log.d("LyftParser", "Failed to parse remaining text in Match format into dropoff location, rider's name, and rating.")
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
        Log.d("LyftParser", "Parsed ride info (Match format): $rideInfo")
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
        Log.d("LyftParser", sb.toString())
    }

    /**
     * Process the ride request using ACC text.
     * Updated to accept a Context so that both accessibility services and activities can call this method.
     */
    fun processLyftRideRequest(accText: String, context: Context) {

        // ----- NEW: Guard clause to ignore empty events -----
        if (accText.trim().isEmpty()) {
            Log.d("LyftParser", "ACC text is empty, ignoring event.")
            return
        }

        val preprocessedText = preprocessAccessibilityText(accText)
        val rideInfo = parse(preprocessedText)
        if (rideInfo != null) {
            // Update the last valid request time for auto-hide checks.
            lastValidRequestTime = System.currentTimeMillis()
            Log.d("LyftParser", "Processed ride info: $rideInfo")
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
            // Retrieve settings for calculations.
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val adjustedFare = rideInfo.fare ?: 0.0

            // ----- UPDATED CODE: Combining pickup and dropoff values -----
            val totalMiles = (rideInfo.pickupDistance ?: 0.0) + (rideInfo.tripDistance ?: 0.0)
            val totalMinutes = (rideInfo.pickupTime ?: 0.0) + (rideInfo.tripTime ?: 0.0)


            // Compute fare color.
            val computedFareColor = when {
                adjustedFare < prefs.getFloat(SettingsActivity.KEY_FARE_LOW, 5.0f) -> Color.RED
                adjustedFare < prefs.getFloat(SettingsActivity.KEY_FARE_HIGH, 10.0f) -> Color.YELLOW
                else -> Color.GREEN
            }

            // Compute price per mile and its color.
            val pricePerMile = if (totalMiles > 0) adjustedFare / totalMiles else 0.0
            val computedPMileColor = when {
                pricePerMile < prefs.getFloat(SettingsActivity.KEY_DECLINE_MILE, 0.75f) -> Color.RED
                pricePerMile < prefs.getFloat(SettingsActivity.KEY_ACCEPT_MILE, 1.0f) -> Color.YELLOW
                else -> Color.GREEN
            }

            // Compute price per hour and its color.
            val pricePerHour = if (totalMinutes > 0) adjustedFare / (totalMinutes / 60.0) else 0.0
            val computedPHourColor = when {
                pricePerHour < prefs.getFloat(SettingsActivity.KEY_DECLINE_HOUR, 20.0f) -> Color.RED
                pricePerHour < prefs.getFloat(SettingsActivity.KEY_ACCEPT_HOUR, 25.0f) -> Color.YELLOW
                else -> Color.GREEN
            }

            // Compute profit and its color.
            val drivingCost = prefs.getFloat(SettingsActivity.KEY_COST_DRIVING, 0.20f) * totalMiles
            val profit = adjustedFare - drivingCost
            val computedProfitColor = if (profit >= 0) Color.GREEN else Color.RED

            // Compute rating color.
            val currentRating = rideInfo.rating?.toFloat() ?: 0f
            val computedRatingColor = if (currentRating >= prefs.getFloat(SettingsActivity.KEY_RATING_THRESHOLD, 4.70f)) Color.GREEN else Color.RED

            // Update the overlay using the computed values.
            FloatingOverlayService.updateOverlay(
                rideType = rideInfo.rideType ?: "Undetected",
                isExclusive = rideInfo.isExclusive,
                fare = "$${String.format("%.2f", adjustedFare)}",
                fareColor = computedFareColor,
                pMile = "$${String.format("%.2f", if (totalMiles > 0) adjustedFare / totalMiles else 0.0)}",
                pMileColor = computedPMileColor,
                pHour = "$${String.format("%.2f", if (totalMinutes > 0) adjustedFare / (totalMinutes / 60.0) else 0.0)}",
                pHourColor = computedPHourColor,
                miles = String.format("%.1f", totalMiles),
                minutes = String.format("%.1f", totalMinutes),
                profit = "$${String.format("%.2f", profit)}",
                profitColor = computedProfitColor,
                rating = rideInfo.rating?.toString() ?: "N/A",
                stops = rideInfo.stops ?: ""
            )
        } else {
//            Log.d("LyftParser", "Failed to parse ride info from ACC text: $accText")
            // Schedule the overlay to hide after 15 seconds.
            Handler(Looper.getMainLooper()).postDelayed({
                FloatingOverlayService.hideOverlay()
            }, 6000)
        }
    }
    // ----- UPDATED FUNCTION: Bring Lyft App to Foreground with explicit intent fallback -----
    private fun bringLyftAppToForeground(context: Context) {
        val currentTime = System.currentTimeMillis()
        // Throttle to only launch once every 2 seconds.
        if (currentTime - lastLyftForegroundRequestTime < 2000) {
//            Log.d("LyftParser", "Foreground launch throttled. Not launching Lyft app again.")
            return
        }
        lastLyftForegroundRequestTime = currentTime

        val packageManager = context.packageManager
        // Try to get the default launch intent.
        var intent = packageManager.getLaunchIntentForPackage("com.lyft.android.driver")
        // If not found, explicitly create an intent using Lyft’s known main activity.
        if (intent == null) {
            intent = Intent()
            intent.component = ComponentName("com.lyft.android.driver", "com.lyft.android.driver.app.ui.DriverMainActivity")
        }
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
//            Log.d("LyftParser", "Brought Lyft app to foreground.")
        } else {
            Log.e("LyftParser", "Lyft app not found on device.")
        }
    }




    // Add this function at the end of the LyftParser object for debugging purposes.
//    fun debugTestParse() {
//        // Define a hardcoded ACC text string for testing.
//        val testAccText = "Reject ride\$20.15Pay includes pickup\$21.98/hr est. rate for this ride11 min • 5.5 miUmber & Unger, Ft. Myers44 min • 22 miChar Ann & Juanita, Ft. MyersDulce5.0Accept"
//        // Log the test ACC text so you know what input is being parsed.
//        Log.d("LyftParserDebug", "Test ACC text: $testAccText")
//        // Call the parse function on the test string.
//        val rideInfo = parse(testAccText)
//        // Log the parsed ride info result.
//        if (rideInfo != null) {
//            Log.d("LyftParserDebug", "Debug parsed ride info: $rideInfo")
//            // Optionally, log detailed info:
//            logLyftRideRequest(
//                rideInfo,
//                pickupLocation = rideInfo.pickupLocation ?: "",
//                dropoffLocation = rideInfo.tripLocation ?: "",
//                actionButton = rideInfo.actionButton ?: "",
//                verified = false
//            )
//        } else {
//            Log.d("LyftParserDebug", "Debug parsing failed for test ACC text.")
//        }
//    }

}

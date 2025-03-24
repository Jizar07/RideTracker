package com.stoffeltech.ridetracker.uber

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
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
import com.stoffeltech.ridetracker.utils.RideScoreSettings

import kotlin.math.abs

// Define Uber-specific keys internally.
object UberKeys {
    const val KEY_ACCEPT_MILE = "pref_accept_mile"       // default: 1.0
    const val KEY_DECLINE_MILE = "pref_decline_mile"       // default: 0.75
    const val KEY_ACCEPT_HOUR = "pref_accept_hour"         // default: 25.0
    const val KEY_DECLINE_HOUR = "pref_decline_hour"         // default: 20.0
    const val KEY_FARE_LOW = "pref_fare_low"               // default: 5.0
    const val KEY_FARE_HIGH = "pref_fare_high"             // default: 10.0
    const val KEY_BONUS_RIDE = "pref_bonus_ride"
    const val KEY_RATING_THRESHOLD = "pref_rating_threshold" // default: 4.70
}

// Uber-specific duplicate tracking variables
private var lastDeliveryRawFare: Double? = null
private var lastDeliveryAdjustedFare: Double? = null
private var lastDeliveryRequestHash: Int? = null

// New fingerprint variable for Uber ride requests to avoid duplicates.
private var lastUberRequestFingerprint: String? = null
private var lastFingerprintTime: Long = 0
private var lastUberFingerprintTime: Long = 0

// ----- ADD DUPLICATE INVALID REQUEST TRACKING -----
private var lastInvalidRequestFingerprint: Int? = null
private var lastInvalidRequestTime: Long = 0
// ----- Global throttle for invalid ride request logs -----
private var lastGlobalInvalidRequestTime: Long = 0

object UberParser {
    // ---------------- LAST VALID REQUEST TIMESTAMP -----------------
    var lastValidRequestTime: Long = 0

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

    /**
     * Original function for minimal 'l' => '1' checks (we keep it as is).
     */
    private fun normalizeOcrText(text: String): String {
        val sb = StringBuilder()
        for (i in text.indices) {
            val c = text[i]
            if ((c == 'l' || c == 'L') &&
                i < text.lastIndex && text[i + 1].isDigit()
            ) {
                sb.append('1')
            } else if ((c == 'l' || c == 'L') &&
                i > 0 && text[i - 1].isDigit()
            ) {
                sb.append('1')
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    // Helper function to bring Uber Driver app to the foreground.
    private fun bringUberAppToForeground(context: Context) {
        // Retrieve the launch intent for the Uber Driver package.
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage("com.ubercab.driver")
        if (intent != null) {
            // Add flag to start a new task so it works from non-Activity context.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)  // Launches the app.
        } else {
//            FileLogger.log("UberParser", "Uber Driver app not found on device.")
        }
    }

    /**
     * Replaces all 'l'/'L' with '1' except if the entire substring is exactly "total".
     */
    private fun replaceLsInField(fieldValue: String?): String {
        if (fieldValue == null) return ""
        if (fieldValue.equals("total", ignoreCase = true)) {
            return fieldValue // skip replacing in "total"
        }
        var result = fieldValue
        // Check if the value ends with ".l" or ".L" and replace it with ".1"
        if (result.matches(Regex(".*\\.[lL]$"))) {
            result = result.replace(Regex("(?<=\\.)[lL]$"), "1")
        }
        // Then, replace any remaining occurrences of 'l' or 'L' with "1"
        return result.replace(Regex("[lL]"), "1")
    }

    fun extractRideRequestText(fullText: String): String {
        val match = rideTypeRegex.find(fullText)
        return if (match != null) fullText.substring(match.range.first) else fullText
    }

    /**
     * Preprocess text from accessibility events (Android <= 13).
     */
    private fun preprocessAccessibilityText(text: String): String {
        var result = text
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

    /**
     * Preprocess text from OCR (Android 14+).
     */
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
        sb.appendLine("1) Ride Type: ${rideInfo.rideType ?: "Undetected"}")

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
         FileLogger.log("UberRideRequest", sb.toString())
    }

    fun getRideTypes(): List<String> = rideTypes

    // ---------------- FOCUSED CHANGE: REPLACE L in fare/time/distance STRINGS ----------------
    fun parse(cleanedText: String): RideInfo? {

        // Create a list to collect anomaly messages
        val anomalyMessages = mutableListOf<String>()  // ----- ANOMALY: Collect messages -----

        // Basic normalizing
        val normalizedInput = normalizeOcrText(cleanedText)
        val text = normalizeOcrText(cleanedText)

//        var rideType = "Undetected"
        var rideSubtype: String? = null

        val rideTypeMatch = rideTypeRegex.find(text)
        var rideType = rideTypeMatch?.value ?: "Undetected"

        /* Checks if the ride type contains "Exclusive" (case-insensitive).
         * We'll store it in rideSubtype, but also remove it from the main rideType text
         * so it doesn't break "Uber Black", "UberX", etc.
         */
        // Determine if the text contains "Exclusive" anywhere.
        val isExclusive = text.contains("Exclusive", ignoreCase = true)
        // If found, remove the word "Exclusive" from the rideType string.
        if (isExclusive) {
            rideType = rideType.replace(Regex("Exclusive\\s*", RegexOption.IGNORE_CASE), "").trim()
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
        // Updated regex to allow optional decimal part (e.g., "$1748" or "$17.48").
        val fareRegex = Regex("\\$(\\d+)(?:[.,](\\d{2}))?")

        val fareMatch = fareRegex.find(text)

        // Replace L in those groups except if equals "total"
        var rawFareWhole = replaceLsInField(fareMatch?.groupValues?.get(1))
        var rawFareDecimal = replaceLsInField(fareMatch?.groupValues?.getOrNull(2))

        // If the optional decimal is null or blank, rawFareDecimal won't be used.
        var fare: Double? = null

        if (!rawFareWhole.isNullOrBlank()) {
            if (!rawFareDecimal.isNullOrBlank()) {
                // We have something like "$1748.99"
                fare = (rawFareWhole + "." + rawFareDecimal).toDoubleOrNull()
            } else {
                // We have something like "$1748" (missing decimal).
                // Optionally, correct if it looks like 1748 -> 17.48
                if (rawFareWhole.length > 2) {
                    // e.g. "1748" => "17.48"
                    val correctedWhole = rawFareWhole.dropLast(2)
                    val correctedDecimal = rawFareWhole.takeLast(2)
                    anomalyMessages.add("Correcting missing decimal: $$rawFareWhole => \$$correctedWhole.$correctedDecimal") // ----- ANOMALY: Correction logged once -----
                    rawFareWhole = correctedWhole
                    rawFareDecimal = correctedDecimal
                    fare = (rawFareWhole + "." + rawFareDecimal).toDoubleOrNull()
                } else {
                    // If it's too short to correct, treat it as is.
                    fare = rawFareWhole.toDoubleOrNull()
                }
            }
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
            val totalPattern = Regex("([lL\\d]+)\\s*min[s]?\\s*\\(([lL\\d]+(?:\\.[lL\\d]+)?)\\s*mi\\)\\s*total", RegexOption.IGNORE_CASE)
            val totalMatch = totalPattern.find(text)

            // Replace L in these fields except if exactly "total"
            val rawPickupTime = replaceLsInField(totalMatch?.groupValues?.get(1))
            val rawPickupDistance = replaceLsInField(totalMatch?.groupValues?.get(2))

            pickupTime = rawPickupTime.toDoubleOrNull()
            pickupDistance = rawPickupDistance.toDoubleOrNull()

            // ----- ANOMALY DETECTION for Delivery Time & Distance -----
            if (pickupTime != null && pickupTime >= 60) {
                anomalyMessages.add("Unrealistic delivery pickupTime: $pickupTime min. Adjusting by dividing by 10.") // ----- ANOMALY: Delivery PickupTime -----
                pickupTime = pickupTime / 10.0
            }
            if (pickupDistance != null && pickupDistance >= 20) {
                anomalyMessages.add("Unrealistic delivery pickupDistance: $pickupDistance mi. Adjusting by dividing by 10.") // ----- ANOMALY: Delivery PickupDistance -----
                pickupDistance = pickupDistance / 10.0
            }
            tripTime = null
            tripDistance = null

            val addressPattern = Regex("total\\s*(.*?)\\s*(Accept|Match)", RegexOption.IGNORE_CASE)
            pickupLocation = addressPattern.find(text)?.groupValues?.get(1)?.trim() ?: "N/A"
            dropoffLocation = pickupLocation
        } else {

            // For pickup location: capture all lines between the line that contains "away" and the line that contains "trip"
            try {
                val lines = text.lines().map { it.trim() }
                val awayIndex = lines.indexOfFirst { it.contains("away", ignoreCase = true) }
                // Find the first occurrence of a line containing "trip" after the "away" line.
                val tripIndex = lines.indexOfFirst { it.contains("trip", ignoreCase = true) && lines.indexOf(it) > awayIndex }
                if (awayIndex != -1 && tripIndex != -1 && tripIndex > awayIndex) {
                    // Extract lines between the "away" and "trip" markers.
                    val candidateLines = lines.subList(awayIndex + 1, tripIndex)
                        .filter { it.isNotEmpty() && it != "X" }
                    pickupLocation = if (candidateLines.isNotEmpty()) candidateLines.joinToString(" ") else "N/A"
                } else {
                    pickupLocation = "N/A"
                }
            } catch (e: Exception) {
                FileLogger.log("UberParser", "Error extracting pickup location: ${e.message}")
                pickupLocation = "N/A"
            }


            // For dropoff location: capture all lines between "trip" and "Accept/Match"
            try {
                val lines = text.lines().map { it.trim() }
                val tripIndex = lines.indexOfFirst { it.contains("trip", ignoreCase = true) }
                // Find the first occurrence of "Accept" or "Match" after the trip line
                val actionIndex = lines.indexOfFirst {
                    (it.equals("Accept", ignoreCase = true) || it.equals("Match", ignoreCase = true))
                            && lines.indexOf(it) > tripIndex
                }
                if (tripIndex != -1 && actionIndex != -1 && actionIndex > tripIndex) {
                    // Extract lines between tripIndex and actionIndex
                    val candidateLines = lines.subList(tripIndex + 1, actionIndex)
                        .filter { it.isNotEmpty() && !it.equals("X", ignoreCase = true) }
                    dropoffLocation = if (candidateLines.isNotEmpty()) candidateLines.joinToString(" ") else "N/A"
                } else {
                    dropoffLocation = "N/A"
                }
            } catch (e: Exception) {
                FileLogger.log("UberParser", "Error extracting dropoff location: ${e.message}")
                dropoffLocation = "N/A"
            }


            val pickupInfoRegex = Regex("([lL\\d]+)\\s*mins?\\s*\\(([lL\\d]+(?:\\.[lL\\d]+)?)\\s*mi\\)\\s*away", RegexOption.IGNORE_CASE)
            val pickupMatch = pickupInfoRegex.find(text)

            val rawPickupTime = replaceLsInField(pickupMatch?.groupValues?.get(1))
            val rawPickupDistance = replaceLsInField(pickupMatch?.groupValues?.get(2))
            pickupTime = rawPickupTime.toDoubleOrNull()
            pickupDistance = rawPickupDistance.toDoubleOrNull()

            // ----- ANOMALY DETECTION for Pickup Time & Distance -----
            if (pickupTime != null && pickupTime >= 60) {
                anomalyMessages.add("Unrealistic pickupTime detected: $pickupTime min. Adjusting by dividing by 10.") // ----- ANOMALY: PickupTime -----
                pickupTime = pickupTime / 10.0
            }
            if (pickupDistance != null && pickupDistance >= 20) {
                anomalyMessages.add("Unrealistic pickupDistance detected: $pickupDistance mi. Adjusting by dividing by 10.") // ----- ANOMALY: PickupDistance -----
                pickupDistance = pickupDistance / 10.0
            }

            val tripInfoRegex = Regex("([lL\\d]+)\\s*mins?\\s*\\(([lL\\d]+(?:\\.[lL\\d]+)?)\\s*mi\\)\\s*trip", RegexOption.IGNORE_CASE)
            val tripMatch = tripInfoRegex.find(text)
            val rawTripTime = replaceLsInField(tripMatch?.groupValues?.get(1))
            val rawTripDistance = replaceLsInField(tripMatch?.groupValues?.get(2))
            tripTime = rawTripTime.toDoubleOrNull()
            tripDistance = rawTripDistance.toDoubleOrNull()

            // ----- ANOMALY DETECTION for Trip Time & Distance using average speed -----
            // Compute the average speed in miles per minute.
            if (tripTime != null && tripDistance != null && tripTime > 0) {
                val speed = tripDistance / tripTime
                // For typical rides, an average speed of 0.5 to 1.5 mi/min (30-90 mph) is realistic.
                // If the computed speed is way too high (say > 2.5 mi/min), assume a misread decimal and adjust.
                if (speed > 2) {
                    anomalyMessages.add("Unrealistic trip speed detected: $speed mi/min (tripDistance: $tripDistance, tripTime: $tripTime). Adjusting tripDistance by dividing by 10.") // ----- ANOMALY: TripSpeed -----
                    tripDistance = tripDistance / 10.0
                }
            }
        }

        // --- Extract Action Button ---
        val actionButtonRegex = Regex("(?i)(Accept|Match)\\s*$")
        val actionButton = actionButtonRegex.find(text)?.groupValues?.get(1) ?: "N/A"

        // --- Combine extra fields (for stops) ---
        val stops = if (text.contains("Multiple stops", ignoreCase = true)) "Multiple stops" else ""
        // Extra info is available if needed: "Verified: $verified, Exclusive: $exclusive"

        // ----- Updated Validation Block in parse() -----
        if (fare == null || pickupTime == null ||
            (!rideType.contains("Delivery", ignoreCase = true) && tripTime == null)
        ) {
            val missingFields = mutableListOf<String>()
            if (fare == null) missingFields.add("fare")
            if (pickupTime == null) missingFields.add("pickupTime")
            if (!rideType.contains("Delivery", ignoreCase = true) && tripTime == null) missingFields.add("tripTime")

            // Build the error message including missing fields, the raw text, and any anomaly messages
//            val errorMsg = "Invalid ride request. Missing fields: ${missingFields.joinToString(", ")}. Raw text: $text. " +
//                    if (anomalyMessages.isNotEmpty()) "Anomalies: ${anomalyMessages.joinToString("; ")}" else ""

            val currentTime = System.currentTimeMillis()
            // Throttle logging: if an invalid log has been produced within the last 30 seconds, skip logging.
            if (currentTime - lastGlobalInvalidRequestTime < 30000) {
                return null
            }
            lastGlobalInvalidRequestTime = currentTime
//            FileLogger.log("UberParser", errorMsg)
            return null
        }


        // If anomalies were detected in a valid ride, log them once.
        if (anomalyMessages.isNotEmpty()) {
            FileLogger.log("UberParser", "Ride request anomalies: " + anomalyMessages.joinToString("; "))
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
            isExclusive = isExclusive,
            bonuses = bonuses
        )
    }

    /**
     * Processes an Uber ride request.
     *
     * Blocks:
     * 1) Validate and pre-process raw text.
     * 2) Parse ride information.
     * 3) Avoid duplicate requests.
     * 4) Load thresholds exclusively from Uber preferences (except cost-to-drive from main settings).
     * 5) Compute metrics and update the overlay.
     */
    @SuppressLint("DefaultLocale")
    suspend fun processUberRideRequest(rawText: String, context: Context) {
        // Normalize and trim the raw OCR text
        val trimmedText = normalizeOcrText(rawText.trim())
//        FileLogger.log("UberParser", "Normalized text in processUberRideRequest: $trimmedText")

        // Early return if text does not contain required keywords.
        // ----- ADDED LOG: Log even if the required keywords are missing -----
        if (!trimmedText.contains("Accept", ignoreCase = true) && !trimmedText.contains("Match", ignoreCase = true)) {
//            FileLogger.log("UberParser", "Request rejected: missing Accept/Match keywords. Raw text: $trimmedText")
            return
        }

        // Choose the proper preprocessor based on OS version (if needed)
        val preparedText = if (android.os.Build.VERSION.SDK_INT < 34) {
//            FileLogger.log("UberParser", "Using ACC processing (Android <= 13)")
            preprocessAccessibilityText(trimmedText)
        } else {
//            FileLogger.log("UberParser", "Using OCR processing (Android 14+)")
            prepareOcrTextForParsing(trimmedText)
        }

//        FileLogger.log("UberParser", "Prepared text for parsing: $preparedText")

        // Parse the prepared text to extract ride information
        val rideInfo = parse(preparedText)
        if (rideInfo == null) {
//            FileLogger.log("UberParser", "Parsed ride info is null. Raw text: $preparedText")
            return
        }


        // ---------------- UPDATE LAST VALID REQUEST TIME -----------------
        // We update the timestamp to the current time every time a valid ride request is received.
        lastValidRequestTime = System.currentTimeMillis()

        // --- Insert the debug log here ---
        FileLogger.log("UberParser", """
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
//        // Start the headless WebView service if it's not already running.
//        val serviceIntent = Intent(context, UberEstimateService::class.java)
//        context.startService(serviceIntent)
//        Log.d("UberParser", "UberEstimateService started.")
//        // Now call the injection logic:
//        UberEstimateService.instance?.injectFormData(rideInfo.pickupLocation ?: "", rideInfo.tripLocation ?: "")



        // Extract ride details for calculations
        val fareVal = rideInfo.fare ?: 0.0
        val pickupDistanceVal = rideInfo.pickupDistance ?: 0.0
        val tripDistanceVal = rideInfo.tripDistance ?: 0.0
        val totalMiles = pickupDistanceVal + tripDistanceVal
        val pickupTimeVal = rideInfo.pickupTime ?: 0.0
        val tripTimeVal = rideInfo.tripTime ?: 0.0
        val totalMinutes = pickupTimeVal + tripTimeVal

        // Load Uber-specific thresholds from "uber_prefs".
        val uberPrefs = context.getSharedPreferences("uber_prefs", Context.MODE_PRIVATE)
        // For cost to drive, keep reading from main settings.
        val mainPrefs = PreferenceManager.getDefaultSharedPreferences(context)

        val bonus = uberPrefs.getFloat(UberKeys.KEY_BONUS_RIDE, 0.0f).toDouble()
        val fareLow = uberPrefs.getFloat(UberKeys.KEY_FARE_LOW, 5.0f)
        val fareHigh = uberPrefs.getFloat(UberKeys.KEY_FARE_HIGH, 10.0f)
        val acceptMile = uberPrefs.getFloat(UberKeys.KEY_ACCEPT_MILE, 1.0f)
        val declineMile = uberPrefs.getFloat(UberKeys.KEY_DECLINE_MILE, 0.75f)
        val acceptHour = uberPrefs.getFloat(UberKeys.KEY_ACCEPT_HOUR, 25.0f)
        val declineHour = uberPrefs.getFloat(UberKeys.KEY_DECLINE_HOUR, 20.0f)
        val ratingThreshold = uberPrefs.getFloat(UberKeys.KEY_RATING_THRESHOLD, 4.70f)

        // Adjust fare with bonus.
        var adjustedFare = fareVal + bonus

        // Anomaly Detection: Check if the computed price per mile is unrealistically high.
        // If totalMiles > 0, calculate price per mile. If price per mile exceeds $50, assume OCR misinterpreted the decimal point.
        if (totalMiles > 0) {
            val pricePerMile = adjustedFare / totalMiles
            if (pricePerMile > 10) {
                FileLogger.log("UberParser", "Unrealistic fare detected: $$adjustedFare for $totalMiles miles (price per mile: $$pricePerMile). Adjusting fare by dividing by 100.")
                adjustedFare /= 100.0
            }
        }

        val fingerprint = "$adjustedFare-$totalMiles-$totalMinutes"
        val currentTime = System.currentTimeMillis()

        // If same fingerprint arrives within 30 seconds, skip
        if (fingerprint == lastUberRequestFingerprint &&
            (currentTime - lastUberFingerprintTime) < 30_000
        ) {
            FileLogger.log("UberParser", "Skipping duplicate Uber request: $fingerprint")
            return
        }

        // Update the stored fingerprint + time
        lastUberRequestFingerprint = fingerprint
        lastUberFingerprintTime = currentTime

        com.stoffeltech.ridetracker.services.HistoryManager.addRideRequest(rideInfo, context)

        // Bring Uber Driver app to the foreground when a new ride request is processed.
        bringUberAppToForeground(context)

        // --- Compute colors for each metric (new additions) ---
        // Compute fare color
        val computedFareColor = when {
            adjustedFare < fareLow -> Color.RED
            adjustedFare < fareHigh -> Color.YELLOW
            else -> Color.GREEN
        }

        // Compute price per mile and its color
        val pricePerMile = if (totalMiles > 0) adjustedFare / totalMiles else 0.0
        val computedPMileColor = when {
            pricePerMile < declineMile -> Color.RED
            pricePerMile < acceptMile -> Color.YELLOW
            else -> Color.GREEN
        }

        // Compute price per hour and its color
        val pricePerHour = if (totalMinutes > 0) adjustedFare / (totalMinutes / 60.0) else 0.0
        val computedPHourColor = when {
            pricePerHour < declineHour -> Color.RED
            pricePerHour < acceptHour -> Color.YELLOW
            else -> Color.GREEN
        }
        val currentRating = rideInfo.rating?.toFloat() ?: 0f
        val computedRatingColor = if (currentRating >= ratingThreshold) Color.GREEN else Color.RED

        val drivingCost = mainPrefs.getFloat(SettingsActivity.KEY_COST_DRIVING, 0.20f) * totalMiles
        val profit = adjustedFare - drivingCost
        val computedProfitColor = if (profit >= 0) Color.GREEN else Color.RED

        // Update overlay.
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
            ratingColor = computedRatingColor,
            stops = rideInfo.stops ?: ""
        )
        // ----- NEW CODE INSERTION: Ride Score Calculation -----

// Create a RideScoreSettings instance using Uber preference thresholds.
// Adjust the scale factors and weights as desired.
        val rideScoreSettings = com.stoffeltech.ridetracker.utils.RideScoreSettings(
            idealPMile = acceptMile,       // Ideal price per mile (e.g., 1.0)
            idealPHour = acceptHour,       // Ideal price per hour (e.g., 25.0)
            idealFare = fareHigh,          // Ideal fare price (e.g., 10.0)
            scaleFactorPMile = 50f,        // Scale factor for overshoot in p/mile
            scaleFactorPHour = 2f,         // Scale factor for overshoot in p/hour
            scaleFactorFare = 10f,         // Scale factor for overshoot in fare price
            weightPMile = 0.33f,           // Weight for price per mile (must sum to 1.0 with others)
            weightPHour = 0.33f,           // Weight for price per hour
            weightFare = 0.34f             // Weight for fare price
        )

        // Call the new updateScore companion function to update the ride score on the overlay.
        com.stoffeltech.ridetracker.services.FloatingOverlayService.updateScore(
            actualPMile = pricePerMile.toFloat(),   // Actual price per mile computed earlier.
            actualPHour = pricePerHour.toFloat(),    // Actual price per hour computed earlier.
            actualFare = adjustedFare.toFloat(),     // Actual fare price after adjustments.
            settings = rideScoreSettings
        )

        Handler(Looper.getMainLooper()).postDelayed({
            FloatingOverlayService.hideOverlay()
        }, 6000)
    }


    /**
     * Processes Delivery requests separately.
     * Loads Uber thresholds from "uber_prefs" and keeps cost-to-drive from main settings.
     */
    @SuppressLint("DefaultLocale")
    private fun processDeliveryRequest(rideInfo: RideInfo, bonus: Double, prefsFromUber: android.content.SharedPreferences, mainPrefs: android.content.SharedPreferences) {
        val adjustedFare = rideInfo.fare?.plus(bonus) ?: 0.0
        // For deliveries, we use pickupTime and pickupDistance as total values.
        var totalMinutes = rideInfo.pickupTime ?: 0.0
        var totalMiles = rideInfo.pickupDistance ?: 0.0

        // ----- ANOMALY DETECTION for Delivery Pickup Time & Distance -----
        if (totalMinutes >= 60) {
            FileLogger.log("UberParser", "Unrealistic delivery pickupTime: $totalMinutes min. Adjusting by dividing by 10.")
            totalMinutes /= 10.0
        }
        if (totalMiles >= 20) {
            FileLogger.log("UberParser", "Unrealistic delivery pickupDistance: $totalMiles mi. Adjusting by dividing by 10.")
            totalMiles /= 10.0
        }

        // Calculate per-mile and per-hour metrics.
        val pricePerMile = if (totalMiles > 0) adjustedFare / totalMiles else 0.0
        val pricePerHour = if (totalMinutes > 0) adjustedFare / (totalMinutes / 60.0) else 0.0

        val drivingCost = mainPrefs.getFloat(SettingsActivity.KEY_COST_DRIVING, 0.20f) * totalMiles
        val profit = adjustedFare - drivingCost

        // Determine colors based on thresholds from user preferences.
        val pmileColor = when {
            pricePerMile < prefsFromUber.getFloat(UberKeys.KEY_DECLINE_MILE, 0.75f) -> Color.RED
            pricePerMile < prefsFromUber.getFloat(UberKeys.KEY_ACCEPT_MILE, 1.0f) -> Color.YELLOW
            else -> Color.GREEN
        }
        val phourColor = when {
            pricePerHour < prefsFromUber.getFloat(UberKeys.KEY_DECLINE_HOUR, 20.0f) -> Color.RED
            pricePerHour < prefsFromUber.getFloat(UberKeys.KEY_ACCEPT_HOUR, 25.0f) -> Color.YELLOW
            else -> Color.GREEN
        }
        val fareColor = when {
            adjustedFare < prefsFromUber.getFloat(UberKeys.KEY_FARE_LOW, 5.0f) -> Color.RED
            adjustedFare < prefsFromUber.getFloat(UberKeys.KEY_FARE_HIGH, 10.0f) -> Color.YELLOW
            else -> Color.GREEN
        }
        val profitColor = if (profit >= 0) Color.GREEN else Color.RED

    // For deliveries, rating isn't applicable; pass a default color.
    val defaultRatingColor = Color.GRAY

    FloatingOverlayService.updateOverlay(
        rideType = rideInfo.rideType ?: "Unknown",
        isExclusive = rideInfo.isExclusive,
        fare = "$${String.format("%.2f", adjustedFare)}",
        fareColor = fareColor,
        pMile = "$${String.format("%.2f", if (totalMiles > 0) adjustedFare / totalMiles else 0.0)}",
        pMileColor = pmileColor,
        pHour = "$${String.format("%.2f", if (totalMinutes > 0) adjustedFare / (totalMinutes / 60.0) else 0.0)}",
        pHourColor = phourColor,
        miles = String.format("%.1f", totalMiles),
        minutes = String.format("%.1f", totalMinutes),
        profit = "$${String.format("%.2f", profit)}",
        profitColor = profitColor,
        rating = rideInfo.rating?.toString() ?: "N/A",
        ratingColor = defaultRatingColor,  // Added parameter for rating color.
        stops = rideInfo.stops ?: ""
    )
        // ----- NEW CODE INSERTION for Delivery: Ride Score Calculation -----

// Create RideScoreSettings instance for deliveries.
// You may adjust these ideal values, scale factors, and weights to suit delivery requests.
        val rideScoreSettings = com.stoffeltech.ridetracker.utils.RideScoreSettings(
            idealPMile = prefsFromUber.getFloat(UberKeys.KEY_ACCEPT_MILE, 1.0f),  // Example: 1.0
            idealPHour = prefsFromUber.getFloat(UberKeys.KEY_ACCEPT_HOUR, 25.0f),  // Example: 25.0
            idealFare = prefsFromUber.getFloat(UberKeys.KEY_FARE_HIGH, 10.0f),       // Example: 10.0
            scaleFactorPMile = 50f,        // Scale factor for p/mile overshoot
            scaleFactorPHour = 2f,         // Scale factor for p/hour overshoot
            scaleFactorFare = 10f,         // Scale factor for fare overshoot
            weightPMile = 0.33f,           // Weight for price per mile
            weightPHour = 0.33f,           // Weight for price per hour
            weightFare = 0.34f             // Weight for fare price
        )

//// Calculate actual price per mile and per hour for delivery.
//        val pricePerMile = if (totalMiles > 0) adjustedFare / totalMiles else 0.0
//        val pricePerHour = if (totalMinutes > 0) adjustedFare / (totalMinutes / 60.0) else 0.0

// Update the overlay with the ride score for delivery.
        com.stoffeltech.ridetracker.services.FloatingOverlayService.updateScore(
            actualPMile = pricePerMile.toFloat(),
            actualPHour = pricePerHour.toFloat(),
            actualFare = adjustedFare.toFloat(),
            settings = rideScoreSettings
        )

        Handler(Looper.getMainLooper()).postDelayed({
        FloatingOverlayService.hideOverlay()
    }, 6000)
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

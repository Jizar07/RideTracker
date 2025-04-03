package com.stoffeltech.ridetracker.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.stoffeltech.ridetracker.lyft.LyftParser
import com.stoffeltech.ridetracker.MediaProjectionLifecycleManager
import com.stoffeltech.ridetracker.uber.UberParser
import com.stoffeltech.ridetracker.utils.FileLogger
import com.stoffeltech.ridetracker.utils.RevenueTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import com.stoffeltech.ridetracker.services.HistoryManager
import com.stoffeltech.ridetracker.services.RideInfo

/**
 * AccessibilityService implementation that provides basic permissions and text extraction.
 * It extracts detected text from accessibility events and delegates Uber-related processing
 * to UberParser when the event comes from the Uber app.
 *
 * This updated version specifically looks for the earnings update string. It expects the
 * daily earnings to be found as a dollar amount following "TODAY" (e.g., "$0.00TODAY").
 */
class AccessibilityService : AccessibilityService() {

    // Basic CoroutineScope for handling background tasks.
    val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var ocrCaptureInProgress = false
    private var ocrJob: Job? = null

    companion object {
        // Stores the last extracted daily earnings value from the Uber screen.
        private var lastExtractedDailyEarnings: Double = 0.0
        // Stores the date (formatted as "yyyyMMdd") of the last extraction.
        private var lastExtractionDate: String = ""

        // ----- NEW: Debounce variable for on-demand OCR capture -----
        private var lastOcrCaptureTime: Long = 0L
        // Define a debounce threshold (e.g., 10 seconds)
        private const val OCR_CAPTURE_DEBOUNCE_MS = 2000L
    }

    // ---------------- NEW: PENDING REQUEST VARIABLES ----------------
    // Temporarily holds a pending Uber ride request.
    private var pendingUberRideRequest: RideInfo? = null
    // Job that was previously used to trigger a timeout (decline), now unused.
    private var pendingRequestTimeoutJob: Job? = null
    // Constant for the pending request timeout duration (10 seconds) – not used for auto-decline.
    private val PENDING_REQUEST_TIMEOUT_MS = 10000L

    // Helper function to extract the rider's name from the detected text.
    // It does the following:
    // 1. Finds the text after "Picking up".
    // 2. Splits the remainder by whitespace.
    // 3. If the first token is too long, attempts to split it at a lowercase-to-uppercase transition.
    // 4. If a second token is present and short, combines it with the first token.
    private fun extractRiderName(detectedText: String): String {
        val index = detectedText.indexOf("Picking up", ignoreCase = true)
        if (index == -1) return "Unknown"
        val remaining = detectedText.substring(index + "Picking up".length).trim()
        if (remaining.isEmpty()) return "Unknown"

        // Split the remaining text by whitespace.
        val parts = remaining.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "Unknown"

        // Process the first token.
        var firstToken = parts[0]
        // If the first token is long, attempt to insert a space at a lowercase-to-uppercase boundary.
        if (firstToken.length > 6) {
            val splitToken = firstToken.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").split(" ")
            if (splitToken.isNotEmpty()) {
                firstToken = splitToken.first()
            }
        }

        // If there's a second token that is reasonably short, combine it with the first token.
        return if (parts.size >= 2 && parts[1].length <= 8) {
            "$firstToken ${parts[1]}"
        } else {
            firstToken
        }
    }


    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // -------------------- NULL CHECK --------------------
        event?.let {
            // -------------------- EXTRACT PACKAGE NAME --------------------
            val packageName = event.packageName?.toString() ?: ""

            // -------------------- EXTRACT VISIBLE TEXT --------------------
            val detectedText = extractTextFromNode(event.source).ifBlank { event.text.joinToString(" ") }

            // --- ADDED LOG: Print the extracted text for debugging ---
//                        FileLogger.log("AccessibilityService", "Extracted text from event: $detectedText")   // ------------------------------------------------------------------ SHOW LOG FROM ACC EXTRACTED TEXT

            // ---------------- SPECIFIC EARNINGS EXTRACTION -----------------
            // Look for the specific earnings string following "TODAY"
            // New regex to capture the dollar amount immediately before "TODAY"
            // This will look for a "$", capture one or more digits, a period, and exactly two digits, then "TODAY".
            // Existing extraction block in onAccessibilityEvent:
            val earningsRegex = Regex("\\$(\\d+\\.\\d{2})TODAY")
            val progressRegex = Regex("SEE PROGRESS\\$(\\d+\\.\\d{2})")
            // Try to match the "TODAY" pattern first, then "SEE PROGRESS" if that fails.
            val earningsMatch = earningsRegex.find(detectedText) ?: progressRegex.find(detectedText)
            if (earningsMatch != null) {
                // Both regexes capture the amount in group 1.
                val earningsStr = earningsMatch.groupValues[1]
                val currentDailyEarnings = earningsStr.toDoubleOrNull() ?: 0.0
                FileLogger.log("AccessibilityService", "Extracted daily earnings: $$currentDailyEarnings")

                // ----- NEW: Check for new day by comparing formatted dates -----
                val currentDate = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
                if (currentDate != lastExtractionDate) {
                    FileLogger.log("AccessibilityService", "New day detected. Resetting last extracted earnings.")
                    lastExtractedDailyEarnings = 0.0
                    lastExtractionDate = currentDate
                }

                // Instead of adding a delta, update Uber's revenue to the current value.
                if (currentDailyEarnings != lastExtractedDailyEarnings) {
                    FileLogger.log("AccessibilityService", "Uber earnings updated from $$lastExtractedDailyEarnings to $$currentDailyEarnings")
                    RevenueTracker.updateUberRevenue(applicationContext, currentDailyEarnings)
                    lastExtractedDailyEarnings = currentDailyEarnings
                }

            }

            // ---------------------------------------------------------------

            // -------------------- PROCESS EVENTS FROM UBER'S APP --------------------
            // ----- Existing Uber processing block -----
            if (packageName.contains("com.ubercab.driver", ignoreCase = true) || packageName.contains("com.google.android.apps.nbu.files", ignoreCase = true)) {

//            if (packageName.contains("com.ubercab.driver", ignoreCase = true)) {
                serviceScope.launch {
                    // First, check if the detected text is an acceptance event ("Picking up")
                    if (detectedText.contains("Picking up", ignoreCase = true)) {
                        // Cancel any pending timeout so we don't later mark it as Declined.
                        pendingRequestTimeoutJob?.cancel()
                        pendingRequestTimeoutJob = null
                        val riderName = extractRiderName(detectedText)
                        if (pendingUberRideRequest != null) {
                            // Update the pending request to Accepted.
                            pendingUberRideRequest!!.requestStatus = "Accepted"
                            pendingUberRideRequest!!.riderName = riderName
                            HistoryManager.addRideRequest(pendingUberRideRequest!!, applicationContext)
                            FileLogger.log("AccessibilityService", "Pending Uber Ride Request accepted with rider: $riderName")
                            pendingUberRideRequest = null
                        } else {
                            // If no pending request exists, create a new one as Accepted.
                            val rideInfo = UberParser.parse(detectedText)
                            if (rideInfo != null) {
                                rideInfo.requestStatus = "Accepted"
                                rideInfo.riderName = riderName
                                HistoryManager.addRideRequest(rideInfo, applicationContext)
                                FileLogger.log("AccessibilityService", "Directly processed accepted Uber ride request with rider: $riderName")
                            }
                        }
                        return@launch
                    }
                    // Existing processing for Uber ride requests
                    if (UberParser.isValidRideRequest(detectedText)) {
                        if (pendingUberRideRequest == null) {
                            pendingUberRideRequest = UberParser.parse(detectedText)
                            // Instead of starting a timeout to auto-decline, we simply store the pending request.
                        } else {
                            FileLogger.log("DebugPending", "Pending ride request already exists; ignoring new valid request")
                        }
                        return@launch
                    }
                    // ---------------- END UPDATED PENDING REQUEST LOGIC ----------------

                    // For events that do not match pending logic, process as usual.
                    UberParser.processUberRideRequest(detectedText, this@AccessibilityService)
                    // NEW: Call PickupLocationGeoCoder to convert pickupLocation into coordinates.
                    val coords = com.stoffeltech.ridetracker.utils.PickupLocationGeoCoder.getCoordinatesFromUberRequest(this@AccessibilityService, detectedText)
                    if (coords != null) {
                        FileLogger.log("AccessibilityService", "Test: Pickup coordinates: latitude=${coords.latitude}, longitude=${coords.longitude}")
                    } else {
                        FileLogger.log("AccessibilityService", "Test: No pickup coordinates obtained from detected text.")
                    }
                }

                // ----- INSERT NEW ON-DEMAND OCR TRIGGER BELOW -----
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                    // Check if the MediaProjection is still valid before attempting capture.
                    if (!MediaProjectionLifecycleManager.isMediaProjectionValid()) {
                        FileLogger.log("AccessibilityService", "MediaProjection is not valid, skipping on-demand OCR capture.")
                    } else {
                        val currentTime = System.currentTimeMillis()
                        // Check that at least 10 seconds have passed since the last OCR capture
                        if (currentTime - lastOcrCaptureTime > OCR_CAPTURE_DEBOUNCE_MS) {
                            lastOcrCaptureTime = currentTime
                            if (!ocrCaptureInProgress) {
                                ocrCaptureInProgress = true
                                serviceScope.launch {
                                    FileLogger.log("AccessibilityService", "Starting persistent on-demand OCR capture for Uber foreground.")
                                    // Use the persistent capture function now.
                                    val ocrResult = ScreenCaptureService.captureOcrOnDemandPersistent(this@AccessibilityService)
                                    if (ocrResult != null) {
                                        UberParser.processUberRideRequest(ocrResult, this@AccessibilityService)
                                    } else {
                                        FileLogger.log("AccessibilityService", "Persistent on-demand OCR capture returned null.")
                                    }
                                    ocrCaptureInProgress = false
                                }
                            } else {

                            }
                        } else {

                        }
                    }
                } else {

                }
// ----- END NEW ON-DEMAND OCR TRIGGER -----
            } else {
                if (packageName.contains("com.lyft.android.driver", ignoreCase = true)) {
                    serviceScope.launch {
                        FileLogger.log("AccessibilityService", "Valid Lyft Ride Request: $detectedText")
                        com.stoffeltech.ridetracker.lyft.LyftParser.processLyftRideRequest(detectedText, this@AccessibilityService)
                    }
                } else {

                }
            }
        }
    }

    /**
     * Recursively extracts text from an AccessibilityNodeInfo tree.
     *
     * @param node The starting AccessibilityNodeInfo node.
     * @return The concatenated text from the node and its children.
     */
    private fun extractTextFromNode(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val builder = StringBuilder()
        node.text?.let { builder.append(it).append(" ") }
        node.contentDescription?.let { builder.append(it).append(" ") }
        for (i in 0 until node.childCount) {
            builder.append(extractTextFromNode(node.getChild(i)))
        }
        return builder.toString().trim()
    }

    /**
     * Recursively extracts all text from an AccessibilityNodeInfo tree.
     *
     * @param node The starting AccessibilityNodeInfo node.
     * @return The concatenated text from the node and its children.
     */
    private fun extractAllTextFromOverlay(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val builder = StringBuilder()
        // Append node text if present.
        node.text?.let { builder.append(it.toString()).append(" ") }
        // Recursively append child texts.
        for (i in 0 until node.childCount) {
            builder.append(extractAllTextFromOverlay(node.getChild(i)))
        }
        val result = builder.toString().trim()
        // Only return result if it qualifies as a valid ride request;
        // otherwise return an empty string.
        return if (UberParser.isValidRideRequest(result)) {
//            FileLogger.log("AccessibilityService", "Extracted overlay text (valid): $result")
            result
        } else {
//            FileLogger.log("AccessibilityService", "Extracted overlay text is not a valid ride request.")
            ""
        }
    }

    @SuppressLint("SuspiciousIndentation")
    override fun onServiceConnected() {
    super.onServiceConnected()
    val info = AccessibilityServiceInfo()
    // Listen for window content changes, state changes, and clicks
    info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                      AccessibilityEvent.TYPE_VIEW_CLICKED
    info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
    // Set packageNames to null to capture events from all apps (including Lyft)
    info.packageNames = null
    info.notificationTimeout = 100
    serviceInfo = info


//        // TEMPORARY DEBUG: Simulate a ride request after 3 seconds.
//        serviceScope.launch {
//            delay(3000)
//            val testRideText = "Delivery (2)Exclusive\$5.07Includes expected tip39 min (12.1 mi) totalDairon's Cuban Pizzeria FoodtruckCalumet St & Lancelot Ave, Lehigh AcresAccept"
//            FileLogger.log("AccessibilityServiceDebug", "Simulating ride request: $testRideText")
//            UberParser.processUberRideRequest(testRideText, this@AccessibilityService)
//        }
        // ----- BEGIN: Debug Simulation for Lyft Ride Request -----
//        serviceScope.launch {
//            delay(10000)  // Delay to allow the overlay service to be ready.
//            // Use the same test text that you see in your logs for Lyft:
//            val testLyftText = "DismissRide Finder\$9.16\$21.98/hr est. rate for this rideMap image with pickup and drop-off route7 min - 2.8 miDa Vinci & Fontana, Lehigh Acres18 min - 8.8 miHomestead & Shady, Lehigh AcresRide within driving rangeJerryVerified5.0Request match"
//            FileLogger.log("AccessibilityServiceDebug", "Simulating Lyft ride request: $testLyftText")
//            // Call the LyftParser processing function:
//            LyftParser.processLyftRideRequest(testLyftText, this@AccessibilityService)
//        }
        // ----- END: Debug Simulation for Lyft Ride Request -----
    }

    override fun onInterrupt() {
        // Handle service interruption if necessary.
//        FileLogger.log("AccessibilityService", "Accessibility Service Interrupted")
    }
}

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
import com.stoffeltech.ridetracker.media.MediaProjectionLifecycleManager
import com.stoffeltech.ridetracker.services.ScreenCaptureService.continuouslyCaptureAndSendOcr
import com.stoffeltech.ridetracker.uber.UberParser
import com.stoffeltech.ridetracker.utils.RevenueTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // -------------------- NULL CHECK --------------------
        event?.let {
            // -------------------- EXTRACT PACKAGE NAME --------------------
            val packageName = event.packageName?.toString() ?: ""

            // -------------------- EXTRACT VISIBLE TEXT --------------------
            val detectedText = extractTextFromNode(event.source).ifBlank { event.text.joinToString(" ") }

            // --- ADDED LOG: Print the extracted text for debugging ---
            Log.d("AccessibilityService", "Extracted text from event: $detectedText")

            // ---------------- SPECIFIC EARNINGS EXTRACTION -----------------
            // Look for the specific earnings string following "TODAY"
            // New regex to capture the dollar amount immediately before "TODAY"
            // This will look for a "$", capture one or more digits, a period, and exactly two digits, then "TODAY".
            val earningsRegex = Regex("\\$(\\d+\\.\\d{2})TODAY")

            val earningsMatch = earningsRegex.find(detectedText)
            if (earningsMatch != null) {
                val earningsStr = earningsMatch.groupValues[1]
                val currentDailyEarnings = earningsStr.toDoubleOrNull() ?: 0.0
                // Log the extracted earnings value.
                Log.d("AccessibilityService", "Extracted daily earnings from 'TODAY': $$currentDailyEarnings")
                // If the new earnings value is greater than the last extracted, update RevenueTracker.
                if (currentDailyEarnings > lastExtractedDailyEarnings) {
                    val delta = currentDailyEarnings - lastExtractedDailyEarnings
                    Log.d("AccessibilityService", "New earnings detected. Delta: $$delta")
                    RevenueTracker.addRevenue(applicationContext, delta)
                    lastExtractedDailyEarnings = currentDailyEarnings
                }
            }
            // ---------------------------------------------------------------

            // -------------------- PROCESS EVENTS FROM UBER'S APP --------------------
            if (packageName.contains("com.ubercab.driver", ignoreCase = true)) {
                serviceScope.launch {
                    // -------------------- NORMAL ACCESSIBILITY PROCESSING --------------------
                    if (UberParser.isValidRideRequest(detectedText)) {
                        Log.d("AccessibilityService", "✔ Valid Uber Ride Request: $detectedText")
                        UberParser.processUberRideRequest(detectedText, this@AccessibilityService)
                    } else {
//                        Log.e("AccessibilityService", "❌ Could not extract valid text from Uber overlay.")
                        // Forward the extracted text (even if not valid) to UberParser for further decision-making.
                        UberParser.processUberRideRequest(detectedText, this@AccessibilityService)
                    }
                }
            } else {
                if (packageName.contains("com.lyft.android.driver", ignoreCase = true)) {
                    serviceScope.launch {
                        Log.d("AccessibilityService", "Valid Lyft Ride Request: $detectedText")
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
//            Log.d("AccessibilityService", "Extracted overlay text (valid): $result")
            result
        } else {
//            Log.d("AccessibilityService", "Extracted overlay text is not a valid ride request.")
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
//            Log.d("AccessibilityServiceDebug", "Simulating ride request: $testRideText")
//            UberParser.processUberRideRequest(testRideText, this@AccessibilityService)
//        }
        // ----- BEGIN: Debug Simulation for Lyft Ride Request -----
//        serviceScope.launch {
//            delay(10000)  // Delay to allow the overlay service to be ready.
//            // Use the same test text that you see in your logs for Lyft:
//            val testLyftText = "DismissRide Finder\$9.16\$21.98/hr est. rate for this rideMap image with pickup and drop-off route7 min - 2.8 miDa Vinci & Fontana, Lehigh Acres18 min - 8.8 miHomestead & Shady, Lehigh AcresRide within driving rangeJerryVerified5.0Request match"
//            Log.d("AccessibilityServiceDebug", "Simulating Lyft ride request: $testLyftText")
//            // Call the LyftParser processing function:
//            LyftParser.processLyftRideRequest(testLyftText, this@AccessibilityService)
//        }
        // ----- END: Debug Simulation for Lyft Ride Request -----

    }

    override fun onInterrupt() {
        // Handle service interruption if necessary.
//        Log.w("AccessibilityService", "Accessibility Service Interrupted")
    }
}

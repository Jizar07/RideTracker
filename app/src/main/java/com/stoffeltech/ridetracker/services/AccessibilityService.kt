package com.stoffeltech.ridetracker.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.localbroadcastmanager.content.LocalBroadcastManager
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
 * daily earnings to be found as a dollar amount following "Waybill" (e.g., "Waybill$0.00").
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
            // Look for the specific earnings string following "Waybill"
            val earningsRegex = Regex("Waybill\\$(\\d+\\.\\d{2})")
            val earningsMatch = earningsRegex.find(detectedText)
            if (earningsMatch != null) {
                val earningsStr = earningsMatch.groupValues[1]
                val currentDailyEarnings = earningsStr.toDoubleOrNull() ?: 0.0
                // Log the extracted earnings value.
                Log.d("AccessibilityService", "Extracted daily earnings from 'Waybill': $$currentDailyEarnings")
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        // -------------------- CONFIGURE ACCESSIBILITY SERVICE --------------------
        val info = serviceInfo
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 100
        // Force detect ALL UI elements, including pop-ups & system dialogs.
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        serviceInfo = info

//        // TEMPORARY DEBUG: Simulate a ride request after 3 seconds.
//        serviceScope.launch {
//            delay(3000)
//            val testRideText = "Delivery (2)Exclusive\$5.07Includes expected tip39 min (12.1 mi) totalDairon's Cuban Pizzeria FoodtruckCalumet St & Lancelot Ave, Lehigh AcresAccept"
//            Log.d("AccessibilityServiceDebug", "Simulating ride request: $testRideText")
//            UberParser.processUberRideRequest(testRideText, this@AccessibilityService)
//        }
    }

    override fun onInterrupt() {
        // Handle service interruption if necessary.
//        Log.w("AccessibilityService", "Accessibility Service Interrupted")
    }
}

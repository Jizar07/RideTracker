package com.stoffeltech.ridetracker.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.stoffeltech.ridetracker.uber.UberParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * AccessibilityService implementation that provides basic permissions and text extraction.
 * It only extracts detected text from accessibility events and delegates Uber-related processing
 * to UberParser when the event comes from the Uber app.
 */
class AccessibilityService : AccessibilityService() {

    // Basic CoroutineScope for handling background tasks.
    val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            val packageName = event.packageName?.toString() ?: ""
            // Extract the text from the node tree
            val detectedText = extractTextFromNode(event.source).ifBlank { event.text.joinToString(" ") }

            // Log the detected text for debugging
            Log.d("AccessibilityService", "Detected text: $detectedText")

            // If the event is from the Uber app, send the detected text to UberParser for processing.
            if (packageName.contains("com.ubercab.driver", ignoreCase = true)) {
                serviceScope.launch {
                    UberParser.processUberRideRequest(detectedText, this@AccessibilityService)
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Configure the AccessibilityService to listen for basic content changes.
        val info = serviceInfo
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 100
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
        Log.w("AccessibilityService", "Accessibility Service Interrupted")
    }
}

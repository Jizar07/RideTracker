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

            // ✅ Extract visible text
            val detectedText = extractTextFromNode(event.source).ifBlank { event.text.joinToString(" ") }

            if (packageName.contains("com.ubercab.driver", ignoreCase = true)) {
                serviceScope.launch {
                    if (detectedText.isNotBlank()) {
                        Log.d("AccessibilityService", "✔ Uber Ride Request: $detectedText")
                        UberParser.processUberRideRequest(detectedText, this@AccessibilityService)
                    } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                        event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                        Log.d("AccessibilityService", "🚨 Uber Overlay Detected! Extracting text...")

                        // ✅ Extract All UI Elements from Uber Overlay
                        val extractedText = extractAllTextFromOverlay(event.source)
                        if (extractedText.isNotBlank()) {
                            Log.d("AccessibilityService", "✔ Extracted Text from Overlay: $extractedText")
                            UberParser.processUberRideRequest(extractedText, this@AccessibilityService)
                        } else {
                            Log.e("AccessibilityService", "❌ Could not extract text from Uber overlay.")
                        }
                    }
                }
            }
        }
    }
    private fun extractAllTextFromOverlay(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""

        val stringBuilder = StringBuilder()
        if (node.text != null) {
            stringBuilder.append(node.text.toString()).append(" ")
        }

        for (i in 0 until node.childCount) {
            stringBuilder.append(extractAllTextFromOverlay(node.getChild(i)))
        }

        return stringBuilder.toString().trim()
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
        // ✅ Force detect ALL UI elements, including pop-ups & system dialogs
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
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

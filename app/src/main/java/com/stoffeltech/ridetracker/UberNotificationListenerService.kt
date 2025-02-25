package com.stoffeltech.ridetracker

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class UberNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        // Check if the notification is from the Uber Driver app.
        if (sbn.packageName == "com.ubercab.driver") {
            Log.d("UberNotification", "Uber notification received: ${sbn.notification.tickerText}")
            // Call the helper function to bring the Uber Driver app forward.
            bringUberDriverToForeground(applicationContext)
        }
    }
}

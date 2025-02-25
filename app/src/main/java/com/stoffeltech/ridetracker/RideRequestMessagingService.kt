package com.stoffeltech.ridetracker

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class RideRequestMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d("RideRequestService", "Message from: ${remoteMessage.from}")

        // Check if the message contains a data payload.
        if (remoteMessage.data.isNotEmpty()) {
            Log.d("RideRequestService", "Data payload: ${remoteMessage.data}")

            // Check for the specific ride request key.
            if (remoteMessage.data["ride_request_type"] == "uber") {
                // Call your helper function to bring the Uber Driver app to the foreground.
                bringUberDriverToForeground(applicationContext)
            }
        }
    }

    override fun onNewToken(token: String) {
        Log.d("RideRequestService", "Refreshed token: $token")
        // Here you could send the new token to your backend if needed.
    }
}

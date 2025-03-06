package com.stoffeltech.ridetracker.media

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * -------------------- MEDIA PROJECTION LIFECYCLE MANAGER --------------------
 * This object encapsulates all logic for managing the MediaProjection lifecycle.
 * It handles:
 * 1. Requesting screen capture permission.
 * 2. Storing the current MediaProjection instance.
 * 3. Checking if the MediaProjection instance is valid.
 * 4. Releasing the MediaProjection when no longer needed.
 */
object MediaProjectionLifecycleManager {

    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionManager: MediaProjectionManager? = null

    /**
     * Requests MediaProjection permission and initializes the MediaProjection instance.
     * Call this from your Activity (e.g., MainActivity) after the user grants permission.
     *
     * @param context The application context.
     * @param resultCode The result code from the permission activity.
     * @param data The intent data returned from the permission activity.
     */
    fun startMediaProjection(context: Context, resultCode: Int, data: Intent) {
        if (mediaProjection == null) {
            mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
            // Register a callback for lifecycle events
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d("MediaProjectionManager", "MediaProjection stopped at: " + System.currentTimeMillis())
                    Exception("MediaProjection onStop stack").printStackTrace()
                    mediaProjection = null
                }
            }, Handler(Looper.getMainLooper()))
            Log.d("MediaProjectionManager", "MediaProjection started and callback registered.")
        } else {
            Log.d("MediaProjectionManager", "MediaProjection already active, reusing instance.")
        }
    }

    /**
     * Returns the current MediaProjection instance.
     */
    fun getMediaProjection(): MediaProjection? {
        return mediaProjection
    }

    /**
     * Checks whether the current MediaProjection instance is valid.
     *
     * @return True if the MediaProjection is non-null, false otherwise.
     */
    fun isMediaProjectionValid(): Boolean {
        return mediaProjection != null
    }

    /**
     * Stops the MediaProjection and clears the stored instance.
     */
    fun stopMediaProjection() {
        Log.w("MediaProjectionManager", "Stopping MediaProjection.")
        mediaProjection?.stop()
        mediaProjection = null
    }
}

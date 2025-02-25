package com.stoffeltech.ridetracker.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.stoffeltech.ridetracker.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

class FloatingOverlayService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    lateinit var tvProfitLossValue: TextView


    companion object {
        private const val CHANNEL_ID = "FloatingOverlayServiceChannel"
        private const val NOTIFICATION_ID = 2

        @SuppressLint("StaticFieldLeak")
        private var instance: FloatingOverlayService? = null

        /**
         * Updates the overlay by setting text and text colors in their respective blocks.
         * Also makes the overlay visible if hidden.
         *
         * @param rideType The ride type string (e.g., "Uber", "Lyft")
         * @param fare The fare string (e.g., "$12.50")
         * @param fareColor Color int for fare value.
         * @param pMile Price per mile string.
         * @param pMileColor Color int for price per mile.
         * @param pHour Price per hour string.
         * @param pHourColor Color int for price per hour.
         * @param miles Total miles string.
         * @param minutes Total minutes string.
         */
        fun updateOverlay(
            rideType: String,
            fare: String, fareColor: Int,
            pMile: String, pMileColor: Int,
            pHour: String, pHourColor: Int,
            miles: String,
            minutes: String,
            profit: String, profitColor: Int
        ) {
            instance?.serviceScope?.launch {
                instance?.floatingView?.visibility = View.VISIBLE
                instance?.tvRideTypeValue?.text = rideType
                instance?.tvFareValue?.text = fare
                instance?.tvFareValue?.setTextColor(fareColor)
                instance?.tvPMileValue?.text = pMile
                instance?.tvPMileValue?.setTextColor(pMileColor)
                instance?.tvPHourValue?.text = pHour
                instance?.tvPHourValue?.setTextColor(pHourColor)
                instance?.tvMilesValue?.text = miles
                instance?.tvTimeValue?.text = minutes
                instance?.tvProfitLossValue?.text = profit
                instance?.tvProfitLossValue?.setTextColor(profitColor)
            }
        }

        /**
         * Hides the entire overlay by setting its visibility to GONE.
         */
        fun hideOverlay() {
            instance?.serviceScope?.launch {
                instance?.floatingView?.visibility = View.GONE
            }
        }
    }

    private lateinit var windowManager: WindowManager
    var floatingView: View? = null

    // New TextView reference for Ride Type
    lateinit var tvRideTypeValue: TextView

    // Existing TextView references.
    lateinit var tvFareValue: TextView
    lateinit var tvPMileValue: TextView
    lateinit var tvPHourValue: TextView
    lateinit var tvMilesValue: TextView
    lateinit var tvTimeValue: TextView

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ride Tracker Overlay")
            .setContentText("Displaying ride calculations overlay")
            .setSmallIcon(R.drawable.logo)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        if (!Settings.canDrawOverlays(this)) {
            Log.e("FloatingOverlayService", "Overlay permission not granted. Please enable it in Settings.")
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_overlay, null)

        // Find the new Ride Type TextView.
        tvRideTypeValue = floatingView!!.findViewById(R.id.tvRideTypeValue)
        // Find the existing TextViews.
        tvFareValue = floatingView!!.findViewById(R.id.tvFareValue)
        tvPMileValue = floatingView!!.findViewById(R.id.tvPMileValue)
        tvPHourValue = floatingView!!.findViewById(R.id.tvPHourValue)
        tvMilesValue = floatingView!!.findViewById(R.id.tvMilesValue)
        tvTimeValue = floatingView!!.findViewById(R.id.tvTimeValue)
        tvProfitLossValue = floatingView!!.findViewById(R.id.tvProfitLossValue)


        // Set up layout parameters for the overlay window.
        val layoutParams = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
        } else {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
        }
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = 100
        layoutParams.y = 100

        // Make the view draggable.
        floatingView!!.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0f
            private var initialTouchY: Float = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, layoutParams)
                        return true
                    }
                }
                return false
            }
        })
        val btnClose = floatingView!!.findViewById<Button>(R.id.btnCloseOverlay)
        btnClose.setOnClickListener {
            // You can choose to simply hide the overlay...
            hideOverlay()
            // ...or stop the service completely:
            // stopSelf()
        }

        windowManager.addView(floatingView, layoutParams)
    }

    private fun runOnUiThread(action: () -> Unit) {
        Handler(Looper.getMainLooper()).post(action)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (floatingView != null) {
            windowManager.removeView(floatingView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Floating Overlay Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
package com.stoffeltech.ridetracker.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.*
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
    var floatingView: View? = null

    // Container that holds the overlay content.
    private var overlayContainer: ViewGroup? = null

    // New TextView reference for Ride Type
    lateinit var tvRideTypeValue: TextView

    // Existing TextView references.
    lateinit var tvFareValue: TextView
    lateinit var tvPMileValue: TextView
    lateinit var tvPHourValue: TextView
    lateinit var tvMilesValue: TextView
    lateinit var tvTimeValue: TextView
    lateinit var tvProfitLossValue: TextView
    lateinit var tvRatingValue: TextView
    lateinit var tvRatingLabel: TextView
    lateinit var tvStopsValue: TextView


    // Variables for dragging.
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    // Layout parameters for the floating view.
    private lateinit var layoutParams: WindowManager.LayoutParams

    // ScaleGestureDetector for pinch-to-zoom resizing.
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var currentScale = 1.0f
    private var originalWidth = 0
    private var originalHeight = 0

    // We'll store each TextView's original text size (in pixels).
    private var origTextSizeRideType: Float = 0f
    private var origTextSizeFare: Float = 0f
    private var origTextSizePMile: Float = 0f
    private var origTextSizePHour: Float = 0f
    private var origTextSizeMiles: Float = 0f
    private var origTextSizeTime: Float = 0f
    private var origTextSizeProfitLoss: Float = 0f

    // Define a moderate scale range.
    private val minScale = 0.8f
    private val maxScale = 1.5f

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
        @SuppressLint("SetTextI18n")
        fun updateOverlay(
            rideType: String,
            fare: String, fareColor: Int,
            pMile: String, pMileColor: Int,
            pHour: String, pHourColor: Int,
            miles: String,
            minutes: String,
            profit: String, profitColor: Int,
            rating: String,
            stops: String
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

                // Combined Rating/Stops update:
                Log.d("OverlayUpdate", "rating: $rating, stops: $stops")
                instance?.tvRatingLabel?.text = "Rating"
                instance?.tvRatingLabel?.setTextColor(Color.WHITE)
                instance?.tvRatingValue?.text = rating
                if (stops.isNotEmpty()) {
                    // Set the new stops TextView with stops info
                    instance?.tvStopsValue?.text = stops
                } else {
                    instance?.tvStopsValue?.text = ""
                }

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

    @SuppressLint("ClickableViewAccessibility")
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

        // Set initial visibility to GONE so the overlay doesn't show immediately.
        floatingView?.visibility = View.GONE

        // Get the container from your layout. Your root element has id "overlayRoot".
        overlayContainer = floatingView?.findViewById(R.id.overlayRoot)
        // Fallback: if not found and the root is a ViewGroup.
        if (overlayContainer == null && floatingView is ViewGroup) {
            overlayContainer = floatingView as ViewGroup
        }
        // Disable clipping on the container.
        overlayContainer?.clipChildren = false
        overlayContainer?.clipToPadding = false

        // Initialize TextView references.
        tvRideTypeValue = floatingView!!.findViewById(R.id.tvRideTypeValue)
        // Find the existing TextViews.
        tvFareValue = floatingView!!.findViewById(R.id.tvFareValue)
        tvPMileValue = floatingView!!.findViewById(R.id.tvPMileValue)
        tvPHourValue = floatingView!!.findViewById(R.id.tvPHourValue)
        tvMilesValue = floatingView!!.findViewById(R.id.tvMilesValue)
        tvTimeValue = floatingView!!.findViewById(R.id.tvTimeValue)
        tvProfitLossValue = floatingView!!.findViewById(R.id.tvProfitLossValue)
        tvRatingValue = floatingView!!.findViewById(R.id.tvRatingValue)
        tvRatingLabel = floatingView!!.findViewById(R.id.tvRatingLabel)
        tvStopsValue = floatingView!!.findViewById(R.id.tvStopsValue)





        // Set up layout parameters.
        layoutParams = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

        // Initialize the ScaleGestureDetector to handle pinch-to-zoom.
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                currentScale *= detector.scaleFactor
                currentScale = currentScale.coerceIn(minScale, maxScale)
                // DO NOT change layoutParams.width – let the XML (wrap_content) determine it.
                // Only update height:
                layoutParams.height = (originalHeight * currentScale).toInt().coerceAtLeast(200)
                windowManager.updateViewLayout(floatingView, layoutParams)
                // Update text sizes:
                updateTextSizes(currentScale)
                // Request layout so that the overlay re-measures its width based on new text sizes.
                floatingView?.requestLayout()
                return true
            }
        })

        // Set up onTouchListener to handle both dragging and scaling.
        floatingView?.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (event.pointerCount == 1) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, layoutParams)
                        true
                    }
                    else -> false
                }
            } else {
                true
            }
        }

        // Set up the close button to hide the overlay.
        val btnClose = floatingView!!.findViewById<Button>(R.id.btnCloseOverlay)
        btnClose.setOnClickListener {
            // You can choose to simply hide the overlay...
            hideOverlay()
            // ...or stop the service completely:
            // stopSelf()
        }

        windowManager.addView(floatingView, layoutParams)

        // Capture the original dimensions of the overlay once it has been laid out.
        floatingView?.post {
            originalWidth = overlayContainer?.width ?: floatingView!!.width
            originalHeight = overlayContainer?.height ?: floatingView!!.height

            origTextSizeRideType = tvRideTypeValue.textSize
            origTextSizeFare = tvFareValue.textSize
            origTextSizePMile = tvPMileValue.textSize
            origTextSizePHour = tvPHourValue.textSize
            origTextSizeMiles = tvMilesValue.textSize
            origTextSizeTime = tvTimeValue.textSize
            origTextSizeProfitLoss = tvProfitLossValue.textSize
        }
    }

    private fun updateTextSizes(scale: Float) {
        // Update each TextView's text size (in pixels) based on its original value.
        tvRideTypeValue.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            origTextSizeRideType * scale
        )
        tvFareValue.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            origTextSizeFare * scale
        )
        tvPMileValue.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            origTextSizePMile * scale
        )
        tvPHourValue.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            origTextSizePHour * scale
        )
        tvMilesValue.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            origTextSizeMiles * scale
        )
        tvTimeValue.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            origTextSizeTime * scale
        )
        tvProfitLossValue.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            origTextSizeProfitLoss * scale
        )
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
package com.stoffeltech.ridetracker.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
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
import android.widget.ImageView // <-- Added to reference our OCR preview
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.stoffeltech.ridetracker.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import com.stoffeltech.ridetracker.services.ScreenshotService
import com.stoffeltech.ridetracker.uber.UberParser
import com.stoffeltech.ridetracker.utils.FileLogger
import com.stoffeltech.ridetracker.utils.RideScoreSettings
import com.stoffeltech.ridetracker.utils.calculateRideScore
import com.stoffeltech.ridetracker.utils.getScoreColor

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


    // We'll keep an ImageView reference for OCR preview:
    private lateinit var ivOcrPreview: ImageView

    // Variables for dragging
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

    // ---------------- OVERLAY AUTO-HIDE CHECK VARIABLES -----------------
    // These variables will be used to schedule a periodic check that hides the overlay
    // when no valid ride request has been detected within the threshold duration.
    private lateinit var overlayCheckHandler: Handler
    private lateinit var overlayCheckRunnable: Runnable
    private lateinit var tvRideScore: TextView


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
            isExclusive: Boolean,
            fare: String, fareColor: Int,
            pMile: String, pMileColor: Int,
            pHour: String, pHourColor: Int,
            miles: String,
            minutes: String,
            profit: String, profitColor: Int,
            rating: String,
            ratingColor: Int,  // New parameter
            stops: String
        ) {
            instance?.serviceScope?.launch {
                instance?.floatingView?.visibility = View.VISIBLE
                // Update ride type text.
                instance?.tvRideTypeValue?.text = rideType
                if (isExclusive) {
                    instance?.tvRideTypeValue?.setTextColor(Color.parseColor("#088DA5"))
                } else {
                    instance?.tvRideTypeValue?.setTextColor(Color.WHITE)
                }
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

                // For rating, use the passed rating color.
                instance?.tvRatingLabel?.text = "Rating"
                instance?.tvRatingLabel?.setTextColor(Color.WHITE)
                instance?.tvRatingValue?.text = rating
                instance?.tvRatingValue?.setTextColor(ratingColor)
                if (stops.isNotEmpty()) {
                    instance?.tvStopsValue?.text = stops
                } else {
                    instance?.tvStopsValue?.text = ""
                }
                // Trigger a full-screen screenshot including the floating overlay.
                instance?.let { service ->
                    ScreenshotService.captureFullScreen(service, rideType)
                }
            }
        }


        /**
         * Exposed function to update the OCR preview image in the overlay.
         */
        fun updateOcrPreview(bitmap: Bitmap?) {
            instance?.setOcrPreview(bitmap)
        }

        /**
         * Hides the entire overlay by setting its visibility to GONE.
         */
        fun hideOverlay() {
            instance?.serviceScope?.launch {
                instance?.floatingView?.visibility = View.GONE
            }
        }

        /**
         * Returns a bitmap snapshot of the overlay itself, if needed.
         */
        fun getOverlayBitmap(): Bitmap? {
            instance?.floatingView?.let { view ->
                // Create a bitmap with the view's width and height
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                // Create a canvas with that bitmap
                val canvas = android.graphics.Canvas(bitmap)
                // Draw the view onto the canvas
                view.draw(canvas)
                return bitmap
            }
            return null
        }
        // ----- NEW: Expose updateRideScore via a companion method -----
        // Insert this inside your companion object in FloatingOverlayService.kt
        fun updateScore(
            actualPMile: Float,
            actualPHour: Float,
            actualFare: Float,
            settings: com.stoffeltech.ridetracker.utils.RideScoreSettings
        ) {
            // Force main thread updates
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                instance?.updateRideScore(actualPMile, actualPHour, actualFare, settings)
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
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            FileLogger.log("FloatingOverlayService", "SecurityException in startForeground: ${e.message}")
            stopSelf()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            FileLogger.log("FloatingOverlayService", "Overlay permission not granted. Please enable it in Settings.")
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

        // Initialize the new ride score TextView.
        tvRideScore = floatingView!!.findViewById<TextView>(R.id.tvRideScore)

        // Initialize our OCR preview ImageView
        ivOcrPreview = floatingView!!.findViewById(R.id.ivOcrPreview)

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
//        val btnClose = floatingView!!.findViewById<Button>(R.id.btnCloseOverlay)
//        btnClose.setOnClickListener {
//            // You can choose to simply hide the overlay...
//            hideOverlay()
//            // ...or stop the service completely:
//            // stopSelf()
//        }

        // Add the view
        windowManager.addView(floatingView, layoutParams)

        // ---------------- PERIODIC CHECK FOR VALID REQUEST ----------------
        // This check hides the overlay if no valid ride request is detected.
        // It uses UberParser.lastValidRequestTime and LyftParser.lastValidRequestTime, which should be updated whenever a valid request is processed.
        overlayCheckHandler = Handler(Looper.getMainLooper())
        overlayCheckRunnable = object : Runnable {
            override fun run() {
                val lastUberTime = UberParser.lastValidRequestTime
                val lastLyftTime = com.stoffeltech.ridetracker.lyft.LyftParser.lastValidRequestTime
                val lastParserTime = maxOf(lastUberTime, lastLyftTime)

                val isDebugMode = false  // Set to true during debugging; set to false in production.

                // Only auto-hide if not in debug mode and if no valid request was processed in the last 3 seconds.
                if (!isDebugMode && System.currentTimeMillis() - lastParserTime > 10000) {
                    hideOverlay()
                }
                overlayCheckHandler.postDelayed(this, 1000)
            }
        }
        overlayCheckHandler.postDelayed(overlayCheckRunnable, 3000)
        // ---------------- END OVERLAY AUTO-HIDE CHECK -----------------


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

        // Make sure the companion object can access this instance
        instance = this
    }

    /**
     * Internal function that sets or clears the OCR preview image on the overlay.
     */
    fun setOcrPreview(bitmap: Bitmap?) {
        serviceScope.launch(Dispatchers.Main) {
            if (bitmap != null) {
                ivOcrPreview.setImageBitmap(bitmap)
                ivOcrPreview.visibility = View.VISIBLE
            } else {
                ivOcrPreview.setImageDrawable(null)
                ivOcrPreview.visibility = View.GONE
            }
        }
    }

    private fun updateTextSizes(scale: Float) {
        tvRideTypeValue.setTextSize(TypedValue.COMPLEX_UNIT_PX, origTextSizeRideType * scale)
        tvFareValue.setTextSize(TypedValue.COMPLEX_UNIT_PX, origTextSizeFare * scale)
        tvPMileValue.setTextSize(TypedValue.COMPLEX_UNIT_PX, origTextSizePMile * scale)
        tvPHourValue.setTextSize(TypedValue.COMPLEX_UNIT_PX, origTextSizePHour * scale)
        tvMilesValue.setTextSize(TypedValue.COMPLEX_UNIT_PX, origTextSizeMiles * scale)
        tvTimeValue.setTextSize(TypedValue.COMPLEX_UNIT_PX, origTextSizeTime * scale)
        tvProfitLossValue.setTextSize(TypedValue.COMPLEX_UNIT_PX, origTextSizeProfitLoss * scale)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        return START_STICKY
    }

    override fun onDestroy() {
        if (::overlayCheckHandler.isInitialized) {
            overlayCheckHandler.removeCallbacks(overlayCheckRunnable)
        }
        super.onDestroy()
        serviceScope.cancel()
        if (floatingView != null) {
            windowManager.removeView(floatingView)
        }
        // Release the OCR resources when the service is destroyed
        ScreenCaptureService.releaseTextRecognizer()
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
    /**
     * New function to update the ride score on the overlay.
     * @param actualPMile The actual price per mile.
     * @param actualPHour The actual price per hour.
     * @param actualFare The actual fare price.
     * @param settings User-defined settings including ideal values, scale factors, and weights.
     */
    fun updateRideScore(
        actualPMile: Float,
        actualPHour: Float,
        actualFare: Float,
        settings: RideScoreSettings
    ) {
        val score = calculateRideScore(actualPMile, actualPHour, actualFare, settings)
        val color = getScoreColor(score)
        tvRideScore.text = score.toInt().toString()
        tvRideScore.setTextColor(color)
    }

}

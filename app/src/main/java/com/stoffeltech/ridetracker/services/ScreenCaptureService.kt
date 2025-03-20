package com.stoffeltech.ridetracker.services

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.app.NotificationCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.hardware.display.VirtualDisplay
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.channels.Channel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.stoffeltech.ridetracker.uber.UberParser
import com.stoffeltech.ridetracker.utils.FileLogger
import kotlin.coroutines.coroutineContext


/**
 * ---------------- SCREEN CAPTURE SERVICE ----------------
 * This object contains essential functions for continuous OCR capture.
 *
 * Added Global Variable:
 * - lastCapturedBitmap: Stores the latest captured frame for use by ScreenshotService.
 */
object ScreenCaptureService {

    // ---------------- GLOBAL VARIABLE -----------------
    // Stores the last captured bitmap for screenshot purposes.
    @Volatile
    var lastCapturedBitmap: Bitmap? = null
    var lastProcessedBitmap: Bitmap? = null

    // Region Of Interest (ROI) for OCR fallback capture.
    var ocrRoi: Rect? = null

    // ----- FLAG TO PREVENT OVERLAPPING OCR TASKS -----
    private val isOcrProcessing = java.util.concurrent.atomic.AtomicBoolean(false)


    // ----- CACHE TEXT RECOGNIZER INSTANCE - single instance for reuse -----
    // ----- UPDATED: Mutable detector with getter -----
    private var textRecognizerInstance: com.google.mlkit.vision.text.TextRecognizer? = null

    private fun getTextRecognizer(): com.google.mlkit.vision.text.TextRecognizer {
        if (textRecognizerInstance == null) {
            val options = com.google.mlkit.vision.text.latin.TextRecognizerOptions.Builder().build()
            textRecognizerInstance = com.google.mlkit.vision.text.TextRecognition.getClient(options)
        }
        return textRecognizerInstance!!
    }

    // ----- PERSISTENT CAPTURE RESOURCES -----
    private var globalVirtualDisplay: VirtualDisplay? = null
    private var globalImageReader: ImageReader? = null
    private var globalHandlerThread: HandlerThread? = null

    // ----- INITIALIZE PERSISTENT CAPTURE RESOURCES -----
// Call this function once when MediaProjection is granted.
    fun initPersistentCapture(context: Context, mediaProjection: MediaProjection) {
        if (globalVirtualDisplay == null || globalImageReader == null || globalHandlerThread == null) {
            globalHandlerThread = HandlerThread("PersistentOcrThread").apply { start() }
            val handler = Handler(globalHandlerThread!!.looper)
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            // Create an ImageReader with a capacity of 2 for continuous availability.
            globalImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            globalVirtualDisplay = mediaProjection.createVirtualDisplay(
                "PersistentOcrDisplay",
                width,
                height,
                metrics.densityDpi,
                0,
                globalImageReader!!.surface,
                null,
                handler
            )
            FileLogger.log("ScreenCaptureService", "Persistent capture resources initialized.")
        }
    }

    // ----- RELEASE PERSISTENT CAPTURE RESOURCES -----
// Call this function when ending the session (e.g., when MediaProjection stops).
    fun releasePersistentCapture() {
        globalVirtualDisplay?.release()
        globalImageReader?.close()
        globalHandlerThread?.quitSafely()
        globalVirtualDisplay = null
        globalImageReader = null
        globalHandlerThread = null
        FileLogger.log("ScreenCaptureService", "Persistent capture resources released.")
    }




    // ----- RELEASE TEXT RECOGNIZER INSTANCE -----
    fun releaseTextRecognizer() {
        try {
            textRecognizerInstance?.close()
            textRecognizerInstance = null // Reset so a new instance is created next time
            FileLogger.log("ScreenCaptureService", "TextRecognizer released")
        } catch (e: Exception) {
            FileLogger.log("ScreenCaptureService", "Error releasing TextRecognizer: ${e.message}")
        }
    }



    // -------------- NEW GLOBALS FOR BOUNDING BOX --------------
    @Volatile
    var lockedRideTypeTop: Int? = null
    @Volatile
    var boundingBoxLockActive: Boolean = false
    // ----------------------------------------------------------

    /**
     * ---------------- RUN OCR ON BITMAP - START ----------------
     * Processes the provided bitmap using ML Kit's OCR and returns the extracted text.
     */
    suspend fun runOcrOnBitmap(bitmap: Bitmap): String? {
        return withContext(Dispatchers.Default) {
            try {
                val startTime = System.currentTimeMillis()  // --- PROFILE: Start time ---
                val image = InputImage.fromBitmap(bitmap, 0)
                // Use our fresh (or reinitialized) detector
                val result = getTextRecognizer().process(image).await()
                val duration = System.currentTimeMillis() - startTime  // --- PROFILE: End time ---
                result.text
            } catch (e: Exception) {
                FileLogger.log("ScreenCaptureService", "Error during OCR processing: ${e.message}")
                null
            }
        }
    }



    /**
     * Continuously captures frames for OCR and sends the captured texts to the provided callback.
     * This function creates a single VirtualDisplay and ImageReader, then enters a loop capturing frames.
     * For each frame, OCR is run and if non-blank text is extracted, the callback is invoked.
     *
     * @param context The application context.
     * @param mediaProjection A valid MediaProjection instance.
     * @param captureIntervalMillis Interval between captures in milliseconds (default 500ms).
     * @param ocrCallback Callback function to receive each captured OCR text.
     * ---------------- CONTINUOUS OCR CAPTURE & SEND - END ----------------
     */
    @SuppressLint("SuspiciousIndentation")
    suspend fun continuouslyCaptureAndSendOcr(
        context: Context,
        mediaProjection: MediaProjection,
        captureIntervalMillis: Long = 500,
        ocrCallback: (String) -> Unit
    ) {
        // -------------------- SET UP HANDLER THREAD --------------------
        val handlerThread = HandlerThread("ContinuousOcrSendThread")
        handlerThread.start()
        val handler = Handler(handlerThread.looper)
        // -----------------------------------------------------------------

        // -------------------- GET SCREEN DIMENSIONS --------------------
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        // -----------------------------------------------------------------

        // -------------------- CREATE IMAGE READER --------------------
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        // -----------------------------------------------------------------

        // -------------------- CREATE VIRTUAL DISPLAY --------------------
        var virtualDisplay = mediaProjection.createVirtualDisplay(
            "ContinuousOcrSendDisplay",
            width,
            height,
            metrics.densityDpi,
            0,
            imageReader.surface,
            null,
            handler
        )

        var attempts = 0
        val maxAttempts = 10
        while (virtualDisplay == null && attempts < maxAttempts) {
            FileLogger.log("ScreenCaptureService", "VirtualDisplay not ready. Attempt ${attempts + 1} of $maxAttempts. Retrying in 750ms...")
            delay(750) // wait 750ms before retrying
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ContinuousOcrSendDisplay",
                width,
                height,
                metrics.densityDpi,
                0,
                imageReader.surface,
                null,
                handler
            )
            attempts++
        }

        if (virtualDisplay == null) {
            FileLogger.log("ScreenCaptureService", "VirtualDisplay creation failed after $maxAttempts attempts; aborting capture.")
            handlerThread.quitSafely()
            return
        }
        // -----------------------------------------------------------------

        try {
            val bitmapChannel = Channel<Bitmap?>(capacity = Channel.CONFLATED)
            // ---- Add a counter for consecutive timeouts ----
            var consecutiveTimeouts = 0

        val imageListener = ImageReader.OnImageAvailableListener { reader ->
            reader.acquireLatestImage()?.let { image ->
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val fullBitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    fullBitmap.copyPixelsFromBuffer(buffer)

                    val resultBitmap = ocrRoi?.let { rect ->
                        Bitmap.createBitmap(fullBitmap, rect.left, rect.top, rect.width(), rect.height()).also {
                            fullBitmap.recycle()
                        }
                    } ?: fullBitmap

                    // *** Add this line to update the global lastCapturedBitmap ***
                    lastCapturedBitmap = resultBitmap

                    bitmapChannel.trySend(resultBitmap)

                } catch (e: Exception) {
                    FileLogger.log("ScreenCaptureService", "Error processing image: ${e.message}")
                    bitmapChannel.trySend(null)
                } finally {
                    image.close()
                }
            }
        }

        imageReader.setOnImageAvailableListener(imageListener, handler)

        while (coroutineContext.isActive) {

            // If an OCR task is already running, skip this iteration
            if (!isOcrProcessing.compareAndSet(false, true)) {
                delay(captureIntervalMillis)
                continue
            }
            val captureStart = System.currentTimeMillis()  // --- PROFILE: Capture start ---
            val capturedBitmap = withTimeoutOrNull(2000) { bitmapChannel.receive() }
            val captureDuration = System.currentTimeMillis() - captureStart

            // FileLogger.log("ScreenCaptureService", "Bitmap capture time: $captureDuration ms")

            if (capturedBitmap == null) {
                FileLogger.log("ScreenCaptureService", "Bitmap capture timed out or failed.")
                consecutiveTimeouts++  // Increment timeout counter
                if (consecutiveTimeouts >= 3) {
                    sendCaptureTimeoutNotification(context)
                    consecutiveTimeouts = 0  // Reset counter after notification
                }
                isOcrProcessing.set(false)  // Reset flag to allow future processing
                delay(captureIntervalMillis)
                continue
            } else {
                consecutiveTimeouts = 0  // Reset counter on successful capture
            }
            // ----- STEP 3C: Skip OCR if the captured frame is similar to the last processed frame -----
            // This helps reduce redundant OCR processing when consecutive frames are nearly identical.
            if (lastProcessedBitmap != null && areBitmapsSimilar(lastProcessedBitmap!!, capturedBitmap)) {
//                FileLogger.log("ScreenCaptureService", "Captured frame similar to previous; skipping OCR.")
                isOcrProcessing.set(false)  // Reset the processing flag
                delay(captureIntervalMillis)
                continue
            }

            // ---- CONVERT THE CAPTURED BITMAP TO GRAYSCALE ----
            val grayscaleBitmap = toGrayscale(capturedBitmap)


            // Process OCR using the grayscale bitmap
            val ocrResult = runOcrOnBitmap(grayscaleBitmap)
                // Use the ocrResult for your ride processing logic here...

            isOcrProcessing.set(false)  // Reset the flag after processing is complete
            delay(captureIntervalMillis)

            // ---------------------------------------------------------------
            // 1) Quick full-screen OCR to see if it's Lyft or Uber
            // ---------------------------------------------------------------
            val fullScreenImage = InputImage.fromBitmap(capturedBitmap, 0)
            // Removed creation of new recognizer and use the cached textRecognizer instead
            val fullScreenResult = try {
                getTextRecognizer().process(fullScreenImage).await()
            } catch (e: Exception) {
                FileLogger.log("ScreenCaptureService", "Error during quick OCR: ${e.message}")
                null
            }

            val fullText = fullScreenResult?.text ?: ""
            val isLyft = fullText.contains("Lyft", ignoreCase = true) // or "Lyft request"
            val isUber = UberParser.isValidRideRequest(fullText) // quick check from your parser

            // ---------------------------------------------------------------
            // 2) If boundingBoxLockActive is false, find the top for bounding box
            //    - For Uber: ride type top
            //    - For Lyft: fare price top
            // ---------------------------------------------------------------
            var finalTop: Int? = null
            if (!boundingBoxLockActive) {
                // We'll do a small pass to see if it's Lyft or Uber
                var uberRideTypeTop: Int? = null
                var lyftFareTop: Int? = null

                if (fullScreenResult != null) {
                    // NEW: We'll skip blocks that have these keywords
                    val ignoreRegex = Regex("(incl|bonus|est\\.|/hr|rate)", RegexOption.IGNORE_CASE)
                    // Regex for a normal fare line
                    val lyftFareRegex = Regex("\\$(\\d+\\.\\d{2})", RegexOption.IGNORE_CASE)

                    for (block in fullScreenResult.textBlocks) {
                        val blockText = block.text
                        val box = block.boundingBox ?: continue

                        // Skip lines with these keywords for Lyft bounding box
                        if (ignoreRegex.containsMatchIn(blockText)) {
                            continue
                        }

                        // Uber bounding box
                        val uberRideTypes = UberParser.getRideTypes()
                        for (ride in uberRideTypes) {
                            if (blockText.contains(ride, ignoreCase = true)) {
                                if (uberRideTypeTop == null || box.top < uberRideTypeTop) {
                                    uberRideTypeTop = box.top
                                }
                            }
                        }

                        // For Lyft
                        val fareMatch = lyftFareRegex.find(blockText)
                        if (fareMatch != null) {
                            if (lyftFareTop == null || box.top < lyftFareTop) {
                                lyftFareTop = box.top
                            }
                        }
                    }
                }
    //                    FileLogger.log("ScreenCaptureService", "Final rideTypeTop for cropping: $rideTypeTop")

                if (isLyft && lyftFareTop != null) {
                    val offset = 50
                    finalTop = (lyftFareTop - offset).coerceAtLeast(0)
                    boundingBoxLockActive = true
                    lockedRideTypeTop = finalTop
//                    FileLogger.log("ScreenCaptureService", "Lyft fare bounding box => $finalTop")
                } else if (isUber && uberRideTypeTop != null) {
                    finalTop = uberRideTypeTop - 20
                    boundingBoxLockActive = true
                    lockedRideTypeTop = finalTop
//                    FileLogger.log("ScreenCaptureService", "Uber ride type bounding box => $finalTop")
                }
            } else {
                // bounding box is locked from previous frames
                finalTop = lockedRideTypeTop
            }

            // ---------------------------------------------------------------
            // 3) Crop from finalTop downward if valid
            // ---------------------------------------------------------------
            val finalBitmap = if (finalTop != null && finalTop in 0 until grayscaleBitmap.height) {
                Bitmap.createBitmap(
                    grayscaleBitmap,
                    0,
                    finalTop,
                    grayscaleBitmap.width,
                    grayscaleBitmap.height - finalTop
                )
            } else {
                grayscaleBitmap
            }

            // Show the OCR preview
//                FloatingOverlayService.updateOcrPreview(finalBitmap)

            // ---------------------------------------------------------------
            // 4) Final OCR pass
            // ---------------------------------------------------------------
            // ----- STEP 2: Convert finalBitmap to binary for improved OCR accuracy -----
            // Convert the cropped grayscale image to a binary (black and white) image.
            val binaryFinalBitmap = toBinary(finalBitmap)  // Uses the new toBinary helper function

            // Create an InputImage from the binary image for final OCR processing.
            val finalImage = InputImage.fromBitmap(binaryFinalBitmap, 0)

            val finalResult = try {
                getTextRecognizer().process(finalImage).await()
            } catch (e: Exception) {
                FileLogger.log("ScreenCaptureService", "Error during final OCR: ${e.message}")
                null
            }
            // ----- REPLACEMENT CODE: Extract text from lines only -----
            val finalText = finalResult?.textBlocks
                ?.flatMap { it.lines }              // Extract lines from each block
                ?.joinToString(separator = "\n") { it.text } ?: ""

//            FileLogger.log("ScreenCaptureService", "Final OCR result (lines only): $finalText")

            finalResult?.textBlocks?.forEachIndexed { index, block ->
//                FileLogger.log("OCR_DEBUG", "Text Block #$index: '${block.text}' with bounding box ${block.boundingBox}")
            }

            ocrCallback(finalText)
            lastProcessedBitmap = capturedBitmap

            delay(captureIntervalMillis)
            }
        } catch (e: Exception) {
//            FileLogger.log("ScreenCaptureService", "Unexpected error in continuous OCR: ${e.message}")
        } finally {
            virtualDisplay.release()
            imageReader.close()
            handlerThread.quitSafely()
        }
    }

    // ----- HELPER FUNCTION: Send Notification After Multiple Timeouts -----
    private fun sendCaptureTimeoutNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "CaptureTimeoutChannel",
                "Capture Timeout Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when bitmap capture fails repeatedly."
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(context, "CaptureTimeoutChannel")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Screen Capture Error")
            .setContentText("Bitmap capture timed out repeatedly. Consider restarting the app.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1001, notification)
    }
    // ----- HELPER FUNCTION: Convert Bitmap to Grayscale -----
    // This function creates a new Bitmap in grayscale using a ColorMatrix.
    fun toGrayscale(src: Bitmap): Bitmap {
        // Create a mutable bitmap with the same dimensions and configuration as src.
        val config = src.config ?: Bitmap.Config.ARGB_8888
        val grayscaleBitmap = Bitmap.createBitmap(src.width, src.height, config)

        // Create a Canvas to draw onto the new bitmap.
        val canvas = android.graphics.Canvas(grayscaleBitmap)
        // Create a ColorMatrix that converts colors to grayscale.
        val colorMatrix = android.graphics.ColorMatrix().apply {
            setSaturation(0f) // 0 means fully desaturated (grayscale)
        }
        // Create a Paint object with the ColorMatrixColorFilter.
        val paint = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
        }
        // Draw the original bitmap onto the grayscale bitmap.
        canvas.drawBitmap(src, 0f, 0f, paint)
        return grayscaleBitmap
    }
    // ----- HELPER FUNCTION: Convert Grayscale Bitmap to Binary using Thresholding -----
    // This function converts a grayscale bitmap into a binary (black and white) image
    // using a simple threshold. Adjust the 'threshold' value (default is 128) if needed.
    fun toBinary(src: Bitmap, threshold: Int = 128): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        // Retrieve all pixel data from the source bitmap
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            // Calculate the luminance using standard weights
            val gray = (0.299 * red + 0.587 * green + 0.114 * blue).toInt()
            // Set the pixel to black if below the threshold; otherwise, white
            pixels[i] = if (gray < threshold) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val binaryBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        binaryBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return binaryBitmap
    }
    // ----- HELPER FUNCTION: Compare Two Bitmaps for Similarity -----
    // This function downsamples both bitmaps and calculates a difference metric.
    // If the total difference is below 'threshold', they are considered similar.
    fun areBitmapsSimilar(bmp1: Bitmap, bmp2: Bitmap, sampleSize: Int = 10, threshold: Int = 1000): Boolean {
        // Downscale both bitmaps to reduce computation.
        val scaledBmp1 = Bitmap.createScaledBitmap(bmp1, sampleSize, sampleSize, true)
        val scaledBmp2 = Bitmap.createScaledBitmap(bmp2, sampleSize, sampleSize, true)
        var diff = 0
        for (x in 0 until sampleSize) {
            for (y in 0 until sampleSize) {
                val pixel1 = scaledBmp1.getPixel(x, y)
                val pixel2 = scaledBmp2.getPixel(x, y)
                // Extract RGB components.
                val r1 = (pixel1 shr 16) and 0xFF
                val g1 = (pixel1 shr 8) and 0xFF
                val b1 = pixel1 and 0xFF
                val r2 = (pixel2 shr 16) and 0xFF
                val g2 = (pixel2 shr 8) and 0xFF
                val b2 = pixel2 and 0xFF
                diff += kotlin.math.abs(r1 - r2) + kotlin.math.abs(g1 - g2) + kotlin.math.abs(b1 - b2)
            }
        }
        return diff < threshold
    }
    // ----- PERSISTENT ON-DEMAND OCR CAPTURE FUNCTION (Polling Approach) -----
    // This function reuses the persistent VirtualDisplay and ImageReader by polling for the latest image.
    suspend fun captureOcrOnDemandPersistent(
        context: Context,
        captureTimeoutMillis: Long = 1000  // Adjust timeout as needed
    ): String? {
        // Wait until persistent resources are initialized (poll up to captureTimeoutMillis)
        val startWait = System.currentTimeMillis()
        while (globalImageReader == null && System.currentTimeMillis() - startWait < captureTimeoutMillis) {
            delay(50)
        }
        if (globalImageReader == null || globalVirtualDisplay == null) {
            FileLogger.log("ScreenCaptureService", "Persistent capture resources not initialized.")
            return null
        }
        // Clear out any stale images from previous captures.
        while (true) {
            val staleImage = globalImageReader!!.acquireLatestImage()
            if (staleImage != null) {
                staleImage.close()
            } else {
                break
            }
        }

        // Poll for a new image up to captureTimeoutMillis
        var image = globalImageReader!!.acquireLatestImage()
        val startTime = System.currentTimeMillis()
        while (image == null && System.currentTimeMillis() - startTime < captureTimeoutMillis) {
            delay(20) // Poll every 50ms
            image = globalImageReader!!.acquireLatestImage()
        }

        if (image == null) {
            FileLogger.log("ScreenCaptureService", "Persistent on-demand bitmap capture timed out or failed.")
            return null
        }

        // Process the captured image
        return try {
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val fullBitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                metrics.heightPixels,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)
            image.close()

            // Optionally process the bitmap (e.g., convert to grayscale) for improved OCR accuracy.
            val processedBitmap = toGrayscale(fullBitmap)
            runOcrOnBitmap(processedBitmap)
        } catch (e: Exception) {
            FileLogger.log("ScreenCaptureService", "Error processing image: ${e.message}")
            image.close()
            null
        }
    }
    // ----- PERSISTENT BITMAP CAPTURE FUNCTION -----
    // This function captures a raw Bitmap using the persistent VirtualDisplay and ImageReader.
    suspend fun captureBitmapPersistent(
        context: Context,
        captureTimeoutMillis: Long = 1000  // Adjust as needed
    ): Bitmap? {
        if (globalImageReader == null || globalVirtualDisplay == null) {
            FileLogger.log("ScreenCaptureService", "Persistent capture resources not initialized.")
            return null
        }

        // Drain any stale images
        while (true) {
            val staleImage = globalImageReader!!.acquireLatestImage()
            if (staleImage != null) {
                staleImage.close()
            } else {
                break
            }
        }

        // Poll for a fresh image
        var image = globalImageReader!!.acquireLatestImage()
        val startTime = System.currentTimeMillis()
        while (image == null && System.currentTimeMillis() - startTime < captureTimeoutMillis) {
            delay(20) // Poll every 20ms
            image = globalImageReader!!.acquireLatestImage()
        }

        if (image == null) {
            FileLogger.log("ScreenCaptureService", "Persistent bitmap capture timed out or failed.")
            return null
        }

        return try {
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            // Create the bitmap based on screen dimensions.
            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                metrics.heightPixels,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()
            bitmap
        } catch (e: Exception) {
            FileLogger.log("ScreenCaptureService", "Error capturing bitmap: ${e.message}")
            image.close()
            null
        }
    }

}

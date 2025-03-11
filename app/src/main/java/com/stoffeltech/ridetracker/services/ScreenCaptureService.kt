package com.stoffeltech.ridetracker.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.suspendCancellableCoroutine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.stoffeltech.ridetracker.uber.UberParser
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.FileOutputStream

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

    // Region Of Interest (ROI) for OCR fallback capture.
    var ocrRoi: Rect? = null

    // ----- FLAG TO PREVENT OVERLAPPING OCR TASKS -----
    private val isOcrProcessing = java.util.concurrent.atomic.AtomicBoolean(false)


    // ----- CACHE TEXT RECOGNIZER INSTANCE - single instance for reuse -----
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // ----- RELEASE TEXT RECOGNIZER INSTANCE -----
    fun releaseTextRecognizer() {
        try {
            textRecognizer.close()  // Safely releases resources
            Log.d("ScreenCaptureService", "TextRecognizer released")
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error releasing TextRecognizer: ${e.message}")
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
                // Use the cached textRecognizer instance instead of creating a new one
                val result = textRecognizer.process(image).await()
                val duration = System.currentTimeMillis() - startTime  // --- PROFILE: End time ---
                Log.d("ScreenCaptureService", "OCR processing time: $duration ms")
                result.text
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error during OCR processing: ${e.message}")
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
        val virtualDisplay = mediaProjection.createVirtualDisplay(
            "ContinuousOcrSendDisplay",
            width,
            height,
            metrics.densityDpi,
            0,
            imageReader.surface,
            null,
            handler
        )
        if (virtualDisplay == null) {
            Log.e("ScreenCaptureService", "VirtualDisplay creation failed in continuous OCR & send; aborting capture.")
            handlerThread.quitSafely()
            return
        }
        // -----------------------------------------------------------------

    try {
        val bitmapChannel = Channel<Bitmap?>(capacity = Channel.CONFLATED)

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
                    Log.e("ScreenCaptureService", "Error processing image: ${e.message}")
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
            val captureDuration = System.currentTimeMillis() - captureStart  // --- PROFILE: Capture duration ---
            Log.d("ScreenCaptureService", "Bitmap capture time: $captureDuration ms")

            if (capturedBitmap == null) {
                Log.e("ScreenCaptureService", "Bitmap capture timed out or failed.")
                isOcrProcessing.set(false)  // Reset flag to allow future processing
                delay(captureIntervalMillis)
                continue
            }
            // Process OCR using the cached recognizer
            val ocrResult = runOcrOnBitmap(capturedBitmap)
            // Use the ocrResult for your ride processing logic here...

            isOcrProcessing.set(false)  // Reset the flag after processing is complete
            delay(captureIntervalMillis)

            // ---------------------------------------------------------------
            // 1) Quick full-screen OCR to see if it's Lyft or Uber
            // ---------------------------------------------------------------
            val fullScreenImage = InputImage.fromBitmap(capturedBitmap, 0)
            // Removed creation of new recognizer and use the cached textRecognizer instead
            val fullScreenResult = try {
                textRecognizer.process(fullScreenImage).await()
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error during quick OCR: ${e.message}")
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
    //                    Log.d("ScreenCaptureService", "Final rideTypeTop for cropping: $rideTypeTop")

                if (isLyft && lyftFareTop != null) {
                    val offset = 50
                    finalTop = (lyftFareTop - offset).coerceAtLeast(0)
                    boundingBoxLockActive = true
                    lockedRideTypeTop = finalTop
                    Log.d("ScreenCaptureService", "Lyft fare bounding box => $finalTop")
                } else if (isUber && uberRideTypeTop != null) {
                    finalTop = uberRideTypeTop - 20
                    boundingBoxLockActive = true
                    lockedRideTypeTop = finalTop
                    Log.d("ScreenCaptureService", "Uber ride type bounding box => $finalTop")
                }
            } else {
                // bounding box is locked from previous frames
                finalTop = lockedRideTypeTop
            }

            // ---------------------------------------------------------------
            // 3) Crop from finalTop downward if valid
            // ---------------------------------------------------------------
            val finalBitmap = if (finalTop != null && finalTop in 0 until capturedBitmap.height) {
                Bitmap.createBitmap(
                    capturedBitmap,
                    0,
                    finalTop,
                    capturedBitmap.width,
                    capturedBitmap.height - finalTop
                )
            } else {
                capturedBitmap
            }

            // Show the OCR preview
//                FloatingOverlayService.updateOcrPreview(finalBitmap)

            // ---------------------------------------------------------------
            // 4) Final OCR pass
            // ---------------------------------------------------------------
            val finalImage = InputImage.fromBitmap(finalBitmap, 0)
            val finalResult = try {
                textRecognizer.process(finalImage).await()
            } catch (e: Exception) {
                null
            }
            val finalText = finalResult?.text ?: ""

            // NEW DEBUG: Log each recognized text block
            if (finalResult != null) {
                for ((index, block) in finalResult.textBlocks.withIndex()) {
//                        Log.d("FinalOCR", "Block #$index: ${block.text}")
                }
            }

            // Send text to callback
            ocrCallback(finalText)

            delay(captureIntervalMillis)
        }
        } catch (e: Exception) {
    //        Log.e("ScreenCaptureService", "Unexpected error in continuous OCR: ${e.message}")
        } finally {
            virtualDisplay.release()
            imageReader.close()
            handlerThread.quitSafely()
        }
    }
}

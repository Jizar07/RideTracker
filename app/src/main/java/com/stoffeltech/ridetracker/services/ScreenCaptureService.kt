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

    // ---------------- GLOBAL VARIABLE -----------------
    // Region Of Interest (ROI) for OCR fallback capture.
    var ocrRoi: Rect? = null

    /**
     * ---------------- RUN OCR ON BITMAP - START ----------------
     * Processes the provided bitmap using ML Kit's OCR and returns the extracted text.
     */
    suspend fun runOcrOnBitmap(bitmap: Bitmap): String? {
//        Log.d("runOcrOnBitmap", "OCR function invoked with bitmap size: ${bitmap.width}x${bitmap.height}")
        return withContext(Dispatchers.Default) {
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
//                Log.d("runOcrOnBitmap", "ML Kit text recognizer initialized, processing image...")
                val result = recognizer.process(image).await()
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
            val capturedBitmap = withTimeoutOrNull(2000) { bitmapChannel.receive() }

                if (capturedBitmap != null) {
                    // --- Initial OCR Pass to detect ride type region ---
                    val initialImage = InputImage.fromBitmap(capturedBitmap, 0)
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    val initialResult = try {
                        recognizer.process(initialImage).await()
                    } catch (e: Exception) {
                        Log.e("ScreenCaptureService", "Error during initial OCR: ${e.message}")
                        null
                    }

                    var rideTypeTop: Int? = null
                    if (initialResult != null) {
                        val rideTypes = UberParser.getRideTypes()
                        for (block in initialResult.textBlocks) {
                            for (ride in rideTypes) {
                                if (block.text.contains(ride, ignoreCase = true)) {
                                    block.boundingBox?.let { box ->
                                        val currentTop = rideTypeTop // Capture rideTypeTop safely
//                                        Log.d("ScreenCaptureService", "Found ride type '${block.text}' at Y=${box.top}")

                                        if (currentTop == null || box.top < currentTop) {
                                            rideTypeTop = box.top
                                        }
                                    }
                                }
                            }
                        }
                    }
//                    Log.d("ScreenCaptureService", "Final rideTypeTop for cropping: $rideTypeTop")

                    // --- End Initial OCR Pass ---

                    // Crop the bitmap from the detected ride type region if available
                    val top = rideTypeTop
                    val finalBitmap = if (top != null && top in 0 until capturedBitmap.height) {
                        Bitmap.createBitmap(
                            capturedBitmap,
                            0,
                            top,
                            capturedBitmap.width,
                            capturedBitmap.height - top
                        )
                    } else {
                        capturedBitmap
                    }

                    // Run final OCR on the cropped bitmap
                    val finalImage = InputImage.fromBitmap(finalBitmap, 0)
                    val finalResult = try {
                        recognizer.process(finalImage).await()
                    } catch (e: Exception) {
//                        Log.e("ScreenCaptureService", "Error during final OCR: ${e.message}")
                        null
                    }
                    val finalText = finalResult?.text ?: ""
//                    Log.d("ScreenCaptureService", "Final OCR text: $finalText")
                    ocrCallback(finalText)
                } else {
                    Log.e("ScreenCaptureService", "Bitmap capture timed out or failed.")
                }

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

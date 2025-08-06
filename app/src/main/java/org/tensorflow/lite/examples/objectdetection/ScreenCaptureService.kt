package org.tensorflow.lite.examples.objectdetection

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import org.tensorflow.lite.examples.objectdetection.detectors.ObjectDetection
import com.ultralytics.yolo.predict.Predictor
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.io.File
import java.io.FileOutputStream
import android.os.Environment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.WindowManager
import android.graphics.PixelFormat
import android.view.Gravity
import android.graphics.Matrix
import android.view.View
import org.tensorflow.lite.examples.objectdetection.WarmColdIndicatorView

class ScreenCaptureService : Service() {
    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "screen_capture_channel"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
    }

    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val imageReaderHandler = Handler(Looper.getMainLooper())
    private val imageProcessor = Executors.newSingleThreadExecutor()

    private var onFrameListener: ((List<ObjectDetection>, Long, Int, Int) -> Unit)? = null
    private var warmColdUpdateListener: ((List<ObjectDetection>) -> Unit)? = null
    private var objectDetectorHelper: ObjectDetectorHelper? = null
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var overlayView: OverlayView? = null
    private var warmColdIndicatorView: WarmColdIndicatorView? = null
    private lateinit var windowManager: WindowManager

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        objectDetectorHelper = ObjectDetectorHelper(
            context = this,
            objectDetectorListener = object : ObjectDetectorHelper.DetectorListener {
                override fun onError(error: String) {
                    Log.e(TAG, "Detection error: $error")
                }

                override fun onResults(
                    results: List<ObjectDetection>,
                    inferenceTime: Long,
                    imageHeight: Int,
                    imageWidth: Int
                ) {
                    // Switch to the main thread to update the overlay view
                    Handler(Looper.getMainLooper()).post {
                        overlayView?.setResults(results, imageHeight, imageWidth)
                        // Update on-screen warm/cold indicator
                        warmColdIndicatorView?.updateScore(results)
                        warmColdUpdateListener?.invoke(results)
                        // Notify any frame listener (e.g., ScreenFragment) with inference time
                        onFrameListener?.invoke(results, inferenceTime, imageHeight, imageWidth)

                    }
                }
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")
        intent?.let {
            val resultCode = it.getIntExtra(EXTRA_RESULT_CODE, -1)
            val data = it.getParcelableExtra<Intent>(EXTRA_DATA)

            Log.d(TAG, "Result code: $resultCode, data: $data")

            // RESULT_OK is -1 when permission was granted. Use the constant instead of magic number.
            if (resultCode == Activity.RESULT_OK && data != null) {
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, data)
                Log.d(TAG, "MediaProjection created: $mediaProjection")
                startScreenCapture()
            } else {
                Log.e(TAG, "Invalid result code or data, stopping service")
                stopSelf()
            }
        } ?: run {
            Log.e(TAG, "No intent data, stopping service")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Screen capture service for object detection"
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Capture Active")
            .setContentText("Object detection is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startScreenCapture() {
        Log.d(TAG, "startScreenCapture called")
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        this.screenWidth = screenWidth
        this.screenHeight = screenHeight

        // Setup the system overlay
        setupOverlayView()

        Log.d(TAG, "Screen metrics: ${metrics.widthPixels}x${metrics.heightPixels}")

        imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            android.graphics.PixelFormat.RGBA_8888,
            2
        )

        Log.d(TAG, "ImageReader created")

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "screen-capture",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            imageReader!!.surface,
            null,
            null
        )

        Log.d(TAG, "VirtualDisplay created: $virtualDisplay")

        imageReader?.setOnImageAvailableListener({ reader ->
            Log.d(TAG, "Image available")

            // Off-load heavy processing to background thread without touching the UI
            imageProcessor.execute {

                val image = reader.acquireLatestImage()

                image?.use {
                    Log.d(TAG, "Processing image: ${it.width}x${it.height}")
                    val plane = it.planes[0]
                    val width = it.width
                    val height = it.height
                    val buffer: ByteBuffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val paddedBitmap = android.graphics.Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    paddedBitmap.copyPixelsFromBuffer(buffer)

                    // Calculate the size of the 4:3 crop that fits within the screen
                    val cropWidth: Int
                    val cropHeight: Int

                    if (width * 3 <= height * 4) {
                        // Width is the limiting factor (tall screen)
                        cropWidth = width
                        cropHeight = width * 4 / 3
                    } else {
                        // Height is the limiting factor (wide screen)
                        cropHeight = height
                        cropWidth = height * 3 / 4
                    }

                    // scale downm
                    val aspectRatioCrop = android.graphics.Bitmap.createBitmap(
                        paddedBitmap,
                        0,
                        300,
                        cropWidth,
                        cropHeight
                    )

                    // Rotate the image 90 degrees counter-clockwise
                    val matrix = Matrix()
                    matrix.postRotate(-90f)
                    val rotatedBitmap = android.graphics.Bitmap.createBitmap(
                        aspectRatioCrop, 0, 0, aspectRatioCrop.width, aspectRatioCrop.height, matrix, true
                    )

                    // Scale down to a size appropriate for the model
                    // Calculate based on specific ratios: height/3.75 and width/2.25
                    val scaledWidth = (cropWidth / 2.25).toInt()
                    val scaledHeight = (cropHeight / 3.75).toInt()

                    val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(
                        rotatedBitmap, scaledWidth, scaledHeight, true
                    )

                    // Pass the scaled bitmap to the detector
                    // saveBitmapForDebugging(scaledBitmap)

                    Log.d(TAG, "Calling object detection on scaled bitmap: ${scaledBitmap.width}x${scaledBitmap.height}")
                    objectDetectorHelper?.detect(scaledBitmap, 90)

                    paddedBitmap.recycle()
                    aspectRatioCrop.recycle()
                    rotatedBitmap.recycle()
                    scaledBitmap.recycle()
                } ?: Log.w(TAG, "Failed to acquire image")
            }
        }, imageReaderHandler)
    }

    private fun setupOverlayView() {
        overlayView = OverlayView(this, null)

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        // Calculate overlay dimensions based on screen size with specific ratios
        val overlayWidth = screenWidth
        val overlayHeight = (screenHeight / 1.7).toInt()
        val overlayY = (screenHeight / 8).toInt()

        // Bounding box overlay
        val overlayParams = WindowManager.LayoutParams(
            overlayWidth,
            overlayHeight,
            0,
            overlayY,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager.addView(overlayView, overlayParams)

        // Warm/Cold indicator overlay (bottom center)
        warmColdIndicatorView = WarmColdIndicatorView(this, null)
        val indicatorHeight = (screenHeight / 10.9).toInt()  // Approximately 220 for 2400 height
        val indicatorParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            indicatorHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (screenHeight / 48).toInt()  // Approximately 50 for 2400 height
        }
        windowManager.addView(warmColdIndicatorView, indicatorParams)
    }

    private fun saveBitmapForDebugging(bitmap: android.graphics.Bitmap) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "yolo_input_$timestamp.png"

            // For Android 10+ we don't need external storage permission to write to app-specific directories
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val imageFile = File(storageDir, filename)

            FileOutputStream(imageFile).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }

            Log.d(TAG, "Saved debug image to: ${imageFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving debug image", e)
        }
    }

    fun setOnFrameListener(listener: (List<ObjectDetection>, Long, Int, Int) -> Unit) {
        Log.d(TAG, "Setting frame listener")
        onFrameListener = listener
    }

    fun getObjectDetectorHelper(): ObjectDetectorHelper? {
        return objectDetectorHelper
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let { windowManager.removeView(it) }
        warmColdIndicatorView?.let { windowManager.removeView(it) }
        imageReader?.close()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageProcessor.shutdown()
    }
}
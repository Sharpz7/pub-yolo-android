package org.tensorflow.lite.examples.objectdetection.detectors

import android.content.Context
import android.graphics.RectF
import com.ultralytics.yolo.ImageProcessing
import com.ultralytics.yolo.models.LocalYoloModel
import com.ultralytics.yolo.predict.detect.DetectedObject
import com.ultralytics.yolo.predict.detect.TfliteDetector
import org.tensorflow.lite.support.image.TensorImage
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import android.os.Environment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class YoloDetector(
    var confidenceThreshold: Float = 0.5f,
    var iouThreshold: Float = 0.3f,
    var numThreads: Int = 2,
    var maxResults: Int = 3,
    var currentDelegate: Int = 0,
    var currentModel: Int = 0,
    val context: Context
): ObjectDetector {

    private var yolo: TfliteDetector
    private var ip: ImageProcessing
    private val TAG = "YoloDetector"

    init {
        Log.d(TAG, "YoloDetector init started")

        yolo = TfliteDetector(context)
        yolo.setIouThreshold(iouThreshold)
        yolo.setConfidenceThreshold(confidenceThreshold)

        // val modelPath = "YOLO11n-catsdogs_float32.tflite"
        // val metadataPath = "metadata-catsdogs.yaml"
        val modelPath = "yolo.tflite"
        val metadataPath = "metadata.yaml"

        val config = LocalYoloModel(
            "detect",
            "tflite",
            modelPath,
            metadataPath,
        )

        val useGPU = currentDelegate == 0
        Log.d(TAG, "Loading YOLO model with useGPU: $useGPU")
        yolo.loadModel(
            config,
            useGPU
        )
        Log.d(TAG, "YOLO model loaded successfully")

        ip = ImageProcessing()

    }

    override fun detect(image: TensorImage, imageRotation: Int): DetectionResult  {
        Log.d(TAG, "YoloDetector detect called with image: ${image.bitmap.width}x${image.bitmap.height}, rotation: $imageRotation")

        val bitmap = image.bitmap

        val ppImage = yolo.preprocess(bitmap)
        Log.d(TAG, "Preprocessed image: ${ppImage.width}x${ppImage.height}")

        // Save the preprocessed image for debugging
        // saveBitmapForDebugging(ppImage)

        val results = yolo.predict(ppImage)
        Log.d(TAG, "YOLO prediction completed, found ${results.size} objects")

        val detections = ArrayList<ObjectDetection>()

        // ASPECT_RATIO = 4:3
        // => imgW = imgH * 3/4
        var imgH: Int
        var imgW: Int
        if (imageRotation == 90 || imageRotation == 270) {
            imgH = ppImage.height
            imgW = imgH * 3 / 4
        }
        else {
            imgW = ppImage.width
            imgH = imgW * 3 / 4

        }


        for (result: DetectedObject in results) {
            val category = Category(
                result.label,
                result.confidence,
            )
            // Bounding box is already normalized, pass it directly.
            val yoloBox = result.boundingBox

            val left = yoloBox.left * imgW
            val top = yoloBox.top * imgH
            val right = yoloBox.right * imgW
            val bottom = yoloBox.bottom * imgH

            val bbox = RectF(
                left,
                top,
                right,
                bottom
            )
            val detection = ObjectDetection(
                bbox,
                category
            )
            detections.add(detection)
        }

        val ret = DetectionResult(ppImage, detections)
        ret.info = yolo.stats
        return ret

    }

    private fun saveBitmapForDebugging(bitmap: android.graphics.Bitmap) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "yolo_detector_input_$timestamp.png"

            val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val imageFile = File(storageDir, filename)

            FileOutputStream(imageFile).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }

            Log.d(TAG, "Saved YoloDetector input image to: ${imageFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving debug image", e)
        }
    }

}
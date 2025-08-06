package org.tensorflow.lite.examples.objectdetection

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import org.tensorflow.lite.examples.objectdetection.detectors.ObjectDetection
import kotlin.math.min

class WarmColdIndicatorView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var gradientPaint = Paint()
    private var markerPaint = Paint()
    private var backgroundPaint = Paint()
    private var textPaint = Paint()

    private var currentScore: Float = 0f
    private var maxScore: Float = 7f // Maximum possible score for scaling
    private var barHeight: Float = 120f
    private var markerWidth: Float = 24f  // Wider marker for better visibility
    private var markerHeight: Float = 60f

    private var targetScore: Float = 0f
    private val maxSpeedPerFrame: Float = 0.1f  // Max change per frame

    private val TAG = "WarmColdIndicatorView"

    companion object {
        private const val MARGIN_HORIZONTAL = 40f
        private const val MARGIN_TOP = 20f
    }

    init {
        initPaints()
    }

    private fun initPaints() {
        // Background paint for the bar
        backgroundPaint.color = Color.BLACK
        backgroundPaint.style = Paint.Style.FILL
        backgroundPaint.alpha = 180  // More opaque background for better readability

        // Marker paint
        markerPaint.color = Color.WHITE
        markerPaint.style = Paint.Style.FILL
        markerPaint.strokeWidth = 4f

        // Text paint for labels
        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 50f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.DEFAULT_BOLD
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setupGradient()
    }

    private fun setupGradient() {
        val contentPadding = MARGIN_HORIZONTAL
        val barLeft = contentPadding
        val barRight = width - contentPadding

        // Create blue to orange gradient
        val gradient = LinearGradient(
            barLeft, 0f, barRight, 0f,
            intArrayOf(
                Color.parseColor("#2196F3"), // Blue
                Color.parseColor("#03A9F4"), // Light Blue
                Color.parseColor("#00BCD4"), // Cyan
                Color.parseColor("#4CAF50"), // Green
                Color.parseColor("#8BC34A"), // Light Green
                Color.parseColor("#CDDC39"), // Lime
                Color.parseColor("#FFEB3B"), // Yellow
                Color.parseColor("#FFC107"), // Amber
                Color.parseColor("#FF9800"), // Orange
                Color.parseColor("#FF5722")  // Deep Orange
            ),
            null,
            Shader.TileMode.CLAMP
        )

        gradientPaint.shader = gradient
        gradientPaint.style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val contentPadding = MARGIN_HORIZONTAL
        val barLeft = contentPadding
        val barRight = width - contentPadding
        val barTop = MARGIN_TOP
        val barBottom = barTop + barHeight

        // Draw full-width background; leave internal padding for contents
        val textAreaHeight = textPaint.textSize + 60f  // extra padding for labels
        val fullBackgroundRect = RectF(0f, barTop, width.toFloat(), barBottom + textAreaHeight)
        canvas.drawRoundRect(fullBackgroundRect, 8f, 8f, backgroundPaint)

        // Draw gradient bar
        val gradientRect = RectF(barLeft, barTop, barRight, barBottom)
        canvas.drawRoundRect(gradientRect, 8f, 8f, gradientPaint)

        // Calculate marker position based on current score
        // Smoothly move currentScore toward targetScore
        val diff = targetScore - currentScore
        if (kotlin.math.abs(diff) > maxSpeedPerFrame) {
            currentScore += kotlin.math.sign(diff) * maxSpeedPerFrame
            // Continue animating until we reach target
            postInvalidateOnAnimation()
        } else {
            currentScore = targetScore
        }

        val scoreRatio = min(currentScore / maxScore, 1f)
        val markerX = barLeft + (barRight - barLeft) * scoreRatio

        // Draw marker
        val markerTop = barTop - (markerHeight - barHeight) / 2
        val markerBottom = markerTop + markerHeight
        val markerLeft = markerX - markerWidth / 2
        val markerRight = markerX + markerWidth / 2

        canvas.drawRoundRect(
            RectF(markerLeft, markerTop, markerRight, markerBottom),
            4f, 4f, markerPaint
        )

        // Draw labels
        val coldY = barBottom + 40f
        val hotY = coldY

        canvas.drawText("COLD", barLeft + 40f, coldY, textPaint)
        canvas.drawText("HOT", barRight - 40f, hotY, textPaint)

        // Draw score text
        val scoreText = String.format("Score: %.2f", targetScore)
        canvas.drawText(scoreText, width / 2f, coldY, textPaint)
    }

    fun updateScore(detections: List<ObjectDetection>) {
        // Calculate net score based on detections.
        var totalScore = 0f

        for (detection in detections) {
            val confidence = detection.category.confidence
            val label = detection.category.label.lowercase()

            // If the detection is a "bad_frame", subtract its confidence.
            totalScore += if (label == "bad_frame") {
                -confidence
            } else {
                confidence
            }
        }

        // Apply scaling to make the indicator more responsive.
        val scoreMultiplier = 2.0f
        targetScore = (totalScore * scoreMultiplier).coerceAtLeast(0f)

        // If above the max score, update the max score.
        if (targetScore > maxScore) {
            maxScore = targetScore
        }

        Log.d(TAG, "Updated score target: $targetScore (from ${detections.size} detections)")

        // Trigger redraw
        invalidate()
    }

    fun resetScore() {
        currentScore = 0f
        invalidate()
    }
}
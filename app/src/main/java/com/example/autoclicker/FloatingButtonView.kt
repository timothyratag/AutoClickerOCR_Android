package com.example.autoclicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Floating crosshair button component (pure visual)
 *
 * Visual style: Circular border + center number + crosshair (short lines in 4 directions: top, bottom, left, right) + yellow anchor
 * Click anchor precisely positioned at the visual geometric center.
 *
 * Touch interaction is driven externally by setOnTouchListener (most reliable for WindowManager overlay scenarios).
 */
class FloatingButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ==================== Configurable Properties ====================

    var number: Int = 1
        set(value) { field = value; invalidate() }

    var ringColor: Int = 0xFF333333.toInt()
        set(value) { field = value; ringPaint.color = value; invalidate() }

    var fillColor: Int = 0x00FFFFFF.toInt()
        set(value) { field = value; fillPaint.color = value; invalidate() }

    var textColor: Int = 0xFF666666.toInt()
        set(value) {
            field = value
            textPaint.color = value
            crosshairPaint.color = value
            invalidate()
        }

    var activeColor: Int = 0xFFFF4444.toInt()

    var isActive: Boolean = false
        set(value) {
            field = value
            val c = if (value) activeColor else ringColor
            val tc = if (value) activeColor else textColor
            ringPaint.color = c
            textPaint.color = tc
            crosshairPaint.color = tc
            invalidate()
        }

    // ==================== Dimensions ====================

    private val density: Float = context.resources.displayMetrics.density

    private val ringStrokeWidthPx = 2f * density
    private val crosshairLengthPx = 12f * density
    private val crosshairWidthPx = 1.5f * density
    private val textSizePx = 17f * density
    private val anchorRadiusPx = 3f * density

    // ==================== Paint ====================

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ringColor
        strokeWidth = ringStrokeWidthPx
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = textColor
        textSize = textSizePx
        textAlign = Paint.Align.CENTER
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = textColor
        strokeWidth = crosshairWidthPx
        strokeCap = Paint.Cap.BUTT
    }

    private val anchorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFF00.toInt()
    }

    // ==================== Drawing ====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - ringStrokeWidthPx / 2f

        // Fill
        canvas.drawCircle(cx, cy, r, fillPaint)
        // Circle border
        canvas.drawCircle(cx, cy, r / 2f, ringPaint)

        // Crosshairs (extended from number edge to inside the ring)
        val innerGap = textSizePx * 0.35f
        canvas.drawLine(cx, cy - innerGap, cx, cy - r + ringStrokeWidthPx, crosshairPaint)
        canvas.drawLine(cx, cy + innerGap, cx, cy + r - ringStrokeWidthPx, crosshairPaint)
        canvas.drawLine(cx - innerGap, cy, cx - r + ringStrokeWidthPx, cy, crosshairPaint)
        canvas.drawLine(cx + innerGap, cy, cx + r - ringStrokeWidthPx, cy, crosshairPaint)

        // Center number
        canvas.drawText(number.toString(), cx, cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // Click anchor (yellow dot)
        canvas.drawCircle(cx, cy, anchorRadiusPx, anchorPaint)
    }
}

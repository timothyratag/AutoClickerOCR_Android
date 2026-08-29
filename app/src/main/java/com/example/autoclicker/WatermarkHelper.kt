package com.example.autoclicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Watermark utility class: Overlay diagonal watermark on Activity root layout
 *
 * ====== Watermark Configuration Area ======
 * Modify the following two constants to control watermark behavior:
 *   ENABLED  - true to enable watermark, false to disable watermark
 *   TEXT     - Watermark text content
 * ========================================
 */
object WatermarkHelper {

    /** Watermark switch: true=enabled, false=disabled */
    private const val ENABLED = true

    /** Watermark text content */
    private const val TEXT = "pandie"

    fun shouldApply(): Boolean = ENABLED

    fun getWatermarkText(): String = TEXT // Wait, let's fix TEXT type below in python code

    /**
     * Apply watermark to Activity, must be called after setContentView
     */
    fun apply(activity: androidx.appcompat.app.AppCompatActivity) {
        if (!ENABLED) return

        val rootView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        // Remove old watermark
        val old = rootView.findViewWithTag<WatermarkView>("watermark_overlay")
        old?.let { rootView.removeView(it) }

        val watermark = WatermarkView(activity, TEXT).apply {
            tag = "watermark_overlay"
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        watermark.isClickable = false
        rootView.addView(watermark)
    }

    /**
     * Remove watermark from current Activity
     */
    fun remove(activity: androidx.appcompat.app.AppCompatActivity) {
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val old = rootView.findViewWithTag<WatermarkView>("watermark_overlay")
        old?.let { rootView.removeView(it) }
    }
}

/**
 * Custom watermark View, draws 45° diagonal repeating text
 */
class WatermarkView @JvmOverloads constructor(
    context: Context,
    private val watermarkText: String = "Watermark",
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A000000") // 10% opaque black
        textSize = 36f
        textAlign = Paint.Align.LEFT
    }

    private val spacingX = 300
    private val spacingY = 200
    private val angle = -30f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.save()
        canvas.rotate(angle, width / 2f, height / 2f)

        val diagonal = Math.sqrt((width * width + height * height).toDouble()).toFloat()
        val startX = -diagonal
        val startY = -diagonal
        val endX = diagonal * 2
        val endY = diagonal * 2

        var y = startY
        while (y < endY) {
            var x = startX
            while (x < endX) {
                canvas.drawText(watermarkText, x, y, paint)
                x += spacingX
            }
            y += spacingY
        }

        canvas.restore()
    }
}

package com.example.autoclicker

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * Screen capture foreground service
 * Responsible for MediaProjection management, event-driven screen capture, OCR recognition, and auto-clicking
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCapture"

        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 1001

        // Intent parameter keys
        const val EXTRA_TARGET_TEXT = "target_text"
        const val EXTRA_SCAN_INTERVAL = "scan_interval"
        const val EXTRA_CLICK_COUNT = "click_count"
        const val EXTRA_EXACT_MATCH = "exact_match"
        const val EXTRA_CLICK_INTERVAL = "click_interval"

        // Action types
        const val ACTION_START = "action_start"
        const val ACTION_STOP = "action_stop"

        // MediaProjection auth data must be passed via static variables
        @Volatile
        var projectionResultCode: Int = -1
        var projectionResultData: Intent? = null

        // Status queries
        @Volatile
        var isRunning = false
            private set

        @Volatile
        var lastOcrText: String = ""
            private set

        @Volatile
        var lastMatchResult: OcrClickEngine.MatchResult? = null
            private set

        @Volatile
        var ocrClickCount: Long = 0
            private set

        @Volatile
        var ocrScanCount: Long = 0
            private set

        // ===== Diagnostic log =====
        @Volatile
        var diagLog: String = ""
            private set

        private fun diag(msg: String) {
            Log.d(TAG, msg)
            val ts = System.currentTimeMillis() % 100000
            diagLog = "[$ts] $msg
" + diagLog
            // Keep recent 30 entries
            val lines = diagLog.split("
")
            if (lines.size > 31) {
                diagLog = lines.take(30).joinToString("
")
            }
        }

        fun clearDiagLog() {
            diagLog = ""
        }

        fun stopCapture() {
            isRunning = false
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 1

    private var targetText = ""
    private var scanInterval = 500L
    private var clickCount = 1L
    private var exactMatch = false
    private var clickInterval = 100L

    private var clickedCount = 0L
    private var isClickingAfterMatch = false
    private var isProcessing = false

    private var lastProcessTime = 0L
    private var frameAvailableCount = 0L
    private var frameNullCount = 0L
    private var bitmapNullCount = 0L

    // ===== Floating stop button =====
    private var floatingView: View? = null
    private var windowManager: WindowManager? = null

    private val handler = Handler(Looper.getMainLooper())
    private var clickRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        diag("Service.onCreate()")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        diag("onStartCommand action=${intent?.action}")

        if (intent?.action == ACTION_STOP) {
            diag("→ ACTION_STOP, stopping self")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START) {
            val resultCode = projectionResultCode
            val resultData = projectionResultData
            projectionResultData = null

            targetText = intent.getStringExtra(EXTRA_TARGET_TEXT) ?: ""
            scanInterval = intent.getLongExtra(EXTRA_SCAN_INTERVAL, 500L).coerceIn(200L, 10000L)
            clickCount = intent.getLongExtra(EXTRA_CLICK_COUNT, 1L).coerceIn(1L, 99999L)
            exactMatch = intent.getBooleanExtra(EXTRA_EXACT_MATCH, false)
            clickInterval = intent.getLongExtra(EXTRA_CLICK_INTERVAL, 100L).coerceIn(50L, 60000L)

            diag("params: target="$targetText" interval=${scanInterval}ms count=$clickCount exact=$exactMatch")
            diag("projection: resultCode=$resultCode data=${resultData != null}")

            // Start foreground notification (must be before getMediaProjection)
            val notification = buildNotification("OCR recognition running...")
            startForeground(NOTIFICATION_ID, notification)
            diag("startForeground() OK")

            if (resultCode == Activity.RESULT_OK && resultData != null) {
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

                if (mediaProjection != null) {
                    diag("✅ getMediaProjection success!")
                    setupVirtualDisplay()
                    isRunning = true
                    showFloatingStopButton()
                    diag("✅ Service started, waiting for frames...")
                } else {
                    diag("❌ getMediaProjection returned null! resultCode=$resultCode")
                    stopSelf()
                }
            } else {
                diag("❌ Missing projection authorization: resultCode=$resultCode, data=${resultData != null}")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        diag("Service.onDestroy()")
        isRunning = false
        stopClickLoop()
        releaseVirtualDisplay()
        removeFloatingStopButton()
        mediaProjection?.stop()
        mediaProjection = null
        ocrScanCount = 0
        ocrClickCount = 0
        lastOcrText = ""
        lastMatchResult = null
        frameAvailableCount = 0
        frameNullCount = 0
        bitmapNullCount = 0
    }

    // ==================== Floating Stop Button ====================

    private fun showFloatingStopButton() {
        if (floatingView != null) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val container = FrameLayout(this)
        val btn = TextView(this).apply {
            text = "■ Stop"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(24, 12, 24, 12)
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
        }

        val bg = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xDDFF4444.toInt())
            cornerRadius = 28f
        }
        btn.background = bg

        val wrap = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        wrap.gravity = Gravity.CENTER
        container.addView(btn, wrap)
        container.setPadding(4, 4, 4, 4)

        // Click to stop
        btn.setOnClickListener {
            diag("Floating button → Stop")
            stopCapture()
            val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            startService(stopIntent)
        }

        // Drag support
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = (container.tag as? IntArray)?.get(0) ?: 0
                    initialY = (container.tag as? IntArray)?.get(1) ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                    val params = container.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    container.tag = intArrayOf(params.x, params.y)
                    windowManager?.updateViewLayout(container, params)
                }
            }
            isDragging
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 100
        }

        container.tag = intArrayOf(params.x, params.y)
        windowManager?.addView(container, params)
        floatingView = container
        diag("✅ Floating stop button shown")
    }

    private fun removeFloatingStopButton() {
        floatingView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
            floatingView = null
            diag("Floating stop button removed")
        }
    }

    // ==================== Virtual Display ====================

    private fun setupVirtualDisplay() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        diag("screen: ${screenWidth}x${screenHeight} density=$screenDensity")

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        diag("ImageReader created: ${screenWidth}x${screenHeight} format=RGBA_8888")

        imageReader?.setOnImageAvailableListener({ reader ->
            frameAvailableCount++
            if (frameAvailableCount <= 5) {
                diag("Frame arrived #${frameAvailableCount}")
            }

            if (!isRunning) {
                val img = reader.acquireLatestImage()
                img?.close()
                return@setOnImageAvailableListener
            }

            val now = System.currentTimeMillis()

            if (now - lastProcessTime >= scanInterval && !isProcessing && !isClickingAfterMatch) {
                lastProcessTime = now
                processFrame(reader)
            } else {
                val img = reader.acquireLatestImage()
                img?.close()
            }
        }, handler)

        val surface = imageReader?.surface
        diag("ImageReader.surface: ${surface != null}")

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, handler
        )

        if (virtualDisplay != null) {
            diag("✅ VirtualDisplay created successfully")
        } else {
            diag("❌ VirtualDisplay creation failed! mediaProjection=${mediaProjection != null}")
        }
    }

    private fun releaseVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
    }

    // ==================== Frame Processing ====================

    private fun processFrame(reader: ImageReader) {
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) {
                frameNullCount++
                if (frameNullCount <= 3) {
                    diag("⚠️ acquireLatestImage returned null (${frameNullCount}th time)")
                }
                return
            }

            val imgW = image.width
            val imgH = image.height
            val planes = image.planes
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride

            val bitmap = imageToBitmap(image)
            image.close()
            image = null

            if (bitmap == null) {
                bitmapNullCount++
                if (bitmapNullCount <= 3) {
                    diag("⚠️ imageToBitmap returned null (${bitmapNullCount}th time) imgSize=${imgW}x${imgH} pixelStride=$pixelStride rowStride=$rowStride")
                }
                return
            }

            ocrScanCount++

            if (ocrScanCount <= 3) {
                diag("Frame process #${ocrScanCount}: bitmap=${bitmap.width}x${bitmap.height}")
            }

            isProcessing = true

            OcrClickEngine.recognizeAndMatch(bitmap, targetText, exactMatch) { result ->
                isProcessing = false
                lastOcrText = result.allText
                lastMatchResult = result

                if (ocrScanCount <= 5 || result.matched) {
                    val textPreview = if (result.allText.length > 80) result.allText.take(80) + "..." else result.allText
                    diag("OCR #${ocrScanCount}: matched=${result.matched} textBlocks=${OcrClickEngine.lastBlockCount} text="${textPreview}"")
                }

                if (result.matched && !isClickingAfterMatch) {
                    diag("🎯 Match successful! center=(${result.centerX},${result.centerY}) rect=${result.boundingRect}")
                    onTextMatched(result)
                }

                updateNotification(
                    if (result.matched) "Found: "${result.targetText}""
                    else "Scanning... (scan #${ocrScanCount})"
                )
            }
        } catch (e: Exception) {
            isProcessing = false
            diag("❌ processFrame exception: ${e.message}")
            e.printStackTrace()
        } finally {
            image?.close()
        }
    }

    /**
     * Convert Image to Bitmap, handle row padding
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmapWidth = screenWidth + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding != 0) {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "imageToBitmap failed", e)
            null
        }
    }

    // ==================== Click After Text Match ====================

    private fun onTextMatched(result: OcrClickEngine.MatchResult) {
        isClickingAfterMatch = true
        clickedCount = 0

        clickRunnable = object : Runnable {
            override fun run() {
                if (!isRunning || clickedCount >= clickCount) {
                    diag("Clicking finished: clicked=$clickedCount/$clickCount")
                    stopClickLoop()
                    return
                }

                ClickAccessibilityService.performClickAt(
                    result.centerX.toFloat(),
                    result.centerY.toFloat()
                )

                clickedCount++
                ocrClickCount++

                handler.postDelayed(this, clickInterval)
            }
        }
        handler.post(clickRunnable!!)
    }

    private fun stopClickLoop() {
        clickRunnable?.let { handler.removeCallbacks(it) }
        clickRunnable = null
        isClickingAfterMatch = false
    }

    // ==================== Notification ====================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OCR Screen Recognition Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "OCR text recognition and auto-clicking service notification"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoClicker OCR")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

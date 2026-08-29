package com.example.autoclicker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class DaMaiTicketActivity : AppCompatActivity() {

    // Title bar
    private lateinit var btnBack: ImageView

    // Session configuration
    private lateinit var etSessions: EditText
    private lateinit var btnAddSession: Button

    // Price tier configuration
    private lateinit var etPrices: EditText
    private lateinit var btnAddPrice: Button

    // Viewer configuration
    private lateinit var etViewers: EditText
    private lateinit var btnAddViewer: Button

    // Advanced parameters
    private lateinit var etRetryInterval: EditText
    private lateinit var etMaxRetries: EditText

    // Action buttons
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    // Status display
    private lateinit var tvState: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvAttemptCount: TextView

    // Tag containers
    private lateinit var layoutSessionTags: LinearLayout
    private lateinit var layoutPriceTags: LinearLayout
    private lateinit var layoutViewerTags: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var statusRunnable: Runnable? = null

    // Configuration data
    private val sessions = mutableListOf<String>()
    private val prices = mutableListOf<String>()
    private val viewers = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_damai_ticket)

        initViews()
        loadSettings()
        setupListeners()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)

        etSessions = findViewById(R.id.etSessions)
        btnAddSession = findViewById(R.id.btnAddSession)
        layoutSessionTags = findViewById(R.id.layoutSessionTags)

        etPrices = findViewById(R.id.etPrices)
        btnAddPrice = findViewById(R.id.btnAddPrice)
        layoutPriceTags = findViewById(R.id.layoutPriceTags)

        etViewers = findViewById(R.id.etViewers)
        btnAddViewer = findViewById(R.id.btnAddViewer)
        layoutViewerTags = findViewById(R.id.layoutViewerTags)

        etRetryInterval = findViewById(R.id.etRetryInterval)
        etMaxRetries = findViewById(R.id.etMaxRetries)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        tvState = findViewById(R.id.tvState)
        tvStatus = findViewById(R.id.tvStatus)
        tvAttemptCount = findViewById(R.id.tvAttemptCount)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        // Add session
        btnAddSession.setOnClickListener {
            val text = etSessions.text.toString().trim()
            if (text.isNotEmpty() && !sessions.contains(text)) {
                sessions.add(text)
                etSessions.text.clear()
                refreshTags(layoutSessionTags, sessions, null)
            }
        }

        // Add price
        btnAddPrice.setOnClickListener {
            val text = etPrices.text.toString().trim()
            if (text.isNotEmpty() && !prices.contains(text)) {
                prices.add(text)
                etPrices.text.clear()
                refreshTags(layoutPriceTags, prices, null)
            }
        }

        // Add viewer
        btnAddViewer.setOnClickListener {
            val text = etViewers.text.toString().trim()
            if (text.isNotEmpty() && !viewers.contains(text)) {
                viewers.add(text)
                etViewers.text.clear()
                refreshTags(layoutViewerTags, viewers, null)
            }
        }

        // Start ticket grab
        btnStart.setOnClickListener {
            if (!checkPermissions()) return@setOnClickListener
            if (sessions.isEmpty()) {
                Toast.makeText(this, "Please add at least one session", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (prices.isEmpty()) {
                Toast.makeText(this, "Please add at least one price tier", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (viewers.isEmpty()) {
                Toast.makeText(this, "Please add at least one viewer", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            saveSettings()
            startTicketGrab()
        }

        // Stop ticket grab
        btnStop.setOnClickListener {
            ClickAccessibilityService.stopTicketGrab()
            updateUI()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        startStatusUpdate()
        WatermarkHelper.apply(this)
    }

    override fun onPause() {
        super.onPause()
        stopStatusUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStatusUpdate()
    }

    private fun checkPermissions(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please enable floating window permission first", Toast.LENGTH_SHORT).show()
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
            return false
        }
        if (!ClickAccessibilityService.isRunning()) {
            Toast.makeText(this, "Please enable Accessibility Service first", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return false
        }
        return true
    }

    private fun startTicketGrab() {
        val cfg = TicketGrabEngine.Config(
            sessions = sessions.toList(),
            prices = prices.toList(),
            viewers = viewers.toList(),
            retryInterval = etRetryInterval.text.toString().trim().toLongOrNull()?.coerceIn(50L, 5000L) ?: 300L,
            maxRetries = etMaxRetries.text.toString().trim().toIntOrNull()?.coerceIn(1, 9999) ?: 200
        )

        val engine = ClickAccessibilityService.getTicketGrabEngine()
        if (engine == null) {
            Toast.makeText(this, "Accessibility service not ready", Toast.LENGTH_SHORT).show()
            return
        }

        engine.onStateChanged = { state, msg ->
            handler.post {
                tvState.text = when (state) {
                    TicketGrabEngine.State.IDLE -> "Idle"
                    TicketGrabEngine.State.ENTERING_PAGE -> "Entering ticket page"
                    TicketGrabEngine.State.SELECTING_SESSION -> "Selecting session"
                    TicketGrabEngine.State.SELECTING_PRICE -> "Selecting price"
                    TicketGrabEngine.State.SELECTING_VIEWERS -> "Selecting viewers"
                    TicketGrabEngine.State.SUBMITTING_ORDER -> "Submitting order"
                    TicketGrabEngine.State.DONE -> "Done"
                }
                tvStatus.text = msg
            }
        }

        ClickAccessibilityService.startTicketGrab(cfg)
        updateUI()

        // Switch to Damai APP
        val pm = packageManager
        val launchIntent = pm.getLaunchIntentForPackage("cn.damai")
        if (launchIntent != null) {
            startActivity(launchIntent)
            Toast.makeText(this, "Damai APP launched, grabbing tickets...", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Damai APP not detected, please open manually", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateUI() {
        val engine = ClickAccessibilityService.getTicketGrabEngine()
        val isRunning = engine?.isRunning() == true

        if (isRunning) {
            btnStart.isEnabled = false
            btnStop.isEnabled = true
            val state = engine!!.state
            tvState.text = when (state) {
                TicketGrabEngine.State.IDLE -> "Idle"
                TicketGrabEngine.State.ENTERING_PAGE -> "Entering ticket page"
                TicketGrabEngine.State.SELECTING_SESSION -> "Selecting session"
                TicketGrabEngine.State.SELECTING_PRICE -> "Selecting price"
                TicketGrabEngine.State.SELECTING_VIEWERS -> "Selecting viewers"
                TicketGrabEngine.State.SUBMITTING_ORDER -> "Submitting order"
                TicketGrabEngine.State.DONE -> "Done"
            }
            tvStatus.text = engine.statusMessage
            tvAttemptCount.text = "Attempts: ${engine.attemptCount}"
            tvAttemptCount.visibility = View.VISIBLE
        } else {
            btnStart.isEnabled = true
            btnStop.isEnabled = false
            if (engine?.state == TicketGrabEngine.State.DONE) {
                tvState.text = "Done"
                tvStatus.text = engine.statusMessage
            } else {
                tvState.text = "Idle"
                tvStatus.text = "Ready"
            }
            tvAttemptCount.visibility = View.GONE
        }
    }

    /**
     * Refresh tag list
     */
    private fun refreshTags(container: LinearLayout, items: List<String>, onRemove: ((String) -> Unit)?) {
        container.removeAllViews()
        for (item in items) {
            val tagView = layoutInflater.inflate(R.layout.item_tag, container, false)
            val tvTag = tagView.findViewById<TextView>(R.id.tvTag)
            val btnRemove = tagView.findViewById<ImageView>(R.id.btnRemoveTag)
            tvTag.text = item

            btnRemove.setOnClickListener {
                if (container == layoutSessionTags) {
                    sessions.remove(item)
                    refreshTags(layoutSessionTags, sessions, null)
                } else if (container == layoutPriceTags) {
                    prices.remove(item)
                    refreshTags(layoutPriceTags, prices, null)
                } else if (container == layoutViewerTags) {
                    viewers.remove(item)
                    refreshTags(layoutViewerTags, viewers, null)
                }
            }

            container.addView(tagView)
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("damai_ticket_prefs", Context.MODE_PRIVATE)

        // Sessions
        val sessionStr = prefs.getString("sessions", "") ?: ""
        if (sessionStr.isNotEmpty()) {
            sessions.clear()
            sessions.addAll(sessionStr.split(",").filter { it.isNotEmpty() })
        }

        // Prices
        val priceStr = prefs.getString("prices", "") ?: ""
        if (priceStr.isNotEmpty()) {
            prices.clear()
            prices.addAll(priceStr.split(",").filter { it.isNotEmpty() })
        }

        // Viewers
        val viewerStr = prefs.getString("viewers", "") ?: ""
        if (viewerStr.isNotEmpty()) {
            viewers.clear()
            viewers.addAll(viewerStr.split(",").filter { it.isNotEmpty() })
        }

        // Advanced parameters
        etRetryInterval.setText(prefs.getLong("retry_interval", 300L).toString())
        etMaxRetries.setText(prefs.getInt("max_retries", 200).toString())

        // Refresh tags
        refreshTags(layoutSessionTags, sessions, null)
        refreshTags(layoutPriceTags, prices, null)
        refreshTags(layoutViewerTags, viewers, null)
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("damai_ticket_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("sessions", sessions.joinToString(","))
            .putString("prices", prices.joinToString(","))
            .putString("viewers", viewers.joinToString(","))
            .putLong("retry_interval", etRetryInterval.text.toString().trim().toLongOrNull() ?: 300L)
            .putInt("max_retries", etMaxRetries.text.toString().trim().toIntOrNull() ?: 200)
            .apply()
    }

    private fun startStatusUpdate() {
        stopStatusUpdate()
        statusRunnable = object : Runnable {
            override fun run() {
                updateUI()
                handler.postDelayed(this, 500)
            }
        }
        handler.post(statusRunnable!!)
    }

    private fun stopStatusUpdate() {
        statusRunnable?.let { handler.removeCallbacks(it) }
        statusRunnable = null
    }
}

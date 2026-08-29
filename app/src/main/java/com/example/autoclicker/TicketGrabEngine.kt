package com.example.autoclicker

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Ticket Grab Engine — Operates APP controls directly based on Accessibility Service node tree
 *
 * State Machine: IDLE → ENTERING_PAGE → SELECTING_SESSION → SELECTING_PRICE
 *               → SELECTING_VIEWERS → SUBMITTING_ORDER → DONE
 */
class TicketGrabEngine(private val service: AccessibilityService) {

    enum class State {
        IDLE,               // Idle, not started
        ENTERING_PAGE,      // Continuously tap "Immediate" / "Buy Tickets" to enter ticket selection page
        SELECTING_SESSION,  // Select session
        SELECTING_PRICE,    // Select price tier
        SELECTING_VIEWERS,  // Check viewers
        SUBMITTING_ORDER,   // Submit order
        DONE                // Done
    }

    data class Config(
        val sessions: List<String>,     // Session keyword list, e.g. ["05-31", "06-01"]
        val prices: List<String>,       // Price tier keyword list, e.g. ["1555", "355"]
        val viewers: List<String>,      // Viewer name keyword list, e.g. ["John Doe", "Jane Smith"]
        val retryInterval: Long = 300L, // Operation interval per round (ms)
        val maxRetries: Int = 200       // Max retry rounds
    )

    // ==================== Public API ====================

    var state: State = State.IDLE
        private set

    var config: Config? = null
        private set

    var attemptCount: Int = 0
        private set

    var statusMessage: String = "Ready"
        private set

    var onStateChanged: ((State, String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private var currentSessionIndex = 0
    private var currentPriceIndex = 0
    private var isRunning = false

    fun start(cfg: Config) {
        if (isRunning) return
        config = cfg
        isRunning = true
        retryCount = 0
        currentSessionIndex = 0
        currentPriceIndex = 0
        attemptCount = 0
        ClickAccessibilityService.showFloatingLog()
        transitionTo(State.ENTERING_PAGE, "Waiting to enter ticket selection page...")
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        transitionTo(State.IDLE, "Stopped")
        ClickAccessibilityService.removeFloatingLog()
    }

    fun isRunning(): Boolean = isRunning && state != State.IDLE && state != State.DONE

    /**
     * Called by ClickAccessibilityService.onAccessibilityEvent
     */
    fun onEvent(event: AccessibilityEvent) {
        if (!isRunning) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleState()
            }
        }
    }

    // ==================== State Machine Driver ====================

    private fun handleState() {
        if (!isRunning) return

        when (state) {
            State.ENTERING_PAGE -> handleEnteringPage()
            State.SELECTING_SESSION -> handleSelectingSession()
            State.SELECTING_PRICE -> handleSelectingPrice()
            State.SELECTING_VIEWERS -> handleSelectingViewers()
            State.SUBMITTING_ORDER -> handleSubmittingOrder()
            State.DONE, State.IDLE -> { /* no-op */ }
        }
    }

    /**
     * Phase 1: Continuously click "Immediate" button until node containing "票档" (Ticket Tier) appears
     */
    private fun handleEnteringPage() {
        val rootNode = rootInActiveWindow ?: return

        // Check if entered ticket selection page — find node containing "票档"
        if (findNodeByTextContains(rootNode, "票档") != null) {
            currentSessionIndex = 0
            transitionTo(State.SELECTING_SESSION, "Selecting session...")
            rootNode.recycle()
            return
        }

        // Not yet entered, click "Immediate" or "Buy Tickets" button
        if (clickNodeByText(rootNode, "立即") || clickNodeByText(rootNode, "购票")) {
            attemptCount++
            updateStatus("Tap to enter ticket page (attempt #${attemptCount})")
        }

        rootNode.recycle()
        checkRetryLimit()
    }

    /**
     * Phase 2: Iterate sessions, skip sold-out sessions
     */
    private fun handleSelectingSession() {
        val cfg = config ?: return stop()
        if (currentSessionIndex >= cfg.sessions.size) {
            // All sessions traversed, restart
            currentSessionIndex = 0
            retryCount++
            if (checkRetryLimit()) return
        }

        val rootNode = rootInActiveWindow ?: return
        val session = cfg.sessions[currentSessionIndex]

        // Find session node
        val sessionNode = findNodeByTextContains(rootNode, session)
        if (sessionNode == null) {
            rootNode.recycle()
            attemptCount++
            updateStatus("Session not found: $session")
            currentSessionIndex++
            return
        }

        // Check if session is sold out — sibling/child node contains "无票" (Sold Out) or "缺货登记" (Out of Stock Registration)
        if (isSoldOut(sessionNode)) {
            updateStatus("Session $session sold out, skipping")
            currentSessionIndex++
            rootNode.recycle()
            return
        }

        // Click to select this session
        if (performClick(sessionNode)) {
            currentPriceIndex = 0
            transitionTo(State.SELECTING_PRICE, "Selecting price tier...")
        } else {
            // If cannot click directly, try clicking text
            clickNodeByTextContains(rootNode, session)
            currentPriceIndex = 0
            transitionTo(State.SELECTING_PRICE, "Selecting price tier...")
        }

        rootNode.recycle()
    }

    /**
     * Phase 3: Iterate price tiers, skip out-of-stock ones
     */
    private fun handleSelectingPrice() {
        val cfg = config ?: return stop()
        if (currentPriceIndex >= cfg.prices.size) {
            // All prices for this session are out of stock, switch to next session
            currentSessionIndex++
            transitionTo(State.SELECTING_SESSION, "All prices for this session out of stock, switching session...")
            return
        }

        val rootNode = rootInActiveWindow ?: return
        val price = cfg.prices[currentPriceIndex]

        val priceNode = findNodeByTextContains(rootNode, price)
        if (priceNode == null) {
            currentPriceIndex++
            rootNode.recycle()
            return
        }

        // Check if out of stock
        if (isSoldOut(priceNode)) {
            updateStatus("Price $price out of stock, skipping")
            currentPriceIndex++
            rootNode.recycle()
            return
        }

        // Click to select this price
        if (performClick(priceNode) || clickNodeByTextContains(rootNode, price)) {
            attemptCount++
            updateStatus("Selected price: $price")

            // Set ticket count (click "+" button N-1 times, N = number of viewers)
            handler.postDelayed({
                setTicketCount(cfg.viewers.size)
                // Click "Confirm"
                handler.postDelayed({
                    clickConfirm(rootNode)
                    transitionTo(State.SELECTING_VIEWERS, "Selecting viewers...")
                }, 200)
            }, 200)
        }

        rootNode.recycle()
    }

    /**
     * Phase 4: Check viewers
     */
    private fun handleSelectingViewers() {
        val cfg = config ?: return stop()
        val rootNode = rootInActiveWindow ?: return

        var allSelected = true
        for (viewerName in cfg.viewers) {
            val viewerNode = findNodeByTextContains(rootNode, viewerName)
            if (viewerNode != null) {
                // Try to find clickable checkbox (usually 4th child of parent)
                val clicked = clickViewerCheckbox(viewerNode)
                if (!clicked) {
                    // Fallback: click text directly
                    performClick(viewerNode)
                }
                updateStatus("Selected viewer: $viewerName")
            } else {
                allSelected = false
            }
        }

        // Selection complete, proceed to submit
        if (allSelected) {
            transitionTo(State.SUBMITTING_ORDER, "Submitting order...")
        } else {
            // Proceed to submit even if some were not found
            transitionTo(State.SUBMITTING_ORDER, "Submitting order...")
        }

        rootNode.recycle()
    }

    /**
     * Phase 5: Submit order
     */
    private fun handleSubmittingOrder() {
        val rootNode = rootInActiveWindow ?: return

        val clicked = clickNodeByText(rootNode, "提交订单")
        if (clicked) {
            attemptCount++
            updateStatus("Clicked submit order")
        }

        // Handle pop-up "I know" (我知道了)
        clickNodeByText(rootNode, "我知道了")
        clickNodeById(rootNode, "damai_theme_dialog_confirm_btn")

        // Check if payment-related interface appears (indicates success)
        if (findNodeByTextContains(rootNode, "支付") != null ||
            findNodeByTextContains(rootNode, "收银台") != null) {
            transitionTo(State.DONE, "Ticket grabbed successfully! Please complete payment ASAP")
            isRunning = false
            // Keep log window open to show success message
            handler.postDelayed({
                ClickAccessibilityService.removeFloatingLog()
            }, 5000)
            rootNode.recycle()
            return
        }

        // If "I know" dialog appears, it means failure, restart
        if (findNodeByTextContains(rootNode, "我知道了") != null) {
            currentSessionIndex = 0
            retryCount++
            transitionTo(State.ENTERING_PAGE, "Restarting ticket grab...")
        }

        rootNode.recycle()
        checkRetryLimit()
    }

    // ==================== Helper Methods ====================

    private val rootInActiveWindow: AccessibilityNodeInfo?
        get() = service.rootInActiveWindow

    /**
     * Find node containing specified text (fuzzy match)
     */
    fun findNodeByTextContains(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes.firstOrNull()
    }

    /**
     * Find node matching specified text exactly
     */
    fun findNodeByTextExact(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes.firstOrNull { it.text?.toString() == text }
    }

    /**
     * Find node by resource-id
     */
    fun findNodeById(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        return nodes.firstOrNull()
    }

    /**
     * Click node containing specified text
     */
    fun clickNodeByTextContains(root: AccessibilityNodeInfo, text: String): Boolean {
        val node = findNodeByTextContains(root, text) ?: return false
        return performClick(node)
    }

    /**
     * Click node matching specified text exactly
     */
    fun clickNodeByText(root: AccessibilityNodeInfo, text: String): Boolean {
        val node = findNodeByTextExact(root, text) ?: return false
        return performClick(node)
    }

    /**
     * Click node with specified resource-id
     */
    fun clickNodeById(root: AccessibilityNodeInfo, viewId: String): Boolean {
        val node = findNodeById(root, viewId) ?: return false
        return performClick(node)
    }

    /**
     * Perform click action on node (prefer ACTION_CLICK, search parent upwards if not clickable)
     */
    fun performClick(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        // Search up to 5 levels upwards
        for (i in 0..5) {
            if (target == null) return false
            if (target.isClickable) {
                return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            target = target.parent
        }
        return false
    }

    /**
     * Check if item corresponding to node is sold out
     * Check if sibling/child nodes contain "无票" or "缺货登记"
     */
    fun isSoldOut(node: AccessibilityNodeInfo): Boolean {
        val parent = node.parent ?: return false

        // Traverse all child nodes of parent to find sold-out marks
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            val text = child.text?.toString() ?: continue
            if (text.contains("无票") || text.contains("缺货登记") || text.contains("缺货")) {
                return true
            }
            // Further check grandchildren
            for (j in 0 until child.childCount) {
                val grandChild = child.getChild(j) ?: continue
                val gcText = grandChild.text?.toString() ?: continue
                if (gcText.contains("无票") || gcText.contains("缺货登记") || gcText.contains("缺货")) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Set ticket count = number of viewers (by clicking "+" button)
     */
    private fun setTicketCount(viewerCount: Int) {
        val rootNode = rootInActiveWindow ?: return
        if (viewerCount <= 1) return

        // Find node containing "1张", its parent's 3rd child is usually the "+" button
        val ticketNode = findNodeByTextContains(rootNode, "1张") ?: return
        val parent = ticketNode.parent ?: return

        // Try to find "+" button
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            val text = child.text?.toString() ?: continue
            if (text.contains("+") || child.contentDescription?.contains("增加") == true) {
                // Click N-1 times
                for (k in 0 until viewerCount - 1) {
                    performClick(child)
                }
                return
            }
        }

        // Fallback: find clickable nodes containing "+"
        val plusNodes = rootNode.findAccessibilityNodeInfosByText("+")
        for (plusNode in plusNodes) {
            if (plusNode.isClickable) {
                for (k in 0 until viewerCount - 1) {
                    plusNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                return
            }
        }
    }

    /**
     * Click "Confirm" button
     */
    private fun clickConfirm(rootNode: AccessibilityNodeInfo) {
        // Prefer clicking button matching "确定" exactly
        val confirmNode = findNodeByTextExact(rootNode, "确定")
        if (confirmNode != null) {
            if (!performClick(confirmNode)) {
                // Node not clickable, try clicking parent container
                val parent = confirmNode.parent
                if (parent != null) {
                    performClick(parent)
                }
            }
        }
    }

    /**
     * Check viewer checkbox
     */
    private fun clickViewerCheckbox(viewerNode: AccessibilityNodeInfo): Boolean {
        val parent = viewerNode.parent ?: return false

        // Try to find checkbox — usually in sibling nodes
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            if (child.isCheckable) {
                if (!child.isChecked) {
                    return performClick(child)
                }
                return true // already checked
            }
        }

        // Search one level higher
        val grandParent = parent.parent ?: return false
        for (i in 0 until grandParent.childCount) {
            val child = grandParent.getChild(i) ?: continue
            if (child.isCheckable) {
                if (!child.isChecked) {
                    return performClick(child)
                }
                return true
            }
        }

        return false
    }

    /**
     * Check if retry limit exceeded
     */
    private fun checkRetryLimit(): Boolean {
        val cfg = config ?: return true
        if (retryCount >= cfg.maxRetries) {
            transitionTo(State.DONE, "Max retries reached (${cfg.maxRetries}), stopped")
            isRunning = false
            handler.postDelayed({
                ClickAccessibilityService.removeFloatingLog()
            }, 5000)
            return true
        }
        return false
    }

    private fun transitionTo(newState: State, msg: String) {
        state = newState
        statusMessage = msg
        Log.d("TicketGrab", "State: $newState - $msg")
        ClickAccessibilityService.appendTicketLog("[$newState] $msg")
        onStateChanged?.invoke(newState, msg)
    }

    private fun updateStatus(msg: String) {
        statusMessage = msg
        ClickAccessibilityService.appendTicketLog(msg)
        onStateChanged?.invoke(state, msg)
    }
}

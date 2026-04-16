package com.astramadeus.client

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

class AmadeusAccessibilityService : AccessibilityService() {

    private var lastSnapshotAt: Long = 0L
    private var lastSnapshotSignature: String? = null
    @Volatile private var visionCaptureInFlight: Boolean = false
    private var visionCancellationToken: AtomicBoolean = AtomicBoolean(false)
    private var lastWindowBoundsFingerprint: String? = null
    /** Window+content fingerprint that caused vision pipeline safety timeout.
     *  When set, vision pipeline is skipped for that context to prevent death loops.
     *  Format: "<windowBoundsFingerprint>|n=<elementCount>" */
    private var visionTimeoutFingerprint: String? = null
    private var currentForegroundPackage: String? = null
    /** Structural fingerprint of the last processed frame — used for cancellation decisions. */
    private var lastStructuralFingerprint: String? = null

    // Vision polling: periodic forced captures after window geometry changes
    private val visionPollingHandler = Handler(Looper.getMainLooper())
    private var visionPollingCount = 0

    private val visionPollRunnable: Runnable = Runnable {
        if (visionPollingCount < MAX_VISION_POLLS) {
            visionPollingCount++
            Log.d(TAG, "Vision polling tick #$visionPollingCount")
            captureAndPublishSnapshot(event = null, force = true)
            visionPollingHandler.postDelayed(visionPollRunnable, VISION_POLL_INTERVAL_MS)
        }
    }

    // Heartbeat: periodic capture for vision-enabled apps (catches WebView scroll etc.)
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!visionCaptureInFlight) {
                captureAndPublishSnapshot(event = null, force = true)
            }
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    private val snapshotRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SnapshotBroadcasts.ACTION_REQUEST_SNAPSHOT) {
                captureAndPublishSnapshot(event = null, force = true)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
        ContextCompat.registerReceiver(
            this,
            snapshotRequestReceiver,
            IntentFilter(SnapshotBroadcasts.ACTION_REQUEST_SNAPSHOT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        UiFrameWebSocketClient.syncConfig(this)
        SnapshotBroadcasts.publishStatus(this, true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !shouldHandleEvent(event.eventType)) {
            return
        }

        if (event.packageName?.toString() == packageName) {
            return
        }

        captureAndPublishSnapshot(event = event, force = false)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        stopHeartbeat()
        stopVisionPolling()
        unregisterReceiver(snapshotRequestReceiver)
        SnapshotBroadcasts.publishStatus(this, false)
        super.onDestroy()
    }

    private fun captureAndPublishSnapshot(event: AccessibilityEvent?, force: Boolean) {
        val now = System.currentTimeMillis()
        val debounceMs = PreviewControlsConfig.toIntervalMs(PreviewControlsConfig.getMaxPullRateHz(this))
        if (!force && now - lastSnapshotAt < debounceMs) {
            return
        }

        val root = rootInActiveWindow ?: return
        val rootPackageName = root.packageName?.toString().orEmpty()
        if (rootPackageName.isEmpty() || rootPackageName == packageName) {
            return
        }

        val eventPackageName = event?.packageName?.toString().orEmpty()
        if (eventPackageName.isNotEmpty() && eventPackageName != rootPackageName && eventPackageName == SYSTEM_UI_PACKAGE) {
            return
        }

        val snapshot = SnapshotBuilder.buildSnapshot(root, event, now)

        // Detect multi-window overlay state
        val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val hasMultipleWindows = appWindows.size > 1
        snapshot.getJSONObject("data").put("has_multiple_windows", hasMultipleWindows)

        val snapshotSignature = SnapshotBuilder.buildSignature(snapshot)
        val isSameSnapshot = snapshotSignature == lastSnapshotSignature

        // Detect window geometry changes (slide animations) by fingerprinting bounds
        val windowBoundsFingerprint = buildWindowBoundsFingerprint()
        val windowBoundsChanged = windowBoundsFingerprint != lastWindowBoundsFingerprint
        lastWindowBoundsFingerprint = windowBoundsFingerprint

        if (isSameSnapshot && !windowBoundsChanged) {
            val withinRepeatWindow = now - lastSnapshotAt < FORCED_REPEAT_MIN_INTERVAL_MS
            if (!force || withinRepeatWindow) {
                return
            }
        }

        // Check for info-void overlay to auto-enable vision pipeline
        val elements = snapshot.getJSONObject("data").optJSONArray("elements")
        val hasOverlay = if (elements != null) {
            val screenBounds = parseScreenBoundsFromElements(elements)
            OverlayDetector.detect(elements, screenBounds, hasMultipleWindows) != null
        } else {
            false
        }

        val needsVision = VisionAssistConfig.isVisionAssistEnabled(this, rootPackageName) || hasOverlay
        val useVlModelPath = VisionAssistConfig.shouldUseVlModelPath(this, rootPackageName)

        // Track foreground package and manage heartbeat for vision-enabled apps
        val foregroundChanged = rootPackageName != currentForegroundPackage
        currentForegroundPackage = rootPackageName

        if (!needsVision) {
            stopHeartbeat()
            stopVisionPolling()
            publishSnapshot(snapshot, snapshotSignature, now)
            return
        }

        // VL Model mode: skip OCR pipeline entirely.
        // Raw UI tree (sanitized by FrameSanitizer during WS transport) serves as
        // a local "coordinate dictionary". VL model uses phone_vision_describe +
        // phone_vision_tap for perceive → act workflow.
        // OverlayDetector results (overlay_detected, close button) remain in the
        // snapshot from the analysis above — no OCR needed for those.
        if (useVlModelPath) {
            if (foregroundChanged) {
                startHeartbeat()
            }
            stopVisionPolling()
            // Strip to leaf nodes only — containers are useless for coordinate lookup.
            // Only the actual UI widgets (buttons, text, images) matter as the
            // "coordinate dictionary" for phone_vision_tap.
            val leafCount = filterToLeafNodes(snapshot)
            Log.d(TAG, "VL Model mode: skipping OCR pipeline for $rootPackageName (leaves=$leafCount, original=${elements?.length() ?: 0})")
            publishSnapshot(snapshot, snapshotSignature, now)
            return
        }

        // --- Legacy vision mode (VL mode disabled): full OCR pipeline below ---

        // Start/restart heartbeat when a vision-enabled app is in foreground
        if (foregroundChanged) {
            startHeartbeat()
        }

        // Start vision polling when window geometry changes while in vision mode
        if (windowBoundsChanged && needsVision) {
            Log.d(TAG, "Window bounds changed in vision mode, starting polling")
            startVisionPolling()
        }

        val visionTargets = VisionPipeline.extractVisionTargets(snapshot, rootPackageName)
        val captureWindow = resolveCaptureWindow(rootPackageName)

        val elementCount = elements?.length() ?: 0
        val isSparseByCount = elementCount in 1..VisionPipeline.SPARSE_TREE_THRESHOLD

        // Also detect information-void trees: many elements but almost none have text
        var hasWebView = false
        val isSparseByContent = if (!isSparseByCount && elementCount > 0 && elements != null) {
            var textNodeCount = 0
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                val text = el.optString("text", "")
                val desc = el.optString("desc", "")
                if (text.isNotBlank() || desc.isNotBlank()) textNodeCount++
                val className = el.optString("class_name", "")
                if (className.contains("WebView", ignoreCase = true)) hasWebView = true
            }
            val textDensity = textNodeCount.toFloat() / elementCount
            textDensity < TEXT_DENSITY_THRESHOLD
        } else {
            false
        }

        val isSparseTree = isSparseByCount || isSparseByContent
        // Also use full-page OCR when:
        // - tree is sparse, or
        // - vision is needed but no targets were selected, or
        // - a WebView is present (its content is opaque to accessibility)
        val needsFullPageFallback = isSparseTree || (needsVision && visionTargets.isEmpty()) || hasWebView

        val shouldUseVisionPipeline =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                (visionTargets.isNotEmpty() || needsFullPageFallback)

        // Build a structural fingerprint for cancellation decisions.
        // Unlike snapshotSignature (which changes on any text/selection update),
        // this only changes when the page structure actually changes — preventing
        // false cancellations from animations, timers, or minor text updates.
        val structuralFingerprint = buildStructuralFingerprint(elements, elementCount, snapshot)
        val structurallyChanged = structuralFingerprint != lastStructuralFingerprint
        lastStructuralFingerprint = structuralFingerprint

        // Clear timeout cooldown when window geometry or page structure changes
        if (windowBoundsChanged || structurallyChanged) {
            visionTimeoutFingerprint = null
        }

        if (shouldUseVisionPipeline && visionCaptureInFlight) {
            if (windowBoundsChanged || structurallyChanged) {
                // Page structure changed (different bounds, different activity, or
                // significantly different element layout) — cancel stale pipeline.
                // This handles splash screens, page transitions, tab switches.
                Log.d(TAG, "UI structure changed while vision pipeline busy — cancelling stale work" +
                    " (boundsChanged=$windowBoundsChanged, structuralChanged=$structurallyChanged)")
                visionCancellationToken.set(true)
                visionCaptureInFlight = false
                // Fall through to start new capture below
            } else {
                Log.d(TAG, "Skip frame: vision pipeline busy (same structure)")
                return
            }
        }

        // Skip vision pipeline if the same window + content context already caused a safety timeout.
        // This breaks the death loop: timeout → clear flag → restart → timeout → ...
        // Include element count so navigating to a different page (same window, different content)
        // correctly retries the vision pipeline.
        val visionContextFingerprint = "$windowBoundsFingerprint|n=$elementCount"
        if (shouldUseVisionPipeline && visionContextFingerprint == visionTimeoutFingerprint) {
            Log.d(TAG, "Skip vision pipeline: previous timeout for same window + content")
            publishSnapshot(snapshot, snapshotSignature, now)
            return
        }

        if (shouldUseVisionPipeline) {
            if (needsFullPageFallback) {
                Log.d(TAG, "Full-page OCR fallback: elements=$elementCount" +
                    " sparseByCount=$isSparseByCount sparseByContent=$isSparseByContent" +
                    " visionTargets=${visionTargets.size}")
            }
            captureVisionSnapshot(
                baseSnapshot = snapshot,
                snapshotSignature = snapshotSignature,
                timestamp = now,
                targets = visionTargets,
                captureWindow = captureWindow,
                useSparseTreeFallback = needsFullPageFallback,
                contextFingerprint = visionContextFingerprint,
            )
            return
        }

        publishSnapshot(snapshot, snapshotSignature, now)
    }

    @SuppressLint("NewApi")
    private fun captureVisionSnapshot(
        baseSnapshot: JSONObject,
        snapshotSignature: String,
        timestamp: Long,
        targets: List<VisionPipeline.VisionTarget>,
        captureWindow: CaptureWindow?,
        useSparseTreeFallback: Boolean = false,
        contextFingerprint: String = "",
    ) {
        visionCaptureInFlight = true
        val cancellationToken = AtomicBoolean(false)
        visionCancellationToken = cancellationToken

        // Pre-create a safe copy for the safety timeout to publish.
        // This avoids ConcurrentModificationException if the bg thread is still
        // modifying baseSnapshot when the timeout fires.
        val safetyFallbackSnapshot = JSONObject(baseSnapshot.toString())

        // Safety timeout: if the pipeline hasn't completed in time, force-clear the flag,
        // publish the base snapshot so the user gets data, and remember this window to
        // prevent the death loop (timeout → clear → restart on same screen → timeout...).
        val safetyTimeout = Runnable {
            if (visionCaptureInFlight) {
                Log.w(TAG, "Vision pipeline safety timeout — publishing base snapshot")
                visionCaptureInFlight = false
                cancellationToken.set(true)
                visionTimeoutFingerprint = contextFingerprint
                publishSnapshot(safetyFallbackSnapshot, snapshotSignature, timestamp)
            }
        }
        visionPollingHandler.postDelayed(safetyTimeout, VISION_PIPELINE_TIMEOUT_MS)

        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                thread(name = "vision-segments-builder") {
                    val enrichedSnapshot = runCatching {
                        if (cancellationToken.get()) {
                            Log.d(TAG, "Vision pipeline cancelled before processing")
                            baseSnapshot
                        } else {
                            // Work on a deep copy so the original baseSnapshot is never
                            // mutated — prevents races with safety timeout / main thread.
                            val snapshotCopy = JSONObject(baseSnapshot.toString())
                            buildSnapshotWithVisionSegments(
                                baseSnapshot = snapshotCopy,
                                screenshot = screenshot,
                                targets = targets,
                                captureWindowBounds = captureWindow?.boundsInScreen,
                                useSparseTreeFallback = useSparseTreeFallback,
                                cancelled = cancellationToken,
                            )
                        }
                    }.getOrElse { error ->
                        Log.w(TAG, "Failed to build vision segments: ${error.message}")
                        baseSnapshot
                    }

                    mainExecutor.execute {
                        // Always clear in-flight flag and cancel safety timeout
                        visionCaptureInFlight = false
                        visionPollingHandler.removeCallbacks(safetyTimeout)

                        if (!cancellationToken.get()) {
                            // If fullpage OCR was requested but produced 0 segments,
                            // record cooldown to prevent infinite retry loop.
                            if (useSparseTreeFallback) {
                                val visionSegs = enrichedSnapshot
                                    .optJSONObject("data")
                                    ?.optJSONArray("vision_segments")
                                if (visionSegs == null || visionSegs.length() == 0) {
                                    Log.d(TAG, "Fullpage OCR produced 0 segments — setting cooldown")
                                    visionTimeoutFingerprint = contextFingerprint
                                }
                            }
                            publishSnapshot(enrichedSnapshot, snapshotSignature, timestamp)
                        } else {
                            Log.d(TAG, "Discarding cancelled vision pipeline result")
                        }
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                visionCaptureInFlight = false
                visionPollingHandler.removeCallbacks(safetyTimeout)
                Log.w(TAG, "takeScreenshot failed with code=$errorCode")
                publishSnapshot(baseSnapshot, snapshotSignature, timestamp)
            }
        }

        // Delay screenshot capture to let UI animations settle (typically 250-300ms).
        // Without this, screenshots may capture mid-transition frames.
        visionPollingHandler.postDelayed({
            if (cancellationToken.get()) {
                visionCaptureInFlight = false
                visionPollingHandler.removeCallbacks(safetyTimeout)
                Log.d(TAG, "Vision capture cancelled during animation settle delay")
                return@postDelayed
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && captureWindow != null) {
                takeScreenshotOfWindow(captureWindow.windowId, mainExecutor, callback)
                return@postDelayed
            }

            // Fallback: full-screen screenshot (API 30+) when window-specific capture unavailable
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
                return@postDelayed
            }

            visionCaptureInFlight = false
            visionPollingHandler.removeCallbacks(safetyTimeout)
            Log.w(TAG, "Skip vision capture: screenshot API unavailable")
            publishSnapshot(baseSnapshot, snapshotSignature, timestamp)
        }, SCREENSHOT_SETTLE_DELAY_MS)
    }

    private fun publishSnapshot(snapshot: JSONObject, signature: String, timestamp: Long) {
        lastSnapshotAt = timestamp
        lastSnapshotSignature = signature
        Log.d(TAG, snapshot.toString())
        val snapshotText = snapshot.toString()
        SnapshotBroadcasts.publishSnapshot(this, snapshotText)
        UiFrameWebSocketClient.sendUiFrame(this, snapshotText)
    }

    @SuppressLint("NewApi")
    private fun buildSnapshotWithVisionSegments(
        baseSnapshot: JSONObject,
        screenshot: ScreenshotResult,
        targets: List<VisionPipeline.VisionTarget>,
        captureWindowBounds: Rect?,
        useSparseTreeFallback: Boolean = false,
        cancelled: AtomicBoolean = AtomicBoolean(false),
    ): JSONObject {
        val data = baseSnapshot.getJSONObject("data")
        val hardwareBuffer: HardwareBuffer = screenshot.hardwareBuffer
        val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
        val bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)

        try {
            hardwareBitmap?.recycle()
        } finally {
            hardwareBuffer.close()
        }

        if (bitmap == null) {
            return baseSnapshot
        }

        val segments = try {
            if (targets.isNotEmpty()) {
                VisionPipeline.buildVisionSegments(this, bitmap, targets, captureWindowBounds, cancelled)
            } else {
                JSONArray()
            }
        } catch (error: Throwable) {
            Log.w(TAG, "buildVisionSegments failed: ${error.message}")
            JSONArray()
        }

        // Full-page OCR fallback for sparse trees
        if (useSparseTreeFallback) {
            try {
                val fullPageSegments = VisionPipeline.buildFullPageOcrSegments(this, bitmap, cancelled)
                for (i in 0 until fullPageSegments.length()) {
                    segments.put(fullPageSegments.getJSONObject(i))
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Full-page OCR fallback failed: ${error.message}")
            }
        }

        bitmap.recycle()
        data.put("vision_segments", segments)
        return baseSnapshot
    }

    private fun resolveCaptureWindow(rootPackageName: String): CaptureWindow? {
        val window = windows
            .asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .firstOrNull { info ->
                val pkg = info.root?.packageName?.toString().orEmpty()
                pkg == rootPackageName
            }
            ?: return null

        val bounds = Rect()
        window.getBoundsInScreen(bounds)
        return CaptureWindow(window.id, bounds)
    }

    private fun shouldHandleEvent(eventType: Int): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
    }

    /**
     * Build a fingerprint of all application window bounds.
     * Changes in this fingerprint indicate window geometry changes
     * (e.g., a webview sliding in from the side).
     */
    private fun buildWindowBoundsFingerprint(): String {
        val bounds = Rect()
        return windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedBy { it.id }
            .joinToString("|") { window ->
                window.getBoundsInScreen(bounds)
                "${window.id}:${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
            }
    }

    /**
     * Build a coarse structural fingerprint for pipeline cancellation decisions.
     * Includes: package, activity, element count, and a hash of resource IDs / class names.
     * Does NOT include text content, selection state, or other volatile fields.
     * This prevents false cancellations from animations, timers, or minor text updates
     * while still catching real page transitions.
     */
    private fun buildStructuralFingerprint(
        elements: JSONArray?,
        elementCount: Int,
        snapshot: JSONObject,
    ): String {
        val data = snapshot.optJSONObject("data") ?: return ""
        val pkg = data.optString("package_name", "")
        val activity = data.optString("activity_name", "")

        // Hash resource IDs to detect layout changes without being text-sensitive
        var structuralHash = 0L
        if (elements != null) {
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                val resId = el.optString("resource_id", "")
                val className = el.optString("class_name", "")
                structuralHash = structuralHash * 31 + resId.hashCode()
                structuralHash = structuralHash * 31 + className.hashCode()
            }
        }

        return "$pkg|$activity|n=$elementCount|h=$structuralHash"
    }

    private fun startVisionPolling() {
        visionPollingCount = 0
        visionPollingHandler.removeCallbacks(visionPollRunnable)
        visionPollingHandler.postDelayed(visionPollRunnable, VISION_POLL_INTERVAL_MS)
    }

    private fun stopVisionPolling() {
        visionPollingHandler.removeCallbacks(visionPollRunnable)
    }

    private fun startHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
        Log.d(TAG, "Heartbeat started for vision-enabled app")
    }

    private fun stopHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
    }

    private data class CaptureWindow(
        val windowId: Int,
        val boundsInScreen: Rect,
    )

    companion object {
        private const val TAG = "AmadeusAccessibility"
        private const val FORCED_REPEAT_MIN_INTERVAL_MS = 2_500L
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val MAX_VISION_POLLS = 6
        private const val VISION_POLL_INTERVAL_MS = 500L
        /** Heartbeat interval for vision-enabled apps (catches WebView scroll etc.) */
        private const val HEARTBEAT_INTERVAL_MS = 2_000L
        /** Safety timeout for the entire vision pipeline (screenshot + OCR).
         *  Must be longer than FULLPAGE_OCR_TIMEOUT_MS in VisionPipeline. */
        private const val VISION_PIPELINE_TIMEOUT_MS = 25_000L
        /** Delay before taking screenshot to let UI animations settle. */
        private const val SCREENSHOT_SETTLE_DELAY_MS = 350L
        /** If fewer than this fraction of elements have text, treat the tree as sparse. */
        private const val TEXT_DENSITY_THRESHOLD = 0.10f
    }

    private fun parseScreenBoundsFromElements(elements: JSONArray): BoundsParser.RectTuple? {
        return BoundsParser.parseScreenBoundsFromElements(elements)
    }

    /**
     * Filter the snapshot's elements array to only keep leaf nodes (child_count == 0)
     * and actionable non-leaf nodes (clickable/scrollable containers that are valid
     * tap/scroll targets). Removes pure container/layout nodes that add no value
     * to the "coordinate dictionary" used by phone_vision_tap.
     *
     * @return the number of elements remaining after filtering.
     */
    private fun filterToLeafNodes(snapshot: JSONObject): Int {
        val data = snapshot.optJSONObject("data") ?: return 0
        val elements = data.optJSONArray("elements") ?: return 0

        val filtered = JSONArray()
        for (i in 0 until elements.length()) {
            val node = elements.optJSONObject(i) ?: continue
            val childCount = node.optInt("child_count", 0)
            val isLeaf = childCount == 0

            // Keep leaf nodes (actual UI widgets)
            if (isLeaf) {
                filtered.put(node)
                continue
            }

            // Also keep non-leaf nodes that are actionable targets
            // (e.g., a clickable FrameLayout that wraps an icon)
            val isClickable = node.optBoolean("is_clickable", false)
            val isScrollable = node.optBoolean("is_scrollable", false)
            val hasText = node.optString("text", "").isNotBlank() ||
                node.optString("desc", "").isNotBlank()
            if (isClickable || isScrollable || hasText) {
                filtered.put(node)
            }
        }

        data.put("elements", filtered)
        return filtered.length()
    }
}

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
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

class AmadeusAccessibilityService : AccessibilityService() {

    private var lastSnapshotAt: Long = 0L
    private var lastSnapshotSignature: String? = null
    private var visionCaptureInFlight: Boolean = false

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
        val snapshotSignature = SnapshotBuilder.buildSignature(snapshot)
        val isSameSnapshot = snapshotSignature == lastSnapshotSignature

        if (isSameSnapshot) {
            val withinRepeatWindow = now - lastSnapshotAt < FORCED_REPEAT_MIN_INTERVAL_MS
            if (!force || withinRepeatWindow) {
                return
            }
        }

        if (!VisionAssistConfig.isVisionAssistEnabled(this, rootPackageName)) {
            publishSnapshot(snapshot, snapshotSignature, now)
            return
        }

        val visionTargets = VisionPipeline.extractVisionTargets(snapshot, rootPackageName)
        val captureWindow = resolveCaptureWindow(rootPackageName)
        val shouldUseVisionPipeline =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                visionTargets.isNotEmpty()

        if (shouldUseVisionPipeline && visionCaptureInFlight) {
            Log.d(TAG, "Skip frame: vision pipeline busy")
            return
        }

        if (shouldUseVisionPipeline) {
            captureVisionSnapshot(
                baseSnapshot = snapshot,
                snapshotSignature = snapshotSignature,
                timestamp = now,
                targets = visionTargets,
                captureWindow = captureWindow,
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
    ) {
        visionCaptureInFlight = true

        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                thread(name = "vision-segments-builder") {
                    val enrichedSnapshot = runCatching {
                        buildSnapshotWithVisionSegments(
                            baseSnapshot = baseSnapshot,
                            screenshot = screenshot,
                            targets = targets,
                            captureWindowBounds = captureWindow?.boundsInScreen,
                        )
                    }.getOrElse { error ->
                        Log.w(TAG, "Failed to build vision segments: ${error.message}")
                        baseSnapshot
                    }

                    mainExecutor.execute {
                        visionCaptureInFlight = false
                        publishSnapshot(enrichedSnapshot, snapshotSignature, timestamp)
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                visionCaptureInFlight = false
                Log.w(TAG, "takeScreenshot failed with code=$errorCode")
                publishSnapshot(baseSnapshot, snapshotSignature, timestamp)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && captureWindow != null) {
            takeScreenshotOfWindow(captureWindow.windowId, mainExecutor, callback)
            return
        }

        visionCaptureInFlight = false
        Log.w(TAG, "Skip vision capture: app-window screenshot unavailable")
        publishSnapshot(baseSnapshot, snapshotSignature, timestamp)
    }

    private fun publishSnapshot(snapshot: JSONObject, signature: String, timestamp: Long) {
        lastSnapshotAt = timestamp
        lastSnapshotSignature = signature
        Log.i(TAG, snapshot.toString())
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
            VisionPipeline.buildVisionSegments(this, bitmap, targets, captureWindowBounds)
        } catch (error: Throwable) {
            Log.w(TAG, "buildVisionSegments failed: ${error.message}")
            JSONArray()
        } finally {
            bitmap.recycle()
        }
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
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
    }

    private data class CaptureWindow(
        val windowId: Int,
        val boundsInScreen: Rect,
    )

    companion object {
        private const val TAG = "AmadeusAccessibility"
        private const val FORCED_REPEAT_MIN_INTERVAL_MS = 2_500L
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}

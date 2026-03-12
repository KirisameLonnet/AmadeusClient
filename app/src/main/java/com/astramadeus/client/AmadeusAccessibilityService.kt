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
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

class AmadeusAccessibilityService : AccessibilityService() {

    private var lastSnapshotAt: Long = 0L
    private var lastSnapshotSignature: String? = null
    private var visionCaptureInFlight: Boolean = false

    private val boundsRegex = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")

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

        val snapshot = buildSnapshot(root, event, now)
        val snapshotSignature = buildSnapshotSignature(snapshot)
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

        val visionTargets = extractVisionTargets(snapshot, rootPackageName)
        val captureWindow = resolveCaptureWindow(rootPackageName)
        val shouldCaptureVision =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                visionTargets.isNotEmpty() &&
                !visionCaptureInFlight

        if (shouldCaptureVision) {
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
        targets: List<VisionTarget>,
        captureWindow: CaptureWindow?,
    ) {
        visionCaptureInFlight = true

        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
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

                visionCaptureInFlight = false
                publishSnapshot(enrichedSnapshot, snapshotSignature, timestamp)
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
        SnapshotBroadcasts.publishSnapshot(this, snapshot.toString())
    }

    @SuppressLint("NewApi")
    private fun buildSnapshotWithVisionSegments(
        baseSnapshot: JSONObject,
        screenshot: ScreenshotResult,
        targets: List<VisionTarget>,
        captureWindowBounds: Rect?,
    ): JSONObject {
        val data = baseSnapshot.getJSONObject("data")
        val hardwareBuffer: HardwareBuffer = screenshot.hardwareBuffer
        val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
        val bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)

        hardwareBitmap?.recycle()
        hardwareBuffer.close()

        if (bitmap == null) {
            return baseSnapshot
        }

        val segments = runCatching {
            buildVisionSegments(bitmap, targets, captureWindowBounds)
        }.getOrDefault(JSONArray())

        bitmap.recycle()
        data.put("vision_segments", segments)
        return baseSnapshot
    }

    private fun buildVisionSegments(
        bitmap: Bitmap,
        targets: List<VisionTarget>,
        captureWindowBounds: Rect?,
    ): JSONArray {
        val segments = JSONArray()

        targets.take(MAX_VISION_SEGMENTS).forEach { target ->
            val captureSpaceBounds = toCaptureSpace(target.bounds, captureWindowBounds)
            val clampedCaptureBounds = Rect(
                captureSpaceBounds.left.coerceIn(0, bitmap.width),
                captureSpaceBounds.top.coerceIn(0, bitmap.height),
                captureSpaceBounds.right.coerceIn(0, bitmap.width),
                captureSpaceBounds.bottom.coerceIn(0, bitmap.height),
            )

            if (clampedCaptureBounds.width() < MIN_VISION_BOUNDS_SIZE_PX || clampedCaptureBounds.height() < MIN_VISION_BOUNDS_SIZE_PX) {
                return@forEach
            }

            val crop = Bitmap.createBitmap(
                bitmap,
                clampedCaptureBounds.left,
                clampedCaptureBounds.top,
                clampedCaptureBounds.width(),
                clampedCaptureBounds.height(),
            )

            val normalizedCrop = scaleDown(crop, VISION_CROP_MAX_EDGE_PX)
            if (normalizedCrop !== crop) {
                crop.recycle()
            }

            val output = ByteArrayOutputStream()
            normalizedCrop.compress(Bitmap.CompressFormat.JPEG, VISION_CROP_JPEG_QUALITY, output)
            normalizedCrop.recycle()

            val encoded = android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
            output.close()

            segments.put(
                JSONObject()
                    .put("id", target.id)
                    .put("bounds", "[${target.bounds.left},${target.bounds.top}][${target.bounds.right},${target.bounds.bottom}]")
                    .put("image_base64", encoded)
                    .put("image_format", "jpeg"),
            )
        }

        return segments
    }

    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val maxCurrentEdge = maxOf(bitmap.width, bitmap.height)
        if (maxCurrentEdge <= maxEdge) {
            return bitmap
        }

        val scale = maxEdge.toFloat() / maxCurrentEdge.toFloat()
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun extractVisionTargets(snapshot: JSONObject, rootPackageName: String): List<VisionTarget> {
        val data = snapshot.optJSONObject("data") ?: return emptyList()
        val elements = data.optJSONArray("elements") ?: return emptyList()
        val screenBounds = parseBoundsFromSnapshot(elements) ?: return emptyList()
        val screenArea = screenBounds.width().toLong() * screenBounds.height().toLong()
        val targets = mutableListOf<VisionTarget>()
        val rejectStats = mutableMapOf(
            VisionRejectReason.NOT_VISIBLE to 0,
            VisionRejectReason.HAS_MEANINGFUL_LABEL to 0,
            VisionRejectReason.NON_VISUAL_OR_NON_ACTIONABLE to 0,
            VisionRejectReason.INVALID_BOUNDS to 0,
            VisionRejectReason.TOO_SMALL to 0,
            VisionRejectReason.TOO_LARGE to 0,
        )

        for (index in 0 until elements.length()) {
            val node = elements.optJSONObject(index) ?: continue
            val rejectReason = getVisionRejectReason(node)
            if (rejectReason != null) {
                rejectStats[rejectReason] = rejectStats.getValue(rejectReason) + 1
                continue
            }

            val bounds = parseBounds(node.optString("bounds"))
            if (bounds == null) {
                rejectStats[VisionRejectReason.INVALID_BOUNDS] = rejectStats.getValue(VisionRejectReason.INVALID_BOUNDS) + 1
                continue
            }

            if (bounds.width() < MIN_VISION_BOUNDS_SIZE_PX || bounds.height() < MIN_VISION_BOUNDS_SIZE_PX) {
                rejectStats[VisionRejectReason.TOO_SMALL] = rejectStats.getValue(VisionRejectReason.TOO_SMALL) + 1
                continue
            }

            val area = bounds.width().toLong() * bounds.height().toLong()
            if (screenArea > 0L && area > (screenArea * MAX_VISION_AREA_RATIO).toLong()) {
                rejectStats[VisionRejectReason.TOO_LARGE] = rejectStats.getValue(VisionRejectReason.TOO_LARGE) + 1
                continue
            }

            targets += VisionTarget(
                id = node.optString("id"),
                bounds = bounds,
            )
        }

        val finalTargets = if (targets.size > MAX_VISION_SEGMENTS) {
            targets
                .sortedBy { it.bounds.width().toLong() * it.bounds.height().toLong() }
                .take(MAX_VISION_SEGMENTS)
        } else {
            targets
        }

        val trimmed = targets.size - finalTargets.size
        logVisionFilterStats(
            packageName = rootPackageName,
            totalNodes = elements.length(),
            selected = finalTargets.size,
            trimmed = trimmed,
            rejectStats = rejectStats,
            sampleTargets = finalTargets.take(VISION_FILTER_LOG_SAMPLE_COUNT),
        )

        return finalTargets
    }

    private fun parseBoundsFromSnapshot(elements: JSONArray): Rect? {
        var minLeft = Int.MAX_VALUE
        var minTop = Int.MAX_VALUE
        var maxRight = Int.MIN_VALUE
        var maxBottom = Int.MIN_VALUE

        for (index in 0 until elements.length()) {
            val node = elements.optJSONObject(index) ?: continue
            val bounds = parseBounds(node.optString("bounds")) ?: continue
            minLeft = minOf(minLeft, bounds.left)
            minTop = minOf(minTop, bounds.top)
            maxRight = maxOf(maxRight, bounds.right)
            maxBottom = maxOf(maxBottom, bounds.bottom)
        }

        if (minLeft == Int.MAX_VALUE || minTop == Int.MAX_VALUE || maxRight <= minLeft || maxBottom <= minTop) {
            return null
        }

        return Rect(minLeft, minTop, maxRight, maxBottom)
    }

    private fun getVisionRejectReason(node: JSONObject): VisionRejectReason? {
        if (!node.optBoolean("is_visible_to_user", true)) {
            return VisionRejectReason.NOT_VISIBLE
        }

        val text = node.optString("text")
        val desc = node.optString("desc")
        if (hasMeaningfulLabel(text) || hasMeaningfulLabel(desc)) {
            return VisionRejectReason.HAS_MEANINGFUL_LABEL
        }

        val className = node.optString("class_name")
        val isViewLikeClass = className.contains("View", ignoreCase = true) ||
            className.contains("Layout", ignoreCase = true)
        val isVisualClass = className.contains("Image", ignoreCase = true) ||
            className.contains("Icon", ignoreCase = true) ||
            className.contains("WebView", ignoreCase = true)
        val isActionableWithoutLabel = node.optBoolean("is_clickable") ||
            node.optBoolean("is_scrollable") ||
            node.optBoolean("is_editable") ||
            node.optBoolean("is_focusable")

        if (!isVisualClass && !(isViewLikeClass && isActionableWithoutLabel)) {
            return VisionRejectReason.NON_VISUAL_OR_NON_ACTIONABLE
        }

        return null
    }

    private fun logVisionFilterStats(
        packageName: String,
        totalNodes: Int,
        selected: Int,
        trimmed: Int,
        rejectStats: Map<VisionRejectReason, Int>,
        sampleTargets: List<VisionTarget>,
    ) {
        val rejectSummary = buildString {
            append("not_visible=")
            append(rejectStats.getValue(VisionRejectReason.NOT_VISIBLE))
            append(", has_label=")
            append(rejectStats.getValue(VisionRejectReason.HAS_MEANINGFUL_LABEL))
            append(", non_visual_or_non_actionable=")
            append(rejectStats.getValue(VisionRejectReason.NON_VISUAL_OR_NON_ACTIONABLE))
            append(", invalid_bounds=")
            append(rejectStats.getValue(VisionRejectReason.INVALID_BOUNDS))
            append(", too_small=")
            append(rejectStats.getValue(VisionRejectReason.TOO_SMALL))
            append(", too_large=")
            append(rejectStats.getValue(VisionRejectReason.TOO_LARGE))
        }

        val sampleSummary = if (sampleTargets.isEmpty()) {
            "none"
        } else {
            sampleTargets.joinToString(separator = " | ") { target ->
                val width = target.bounds.width()
                val height = target.bounds.height()
                "${target.id}:${target.bounds.left},${target.bounds.top},${width}x${height}"
            }
        }

        Log.d(
            TAG,
            "Vision filter package=$packageName total_nodes=$totalNodes selected=$selected trimmed=$trimmed rejects={$rejectSummary} sample={$sampleSummary}",
        )
    }

    private fun hasMeaningfulLabel(value: String): Boolean {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            return false
        }

        return normalized.any { it.isLetterOrDigit() }
    }

    private fun parseBounds(value: String): Rect? {
        val match = boundsRegex.matchEntire(value) ?: return null
        val left = match.groupValues[1].toInt()
        val top = match.groupValues[2].toInt()
        val right = match.groupValues[3].toInt()
        val bottom = match.groupValues[4].toInt()
        return Rect(left, top, right, bottom)
    }

    private fun toCaptureSpace(bounds: Rect, captureWindowBounds: Rect?): Rect {
        if (captureWindowBounds == null) {
            return Rect(bounds)
        }

        return Rect(
            bounds.left - captureWindowBounds.left,
            bounds.top - captureWindowBounds.top,
            bounds.right - captureWindowBounds.left,
            bounds.bottom - captureWindowBounds.top,
        )
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

    private fun buildSnapshot(
        root: AccessibilityNodeInfo,
        event: AccessibilityEvent?,
        timestamp: Long,
    ): JSONObject {
        val eventType = event?.eventType?.let(AccessibilityEvent::eventTypeToString) ?: REQUEST_EVENT_TYPE
        val activityName = event?.className?.toString().orEmpty()

        val elements = JSONArray()
        traverseNode(
            node = root,
            elements = elements,
            parentId = null,
            nextId = IdCounter(),
            depth = 0,
            indexInParent = 0,
        )

        return JSONObject()
            .put("type", "ui_state")
            .put("timestamp", timestamp)
            .put(
                "data",
                JSONObject()
                    .put("package_name", root.packageName?.toString().orEmpty())
                    .put("activity_name", activityName)
                    .put("event_type", eventType)
                    .put("elements", elements),
            )
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        elements: JSONArray,
        parentId: String?,
        nextId: IdCounter,
        depth: Int,
        indexInParent: Int,
    ) {
        val nodeId = "node_${nextId.next()}"
        elements.put(nodeToJson(node, nodeId, parentId, depth, indexInParent))

        for (childIndex in 0 until node.childCount) {
            val child = node.getChild(childIndex) ?: continue
            traverseNode(child, elements, nodeId, nextId, depth + 1, childIndex)
            child.recycle()
        }
    }

    private fun nodeToJson(
        node: AccessibilityNodeInfo,
        nodeId: String,
        parentId: String?,
        depth: Int,
        indexInParent: Int,
    ): JSONObject {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        return JSONObject()
            .put("id", nodeId)
            .put("parent_id", parentId ?: JSONObject.NULL)
            .put("depth", depth)
            .put("index_in_parent", indexInParent)
            .put("text", node.text?.toString().orEmpty())
            .put("desc", node.contentDescription?.toString().orEmpty())
            .put("class_name", node.className?.toString().orEmpty())
            .put("resource_id", node.viewIdResourceName.orEmpty())
            .put("package_name", node.packageName?.toString().orEmpty())
            .put("bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
            .put("child_count", node.childCount)
            .put("is_clickable", node.isClickable)
            .put("is_enabled", node.isEnabled)
            .put("is_focusable", node.isFocusable)
            .put("is_focused", node.isFocused)
            .put("is_scrollable", node.isScrollable)
            .put("is_editable", node.isEditable)
            .put("is_selected", node.isSelected)
            .put("is_visible_to_user", node.isVisibleToUser)
    }

    private fun shouldHandleEvent(eventType: Int): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
    }

    private fun buildSnapshotSignature(snapshot: JSONObject): String {
        val data = snapshot.getJSONObject("data")
        val semanticElements = JSONArray()
        val elements = data.getJSONArray("elements")

        for (index in 0 until elements.length()) {
            val node = elements.getJSONObject(index)
            if (!isMeaningfulNode(node)) {
                continue
            }

            semanticElements.put(
                JSONObject()
                    .put("text", node.optString("text"))
                    .put("desc", node.optString("desc"))
                    .put("class_name", node.optString("class_name"))
                    .put("resource_id", node.optString("resource_id"))
                    .put("child_count", node.optInt("child_count"))
                    .put("is_clickable", node.optBoolean("is_clickable"))
                    .put("is_enabled", node.optBoolean("is_enabled"))
                    .put("is_focusable", node.optBoolean("is_focusable"))
                    .put("is_scrollable", node.optBoolean("is_scrollable"))
                    .put("is_editable", node.optBoolean("is_editable"))
                    .put("is_selected", node.optBoolean("is_selected")),
            )
        }

        val normalized = JSONObject()
            .put("package_name", data.optString("package_name"))
            .put("activity_name", data.optString("activity_name"))
            .put("elements", semanticElements)

        return normalized.toStableHash()
    }

    private fun isMeaningfulNode(node: JSONObject): Boolean {
        if (!node.optBoolean("is_visible_to_user", true)) {
            return false
        }

        return node.optString("text").isNotBlank() ||
            node.optString("desc").isNotBlank() ||
            node.optString("resource_id").isNotBlank() ||
            node.optBoolean("is_clickable") ||
            node.optBoolean("is_scrollable") ||
            node.optBoolean("is_editable") ||
            node.optBoolean("is_focusable")
    }

    private fun JSONObject.toStableHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(toString().toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private class IdCounter {
        private var value: Int = 0

        fun next(): Int {
            val current = value
            value += 1
            return current
        }
    }

    private data class VisionTarget(
        val id: String,
        val bounds: Rect,
    )

    private data class CaptureWindow(
        val windowId: Int,
        val boundsInScreen: Rect,
    )

    private enum class VisionRejectReason {
        NOT_VISIBLE,
        HAS_MEANINGFUL_LABEL,
        NON_VISUAL_OR_NON_ACTIONABLE,
        INVALID_BOUNDS,
        TOO_SMALL,
        TOO_LARGE,
    }

    companion object {
        private const val TAG = "AmadeusAccessibility"
        private const val FORCED_REPEAT_MIN_INTERVAL_MS = 2_500L
        private const val VISION_CROP_MAX_EDGE_PX = 1080
        private const val VISION_CROP_JPEG_QUALITY = 88
        private const val MIN_VISION_BOUNDS_SIZE_PX = 24
        private const val MAX_VISION_SEGMENTS = 64
        private const val MAX_VISION_AREA_RATIO = 0.35
        private const val VISION_FILTER_LOG_SAMPLE_COUNT = 8
        private const val REQUEST_EVENT_TYPE = "TYPE_SNAPSHOT_REQUESTED"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}

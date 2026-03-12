package com.astramadeus.client

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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
        targets: List<VisionTarget>,
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
        targets: List<VisionTarget>,
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
            buildVisionSegments(bitmap, targets, captureWindowBounds)
        } catch (error: Throwable) {
            Log.w(TAG, "buildVisionSegments failed: ${error.message}")
            JSONArray()
        } finally {
            bitmap.recycle()
        }
        data.put("vision_segments", segments)
        return baseSnapshot
    }

    private fun buildVisionSegments(
        bitmap: Bitmap,
        targets: List<VisionTarget>,
        captureWindowBounds: Rect?,
    ): JSONArray {
        val pipelineStartAt = System.currentTimeMillis()
        val segments = JSONArray()
        val preparedSegments = mutableListOf<PreparedVisionSegment>()

        try {
            targets.take(MAX_VISION_SEGMENTS).forEach { target ->
                val captureSpaceBounds = toCaptureSpace(target.bounds, captureWindowBounds)
                val clampedCaptureBounds = Rect(
                    captureSpaceBounds.left.coerceIn(0, bitmap.width),
                    captureSpaceBounds.top.coerceIn(0, bitmap.height),
                    captureSpaceBounds.right.coerceIn(0, bitmap.width),
                    captureSpaceBounds.bottom.coerceIn(0, bitmap.height),
                )

                if (clampedCaptureBounds.width() <= 0 || clampedCaptureBounds.height() <= 0) {
                    return@forEach
                }

                val crop = Bitmap.createBitmap(
                    bitmap,
                    clampedCaptureBounds.left,
                    clampedCaptureBounds.top,
                    clampedCaptureBounds.width(),
                    clampedCaptureBounds.height(),
                )

                val normalizedCrop = try {
                    scaleDown(crop, VISION_CROP_MAX_EDGE_PX)
                } catch (error: Throwable) {
                    crop.recycle()
                    throw error
                }
                if (normalizedCrop !== crop) {
                    crop.recycle()
                }

                preparedSegments += PreparedVisionSegment(
                    id = target.id,
                    bounds = target.bounds,
                    bitmap = normalizedCrop,
                )
            }

            val configuredParallelism = OcrPipelineConfig.getMaxParallelism(this)
            val ocrResultByNodeId = runParallelOcr(preparedSegments, configuredParallelism)
            var ocrHitCount = 0

            preparedSegments.forEach { segment ->
                val ocrText = ocrResultByNodeId[segment.id].orEmpty()
                if (ocrText.isNotBlank()) {
                    ocrHitCount += 1
                }

                val averageColor = sampleAverageColor(segment.bitmap)

                segments.put(
                    JSONObject()
                        .put("id", segment.id)
                        .put("bounds", "[${segment.bounds.left},${segment.bounds.top}][${segment.bounds.right},${segment.bounds.bottom}]")
                        .put("ocr_text", ocrText)
                        .put("avg_color_rgb", JSONArray().put(averageColor.red).put(averageColor.green).put(averageColor.blue))
                        .put("avg_color_hex", averageColor.toHexString()),
                )
            }

            val elapsed = System.currentTimeMillis() - pipelineStartAt
            Log.d(
                TAG,
                "Vision pipeline segments=${preparedSegments.size} ocr_hits=$ocrHitCount parallelism=${configuredParallelism.coerceAtMost(preparedSegments.size.coerceAtLeast(1))} cost_ms=$elapsed",
            )
        } finally {
            preparedSegments.forEach { segment ->
                runCatching {
                    segment.bitmap.recycle()
                }
            }
        }

        return segments
    }

    private fun runParallelOcr(
        segments: List<PreparedVisionSegment>,
        configuredParallelism: Int,
    ): Map<String, String> {
        if (segments.isEmpty()) {
            return emptyMap()
        }

        val workerCount = OcrPipelineConfig
            .normalizeParallelism(configuredParallelism)
            .coerceAtMost(segments.size)
        val executor = Executors.newFixedThreadPool(workerCount)
        return try {
            val futures = mutableMapOf<String, Future<String>>()
            segments.forEach { segment ->
                futures[segment.id] = executor.submit<String> {
                    VisionOcrProcessor.recognizeBitmapBlocking(segment.bitmap)
                }
            }

            futures.mapValues { (_, future) ->
                runCatching {
                    future.get(OCR_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }.onFailure { error ->
                    Log.w(TAG, "OCR worker timeout/failure: ${error.message}")
                }.getOrDefault("")
            }
        } finally {
            executor.shutdownNow()
        }
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

    private fun sampleAverageColor(bitmap: Bitmap): SampledColor {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            return SampledColor(0, 0, 0)
        }

        val samplePoints = listOf(
            bitmap.width / 2 to bitmap.height / 2,
            bitmap.width / 4 to bitmap.height / 4,
            (bitmap.width * 3) / 4 to bitmap.height / 4,
            bitmap.width / 4 to (bitmap.height * 3) / 4,
            (bitmap.width * 3) / 4 to (bitmap.height * 3) / 4,
        )

        var redSum = 0
        var greenSum = 0
        var blueSum = 0

        samplePoints.forEach { (x, y) ->
            val safeX = x.coerceIn(0, bitmap.width - 1)
            val safeY = y.coerceIn(0, bitmap.height - 1)
            val pixel = bitmap.getPixel(safeX, safeY)
            redSum += Color.red(pixel)
            greenSum += Color.green(pixel)
            blueSum += Color.blue(pixel)
        }

        val count = samplePoints.size.coerceAtLeast(1)
        return SampledColor(
            red = redSum / count,
            green = greenSum / count,
            blue = blueSum / count,
        )
    }

    private fun extractVisionTargets(snapshot: JSONObject, rootPackageName: String): List<VisionTarget> {
        val data = snapshot.optJSONObject("data") ?: return emptyList()
        val elements = data.optJSONArray("elements") ?: return emptyList()
        val screenBounds = parseBoundsFromSnapshot(elements) ?: return emptyList()
        val screenArea = screenBounds.width().toLong() * screenBounds.height().toLong()
        val nodes = mutableListOf<SnapshotNode>()
        val rejectStats = mutableMapOf(
            VisionRejectReason.NOT_VISIBLE to 0,
            VisionRejectReason.HAS_MEANINGFUL_LABEL to 0,
            VisionRejectReason.NON_VISUAL_OR_NON_ACTIONABLE to 0,
            VisionRejectReason.INVALID_BOUNDS to 0,
            VisionRejectReason.TOO_LARGE to 0,
            VisionRejectReason.CHILD_PREFERRED to 0,
        )

        for (index in 0 until elements.length()) {
            val node = elements.optJSONObject(index) ?: continue
            val bounds = parseBounds(node.optString("bounds"))
            if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
                rejectStats[VisionRejectReason.INVALID_BOUNDS] = rejectStats.getValue(VisionRejectReason.INVALID_BOUNDS) + 1
                continue
            }

            nodes += SnapshotNode(
                id = node.optString("id"),
                parentId = node.optString("parent_id").takeIf { it.isNotBlank() && it != "null" },
                bounds = bounds,
                depth = node.optInt("depth"),
                childCount = node.optInt("child_count"),
                className = node.optString("class_name"),
                isClickable = node.optBoolean("is_clickable"),
                isScrollable = node.optBoolean("is_scrollable"),
                isEditable = node.optBoolean("is_editable"),
                isFocusable = node.optBoolean("is_focusable"),
                text = node.optString("text"),
                desc = node.optString("desc"),
                isVisibleToUser = node.optBoolean("is_visible_to_user", true),
            )
        }

        val nodeById = nodes.associateBy { it.id }
        val candidateNodes = mutableListOf<SnapshotNode>()

        for (node in nodes) {
            val rejectReason = getVisionRejectReason(node)
            if (rejectReason != null) {
                rejectStats[rejectReason] = rejectStats.getValue(rejectReason) + 1
                continue
            }

            val area = node.bounds.width().toLong() * node.bounds.height().toLong()
            if (screenArea > 0L && area > (screenArea * MAX_VISION_AREA_RATIO).toLong()) {
                rejectStats[VisionRejectReason.TOO_LARGE] = rejectStats.getValue(VisionRejectReason.TOO_LARGE) + 1
                continue
            }

            candidateNodes += node
        }

        val keptNodes = mutableListOf<SnapshotNode>()
        candidateNodes
            .sortedWith(compareBy<SnapshotNode> { it.area() }.thenByDescending { it.depth })
            .forEach { candidate ->
                val coveredByChild = keptNodes.any { kept ->
                    isDescendant(descendantId = kept.id, ancestorId = candidate.id, nodeById = nodeById) &&
                        intersectionArea(candidate.bounds, kept.bounds) >= kept.area() * MIN_CHILD_COVERAGE_RATIO
                }

                if (coveredByChild) {
                    rejectStats[VisionRejectReason.CHILD_PREFERRED] = rejectStats.getValue(VisionRejectReason.CHILD_PREFERRED) + 1
                    return@forEach
                }

                keptNodes += candidate
            }

        val targets = keptNodes.map {
            VisionTarget(
                id = it.id,
                bounds = it.bounds,
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

    private fun getVisionRejectReason(node: SnapshotNode): VisionRejectReason? {
        if (!node.isVisibleToUser) {
            return VisionRejectReason.NOT_VISIBLE
        }

        val text = node.text
        val desc = node.desc
        if (hasMeaningfulLabel(text) || hasMeaningfulLabel(desc)) {
            return VisionRejectReason.HAS_MEANINGFUL_LABEL
        }

        val className = node.className
        val isViewLikeClass = className.contains("View", ignoreCase = true) ||
            className.contains("Layout", ignoreCase = true)
        val isVisualClass = className.contains("Image", ignoreCase = true) ||
            className.contains("Icon", ignoreCase = true) ||
            className.contains("WebView", ignoreCase = true)
        val isActionableWithoutLabel = node.isClickable ||
            node.isScrollable ||
            node.isEditable ||
            node.isFocusable

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
            append(", too_large=")
            append(rejectStats.getValue(VisionRejectReason.TOO_LARGE))
            append(", child_preferred=")
            append(rejectStats.getValue(VisionRejectReason.CHILD_PREFERRED))
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

    private fun isDescendant(
        descendantId: String,
        ancestorId: String,
        nodeById: Map<String, SnapshotNode>,
    ): Boolean {
        var currentId = nodeById[descendantId]?.parentId
        while (!currentId.isNullOrBlank()) {
            if (currentId == ancestorId) {
                return true
            }
            currentId = nodeById[currentId]?.parentId
        }
        return false
    }

    private fun intersectionArea(first: Rect, second: Rect): Long {
        val left = maxOf(first.left, second.left)
        val top = maxOf(first.top, second.top)
        val right = minOf(first.right, second.right)
        val bottom = minOf(first.bottom, second.bottom)
        if (right <= left || bottom <= top) {
            return 0L
        }
        return (right - left).toLong() * (bottom - top).toLong()
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

    private data class SnapshotNode(
        val id: String,
        val parentId: String?,
        val bounds: Rect,
        val depth: Int,
        val childCount: Int,
        val className: String,
        val isClickable: Boolean,
        val isScrollable: Boolean,
        val isEditable: Boolean,
        val isFocusable: Boolean,
        val text: String = "",
        val desc: String = "",
        val isVisibleToUser: Boolean = true,
    ) {
        fun area(): Long = bounds.width().toLong() * bounds.height().toLong()
    }

    private data class PreparedVisionSegment(
        val id: String,
        val bounds: Rect,
        val bitmap: Bitmap,
    )

    private data class SampledColor(
        val red: Int,
        val green: Int,
        val blue: Int,
    ) {
        fun toHexString(): String {
            return String.format("#%02X%02X%02X", red, green, blue)
        }
    }

    private data class CaptureWindow(
        val windowId: Int,
        val boundsInScreen: Rect,
    )

    private enum class VisionRejectReason {
        NOT_VISIBLE,
        HAS_MEANINGFUL_LABEL,
        NON_VISUAL_OR_NON_ACTIONABLE,
        INVALID_BOUNDS,
        TOO_LARGE,
        CHILD_PREFERRED,
    }

    companion object {
        private const val TAG = "AmadeusAccessibility"
        private const val FORCED_REPEAT_MIN_INTERVAL_MS = 2_500L
        private const val VISION_CROP_MAX_EDGE_PX = 1080
        private const val MAX_VISION_SEGMENTS = 64
        private const val MAX_VISION_AREA_RATIO = 0.35
        private const val MIN_CHILD_COVERAGE_RATIO = 0.6f
        private const val VISION_FILTER_LOG_SAMPLE_COUNT = 8
        private const val OCR_TASK_TIMEOUT_MS = 1_200L
        private const val REQUEST_EVENT_TYPE = "TYPE_SNAPSHOT_REQUESTED"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}

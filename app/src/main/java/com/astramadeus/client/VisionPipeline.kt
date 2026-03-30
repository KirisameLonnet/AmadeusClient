package com.astramadeus.client

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.astramadeus.client.ocr.OcrEngineRegistry
import com.astramadeus.client.ocr.RapidOcrEngine
import com.astramadeus.client.ocr.DetectedTextBlock
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Vision pipeline: selects UI nodes that need OCR, crops/scales bitmaps,
 * runs parallel OCR, and assembles vision segment results.
 */
object VisionPipeline {

    fun extractVisionTargets(snapshot: JSONObject, rootPackageName: String): List<VisionTarget> {
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
            val bounds = BoundsParser.parse(node.optString("bounds"))
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

    fun buildVisionSegments(
        context: Context,
        bitmap: Bitmap,
        targets: List<VisionTarget>,
        captureWindowBounds: Rect?,
        cancelled: AtomicBoolean = AtomicBoolean(false),
    ): JSONArray {
        val pipelineStartAt = System.currentTimeMillis()
        val segments = JSONArray()
        val preparedSegments = mutableListOf<PreparedVisionSegment>()

        try {
            targets.take(MAX_VISION_SEGMENTS).forEach { target ->
                if (cancelled.get()) {
                    Log.d(TAG, "Vision pipeline cancelled during crop preparation")
                    return JSONArray()
                }
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

            val configuredParallelism = OcrPipelineConfig.getMaxParallelism(context)
            val ocrResultByNodeId = runParallelOcr(context, preparedSegments, configuredParallelism, cancelled)
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

    /**
     * Full-page OCR fallback for sparse accessibility trees.
     * Uses an engine that supports det+cls+rec to scan the entire screenshot.
     */
    fun buildFullPageOcrSegments(
        context: Context,
        bitmap: Bitmap,
        cancelled: AtomicBoolean = AtomicBoolean(false),
    ): JSONArray {
        val startAt = System.currentTimeMillis()

        if (cancelled.get()) return JSONArray()

        // Use the dedicated fallback engine for full-page detection
        val engine = OcrEngineRegistry.getFallbackEngine(context)

        if (engine == null) {
            Log.d(TAG, "No fallback engine available for full-page detection")
            return JSONArray()
        }

        val scaledBitmap = scaleDown(bitmap, FULLPAGE_MAX_EDGE_PX)
        val scaleX = bitmap.width.toFloat() / scaledBitmap.width.toFloat()
        val scaleY = bitmap.height.toFloat() / scaledBitmap.height.toFloat()

        val textBlocks = try {
            engine.detectFullPage(scaledBitmap)
        } catch (e: Throwable) {
            Log.w(TAG, "Full-page OCR failed: ${e.message}")
            emptyList()
        } finally {
            if (scaledBitmap !== bitmap) {
                scaledBitmap.recycle()
            }
        }

        val segments = JSONArray()
        var index = 0
        textBlocks.forEach { block ->
            if (block.text.isBlank()) return@forEach

            // Scale bounds back to original bitmap coordinates
            val scaledBounds = android.graphics.Rect(
                (block.bounds.left * scaleX).toInt(),
                (block.bounds.top * scaleY).toInt(),
                (block.bounds.right * scaleX).toInt(),
                (block.bounds.bottom * scaleY).toInt(),
            )

            segments.put(
                JSONObject()
                    .put("id", "fullpage_ocr_$index")
                    .put("bounds", "[${scaledBounds.left},${scaledBounds.top}][${scaledBounds.right},${scaledBounds.bottom}]")
                    .put("ocr_text", block.text)
                    .put("score", block.score)
                    .put("source", "fullpage_ocr"),
            )
            index++
        }

        val elapsed = System.currentTimeMillis() - startAt
        Log.d(TAG, "Full-page OCR: engine=${engine.displayName} blocks=${segments.length()} cost_ms=$elapsed")
        return segments
    }

    private fun runParallelOcr(
        context: Context,
        segments: List<PreparedVisionSegment>,
        configuredParallelism: Int,
        cancelled: AtomicBoolean = AtomicBoolean(false),
    ): Map<String, String> {
        if (segments.isEmpty()) {
            return emptyMap()
        }

        val engine = OcrEngineRegistry.getActiveEngine(context)
        val workerCount = OcrPipelineConfig
            .normalizeParallelism(configuredParallelism)
            .coerceAtMost(segments.size)

        // Ensure RapidOCR engine pool has enough instances for the requested parallelism
        if (engine is RapidOcrEngine) {
            engine.ensurePoolSize(workerCount)
        }

        // Use persistent shared pool — never shut it down.
        // OpenMP initializes TLS on each thread that calls into native code.
        // When that Java thread is destroyed, OpenMP's __kmp_internal_end_thread
        // crashes in ___kmp_free. By reusing threads, we avoid this.
        val executor = ocrExecutor
        val deadlineAt = System.currentTimeMillis() + PARALLEL_OCR_DEADLINE_MS
        if (cancelled.get()) return emptyMap()

        val futures = mutableMapOf<String, Future<String>>()
        segments.forEach { segment ->
            futures[segment.id] = executor.submit<String> {
                if (cancelled.get()) return@submit ""
                engine.recognizeBitmap(segment.bitmap)
            }
        }

        if (cancelled.get()) {
            futures.values.forEach { it.cancel(true) }
            return emptyMap()
        }

        var completedCount = 0
        return futures.mapValues { (_, future) ->
            if (cancelled.get()) {
                future.cancel(true)
                return@mapValues ""
            }
            // Enforce total wall-clock deadline so 37 sequential gets
            // can't block for 37 × 1200ms = 44s
            val remaining = deadlineAt - System.currentTimeMillis()
            if (remaining <= 0) {
                future.cancel(true)
                return@mapValues ""
            }
            val timeout = remaining.coerceAtMost(OCR_TASK_TIMEOUT_MS)
            runCatching {
                future.get(timeout, TimeUnit.MILLISECONDS)
            }.onSuccess {
                completedCount++
            }.onFailure { error ->
                Log.w(TAG, "OCR worker timeout/failure: ${error.message}")
            }.getOrDefault("")
        }.also {
            if (completedCount < segments.size) {
                Log.d(TAG, "OCR deadline: completed $completedCount/${segments.size} segments within ${PARALLEL_OCR_DEADLINE_MS}ms")
            }
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

    private fun parseBoundsFromSnapshot(elements: JSONArray): Rect? {
        val tuple = BoundsParser.parseScreenBoundsFromElements(elements) ?: return null
        return Rect(tuple.left, tuple.top, tuple.right, tuple.bottom)
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

    data class VisionTarget(
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

    private enum class VisionRejectReason {
        NOT_VISIBLE,
        HAS_MEANINGFUL_LABEL,
        NON_VISUAL_OR_NON_ACTIONABLE,
        INVALID_BOUNDS,
        TOO_LARGE,
        CHILD_PREFERRED,
    }

    private const val TAG = "VisionPipeline"

    /** Max pool size matches the user-configurable OCR thread max (12). */
    private const val OCR_POOL_SIZE = 12

    /**
     * FIXED-SIZE thread pool for OCR tasks — threads are NEVER destroyed.
     * OpenMP (used by libRapidOcr.so) initializes TLS on each thread that calls
     * native code. When that thread is destroyed, __kmp_internal_end_thread fires
     * and crashes in ___kmp_free. A fixed pool with allowCoreThreadTimeOut(false)
     * guarantees core threads persist forever, preventing the crash.
     */
    private val ocrExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.ThreadPoolExecutor(
            OCR_POOL_SIZE, OCR_POOL_SIZE,
            Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS,
            java.util.concurrent.LinkedBlockingQueue(),
        ) { r ->
            Thread(r).apply {
                isDaemon = true
                name = "ocr-worker-${threadCount.incrementAndGet()}"
            }
        }.apply { allowCoreThreadTimeOut(false) }
    private val threadCount = java.util.concurrent.atomic.AtomicInteger(0)
    private const val VISION_CROP_MAX_EDGE_PX = 1080
    private const val MAX_VISION_SEGMENTS = 64
    private const val MAX_VISION_AREA_RATIO = 0.35
    private const val MIN_CHILD_COVERAGE_RATIO = 0.6f
    private const val VISION_FILTER_LOG_SAMPLE_COUNT = 8
    private const val OCR_TASK_TIMEOUT_MS = 1_200L
    /** Total wall-clock deadline for all parallel OCR tasks combined. */
    private const val PARALLEL_OCR_DEADLINE_MS = 5_000L
    private const val FULLPAGE_MAX_EDGE_PX = 1280
    /** Timeout for a single full-page OCR detectFullPage call.\n     *  GLM-OCR can take 10-20s on complex screens. */
    private const val FULLPAGE_OCR_TIMEOUT_MS = 20_000L
    const val SPARSE_TREE_THRESHOLD = 10
}

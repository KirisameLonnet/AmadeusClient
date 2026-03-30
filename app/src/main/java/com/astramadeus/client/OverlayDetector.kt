package com.astramadeus.client

import org.json.JSONArray
import org.json.JSONObject

/**
 * Detects popup ad overlays in UI snapshots by analyzing subtree info density
 * and multi-window state. Identifies close buttons by position/size heuristics.
 */
object OverlayDetector {

    data class OverlayResult(
        val overlayNodeIds: Set<String>,
        val closeButtonNodeId: String?,
        val overlayBounds: BoundsParser.RectTuple?,
    )

    /**
     * Analyze elements for popup overlay presence.
     *
     * @param elements the raw "elements" JSONArray from the snapshot
     * @param screenBounds the overall screen bounds
     * @param hasMultipleWindows whether multiple TYPE_APPLICATION windows are visible
     * @return overlay info, or null if no overlay detected
     */
    fun detect(
        elements: JSONArray,
        screenBounds: BoundsParser.RectTuple?,
        hasMultipleWindows: Boolean,
    ): OverlayResult? {
        if (screenBounds == null || elements.length() < MIN_ELEMENTS_FOR_DETECTION) {
            return null
        }

        val screenArea = screenArea(screenBounds)
        if (screenArea <= 0L) {
            return null
        }

        // Build node list and parent→children index
        val nodes = mutableListOf<DetectorNode>()
        for (index in 0 until elements.length()) {
            val node = elements.optJSONObject(index) ?: continue
            val id = node.optString("id")
            val parentId = node.optString("parent_id").takeIf { it.isNotBlank() && it != "null" }
            val bounds = BoundsParser.parseToTuple(node.optString("bounds"))
            val text = node.optString("text")
            val desc = node.optString("desc")
            val resourceId = node.optString("resource_id")
            val className = node.optString("class_name")
            val isClickable = node.optBoolean("is_clickable")
            val depth = node.optInt("depth")

            nodes += DetectorNode(
                id = id,
                parentId = parentId,
                bounds = bounds,
                depth = depth,
                hasText = text.isNotBlank() || desc.isNotBlank(),
                text = text,
                desc = desc,
                resourceId = resourceId,
                className = className,
                isClickable = isClickable,
            )
        }

        if (nodes.isEmpty()) {
            return null
        }

        val nodeById = nodes.associateBy { it.id }
        val childrenIndex = buildChildrenIndex(nodes)

        // Find info-void subtrees: shallow containers with large area and low text ratio
        val candidates = nodes.filter { it.depth <= MAX_OVERLAY_ROOT_DEPTH }
        var bestOverlay: OverlayCandidate? = null

        for (candidate in candidates) {
            val bounds = candidate.bounds ?: continue
            val area = nodeArea(bounds)
            val areaRatio = area.toFloat() / screenArea.toFloat()
            if (areaRatio < MIN_OVERLAY_AREA_RATIO) {
                continue
            }

            // Collect all descendants in this subtree
            val subtreeIds = mutableSetOf(candidate.id)
            val descendantNodes = collectDescendants(candidate.id, childrenIndex, nodeById)
            descendantNodes.forEach { subtreeIds += it.id }

            val totalInSubtree = descendantNodes.size + 1
            if (totalInSubtree < MIN_SUBTREE_NODES) {
                continue
            }

            val textBearing = descendantNodes.count { it.hasText }
            val textRatio = textBearing.toFloat() / totalInSubtree.toFloat()
            val isInfoVoid = textRatio < MAX_TEXT_RATIO_FOR_VOID

            // Need at least one signal: info void or multiple windows
            if (!isInfoVoid && !hasMultipleWindows) {
                continue
            }

            // Score: prefer larger area + lower text ratio
            val score = areaRatio * (1f - textRatio) * if (hasMultipleWindows && isInfoVoid) 2f else 1f

            if (bestOverlay == null || score > bestOverlay.score) {
                bestOverlay = OverlayCandidate(
                    rootId = candidate.id,
                    bounds = bounds,
                    subtreeIds = subtreeIds,
                    descendants = descendantNodes,
                    score = score,
                )
            }
        }

        if (bestOverlay == null) {
            return null
        }

        // Find close button within overlay subtree
        val closeButtonId = findCloseButton(bestOverlay, screenBounds)

        return OverlayResult(
            overlayNodeIds = bestOverlay.subtreeIds,
            closeButtonNodeId = closeButtonId,
            overlayBounds = bestOverlay.bounds,
        )
    }

    /**
     * Search for the most likely close button within the overlay.
     *
     * Heuristics:
     * - Clickable element
     * - Small area (icon-like, ≤40000px²)
     * - Position: top-right corner or bottom-center of overlay
     * - Text/resourceId contains close-related keywords
     */
    private fun findCloseButton(
        overlay: OverlayCandidate,
        screenBounds: BoundsParser.RectTuple,
    ): String? {
        data class CloseCandidate(
            val id: String,
            val score: Float,
        )

        val candidates = mutableListOf<CloseCandidate>()
        val ob = overlay.bounds

        for (node in overlay.descendants) {
            if (!node.isClickable) {
                continue
            }
            val nb = node.bounds ?: continue
            val area = nodeArea(nb)

            // Score components
            var score = 0f

            // 1. Keyword match (highest signal)
            val lookup = "${node.text} ${node.desc} ${node.resourceId}".lowercase()
            val hasCloseKeyword = CLOSE_KEYWORDS.any { it in lookup }
            if (hasCloseKeyword) {
                score += 5f
            }

            // 2. Size: should be small (icon-like)
            if (area <= CLOSE_BUTTON_MAX_AREA) {
                score += 2f
            } else if (area <= CLOSE_BUTTON_MAX_AREA * 2) {
                score += 0.5f
            } else {
                // Too large to be a close button
                continue
            }

            // 3. Position: right-top of overlay
            val centerX = (nb.left + nb.right) / 2f
            val centerY = (nb.top + nb.bottom) / 2f
            val overlayWidth = (ob.right - ob.left).coerceAtLeast(1)
            val overlayHeight = (ob.bottom - ob.top).coerceAtLeast(1)
            val normalizedX = (centerX - ob.left) / overlayWidth
            val normalizedY = (centerY - ob.top) / overlayHeight

            // Top-right corner (most common for X button)
            if (normalizedX > 0.7f && normalizedY < 0.25f) {
                score += 3f
            }
            // Bottom-center (common for "关闭" text button below popup)
            else if (normalizedX in 0.3f..0.7f && normalizedY > 0.85f) {
                score += 2f
            }
            // Just below the overlay (external close button)
            else if (normalizedX in 0.3f..0.7f && centerY > ob.bottom && centerY < ob.bottom + overlayHeight * 0.15f) {
                score += 2.5f
            }

            // 4. No text or very short text (icon buttons)
            if (node.text.isBlank() && node.desc.isBlank()) {
                score += 1f
            } else if ((node.text + node.desc).length <= 3) {
                score += 0.5f
            }

            if (score > 0f) {
                candidates += CloseCandidate(id = node.id, score = score)
            }
        }

        // Also check nodes just outside/below the overlay (close buttons sometimes sit outside)
        // This is already handled by the normalizedY > 0.85 check above

        return candidates.maxByOrNull { it.score }?.id
    }

    private fun buildChildrenIndex(nodes: List<DetectorNode>): Map<String, List<String>> {
        val index = mutableMapOf<String, MutableList<String>>()
        nodes.forEach { node ->
            val parentId = node.parentId
            if (!parentId.isNullOrBlank()) {
                index.getOrPut(parentId) { mutableListOf() }.add(node.id)
            }
        }
        return index
    }

    private fun collectDescendants(
        nodeId: String,
        childrenIndex: Map<String, List<String>>,
        nodeById: Map<String, DetectorNode>,
    ): List<DetectorNode> {
        val result = mutableListOf<DetectorNode>()
        val queue = ArrayDeque<String>()
        childrenIndex[nodeId]?.let { queue.addAll(it) }

        while (queue.isNotEmpty()) {
            val childId = queue.removeFirst()
            val childNode = nodeById[childId] ?: continue
            result.add(childNode)
            childrenIndex[childId]?.let { queue.addAll(it) }
        }

        return result
    }

    private fun screenArea(bounds: BoundsParser.RectTuple): Long {
        return (bounds.right - bounds.left).toLong().coerceAtLeast(0L) *
            (bounds.bottom - bounds.top).toLong().coerceAtLeast(0L)
    }

    private fun nodeArea(bounds: BoundsParser.RectTuple): Long {
        return (bounds.right - bounds.left).toLong().coerceAtLeast(0L) *
            (bounds.bottom - bounds.top).toLong().coerceAtLeast(0L)
    }

    private data class DetectorNode(
        val id: String,
        val parentId: String?,
        val bounds: BoundsParser.RectTuple?,
        val depth: Int,
        val hasText: Boolean,
        val text: String,
        val desc: String,
        val resourceId: String,
        val className: String,
        val isClickable: Boolean,
    )

    private data class OverlayCandidate(
        val rootId: String,
        val bounds: BoundsParser.RectTuple,
        val subtreeIds: Set<String>,
        val descendants: List<DetectorNode>,
        val score: Float,
    )

    private val CLOSE_KEYWORDS = listOf(
        "close", "dismiss", "cancel", "skip", "×", "✕", "✖", "✗",
        "关闭", "取消", "跳过", "不再显示", "我知道了", "知道了",
        "不感兴趣", "残忍拒绝", "放弃", "稍后再说",
    )

    private const val MIN_ELEMENTS_FOR_DETECTION = 5
    private const val MAX_OVERLAY_ROOT_DEPTH = 3
    private const val MIN_OVERLAY_AREA_RATIO = 0.15f
    private const val MAX_TEXT_RATIO_FOR_VOID = 0.20f
    private const val MIN_SUBTREE_NODES = 3
    private const val CLOSE_BUTTON_MAX_AREA = 40_000L
}

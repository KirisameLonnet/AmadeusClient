package com.astramadeus.client

import org.json.JSONArray
import org.json.JSONObject

private typealias RectTuple = BoundsParser.RectTuple

/**
 * Sanitizes raw UI snapshots for WebSocket transport: compacts nodes,
 * infers semantic hints, folds duplicates, and pre-computes tap points.
 */
object FrameSanitizer {

    fun sanitize(snapshot: JSONObject): JSONObject {
        val data = snapshot.optJSONObject("data") ?: return snapshot
        val ocrByNodeId = mutableMapOf<String, String>()
        val avgColorByNodeId = mutableMapOf<String, JSONObject>()
        val segments = data.optJSONArray("vision_segments")
        if (segments != null) {
            for (index in 0 until segments.length()) {
                val segment = segments.optJSONObject(index) ?: continue
                val id = segment.optString("id")
                val ocrText = segment.optString("ocr_text")

                if (id.isNotBlank() && ocrText.isNotBlank()) {
                    ocrByNodeId[id] = ocrText
                }

                if (id.isNotBlank()) {
                    val colorObject = JSONObject()
                    segment.optJSONArray("avg_color_rgb")?.let { colorObject.put("rgb", it) }
                    segment.optString("avg_color_hex").takeIf { it.isNotBlank() }?.let { colorObject.put("hex", it) }
                    if (colorObject.length() > 0) {
                        avgColorByNodeId[id] = colorObject
                    }
                }
            }
        }

        val elements = data.optJSONArray("elements") ?: return snapshot
        val screenBounds = parseScreenBounds(elements)
        val compactNodes = mutableListOf<CompactTransportNode>()
        for (index in 0 until elements.length()) {
            val node = elements.optJSONObject(index) ?: continue
            val nodeId = node.optString("id")
            val compactNode = compactNodeForTransport(
                node = node,
                ocrText = ocrByNodeId[nodeId],
                averageColor = avgColorByNodeId[nodeId],
                screenBounds = screenBounds,
            ) ?: continue
            compactNodes += compactNode
        }

        // Overlay / popup ad detection
        val hasMultipleWindows = data.optBoolean("has_multiple_windows", false)
        val overlayResult = OverlayDetector.detect(elements, screenBounds, hasMultipleWindows)
        if (overlayResult != null) {
            compactNodes.forEach { node ->
                if (node.id == overlayResult.closeButtonNodeId) {
                    node.payload.put("semantic_hint", "popup_close")
                } else if (node.id in overlayResult.overlayNodeIds) {
                    // Only override if no existing hint
                    if (!node.payload.has("semantic_hint")) {
                        node.payload.put("semantic_hint", "popup_ad")
                    }
                }
            }
            data.put("overlay_detected", true)
            overlayResult.overlayBounds?.let { bounds ->
                data.put("overlay_bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
            }
            overlayResult.closeButtonNodeId?.let { data.put("overlay_close_node_id", it) }
        }

        val compactElements = JSONArray()
        foldSemanticDuplicates(compactNodes).forEach { compactElements.put(it.payload) }

        data.put("elements", compactElements)

        // Preserve full-page OCR segments (not matched to any tree node)
        if (segments != null) {
            val fullPageSegments = JSONArray()
            for (index in 0 until segments.length()) {
                val segment = segments.optJSONObject(index) ?: continue
                if (segment.optString("source") == "fullpage_ocr") {
                    fullPageSegments.put(segment)
                }
            }
            if (fullPageSegments.length() > 0) {
                data.put("vision_segments", fullPageSegments)
            } else {
                data.remove("vision_segments")
            }
        } else {
            data.remove("vision_segments")
        }

        return snapshot
    }

    private fun compactNodeForTransport(
        node: JSONObject,
        ocrText: String?,
        averageColor: JSONObject?,
        screenBounds: RectTuple?,
    ): CompactTransportNode? {
        val isVisible = node.optBoolean("is_visible_to_user", true)
        if (!isVisible) {
            return null
        }

        val text = node.optString("text")
        val desc = node.optString("desc")
        val normalizedOcr = ocrText.orEmpty().trim()
        val displayText = when {
            text.isNotBlank() -> text
            desc.isNotBlank() -> desc
            normalizedOcr.isNotBlank() -> normalizedOcr
            else -> ""
        }

        val isActionable =
            node.optBoolean("is_clickable") ||
                node.optBoolean("is_scrollable") ||
                node.optBoolean("is_editable") ||
                node.optBoolean("is_focusable") ||
                node.optBoolean("is_long_clickable")
        val bounds = BoundsParser.parseToTuple(node.optString("bounds"))
        val shortClassName = shortenClassName(node.optString("class_name"))
        val shortResourceId = shortenResourceId(node.optString("resource_id"))
        val isGenericContainer = isGenericContainerClass(shortClassName)
        val semanticHint = inferSemanticHint(
            shortResourceId = shortResourceId,
            shortClassName = shortClassName,
            displayText = displayText,
            bounds = bounds,
            screenBounds = screenBounds,
            isActionable = isActionable,
        )
        val isIconLikeAction = isLikelyIconAction(bounds, isActionable, displayText)

        val hasUsefulSemantic =
            displayText.isNotBlank() ||
                isActionable ||
                semanticHint != null

        if (!hasUsefulSemantic) {
            return null
        }

        val isVisualClass = shortClassName in VISUAL_CLASS_NAMES
        val isLowValueNode =
            displayText.isBlank() &&
                !isActionable &&
                !isVisualClass &&
                shortResourceId.isBlank() &&
                node.optInt("child_count") <= 1
        if (isLowValueNode) {
            return null
        }

        if (displayText.isBlank() && !isActionable && isGenericContainer && semanticHint == null) {
            val area = bounds?.let { (it.right - it.left).toLong() * (it.bottom - it.top).toLong() } ?: 0L
            val isNegligible = area < CONTAINER_MIN_AREA || node.optInt("child_count") == 0
            if (isNegligible) {
                return null
            }
        }

        val compact = JSONObject()
            .put("id", node.optString("id"))
            .put("parent_id", node.opt("parent_id"))
            .put("depth", node.optInt("depth"))
            .put("index_in_parent", node.optInt("index_in_parent"))
            .put("bounds", node.optString("bounds"))
            .put("class_name", shortClassName)
            .put("resource_id", shortResourceId)
            .put("text", displayText)
            .put("text_source", resolveTextSource(text, desc, normalizedOcr))
            .put("is_clickable", node.optBoolean("is_clickable"))
            .put("is_scrollable", node.optBoolean("is_scrollable"))
            .put("is_editable", node.optBoolean("is_editable"))
            .put("is_focusable", node.optBoolean("is_focusable"))
            .put("is_selected", node.optBoolean("is_selected"))
            .put("is_visible_to_user", isVisible)
            .put("is_checkable", node.optBoolean("is_checkable"))
            .put("is_checked", node.optBoolean("is_checked"))
            .put("is_long_clickable", node.optBoolean("is_long_clickable"))

        if (isIconLikeAction) {
            compact.put("icon_button", true)
        }
        semanticHint?.let { compact.put("semantic_hint", it) }

        val shouldIncludeAverageColor = normalizedOcr.isNotBlank() || isActionable
        if (shouldIncludeAverageColor) {
            averageColor?.let {
                compact.put("avg_color", JSONObject(it.toString()))
            }
        }

        bounds?.let { rect ->
            if (isActionable) {
                val tapX = rect.left + ((rect.right - rect.left) / 2)
                val tapY = rect.top + ((rect.bottom - rect.top) / 2)
                compact.put("tap_point", JSONArray().put(tapX).put(tapY))
            }
        }

        return CompactTransportNode(
            id = node.optString("id"),
            parentId = node.optString("parent_id").takeIf { it.isNotBlank() && it != "null" },
            bounds = bounds,
            displayText = displayText,
            normalizedDisplayText = normalizeTextForComparison(displayText),
            textSource = resolveTextSource(text, desc, normalizedOcr),
            isActionable = isActionable,
            isGenericContainer = isGenericContainer,
            shortClassName = shortClassName,
            resourceId = shortResourceId,
            payload = compact,
        )
    }

    private fun foldSemanticDuplicates(nodes: List<CompactTransportNode>): List<CompactTransportNode> {
        if (nodes.isEmpty()) {
            return emptyList()
        }

        val dedupedNodes = deduplicateEquivalentNodes(nodes)
        val nodeById = dedupedNodes.associateBy { it.id }
        val childrenIndex = buildChildrenIndex(nodeById)

        return dedupedNodes.filterNot { node ->
            if (shouldDropEmptyClickableView(node, nodeById)) {
                return@filterNot true
            }

            if (shouldDropActionableWrapper(node, nodeById, childrenIndex)) {
                return@filterNot true
            }

            if (node.isActionable || node.normalizedDisplayText.isBlank()) {
                return@filterNot false
            }

            val descendants = collectDescendants(node.id, childrenIndex, nodeById)
            if (descendants.isEmpty()) {
                return@filterNot false
            }

            val sameTextDescendant = descendants.any { descendant ->
                descendant.normalizedDisplayText.isNotBlank() &&
                    descendant.normalizedDisplayText == node.normalizedDisplayText &&
                    isInside(descendant.bounds, node.bounds)
            }
            if (sameTextDescendant) {
                return@filterNot true
            }

            val matchedLength = descendants
                .asSequence()
                .filter { it.normalizedDisplayText.length >= MIN_DEDUP_TEXT_LENGTH }
                .filter { descendant ->
                    descendant.isActionable || isInside(descendant.bounds, node.bounds)
                }
                .map { it.normalizedDisplayText }
                .distinct()
                .filter { childText -> node.normalizedDisplayText.contains(childText) }
                .sumOf { it.length }

            matchedLength >= maxOf(MIN_PARENT_FOLD_TEXT_COVERAGE, (node.normalizedDisplayText.length * PARENT_FOLD_RATIO).toInt())
        }
    }

    /**
     * Builds a parent→children index for O(1) child lookups instead of
     * traversing all nodes for each collectDescendants call.
     */
    private fun buildChildrenIndex(
        nodeById: Map<String, CompactTransportNode>,
    ): Map<String, List<String>> {
        val index = mutableMapOf<String, MutableList<String>>()
        nodeById.values.forEach { node ->
            val parentId = node.parentId
            if (!parentId.isNullOrBlank()) {
                index.getOrPut(parentId) { mutableListOf() }.add(node.id)
            }
        }
        return index
    }

    /**
     * Collects all descendants of the given node using the pre-built children index.
     * This is O(descendants) instead of the previous O(N × depth) approach.
     */
    private fun collectDescendants(
        nodeId: String,
        childrenIndex: Map<String, List<String>>,
        nodeById: Map<String, CompactTransportNode>,
    ): List<CompactTransportNode> {
        val result = mutableListOf<CompactTransportNode>()
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

    private fun deduplicateEquivalentNodes(nodes: List<CompactTransportNode>): List<CompactTransportNode> {
        return nodes
            .groupBy { node ->
                listOf(
                    node.bounds?.left,
                    node.bounds?.top,
                    node.bounds?.right,
                    node.bounds?.bottom,
                    node.normalizedDisplayText,
                    node.textSource,
                ).joinToString("|")
            }
            .values
            .map { group ->
                group.maxWithOrNull(compareBy<CompactTransportNode> { it.textScore() }.thenBy { it.id }) ?: group.first()
            }
    }

    private fun shouldDropEmptyClickableView(
        node: CompactTransportNode,
        nodeById: Map<String, CompactTransportNode>,
    ): Boolean {
        if (!node.isActionable || node.normalizedDisplayText.isNotBlank()) {
            return false
        }
        if (node.shortClassName != "view" || node.resourceId.isNotBlank()) {
            return false
        }

        val bounds = node.bounds ?: return false
        val area = rectArea(bounds)
        val siblings = node.parentId
            ?.let { parentId -> nodeById.values.filter { it.parentId == parentId && it.id != node.id } }
            .orEmpty()

        val hasMeaningfulSibling = siblings.any { sibling ->
            sibling.normalizedDisplayText.isNotBlank() || sibling.resourceId.isNotBlank() || sibling.shortClassName != "view"
        }

        return hasMeaningfulSibling || area >= EMPTY_CLICKABLE_VIEW_MAX_AREA
    }

    private fun shouldDropActionableWrapper(
        node: CompactTransportNode,
        nodeById: Map<String, CompactTransportNode>,
        childrenIndex: Map<String, List<String>>,
    ): Boolean {
        if (!node.isActionable || node.normalizedDisplayText.isNotBlank() || !node.isGenericContainer) {
            return false
        }

        val descendants = collectDescendants(node.id, childrenIndex, nodeById)
        if (descendants.isEmpty()) {
            return false
        }

        val meaningfulActionableDescendant = descendants.any { descendant ->
            descendant.isActionable &&
                (descendant.normalizedDisplayText.isNotBlank() || !descendant.isGenericContainer) &&
                isMostlyInside(descendant.bounds, node.bounds)
        }
        if (meaningfulActionableDescendant) {
            return true
        }

        val meaningfulNonActionableDescendant = descendants.any { descendant ->
            descendant.normalizedDisplayText.isNotBlank() && isMostlyInside(descendant.bounds, node.bounds)
        }
        return meaningfulNonActionableDescendant
    }

    private fun isInside(child: RectTuple?, parent: RectTuple?): Boolean {
        if (child == null || parent == null) {
            return false
        }
        return child.left >= parent.left &&
            child.top >= parent.top &&
            child.right <= parent.right &&
            child.bottom <= parent.bottom
    }

    private fun isMostlyInside(child: RectTuple?, parent: RectTuple?): Boolean {
        if (child == null || parent == null) {
            return false
        }
        val intersection = intersectionArea(child, parent)
        val childArea = rectArea(child)
        if (childArea <= 0L) {
            return false
        }
        return intersection >= (childArea * 0.75f).toLong()
    }

    private fun rectArea(rect: RectTuple): Long {
        return (rect.right - rect.left).toLong().coerceAtLeast(0L) *
            (rect.bottom - rect.top).toLong().coerceAtLeast(0L)
    }

    private fun intersectionArea(first: RectTuple, second: RectTuple): Long {
        val left = maxOf(first.left, second.left)
        val top = maxOf(first.top, second.top)
        val right = minOf(first.right, second.right)
        val bottom = minOf(first.bottom, second.bottom)
        if (right <= left || bottom <= top) {
            return 0L
        }
        return (right - left).toLong() * (bottom - top).toLong()
    }

    private fun normalizeTextForComparison(value: String): String {
        return value
            .replace(Regex("\\s+"), "")
            .replace(Regex("[|,，。.!！:：;；·•()（）\\[\\]【】<>《》\"'`-]"), "")
            .trim()
    }

    private fun resolveTextSource(text: String, desc: String, ocrText: String): String {
        return when {
            text.isNotBlank() -> "native_text"
            desc.isNotBlank() -> "native_desc"
            ocrText.isNotBlank() -> "ocr"
            else -> "none"
        }
    }

    private fun shortenClassName(value: String): String {
        val simple = value.substringAfterLast('.').trim()
        if (simple.isBlank()) {
            return ""
        }
        return when {
            simple.contains("Text", ignoreCase = true) -> "text"
            simple.contains("Button", ignoreCase = true) -> "button"
            simple.contains("Edit", ignoreCase = true) -> "input"
            simple.contains("Image", ignoreCase = true) -> "image"
            simple.contains("WebView", ignoreCase = true) -> "web"
            simple.contains("Recycler", ignoreCase = true) -> "list"
            simple.contains("Scroll", ignoreCase = true) -> "scroll"
            simple.contains("Frame", ignoreCase = true) -> "frame"
            simple.contains("Linear", ignoreCase = true) -> "linear"
            simple.contains("Relative", ignoreCase = true) -> "relative"
            simple.contains("Constraint", ignoreCase = true) -> "constraint"
            simple.contains("View", ignoreCase = true) -> "view"
            else -> simple.lowercase()
        }
    }

    private fun shortenResourceId(value: String): String {
        return value.substringAfter('/').substringAfter(':').trim()
    }

    private fun isGenericContainerClass(value: String): Boolean {
        return value in setOf("view", "frame", "linear", "relative", "constraint")
    }

    private fun parseScreenBounds(elements: JSONArray): RectTuple? {
        return BoundsParser.parseScreenBoundsFromElements(elements)
    }

    private fun inferSemanticHint(
        shortResourceId: String,
        shortClassName: String,
        displayText: String,
        bounds: RectTuple?,
        screenBounds: RectTuple?,
        isActionable: Boolean,
    ): String? {
        val lookup = (shortResourceId + " " + displayText).lowercase()
        return when {
            "search" in lookup || "搜索" in lookup -> "search"
            "scan" in lookup || "扫码" in lookup -> "scan"
            "back" in lookup || "返回" in lookup -> "back"
            "close" in lookup || "关闭" in lookup || "取消" in lookup -> "close"
            "menu" in lookup || "more" in lookup || "更多" in lookup -> "more"
            "cart" in lookup || "购物车" in lookup -> "cart"
            "message" in lookup || "消息" in lookup || "chat" in lookup -> "message"
            "home" in lookup || "首页" in lookup -> "home"
            "mine" in lookup || "我的" in lookup || "profile" in lookup -> "profile"
            !isActionable || bounds == null || screenBounds == null -> null
            isLikelyIconAction(bounds, isActionable, displayText) && isTopToolbarZone(bounds, screenBounds) && isRightToolbarZone(bounds, screenBounds) -> "toolbar_action"
            isLikelyIconAction(bounds, isActionable, displayText) && isTopToolbarZone(bounds, screenBounds) && isLeftToolbarZone(bounds, screenBounds) -> "back"
            isLikelyIconAction(bounds, isActionable, displayText) && isBottomNavigationZone(bounds, screenBounds) -> inferBottomNavigationHint(shortResourceId, bounds, screenBounds)
            else -> null
        }
    }

    private fun inferBottomNavigationHint(
        shortResourceId: String,
        bounds: RectTuple,
        screenBounds: RectTuple,
    ): String {
        val lookup = shortResourceId.lowercase()
        if ("home" in lookup) return "home"
        if ("message" in lookup || "msg" in lookup) return "message"
        if ("cart" in lookup) return "cart"
        if ("mine" in lookup || "profile" in lookup || "me" in lookup) return "profile"

        val screenWidth = (screenBounds.right - screenBounds.left).coerceAtLeast(1)
        val centerX = (bounds.left + bounds.right) / 2f
        val normalizedX = (centerX - screenBounds.left) / screenWidth.toFloat()
        return when {
            normalizedX < 0.2f -> "home"
            normalizedX < 0.4f -> "promo"
            normalizedX < 0.6f -> "message"
            normalizedX < 0.8f -> "cart"
            else -> "profile"
        }
    }

    private fun isLikelyIconAction(bounds: RectTuple?, isActionable: Boolean, displayText: String): Boolean {
        if (!isActionable || bounds == null || displayText.isNotBlank()) {
            return false
        }
        val width = (bounds.right - bounds.left).coerceAtLeast(0)
        val height = (bounds.bottom - bounds.top).coerceAtLeast(0)
        if (width == 0 || height == 0) {
            return false
        }
        val area = width.toLong() * height.toLong()
        val ratio = width.toFloat() / height.toFloat()
        return area in ICON_MIN_AREA..ICON_MAX_AREA && ratio in ICON_MIN_ASPECT..ICON_MAX_ASPECT
    }

    private fun isTopToolbarZone(bounds: RectTuple, screenBounds: RectTuple): Boolean {
        return bounds.top <= screenBounds.top + ((screenBounds.bottom - screenBounds.top) * TOP_TOOLBAR_ZONE_RATIO).toInt()
    }

    private fun isRightToolbarZone(bounds: RectTuple, screenBounds: RectTuple): Boolean {
        return bounds.left >= screenBounds.left + ((screenBounds.right - screenBounds.left) * RIGHT_TOOLBAR_ZONE_RATIO).toInt()
    }

    private fun isLeftToolbarZone(bounds: RectTuple, screenBounds: RectTuple): Boolean {
        return bounds.right <= screenBounds.left + ((screenBounds.right - screenBounds.left) * LEFT_TOOLBAR_ZONE_RATIO).toInt()
    }

    private fun isBottomNavigationZone(bounds: RectTuple, screenBounds: RectTuple): Boolean {
        return bounds.top >= screenBounds.top + ((screenBounds.bottom - screenBounds.top) * BOTTOM_NAV_ZONE_RATIO).toInt()
    }

    private data class CompactTransportNode(
        val id: String,
        val parentId: String?,
        val bounds: RectTuple?,
        val displayText: String,
        val normalizedDisplayText: String,
        val textSource: String,
        val isActionable: Boolean,
        val isGenericContainer: Boolean,
        val shortClassName: String,
        val resourceId: String,
        val payload: JSONObject,
    ) {
        fun textScore(): Int {
            val sourceScore = when (textSource) {
                "native_text" -> 3
                "native_desc" -> 2
                "ocr" -> 1
                else -> 0
            }
            return normalizedDisplayText.length * 10 + sourceScore
        }
    }

    private const val MIN_DEDUP_TEXT_LENGTH = 2
    private const val MIN_PARENT_FOLD_TEXT_COVERAGE = 15
    private const val PARENT_FOLD_RATIO = 0.70f
    private const val EMPTY_CLICKABLE_VIEW_MAX_AREA = 48_000L
    private const val CONTAINER_MIN_AREA = 5_000L
    private const val ICON_MIN_AREA = 1_600L
    private const val ICON_MAX_AREA = 40_000L
    private const val ICON_MIN_ASPECT = 0.55f
    private const val ICON_MAX_ASPECT = 1.8f
    private const val TOP_TOOLBAR_ZONE_RATIO = 0.22f
    private const val RIGHT_TOOLBAR_ZONE_RATIO = 0.72f
    private const val LEFT_TOOLBAR_ZONE_RATIO = 0.28f
    private const val BOTTOM_NAV_ZONE_RATIO = 0.82f
    private val VISUAL_CLASS_NAMES = setOf("image", "web", "button")
}

package com.astramadeus.client

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

object UiFrameWebSocketClient {

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR,
    }

    @Volatile
    var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    @Volatile
    var lastError: String? = null
        private set

    private val client = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sequence = AtomicLong(0L)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var connectedUrl: String? = null

    @Volatile
    private var manuallyClosed: Boolean = false

    private val reconnectTask = Runnable {
        val context = appContext ?: return@Runnable
        if (!WebSocketPushConfig.isEnabled(context)) {
            return@Runnable
        }
        connect(WebSocketPushConfig.getUrl(context))
    }

    fun syncConfig(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (!WebSocketPushConfig.isEnabled(appCtx)) {
            disconnect(manual = true)
            return
        }
        connect(WebSocketPushConfig.getUrl(appCtx))
    }

    fun sendUiFrame(context: Context, snapshotJson: String) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (!WebSocketPushConfig.isEnabled(appCtx)) {
            return
        }

        val targetUrl = WebSocketPushConfig.getUrl(appCtx)
        connect(targetUrl)

        val payload = runCatching {
            val sanitizedSnapshot = sanitizeSnapshotForTransport(JSONObject(snapshotJson))
            val frameId = buildFrameId(sanitizedSnapshot)
            val frame = JSONObject()
                .put("type", "ui_frame_full")
                .put("protocol_version", "1.0")
                .put("seq", sequence.incrementAndGet())
                .put("sent_at_ms", System.currentTimeMillis())
                .put(
                    "payload",
                    JSONObject()
                        .put(
                            "frame_meta",
                            JSONObject()
                                .put("frame_id", frameId)
                                .put("timestamp_ms", sanitizedSnapshot.optLong("timestamp")),
                        )
                        .put("ui_state", sanitizedSnapshot),
                )
            frame.toString()
        }.getOrElse {
            Log.w(TAG, "Failed to wrap ui frame, sending raw snapshot")
            snapshotJson
        }

        val sent = socket?.send(payload) ?: false
        if (!sent) {
            Log.w(TAG, "WS send failed; state=$connectionState")
        }
    }

    private fun connect(url: String) {
        if (url.isBlank()) {
            return
        }

        if (connectedUrl == url && socket != null) {
            return
        }

        disconnect(manual = false)
        manuallyClosed = false
        connectionState = ConnectionState.CONNECTING
        lastError = null

        Log.i(TAG, "Connecting websocket: $url")
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, createListener(url))
        connectedUrl = url
    }

    private fun disconnect(manual: Boolean) {
        manuallyClosed = manual
        mainHandler.removeCallbacks(reconnectTask)
        socket?.close(1000, "closed")
        socket = null
        connectedUrl = null
        connectionState = ConnectionState.DISCONNECTED
    }

    private fun createListener(url: String): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connectionState = ConnectionState.CONNECTED
                lastError = null
                Log.i(TAG, "WebSocket connected: $url")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                connectionState = ConnectionState.DISCONNECTED
                Log.i(TAG, "WebSocket closing code=$code reason=$reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connectionState = ConnectionState.DISCONNECTED
                socket = null
                connectedUrl = null
                Log.i(TAG, "WebSocket closed code=$code reason=$reason")
                scheduleReconnectIfNeeded()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connectionState = ConnectionState.ERROR
                lastError = t.message ?: "Unknown websocket error"
                socket = null
                connectedUrl = null
                Log.w(TAG, "WebSocket failure: ${lastError}")
                scheduleReconnectIfNeeded()
            }
        }
    }

    private fun scheduleReconnectIfNeeded() {
        val context = appContext ?: return
        if (manuallyClosed || !WebSocketPushConfig.isEnabled(context)) {
            return
        }
        mainHandler.removeCallbacks(reconnectTask)
        mainHandler.postDelayed(reconnectTask, RECONNECT_DELAY_MS)
    }

    private fun sanitizeSnapshotForTransport(snapshot: JSONObject): JSONObject {
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

        val compactElements = JSONArray()
        foldSemanticDuplicates(compactNodes).forEach { compactElements.put(it.payload) }

        data.put("elements", compactElements)
        data.remove("vision_segments")

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
                node.optBoolean("is_focusable")
        val bounds = parseBounds(node.optString("bounds"))
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

        val isLowValueNode =
            displayText.isBlank() &&
                !isActionable &&
                shortResourceId.isBlank() &&
                node.optInt("child_count") <= 1
        if (isLowValueNode) {
            return null
        }

        if (displayText.isBlank() && !isActionable && isGenericContainer && semanticHint == null) {
            return null
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
                val tapX = rect.first + ((rect.third - rect.first) / 2)
                val tapY = rect.second + ((rect.fourth - rect.second) / 2)
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
        return dedupedNodes.filterNot { node ->
            if (shouldDropEmptyClickableView(node, nodeById)) {
                return@filterNot true
            }

            if (shouldDropActionableWrapper(node, nodeById)) {
                return@filterNot true
            }

            if (node.isActionable || node.normalizedDisplayText.isBlank()) {
                return@filterNot false
            }

            val descendants = collectDescendants(node.id, nodeById)
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

    private fun deduplicateEquivalentNodes(nodes: List<CompactTransportNode>): List<CompactTransportNode> {
        return nodes
            .groupBy { node ->
                listOf(
                    node.bounds?.first,
                    node.bounds?.second,
                    node.bounds?.third,
                    node.bounds?.fourth,
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
    ): Boolean {
        if (!node.isActionable || node.normalizedDisplayText.isNotBlank() || !node.isGenericContainer) {
            return false
        }

        val descendants = collectDescendants(node.id, nodeById)
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

    private fun collectDescendants(
        nodeId: String,
        nodeById: Map<String, CompactTransportNode>,
    ): List<CompactTransportNode> {
        return nodeById.values.filter { candidate ->
            var currentParentId = candidate.parentId
            while (!currentParentId.isNullOrBlank()) {
                if (currentParentId == nodeId) {
                    return@filter true
                }
                currentParentId = nodeById[currentParentId]?.parentId
            }
            false
        }
    }

    private fun isInside(child: RectTuple?, parent: RectTuple?): Boolean {
        if (child == null || parent == null) {
            return false
        }
        return child.first >= parent.first &&
            child.second >= parent.second &&
            child.third <= parent.third &&
            child.fourth <= parent.fourth
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
        return (rect.third - rect.first).toLong().coerceAtLeast(0L) *
            (rect.fourth - rect.second).toLong().coerceAtLeast(0L)
    }

    private fun intersectionArea(first: RectTuple, second: RectTuple): Long {
        val left = maxOf(first.first, second.first)
        val top = maxOf(first.second, second.second)
        val right = minOf(first.third, second.third)
        val bottom = minOf(first.fourth, second.fourth)
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
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        var found = false
        for (index in 0 until elements.length()) {
            val node = elements.optJSONObject(index) ?: continue
            val rect = parseBounds(node.optString("bounds")) ?: continue
            left = minOf(left, rect.first)
            top = minOf(top, rect.second)
            right = maxOf(right, rect.third)
            bottom = maxOf(bottom, rect.fourth)
            found = true
        }
        return if (found) RectTuple(left, top, right, bottom) else null
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

        val screenWidth = (screenBounds.third - screenBounds.first).coerceAtLeast(1)
        val centerX = (bounds.first + bounds.third) / 2f
        val normalizedX = (centerX - screenBounds.first) / screenWidth.toFloat()
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
        val width = (bounds.third - bounds.first).coerceAtLeast(0)
        val height = (bounds.fourth - bounds.second).coerceAtLeast(0)
        if (width == 0 || height == 0) {
            return false
        }
        val area = width.toLong() * height.toLong()
        val ratio = width.toFloat() / height.toFloat()
        return area in 1_600L..40_000L && ratio in 0.55f..1.8f
    }

    private fun isTopToolbarZone(bounds: RectTuple, screenBounds: RectTuple): Boolean {
        return bounds.second <= screenBounds.second + ((screenBounds.fourth - screenBounds.second) * 0.22f).toInt()
    }

    private fun isRightToolbarZone(bounds: RectTuple, screenBounds: RectTuple): Boolean {
        return bounds.first >= screenBounds.first + ((screenBounds.third - screenBounds.first) * 0.72f).toInt()
    }

    private fun isLeftToolbarZone(bounds: RectTuple, screenBounds: RectTuple): Boolean {
        return bounds.third <= screenBounds.first + ((screenBounds.third - screenBounds.first) * 0.28f).toInt()
    }

    private fun isBottomNavigationZone(bounds: RectTuple, screenBounds: RectTuple): Boolean {
        return bounds.second >= screenBounds.second + ((screenBounds.fourth - screenBounds.second) * 0.82f).toInt()
    }

    private fun parseBounds(value: String): RectTuple? {
        val match = BOUNDS_REGEX.matchEntire(value) ?: return null
        return RectTuple(
            first = match.groupValues[1].toInt(),
            second = match.groupValues[2].toInt(),
            third = match.groupValues[3].toInt(),
            fourth = match.groupValues[4].toInt(),
        )
    }

    private fun buildFrameId(snapshot: JSONObject): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(snapshot.toString().toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it) }.take(16)
    }

    private data class RectTuple(
        val first: Int,
        val second: Int,
        val third: Int,
        val fourth: Int,
    )

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

    private const val TAG = "UiFrameWebSocket"
    private const val RECONNECT_DELAY_MS = 1_500L
    private val BOUNDS_REGEX = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")
    private const val MIN_DEDUP_TEXT_LENGTH = 2
    private const val MIN_PARENT_FOLD_TEXT_COVERAGE = 8
    private const val PARENT_FOLD_RATIO = 0.45f
    private const val EMPTY_CLICKABLE_VIEW_MAX_AREA = 48_000L
}

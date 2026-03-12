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
        val segments = data.optJSONArray("vision_segments")
        if (segments != null) {
            for (index in 0 until segments.length()) {
                val segment = segments.optJSONObject(index) ?: continue
                val id = segment.optString("id")
                val ocrText = segment.optString("ocr_text")

                if (id.isNotBlank() && ocrText.isNotBlank()) {
                    ocrByNodeId[id] = ocrText
                }
            }
        }

        val elements = data.optJSONArray("elements") ?: return snapshot
        val compactElements = JSONArray()
        for (index in 0 until elements.length()) {
            val node = elements.optJSONObject(index) ?: continue
            val compactNode = compactNodeForTransport(node, ocrByNodeId[node.optString("id")]) ?: continue
            compactElements.put(compactNode)
        }

        data.put("elements", compactElements)
        data.remove("vision_segments")

        return snapshot
    }

    private fun compactNodeForTransport(node: JSONObject, ocrText: String?): JSONObject? {
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

        val hasUsefulSemantic =
            displayText.isNotBlank() ||
                node.optString("resource_id").isNotBlank() ||
                isActionable

        if (!hasUsefulSemantic) {
            return null
        }

        val compact = JSONObject()
            .put("id", node.optString("id"))
            .put("parent_id", node.opt("parent_id"))
            .put("depth", node.optInt("depth"))
            .put("index_in_parent", node.optInt("index_in_parent"))
            .put("bounds", node.optString("bounds"))
            .put("class_name", node.optString("class_name"))
            .put("resource_id", node.optString("resource_id"))
            .put("text", text)
            .put("desc", desc)
            .put("ocr_text", normalizedOcr)
            .put("display_text", displayText)
            .put("is_clickable", node.optBoolean("is_clickable"))
            .put("is_scrollable", node.optBoolean("is_scrollable"))
            .put("is_editable", node.optBoolean("is_editable"))
            .put("is_focusable", node.optBoolean("is_focusable"))
            .put("is_selected", node.optBoolean("is_selected"))
            .put("is_visible_to_user", isVisible)

        parseBounds(node.optString("bounds"))?.let { rect ->
            if (isActionable) {
                val tapX = rect.first + ((rect.third - rect.first) / 2)
                val tapY = rect.second + ((rect.fourth - rect.second) / 2)
                compact.put("tap_point", JSONArray().put(tapX).put(tapY))
            }
        }

        return compact
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

    private const val TAG = "UiFrameWebSocket"
    private const val RECONNECT_DELAY_MS = 1_500L
    private val BOUNDS_REGEX = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")
}

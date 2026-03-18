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
    private val connectionLock = Any()
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
            val sanitizedSnapshot = FrameSanitizer.sanitize(JSONObject(snapshotJson))
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

        val activeSocket = synchronized(connectionLock) { socket }
        val sent = activeSocket?.send(payload) ?: false
        if (!sent) {
            Log.w(TAG, "WS send failed; state=$connectionState")
        }
    }

    private fun connect(url: String) {
        if (url.isBlank()) {
            return
        }

        synchronized(connectionLock) {
            if ((connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.CONNECTED) && connectedUrl == url && socket != null) {
                return
            }

            disconnectInternal(manual = false)
            manuallyClosed = false
            connectionState = ConnectionState.CONNECTING
            lastError = null
            connectedUrl = url

            Log.i(TAG, "Connecting websocket: $url")
            val request = Request.Builder().url(url).build()
            socket = client.newWebSocket(request, createListener(url))
        }
    }

    private fun disconnect(manual: Boolean) {
        synchronized(connectionLock) {
            disconnectInternal(manual)
        }
    }

    private fun disconnectInternal(manual: Boolean) {
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
                synchronized(connectionLock) {
                    if (socket !== webSocket) {
                        return
                    }
                    connectionState = ConnectionState.CONNECTED
                    lastError = null
                }
                Log.i(TAG, "WebSocket connected: $url")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(connectionLock) {
                    if (socket === webSocket) {
                        connectionState = ConnectionState.DISCONNECTED
                    }
                }
                Log.i(TAG, "WebSocket closing code=$code reason=$reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(connectionLock) {
                    if (socket === webSocket) {
                        connectionState = ConnectionState.DISCONNECTED
                        socket = null
                        connectedUrl = null
                    }
                }
                Log.i(TAG, "WebSocket closed code=$code reason=$reason")
                scheduleReconnectIfNeeded()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val errorText = t.message ?: "Unknown websocket error"
                synchronized(connectionLock) {
                    if (socket === webSocket) {
                        connectionState = ConnectionState.ERROR
                        lastError = errorText
                        socket = null
                        connectedUrl = null
                    }
                }
                Log.w(TAG, "WebSocket failure: $errorText")
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

    private fun buildFrameId(snapshot: JSONObject): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(snapshot.toString().toByteArray(Charsets.UTF_8))
        return bytes.toHexString().take(16)
    }

    private fun ByteArray.toHexString(): String {
        val result = CharArray(size * 2)
        for (index in indices) {
            val value = this[index].toInt() and 0xFF
            result[index * 2] = HEX_CHARS[value ushr 4]
            result[index * 2 + 1] = HEX_CHARS[value and 0x0F]
        }
        return String(result)
    }

    private const val TAG = "UiFrameWebSocket"
    private const val RECONNECT_DELAY_MS = 1_500L
    private val HEX_CHARS = "0123456789abcdef".toCharArray()
}

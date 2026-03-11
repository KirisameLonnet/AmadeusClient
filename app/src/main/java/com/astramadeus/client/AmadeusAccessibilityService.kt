package com.astramadeus.client

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

class AmadeusAccessibilityService : AccessibilityService() {

    private var lastSnapshotAt: Long = 0L
    private var lastSnapshotSignature: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
        SnapshotBroadcasts.publishStatus(this, true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !shouldHandleEvent(event.eventType)) {
            return
        }

        if (event.packageName?.toString() == packageName) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastSnapshotAt < SNAPSHOT_DEBOUNCE_MS) {
            return
        }

        val root = rootInActiveWindow ?: return
        val rootPackageName = root.packageName?.toString().orEmpty()
        val eventPackageName = event.packageName?.toString().orEmpty()

        if (rootPackageName.isEmpty() || rootPackageName == packageName) {
            return
        }

        if (eventPackageName.isNotEmpty() && eventPackageName != rootPackageName && eventPackageName == SYSTEM_UI_PACKAGE) {
            return
        }

        val snapshot = buildSnapshot(root, event, now)
        val snapshotSignature = buildSnapshotSignature(snapshot)

        if (snapshotSignature == lastSnapshotSignature) {
            return
        }

        lastSnapshotAt = now
        lastSnapshotSignature = snapshotSignature

        Log.i(TAG, snapshot.toString())
        SnapshotBroadcasts.publishSnapshot(this, snapshot.toString())
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        SnapshotBroadcasts.publishStatus(this, false)
        super.onDestroy()
    }

    private fun buildSnapshot(
        root: AccessibilityNodeInfo,
        event: AccessibilityEvent,
        timestamp: Long,
    ): JSONObject {
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
                    .put("activity_name", event.className?.toString().orEmpty())
                    .put("event_type", AccessibilityEvent.eventTypeToString(event.eventType))
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
    companion object {
        private const val TAG = "AmadeusAccessibility"
        private const val SNAPSHOT_DEBOUNCE_MS = 350L
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}

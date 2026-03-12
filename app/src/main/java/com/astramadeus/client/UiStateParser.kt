package com.astramadeus.client

import android.graphics.Rect
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject

object UiStateParser {
    private val boundsRegex = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")

    fun parse(rawSnapshot: String): UiStatePreview? {
        return runCatching {
            val root = JSONObject(rawSnapshot)
            val data = root.getJSONObject("data")
            val elements = data.getJSONArray("elements")

            val nodes = mutableListOf<PreviewNode>()
            var minLeft = Int.MAX_VALUE
            var minTop = Int.MAX_VALUE
            var maxRight = Int.MIN_VALUE
            var maxBottom = Int.MIN_VALUE

            for (index in 0 until elements.length()) {
                val item = elements.getJSONObject(index)
                val bounds = parseBounds(item.optString("bounds")) ?: continue

                minLeft = minOf(minLeft, bounds.left)
                minTop = minOf(minTop, bounds.top)
                maxRight = maxOf(maxRight, bounds.right)
                maxBottom = maxOf(maxBottom, bounds.bottom)

                nodes += PreviewNode(
                    id = item.optString("id"),
                    text = item.optString("text"),
                    desc = item.optString("desc"),
                    className = item.optString("class_name"),
                    bounds = bounds,
                    isClickable = item.optBoolean("is_clickable"),
                    isScrollable = item.optBoolean("is_scrollable"),
                    isEditable = item.optBoolean("is_editable"),
                )
            }

            if (nodes.isEmpty()) {
                return null
            }

            val screenBounds = Rect(minLeft, minTop, maxRight, maxBottom)
            val visionSegments = mutableListOf<PreviewVisionSegment>()
            val rawSegments = data.optJSONArray("vision_segments")

            if (rawSegments != null) {
                for (index in 0 until rawSegments.length()) {
                    val item = rawSegments.optJSONObject(index) ?: continue
                    val bounds = parseBounds(item.optString("bounds")) ?: continue
                    val encoded = item.optString("image_base64")
                    if (encoded.isBlank()) {
                        continue
                    }

                    val decoded = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: continue
                    val bitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.size) ?: continue
                    visionSegments += PreviewVisionSegment(
                        id = item.optString("id"),
                        bounds = bounds,
                        bitmap = bitmap,
                    )
                }
            }

            UiStatePreview(
                packageName = data.optString("package_name"),
                activityName = data.optString("activity_name"),
                eventType = data.optString("event_type"),
                screenBounds = screenBounds,
                nodes = nodes,
                visionSegments = visionSegments,
            )
        }.getOrNull()
    }

    private fun parseBounds(value: String): Rect? {
        val match = boundsRegex.matchEntire(value) ?: return null
        val left = match.groupValues[1].toInt()
        val top = match.groupValues[2].toInt()
        val right = match.groupValues[3].toInt()
        val bottom = match.groupValues[4].toInt()
        return Rect(left, top, right, bottom)
    }
}

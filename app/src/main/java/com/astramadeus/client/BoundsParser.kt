package com.astramadeus.client

import android.graphics.Rect
import org.json.JSONArray

object BoundsParser {
    private val BOUNDS_REGEX = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")

    fun parse(value: String): Rect? {
        val match = BOUNDS_REGEX.matchEntire(value) ?: return null
        val left = match.groupValues[1].toInt()
        val top = match.groupValues[2].toInt()
        val right = match.groupValues[3].toInt()
        val bottom = match.groupValues[4].toInt()
        return Rect(left, top, right, bottom)
    }

    fun parseToTuple(value: String): RectTuple? {
        val match = BOUNDS_REGEX.matchEntire(value) ?: return null
        return RectTuple(
            left = match.groupValues[1].toInt(),
            top = match.groupValues[2].toInt(),
            right = match.groupValues[3].toInt(),
            bottom = match.groupValues[4].toInt(),
        )
    }

    data class RectTuple(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    /**
     * Parse screen bounds from a JSON elements array by computing the
     * bounding box of all elements with parseable bounds.
     */
    fun parseScreenBoundsFromElements(elements: JSONArray): RectTuple? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        var found = false
        for (index in 0 until elements.length()) {
            val node = elements.optJSONObject(index) ?: continue
            val rect = parseToTuple(node.optString("bounds")) ?: continue
            left = minOf(left, rect.left)
            top = minOf(top, rect.top)
            right = maxOf(right, rect.right)
            bottom = maxOf(bottom, rect.bottom)
            found = true
        }
        return if (found) RectTuple(left, top, right, bottom) else null
    }
}

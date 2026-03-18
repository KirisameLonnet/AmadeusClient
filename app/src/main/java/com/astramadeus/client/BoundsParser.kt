package com.astramadeus.client

import android.graphics.Rect

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
            first = match.groupValues[1].toInt(),
            second = match.groupValues[2].toInt(),
            third = match.groupValues[3].toInt(),
            fourth = match.groupValues[4].toInt(),
        )
    }

    data class RectTuple(
        val first: Int,
        val second: Int,
        val third: Int,
        val fourth: Int,
    )
}

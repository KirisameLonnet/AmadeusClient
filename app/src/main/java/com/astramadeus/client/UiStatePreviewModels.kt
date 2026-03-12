package com.astramadeus.client

import android.graphics.Bitmap

data class PreviewNode(
    val id: String,
    val text: String,
    val desc: String,
    val className: String,
    val bounds: android.graphics.Rect,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean,
)

data class UiStatePreview(
    val packageName: String,
    val activityName: String,
    val eventType: String,
    val screenBounds: android.graphics.Rect,
    val nodes: List<PreviewNode>,
    val visionSegments: List<PreviewVisionSegment>,
)

data class PreviewVisionSegment(
    val id: String,
    val bounds: android.graphics.Rect,
    val bitmap: Bitmap,
)

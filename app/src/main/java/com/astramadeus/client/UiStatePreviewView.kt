package com.astramadeus.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class UiStatePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var preview: UiStatePreview? = null
    private var showVisionMosaic: Boolean = true
    private var showNodeOverlay: Boolean = true
    private var showOcrOverlay: Boolean = true
    private var visionOcrResults: Map<String, String> = emptyMap()

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFF9F6EE")
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#FFD5C7A0")
    }

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0B4A6FA5")
    }

    private val clickableNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#162E7D32")
    }

    private val visionSegmentBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#FF8F5A2B")
    }

    private val ocrLabelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC1B4332")
    }

    private val ocrLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF6FFF8")
        textSize = 22f
    }

    private val ocrTextMeasurePaint = TextPaint(ocrLabelTextPaint)

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1A2A3A")
        textSize = 28f
    }

    fun submit(
        preview: UiStatePreview?,
        showVisionMosaic: Boolean,
        showNodeOverlay: Boolean,
        showOcrOverlay: Boolean,
        visionOcrResults: Map<String, String>,
    ) {
        this.preview = preview
        this.showVisionMosaic = showVisionMosaic
        this.showNodeOverlay = showNodeOverlay
        this.showOcrOverlay = showOcrOverlay
        this.visionOcrResults = visionOcrResults
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#FFF3EEE4"))

        val data = preview ?: run {
            drawCenteredText(canvas, "No preview")
            return
        }

        if (data.screenBounds.width() <= 0 || data.screenBounds.height() <= 0) {
            drawCenteredText(canvas, "Invalid bounds")
            return
        }

        val horizontalPadding = 24f
        val verticalPadding = 24f

        val usableWidth = width - horizontalPadding * 2f
        val usableHeight = height - verticalPadding * 2f
        val scale = minOf(
            usableWidth / data.screenBounds.width().toFloat(),
            usableHeight / data.screenBounds.height().toFloat(),
        )

        val previewWidth = data.screenBounds.width() * scale
        val previewHeight = data.screenBounds.height() * scale
        val previewLeft = (width - previewWidth) / 2f
        val previewTop = verticalPadding + max(0f, (usableHeight - previewHeight) / 2f)
        val previewRect = RectF(
            previewLeft,
            previewTop,
            previewLeft + previewWidth,
            previewTop + previewHeight,
        )

        canvas.drawRoundRect(previewRect, 20f, 20f, framePaint)
        canvas.drawRoundRect(previewRect, 20f, 20f, borderPaint)

        if (showVisionMosaic && data.visionSegments.isNotEmpty()) {
            data.visionSegments.forEach { segment ->
                val left = previewRect.left + (segment.bounds.left - data.screenBounds.left) * scale
                val top = previewRect.top + (segment.bounds.top - data.screenBounds.top) * scale
                val right = previewRect.left + (segment.bounds.right - data.screenBounds.left) * scale
                val bottom = previewRect.top + (segment.bounds.bottom - data.screenBounds.top) * scale

                if (right <= left || bottom <= top) {
                    return@forEach
                }

                val rect = RectF(left, top, right, bottom)
                canvas.drawBitmap(segment.bitmap, null, rect, null)
                canvas.drawRoundRect(rect, 8f, 8f, visionSegmentBorderPaint)
            }
        }

        if (showNodeOverlay) {
            data.nodes.forEach { node ->
                val left = previewRect.left + (node.bounds.left - data.screenBounds.left) * scale
                val top = previewRect.top + (node.bounds.top - data.screenBounds.top) * scale
                val right = previewRect.left + (node.bounds.right - data.screenBounds.left) * scale
                val bottom = previewRect.top + (node.bounds.bottom - data.screenBounds.top) * scale

                if (right <= left || bottom <= top) {
                    return@forEach
                }

                val rect = RectF(left, top, right, bottom)
                val paint = if (node.isClickable) clickableNodePaint else nodePaint
                canvas.drawRoundRect(rect, 8f, 8f, paint)
                canvas.drawRoundRect(rect, 8f, 8f, borderPaint)

                val nodeLabel = buildNodeLabel(node)
                if (nodeLabel.isNotBlank() && rect.width() > 120f && rect.height() > 48f) {
                    val clipped = TextUtils.ellipsize(
                        nodeLabel,
                        android.text.TextPaint(labelPaint),
                        rect.width() - 12f,
                        TextUtils.TruncateAt.END,
                    ).toString()
                    canvas.drawText(clipped, rect.left + 6f, rect.top + 30f, labelPaint)
                }
            }
        }

        if (showOcrOverlay) {
            drawVisionOcrLayer(canvas, data, previewRect, scale)
        }
    }

    private fun buildNodeLabel(node: PreviewNode): String {
        return when {
            node.text.isNotBlank() -> node.text
            node.desc.isNotBlank() -> node.desc
            node.className.isNotBlank() -> node.className.substringAfterLast('.')
            else -> node.id
        }
    }

    private fun drawVisionOcrLayer(
        canvas: Canvas,
        data: UiStatePreview,
        previewRect: RectF,
        scale: Float,
    ) {
        if (visionOcrResults.isEmpty() || data.visionSegments.isEmpty()) {
            return
        }

        data.visionSegments.forEach { segment ->
            val label = visionOcrResults[segment.id]?.trim().orEmpty()
            if (label.isBlank()) {
                return@forEach
            }

            val left = previewRect.left + (segment.bounds.left - data.screenBounds.left) * scale
            val top = previewRect.top + (segment.bounds.top - data.screenBounds.top) * scale
            val right = previewRect.left + (segment.bounds.right - data.screenBounds.left) * scale
            val bottom = previewRect.top + (segment.bounds.bottom - data.screenBounds.top) * scale

            if (right <= left || bottom <= top) {
                return@forEach
            }

            val anchorRect = RectF(left, top, right, bottom)
            val bannerHeight = (anchorRect.height() * 0.24f).coerceIn(22f, 44f)
            val bannerRect = RectF(
                anchorRect.left,
                (anchorRect.bottom - bannerHeight).coerceAtLeast(anchorRect.top),
                anchorRect.right,
                anchorRect.bottom,
            )

            ocrLabelTextPaint.textSize = (bannerRect.height() * 0.58f).coerceIn(12f, 24f)
            ocrTextMeasurePaint.textSize = ocrLabelTextPaint.textSize
            val clippedText = TextUtils.ellipsize(
                label.replace("\n", " "),
                ocrTextMeasurePaint,
                (bannerRect.width() - 12f).coerceAtLeast(0f),
                TextUtils.TruncateAt.END,
            ).toString()

            canvas.drawRoundRect(bannerRect, 6f, 6f, ocrLabelBackgroundPaint)

            val textBaseline = bannerRect.top +
                (bannerRect.height() - (ocrLabelTextPaint.descent() + ocrLabelTextPaint.ascent())) / 2f
            canvas.drawText(clippedText, bannerRect.left + 6f, textBaseline, ocrLabelTextPaint)
        }
    }

    private fun drawCenteredText(canvas: Canvas, text: String) {
        val x = width / 2f
        val y = height / 2f
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, x, y, labelPaint)
        labelPaint.textAlign = Paint.Align.LEFT
    }
}

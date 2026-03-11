package com.astramadeus.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class UiStatePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var preview: UiStatePreview? = null

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
        color = Color.parseColor("#1A4A6FA5")
    }

    private val clickableNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4D2E7D32")
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1A2A3A")
        textSize = 28f
    }

    private val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3F4F5F")
        textSize = 30f
    }

    fun submit(preview: UiStatePreview?) {
        this.preview = preview
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
        val topInfoHeight = 120f
        val bottomPadding = 24f

        val usableWidth = width - horizontalPadding * 2f
        val usableHeight = height - topInfoHeight - bottomPadding
        val scale = minOf(
            usableWidth / data.screenBounds.width().toFloat(),
            usableHeight / data.screenBounds.height().toFloat(),
        )

        val previewWidth = data.screenBounds.width() * scale
        val previewHeight = data.screenBounds.height() * scale
        val previewLeft = (width - previewWidth) / 2f
        val previewTop = topInfoHeight + max(0f, (usableHeight - previewHeight) / 2f)
        val previewRect = RectF(
            previewLeft,
            previewTop,
            previewLeft + previewWidth,
            previewTop + previewHeight,
        )

        canvas.drawRoundRect(previewRect, 20f, 20f, framePaint)
        canvas.drawRoundRect(previewRect, 20f, 20f, borderPaint)

        drawMeta(canvas, data)

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

    private fun buildNodeLabel(node: PreviewNode): String {
        return when {
            node.text.isNotBlank() -> node.text
            node.desc.isNotBlank() -> node.desc
            node.className.isNotBlank() -> node.className.substringAfterLast('.')
            else -> node.id
        }
    }

    private fun drawMeta(canvas: Canvas, preview: UiStatePreview) {
        val meta = "${preview.packageName} | ${preview.eventType}"
        canvas.drawText(meta, 20f, 40f, metaPaint)
        if (preview.activityName.isNotBlank()) {
            canvas.drawText(preview.activityName, 20f, 80f, metaPaint)
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

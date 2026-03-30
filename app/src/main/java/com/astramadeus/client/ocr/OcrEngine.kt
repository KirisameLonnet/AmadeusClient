package com.astramadeus.client.ocr

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Abstraction for OCR engines. Implement this interface to add a new
 * OCR backend (ML Kit, RapidOCR, PaddleOCR, Tesseract, etc.).
 */
interface OcrEngine {
    /** Unique identifier, e.g. "mlkit", "rapidocr". Used for persistence. */
    val id: String

    /** Human-readable name shown in settings, e.g. "ML Kit", "RapidOCR". */
    val displayName: String

    /** Whether the engine's native libraries / models are available at runtime. */
    val isAvailable: Boolean

    /**
     * Pre-load models or allocate resources.
     * Called once when the engine is first activated. No-op if not needed.
     */
    fun initialize()

    /**
     * Run OCR on a bitmap and return recognized text.
     * This is called from a worker thread; implementations may block.
     *
     * @return recognized text, or empty string on failure
     */
    fun recognizeBitmap(bitmap: Bitmap): String

    /** Release models and native resources. */
    fun release()

    /**
     * Whether this engine supports full-page text detection (det+cls+rec).
     * When true, [detectFullPage] can be called as a fallback for sparse trees.
     */
    val supportsFullPageDetection: Boolean
        get() = false

    /**
     * Whether this engine should ONLY be used for full-page OCR,
     * never for per-node text recognition (too slow for individual crops).
     */
    val isFullPageOnly: Boolean
        get() = false

    /**
     * Run full-page OCR detection: finds text regions, classifies direction,
     * and recognizes text. Returns a list of detected text blocks with bounds.
     *
     * Only RapidOCR currently supports this (via native C++ pipeline).
     *
     * @return detected text blocks, or empty list if unsupported/failed
     */
    fun detectFullPage(bitmap: Bitmap): List<DetectedTextBlock> = emptyList()
}

/**
 * A text block detected by full-page OCR, with its bounding box and confidence.
 */
data class DetectedTextBlock(
    val text: String,
    val bounds: Rect,
    val score: Float,
)

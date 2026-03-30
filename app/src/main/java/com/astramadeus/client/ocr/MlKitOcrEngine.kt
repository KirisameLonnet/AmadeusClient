package com.astramadeus.client.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * ML Kit Chinese text recognition engine.
 * Uses Google's on-device ML Kit recognizer (no network required after model download).
 */
class MlKitOcrEngine : OcrEngine {

    override val id: String = ID
    override val displayName: String = "ML Kit"
    override val isAvailable: Boolean = true

    private val recognizerLocal = ThreadLocal<TextRecognizer>()

    private fun recognizer(): TextRecognizer {
        val existing = recognizerLocal.get()
        if (existing != null) {
            return existing
        }
        val created = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizerLocal.set(created)
        return created
    }

    override fun initialize() {
        // ML Kit lazy-initializes on first use; nothing to pre-load.
    }

    override fun recognizeBitmap(bitmap: Bitmap): String {
        return runCatching {
            val image = InputImage.fromBitmap(bitmap, 0)
            val output = Tasks.await(recognizer().process(image))
            output.text.trim()
        }.getOrElse { error ->
            Log.w(TAG, "OCR bitmap failed: ${error.message}")
            ""
        }
    }

    override fun detectFullPage(bitmap: Bitmap): List<DetectedTextBlock> {
        return runCatching {
            val image = InputImage.fromBitmap(bitmap, 0)
            val output = Tasks.await(recognizer().process(image))
            
            val blocks = mutableListOf<DetectedTextBlock>()
            for (block in output.textBlocks) {
                val rect = block.boundingBox ?: continue
                if (block.text.isNotBlank()) {
                    blocks.add(
                        DetectedTextBlock(
                            text = block.text.trim(),
                            bounds = rect,
                            score = 1.0f
                        )
                    )
                }
            }
            blocks
        }.getOrElse { error ->
            Log.w(TAG, "ML Kit detectFullPage failed: ${error.message}")
            emptyList()
        }
    }

    override val supportsFullPageDetection: Boolean = true

    override fun release() {
        recognizerLocal.get()?.close()
        recognizerLocal.remove()
    }

    companion object {
        const val ID = "mlkit"
        private const val TAG = "MlKitOcr"
    }
}

package com.astramadeus.client

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognizer

object VisionOcrProcessor {
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

    fun recognizeBitmapBlocking(bitmap: Bitmap): String {
        return runCatching {
            val image = InputImage.fromBitmap(bitmap, 0)
            val output = Tasks.await(recognizer().process(image))
            output.text.trim()
        }.getOrElse { error ->
            Log.w(TAG, "OCR bitmap failed: ${error.message}")
            ""
        }
    }

    private const val TAG = "VisionOcr"
}

package com.astramadeus.client

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object VisionOcrProcessor {
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    suspend fun recognizeSegments(
        packageName: String,
        segments: List<PreviewVisionSegment>,
    ): Map<String, String> {
        if (segments.isEmpty()) {
            return emptyMap()
        }

        return withContext(Dispatchers.Default) {
            val results = mutableMapOf<String, String>()

            segments.forEach { segment ->
                val text = runCatching {
                    val image = InputImage.fromBitmap(segment.bitmap, 0)
                    val output = Tasks.await(recognizer.process(image))
                    output.text.trim()
                }.onFailure { error ->
                    Log.w(TAG, "OCR failed id=${segment.id}: ${error.message}")
                }.getOrDefault("")

                if (text.isNotBlank()) {
                    results[segment.id] = text
                    Log.d(
                        TAG,
                        "OCR hit id=${segment.id} bounds=${segment.bounds.left},${segment.bounds.top},${segment.bounds.right},${segment.bounds.bottom} text=${text.replace("\n", " ")}",
                    )
                } else {
                    Log.d(
                        TAG,
                        "OCR miss id=${segment.id} bounds=${segment.bounds.left},${segment.bounds.top},${segment.bounds.right},${segment.bounds.bottom}",
                    )
                }
            }

            val sample = results.entries
                .take(4)
                .joinToString(separator = " | ") { (id, value) ->
                    val normalized = value.replace("\n", " ").trim()
                    val clipped = if (normalized.length > 24) normalized.substring(0, 24) + "..." else normalized
                    "$id:$clipped"
                }

            Log.d(
                TAG,
                "OCR package=$packageName segments=${segments.size} with_text=${results.size} sample=${if (sample.isBlank()) "none" else sample}",
            )

            results
        }
    }

    private const val TAG = "VisionOcr"
}

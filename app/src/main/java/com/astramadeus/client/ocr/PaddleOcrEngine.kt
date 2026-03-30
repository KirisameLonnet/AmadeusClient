package com.astramadeus.client.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.astramadeus.client.OcrPipelineConfig
import java.io.File
import java.nio.FloatBuffer
import java.util.LinkedList

/**
 * PaddleOCR-based OCR engine using ONNX Runtime (pure Kotlin, no native C++ dependency).
 *
 * Supports both per-node recognition ([recognizeBitmap]) and full-page
 * detection+recognition ([detectFullPage]) using downloaded PP-OCRv4 ONNX models:
 *   - det: ch_PP-OCRv4_det_infer.onnx  (text region detection)
 *   - cls: ch_ppocr_mobile_v2.0_cls_infer.onnx  (text direction classification)
 *   - rec: ch_PP-OCRv4_rec_infer.onnx  (text recognition + CTC decode)
 *
 * Models must be downloaded via [OcrModelDownloader] before use.
 */
class PaddleOcrEngine(private val appContext: Context) : OcrEngine {

    override val id: String = ID
    override val displayName: String = "PaddleOCR"
    override val isAvailable: Boolean
        get() = OcrModelDownloader.allModelsPresent(appContext)

    @Volatile private var ortEnv: OrtEnvironment? = null
    @Volatile private var detSession: OrtSession? = null
    @Volatile private var clsSession: OrtSession? = null
    @Volatile private var recSession: OrtSession? = null
    @Volatile private var charDict: List<String>? = null
    @Volatile private var initialized = false

    override fun initialize() {
        if (initialized) return
        if (!isAvailable) {
            Log.w(TAG, "Models not downloaded, cannot initialize")
            return
        }

        try {
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env

            val modelDir = OcrModelDownloader.getModelDir(appContext)
            val useGpu = OcrPipelineConfig.getUseGpu(appContext)

            fun makeOptions(): OrtSession.SessionOptions {
                val opts = OrtSession.SessionOptions()
                opts.setIntraOpNumThreads(2)
                if (useGpu) {
                    try {
                        opts.addNnapi()
                    } catch (e: Throwable) {
                        Log.w(TAG, "NNAPI unavailable, using CPU: ${e.message}")
                    }
                }
                return opts
            }

            recSession = env.createSession(
                File(modelDir, REC_MODEL_FILE).absolutePath, makeOptions()
            )

            val detFile = File(modelDir, DET_MODEL_FILE)
            if (detFile.exists()) {
                detSession = env.createSession(detFile.absolutePath, makeOptions())
            }

            val clsFile = File(modelDir, CLS_MODEL_FILE)
            if (clsFile.exists()) {
                clsSession = env.createSession(clsFile.absolutePath, makeOptions())
            }

            charDict = loadCharDict(appContext)
            initialized = true
            Log.i(TAG, "PaddleOCR initialized (gpu=$useGpu) det=${detSession != null} cls=${clsSession != null} dict=${charDict?.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PaddleOCR: ${e.message}", e)
            release()
        }
    }

    // ---- Per-node recognition (existing) ----

    override fun recognizeBitmap(bitmap: Bitmap): String {
        val session = recSession ?: return ""
        val dict = charDict ?: return ""
        val env = ortEnv ?: return ""

        return runCatching {
            recognizeText(env, session, bitmap, dict)
        }.getOrElse { error ->
            Log.w(TAG, "Recognition failed: ${error.message}")
            ""
        }
    }

    // ---- Full-page detection + recognition ----

    override val supportsFullPageDetection: Boolean
        get() = detSession != null

    override fun detectFullPage(bitmap: Bitmap): List<DetectedTextBlock> {
        val env = ortEnv ?: return emptyList()
        val det = detSession ?: return emptyList()
        val rec = recSession ?: return emptyList()
        val dict = charDict ?: return emptyList()

        return runCatching {
            val boxes = detectTextRegions(env, det, bitmap)
            if (boxes.isEmpty()) return emptyList()

            boxes.mapNotNull { box ->
                // Clamp to bitmap bounds
                val clampedLeft = box.left.coerceIn(0, bitmap.width - 1)
                val clampedTop = box.top.coerceIn(0, bitmap.height - 1)
                val clampedRight = box.right.coerceIn(clampedLeft + 1, bitmap.width)
                val clampedBottom = box.bottom.coerceIn(clampedTop + 1, bitmap.height)
                val w = clampedRight - clampedLeft
                val h = clampedBottom - clampedTop
                if (w < 4 || h < 4) return@mapNotNull null

                val crop = Bitmap.createBitmap(bitmap, clampedLeft, clampedTop, w, h)

                // Classify direction and rotate if needed
                val oriented = classifyAndRotate(env, crop)

                // Recognize text
                val text = recognizeText(env, rec, oriented, dict)
                if (oriented !== crop) oriented.recycle()
                if (crop !== bitmap) crop.recycle()

                if (text.isBlank()) return@mapNotNull null

                DetectedTextBlock(
                    text = text,
                    bounds = Rect(clampedLeft, clampedTop, clampedRight, clampedBottom),
                    score = 1.0f,
                )
            }
        }.getOrElse { error ->
            Log.w(TAG, "detectFullPage failed: ${error.message}", error)
            emptyList()
        }
    }

    // ---- Detection pipeline ----

    /**
     * Run the DB (Differentiable Binarization) text detection model.
     * Input: image resized so max side ≤ [DET_MAX_SIDE], dimensions rounded to multiples of 32.
     * Output: probability map → threshold → connected components → bounding boxes.
     */
    private fun detectTextRegions(env: OrtEnvironment, session: OrtSession, bitmap: Bitmap): List<Rect> {
        // Resize to detection input size (max side DET_MAX_SIDE, multiples of 32)
        val scale: Float
        val resizedW: Int
        val resizedH: Int
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide > DET_MAX_SIDE) {
            scale = DET_MAX_SIDE.toFloat() / maxSide
            resizedW = roundTo32((bitmap.width * scale).toInt())
            resizedH = roundTo32((bitmap.height * scale).toInt())
        } else {
            scale = 1.0f
            resizedW = roundTo32(bitmap.width)
            resizedH = roundTo32(bitmap.height)
        }

        val resized = Bitmap.createScaledBitmap(bitmap, resizedW, resizedH, true)

        // Build CHW float tensor with ImageNet normalization
        val floatBuffer = FloatBuffer.allocate(3 * resizedH * resizedW)
        val pixels = IntArray(resizedW * resizedH)
        resized.getPixels(pixels, 0, resizedW, 0, 0, resizedW, resizedH)
        if (resized !== bitmap) resized.recycle()

        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        for (c in 0 until 3) {
            for (y in 0 until resizedH) {
                for (x in 0 until resizedW) {
                    val pixel = pixels[y * resizedW + x]
                    val channelValue = when (c) {
                        0 -> (pixel shr 16) and 0xFF
                        1 -> (pixel shr 8) and 0xFF
                        2 -> pixel and 0xFF
                        else -> 0
                    }
                    floatBuffer.put((channelValue / 255.0f - mean[c]) / std[c])
                }
            }
        }
        floatBuffer.rewind()

        val inputShape = longArrayOf(1L, 3L, resizedH.toLong(), resizedW.toLong())
        val inputTensor = OnnxTensor.createTensor(env, floatBuffer, inputShape)
        val inputName = session.inputNames.firstOrNull() ?: "x"

        val results = session.run(mapOf(inputName to inputTensor))
        inputTensor.close()

        // Output: [1, 1, H, W] probability map
        @Suppress("UNCHECKED_CAST")
        val probMap = (results[0].value as Array<Array<Array<FloatArray>>>)[0][0]
        results.close()

        // Threshold and find connected component bounding boxes
        val binaryMask = Array(resizedH) { y ->
            BooleanArray(resizedW) { x -> probMap[y][x] > DET_THRESHOLD }
        }

        val boxes = findConnectedComponentBoxes(binaryMask, resizedW, resizedH)

        // Scale boxes back to original bitmap coordinates
        val scaleBackX = bitmap.width.toFloat() / resizedW
        val scaleBackY = bitmap.height.toFloat() / resizedH

        return boxes.mapNotNull { box ->
            val scaledBox = Rect(
                (box.left * scaleBackX).toInt(),
                (box.top * scaleBackY).toInt(),
                (box.right * scaleBackX).toInt(),
                (box.bottom * scaleBackY).toInt(),
            )
            // Filter out tiny boxes
            if (scaledBox.width() < DET_MIN_BOX_SIZE || scaledBox.height() < DET_MIN_BOX_SIZE) null
            else scaledBox
        }
    }

    /**
     * Two-pass connected component labeling on a binary mask.
     * Returns axis-aligned bounding boxes for each component.
     */
    private fun findConnectedComponentBoxes(mask: Array<BooleanArray>, w: Int, h: Int): List<Rect> {
        val labels = Array(h) { IntArray(w) }
        var nextLabel = 1
        // Map: label -> (minX, minY, maxX, maxY)
        val boxMap = mutableMapOf<Int, IntArray>()

        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y][x] || labels[y][x] != 0) continue

                // BFS flood fill
                val label = nextLabel++
                val queue = LinkedList<Int>()
                queue.add(y * w + x)
                labels[y][x] = label
                var minX = x; var maxX = x; var minY = y; var maxY = y

                while (queue.isNotEmpty()) {
                    val pos = queue.poll()!!
                    val py = pos / w
                    val px = pos % w

                    if (px < minX) minX = px
                    if (px > maxX) maxX = px
                    if (py < minY) minY = py
                    if (py > maxY) maxY = py

                    // 4-connectivity neighbors
                    for (d in DIRS_4) {
                        val nx = px + d[0]
                        val ny = py + d[1]
                        if (nx in 0 until w && ny in 0 until h && mask[ny][nx] && labels[ny][nx] == 0) {
                            labels[ny][nx] = label
                            queue.add(ny * w + nx)
                        }
                    }
                }

                boxMap[label] = intArrayOf(minX, minY, maxX + 1, maxY + 1)
            }
        }

        return boxMap.values.map { Rect(it[0], it[1], it[2], it[3]) }
    }

    // ---- Classification (text direction) ----

    /**
     * Run the text direction classifier. If the text is detected as rotated 180°,
     * flip the bitmap. Otherwise return the original.
     */
    private fun classifyAndRotate(env: OrtEnvironment, crop: Bitmap): Bitmap {
        val cls = clsSession ?: return crop

        val targetW = CLS_IMAGE_WIDTH
        val targetH = CLS_IMAGE_HEIGHT
        val resized = Bitmap.createScaledBitmap(crop, targetW, targetH, true)

        val floatBuffer = FloatBuffer.allocate(3 * targetH * targetW)
        val pixels = IntArray(targetW * targetH)
        resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        if (resized !== crop) resized.recycle()

        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        for (c in 0 until 3) {
            for (y in 0 until targetH) {
                for (x in 0 until targetW) {
                    val pixel = pixels[y * targetW + x]
                    val channelValue = when (c) {
                        0 -> (pixel shr 16) and 0xFF
                        1 -> (pixel shr 8) and 0xFF
                        2 -> pixel and 0xFF
                        else -> 0
                    }
                    floatBuffer.put((channelValue / 255.0f - mean[c]) / std[c])
                }
            }
        }
        floatBuffer.rewind()

        val inputShape = longArrayOf(1L, 3L, targetH.toLong(), targetW.toLong())
        val inputTensor = OnnxTensor.createTensor(env, floatBuffer, inputShape)
        val inputName = cls.inputNames.firstOrNull() ?: "x"

        val result = cls.run(mapOf(inputName to inputTensor))
        inputTensor.close()

        @Suppress("UNCHECKED_CAST")
        val scores = (result[0].value as Array<FloatArray>)[0]
        result.close()

        // scores[0] = normal, scores[1] = rotated 180°
        if (scores.size >= 2 && scores[1] > CLS_THRESHOLD) {
            // Rotate 180°
            val matrix = android.graphics.Matrix()
            matrix.postRotate(180f, crop.width / 2f, crop.height / 2f)
            return Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, matrix, true)
        }

        return crop
    }

    // ---- Recognition pipeline (existing, with dict fix) ----

    private fun recognizeText(
        env: OrtEnvironment,
        session: OrtSession,
        bitmap: Bitmap,
        dict: List<String>,
    ): String {
        val targetH = REC_IMAGE_HEIGHT
        val scale = targetH.toFloat() / bitmap.height.coerceAtLeast(1)
        val targetW = ((bitmap.width * scale).toInt()).coerceAtLeast(1).coerceAtMost(REC_MAX_WIDTH)

        val resized = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)

        val floatBuffer = FloatBuffer.allocate(3 * targetH * targetW)
        val pixels = IntArray(targetW * targetH)
        resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        if (resized !== bitmap) resized.recycle()

        // PaddleOCR rec normalization: (pixel/255 - 0.5) / 0.5 = pixel/127.5 - 1.0
        for (c in 0 until 3) {
            for (y in 0 until targetH) {
                for (x in 0 until targetW) {
                    val pixel = pixels[y * targetW + x]
                    val channelValue = when (c) {
                        0 -> (pixel shr 16) and 0xFF
                        1 -> (pixel shr 8) and 0xFF
                        2 -> pixel and 0xFF
                        else -> 0
                    }
                    floatBuffer.put(channelValue / 127.5f - 1.0f)
                }
            }
        }
        floatBuffer.rewind()

        val inputShape = longArrayOf(1, 3, targetH.toLong(), targetW.toLong())
        val inputTensor = OnnxTensor.createTensor(env, floatBuffer, inputShape)

        val inputName = session.inputNames.firstOrNull() ?: "x"
        val results = session.run(mapOf(inputName to inputTensor))
        inputTensor.close()

        @Suppress("UNCHECKED_CAST")
        val output = (results[0].value as Array<Array<FloatArray>>)[0]
        results.close()

        return ctcGreedyDecode(output, dict)
    }

    /**
     * CTC greedy decoding: argmax at each timestep,
     * collapse repeated indices, skip blank (index 0).
     */
    private fun ctcGreedyDecode(logits: Array<FloatArray>, dict: List<String>): String {
        val sb = StringBuilder()
        var prevIndex = -1

        for (timestep in logits) {
            var maxIdx = 0
            var maxVal = timestep[0]
            for (i in 1 until timestep.size) {
                if (timestep[i] > maxVal) {
                    maxVal = timestep[i]
                    maxIdx = i
                }
            }

            if (maxIdx != 0 && maxIdx != prevIndex) {
                val charIdx = maxIdx - 1
                if (charIdx in dict.indices) {
                    sb.append(dict[charIdx])
                }
            }
            prevIndex = maxIdx
        }

        return sb.toString().trim()
    }

    private fun loadCharDict(context: Context): List<String> {
        val modelDir = OcrModelDownloader.getModelDir(context)
        val dictFile = File(modelDir, DICT_FILE)
        val lines = if (dictFile.exists()) {
            dictFile.readLines().filter { it.isNotEmpty() }
        } else {
            try {
                context.assets.open(DICT_FILE).bufferedReader().readLines().filter { it.isNotEmpty() }
            } catch (_: Exception) {
                Log.w(TAG, "Character dictionary not found")
                emptyList()
            }
        }
        // PaddleOCR convention: output_dim = len(dict) + 2 (blank@0 + space@end).
        // Append space so that the last model output index maps to " " instead of OOB.
        return lines + " "
    }

    override fun release() {
        detSession?.close(); detSession = null
        clsSession?.close(); clsSession = null
        recSession?.close(); recSession = null
        ortEnv?.close(); ortEnv = null
        charDict = null
        initialized = false
    }

    companion object {
        const val ID = "paddleocr"
        private const val TAG = "PaddleOcr"

        // Model files
        private const val DET_MODEL_FILE = "ch_PP-OCRv4_det_infer.onnx"
        private const val CLS_MODEL_FILE = "ch_ppocr_mobile_v2.0_cls_infer.onnx"
        private const val REC_MODEL_FILE = "ch_PP-OCRv4_rec_infer.onnx"
        private const val DICT_FILE = "ppocr_keys_v1.txt"

        // Detection parameters
        private const val DET_MAX_SIDE = 960
        private const val DET_THRESHOLD = 0.3f
        private const val DET_MIN_BOX_SIZE = 6

        // Classification parameters
        private const val CLS_IMAGE_WIDTH = 192
        private const val CLS_IMAGE_HEIGHT = 48
        private const val CLS_THRESHOLD = 0.9f

        // Recognition parameters
        private const val REC_IMAGE_HEIGHT = 48
        private const val REC_MAX_WIDTH = 1280

        // 4-connectivity directions for flood fill
        private val DIRS_4 = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))

        private fun roundTo32(v: Int): Int {
            val r = ((v + 31) / 32) * 32
            return r.coerceAtLeast(32)
        }
    }
}

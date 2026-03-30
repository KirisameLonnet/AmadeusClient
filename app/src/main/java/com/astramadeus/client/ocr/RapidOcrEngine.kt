package com.astramadeus.client.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.benjaminwan.ocrlibrary.OcrEngine as RapidOcrLib
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * RapidOCR engine using the official OcrLibrary AAR (bundled in app/libs/).
 *
 * The AAR provides a full det+cls+rec pipeline via native C++ (libRapidOcr.so).
 * Models are bundled inside the AAR's assets/ directory.
 *
 * A bounded pool of [ENGINE_POOL_SIZE] C++ engine instances is maintained.
 * Each `recognizeBitmap` call borrows an engine from the pool, uses it, and
 * returns it. This avoids N simultaneous model loads when N threads start.
 */
class RapidOcrEngine(private val appContext: Context) : OcrEngine {

    override val id: String = ID
    override val displayName: String = "RapidOCR"
    override val isAvailable: Boolean
        get() = initializedGlobal || canInitialize()

    /**
     * Bounded pool of C++ engine instances. Threads borrow/return engines
     * via [borrowEngine] / [returnEngine] to avoid concurrent model loading.
     * Pool grows on demand up to [MAX_ENGINES] via [ensurePoolSize].
     */
    private val enginePool = LinkedBlockingQueue<RapidOcrLib>()
    @Volatile private var currentPoolCapacity = 0
    @Volatile private var initializedGlobal = false

    override fun initialize() {
        if (initializedGlobal) return
        if (!canInitialize()) return
        ensurePoolSize(DEFAULT_POOL_SIZE)
        initializedGlobal = true
        Log.i(TAG, "RapidOCR engine ready (pool=$currentPoolCapacity)")
    }

    /**
     * Grow the pool to [size] instances if currently smaller.
     * Called by VisionPipeline before parallel OCR to match user-configured parallelism.
     */
    @Synchronized
    fun ensurePoolSize(size: Int) {
        val target = size.coerceIn(1, MAX_ENGINES)
        while (currentPoolCapacity < target) {
            val engine = createEngine() ?: break
            enginePool.offer(engine)
            currentPoolCapacity++
        }
        if (currentPoolCapacity > 0 && !initializedGlobal) {
            initializedGlobal = true
        }
    }

    private fun createEngine(): RapidOcrLib? {
        return try {
            val engine = RapidOcrLib(appContext)
            engine.doAngle = true
            engine.mostAngle = true
            engine.padding = 50
            engine.boxScoreThresh = 0.5f
            engine.boxThresh = 0.3f
            engine.unClipRatio = 1.6f
            engine
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create RapidOCR engine: ${e.message}")
            null
        }
    }

    /**
     * Borrow an engine from the pool, blocking up to [timeoutMs] ms.
     * Returns null if the pool is empty after the timeout.
     */
    private fun borrowEngine(timeoutMs: Long = ENGINE_BORROW_TIMEOUT_MS): RapidOcrLib? {
        return enginePool.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun returnEngine(engine: RapidOcrLib) {
        enginePool.offer(engine)
    }

    override fun recognizeBitmap(bitmap: Bitmap): String {
        val engine = borrowEngine() ?: run {
            Log.w(TAG, "No engine available from pool (timeout)")
            return ""
        }
        return try {
            // Native API requires an output bitmap matching input dimensions for debug viz.
            // We create one per call and recycle immediately — the allocation is cheap vs OCR cost.
            val outputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val result = engine.detect(bitmap, outputBitmap, MAX_SIDE_LEN)
            outputBitmap.recycle()
            result.strRes
        } catch (e: Exception) {
            Log.w(TAG, "RapidOCR recognizeBitmap failed: ${e.message}")
            ""
        } finally {
            returnEngine(engine)
        }
    }

    override fun detectFullPage(bitmap: Bitmap): List<DetectedTextBlock> {
        val engine = borrowEngine() ?: run {
            Log.w(TAG, "No engine available from pool (timeout)")
            return emptyList()
        }
        return try {
            val outputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val result = engine.detect(bitmap, outputBitmap, MAX_SIDE_LEN)
            outputBitmap.recycle()
            result.textBlocks.mapNotNull { block ->
                if (block.text.isBlank()) return@mapNotNull null
                val points = block.boxPoint
                if (points.size < 4) return@mapNotNull null

                // Convert 4-point polygon to axis-aligned bounding box
                val left = points.minOf { it.x }
                val top = points.minOf { it.y }
                val right = points.maxOf { it.x }
                val bottom = points.maxOf { it.y }

                if (right <= left || bottom <= top) return@mapNotNull null

                DetectedTextBlock(
                    text = block.text.trim(),
                    bounds = Rect(left, top, right, bottom),
                    score = block.boxScore,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "RapidOCR detectFullPage failed: ${e.message}")
            emptyList()
        } finally {
            returnEngine(engine)
        }
    }

    override val supportsFullPageDetection: Boolean = true

    override fun release() {
        enginePool.clear()
        initializedGlobal = false
    }

    /**
     * Check if the native library can actually be loaded.
     * Caches the result so we only attempt once.
     */
    private fun canInitialize(): Boolean {
        if (nativeLibChecked) return nativeLibAvailable
        nativeLibChecked = true
        nativeLibAvailable = try {
            System.loadLibrary("RapidOcr")
            true
        } catch (_: UnsatisfiedLinkError) {
            Log.w(TAG, "RapidOCR native library incompatible with current ORT version")
            false
        } catch (_: Throwable) {
            false
        }
        return nativeLibAvailable
    }

    @Volatile private var nativeLibChecked = false
    @Volatile private var nativeLibAvailable = false

    companion object {
        const val ID = "rapidocr"
        private const val TAG = "RapidOcr"
        private const val MAX_SIDE_LEN = 960
        /** Default number of engines created at initialize() time. */
        private const val DEFAULT_POOL_SIZE = 2
        /** Max number of native engine instances (each holds ~50MB of ONNX models). */
        const val MAX_ENGINES = 12
        /** Max time to wait for an engine from the pool (ms). */
        private const val ENGINE_BORROW_TIMEOUT_MS = 2_000L
    }
}


package com.astramadeus.client.ocr

import android.content.Context
import android.util.Log

/**
 * Central registry for OCR engines.
 * Manages available engines and persists two separate engine choices:
 * - Default engine: for per-node text recognition (fast, e.g. ML Kit)
 * - Fallback engine: for full-page OCR when accessibility tree is sparse (can be slower, e.g. GLM-OCR)
 */
object OcrEngineRegistry {

    private const val TAG = "OcrEngineRegistry"
    private const val PREFS_NAME = "amadeus_ocr_pipeline"
    private const val KEY_ENGINE_ID = "ocr_engine_id"
    private const val KEY_FALLBACK_ENGINE_ID = "fallback_ocr_engine_id"

    private val lock = Any()
    private var engines: List<OcrEngine>? = null
    private var activeEngine: OcrEngine? = null
    private var fallbackEngine: OcrEngine? = null

    private fun ensureEngines(context: Context): List<OcrEngine> {
        engines?.let { return it }
        val created = listOf(
            MlKitOcrEngine(),
            PaddleOcrEngine(context.applicationContext),
            RapidOcrEngine(context.applicationContext),
            // GLM-OCR disabled: autoregressive model is too slow on mobile (~8 tok/sec).
            // To re-enable, rebuild OcrLibrary AAR with ORT v1.17.0 (see third_party README).
            // GlmOcrEngine(context.applicationContext),
        )
        engines = created
        return created
    }

    /** All registered engines (including unavailable ones). */
    fun allEngines(context: Context): List<OcrEngine> = ensureEngines(context)

    /** Only engines whose native libs / models are present. */
    fun availableEngines(context: Context): List<OcrEngine> =
        ensureEngines(context).filter { it.isAvailable }

    /** Find an engine by its ID. */
    fun getEngine(context: Context, id: String): OcrEngine? =
        ensureEngines(context).firstOrNull { it.id == id }

    /**
     * Get the currently active OCR engine (for per-node recognition).
     * Falls back to ML Kit if the saved engine is unavailable.
     */
    fun getActiveEngine(context: Context): OcrEngine {
        synchronized(lock) {
            activeEngine?.let { return it }

            val savedId = prefs(context).getString(KEY_ENGINE_ID, MlKitOcrEngine.ID)
                ?: MlKitOcrEngine.ID
            val engine = getEngine(context, savedId)
                ?.takeIf { it.isAvailable && !it.isFullPageOnly }
                ?: ensureEngines(context).first { it.isAvailable && !it.isFullPageOnly }

            if (engine.id != savedId) {
                Log.w(TAG, "Saved engine '$savedId' unavailable or full-page-only, falling back to '${engine.id}'")
                prefs(context).edit().putString(KEY_ENGINE_ID, engine.id).apply()
            }

            try {
                engine.initialize()
            } catch (e: Throwable) {
                Log.e(TAG, "Engine '${engine.id}' failed to initialize: ${e.message}", e)
                val fallback = ensureEngines(context)
                    .filter { it.id != engine.id && it.isAvailable && !it.isFullPageOnly }
                    .firstOrNull()
                if (fallback != null) {
                    try {
                        fallback.initialize()
                        activeEngine = fallback
                        prefs(context).edit().putString(KEY_ENGINE_ID, fallback.id).apply()
                        Log.i(TAG, "Fell back to '${fallback.displayName}' after init failure")
                        return fallback
                    } catch (e2: Throwable) {
                        Log.e(TAG, "Fallback engine also failed: ${e2.message}", e2)
                    }
                }
            }
            activeEngine = engine
            Log.i(TAG, "Active per-node OCR engine: ${engine.displayName}")
            return engine
        }
    }

    /**
     * Get the fallback OCR engine (for full-page detection).
     * Defaults to the first available engine that supports full-page detection.
     */
    fun getFallbackEngine(context: Context): OcrEngine? {
        synchronized(lock) {
            fallbackEngine?.let { return it }

            val savedId = prefs(context).getString(KEY_FALLBACK_ENGINE_ID, PaddleOcrEngine.ID)
                ?: PaddleOcrEngine.ID
            if (savedId != null) {
                val engine = getEngine(context, savedId)
                    ?.takeIf { it.isAvailable && it.supportsFullPageDetection }
                if (engine != null) {
                    engine.initialize()
                    fallbackEngine = engine
                    return engine
                }
                Log.w(TAG, "Saved fallback engine '$savedId' unavailable")
            }

            val autoEngine = ensureEngines(context)
                .firstOrNull { it.isAvailable && it.supportsFullPageDetection }
            if (autoEngine != null) {
                autoEngine.initialize()
                fallbackEngine = autoEngine
            }
            return fallbackEngine
        }
    }

    /** Get the saved fallback engine ID (may be null if auto-selected). */
    fun getFallbackEngineId(context: Context): String? {
        return prefs(context).getString(KEY_FALLBACK_ENGINE_ID, null)
    }

    /**
     * Switch the active OCR engine. Releases the previous engine.
     */
    fun setActiveEngineId(context: Context, id: String) {
        synchronized(lock) {
            val newEngine = getEngine(context, id) ?: run {
                Log.w(TAG, "Unknown engine id: $id")
                return
            }
            if (!newEngine.isAvailable) {
                Log.w(TAG, "Engine '$id' is not available (models not downloaded)")
                return
            }

            activeEngine?.release()

            newEngine.initialize()
            activeEngine = newEngine

            prefs(context).edit().putString(KEY_ENGINE_ID, id).apply()
            Log.i(TAG, "Active OCR engine switched to: ${newEngine.displayName}")
        }
    }

    /**
     * Switch the fallback OCR engine. Releases the previous fallback engine.
     */
    fun setFallbackEngineId(context: Context, id: String) {
        synchronized(lock) {
            val newEngine = getEngine(context, id) ?: run {
                Log.w(TAG, "Unknown engine id: $id")
                return
            }
            if (!newEngine.isAvailable || !newEngine.supportsFullPageDetection) {
                Log.w(TAG, "Engine '$id' is not available or doesn't support full-page detection")
                return
            }

            val current = fallbackEngine
            if (current != null && current.id != activeEngine?.id) {
                current.release()
            }

            newEngine.initialize()
            fallbackEngine = newEngine

            prefs(context).edit().putString(KEY_FALLBACK_ENGINE_ID, id).apply()
            Log.i(TAG, "Fallback OCR engine switched to: ${newEngine.displayName}")
        }
    }

    /** Force re-evaluation of engine availability (e.g. after model download). */
    fun refreshAvailability() {
        synchronized(lock) {
            val current = activeEngine
            if (current != null && !current.isAvailable) {
                current.release()
                activeEngine = null
            }
            val currentFallback = fallbackEngine
            if (currentFallback != null && !currentFallback.isAvailable) {
                currentFallback.release()
                fallbackEngine = null
            }
        }
    }

    /**
     * Release and clear all cached engines so they re-initialize
     * with current settings (e.g. GPU on/off) on next use.
     */
    fun reinitializeEngines(context: Context) {
        synchronized(lock) {
            val prevActive = activeEngine
            val prevFallback = fallbackEngine
            activeEngine = null
            fallbackEngine = null
            prevActive?.release()
            if (prevFallback != null && prevFallback.id != prevActive?.id) {
                prevFallback.release()
            }
            Log.i(TAG, "All engines released for re-initialization")
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

package com.astramadeus.client

import android.content.Context

object OcrPipelineConfig {
    private const val PREFS_NAME = "amadeus_ocr_pipeline"
    private const val KEY_MAX_PARALLELISM = "max_parallelism"
    private const val KEY_USE_GPU = "use_gpu"

    const val MIN_PARALLELISM = 1
    const val MAX_PARALLELISM = 12
    val DEFAULT_PARALLELISM: Int
        get() = Runtime.getRuntime().availableProcessors().coerceIn(MIN_PARALLELISM, 8)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun normalizeParallelism(value: Int): Int {
        return value.coerceIn(MIN_PARALLELISM, MAX_PARALLELISM)
    }

    fun getMaxParallelism(context: Context): Int {
        val stored = prefs(context).getInt(KEY_MAX_PARALLELISM, DEFAULT_PARALLELISM)
        return normalizeParallelism(stored)
    }

    fun setMaxParallelism(context: Context, value: Int) {
        prefs(context)
            .edit()
            .putInt(KEY_MAX_PARALLELISM, normalizeParallelism(value))
            .apply()
    }

    fun getUseGpu(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_USE_GPU, false)
    }

    fun setUseGpu(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_GPU, enabled).apply()
    }
}

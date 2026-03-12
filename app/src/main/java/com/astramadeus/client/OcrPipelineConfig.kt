package com.astramadeus.client

import android.content.Context

object OcrPipelineConfig {
    private const val PREFS_NAME = "amadeus_ocr_pipeline"
    private const val KEY_MAX_PARALLELISM = "max_parallelism"

    const val MIN_PARALLELISM = 1
    const val MAX_PARALLELISM = 32
    const val DEFAULT_PARALLELISM = 32

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
}

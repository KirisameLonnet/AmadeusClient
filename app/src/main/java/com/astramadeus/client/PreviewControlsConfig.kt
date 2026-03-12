package com.astramadeus.client

import android.content.Context

object PreviewControlsConfig {
    private const val PREFS_NAME = "preview_controls"
    private const val KEY_SHOW_VISION_MOSAIC = "show_vision_mosaic"
    private const val KEY_SHOW_NODE_OVERLAY = "show_node_overlay"
    private const val KEY_SHOW_OCR_OVERLAY = "show_ocr_overlay"
    private const val KEY_MAX_PULL_RATE_HZ = "max_pull_rate_hz"

    const val MIN_PULL_RATE_HZ = 0.5f
    const val MAX_PULL_RATE_HZ = 10.0f
    const val RATE_STEP_HZ = 0.5f

    fun getShowVisionMosaic(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SHOW_VISION_MOSAIC, true)
    }

    fun setShowVisionMosaic(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_VISION_MOSAIC, enabled).apply()
    }

    fun getShowNodeOverlay(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SHOW_NODE_OVERLAY, true)
    }

    fun setShowNodeOverlay(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_NODE_OVERLAY, enabled).apply()
    }

    fun getShowOcrOverlay(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SHOW_OCR_OVERLAY, true)
    }

    fun setShowOcrOverlay(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_OCR_OVERLAY, enabled).apply()
    }

    fun getMaxPullRateHz(context: Context): Float {
        val stored = prefs(context).getFloat(KEY_MAX_PULL_RATE_HZ, 1.0f)
        return stored.coerceIn(MIN_PULL_RATE_HZ, MAX_PULL_RATE_HZ)
    }

    fun setMaxPullRateHz(context: Context, rateHz: Float) {
        val normalized = normalizeRate(rateHz)
        prefs(context).edit().putFloat(KEY_MAX_PULL_RATE_HZ, normalized).apply()
    }

    fun toIntervalMs(rateHz: Float): Long {
        val safeRate = normalizeRate(rateHz)
        return (1000f / safeRate).toLong().coerceAtLeast((1000f / MAX_PULL_RATE_HZ).toLong())
    }

    fun normalizeRate(rateHz: Float): Float {
        val clamped = rateHz.coerceIn(MIN_PULL_RATE_HZ, MAX_PULL_RATE_HZ)
        return (clamped / RATE_STEP_HZ).toInt() * RATE_STEP_HZ
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

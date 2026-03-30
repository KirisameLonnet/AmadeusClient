package com.astramadeus.client

import android.content.Context

object WebSocketPushConfig {
    private const val PREFS_NAME = "amadeus_ws_push"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_URL = "url"

    const val DEFAULT_WS_URL = "ws://127.0.0.1:6910"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getUrl(context: Context): String {
        return prefs(context).getString(KEY_URL, DEFAULT_WS_URL)
            ?.trim()
            .orEmpty()
            .ifBlank { DEFAULT_WS_URL }
    }

    fun setUrl(context: Context, url: String) {
        val normalized = url.trim().ifBlank { DEFAULT_WS_URL }
        prefs(context).edit().putString(KEY_URL, normalized).apply()
    }
}

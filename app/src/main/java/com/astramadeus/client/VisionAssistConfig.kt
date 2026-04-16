package com.astramadeus.client

import android.content.Context

object VisionAssistConfig {
    private const val PREFS_NAME = "vision_assist_config"
    private const val KEY_ENABLED_PACKAGES = "enabled_packages"
    private const val KEY_SHOW_SYSTEM_APPS = "show_system_apps"
    private const val KEY_SORT_MODE = "sort_mode"
    private const val KEY_VL_MODEL_AVAILABLE = "vl_model_available"

    const val SORT_APP_NAME_ASC = "app_name_asc"
    const val SORT_APP_NAME_DESC = "app_name_desc"
    const val SORT_PACKAGE_ASC = "package_asc"

    fun isVisionAssistEnabled(context: Context, packageName: String): Boolean {
        return getEnabledPackages(context).contains(packageName)
    }

    fun getEnabledPackages(context: Context): Set<String> {
        return prefs(context).getStringSet(KEY_ENABLED_PACKAGES, emptySet()).orEmpty()
    }

    fun setPackageEnabled(context: Context, packageName: String, enabled: Boolean) {
        val updated = getEnabledPackages(context).toMutableSet()
        if (enabled) {
            updated += packageName
        } else {
            updated -= packageName
        }
        prefs(context).edit().putStringSet(KEY_ENABLED_PACKAGES, updated).apply()
    }

    fun getShowSystemApps(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SHOW_SYSTEM_APPS, false)
    }

    fun setShowSystemApps(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_SYSTEM_APPS, show).apply()
    }

    fun getSortMode(context: Context): String {
        return prefs(context).getString(KEY_SORT_MODE, SORT_APP_NAME_ASC).orEmpty().ifBlank {
            SORT_APP_NAME_ASC
        }
    }

    fun setSortMode(context: Context, sortMode: String) {
        prefs(context).edit().putString(KEY_SORT_MODE, sortMode).apply()
    }

    /**
     * When VL model is available AND vision assist is enabled for an app,
     * the expensive OCR vision pipeline is skipped. The raw UI tree is still
     * sent (sanitized) for local coordinate lookup, while the VL model uses
     * screenshots directly for visual understanding.
     */
    fun isVlModelAvailable(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_VL_MODEL_AVAILABLE, true)
    }

    fun setVlModelAvailable(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VL_MODEL_AVAILABLE, enabled).apply()
    }

    /**
     * Check if the given package should use the VL model path:
     * vision assist enabled AND VL model available.
     */
    fun shouldUseVlModelPath(context: Context, packageName: String): Boolean {
        return isVisionAssistEnabled(context, packageName) && isVlModelAvailable(context)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

package com.astramadeus.client

import android.content.Context

object VisionAssistConfig {
    private const val PREFS_NAME = "vision_assist_config"
    private const val KEY_ENABLED_PACKAGES = "enabled_packages"
    private const val KEY_SHOW_SYSTEM_APPS = "show_system_apps"
    private const val KEY_SORT_MODE = "sort_mode"

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

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

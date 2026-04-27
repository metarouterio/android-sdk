package com.metarouter.analytics.storage

import android.content.Context

/**
 * Persistent storage for the last-seen app `version` and `build`.
 *
 * Lives in a dedicated `SharedPreferences` file so [IdentityStorage.clear]
 * (and `MetaRouterAnalyticsClient.reset()`) cannot wipe install/update state.
 */
class LifecycleStorage(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getVersion(): String? = prefs.getString(KEY_VERSION, null)

    fun getBuild(): String? = prefs.getString(KEY_BUILD, null)

    fun setVersionBuild(version: String, build: String) {
        prefs.edit()
            .putString(KEY_VERSION, version)
            .putString(KEY_BUILD, build)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "com.metarouter.analytics.lifecycle"

        const val KEY_VERSION = "metarouter:lifecycle:version"
        const val KEY_BUILD = "metarouter:lifecycle:build"
    }
}

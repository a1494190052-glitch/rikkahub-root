package me.rerere.rikkahub.plugin.data

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Per-plugin isolated KV storage based on SharedPreferences.
 * Each plugin gets its own SharedPreferences file stored under
 * context.filesDir/plugin_data/{pluginId}/
 */
class PluginDataStore(
    private val context: Context,
    private val pluginId: String,
) {
    private val dataDir: File
        get() = File(context.filesDir, "plugin_data/$pluginId").also { it.mkdirs() }

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(
            "plugin_data_$pluginId",
            Context.MODE_PRIVATE
        )

    /**
     * Set a value for the given key.
     */
    fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    /**
     * Get a value for the given key, or null if not found.
     */
    fun get(key: String): String? {
        return prefs.getString(key, null)
    }

    /**
     * Get a value with a default fallback.
     */
    fun get(key: String, default: String): String {
        return prefs.getString(key, default) ?: default
    }

    /**
     * Delete a key from storage.
     */
    fun del(key: String) {
        prefs.edit().remove(key).apply()
    }

    /**
     * List all keys and values in this plugin's storage.
     */
    fun list(): Map<String, String> {
        return prefs.all.entries
            .filter { it.value is String }
            .associate { it.key to (it.value as String) }
    }

    /**
     * Clear all data for this plugin.
     */
    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * Delete the storage directory entirely.
     */
    fun destroy() {
        prefs.edit().clear().commit()
        dataDir.deleteRecursively()
    }
}

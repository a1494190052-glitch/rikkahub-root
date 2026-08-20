package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import android.webkit.WebView
import java.io.File

/**
 * Detects repeated crashes and activates safe mode to break crash loops.
 *
 * Adapted from OpenMinis CrashFrequencyDetector (file-based log scanning)
 * to a simpler SharedPreferences timestamp approach suitable for RikkaHub.
 *
 * Records crash timestamps in SharedPreferences. When 3+ crashes occur
 * within a 5-minute rolling window, [isSafeMode] returns true and the
 * caller should clear WebView cache and disable plugins before proceeding.
 *
 * Self-contained: zero external dependencies, uses android.util.Log directly,
 * every entry point wrapped in try/catch so a partial-init launch can't break it.
 */
object CrashFrequencyDetector {

    private const val TAG = "CrashFreqDetector"
    private const val PREFS_NAME = "crash_frequency_prefs"
    private const val KEY_CRASH_TIMESTAMPS = "crash_timestamps"
    private const val KEY_SAFE_MODE_UNTIL = "safe_mode_until"

    /** Rolling window: 5 minutes. */
    private const val WINDOW_MS: Long = 5L * 60L * 1000L

    /** Number of crashes within the window that triggers safe mode. */
    private const val THRESHOLD = 3

    /** How long safe mode persists once triggered (10 minutes). */
    private const val SAFE_MODE_DURATION_MS: Long = 10L * 60L * 1000L

    /** Maximum timestamps we keep to avoid unbounded growth. */
    private const val MAX_RECORDED = 20

    /**
     * Returns true if the app should start in safe mode (reduced functionality)
     * because of a recent crash burst. In safe mode the caller should:
     *  - Clear WebView cache
     *  - Disable plugins / extensions
     *  - Skip non-essential initialization
     */
    fun isSafeMode(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // Check explicit safe-mode-until timestamp first
            val safeUntil = prefs.getLong(KEY_SAFE_MODE_UNTIL, 0L)
            if (safeUntil > 0L && System.currentTimeMillis() < safeUntil) {
                Log.w(TAG, "Safe mode active until ${safeUntil - System.currentTimeMillis()}ms from now")
                return true
            }

            // Also check rolling window (in case safe-mode-until expired but
            // crashes are still fresh)
            val recentCount = countRecentCrashes(prefs)
            if (recentCount >= THRESHOLD) {
                Log.w(TAG, "Safe mode triggered: $recentCount crashes within ${WINDOW_MS / 1000}s window")
                // Persist safe mode so it survives process restart
                prefs.edit()
                    .putLong(KEY_SAFE_MODE_UNTIL, System.currentTimeMillis() + SAFE_MODE_DURATION_MS)
                    .apply()
                return true
            }

            false
        } catch (t: Throwable) {
            Log.e(TAG, "isSafeMode check failed", t)
            false
        }
    }

    /**
     * Records a crash occurrence. Call this from an UncaughtExceptionHandler
     * or after detecting a crash on cold start (e.g. via a "clean exit" flag).
     */
    fun recordCrash(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()

            val timestamps = loadTimestamps(prefs).toMutableList()
            timestamps.add(now)

            // Prune old entries beyond the window and cap total count
            val cutoff = now - WINDOW_MS
            val pruned = timestamps.filter { it > cutoff }.takeLast(MAX_RECORDED)

            saveTimestamps(prefs.edit(), pruned).apply()

            val recentCount = pruned.size
            Log.i(TAG, "Crash recorded. Recent crashes in window: $recentCount/$THRESHOLD")

            if (recentCount >= THRESHOLD) {
                Log.w(TAG, "Crash threshold reached — safe mode will activate on next launch")
                prefs.edit()
                    .putLong(KEY_SAFE_MODE_UNTIL, now + SAFE_MODE_DURATION_MS)
                    .apply()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "recordCrash failed", t)
        }
    }

    /**
     * Performs safe-mode recovery actions: clears WebView cache and returns
     * a flag indicating plugins should be disabled by the caller.
     *
     * Call this early in Application.onCreate() when [isSafeMode] is true.
     */
    fun performSafeModeRecovery(context: Context) {
        try {
            Log.w(TAG, "Performing safe-mode recovery: clearing WebView cache")

            // Clear WebView cache (both RAM and disk)
            WebView(context).apply {
                clearCache(true)
                destroy()
            }

            // Also nuke the WebView cache directory directly as a belt-and-suspenders
            // measure — some OEM WebView implementations keep stale caches that
            // survive clearCache().
            try {
                val webViewCacheDir = File(context.cacheDir, "WebView")
                if (webViewCacheDir.exists()) {
                    webViewCacheDir.deleteRecursively()
                    Log.i(TAG, "Deleted WebView cache directory")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not delete WebView cache dir: ${t.message}")
            }

            Log.w(TAG, "Safe-mode recovery complete. Plugins should be disabled by caller.")
        } catch (t: Throwable) {
            Log.e(TAG, "performSafeModeRecovery failed", t)
        }
    }

    /**
     * Clears all recorded crash data. Call after a successful stable run
     * (e.g. after the app has been in foreground for 30+ seconds without crash).
     */
    fun reset(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
            Log.i(TAG, "Crash frequency data reset")
        } catch (t: Throwable) {
            Log.e(TAG, "reset failed", t)
        }
    }

    // --- Private helpers ---

    private fun countRecentCrashes(prefs: android.content.SharedPreferences): Int {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        return loadTimestamps(prefs).count { it > cutoff }
    }

    private fun loadTimestamps(prefs: android.content.SharedPreferences): List<Long> {
        val raw = prefs.getString(KEY_CRASH_TIMESTAMPS, null) ?: return emptyList()
        return raw.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
    }

    private fun saveTimestamps(
        editor: android.content.SharedPreferences.Editor,
        timestamps: List<Long>
    ): android.content.SharedPreferences.Editor {
        return editor.putString(KEY_CRASH_TIMESTAMPS, timestamps.joinToString(","))
    }
}

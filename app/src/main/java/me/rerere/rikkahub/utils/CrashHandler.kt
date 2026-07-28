package me.rerere.rikkahub.utils

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import me.rerere.rikkahub.data.ai.CrashFrequencyDetector

private const val TAG = "CrashHandler"
private const val PREFS_NAME = "crash_handler"
private const val KEY_CRASHED = "crashed"
private const val KEY_STACKTRACE = "stacktrace"
private const val KEY_CRASH_VERSION = "crash_version"
private const val KEY_CRASH_TIME = "crash_time"
private const val MAX_STACKTRACE_LENGTH = 8000

/**
 * 崩溃保护机制（v2）
 *
 * 旧版问题：任何一次崩溃都会让下次启动进安全模式，且更新 APK 后旧标志不清除。
 *
 * 新逻辑：
 *  - markCrashed() 记录崩溃时的 versionCode + 时间戳
 *  - shouldEnterSafeMode() 仅在「同版本 + 2 分钟内」才触发安全模式
 *    → 用到一半崩的（启动已超过 2 分钟）不会触发
 *    → 更新了 APK（版本不同）不会触发
 *  - clearCrashed() 在启动成功 5 秒后由 RouteActivity 调用，彻底重置
 */
object CrashHandler {
    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            markCrashed(appContext, thread, throwable)
            // [CrashFrequencyDetector] Record crash for safe-mode detection (H-1b)
            CrashFrequencyDetector.recordCrash(appContext)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 是否应进入安全模式。
     * 仅当：标志存在 && 崩溃发生在同一版本 && 距崩溃不到 2 分钟（说明是启动阶段崩溃）
     */
    fun shouldEnterSafeMode(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_CRASHED, false)) return false

        val crashVersion = prefs.getInt(KEY_CRASH_VERSION, -1)
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        } catch (_: Exception) { -2 }

        // 版本不同 → 旧崩溃与新版无关，自动清除
        if (crashVersion != currentVersion) {
            clearCrashed(context)
            Log.i(TAG, "Cleared stale crash flag (version $crashVersion → $currentVersion)")
            return false
        }

        val crashTime = prefs.getLong(KEY_CRASH_TIME, 0L)
        val elapsed = System.currentTimeMillis() - crashTime

        // 超过 2 分钟 → 不是启动崩溃，是运行时崩溃，不进安全模式
        if (elapsed > 2 * 60 * 1000L) {
            clearCrashed(context)
            Log.i(TAG, "Cleared old crash flag (${elapsed / 1000}s ago, not a startup crash)")
            return false
        }

        return true
    }

    /** 兼容旧调用（SafeModeActivity 等） */
    fun hasCrashed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASHED, false)
    }

    fun getStackTrace(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STACKTRACE, null)
    }

    fun clearCrashed(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                remove(KEY_CRASHED)
                remove(KEY_STACKTRACE)
                remove(KEY_CRASH_VERSION)
                remove(KEY_CRASH_TIME)
            }
    }

    /**
     * 启动成功后调用：清除崩溃标志。
     * 由 RouteActivity 在启动 5 秒后调用，表示本次启动成功，
     * 后续即使运行时崩溃也不会触发安全模式。
     */
    fun markLaunchSuccess(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CRASHED, false)) {
            clearCrashed(context)
            Log.i(TAG, "Launch success — crash flag cleared")
        }
    }

    private fun markCrashed(context: Context, thread: Thread, throwable: Throwable) {
        val stackTrace = buildString {
            appendLine("Thread: ${thread.name}")
            appendLine(throwable.stackTraceToString())
        }.take(MAX_STACKTRACE_LENGTH)

        val versionCode = try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        } catch (_: Exception) { -1 }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putBoolean(KEY_CRASHED, true)
                putString(KEY_STACKTRACE, stackTrace)
                putInt(KEY_CRASH_VERSION, versionCode)
                putLong(KEY_CRASH_TIME, System.currentTimeMillis())
            } // commit() 同步写入，确保崩溃前写完
    }
}

package me.rerere.workspace

import android.util.Log
import java.io.File

private const val TAG = "SuFinder"

/**
 * 查找设备上 su 二进制文件的绝对路径。
 * 支持 Magisk / KernelSU / APatch / SuperSU 等常见 root 方案。
 *
 * 策略：
 * 1. 尝试已知路径（仅 exists() 检查，不查 canExecute — SELinux 可能阻止）
 * 2. 如果都找不到，返回 "su"（让 /system/bin/sh 的 PATH 来解析）
 *
 * 自愈机制：若缓存的绝对路径在二次校验时已失效（如 root 方案切换），
 * 会自动 invalidate 缓存，下次调用重新扫描，而不是永远走兜底。
 */
object SuFinder {

    private val SU_CANDIDATES = listOf(
        "/debug_ramdisk/su",
        "/data/adb/magisk/su",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/su/bin/su",
        "/vendor/bin/su",
        "/odm/bin/su",
        "/system/sbin/su",
    )

    @Volatile
    private var cachedPath: String? = null

    /**
     * 返回 su 的绝对路径。如果所有候选路径都不存在，返回 "su"。
     */
    fun find(): String {
        cachedPath?.let { return it }
        synchronized(this) {
            cachedPath?.let { return it }
            val found = SU_CANDIDATES.firstOrNull { path ->
                try { File(path).exists() } catch (_: Exception) { false }
            } ?: "su"
            cachedPath = found
            Log.i(TAG, "su resolved to: $found")
            return found
        }
    }

    /**
     * 构建启动 root shell 的 ProcessBuilder。
     * 如果找到了 su 绝对路径且仍然有效，直接用 ProcessBuilder(suPath)。
     * 如果没找到或已失效，用 /system/bin/sh -c "exec su" 让 shell PATH 解析，
     * 并清除缓存以便下次重新扫描。
     */
    fun createSuProcessBuilder(): ProcessBuilder {
        val su = find()
        return if (su != "su" && File(su).exists()) {
            ProcessBuilder(su)
        } else {
            if (su != "su") {
                Log.w(TAG, "cached su path '$su' no longer valid, invalidating cache")
                invalidate()
            }
            ProcessBuilder("/system/bin/sh", "-c", "exec su")
        }
    }

    /**
     * 构建执行单条 root 命令的 ProcessBuilder。
     */
    fun createSuCommandBuilder(command: String): ProcessBuilder {
        val su = find()
        return if (su != "su" && File(su).exists()) {
            ProcessBuilder(su, "-c", command)
        } else {
            if (su != "su") {
                Log.w(TAG, "cached su path '$su' no longer valid, invalidating cache")
                invalidate()
            }
            ProcessBuilder("/system/bin/sh", "-c", "su -c ${shellQuote(command)}")
        }
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 强制重新检测（root 方案变更、或缓存路径失效时调用）。
     */
    fun invalidate() {
        synchronized(this) {
            cachedPath = null
        }
    }
}

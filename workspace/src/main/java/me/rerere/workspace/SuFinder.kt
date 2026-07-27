package me.rerere.workspace

import java.io.File

/**
 * 查找设备上 su 二进制文件的绝对路径。
 * 支持 Magisk / KernelSU / APatch / SuperSU 等常见 root 方案。
 *
 * 缓存结果，避免每次调用都遍历文件系统。
 */
object SuFinder {

    private val SU_CANDIDATES = listOf(
        // Magisk (Android 10+)
        "/debug_ramdisk/su",
        // Magisk (legacy)
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        // KernelSU
        "/data/adb/ksu/bin/su",
        // APatch
        "/data/adb/ap/bin/su",
        // SuperSU / other
        "/su/bin/su",
        "/vendor/bin/su",
        "/odm/bin/su",
        // Fallback paths
        "/system/sbin/su",
        "/system/usr/we-need-root/su",
    )

    @Volatile
    private var cachedPath: String? = null

    /**
     * 返回 su 的绝对路径。如果找不到，返回 "su"（依赖 PATH）。
     */
    fun find(): String {
        cachedPath?.let { return it }
        synchronized(this) {
            cachedPath?.let { return it }
            val found = SU_CANDIDATES.firstOrNull { path ->
                val file = File(path)
                file.exists() && file.canExecute()
            } ?: "su"
            cachedPath = found
            return found
        }
    }

    /**
     * 强制重新检测（root 方案变更后调用）
     */
    fun invalidate() {
        cachedPath = null
    }
}

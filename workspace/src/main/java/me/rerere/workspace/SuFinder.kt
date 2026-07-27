package me.rerere.workspace

import java.io.File

/**
 * 查找设备上 su 二进制文件的绝对路径。
 * 支持 Magisk / KernelSU / APatch / SuperSU 等常见 root 方案。
 *
 * 注意：仅检查 exists()，不检查 canExecute()。
 * 原因：Android SELinux 可能对 app 进程拒绝 x 权限检查（返回 false），
 * 但实际通过 ProcessBuilder 执行时 Magisk 的 su daemon 会正确处理。
 */
object SuFinder {

    private val SU_CANDIDATES = listOf(
        // Magisk (Android 10+, 最常见)
        "/debug_ramdisk/su",
        // Magisk (内部路径)
        "/data/adb/magisk/su",
        // Magisk (legacy symlink)
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
        "/system/sbin/su",
    )

    @Volatile
    private var cachedPath: String? = null

    /**
     * 返回 su 的绝对路径。如果所有候选路径都不存在，返回 "su"（依赖 shell PATH）。
     */
    fun find(): String {
        cachedPath?.let { return it }
        synchronized(this) {
            cachedPath?.let { return it }
            val found = SU_CANDIDATES.firstOrNull { path ->
                File(path).exists()
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

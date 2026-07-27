package me.rerere.workspace

import java.io.File

/**
 * 查找设备上 su 二进制文件的绝对路径。
 * 支持 Magisk / KernelSU / APatch / SuperSU 等常见 root 方案。
 *
 * 策略：
 * 1. 尝试已知路径（仅 exists() 检查）
 * 2. 如果都找不到，返回 "su"（让 /system/bin/sh 的 PATH 来解析）
 *
 * 注意：不检查 canExecute()，因为 SELinux 可能阻止。
 * 即使 exists() 全部失败，PersistentShellSession 会通过 sh -c "su" 兜底。
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
            return found
        }
    }

    /**
     * 构建启动 root shell 的 ProcessBuilder。
     * 如果找到了 su 绝对路径，直接用 ProcessBuilder(suPath)。
     * 如果没找到，用 /system/bin/sh -c "exec su" 让 shell PATH 解析。
     */
    fun createSuProcessBuilder(): ProcessBuilder {
        val su = find()
        return if (su != "su" && File(su).exists()) {
            ProcessBuilder(su)
        } else {
            // 兜底：通过 sh 启动 su（sh 的 PATH 包含 Magisk 挂载的目录）
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
            ProcessBuilder("/system/bin/sh", "-c", "su -c ${shellQuote(command)}")
        }
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun invalidate() {
        cachedPath = null
    }
}

package me.rerere.workspace

import android.util.Base64
import java.io.File

/**
 * 通过 chroot 在 rootfs 隔离环境内执行命令（需要 root 权限）。
 *
 * 与 [ProotShellRunner] 相比：chroot 是内核级隔离，性能显著优于 proot（无用户态
 * syscall 翻译）；与 [RootShellRunner]（host 上 su 直跑）相比：chroot 在 rootfs
 * 内部运行，提供隔离环境，workspace 文件区以 bind mount 挂到 /workspace。
 *
 * 使用前提：设备已 root（Magisk / KernelSU / APatch），用户已授予 root 权限，
 * 且 rootfs 已安装（<linuxDir>/bin/sh 存在）。
 *
 * 启动流程：
 *  1. 确保 rootfs 内 bind mount 挂载点已就绪（workspace / skills / tool_outputs /
 *     upload / dev / proc / sys），以 mkdir -p 补齐。
 *  2. 用 su 执行 bind mount（幂等：/proc/mounts 已含目标则跳过）。
 *  3. chroot <linuxDir> /bin/bash -c '...' 在 rootfs 内执行命令。
 *
 * 为避免多层引号转义，整段脚本用 base64 编码后经 `echo <b64> | base64 -d | sh`
 * 交给 host shell 执行；命令本身同样以 base64 编码作为位置参数传入 bash，
 * 在 rootfs 内解码后 eval，从而彻底规避引号/空格/特殊字符问题。
 */
class ChrootShellRunner(
    private val extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {

    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!context.linuxDir.hasUsableRootfs()) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Rootfs is not installed",
            )
        }
        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        return runCommand(context, context.command, context.cwd, context.timeoutMillis, context.stdin)
    }

    /**
     * 构建 chroot 进程(未启动), 供后台任务复用输出重定向.
     * 返回 null 表示 rootfs 不可用.
     */
    fun buildProcessBuilderOrNull(context: WorkspaceShellContext): ProcessBuilder? {
        if (!context.linuxDir.hasUsableRootfs()) return null
        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        val script = buildChrootScript(context, context.command, context.cwd)
        val scriptB64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val wrapper = "echo $scriptB64 | base64 -d | sh"
        return SuFinder.createSuCommandBuilder(wrapper).redirectErrorStream(false)
    }

    private fun runCommand(
        context: WorkspaceShellContext,
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray?,
    ): WorkspaceCommandResult {
        val script = buildChrootScript(context, command, cwd)
        val scriptB64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        // 整个脚本 base64 后交给 host sh 解码执行, 规避引号转义
        val wrapper = "echo $scriptB64 | base64 -d | sh"
        val builder = SuFinder.createSuCommandBuilder(wrapper)
            .redirectErrorStream(false)
        return try {
            builder.start().readResult(timeoutMillis, stdin)
        } catch (e: Exception) {
            WorkspaceCommandResult(-1, "", "chroot execution failed: ${e.message}")
        }
    }

    private fun buildChrootScript(
        context: WorkspaceShellContext,
        command: String,
        cwd: String,
    ): String {
        val linuxDir = context.linuxDir.absolutePath
        val filesDir = context.filesDir.absolutePath
        val sb = StringBuilder()

        // 1. 准备挂载点目录
        sb.append("mkdir -p ")
        sb.append(linuxDir).append("/workspace")
        sb.append(" ").append(linuxDir).append("/dev")
        sb.append(" ").append(linuxDir).append("/proc")
        sb.append(" ").append(linuxDir).append("/sys")
        extraBindMounts.forEach { mount ->
            if (mount.source.exists()) {
                sb.append(" ").append(linuxDir).append("/").append(mount.target.trim('/'))
            }
        }
        sb.append('\n')

        // 2. bind mounts（幂等, 以 /proc/mounts 判断是否已挂载）
        sb.append(bindMountLine("$linuxDir/workspace", filesDir))
        listOf("/dev", "/proc", "/sys").forEach { hostPath ->
            if (File(hostPath).exists()) {
                when (hostPath) {
                    "/dev" -> sb.append(bindMountLine("$linuxDir/dev", "/dev"))
                    "/proc" -> sb.append(bindMountLine("$linuxDir/proc", null, fstype = "proc"))
                    "/sys" -> sb.append(bindMountLine("$linuxDir/sys", null, fstype = "sysfs"))
                }
            }
        }
        extraBindMounts.forEach { mount ->
            if (mount.source.exists()) {
                val target = "$linuxDir/${mount.target.trim('/')}"
                sb.append(bindMountLine(target, mount.source.absolutePath))
            }
        }

        // 3. chroot 进入 rootfs 执行命令
        val normalizedCwd = cwd.trim().trim('/')
        val chrootCwd = if (normalizedCwd.isBlank()) "/workspace" else "/workspace/$normalizedCwd"
        val cmdB64 = Base64.encodeToString(command.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        // bash -c 'cd -- "$1" && eval "$(printf %s "$2" | base64 -d)"' _ <cwd> <cmd_b64>
        sb.append("chroot ")
        sb.append(linuxDir)
        sb.append(" /bin/bash -c 'cd -- \"$1\" && eval \"$(printf %s \"$2\" | base64 -d)\"' _ ")
        sb.append(chrootCwd)
        sb.append(' ')
        sb.append(cmdB64)
        sb.append('\n')
        return sb.toString()
    }

    private fun bindMountLine(target: String, source: String?, fstype: String? = null): String {
        // 已挂载则跳过; 否则 mount
        val isMounted = "grep -q \" $target \" /proc/mounts"
        val mountCmd = if (fstype != null) {
            "mount -t $fstype $fstype $target"
        } else {
            "mount --bind $source $target"
        }
        return "$isMounted || $mountCmd\n"
    }

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile

    companion object {
        private const val WORKSPACE_DIR = "/workspace"
    }
}

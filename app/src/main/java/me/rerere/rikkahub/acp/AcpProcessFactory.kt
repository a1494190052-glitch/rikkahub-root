package me.rerere.rikkahub.acp

import android.content.Context
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceShellContext
import java.io.File

/**
 * 把一条 [AcpAgentProfile] 变成在 proot 工作区里运行的 agent 子进程。
 *
 * 复用 [ProotShellRunner.buildProcessBuilderOrNull] 的 proot 参数拼装
 * （rootfs / bind mount / env），只把命令换成 agent 启动命令，并保留
 * 独立 stderr（ACP 的 stdio 协议要求 stdout 纯净）。
 */
class AcpProcessFactory(
    context: Context,
    private val workspaceRootProvider: suspend () -> String?,
) {
    private val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
    private val workspacesDir = File(context.filesDir, "workspaces")
    private val filesDir = context.filesDir

    private val prootRunner by lazy {
        ProotShellRunner(
            nativeLibraryDir = nativeLibraryDir,
            extraBindMounts = listOf(
                WorkspaceBindMount(
                    source = File(filesDir, FileFolders.SKILLS).apply { mkdirs() },
                    target = "/skills",
                ),
                WorkspaceBindMount(
                    source = File(filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                    target = "/tool_outputs",
                ),
                WorkspaceBindMount(
                    source = File(filesDir, FileFolders.UPLOAD).apply { mkdirs() },
                    target = "/upload",
                ),
            ),
        )
    }

    suspend fun build(profile: AcpAgentProfile): ProcessBuilder {
        val root = workspaceRootProvider()
            ?: throw IllegalStateException("没有可用的工作区（rootfs 未安装）")
        val wsDir = File(workspacesDir, root)
        val wsFiles = File(wsDir, "files")
        val command = buildString {
            profile.environment.forEach { (key, value) ->
                append("export ").append(key).append("='").append(value.replace("'", "'\\''")).append("'; ")
            }
            // 让 npm 全局 / node / bun 安装的 agent 二进制能被 PATH 找到。
            append("export PATH=\"\$PATH:/root/.npm-global/bin:/opt/node/bin:/opt/bun/bin\"; ")
            append(profile.command)
            profile.arguments.forEach { arg ->
                append(" '").append(arg.replace("'", "'\\''")).append("'")
            }
        }
        val context = WorkspaceShellContext(
            root = root,
            command = command,
            cwd = profile.cwd,
            filesDir = wsFiles,
            linuxDir = File(wsDir, "linux"),
            tempDir = File(wsDir, "tmp"),
            workingDir = wsFiles,
            timeoutMillis = 0,
            stdin = null,
        )
        return prootRunner.buildProcessBuilderOrNull(context)
            ?.redirectErrorStream(false)
            ?: throw IllegalStateException("rootfs 未安装或 proot 不可用")
    }
}

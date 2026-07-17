package me.rerere.workspace

import java.io.File

/**
 * Executes shell commands directly on the Android host with root privileges via `su`.
 *
 * Used in two places:
 *  - the optional `root_shell` AI tool (via the simple [execute] overload);
 *  - the workspace "root mode", where it replaces [ProotShellRunner] so both the
 *    AI `workspace_shell` tool and the workspace terminal run on the host as root.
 *
 * Requires a rooted device (Magisk / KernelSU / APatch) and the user must have
 * granted this app root access.
 */
class RootShellRunner : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult =
        execute(
            command = context.command,
            timeoutMillis = context.timeoutMillis,
            stdin = context.stdin,
            cwd = context.workingDir,
        )

    fun execute(
        command: String,
        timeoutMillis: Long,
        stdin: ByteArray? = null,
        cwd: File? = null,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        val builder = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(false)
        if (cwd != null && cwd.isDirectory) {
            builder.directory(cwd)
        }
        return builder.start().readResult(timeoutMillis, stdin)
    }
}

package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * 持久 Shell 会话: 维持一个长生命周期的 shell 进程(非交互模式, stdin 保持打开),
 * 命令逐条写入执行, 因此 cd / export / 后台进程(&)都会跨命令保留.
 *
 * 命令边界协议: 每条命令后追加一行 sentinel 输出:
 *   printf '\n__RIKKA_EOF__%s__%s__\n' "$?" "$PWD"
 * reader 线程持续读 stdout 到共享缓冲, exec 等待 sentinel 出现并解析 exitCode 与 cwd.
 *
 * 注意:
 *  - stderr 合并进 stdout (交互式 shell 无法可靠分流).
 *  - 命令若读取 stdin (cat/read 等) 或引号未闭合, 会吞掉后续输入 — 超时后整个会话销毁重建.
 *  - 超时策略: 销毁会话进程(命令可能仍在跑, 但进程组随 --kill-on-exit / destroyForcibly 回收).
 */
class PersistentShellSession private constructor(
    private val process: Process,
    private val tag: String,
) {
    private val buffer = StringBuilder()
    private val lock = Object()

    @Volatile
    var dead = false
        private set

    @Volatile
    var currentCwd: String = ""
        private set

    private val readerThread = Thread {
        try {
            process.inputStream.bufferedReader().use { reader ->
                val chunk = CharArray(4096)
                while (true) {
                    val read = reader.read(chunk)
                    if (read < 0) break
                    synchronized(lock) {
                        buffer.append(chunk, 0, read)
                        // 防御: 缓冲超过 4MB 时丢弃最旧的一半(忘记收割的长输出)
                        if (buffer.length > MAX_BUFFER_CHARS) {
                            buffer.delete(0, buffer.length / 2)
                        }
                        lock.notifyAll()
                    }
                }
            }
        } catch (_: IOException) {
            // 进程被销毁时流关闭, read 抛异常属正常
        } finally {
            dead = true
            synchronized(lock) { lock.notifyAll() }
        }
    }.apply {
        isDaemon = true
        name = "PersistentShell-reader-$tag"
        start()
    }

    /**
     * 执行命令并等待完成. 阻塞调用 — 调用方应包 runInterruptible / withContext(IO).
     * @param cwd 非空时先 cd 到该目录; null 保持会话当前目录
     */
    fun exec(command: String, cwd: String?, timeoutMillis: Long): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        if (dead) {
            return WorkspaceCommandResult(-1, "", "shell session is dead", timedOut = false)
        }
        val mark = synchronized(lock) { buffer.length }
        try {
            val payload = buildString {
                if (!cwd.isNullOrBlank()) {
                    append("cd -- ").append(shellQuote(cwd)).append('\n')
                }
                append(command).append('\n')
                append(SENTINEL_CMD).append('\n')
            }
            synchronized(lock) {
                process.outputStream.write(payload.toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
            }
        } catch (e: IOException) {
            dead = true
            return WorkspaceCommandResult(-1, "", "failed to write to shell: ${e.message}", timedOut = false)
        }

        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val matcher = synchronized(lock) {
                val found = SENTINEL_PATTERN.matcher(buffer).region(mark, buffer.length)
                val hit = if (found.find()) found else null
                if (hit != null) {
                    val output = buffer.substring(mark, hit.start())
                    buffer.delete(mark, hit.end())
                    lock.notifyAll()
                    Pair(output, hit)
                } else if (dead) {
                    Pair(null, null)
                } else {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining > 0) {
                        try {
                            lock.wait(remaining.coerceAtMost(500L))
                        } catch (e: InterruptedException) {
                            destroy()
                            throw e
                        }
                    }
                    null
                }
            }
            when {
                matcher != null -> {
                    val (output, hit) = matcher
                    val exitCode = hit.group(1)?.toIntOrNull() ?: -1
                    currentCwd = hit.group(2)?.trim().orEmpty()
                    return WorkspaceCommandResult(
                        exitCode = exitCode,
                        stdout = output.trimEnd('\n'),
                        stderr = "",
                        timedOut = false,
                        truncated = false,
                    )
                }
                dead -> {
                    val leftover = synchronized(lock) {
                        buffer.substring(mark.coerceAtMost(buffer.length)).also { buffer.setLength(0) }
                    }
                    return WorkspaceCommandResult(-1, leftover, "shell session terminated", timedOut = false)
                }
                System.currentTimeMillis() >= deadline -> {
                    val partial = synchronized(lock) {
                        buffer.substring(mark.coerceAtMost(buffer.length)).also { buffer.setLength(0) }
                    }
                    destroy()
                    return WorkspaceCommandResult(
                        exitCode = -1,
                        stdout = partial,
                        stderr = "command timed out after ${timeoutMillis}ms; shell session was killed and will be recreated on next call",
                        timedOut = true,
                    )
                }
            }
        }
    }

    fun destroy() {
        dead = true
        try {
            process.outputStream.close()
        } catch (_: IOException) {
        }
        process.destroyForcibly()
        synchronized(lock) { lock.notifyAll() }
    }

    companion object {
        private const val MAX_BUFFER_CHARS = 4 * 1024 * 1024
        private const val SENTINEL_CMD = "printf '\\n__RIKKA_EOF__%s__%s__\\n' \"\$?\" \"\$PWD\""
        private val SENTINEL_PATTERN: Pattern =
            Pattern.compile("__RIKKA_EOF__(\\d+)__([^\\n]*?)__\\n")

        fun start(tag: String, builder: ProcessBuilder): PersistentShellSession {
            val process = builder
                .redirectErrorStream(true)
                .start()
            return PersistentShellSession(process, tag)
        }

        private fun shellQuote(path: String): String =
            "'" + path.replace("'", "'\\''") + "'"
    }
}

/**
 * 持久会话注册表: 按 workspace (或宿主机 root) 复用会话, 空闲超时回收.
 */
class ShellSessionManager(
    private val baseDir: File,
    private val nativeLibraryDir: File,
    private val extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    private val rootModeProvider: () -> Boolean = { false },
    private val patcher: RootfsPatcher = RootfsPatcher(),
) {
    private val sessions = ConcurrentHashMap<String, PersistentShellSession>()
    private val lastUsedAt = ConcurrentHashMap<String, Long>()

    /**
     * 在持久会话中执行命令.
     * @param root workspace root 名; null 表示宿主机 root 会话(与具体 workspace 无关)
     * @param cwd 相对 workspace files 区的目录(proot 模式映射到 /workspace 下, root 模式映射为真实路径);
     *            null 保持会话当前目录
     */
    @Synchronized
    fun exec(
        root: String?,
        command: String,
        cwd: String?,
        timeoutMillis: Long,
    ): WorkspaceCommandResult {
        val key = if (root == null) HOST_KEY else "ws:$root"
        var session = sessions[key]
        if (session == null || session.dead) {
            session?.destroy()
            session = createSession(root)
            sessions[key] = session
        }
        lastUsedAt[key] = System.currentTimeMillis()
        val mappedCwd = mapCwd(root, cwd)
        val result = session.exec(command, mappedCwd, timeoutMillis)
        if (session.dead) {
            sessions.remove(key)
        }
        return result
    }

    /** 宿主机 root 模式持久会话 (root_shell 工具用) */
    fun execHostRoot(command: String, cwd: String?, timeoutMillis: Long): WorkspaceCommandResult =
        exec(null, command, cwd, timeoutMillis)

    /** 该 root 是否已有存活会话(用于决定是否应用默认 cwd) */
    fun hasSession(root: String?): Boolean {
        val key = if (root == null) HOST_KEY else "ws:$root"
        return sessions[key]?.dead == false
    }

    /** 会话当前目录(来自最近一次 sentinel 的 $PWD) */
    fun currentCwd(root: String?): String {
        val key = if (root == null) HOST_KEY else "ws:$root"
        return sessions[key]?.currentCwd.orEmpty()
    }

    private fun mapCwd(root: String?, cwd: String?): String? {
        if (cwd.isNullOrBlank()) return null
        if (root == null) return cwd // 宿主机会话: cwd 原样(绝对路径)
        val trimmed = cwd.trim().trim('/')
        return if (isRootMode()) {
            // root 模式: 真实文件系统路径
            val base = File(File(baseDir, root), "files").absolutePath
            if (trimmed.isEmpty()) base else "$base/$trimmed"
        } else {
            // proot 模式: /workspace 相对路径
            if (trimmed.isEmpty()) "/workspace" else "/workspace/$trimmed"
        }
    }

    private fun createSession(root: String?): PersistentShellSession {
        return if (root == null || isRootMode()) {
            val builder = ProcessBuilder("su")
            val dir = initialHostDir(root)
            if (dir != null && dir.isDirectory) builder.directory(dir)
            PersistentShellSession.start(tag = if (root == null) "host-root" else "root:$root", builder = builder)
        } else {
            createProotSession(root)
        }
    }

    private fun initialHostDir(root: String?): File? {
        if (root == null) return null
        return File(File(baseDir, root), "files")
    }

    private fun createProotSession(root: String): PersistentShellSession {
        val wsDir = File(baseDir, root)
        val filesDir = File(wsDir, "files").apply { mkdirs() }
        val linuxDir = File(wsDir, "linux")
        val tempDir = File(wsDir, "tmp").apply { mkdirs() }
        require(File(linuxDir, "bin/sh").isFile) { "Rootfs is not installed" }
        val proot = File(nativeLibraryDir, "libproot_exec.so")
        val loader = File(nativeLibraryDir, "libproot_loader.so")
        require(proot.isFile) { "proot executable not found" }
        require(loader.isFile) { "proot loader not found" }

        patcher.patch(linuxDir)
        val args = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            linuxDir.absolutePath,
            "-w",
            "/workspace",
            "-b",
            "${filesDir.absolutePath}:/workspace",
        )
        extraBindMounts.forEach { mount ->
            if (mount.source.exists()) {
                args += "-b"
                args += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }
        listOf("/dev", "/proc", "/sys").forEach { path ->
            if (File(path).exists()) {
                args += "-b"
                args += path
            }
        }
        args += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "/bin/bash",
            "--noprofile",
            "--norc",
        )
        val builder = ProcessBuilder(args)
            .directory(filesDir)
        builder.environment()["PROOT_LOADER"] = loader.absolutePath
        builder.environment()["PROOT_TMP_DIR"] = tempDir.absolutePath
        builder.environment()["TMPDIR"] = tempDir.absolutePath
        return PersistentShellSession.start(tag = "proot:$root", builder = builder)
    }

    /** 回收空闲会话, 返回回收数量 */
    fun reapIdleSessions(maxIdleMillis: Long = IDLE_TIMEOUT_MS): Int {
        val now = System.currentTimeMillis()
        var reaped = 0
        for ((key, session) in sessions) {
            val idle = now - (lastUsedAt[key] ?: 0L)
            if (idle > maxIdleMillis || session.dead) {
                session.destroy()
                sessions.remove(key)
                lastUsedAt.remove(key)
                reaped++
            }
        }
        return reaped
    }

    fun close(root: String) {
        sessions.remove("ws:$root")?.destroy()
        lastUsedAt.remove("ws:$root")
    }

    fun closeAll() {
        sessions.values.forEach { it.destroy() }
        sessions.clear()
        lastUsedAt.clear()
    }

    private fun isRootMode(): Boolean = rootModeProvider()

    companion object {
        private const val HOST_KEY = "host_root"
        const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
    }
}

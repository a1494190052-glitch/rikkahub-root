package me.rerere.rikkahub.service.shell

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.ui.pages.extensions.workspace.buildWorkspaceProotLaunch
import me.rerere.rikkahub.ui.pages.extensions.workspace.prepareWorkspaceTerminalSession
import me.rerere.rikkahub.ui.pages.extensions.workspace.workspaceRootfsReady
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 持久 PTY 会话组管理器
 *
 * 管理多个 HeadlessPty 实例, 每个有唯一 id + 可选助记名.
 * 对齐 ShellSessionManager 的空闲回收策略 (IDLE_TIMEOUT_MS = 10 分钟).
 */
class PtySessionManager(
    private val context: Context,
    private val appScope: kotlinx.coroutines.CoroutineScope,
) {
    data class PtySessionEntry(
        val id: String,
        val name: String,
        val target: String,
        val pty: HeadlessPty,
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var lastUsedAt: Long = System.currentTimeMillis(),
    )

    private val sessions = ConcurrentHashMap<String, PtySessionEntry>()

    // 自动空闲回收: 每 2 分钟扫描, 关掉 10 分钟没动过的会话
    init {
        appScope.launch {
            while (isActive) {
                delay(REAP_INTERVAL_MS)
                runCatching { reapIdle() }
            }
        }
    }

    private val HOST_ENV = arrayOf(
        "TERM=xterm-256color",
        "PATH=/sbin:/system/bin:/system/xbin:/vendor/bin",
        "HOME=/sdcard",
        "LANG=C.UTF-8",
    )

    /**
     * 打开一个新的持久 PTY 会话.
     * target: "host" = Android host root shell via su; 其他 = workspace 名.
     * 返回新会话 id.
     * 最多 MAX_SESSIONS 个并发会话; 超限时自动关闭最旧的空闲会话腾位.
     */
    suspend fun open(target: String, name: String = ""): String {
        // 超限时关闭最旧空闲会话
        while (sessions.size >= MAX_SESSIONS) {
            val oldest = sessions.values.minByOrNull { it.lastUsedAt }
            if (oldest != null) {
                Log.w(TAG, "max sessions ($MAX_SESSIONS) reached, evicting ${oldest.id}")
                close(oldest.id)
            } else break
        }
        val id = "pty-" + Random.nextInt(1000, 9999).toString()
        val pty = if (target == "host") {
            HeadlessPty("su", "/", emptyArray(), HOST_ENV, killTreeWithSu = true)
        } else {
            val workspacesDir = File(context.filesDir, "workspaces")
            val wsDir = File(workspacesDir, target)
            if (!wsDir.isDirectory) {
                error("workspace '$target' not found")
            }
            if (!workspaceRootfsReady(context, target)) {
                error("workspace '$target' rootfs is not installed yet")
            }
            withContext(Dispatchers.IO) {
                prepareWorkspaceTerminalSession(context, target)
            }
            val launch = buildWorkspaceProotLaunch(context, target)
            HeadlessPty(launch.shellPath, launch.cwd, launch.args, launch.env, killTreeWithSu = false)
        }
        pty.open()
        sessions[id] = PtySessionEntry(id, name.ifEmpty { target }, target, pty)
        Log.i(TAG, "opened session $id (target=$target, name=$name)")
        return id
    }

    /** 获取会话 (不存在返回 null), 更新 lastUsedAt */
    fun get(id: String): PtySessionEntry? {
        val entry = sessions[id] ?: return null
        entry.lastUsedAt = System.currentTimeMillis()
        return entry
    }

    /** 列出所有活跃会话 */
    fun list(): List<PtySessionEntry> = sessions.values.toList()

    /** 关闭并移除会话 */
    suspend fun close(id: String): PtySessionEntry? {
        val entry = sessions.remove(id) ?: return null
        entry.pty.close()
        Log.i(TAG, "closed session $id")
        return entry
    }

    /** 关闭所有空闲超时的会话, 返回关闭的数量 */
    suspend fun reapIdle(idleTimeoutMs: Long = IDLE_TIMEOUT_MS): Int {
        val now = System.currentTimeMillis()
        val stale = sessions.values.filter { now - it.lastUsedAt > idleTimeoutMs }
        stale.forEach { close(it.id) }
        if (stale.isNotEmpty()) {
            Log.i(TAG, "reaped ${stale.size} idle sessions")
        }
        return stale.size
    }

    /** 关闭所有会话 */
    suspend fun closeAll() {
        sessions.keys.toList().forEach { close(it) }
    }

    companion object {
        private const val TAG = "PtySessionManager"
        private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L // 10 分钟
        private const val REAP_INTERVAL_MS = 2 * 60 * 1000L  // 每 2 分钟扫描
        private const val MAX_SESSIONS = 4                    // 最多并发会话
    }
}

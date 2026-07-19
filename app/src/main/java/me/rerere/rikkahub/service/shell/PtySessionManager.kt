package me.rerere.rikkahub.service.shell

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.ui.pages.extensions.workspace.buildWorkspaceProotLaunch
import me.rerere.rikkahub.ui.pages.extensions.workspace.prepareWorkspaceTerminalSession
import me.rerere.rikkahub.ui.pages.extensions.workspace.workspaceRootfsReady
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 持久 PTY 会话组管理器
 *
 * name 为主键: 同名 open 自动复用已有会话, AI 只需记名字不用记 id.
 * send/read/close 支持 name 或 id 定位.
 * 自动空闲回收 (10 分钟), 最多 4 个并发.
 */
class PtySessionManager(
    private val context: Context,
) {
    data class PtySessionEntry(
        val id: String,
        val name: String,
        val target: String,
        val pty: HeadlessPty,
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var lastUsedAt: Long = System.currentTimeMillis(),
    )

    /** id -> entry */
    private val byId = ConcurrentHashMap<String, PtySessionEntry>()
    /** name -> id */
    private val byName = ConcurrentHashMap<String, String>()

    /**
     * 打开 PTY 会话 (name 为主键).
     * 如果同名会话已存在则返回已有 id (复用, 不新建).
     */
    suspend fun open(target: String, name: String): PtySessionEntry {
        // 同名已存在 -> 直接复用
        val existingId = byName[name]
        if (existingId != null) {
            val entry = byId[existingId]
            if (entry != null) {
                entry.lastUsedAt = System.currentTimeMillis()
                Log.i(TAG, "reusing session $existingId (name=$name)")
                return entry
            } else {
                byName.remove(name)
            }
        }

        // 超限时关闭最旧空闲会话
        while (byId.size >= MAX_SESSIONS) {
            val oldest = byId.values.minByOrNull { it.lastUsedAt }
            if (oldest != null) {
                Log.w(TAG, "max sessions ($MAX_SESSIONS) reached, evicting ${oldest.id}")
                closeById(oldest.id)
            } else break
        }

        val id = "pty-" + name.take(16)
        val pty = if (target == "host") {
            HeadlessPty("su", "/", emptyArray(), HOST_ENV, killTreeWithSu = true)
        } else {
            val workspacesDir = File(context.filesDir, "workspaces")
            val wsDir = File(workspacesDir, target)
            if (!wsDir.isDirectory) error("workspace '$target' not found")
            if (!workspaceRootfsReady(context, target)) error("workspace '$target' rootfs is not installed")
            withContext(Dispatchers.IO) { prepareWorkspaceTerminalSession(context, target) }
            val launch = buildWorkspaceProotLaunch(context, target)
            HeadlessPty(launch.shellPath, launch.cwd, launch.args, launch.env, killTreeWithSu = false)
        }
        pty.open()
        val entry = PtySessionEntry(id, name, target, pty)
        byId[id] = entry
        byName[name] = id
        Log.i(TAG, "opened session $id (name=$name, target=$target)")
        return entry
    }

    /** 按 id 或 name 查找 (不存在返回 null) */
    fun get(idOrName: String): PtySessionEntry? {
        val entry = byId[idOrName] ?: byName[idOrName]?.let { byId[it] }
        entry?.lastUsedAt = System.currentTimeMillis()
        return entry
    }

    fun list(): List<PtySessionEntry> = byId.values.toList()

    suspend fun close(idOrName: String): PtySessionEntry? {
        val entry = get(idOrName) ?: return null
        closeById(entry.id)
        return entry
    }

    private suspend fun closeById(id: String): PtySessionEntry? {
        val entry = byId.remove(id) ?: return null
        byName.remove(entry.name)
        entry.pty.close()
        Log.i(TAG, "closed $id (name=${entry.name})")
        return entry
    }

    suspend fun closeAll() { byId.keys.toList().forEach { closeById(it) } }

    companion object {
        private const val TAG = "PtySessionManager"
        private const val MAX_SESSIONS = 4
        private val HOST_ENV = arrayOf(
            "TERM=xterm-256color", "PATH=/sbin:/system/bin:/system/xbin:/vendor/bin",
            "HOME=/sdcard", "LANG=C.UTF-8",
        )
    }
}

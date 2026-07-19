package me.rerere.workspace

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WorkspaceSnapshot(
    val name: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val fileCount: Int,
)

/**
 * 工作区 files 区的纯文件快照(不依赖 git 二进制, rootfs 未装也能用).
 * 快照存放在 workspace 根目录的 snapshots/ 下, 不在 files 区内, AI 的文件工具看不到.
 */
class SnapshotManager {

    fun create(workspaceDir: File, label: String? = null): WorkspaceSnapshot {
        val filesDir = File(workspaceDir, FILES_DIR)
        require(filesDir.isDirectory) { "workspace files dir missing" }
        val stats = scan(filesDir)
        require(stats.first <= MAX_SNAPSHOT_BYTES) {
            "files area too large to snapshot: ${stats.first / 1024 / 1024}MB (max ${MAX_SNAPSHOT_BYTES / 1024 / 1024}MB)"
        }
        val name = buildName(label)
        val target = File(snapshotsDir(workspaceDir), name)
        target.deleteRecursively()
        target.mkdirs()
        copyTree(filesDir, target)
        return WorkspaceSnapshot(name, System.currentTimeMillis(), stats.first, stats.second)
    }

    fun list(workspaceDir: File): List<WorkspaceSnapshot> {
        val dir = snapshotsDir(workspaceDir)
        val entries = dir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return entries.map { snap ->
            val stats = scan(snap)
            WorkspaceSnapshot(
                name = snap.name,
                createdAt = parseTime(snap.name) ?: snap.lastModified(),
                sizeBytes = stats.first,
                fileCount = stats.second,
            )
        }.sortedByDescending { it.createdAt }
    }

    /**
     * 恢复快照: 恢复前自动对当前 files 区打一个 pre-restore 快照, 防止误操作丢数据.
     * 半原子: 先把当前 files rename 到临时目录, 再复制快照, 失败时可回滚.
     */
    fun restore(workspaceDir: File, name: String): WorkspaceSnapshot {
        requireValidName(name)
        val snap = File(snapshotsDir(workspaceDir), name)
        require(snap.isDirectory) { "snapshot not found: $name" }
        val filesDir = File(workspaceDir, FILES_DIR)
        val safety = create(workspaceDir, "pre-restore")
        val backup = File(workspaceDir, "files.restore-backup-${System.currentTimeMillis()}")
        require(filesDir.renameTo(backup)) { "failed to move current files aside" }
        try {
            filesDir.mkdirs()
            copyTree(snap, filesDir)
            backup.deleteRecursively()
        } catch (e: Throwable) {
            // 复制失败: 回滚原有 files 区
            filesDir.deleteRecursively()
            backup.renameTo(filesDir)
            throw e
        }
        return safety
    }

    fun delete(workspaceDir: File, name: String): Boolean {
        requireValidName(name)
        return File(snapshotsDir(workspaceDir), name).deleteRecursively()
    }

    private fun snapshotsDir(workspaceDir: File) =
        File(workspaceDir, SNAPSHOTS_DIR).apply { mkdirs() }

    private fun buildName(label: String?): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safeLabel = label?.trim()
            ?.replace(Regex("[^A-Za-z0-9._\\-一-鿿]"), "-")
            ?.take(40)
            .orEmpty()
        return if (safeLabel.isEmpty()) ts else "$ts-$safeLabel"
    }

    private fun parseTime(name: String): Long? = runCatching {
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(name.take(15))?.time
    }.getOrNull()

    private fun requireValidName(name: String) {
        require(name.matches(Regex("[A-Za-z0-9._\\-一-鿿]+")) && !name.contains("..")) {
            "invalid snapshot name"
        }
    }

    /** 返回 (总字节数, 文件数); 符号链接跳过(不跟随, 防穿透到 workspace 外) */
    private fun scan(dir: File): Pair<Long, Int> {
        var bytes = 0L
        var count = 0
        dir.walkTopDown().forEach { f ->
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) return@forEach
            if (f.isFile) {
                bytes += f.length()
                count++
            }
        }
        return bytes to count
    }

    private fun copyTree(src: File, dst: File) {
        src.walkTopDown().forEach { f ->
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) return@forEach
            val rel = f.relativeTo(src).path
            val out = File(dst, rel)
            if (f.isDirectory) {
                out.mkdirs()
            } else if (f.isFile) {
                out.parentFile?.mkdirs()
                f.copyTo(out, overwrite = true)
            }
        }
    }

    companion object {
        private const val FILES_DIR = "files"
        private const val SNAPSHOTS_DIR = "snapshots"
        const val MAX_SNAPSHOT_BYTES = 300L * 1024 * 1024
    }
}

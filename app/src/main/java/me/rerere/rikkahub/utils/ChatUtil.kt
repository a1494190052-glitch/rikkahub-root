package me.rerere.rikkahub.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.Navigator
import kotlin.uuid.Uuid

private const val TAG = "ChatUtil"

fun navigateToChatPage(
    navigator: Navigator,
    chatId: Uuid = Uuid.random(),
    initText: String? = null,
    initFiles: List<Uri> = emptyList(),
    nodeId: Uuid? = null,
) {
    Log.i(TAG, "navigateToChatPage: navigate to $chatId")
    navigator.clearAndNavigate(
        Screen.Chat(
            id = chatId.toString(),
            text = initText,
            files = initFiles.map { it.toString() },
            nodeId = nodeId?.toString(),
        )
    )
}

fun Context.copyMessageToClipboard(message: UIMessage) {
    this.writeClipboardText(message.toText())
}

/** 单个附件大小上限: 超过则拒绝(防止几个 GB 的文件塞爆存储/内存) */
const val MAX_ATTACHMENT_BYTES = 100L * 1024 * 1024

/** 文本附件读取上限: 超出部分截断并标注(防止大日志撑爆 LLM 上下文) */
const val MAX_TEXT_READ_CHARS = 256 * 1024

private val TEXT_MIME_TYPES = setOf(
    "text/plain", "text/html", "text/css", "text/javascript", "text/csv", "text/xml",
    "application/json", "application/javascript", "application/xml",
    "application/x-yaml", "application/yaml", "application/toml",
    "application/x-sh", "application/x-shellscript", "application/x-httpd-php",
    "application/sql", "application/graphql", "application/ld+json",
    "application/x-ndjson", "application/jsonl"
)

/** 这些扩展名按文本读取(代码/配置/日志/字幕等) */
private val TEXT_FILE_EXTENSIONS = setOf(
    "txt", "md", "markdown", "mdx", "csv", "tsv", "json", "jsonl", "ndjson",
    "js", "jsx", "mjs", "cjs", "ts", "tsx", "html", "htm", "css", "vue", "svelte", "xml",
    "py", "pyi", "rb", "lua", "sql", "java", "kt", "kts", "dart", "php", "swift", "go",
    "bat", "cmd", "ps1", "psm1", "sh", "bash", "zsh", "fish",
    "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "hxx", "rs", "cs",
    "toml", "ini", "env", "gradle", "properties", "proto", "graphql", "gql", "yml", "yaml",
    "ipynb", "r", "jl", "scala", "groovy", "pl", "pm", "hs", "ex", "exs", "erl",
    "clj", "cljs", "nim", "zig", "v", "asm", "s", "f90", "pas", "d", "vb",
    "coffee", "less", "sass", "scss", "styl", "tf", "hcl", "mk", "cmake",
    "conf", "cfg", "log", "diff", "patch", "service", "desktop", "rc",
    "srt", "ass", "vtt", "sub", "lrc",
    "gitignore", "gitattributes", "dockerignore", "editorconfig", "npmignore",
    "dockerfile", "makefile", "nginx", "htaccess", "pem", "pub", "asc", "sig"
)

/** 这些 MIME/扩展名是二进制: 不把内容塞进 prompt, 仅提供元信息+路径 */
private val BINARY_MIME_PREFIXES = listOf(
    "image/", "video/", "audio/", "font/",
    "application/zip", "application/x-tar", "application/gzip",
    "application/x-7z-compressed", "application/x-rar-compressed",
    "application/vnd.android.package-archive", "application/octet-stream",
    "application/x-executable", "application/x-sharedlib"
)

/**
 * 附件类型准入: 现在放行所有文件类型(文本读取/二进制元信息两条路都能走通),
 * 仅保留大小限制. 保留函数名以免扩散修改.
 */
fun isAllowedFileType(fileName: String, mime: String): Boolean = true

/** 是否可按纯文本读取内容 */
fun isTextLikeFile(fileName: String, mime: String): Boolean {
    if (mime in TEXT_MIME_TYPES || mime.startsWith("text/")) return true
    if (BINARY_MIME_PREFIXES.any { mime.startsWith(it) }) return false
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return extension in TEXT_FILE_EXTENSIONS
}

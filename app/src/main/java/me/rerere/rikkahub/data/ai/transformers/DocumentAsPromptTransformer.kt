package me.rerere.rikkahub.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.rikkahub.utils.MAX_TEXT_READ_CHARS
import me.rerere.rikkahub.utils.isTextLikeFile
import java.io.File

object DocumentAsPromptTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.toMutableList().apply {
                        val documents = filterIsInstance<UIMessagePart.Document>()
                        if (documents.isNotEmpty()) {
                            documents.forEach { document ->
                                val content = readDocumentContent(document)
                                val path = resolveWorkspacePath(document)
                                val pathAttr = path?.let { " path=\"$it\"" } ?: ""
                                val prompt = """
                                  <UploadFile name="${document.fileName}"$pathAttr>
                                  ```
                                  $content
                                  ```
                                  </UploadFile>
                                  """.trimMargin()
                                add(0, UIMessagePart.Text(prompt))
                            }
                        }
                    }
                )
            }
        }
    }

    private fun parsePdfAsText(file: File): String {
        return PdfParser.parserPdf(file)
    }

    private fun parseDocxAsText(file: File): String {
        return DocxParser.parse(file)
    }

    private fun parsePptxAsText(file: File): String {
        return PptxParser.parse(file)
    }

    private fun parseEpubAsText(file: File): String {
        return EpubParser.parse(file)
    }

    // 上传文件保存在 filesDir/upload 下, 该目录通过 proot 挂载到 workspace 的 /upload
    // 返回文件在 workspace 内的绝对路径, 便于 AI 用 workspace 工具直接读取原始文件
    private fun resolveWorkspacePath(document: UIMessagePart.Document): String? {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull() ?: return null
        if (file.parentFile?.name != "upload") return null
        return "/upload/${file.name}"
    }

    private fun readDocumentContent(document: UIMessagePart.Document): String {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
            ?: return "[ERROR, invalid file uri: ${document.fileName}]"
        if (!file.exists() || !file.isFile) {
            return "[ERROR, file not found: ${document.fileName}]"
        }
        return runCatching {
            when {
                document.mime == "application/pdf" -> parsePdfAsText(file)
                document.mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> parseDocxAsText(file)
                document.mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> parsePptxAsText(file)
                document.mime == "application/epub+zip" -> parseEpubAsText(file)
                isTextLikeFile(document.fileName, document.mime) -> readTextCapped(file)
                else -> binaryPlaceholder(document, file)
            }
        }.getOrElse {
            "[ERROR, failed to read file: ${document.fileName}]"
        }
    }

    /** 文本文件上限截断读取, 防止大文件撑爆 LLM 上下文 */
    private fun readTextCapped(file: File): String {
        val reader = file.bufferedReader()
        val buffer = CharArray(MAX_TEXT_READ_CHARS)
        var total = 0
        val sb = StringBuilder()
        while (total < MAX_TEXT_READ_CHARS) {
            val read = reader.read(buffer, 0, MAX_TEXT_READ_CHARS - total)
            if (read < 0) break
            sb.append(buffer, 0, read)
            total += read
        }
        reader.close()
        // 读满上限即视为需要截断标注
        return if (total >= MAX_TEXT_READ_CHARS) {
            sb.toString() + "\n\n... [内容过长已截断: 仅显示前 ${MAX_TEXT_READ_CHARS / 1024}K 字符, 完整文件可用 workspace 工具按路径读取] ..."
        } else {
            sb.toString()
        }
    }

    /** 二进制文件: 不塞乱码进 prompt, 给元信息并指引 AI 用 workspace 工具分析原始文件 */
    private fun binaryPlaceholder(document: UIMessagePart.Document, file: File): String {
        val sizeKb = file.length() / 1024
        val sizeText = if (sizeKb >= 1024) "${sizeKb / 1024}MB" else "${sizeKb}KB"
        return buildString {
            append("[Binary file: content not inlined]")
            append("\nfileName: ").append(document.fileName)
            append("\nmime: ").append(document.mime)
            append("\nsize: ").append(sizeText)
            resolveWorkspacePath(document)?.let { path ->
                append("\nThe raw file is available at \"")
                append(path)
                append("\" inside the workspace. Use workspace_shell / workspace_read_file tools to inspect it ")
                append("(e.g. unzip -l, aapt dump badging, strings, tar -tf, xxd).")
            }
        }
    }
}

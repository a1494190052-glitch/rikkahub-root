package me.rerere.rikkahub.data.ai.tools.local

/**
 * Shell 命令三级风险分级:
 *  - READ_ONLY: 白名单只读命令, 免审批直接执行
 *  - WRITE: 可能修改系统/文件, 默认需要用户审批(可在工作区设置里关闭)
 *  - BLOCKED: 命中高危模式( rm -rf /, dd 写块设备, fork bomb 等), 直接拒绝执行
 *
 * 分类是保守的: 识别不了的一律按 WRITE 处理.
 *
 * v2: 动态多层检测 — 递归解析复合命令 + 命令展开模拟 + 编码绕过检测
 */
enum class ShellRisk { READ_ONLY, WRITE, BLOCKED }

/**
 * 深度分类结果: 包含每个子命令的独立分类及整体判定
 */
data class DeepClassifyResult(
    /** 整体风险等级(取所有子命令中最高风险) */
    val overall: ShellRisk,
    /** 每个子命令的分类详情 */
    val segments: List<SegmentClassification>,
    /** 若被 BLOCKED, 给出原因 */
    val blockReason: String? = null,
    /** 检测到的绕过尝试标记 */
    val bypassIndicators: List<String> = emptyList(),
)

data class SegmentClassification(
    val command: String,
    val risk: ShellRisk,
    val reason: String? = null,
)

// ─────────────────────────────────────────────────────────────────────────────
// ShellCommandParser: 递归解析复合命令
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 递归解析 shell 复合命令:
 *  - 管道 |、分号 ;、&&、||、换行 分隔的子命令
 *  - $() 和反引号内的命令替换
 *  - eval / exec / source / . 后的参数
 *  - 变量赋值后执行模式 (cmd="rm -rf"; $cmd)
 *  - base64/hex/xxd 解码后跟 sh/bash/eval 的模式
 */
object ShellCommandParser {

    /** 解析结果: 所有提取出的原子命令片段 */
    data class ParseResult(
        val segments: List<String>,
        val indicators: List<String> = emptyList(),
    )

    /**
     * 递归解析命令, 返回所有需要独立分类的子命令片段.
     * [depth] 防止无限递归(嵌套命令替换).
     */
    fun parse(command: String, depth: Int = 0): ParseResult {
        if (depth > MAX_RECURSION_DEPTH) return ParseResult(listOf(command), listOf("max recursion depth reached"))

        val indicators = mutableListOf<String>()
        val allSegments = mutableListOf<String>()

        // 第一级: 按 | ; && || 换行 拆分
        val topSegments = splitTopLevel(command)

        for (segment in topSegments) {
            val trimmed = segment.trim()
            if (trimmed.isEmpty()) continue

            // 检测编码绕过: base64 -d | sh, xxd -r | bash, hex 解码后执行
            if (detectEncodedExecution(trimmed)) {
                indicators.add("encoded execution pattern: ${trimmed.take(80)}")
                allSegments.add(trimmed)
                // 尝试提取解码后的内容并递归
                extractDecodedContent(trimmed)?.let { decoded ->
                    val sub = parse(decoded, depth + 1)
                    allSegments.addAll(sub.segments)
                    indicators.addAll(sub.indicators)
                }
                continue
            }

            // 检测 eval / exec / source / . 命令
            val evalContent = extractEvalContent(trimmed)
            if (evalContent != null) {
                indicators.add("eval/exec/source detected")
                allSegments.add(trimmed)
                val sub = parse(evalContent, depth + 1)
                allSegments.addAll(sub.segments)
                indicators.addAll(sub.indicators)
                continue
            }

            // 检测 $() 和反引号内的命令替换, 递归分类
            val substitutions = extractCommandSubstitutions(trimmed)
            if (substitutions.isNotEmpty()) {
                indicators.add("command substitution detected")
                allSegments.add(trimmed)
                for (subCmd in substitutions) {
                    val sub = parse(subCmd, depth + 1)
                    allSegments.addAll(sub.segments)
                    indicators.addAll(sub.indicators)
                }
                continue
            }

            // 检测变量赋值后执行: cmd="rm -rf /"; $cmd
            val varExecContent = extractVariableExecution(trimmed)
            if (varExecContent != null) {
                indicators.add("variable-based execution detected")
                allSegments.add(trimmed)
                val sub = parse(varExecContent, depth + 1)
                allSegments.addAll(sub.segments)
                indicators.addAll(sub.indicators)
                continue
            }

            allSegments.add(trimmed)
        }

        return ParseResult(allSegments.ifEmpty { listOf(command) }, indicators)
    }

    /** 按 | ; && || 换行拆分(引号内不拆) */
    private fun splitTopLevel(command: String): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < command.length) {
            val c = command[i]
            when {
                quote != null -> {
                    if (c == quote) quote = null
                    current.append(c)
                }
                c == '\'' || c == '"' -> {
                    quote = c
                    current.append(c)
                }
                c == '|' -> {
                    if (i + 1 < command.length && command[i + 1] == '|') i++
                    segments += current.toString()
                    current.clear()
                }
                c == ';' -> {
                    segments += current.toString()
                    current.clear()
                }
                c == '\n' || c == '\r' -> {
                    segments += current.toString()
                    current.clear()
                }
                c == '&' -> {
                    if (i + 1 < command.length && command[i + 1] == '&') {
                        i++
                        segments += current.toString()
                        current.clear()
                    } else {
                        current.append(c)
                    }
                }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotBlank()) segments += current.toString()
        return segments.filter { it.isNotBlank() }
    }

    /** 检测 base64/hex/xxd 解码后跟 sh/bash/eval 的模式 */
    private fun detectEncodedExecution(segment: String): Boolean {
        return ENCODED_EXEC_PATTERNS.any { it.containsMatchIn(segment) }
    }

    private val ENCODED_EXEC_PATTERNS = listOf(
        // echo <base64> | base64 -d | sh/bash
        Regex("""base64\s+(-d|--decode|-D)\s*\|\s*(sh|bash|zsh|dash|eval)\b"""),
        // echo <hex> | xxd -r -p | sh
        Regex("""xxd\s+(-r|-p|-rp|-pr)\s*\|\s*(sh|bash|zsh|dash|eval)\b"""),
        // printf '\x..\x..' | sh
        Regex("""printf\s+['"]?\\x[0-9a-fA-F]{2}.*\|\s*(sh|bash|zsh|dash|eval)\b"""),
        // echo ... | openssl enc -d | sh
        Regex("""openssl\s+enc\s+-d.*\|\s*(sh|bash|zsh|dash|eval)\b"""),
        // python -c "import base64;exec(base64.b64decode(...))"
        Regex("""(python[23]?|perl|ruby)\s+-c\s+.*base64.*decode"""),
        // echo ... | tr ... | sh (octal/char translation)
        Regex("""\|\s*tr\s+.*\|\s*(sh|bash|zsh|dash|eval)\b"""),
    )

    /** 尝试从编码执行模式中提取被编码的内容(简单启发式) */
    private fun extractDecodedContent(segment: String): String? {
        // 尝试提取 base64 字符串
        val base64Match = Regex("""echo\s+['"]?([A-Za-z0-9+/=]{8,})['"]?\s*\|""").find(segment)
        if (base64Match != null) {
            return runCatching {
                val decoded = String(android.util.Base64.decode(base64Match.groupValues[1], android.util.Base64.DEFAULT))
                if (decoded.any { it.isLetterOrDigit() }) decoded else null
            }.getOrNull()
        }
        // 尝试提取 hex 字符串 (printf '\x72\x6d...')
        val hexMatch = Regex("""printf\s+['"]((\\x[0-9a-fA-F]{2})+)['"]""").find(segment)
        if (hexMatch != null) {
            return runCatching {
                hexMatch.groupValues[1]
                    .split("\\x").filter { it.isNotBlank() }
                    .map { it.toInt(16).toChar() }
                    .joinToString("")
            }.getOrNull()
        }
        return null
    }

    /** 提取 eval/exec/source/. 后面的实际命令内容 */
    private fun extractEvalContent(segment: String): String? {
        val trimmed = segment.trim()
        // eval "command" 或 eval command
        val evalMatch = Regex("""^\s*(eval|exec)\s+(.+)$""").find(trimmed)
        if (evalMatch != null) {
            return evalMatch.groupValues[2].removeSurrounding("\"").removeSurrounding("'")
        }
        // source file 或 . file — 文件本身可能被分类, 但保守标记
        val sourceMatch = Regex("""^\s*(source|\.)\s+(.+)$""").find(trimmed)
        if (sourceMatch != null) {
            return sourceMatch.groupValues[2].trim()
        }
        return null
    }

    /** 提取 $() 和反引号内的命令(引号感知) */
    private fun extractCommandSubstitutions(command: String): List<String> {
        val results = mutableListOf<String>()
        var i = 0
        var singleQuote = false
        while (i < command.length) {
            val c = command[i]
            when {
                singleQuote -> {
                    if (c == '\'') singleQuote = false
                }
                c == '\'' -> singleQuote = true
                c == '$' && i + 1 < command.length && command[i + 1] == '(' -> {
                    // 找到 $( 的匹配 )
                    val end = findMatchingParen(command, i + 1)
                    if (end > i + 2) {
                        results.add(command.substring(i + 2, end))
                    }
                    i = end
                }
                c == '`' -> {
                    val end = command.indexOf('`', i + 1)
                    if (end > i + 1) {
                        results.add(command.substring(i + 1, end))
                    }
                    i = if (end > 0) end else i
                }
            }
            i++
        }
        return results
    }

    private fun findMatchingParen(s: String, openIdx: Int): Int {
        var depth = 0
        var i = openIdx
        while (i < s.length) {
            when (s[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return s.length - 1
    }

    /** 检测变量赋值后执行模式: cmd="rm -rf /"; $cmd 或 ${cmd} */
    private fun extractVariableExecution(segment: String): String? {
        // 匹配 $VAR 或 ${VAR} 作为命令执行(行首或分号后)
        val varExecMatch = Regex("""^\s*\$\{?([A-Za-z_][A-Za-z0-9_]*)\}?(\s+.*)?$""").find(segment.trim())
        if (varExecMatch != null) {
            // 这是一个变量作为命令执行 — 保守返回变量名作为标记
            return "UNKNOWN_VARIABLE_EXECUTION:${varExecMatch.groupValues[1]}"
        }
        return null
    }

    private const val MAX_RECURSION_DEPTH = 5
}

// ─────────────────────────────────────────────────────────────────────────────
// ShellExpander: 命令展开模拟 (第二层)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 简单 shell 展开模拟:
 *  - 展开 ~ 为 /root 或 /data
 *  - 展开已知危险变量模式
 *  - 检测通配符 rm (rm -rf /*, rm -rf /data/*)
 *  - 展开后重新走分类
 */
object ShellExpander {

    /** 已知危险变量赋值: 变量名 -> 可能的危险值模式 */
    private val DANGEROUS_VAR_PATTERNS = listOf(
        Regex("""\brm\s+[^;&|]*-[a-zA-Z]*[rf][a-zA-Z]*\s+\$"""),  // rm -rf $VAR
        Regex("""\brm\s+[^;&|]*-[a-zA-Z]*[rf][a-zA-Z]*\s+\$\{"""),  // rm -rf ${VAR}
    )

    /** 通配符危险模式 */
    private val WILDCARD_DANGEROUS = listOf(
        Regex("""\brm\s+[^;&|]*-[a-zA-Z]*[rf][a-zA-Z]*\s+/\*""") to "rm with root wildcard /*",
        Regex("""\brm\s+[^;&|]*-[a-zA-Z]*[rf][a-zA-Z]*\s+/data/\*""") to "rm with /data/* wildcard",
        Regex("""\brm\s+[^;&|]*-[a-zA-Z]*[rf][a-zA-Z]*\s+/sdcard/\*""") to "rm with /sdcard/* wildcard",
        Regex("""\brm\s+[^;&|]*-[a-zA-Z]*[rf][a-zA-Z]*\s+/storage/\*""") to "rm with /storage/* wildcard",
        Regex("""\brm\s+[^;&|]*-[a-zA-Z]*[rf][a-zA-Z]*\s+~/\*""") to "rm with ~/* wildcard",
    )

    /**
     * 对命令进行展开模拟, 返回展开后的命令列表(可能有多个变体).
     * 如果展开后检测到新的危险模式, 返回对应的 BLOCKED 原因.
     */
    fun expandAndCheck(command: String): ExpansionResult {
        val indicators = mutableListOf<String>()
        var blockReason: String? = null

        // 1. 展开 ~ 为 /root 和 /data
        val tildeExpanded = command
            .replace("~/", "/root/")
            .replace("~ ", "/root ")

        // 2. 检测通配符 rm
        for ((pattern, reason) in WILDCARD_DANGEROUS) {
            if (pattern.containsMatchIn(command) || pattern.containsMatchIn(tildeExpanded)) {
                blockReason = reason
                indicators.add("wildcard dangerous: $reason")
                break
            }
        }

        // 3. 检测 rm -rf $VAR 模式(变量可能展开为危险路径)
        for (pattern in DANGEROUS_VAR_PATTERNS) {
            if (pattern.containsMatchIn(command)) {
                indicators.add("rm with variable argument — potential path expansion risk")
                // 保守: 升级为 BLOCKED(因为变量可能展开为 / 或 /data)
                if (blockReason == null) blockReason = "rm -rf with variable path (potential system path expansion)"
                break
            }
        }

        // 4. 检测变量拼接绕过: A="rm"; B="-rf"; $A $B /
        if (VARIABLE_CONCAT_EXEC.containsMatchIn(command)) {
            indicators.add("variable concatenation execution pattern")
            if (blockReason == null) blockReason = "variable concatenation may hide destructive command"
        }

        return ExpansionResult(
            expandedCommands = listOf(tildeExpanded),
            blockReason = blockReason,
            indicators = indicators,
        )
    }

    private val VARIABLE_CONCAT_EXEC = Regex("""[A-Za-z_][A-Za-z0-9_]*=[^;\n]*;\s*.*\$[A-Za-z_{]""")

    data class ExpansionResult(
        val expandedCommands: List<String>,
        val blockReason: String? = null,
        val indicators: List<String> = emptyList(),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// ShellSafety: 主分类器 (保持原有 API 兼容)
// ─────────────────────────────────────────────────────────────────────────────

object ShellSafety {

    /** 命中即拒绝执行的高危模式(对整条命令全文匹配) */
    private val BLOCKED_PATTERNS: List<Pair<Regex, String>> = listOf(
        // rm -rf / 或 rm -rf /* 或递归删除系统目录
        Regex("""\brm\s+[^;&|]*-[a-zA-Z]*[rf][a-zA-Z]*\s+(--\S+\s+)*(/\*?|/(system|vendor|boot|proc|dev|etc|lib|lib64|bin|sbin|product|data|storage/emulated/0)(/|\s|$))""") to "recursive delete of system-critical path",
        // dd 写块设备
        Regex("""\bdd\b[^;&|]*\bof=/dev/(block|sd[a-z]|mmcblk|mapper)""") to "dd writing to block device",
        // 文件系统格式化/分区
        Regex("""\b(mkfs|mke2fs|fdisk|sfdisk|parted|newfs)\b""") to "filesystem format/partition tool",
        // fork bomb
        Regex(""":\s*\(\s*\)\s*\{[^}]*\|[^}]*&[^}]*\}""") to "fork bomb",
        // 重定向写块设备
        Regex(""">\s*/dev/(block|sd[a-z]|mmcblk)""") to "redirect overwrite of block device",
        // 递归放宽根目录权限
        Regex("""\bchmod\s+[^;&|]*-R[^;&|]*\s+(/\*|/|/system|/data|/vendor)(\s|$)""") to "recursive chmod on system path",
        // 刷机/重启类(防 AI 把设备重启掉)
        Regex("""\b(fastboot|adb\s+reboot|reboot|shutdown)\b""") to "reboot/flash command",
        // 移除 Magisk 本体 / 卸载 su
        Regex("""\b(magisk\s+--remove|pm\s+uninstall\s+[^;&|]*(magisk|supersu|kernelsu))""") to "removing root solution",
        // 新增: curl/wget 下载后直接管道执行
        Regex("""(curl|wget)\s+[^;&|]*\|\s*(sh|bash|zsh|dash|eval)\b""") to "download and execute pipe",
        // 新增: python/perl/ruby -c 执行系统命令
        Regex("""(python[23]?|perl|ruby)\s+-c\s+.*\b(os\.system|subprocess|exec|system)\b""") to "scripting language system execution",
    )

    /** 只读命令白名单(按管道/分号/&& 拆分后对每段首词判定) */
    private val READ_ONLY_COMMANDS = setOf(
        "ls", "ll", "cat", "pwd", "echo", "printf", "uname", "id", "whoami", "who", "groups",
        "date", "uptime", "cal", "df", "du", "free", "ps", "top", "htop", "vmstat", "iostat",
        "env", "printenv", "which", "whereis", "type", "file", "stat", "head", "tail",
        "grep", "egrep", "fgrep", "zgrep", "zcat", "wc", "sort", "uniq", "cut", "tr",
        "basename", "dirname", "readlink", "realpath", "test", "true", "false", "seq",
        "ip", "ifconfig", "netstat", "ss", "ping", "getprop", "lsusb", "lscpu", "lsblk",
        "lsmod", "mount", "findmnt", "dumpsys", "logcat", "getenforce", "selinuxenabled",
        "sha1sum", "sha256sum", "md5sum", "cksum", "base64", "xxd", "od", "strings",
        "jq", "yq", "column", "tree", "history", "alias", "compgen",
    )

    /** 这些命令默认只读, 但携带特定参数时升级为 WRITE */
    private val CONDITIONAL_WRITE_FLAGS: List<Pair<String, Regex>> = listOf(
        "find" to Regex("""\s-(delete|exec|execdir|ok|okdir)\b"""),
        "sed" to Regex("""\s-i\b|\s--in-place\b"""),
        "awk" to Regex("""\bsystem\s*\(|\bprint\s+.*>\s*"|>"/"""),
        "gawk" to Regex("""\bsystem\s*\("""),
        "xargs" to Regex("""\s-(i|I)\b.*\b(rm|mv|cp|chmod|chown)\b"""),
        "pm" to Regex("""\b(uninstall|clear|disable|enable|install|hide|suspend|grant|revoke|reset-permissions)\b"""),
        "settings" to Regex("""\b(put|delete|reset)\b"""),
        "logcat" to Regex("""\s-(c|b)\b.*-c|\s-c\b"""),
        "cp" to Regex(".*"),   // cp/mv/ln 一律 WRITE, 走这个分支直接标
        "mv" to Regex(".*"),
        "ln" to Regex(".*"),
        "touch" to Regex(".*"),
        "mkdir" to Regex(".*"),
        "rmdir" to Regex(".*"),
        "rm" to Regex(".*"),   // rm 未被 BLOCKED 命中时按 WRITE(需审批)
        "chmod" to Regex(".*"),
        "chown" to Regex(".*"),
        "tee" to Regex(".*"),
        "patch" to Regex(".*"),
        "git" to Regex("""\b(push|reset|clean|checkout|rebase|merge|commit|add|rm|branch\s+-[dD]|tag)\b"""),
        "apt" to Regex(".*"),
        "apt-get" to Regex(".*"),
        "dpkg" to Regex(".*"),
        "pip" to Regex("""\b(install|uninstall)\b"""),
        "pip3" to Regex("""\b(install|uninstall)\b"""),
        "npm" to Regex("""\b(install|uninstall|i|remove|rm|ci|update|exec)\b"""),
        "curl" to Regex("""\s-(o|O|T|d|F|X\s*(POST|PUT|DELETE|PATCH))|--data|--upload-file|--output|--remote-name"""),
        "wget" to Regex(".*"),
        "mount" to Regex("""\S+\s+\S+"""), // 带参数 = 挂载动作
        "su" to Regex(".*"),
        "sh" to Regex(".*"),
        "bash" to Regex(".*"),
        "kill" to Regex(".*"),
        "pkill" to Regex(".*"),
        "killall" to Regex(".*"),
        "service" to Regex(".*"),
        "systemctl" to Regex(".*"),
        "setprop" to Regex(".*"),
        "input" to Regex(".*"),
        "am" to Regex(".*"),
        "screencap" to Regex(".*"),
        "screenrecord" to Regex(".*"),
        "cmd" to Regex("""\b(package\s+(uninstall|clear|disable|enable)|power|activity)\b"""),
        "device_config" to Regex("""\b(put|delete)\b"""),
        "content" to Regex("""\b(insert|delete|update)\b"""),
        // 新增: eval/exec/source 一律 WRITE(内容会被递归分类)
        "eval" to Regex(".*"),
        "exec" to Regex(".*"),
        "source" to Regex(".*"),
    )

    /**
     * 主分类入口(保持原有 API 兼容).
     * 快速路径: 简单命令直接匹配; 复杂命令走递归解析.
     */
    fun classify(command: String): ShellRisk {
        // 第零层: 全文高危模式快速拒绝
        blockReason(command)?.let { return ShellRisk.BLOCKED }

        // 第一层快速路径: 无特殊字符的简单命令直接分类
        if (isSimpleCommand(command)) {
            return classifySegment(command)
        }

        // 第二层: 命令展开模拟
        val expansion = ShellExpander.expandAndCheck(command)
        if (expansion.blockReason != null) return ShellRisk.BLOCKED

        // 第一层完整: 递归解析复合命令
        val parsed = ShellCommandParser.parse(command)

        // 如果检测到变量执行但无法解析内容, 保守标 WRITE
        var hasWrite = false
        for (segment in parsed.segments) {
            if (segment.startsWith("UNKNOWN_VARIABLE_EXECUTION:")) {
                hasWrite = true
                continue
            }
            val risk = classifySegment(segment)
            if (risk == ShellRisk.BLOCKED) return ShellRisk.BLOCKED
            if (risk == ShellRisk.WRITE) hasWrite = true
        }

        if (hasWrite) return ShellRisk.WRITE

        // 命令替换存在但内部都是只读 → 仍保守标 WRITE(因为替换本身有不确定性)
        if (parsed.indicators.isNotEmpty()) return ShellRisk.WRITE

        // 整串含输出重定向 → WRITE
        if (REDIRECT_WRITE_REGEX.containsMatchIn(command)) return ShellRisk.WRITE

        return ShellRisk.READ_ONLY
    }

    /**
     * 深度分类: 返回每个子命令的独立分类及绕过指标.
     * 用于审计日志和结构化警告.
     */
    fun classifyDeep(command: String): DeepClassifyResult {
        val bypassIndicators = mutableListOf<String>()

        // 第零层: 全文高危模式
        val directBlock = blockReason(command)
        if (directBlock != null) {
            return DeepClassifyResult(
                overall = ShellRisk.BLOCKED,
                segments = listOf(SegmentClassification(command, ShellRisk.BLOCKED, directBlock)),
                blockReason = directBlock,
            )
        }

        // 第二层: 命令展开
        val expansion = ShellExpander.expandAndCheck(command)
        bypassIndicators.addAll(expansion.indicators)
        if (expansion.blockReason != null) {
            return DeepClassifyResult(
                overall = ShellRisk.BLOCKED,
                segments = listOf(SegmentClassification(command, ShellRisk.BLOCKED, expansion.blockReason)),
                blockReason = expansion.blockReason,
                bypassIndicators = bypassIndicators,
            )
        }

        // 第一层: 递归解析
        val parsed = ShellCommandParser.parse(command)
        bypassIndicators.addAll(parsed.indicators)

        val segmentResults = mutableListOf<SegmentClassification>()
        var overall = ShellRisk.READ_ONLY
        var blockReasonResult: String? = null

        for (segment in parsed.segments) {
            if (segment.startsWith("UNKNOWN_VARIABLE_EXECUTION:")) {
                segmentResults.add(SegmentClassification(segment, ShellRisk.WRITE, "unresolvable variable execution"))
                if (overall == ShellRisk.READ_ONLY) overall = ShellRisk.WRITE
                continue
            }
            val segBlock = blockReason(segment)
            if (segBlock != null) {
                segmentResults.add(SegmentClassification(segment, ShellRisk.BLOCKED, segBlock))
                overall = ShellRisk.BLOCKED
                blockReasonResult = segBlock
                break
            }
            val risk = classifySegment(segment)
            val reason = when (risk) {
                ShellRisk.READ_ONLY -> null
                ShellRisk.WRITE -> "write operation detected"
                ShellRisk.BLOCKED -> segBlock
            }
            segmentResults.add(SegmentClassification(segment, risk, reason))
            if (risk.ordinal > overall.ordinal) overall = risk
        }

        // 重定向检测
        if (overall == ShellRisk.READ_ONLY && REDIRECT_WRITE_REGEX.containsMatchIn(command)) {
            overall = ShellRisk.WRITE
            segmentResults.add(SegmentClassification("(redirect)", ShellRisk.WRITE, "output redirect detected"))
        }

        return DeepClassifyResult(
            overall = overall,
            segments = segmentResults,
            blockReason = blockReasonResult,
            bypassIndicators = bypassIndicators,
        )
    }

    /** 判断是否为简单命令(无管道、分号、命令替换、编码等) */
    private fun isSimpleCommand(command: String): Boolean {
        val trimmed = command.trim()
        // 无特殊分隔符和替换语法
        if (trimmed.contains('|') || trimmed.contains(';') || trimmed.contains('\n')) return false
        if (trimmed.contains("&&") || trimmed.contains("||")) return false
        if (trimmed.contains("$(") || trimmed.contains('`')) return false
        if (trimmed.contains("eval ") || trimmed.startsWith("eval")) return false
        // 无编码执行模式
        if (ShellCommandParser.parse(trimmed, 0).indicators.isNotEmpty()) return false
        return true
    }

    /** 若命令命中高危模式, 返回拒绝原因; 否则 null */
    fun blockReason(command: String): String? {
        for ((pattern, reason) in BLOCKED_PATTERNS) {
            if (pattern.containsMatchIn(command)) return reason
        }
        return null
    }

    private fun classifySegment(segment: String): ShellRisk {
        val tokens = segment.trim().split(WHITESPACE).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ShellRisk.READ_ONLY
        // 跳过前导环境变量赋值 (FOO=1 cmd ...) 和 sudo/nice/time 等包装
        var idx = 0
        while (idx < tokens.size) {
            val t = tokens[idx]
            when {
                t.contains('=') && !t.startsWith("-") && t.substringBefore('=').matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) -> idx++
                t in WRAPPER_COMMANDS -> idx++
                else -> break
            }
        }
        if (idx >= tokens.size) return ShellRisk.READ_ONLY
        val cmd = tokens[idx].substringAfterLast('/')

        // 新增: 检测 . 命令(source 的简写)
        if (cmd == ".") return ShellRisk.WRITE

        CONDITIONAL_WRITE_FLAGS.firstOrNull { it.first == cmd }?.let { (_, flagPattern) ->
            if (flagPattern.pattern == ".*" || flagPattern.containsMatchIn(segment)) return ShellRisk.WRITE
        }
        return if (cmd in READ_ONLY_COMMANDS) ShellRisk.READ_ONLY else ShellRisk.WRITE
    }

    private val WRAPPER_COMMANDS = setOf("sudo", "nice", "time", "ionice", "stdbuf", "timeout", "nohup", "env", "\\")
    private val WHITESPACE = Regex("\\s+")
    private val REDIRECT_WRITE_REGEX = Regex("""(^|[^>])>(?!&)\s*[^&\s]|\btee\b""")
}

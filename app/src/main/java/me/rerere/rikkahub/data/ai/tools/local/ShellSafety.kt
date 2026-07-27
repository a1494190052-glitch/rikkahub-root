package me.rerere.rikkahub.data.ai.tools.local

/**
 * Shell 命令三级风险分级:
 *  - READ_ONLY: 白名单只读命令, 免审批直接执行
 *  - WRITE: 可能修改系统/文件, 默认需要用户审批(可在工作区设置里关闭)
 *  - BLOCKED: 命中高危模式( rm -rf /, dd 写块设备, fork bomb 等), 直接拒绝执行
 *
 * 分类是保守的: 识别不了的一律按 WRITE 处理.
 */
enum class ShellRisk { READ_ONLY, WRITE, BLOCKED }

/**
 * 深度分类结果 — 包含风险等级 + 详细原因(用于向 AI 解释为什么被拦截)
 */
data class DeepClassification(
    val risk: ShellRisk,
    /** 拦截/提级原因; READ_ONLY 时为 null */
    val reason: String? = null,
    /** 触发问题的子命令片段(如有) */
    val offendingSegment: String? = null,
)

object ShellSafety {

    // ==================== 第一层: 静态分类(原有逻辑, 向后兼容) ====================

    /** 命中即拒绝执行的高危模式(对整条命令全文匹配) */
    private val BLOCKED_PATTERNS: List<Pair<Regex, String>> = listOf(
        // rm -rf / 或 rm -rf /* 或递归删除系统目录
        Regex("""\brm\s+[^;&|]*-[a-zA-Z]*r[a-zA-Z]*\s+(--\S+\s+)*(/\*?|/(system|vendor|boot|proc|dev|etc|lib|lib64|bin|sbin|product|data|storage/emulated/0)(/|\s|$))""") to "recursive delete of system-critical path",
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
    )

    fun classify(command: String): ShellRisk {
        blockReason(command)?.let { return ShellRisk.BLOCKED }
        // 命令替换 $(...)/反引号(引号外): 内部可能藏任意命令, 保守提级 WRITE
        if (hasCommandSubstitution(command)) return ShellRisk.WRITE
        val segments = splitSegments(command)
        var hasWrite = false
        for (segment in segments) {
            val risk = classifySegment(segment)
            if (risk == ShellRisk.BLOCKED) return ShellRisk.BLOCKED
            if (risk == ShellRisk.WRITE) hasWrite = true
        }
        if (hasWrite) return ShellRisk.WRITE
        // 整串含输出重定向(不在引号内的检测从简) → WRITE
        if (REDIRECT_WRITE_REGEX.containsMatchIn(command)) return ShellRisk.WRITE
        return ShellRisk.READ_ONLY
    }

    /** 检测引号外的 $( 或反引号命令替换 */
    private fun hasCommandSubstitution(command: String): Boolean {
        var quote: Char? = null
        var i = 0
        while (i < command.length) {
            val c = command[i]
            when {
                quote != null -> {
                    // 双引号内的 $( 和 ` 仍会被 shell 展开, 单引号内不展开
                    if (c == quote) quote = null
                    else if (quote == '"' && (c == '`' || (c == '$' && i + 1 < command.length && command[i + 1] == '('))) return true
                }
                c == '\'' || c == '"' -> quote = c
                c == '`' -> return true
                c == '$' && i + 1 < command.length && command[i + 1] == '(' -> return true
            }
            i++
        }
        return false
    }

    /** 若命令命中高危模式, 返回拒绝原因; 否则 null */
    fun blockReason(command: String): String? {
        for ((pattern, reason) in BLOCKED_PATTERNS) {
            if (pattern.containsMatchIn(command)) return reason
        }
        return null
    }

    /** 按 | ; && || 拆分成独立命令段(引号内不拆) */
    private fun splitSegments(command: String): List<String> {
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
                c == '|' || c == ';' -> {
                    if (i + 1 < command.length && command[i + 1] == '|') i++
                    segments += current.toString()
                    current.clear()
                    if (i + 1 < command.length && command[i + 1] == '&') i++
                }
                c == '\n' || c == '\r' -> {
                    // 换行与分号等价: 防 "ls\npm uninstall x" 之类的分段绕过
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
        return segments.filter { it.isNotBlank() }.ifEmpty { listOf(command) }
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
        CONDITIONAL_WRITE_FLAGS.firstOrNull { it.first == cmd }?.let { (_, flagPattern) ->
            if (flagPattern.pattern == ".*" || flagPattern.containsMatchIn(segment)) return ShellRisk.WRITE
        }
        return if (cmd in READ_ONLY_COMMANDS) ShellRisk.READ_ONLY else ShellRisk.WRITE
    }

    private val WRAPPER_COMMANDS = setOf("sudo", "nice", "time", "ionice", "stdbuf", "timeout", "nohup", "env", "\\")
    private val WHITESPACE = Regex("\\s+")
    private val REDIRECT_WRITE_REGEX = Regex("""(^|[^>])>(?!&)\s*[^&\s]|\btee\b""")

    // ==================== 第二层: 动态深度检测 ====================

    /** 可执行/危险命令集 — 出现在管道末端或子 shell 中即视为高风险 */
    private val EXECUTION_COMMANDS = setOf(
        "sh", "bash", "dash", "ash", "zsh", "ksh", "csh", "tcsh",
        "eval", "exec", "source", ".",
        "python", "python2", "python3", "perl", "ruby", "node", "php",
        "nc", "ncat", "netcat", "socat",
    )

    /** 编码/解码命令 — 与执行命令组合时构成绕过 */
    private val DECODE_COMMANDS = setOf("base64", "xxd", "openssl", "uudecode")

    /**
     * 深度分类: 在静态分类基础上增加管道解析、编码绕过检测、敏感路径检测。
     * 纯字符串解析, 无 IO, 设计目标 < 5ms。
     */
    /**
     * 深度分类。
     * @param strict true=子代理模式(执行解释器/编码绕过一律 BLOCKED);
     *               false=主代理模式(这些降级为 WRITE, 交给审批机制, 用户可放行)。
     * 灾难级操作(删根/写块设备/fork bomb/删 root)与敏感系统路径写入在两种模式下都 BLOCKED。
     */
    fun deepClassify(command: String, strict: Boolean = false): DeepClassification {
        // 1. 灾难级: 两种模式都硬拒绝
        blockReason(command)?.let { reason ->
            return DeepClassification(ShellRisk.BLOCKED, reason = reason)
        }

        // 2. 敏感系统路径写入: 两种模式都硬拒绝(保护设备, 即使经过 python3 包装)
        sensitivePathReason(command)?.let { reason ->
            return DeepClassification(ShellRisk.BLOCKED, reason = reason)
        }

        // 3. 执行解释器(python3/sh/eval 等): strict→BLOCKED, relaxed→WRITE(可审批)
        val subCommands = parseSubCommands(command)
        for (sub in subCommands) {
            val cmd = extractCommandName(sub)
            if (cmd in EXECUTION_COMMANDS) {
                return if (strict) {
                    DeepClassification(
                        ShellRisk.BLOCKED,
                        reason = "execution interpreter '$cmd' not allowed in subagent",
                        offendingSegment = sub.trim(),
                    )
                } else {
                    DeepClassification(
                        ShellRisk.WRITE,
                        reason = "execution interpreter '$cmd' (main agent: approval-gated)",
                        offendingSegment = sub.trim(),
                    )
                }
            }
        }

        // 4. 编码绕过(base64|sh 等): strict→BLOCKED, relaxed→WRITE(可审批)
        encodingBypassReason(command)?.let { reason ->
            return if (strict) {
                DeepClassification(ShellRisk.BLOCKED, reason = reason)
            } else {
                DeepClassification(ShellRisk.WRITE, reason = "$reason (main agent: approval-gated)")
            }
        }

        // 5. 回退到静态分类
        val static = classify(command)
        return when (static) {
            ShellRisk.BLOCKED -> DeepClassification(ShellRisk.BLOCKED, reason = "blocked by static pattern")
            ShellRisk.WRITE -> DeepClassification(ShellRisk.WRITE, reason = "write operation detected")
            ShellRisk.READ_ONLY -> DeepClassification(ShellRisk.READ_ONLY)
        }
    }

    // ---------- 管道/子命令解析器 ----------

    /**
     * 递归解析 shell 命令, 提取所有实际执行的子命令片段。
     * 处理: 管道(|)、分号(;)、&&、||、子 shell $(...)、反引号 `...`、括号子 shell (...)。
     */
    fun parseSubCommands(command: String): List<String> {
        val results = mutableListOf<String>()
        parseRecursive(command, results)
        return results
    }

    private fun parseRecursive(input: String, out: MutableList<String>) {
        // 先提取 $(...) 和 `...` 中的内容递归解析
        var cleaned = input
        // 提取 $(...) 内容
        var searchFrom = 0
        while (true) {
            val start = cleaned.indexOf("$(", searchFrom)
            if (start < 0) break
            val end = findMatchingParen(cleaned, start + 1)
            if (end > start) {
                val inner = cleaned.substring(start + 2, end)
                parseRecursive(inner, out)
                searchFrom = end + 1
            } else {
                searchFrom = start + 2
            }
        }
        // 提取 `...` 内容
        var backtickStart = cleaned.indexOf('`')
        while (backtickStart >= 0) {
            val backtickEnd = cleaned.indexOf('`', backtickStart + 1)
            if (backtickEnd > backtickStart) {
                val inner = cleaned.substring(backtickStart + 1, backtickEnd)
                parseRecursive(inner, out)
                backtickStart = cleaned.indexOf('`', backtickEnd + 1)
            } else break
        }

        // 按顶层分隔符拆分
        val segments = splitSegments(cleaned)
        for (seg in segments) {
            val trimmed = seg.trim()
            if (trimmed.isNotEmpty()) {
                out.add(trimmed)
            }
        }
    }

    private fun findMatchingParen(s: String, openIdx: Int): Int {
        var depth = 0
        var quote: Char? = null
        for (i in openIdx until s.length) {
            val c = s[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /** 从命令片段中提取实际命令名(跳过环境变量赋值和 wrapper) */
    private fun extractCommandName(segment: String): String {
        val tokens = segment.trim().split(WHITESPACE).filter { it.isNotBlank() }
        var idx = 0
        while (idx < tokens.size) {
            val t = tokens[idx]
            when {
                t.contains('=') && !t.startsWith("-") &&
                    t.substringBefore('=').matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) -> idx++
                t in WRAPPER_COMMANDS -> idx++
                else -> break
            }
        }
        if (idx >= tokens.size) return ""
        return tokens[idx].substringAfterLast('/')
    }

    // ---------- 编码绕过检测 ----------

    /** 检测编码绕过模式, 返回原因或 null */
    private fun encodingBypassReason(command: String): String? {
        // 模式 1: 解码命令 + 管道到执行器 (base64 -d | sh, xxd -r | bash, etc.)
        val segments = splitSegments(command)
        var hasDecode = false
        for (seg in segments) {
            val cmd = extractCommandName(seg)
            if (cmd in DECODE_COMMANDS && DECODE_FLAG_REGEX.containsMatchIn(seg)) {
                hasDecode = true
            }
            if (hasDecode && cmd in EXECUTION_COMMANDS) {
                return "encoded payload piped to execution interpreter '$cmd' (decode-then-execute bypass)"
            }
        }

        // 模式 2: eval / source / . 命令
        for (seg in segments) {
            val cmd = extractCommandName(seg)
            if (cmd == "eval") return "eval command detected — arbitrary code execution risk"
            if (cmd == "source" || cmd == ".") {
                // source 一个非常规路径
                if (seg.contains("/dev/") || seg.contains("/proc/")) {
                    return "sourcing from suspicious path"
                }
            }
        }

        // 模式 3: ${IFS} 绕过 / $'\x..' 十六进制拼接
        if (IFS_BYPASS_REGEX.containsMatchIn(command)) {
            return "\${IFS} or variable-splicing bypass detected"
        }
        if (HEX_ESCAPE_EXEC_REGEX.containsMatchIn(command)) {
            return "hex/octal escape sequence used to obfuscate command"
        }

        // 模式 4: /dev/tcp 或 /dev/udp 网络操作
        if (DEV_NET_REGEX.containsMatchIn(command)) {
            return "/dev/tcp or /dev/udp network operation detected (reverse shell risk)"
        }

        // 模式 5: printf 输出十六进制后管道到执行器
        if (PRINTF_HEX_PIPE_REGEX.containsMatchIn(command)) {
            return "printf hex output piped to potential execution"
        }

        return null
    }

    private val DECODE_FLAG_REGEX = Regex("""\s(-d|-D|--decode|-r|-p)\b|\benc\b""")
    private val IFS_BYPASS_REGEX = Regex("""\$\{?IFS\}?|\$\{[A-Za-z_]*\}""")  
    private val HEX_ESCAPE_EXEC_REGEX = Regex("""\$'\\x[0-9a-fA-F]{2}|\$'\\[0-7]{1,3}""")
    private val DEV_NET_REGEX = Regex("""/dev/(tcp|udp)/""")
    private val PRINTF_HEX_PIPE_REGEX = Regex("""printf\s+['"]?(\\x[0-9a-fA-F]{2})+.*\|\s*(sh|bash|eval|exec|python|perl)\b""")

    // ---------- 敏感路径检测 ----------

    /** 本应用包名前缀 — 允许读写 */
    private const val APP_PACKAGE = "me.rerere.rikkahub"

    /** 无害设备节点白名单 — 重定向写入这些节点永远安全 */
    private val HARMLESS_DEV_NODES = setOf(
        "/dev/null", "/dev/zero", "/dev/urandom", "/dev/random",
        "/dev/stdin", "/dev/stdout", "/dev/stderr", "/dev/tty",
    )

    /** 敏感系统路径(写操作需要拦截) */
    private val SENSITIVE_PATHS = listOf(
        "/system",
        "/vendor",
        "/boot",
        "/proc",
        "/dev",
        "/etc",
        "/lib",
        "/lib64",
        "/bin",
        "/sbin",
        "/product",
    )

    /** 检测对敏感路径的写操作, 返回原因或 null */
    private fun sensitivePathReason(command: String): String? {
        // 检测写操作指示: 重定向 >, >>, tee, cp/mv 目标, chmod/chown, rm, 或写动词
        val hasWriteIndicator = WRITE_INDICATOR_REGEX.containsMatchIn(command)
        if (!hasWriteIndicator) return null

        // 提取命令中涉及的绝对路径
        val paths = PATH_EXTRACT_REGEX.findAll(command).map { it.value }.toList()
        for (path in paths) {
            // 允许的路径
            if (path.startsWith("/sdcard")) continue
            if (path.startsWith("/data/data/$APP_PACKAGE")) continue
            if (path.startsWith("/storage/emulated")) continue
            if (path.startsWith("/tmp")) continue
            // 允许无害设备节点 (/dev/null, /dev/zero 等) — 重定向到它们是标准安全操作
            if (path in HARMLESS_DEV_NODES || path.startsWith("/dev/fd")) continue

            // 检查是否命中敏感路径
            for (sensitive in SENSITIVE_PATHS) {
                if (path == sensitive || path.startsWith("$sensitive/")) {
                    return "write operation targets sensitive system path: $path"
                }
            }

            // /data/data 下非本应用的数据
            if (path.startsWith("/data/data/") && !path.startsWith("/data/data/$APP_PACKAGE")) {
                return "write operation targets another app's private data: $path"
            }
        }
        return null
    }

    private val WRITE_INDICATOR_REGEX = Regex(
        """(^|[^>])>{1,2}\s*[^&\s]|\b(tee|cp|mv|rm|chmod|chown|install|dd|sed\s+-i|truncate|shred)\b"""
    )
    private val PATH_EXTRACT_REGEX = Regex("""/(?:system|vendor|boot|proc|dev|etc|lib64?|bin|sbin|product|data/data|sdcard|storage/emulated|tmp)[^\s;|&'"<>]*""")
}

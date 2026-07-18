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

object ShellSafety {

    /** 命中即拒绝执行的高危模式(对整条命令全文匹配) */
    private val BLOCKED_PATTERNS: List<Pair<Regex, String>> = listOf(
        // rm -rf / 或 rm -rf /* 或递归删除系统目录
        Regex("""\brm\s+[^;&|]*-[a-zA-Z]*[rf][a-zA-Z]*\s+(--\S+\s+)*(/\*|/|/system|/vendor|/data(/|\s|$)|/boot|/proc|/dev|/etc|/lib|/lib64|/bin|/sbin|/product|/storage/emulated/0\s*$)""") to "recursive delete of system-critical path",
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
        // 含重定向写入/命令替换时整体提级(保守)
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
}

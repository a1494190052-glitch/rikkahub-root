package me.rerere.rikkahub.acp

import kotlinx.serialization.Serializable

/**
 * 一个外部 ACP agent 的启动配置。
 *
 * 对应 OpenOmniBot 的 AcpAgentProfile：一条 profile 描述如何在一个子进程里
 * 拉起一个实现了 Agent Client Protocol 的 agent（codex / gemini-cli /
 * deepseek-harness 等）。命令在 RikkaHub 的 proot 工作区（Ubuntu rootfs）
 * 内执行，因此 node/npx/bun 等依赖都来自工作区本身。
 */
@Serializable
data class AcpAgentProfile(
    val id: String,
    val name: String,
    /** 启动命令，例如 "codex"、"npx @openai/codex"、"node /home/dsh/acp/demo.mjs" */
    val command: String,
    val arguments: List<String> = emptyList(),
    /** 追加到进程环境变量（在 proot 环境之上）。 */
    val environment: Map<String, String> = emptyMap(),
    /** 工作区内的启动目录，默认 /workspace。 */
    val cwd: String = "/workspace",
    val enabled: Boolean = true,
)

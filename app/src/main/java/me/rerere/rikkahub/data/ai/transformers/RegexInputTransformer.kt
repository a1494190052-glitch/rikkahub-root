package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.RegexApplyMode
import me.rerere.rikkahub.data.model.replaceRegexes
import org.koin.core.component.KoinComponent

/**
 * 发送通道正则 (ST promptOnly 语义)
 *
 * 在消息发送给模型前, transient 地应用 promptOnly=true 的正则脚本:
 * 只影响发给 AI 的内容, 不修改存储的消息, 也不影响界面显示。
 * 支持 ST 深度限制 (minDepth/maxDepth, depth 0 = 最新一条消息)。
 */
object RegexInputTransformer : InputMessageTransformer, KoinComponent {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val assistant = ctx.assistant
        if (assistant.regexes.none { it.enabled && it.promptOnly }) return messages
        val lastIndex = messages.lastIndex
        return messages.mapIndexed { index, message ->
            val depth = lastIndex - index // ST: depth 0 = 最新消息
            val scope = when (message.role) {
                MessageRole.USER -> AssistantAffectScope.USER
                MessageRole.ASSISTANT -> AssistantAffectScope.ASSISTANT
                else -> return@mapIndexed message
            }
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Text -> part.copy(
                            text = part.text.replaceRegexes(assistant, scope, RegexApplyMode.PROMPT, depth)
                        )

                        is UIMessagePart.Reasoning -> part.copy(
                            reasoning = part.reasoning.replaceRegexes(assistant, scope, RegexApplyMode.PROMPT, depth)
                        )

                        else -> part
                    }
                }
            )
        }
    }
}

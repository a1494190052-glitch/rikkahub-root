package me.rerere.rikkahub.plugin.manager

/**
 * 错误上报抽象。
 *
 * 插件系统通过它把失败呈现给用户，而不必直接依赖 ChatService（避免
 * PluginManager → ChatService → LocalTools → PluginManager 的循环依赖）。
 * 由 DI 层用懒加载 lambda 桥接到 ChatService.addError。
 */
fun interface ErrorReporter {
    /**
     * 上报一个错误。实现方应自行处理 CancellationException（如 ChatService.addError 会跳过它）。
     * @param error 异常
     * @param title 用户可见的错误标题（可选）
     */
    fun report(error: Throwable, title: String? = null)
}

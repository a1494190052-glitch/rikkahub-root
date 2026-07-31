package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import me.rerere.rikkahub.data.datastore.Settings

/**
 * 稳定引用包装（非 data class，equals 走引用比较）。
 *
 * 背景：Compose 在每次重组 CompositionLocalProvider 块时，会用
 * CompositionLocalMap.equals 比较新旧提供值；若直接提供 55 字段的
 * Settings data class，StaticValueHolder.equals 会触发 Settings.equals
 * 全量深比较（嵌套 providers/assistants/lorebooks/promptInjections 的
 * 字符串与正则，逐字符比），主线程上每帧/每次重组重复执行 → ANR。
 *
 * 包装后比较退化为引用比较 O(1)；配合 RouteActivity 中
 * `remember(settings) { SettingsRef(settings) }` 保证实例稳定：
 * settings 内容没变 → ref 引用相等 → 不触发重组，行为与之前一致。
 */
class SettingsRef(val settings: Settings)

val LocalSettings = staticCompositionLocalOf<SettingsRef> {
    error("No SettingsStore provided")
}

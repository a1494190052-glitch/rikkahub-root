package me.rerere.rikkahub.plugin.di

import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.ui.PluginViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Koin module for the plugin system.
 * Registers PluginManager as a singleton and PluginViewModel for UI.
 */
val pluginModule = module {
    single {
        PluginManager(
            context = androidContext(),
            json = get(),
            scope = get(),
            // 懒加载桥接到 ChatService.addError：get() 在 lambda 调用时才解析 ChatService，
            // 避免 PluginManager → ChatService → LocalTools → PluginManager 的构造期循环依赖。
            errorReporter = { error, title ->
                runCatching { get<me.rerere.rikkahub.service.ChatService>().addError(error, title = title) }
            },
        )
    }

    viewModelOf(::PluginViewModel)
}

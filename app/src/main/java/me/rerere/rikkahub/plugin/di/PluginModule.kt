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
        )
    }

    viewModelOf(::PluginViewModel)
}

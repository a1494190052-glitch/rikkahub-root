package me.rerere.rikkahub.plugin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import java.io.File

/**
 * ViewModel for the Plugin Manager UI.
 */
class PluginViewModel(
    private val pluginManager: PluginManager,
) : ViewModel() {

    val plugins: StateFlow<List<PluginInfo>> = pluginManager.plugins

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage: StateFlow<String?> = _operationMessage.asStateFlow()

    init {
        // Ensure plugin system is initialized
        viewModelScope.launch {
            pluginManager.initialized.await()
        }
    }

    /**
     * Toggle plugin enabled/disabled state.
     */
    fun togglePlugin(pluginId: String) {
        pluginManager.togglePlugin(pluginId)
    }

    /**
     * Import a plugin from a zip file.
     */
    fun importPlugin(zipFile: File) {
        viewModelScope.launch {
            _importState.value = ImportState.Importing
            val result = pluginManager.importPlugin(zipFile)
            _importState.value = result.fold(
                onSuccess = { manifest ->
                    _operationMessage.value = "Plugin '${manifest.name}' imported successfully"
                    ImportState.Success(manifest)
                },
                onFailure = { error ->
                    _operationMessage.value = "Import failed: ${error.message}"
                    ImportState.Error(error.message ?: "Unknown error")
                }
            )
        }
    }

    /**
     * Delete a plugin.
     */
    fun deletePlugin(pluginId: String) {
        pluginManager.deletePlugin(pluginId)
        _operationMessage.value = "Plugin deleted"
    }

    /**
     * Update plugin configuration.
     */
    fun updateConfig(pluginId: String, config: Map<String, String>) {
        pluginManager.updateConfig(pluginId, config)
        _operationMessage.value = "Configuration updated"
    }

    /**
     * Refresh plugin list.
     */
    fun refresh() {
        pluginManager.scanPlugins()
    }

    /**
     * Clear the operation message.
     */
    fun clearMessage() {
        _operationMessage.value = null
    }

    /**
     * Reset import state.
     */
    fun resetImportState() {
        _importState.value = ImportState.Idle
    }

    sealed class ImportState {
        data object Idle : ImportState()
        data object Importing : ImportState()
        data class Success(val manifest: PluginManifest) : ImportState()
        data class Error(val message: String) : ImportState()
    }
}

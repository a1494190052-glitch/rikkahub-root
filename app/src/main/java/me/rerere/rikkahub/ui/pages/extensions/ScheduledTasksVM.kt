package me.rerere.rikkahub.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import me.rerere.rikkahub.service.scheduler.ScheduledTaskRepository

class ScheduledTasksVM(
    private val repo: ScheduledTaskRepository,
    settingsStore: SettingsStore,
) : ViewModel() {
    val tasks = repo.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val settings = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { repo.setEnabled(id, enabled) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.delete(id) }
    }

    fun create(task: ScheduledTaskEntity) {
        viewModelScope.launch { repo.upsert(task) }
    }
}

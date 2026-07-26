package me.rerere.rikkahub.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.service.shell.BackgroundShellManager
import me.rerere.rikkahub.service.shell.ShellAuditLogger

class ShellAuditVM(
    private val auditLogger: ShellAuditLogger,
    private val backgroundShellManager: BackgroundShellManager,
) : ViewModel() {

    val items = auditLogger.recentFlow(300)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearAll() {
        viewModelScope.launch { auditLogger.clearAll() }
    }

    fun killTask(taskId: String) {
        backgroundShellManager.kill(taskId)
    }
}

package com.example.polarh10activityviewer.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.example.polarh10activityviewer.storage.SaveState
import com.example.polarh10activityviewer.storage.SaveStatus
import com.example.polarh10activityviewer.storage.SessionSaveController
import com.example.polarh10activityviewer.ui.theme.ContentSpacing

@Composable
internal fun SavePanel(state: SaveState, controller: SessionSaveController, currentSessionId: String?, showRetry: Boolean = true) {
    // Blocking writes remain recoverable on either page; terminal status belongs to its session.
    if (state.status == SaveStatus.IDLE || (!state.blocksStart && state.sessionId != currentSessionId)) return
    var confirmDiscard by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    Text("Save: ${state.status.label}")
    if (state.status == SaveStatus.FAILED) {
        Text(state.error ?: "Unable to save session.", color = MaterialTheme.colorScheme.error)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(ContentSpacing),
            verticalArrangement = Arrangement.spacedBy(ContentSpacing)) {
            if (showRetry) Button(onClick = { controller.retry() }) { Text("Retry save") }
            TextButton(onClick = { confirmDiscard = true }) { Text("Discard session") }
        }
    }
    if (confirmDiscard && state.status == SaveStatus.FAILED) {
        val textHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * 0.4f }
        AlertDialog(onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard unsaved session?") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = textHeight)
                    .verticalScroll(rememberScrollState())) {
                    Text("This session has not been saved. Discarding it removes the pending save and cannot be undone.")
                }
            },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; controller.discard() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } })
    }
}

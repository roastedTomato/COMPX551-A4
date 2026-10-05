package com.example.polarh10activityviewer.session

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.example.polarh10activityviewer.storage.RecordingState

@Composable
internal fun RecordingPanel(state: RecordingState, retry: () -> Unit, discard: () -> Unit, canDiscard: Boolean) {
    var confirm by remember { mutableStateOf(false) }
    if (!state.blocked) return
    if (state.error == null) Text("Preparing recording storage…", style = MaterialTheme.typography.bodySmall)
    else {
        Text("Recording storage failed", color = MaterialTheme.colorScheme.error)
        Text(state.error, style = MaterialTheme.typography.bodySmall)
        FlowRow {
            TextButton(onClick = retry, enabled = !state.busy) { Text("Retry save") }
            if (canDiscard) TextButton(onClick = { confirm = true }, enabled = !state.busy) { Text("Discard") }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Discard this recording?") },
        text = { Text("Its pending and partially stored data will be deleted.") },
        confirmButton = { TextButton(onClick = { confirm = false; discard() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

package com.example.polarh10activityviewer.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.polarh10activityviewer.BluetoothAvailability
import com.example.polarh10activityviewer.R
import com.example.polarh10activityviewer.ble.ConnectionState
import com.example.polarh10activityviewer.ble.ConnectionStatus
import com.example.polarh10activityviewer.ble.DataReadiness
import com.example.polarh10activityviewer.ble.DataReadinessStatus
import com.example.polarh10activityviewer.ble.SubscriptionState
import com.example.polarh10activityviewer.ble.SubscriptionStatus
import com.example.polarh10activityviewer.ui.theme.ContentSpacing

@Composable
internal fun SessionScaffold(
    showHistory: Boolean,
    onSelectHistory: (Boolean) -> Unit,
    controls: @Composable () -> Unit,
    sessionContent: @Composable () -> Unit,
    historyContent: @Composable () -> Unit
) {
    val pages = rememberSaveableStateHolder()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = if (showHistory) MaterialTheme.colorScheme.background else sessionBackground(),
        topBar = {
            Row(Modifier.fillMaxWidth().background(sessionBackground()).statusBarsPadding().padding(horizontal = 28.dp)) {
                listOf("Session", "History").forEachIndexed { index, label ->
                    val selected = showHistory == (index == 1)
                    Tab(selected = showHistory == (index == 1), onClick = { onSelectHistory(index == 1) }, //这就是按钮
                        modifier = Modifier.weight(1f),
                        selectedContentColor = sessionBlue(),
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
                        Text(label, Modifier.padding(top = 6.dp, bottom = 6.dp), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Box(Modifier.fillMaxWidth().padding(horizontal = 2.dp).height(2.dp)
                            .background(if (selected) sessionBlue() else sessionBorder()))//在 tab 下方画一条线。
                    }
                }
            }
        },
        bottomBar = { if (!showHistory) controls() }//如果当前不是 History 页面，就显示底部控制按钮。
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {//告诉子组件：这些 padding 已经被父级处理过了，不需要重复处理。
            pages.SaveableStateProvider(if (showHistory) "history" else "session") {//为不同页面提供独立的可保存状态区域。
                if (showHistory) historyContent() else sessionContent()//这是核心切换逻辑。
            }
        }
    }
}

internal fun startDisabledReason(
    availability: BluetoothAvailability,
    actionEnabled: Boolean,
    connection: ConnectionState,
    session: SessionState,
    savingBlocksStart: Boolean,
    subscriptions: List<SubscriptionState>,
    readiness: Collection<DataReadiness>
): String? = when {
    session.open -> "Stop the current session before starting another."
    session.status == SessionStatus.STOPPING -> "Waiting for streams to stop."
    savingBlocksStart -> "Finish saving or discard the failed session before Start."
    !actionEnabled -> "Complete the Bluetooth system request."
    availability != BluetoothAvailability.READY -> "Open Devices to enable Bluetooth access."
    connection.status != ConnectionStatus.CONNECTED -> "Open Devices and connect an H10 to Start."
    subscriptions.any { it.status in setOf(SubscriptionStatus.STARTING, SubscriptionStatus.RECEIVING,
        SubscriptionStatus.STOPPING) } -> "Waiting for streams to stop."
    readiness.none { it.status == DataReadinessStatus.READY && it.configurationComplete } ->
        "Open Devices to check data readiness."
    else -> null
}

@Composable
internal fun SessionControls(canStart: Boolean, canStop: Boolean, onStart: () -> Unit, onStop: () -> Unit,
    canPause: Boolean, canResume: Boolean, paused: Boolean,
    onPause: () -> Unit, onResume: () -> Unit) {
    Surface(color = sessionBackground()) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally)) {
            listOf(Triple("Pause", R.drawable.ic_pause, canPause),
                Triple(if (paused) "Continue" else "Start", R.drawable.ic_start, if (paused) canResume else canStart),
                Triple("Stop", R.drawable.ic_stop, canStop))
                .forEach { (label, icon, enabled) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(ContentSpacing)) {
                        Button(onClick = when (label) { "Pause" -> onPause; "Continue" -> onResume; "Start" -> onStart; else -> onStop }, enabled = enabled,
                            shape = CircleShape, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = sessionBlue(),
                                contentColor = androidx.compose.ui.graphics.Color.White,
                                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
                                disabledContentColor = androidx.compose.ui.graphics.Color.White),
                            modifier = Modifier.size(64.dp).semantics { contentDescription = label }) {
                            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(32.dp))
                        }
                    }
                }
        }
    }
}

@Composable
internal fun SessionStatusPanel(session: SessionState, disabledReason: String?) {
    if (session.status != SessionStatus.RUNNING) Text("Session: ${session.status.label}", style = MaterialTheme.typography.titleMedium)
    if (!session.open) disabledReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (session.status == SessionStatus.STARTING) Text("Waiting for the first sensor data.")
    session.endReason?.let { Text(if (it == "TIME_LIMIT") "Session time limit reached." else it) }
}

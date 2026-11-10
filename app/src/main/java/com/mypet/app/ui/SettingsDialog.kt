package com.mypet.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mypet.app.PetViewModel
import com.mypet.app.Screen
import com.mypet.app.UiState

@Composable
fun SettingsDialog(state: UiState, vm: PetViewModel, onDismiss: () -> Unit) {
    var confirmRestart by remember { mutableStateOf(false) }
    val settings = state.save.settings
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Settings", fontFamily = Mono, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Time speed", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 4).forEach { speed ->
                        FilterChip(
                            selected = settings.speed == speed,
                            onClick = { vm.setSpeed(speed) },
                            label = { Text("${speed}x") },
                        )
                    }
                }
                Text(
                    "1x is the classic real-time pace: one pet day is one real day. " +
                        "2x and 4x make everything happen faster, including calls.",
                    fontSize = 12.sp,
                )
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Notifications", fontWeight = FontWeight.Bold)
                        Text("A heads-up when your pet calls.", fontSize = 12.sp)
                    }
                    Switch(checked = settings.notifications, onCheckedChange = { vm.setNotifications(it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Pause", fontWeight = FontWeight.Bold)
                        Text("Freeze time while you're busy. Nothing drains, nothing grows.", fontSize = 12.sp)
                    }
                    Switch(checked = state.save.paused, onCheckedChange = { vm.togglePause() })
                }
                HorizontalDivider()
                Text("Tester tools", fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { vm.skipHour() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Skip ahead 1 hour")
                }
                if (state.pet != null) {
                    OutlinedButton(onClick = { confirmRestart = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Start over with a new egg")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "MyPet is an original game. All characters and art are made for this app.",
                    fontSize = 11.sp,
                )
            }
        },
    )
    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text("Start over?") },
            text = { Text("You'll pick a new edition and hatch a new egg. Your collection is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestart = false
                    onDismiss()
                    vm.show(Screen.START)
                }) { Text("Choose a new egg") }
            },
            dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text("Cancel") } },
        )
    }
}

package com.example.safe_kids.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    initialTime: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val parts = initialTime.split(":")
    val initialHour = parts.getOrNull(0)?.toIntOrNull() ?: 12
    val initialMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0
    // Use 12-hour mode with AM/PM toggle
    val timeState = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { 
                val timeStr = String.format("%02d:%02d", timeState.hour, timeState.minute)
                onConfirm(timeStr) 
            }) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        text = {
            TimePicker(state = timeState)
        }
    )
}

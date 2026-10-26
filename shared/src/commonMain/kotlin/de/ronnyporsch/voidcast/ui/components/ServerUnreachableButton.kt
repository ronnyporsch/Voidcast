package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** Tells that the server can't be reached; a click shows [reason] and allows trying again right away. */
@Composable
fun ServerUnreachableButton(reason: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    var showDetails by remember { mutableStateOf(false) }
    TooltipIconButton(
        onClick = { showDetails = true },
        icon = Icons.Default.CloudOff,
        contentDescription = "Server not reachable",
        tint = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("Server not reachable") },
            text = {
                SelectionContainer {
                    Text("$reason\n\nVoidcast keeps trying to connect. Until then, only what is stored on this device is available.")
                }
            },
            confirmButton = {
                Button(onClick = {
                    showDetails = false
                    onRetry()
                }) {
                    Text("Try again")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDetails = false }) { Text("Close") }
            },
        )
    }
}

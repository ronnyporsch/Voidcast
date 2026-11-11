package de.ronnyporsch.voidcast.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import de.ronnyporsch.voidcast.util.isUnencryptedRemoteUrl

/** Warns below the server URL field that an http:// address outside the local network isn't encrypted. */
@Composable
fun UnencryptedConnectionWarning(serverUrl: String, modifier: Modifier = Modifier) {
    if (!isUnencryptedRemoteUrl(serverUrl)) return
    Text(
        text = "This address is not encrypted: your password and everything else can be read on the way to the server. " +
            "Use https:// if the server supports it.",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier,
    )
}

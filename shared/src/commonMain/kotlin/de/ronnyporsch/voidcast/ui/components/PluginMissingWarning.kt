package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.ui.theme.AccentColor

/** Where Jellyfin finds the Voidcast plugin, see the README. */
private const val PLUGIN_REPOSITORY_URL = "https://raw.githubusercontent.com/ronnyporsch/Voidcast/plugin-repository/manifest.json"

/** Tells that the Jellyfin server lacks the Voidcast plugin, which everything YouTube Music related needs, and how to install it. */
@Composable
fun PluginMissingWarning(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
        shape = RoundedCornerShape(0.dp),
    ) {
        Row(Modifier.padding(12.dp)) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 12.dp).size(20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "The Voidcast plugin isn't installed on your Jellyfin server",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Without it, YouTube Music can't be searched, played or downloaded. To install it, add this " +
                        "repository in Jellyfin under Dashboard → Plugins → Repositories, then install Voidcast " +
                        "from the catalog and restart Jellyfin:",
                    style = MaterialTheme.typography.bodySmall,
                )
                SelectionContainer {
                    Text(PLUGIN_REPOSITORY_URL, style = MaterialTheme.typography.bodySmall, color = AccentColor)
                }
            }
        }
    }
}

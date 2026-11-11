package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.platform.canAcceptUntrustedCertificates
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SpotifyLightGray

/**
 * Lets the user accept a self-signed server certificate. Not shown in browsers, which decide that themselves.
 * [fingerprint] is the SHA-256 of the certificate the server is trusted with, null if there is none yet.
 */
@Composable
fun UntrustedCertificateSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    fingerprint: String? = null,
) {
    if (!canAcceptUntrustedCertificates) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Allow self-signed certificate", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (checked && !fingerprint.isNullOrBlank()) {
                    "Only the certificate the server showed first is trusted, with the SHA-256 fingerprint " +
                        fingerprint.uppercase().chunked(2).joinToString(":") +
                        ". Turn this off and on again if the server got a new one."
                } else {
                    "Trusts the certificate the server shows at the next connection even though nobody vouches for it, " +
                        "and only that one from then on."
                },
                style = MaterialTheme.typography.bodySmall,
                color = SpotifyLightGray,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AccentColor,
                checkedTrackColor = AccentColor.copy(alpha = 0.4f),
            ),
        )
    }
}

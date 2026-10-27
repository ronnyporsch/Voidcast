package de.ronnyporsch.voidcast.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ronnyporsch.voidcast.AppVersion
import de.ronnyporsch.voidcast.ui.components.PluginMissingWarning
import de.ronnyporsch.voidcast.ui.components.RememberScrollPosition
import de.ronnyporsch.voidcast.ui.components.VerticalScrollbar
import de.ronnyporsch.voidcast.ui.components.ServerTypeSelector
import de.ronnyporsch.voidcast.ui.components.UntrustedCertificateSwitch
import de.ronnyporsch.voidcast.ui.components.urlPlaceholder
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SpotifyLightGray
import de.ronnyporsch.voidcast.ui.theme.SpotifySurface
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var showLogoutDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    RememberScrollPosition(scrollState)

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Log out?") },
            text = { Text("You will need to enter your password again to log back in.") },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog = false
                        viewModel.onEvent(SettingsEvent.Logout)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Log out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("\u2190", fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Server Section
                SectionHeader("Server Connection")

                ServerTypeSelector(
                    selected = state.serverType,
                    onSelected = { viewModel.onEvent(SettingsEvent.ServerTypeChanged(it)) },
                )

                OutlinedTextField(
                    value = state.serverUrl,
                    onValueChange = { viewModel.onEvent(SettingsEvent.ServerUrlChanged(it)) },
                    label = { Text("Server URL") },
                    placeholder = { Text(state.serverType.urlPlaceholder) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = settingsTextFieldColors(),
                    shape = RoundedCornerShape(0.dp),
                )

                OutlinedTextField(
                    value = state.username,
                    onValueChange = { viewModel.onEvent(SettingsEvent.UsernameChanged(it)) },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = settingsTextFieldColors(),
                    shape = RoundedCornerShape(0.dp),
                )

                OutlinedTextField(
                    value = state.password,
                    onValueChange = { viewModel.onEvent(SettingsEvent.PasswordChanged(it)) },
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = settingsTextFieldColors(),
                    shape = RoundedCornerShape(0.dp),
                )

                UntrustedCertificateSwitch(
                    checked = state.allowUntrustedCertificate,
                    onCheckedChange = { viewModel.onEvent(SettingsEvent.AllowUntrustedCertificateToggled(it)) },
                )

                if (state.error != null) {
                    Text(
                        text = state.error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (state.reconnectSuccess) {
                    Text(
                        text = "Connected successfully",
                        color = AccentColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                OutlinedButton(
                    onClick = { viewModel.onEvent(SettingsEvent.Reconnect) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentColor),
                    enabled = !state.isReconnecting,
                ) {
                    if (state.isReconnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = AccentColor,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Reconnect", fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(
                    onClick = { showLogoutDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    enabled = !state.isLoggingOut && !state.isReconnecting,
                ) {
                    Text("Log out", fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(16.dp))

                // Playback Section
                SectionHeader("Playback")

                SettingsSwitch(
                    title = "Gapless Playback",
                    subtitle = "Pre-buffer the next track for seamless transitions",
                    checked = state.gaplessEnabled,
                    onCheckedChange = { viewModel.onEvent(SettingsEvent.GaplessToggled(it)) },
                )

                SettingsSwitch(
                    title = "Crossfade",
                    subtitle = "Smoothly fade between tracks",
                    checked = state.crossfadeEnabled,
                    onCheckedChange = { viewModel.onEvent(SettingsEvent.CrossfadeToggled(it)) },
                )

                if (state.crossfadeEnabled) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp),
                    ) {
                        Text(
                            text = "Crossfade duration: ${state.crossfadeDurationSeconds}s",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = state.crossfadeDurationSeconds.toFloat(),
                            onValueChange = {
                                viewModel.onEvent(SettingsEvent.CrossfadeDurationChanged(it.roundToInt()))
                            },
                            valueRange = 1f..12f,
                            steps = 10,
                            colors = SliderDefaults.colors(
                                thumbColor = AccentColor,
                                activeTrackColor = AccentColor,
                                inactiveTrackColor = SpotifySurface,
                            ),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("1s", style = MaterialTheme.typography.bodySmall, color = SpotifyLightGray)
                            Text("12s", style = MaterialTheme.typography.bodySmall, color = SpotifyLightGray)
                        }
                    }
                }

                state.serverYouTubeInfo?.let { info ->
                    Spacer(Modifier.height(16.dp))

                    SectionHeader("YouTube Music")

                    Text(info, style = MaterialTheme.typography.bodyMedium, color = SpotifyLightGray)
                }

                if (state.pluginMissing) {
                    Spacer(Modifier.height(16.dp))

                    SectionHeader("YouTube Music")

                    PluginMissingWarning()
                }

                Spacer(Modifier.height(16.dp))

                SectionHeader("About")

                Text("Voidcast $AppVersion", style = MaterialTheme.typography.bodyMedium, color = SpotifyLightGray)

                Spacer(Modifier.height(100.dp))
            }

            VerticalScrollbar(Modifier.align(Alignment.CenterEnd).fillMaxHeight(), scrollState)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = AccentColor,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SpotifyLightGray)
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

@Composable
private fun settingsTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentColor,
    cursorColor = AccentColor,
    focusedLabelColor = AccentColor,
)

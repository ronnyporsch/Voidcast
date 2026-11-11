package de.ronnyporsch.voidcast.ui.screen.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ronnyporsch.voidcast.AppVersion
import de.ronnyporsch.voidcast.ui.components.ServerTypeSelector
import de.ronnyporsch.voidcast.ui.components.UnencryptedConnectionWarning
import de.ronnyporsch.voidcast.ui.components.UntrustedCertificateSwitch
import de.ronnyporsch.voidcast.ui.components.urlPlaceholder
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SpotifyLightGray
import org.jetbrains.compose.resources.painterResource
import voidcast.shared.generated.resources.Res
import voidcast.shared.generated.resources.voidcast_logo

@Composable
fun LoginScreen(viewModel: LoginViewModel) {
    val state by viewModel.state.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painter = painterResource(Res.drawable.voidcast_logo),
                contentDescription = null,
                modifier = Modifier.size(96.dp),
            )

            Text(
                text = "Voidcast",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = "Version $AppVersion",
                style = MaterialTheme.typography.bodySmall,
                color = SpotifyLightGray,
            )

            Text(
                text = "Connect to your Navidrome or Jellyfin server",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            ServerTypeSelector(
                selected = state.serverType,
                onSelected = { viewModel.onEvent(LoginEvent.ServerTypeChanged(it)) },
            )

            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = { viewModel.onEvent(LoginEvent.ServerUrlChanged(it)) },
                label = { Text("Server URL") },
                placeholder = { Text(state.serverType.urlPlaceholder) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentColor,
                    cursorColor = AccentColor,
                    focusedLabelColor = AccentColor,
                ),
                shape = RoundedCornerShape(0.dp),
            )

            UnencryptedConnectionWarning(state.serverUrl)

            OutlinedTextField(
                value = state.username,
                onValueChange = { viewModel.onEvent(LoginEvent.UsernameChanged(it)) },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentColor,
                    cursorColor = AccentColor,
                    focusedLabelColor = AccentColor,
                ),
                shape = RoundedCornerShape(0.dp),
            )

            OutlinedTextField(
                value = state.password,
                onValueChange = { viewModel.onEvent(LoginEvent.PasswordChanged(it)) },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentColor,
                    cursorColor = AccentColor,
                    focusedLabelColor = AccentColor,
                ),
                shape = RoundedCornerShape(0.dp),
            )

            UntrustedCertificateSwitch(
                checked = state.allowUntrustedCertificate,
                onCheckedChange = { viewModel.onEvent(LoginEvent.AllowUntrustedCertificateToggled(it)) },
            )

            if (state.error != null) {
                Text(
                    text = state.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.onEvent(LoginEvent.ConnectClicked) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(0.dp)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentColor,
                ),
                enabled = !state.isLoading,
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        "Connect",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

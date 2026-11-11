package de.ronnyporsch.voidcast.ui.screen.login

import de.ronnyporsch.voidcast.data.repository.MusicServerConnection
import de.ronnyporsch.voidcast.domain.model.AppSettings
import de.ronnyporsch.voidcast.mvi.MviStore
import kotlinx.coroutines.CancellationException

class LoginViewModel(
    private val connection: MusicServerConnection,
    saved: AppSettings,
) : MviStore<LoginState, LoginEvent, LoginEffect>(
    // Pre-fill the last login, e.g. when the server rejected the saved one; the password isn't kept
    if (saved.serverUrl.isBlank()) {
        LoginState()
    } else {
        LoginState(
            serverType = saved.serverType,
            serverUrl = saved.serverUrl,
            username = saved.username,
            allowUntrustedCertificate = saved.allowUntrustedCertificate,
            certificateFingerprint = saved.certificateFingerprint.takeIf { saved.allowUntrustedCertificate },
        )
    }
) {

    override fun onEvent(event: LoginEvent) {
        when (event) {
            is LoginEvent.ServerTypeChanged -> setState { copy(serverType = event.type, error = null) }
            is LoginEvent.ServerUrlChanged -> setState { copy(serverUrl = event.url, error = null) }
            is LoginEvent.UsernameChanged -> setState { copy(username = event.username, error = null) }
            is LoginEvent.PasswordChanged -> setState { copy(password = event.password, error = null) }
            is LoginEvent.AllowUntrustedCertificateToggled -> {
                // Turning it on (again) trusts the certificate the server shows at the next connection
                connection.forgetCertificate()
                setState { copy(allowUntrustedCertificate = event.allow, certificateFingerprint = null, error = null) }
            }
            is LoginEvent.ConnectClicked -> connect()
        }
    }

    private fun connect() {
        val state = currentState
        if (state.serverUrl.isBlank() || state.username.isBlank() || state.password.isBlank()) {
            setState { copy(error = "All fields are required") }
            return
        }

        setState { copy(isLoading = true, error = null) }

        launch {
            try {
                connection.connect(
                    state.serverType,
                    state.serverUrl,
                    state.username,
                    state.password,
                    state.allowUntrustedCertificate,
                )
                setState { copy(isLoading = false) }
                emitEffect(LoginEffect.NavigateToLibrary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { copy(isLoading = false, error = e.message ?: "Connection error") }
            }
        }
    }
}

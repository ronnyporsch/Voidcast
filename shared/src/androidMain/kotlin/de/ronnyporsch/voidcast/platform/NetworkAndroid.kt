package de.ronnyporsch.voidcast.platform

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Follows the default network, which is what requests go over
actual val isOnWifi: StateFlow<Boolean> by lazy {
    val connectivity = AppContextHolder.context.getSystemService(ConnectivityManager::class.java)
    val state = MutableStateFlow(connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.isWifi() == true)
    connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            state.value = capabilities.isWifi()
        }

        override fun onLost(network: Network) {
            state.value = false
        }
    })
    state.asStateFlow()
}

private fun NetworkCapabilities.isWifi() =
    hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

package de.ronnyporsch.voidcast.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual val isOnWifi: StateFlow<Boolean> = MutableStateFlow(true)

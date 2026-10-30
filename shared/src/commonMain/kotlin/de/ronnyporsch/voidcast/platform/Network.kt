package de.ronnyporsch.voidcast.platform

import kotlinx.coroutines.flow.StateFlow

/** Whether the device is connected over wifi (or ethernet) rather than mobile data. Platforms that can't tell say true. */
expect val isOnWifi: StateFlow<Boolean>

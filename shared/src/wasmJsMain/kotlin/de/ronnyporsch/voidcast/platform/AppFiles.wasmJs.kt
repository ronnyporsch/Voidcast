package de.ronnyporsch.voidcast.platform

import kotlinx.browser.localStorage

private fun key(name: String) = "voidcast_file_$name"

actual suspend fun readAppFile(name: String): String? = localStorage.getItem(key(name))

actual suspend fun writeAppFile(name: String, content: String?) {
    // localStorage throws once its quota (usually 5 MB) is used up
    try {
        if (content == null) localStorage.removeItem(key(name)) else localStorage.setItem(key(name), content)
    } catch (_: Throwable) {
    }
}

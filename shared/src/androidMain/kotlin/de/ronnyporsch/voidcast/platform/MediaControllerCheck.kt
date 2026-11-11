package de.ronnyporsch.voidcast.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.media3.session.MediaSession
import java.security.MessageDigest

/**
 * Apps that browse the library and control playback without being trusted by the system (see [mayControl]): Android
 * Auto, Google Maps (media controls while navigating), the Google app (Assistant), Wear OS and Android Automotive's
 * assistant. They also need a `<queries>` entry in the manifest, without which Android hides them from the app.
 */
private val knownControllers = setOf(
    "com.google.android.projection.gearhead",
    "com.google.android.apps.maps",
    "com.google.android.googlequicksearchbox",
    "com.google.android.wearable.app",
    "com.google.android.carassistant",
)

/** SHA-256 of the certificates Google signs the Google app and Maps, and Android Auto with. */
private val googleCertificates = setOf(
    "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83",
    "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf",
)

/**
 * Whether [controller] may connect to the media session, i.e. see the library and control playback. The service has
 * to be exported for Android Auto and Google Maps to find it, which lets every installed app try. Allowed are the
 * app itself, what the system trusts with media control (the system UI, Bluetooth, notification listeners the user
 * enabled) and the [knownControllers].
 */
internal fun mayControl(context: Context, controller: MediaSession.ControllerInfo): Boolean {
    val packageName = controller.packageName
    return controller.uid == Process.myUid() ||
        controller.isTrusted ||
        // Before Android 9 the system doesn't tell who connects through the platform's media session
        packageName == MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME ||
        (packageName in knownControllers && isGenuine(context.packageManager, packageName, controller.uid))
}

/** An app could be installed under the name of a known controller the device doesn't have. */
private fun isGenuine(packages: PackageManager, packageName: String, uid: Int): Boolean = try {
    val app = packages.getApplicationInfo(packageName, 0)
    val preinstalled = app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    app.uid == uid && (preinstalled || certificatesOf(packages, packageName).any { it in googleCertificates })
} catch (_: PackageManager.NameNotFoundException) {
    false
}

@Suppress("DEPRECATION")
private fun certificatesOf(packages: PackageManager, packageName: String): List<String> {
    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val signing = packages.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
        // With several signers all of them would have to be known; none of the known controllers has that
        if (signing == null || signing.hasMultipleSigners()) emptyArray() else signing.signingCertificateHistory
    } else {
        packages.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
    }
    return signatures.orEmpty().map { signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

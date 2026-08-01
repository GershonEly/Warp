package dev.ely.warp.build

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handing an APK to Android, in one place.
 *
 * There were two copies of this before it existed — one in the Build screen, one
 * in the `install` tool — and a third was about to be written for the shelf.
 * Three copies of a FileProvider authority and a set of intent flags is three
 * chances to get it subtly wrong, and the one nobody exercises is the one that
 * breaks.
 */
object Installer {

    /**
     * Open Android's installer for [apk].
     *
     * @return null on success, or why it could not even be started. Success here
     *   means *the installer opened*, not that anything was installed — that is
     *   a screen somebody has to agree to, and no app can do it for them.
     */
    fun open(context: Context, apk: File): String? {
        if (!apk.isFile) return "that APK is not there any more"

        // A FileProvider, because Warp targets API 28 and since API 24 handing a
        // file:// URI to another app throws FileUriExposedException.
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        }.getOrElse { return it.message ?: "could not share the file" }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            // Started from a context that may not be an Activity, so it needs
            // its own task, and the installer needs read access to the URI.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return runCatching { context.startActivity(intent); null }
            .getOrElse { it.message ?: "could not open the installer" }
    }

    /** Is this app on the phone right now? */
    fun isInstalled(context: Context, applicationId: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(applicationId, 0)
            true
        }.getOrDefault(false)

    /** Its installed version, or null. */
    fun installedVersion(context: Context, applicationId: String): String? =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(applicationId, 0).versionName
        }.getOrNull()

    /** Open the built app, or say why not. */
    fun launch(context: Context, applicationId: String): String? {
        val intent = context.packageManager.getLaunchIntentForPackage(applicationId)
            ?: return "not installed"
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null
        }.getOrElse { it.message ?: "could not open it" }
    }

    /** Kept so a caller does not need to know PackageManager exists. */
    @Suppress("unused")
    private val unusedHint = PackageManager::class
}

package com.os4.musiccover.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Getting the downloaded release installed.
 *
 * The APK is fetched into this app's cache and then handed to an installer app exactly the way
 * any other app does it: an implicit `ACTION_VIEW` with the APK mime type and a `content://` URI,
 * resolved by the system against the device's own "default app for APKs". Nothing here names an
 * installer - whatever the user has chosen is what runs, and its own root or Shizuku setup stays
 * its business.
 *
 * This is a deliberate step back from committing a `PackageInstaller` session ourselves. That
 * API talks to `PackageInstallerService` directly: no intent is resolved, no chooser appears, and
 * whatever the device installs APKs with is not consulted at all. Built from an unprivileged app
 * it can only ever produce the system's own "update this app?" screen, and on the MIUI this was
 * built against it produced nothing whatsoever - the system installer read the APK, then dropped
 * the session (`set session <id> to reject due to released`) and no package was ever replaced.
 * Delegating also means this app needs no `REQUEST_INSTALL_PACKAGES` of its own.
 */

sealed interface InstallOutcome {
    /** An installer app took the APK; whether it lands is that app's to report. */
    data object HandedOff : InstallOutcome

    /** Nothing on the device would open an APK. */
    data object NoInstaller : InstallOutcome

    /**
     * The download came back signed by something that is not this project.
     *
     * Its own outcome rather than a [Failed] with a message: that one carries whatever an
     * exception said, and this is a decision - there is no exception, and the sentence the user
     * needs is a different sentence.
     */
    data object NotOurs : InstallOutcome

    data class Failed(val message: String) : InstallOutcome
}

object UpdateInstaller {

    private const val TAG = "HyperMusicCover.Update"

    private const val MIME_APK = "application/vnd.android.package-archive"

    /** Cleared and rewritten on each attempt, so at most one APK is ever held. */
    private const val DOWNLOAD_DIR = "updates"

    /**
     * Process-scoped, deliberately not the composition's.
     *
     * This app sets no `android:configChanges`, so rotating the device destroys the composition
     * and would cancel a `rememberCoroutineScope` download halfway through, leaving a truncated
     * APK behind for the installer to reject.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var inFlight: Boolean = false
        private set

    @Volatile
    var lastOutcome: InstallOutcome? = null
        private set

    fun start(context: Context, apkUrls: List<String>, fileName: String) {
        if (inFlight) return
        inFlight = true
        lastOutcome = null
        val app = context.applicationContext
        scope.launch {
            val outcome = try {
                val file = download(app, apkUrls, fileName)
                if (!UpdateApi.isOurs(app, file)) {
                    file.delete()
                    InstallOutcome.NotOurs
                } else if (handOff(app, file)) {
                    InstallOutcome.HandedOff
                } else {
                    InstallOutcome.NoInstaller
                }
            } catch (t: Throwable) {
                Log.w(TAG, "self-update failed", t)
                InstallOutcome.Failed(t.message ?: t::class.java.simpleName)
            }
            Log.i(TAG, "self-update outcome: $outcome")
            lastOutcome = outcome
            inFlight = false
        }
    }

    /**
     * Fetches the APK, trying [apkUrls] in order.
     *
     * The order is not a preference: the release lives on `github.com`, which is not reachable on
     * every network this app runs on - measured on the project's own test device, a direct
     * connection to `github.com:443` aborts after the 15 second connect timeout while a proxy in
     * front of the same URL returns all 2.9MB in under two seconds.
     */
    private fun download(context: Context, apkUrls: List<String>, fileName: String): File {
        val dir = File(context.cacheDir, DOWNLOAD_DIR)
        dir.listFiles()?.forEach { it.delete() }
        dir.mkdirs()
        val target = File(dir, fileName)

        var lastFailure: Throwable? = null
        for (url in apkUrls) {
            try {
                UpdateApi.openApk(url).use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    response.body.byteStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                UpdateApi.rememberSource(context, apkUrls.first(), url)
                return target
            } catch (t: Throwable) {
                Log.w(TAG, "self-update could not fetch $url", t)
                lastFailure = t
                target.delete()
            }
        }
        throw lastFailure ?: IOException("no download source could be reached")
    }

    /**
     * Opens the APK in whatever installs APKs on this device.
     *
     * Every part of this is the shape the platform documents. The URI has to come from this app's
     * FileProvider and be `content://` - a `file://` path throws `FileUriExposedException` the
     * moment it leaves the process - and it has to carry `FLAG_GRANT_READ_URI_PERMISSION`, since
     * the receiving app has no access to this app's cache directory of its own accord.
     * `FLAG_ACTIVITY_NEW_TASK` is only there because this runs on the application context.
     *
     * No installer is named, deliberately. Picking one would override the user's own default and
     * break for any build shipped under a different id; the intent is left for the system to
     * resolve, and the probe is only so that a device with no APK handler at all can be told
     * apart from one that took it.
     */
    private fun handOff(context: Context, file: File): Boolean {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, MIME_APK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (context.packageManager.resolveActivity(intent, 0) == null) return false
        context.startActivity(intent)
        return true
    }
}

package com.os4.musiccover.updater

import android.content.Context
import android.content.pm.PackageManager
import com.os4.musiccover.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit


/**
 * Finding out whether GitHub has a newer release than the one we are running.
 *
 * Only stable releases are considered. The project publishes two lines from the same tag-driven
 * workflow - `vMAJOR.MINOR.PATCH` and `nightly-YYYYMMDD` - and a nightly is a pre-release whose
 * versionCode is GitHub's run counter, a number on a completely different scale from the stable
 * line's `major*10000 + minor*100 + patch`. Offering a nightly here would be comparing two
 * unrelated numbers, so both the tag shape and the `prerelease` flag are checked.
 *
 * Nothing in here throws. A check that could not be completed and a check that found nothing are
 * the same answer - no update to offer - which is how [com.os4.musiccover.ModuleBridge] already
 * treats a question the module did not answer.
 */

private const val REPO = "zyl6932/HyperMusicCover"
private const val RELEASES_LATEST = "https://api.github.com/repos/$REPO/releases/latest"

/** The package this app ships as. A fork changes it, and a fork must never self-update. */
private const val OFFICIAL_APPLICATION_ID = "com.github.zyl6932.HyperMusicCover"

/** `v1.2.3`, and only that: three numeric parts, each within the width the versionCode packs. */
private val STABLE_TAG = Regex("^v(\\d{1,3})\\.(\\d{1,2})\\.(\\d{1,2})$")

/** The leading triple of a local version name, nightly `-nightly.<date>.<sha>` suffixes and all. */
private val LEADING_TRIPLE = Regex("^(\\d{1,3})\\.(\\d{1,2})\\.(\\d{1,2})")

/**
 * One release, as much of it as this app uses.
 *
 * Every field has a default because [Json] ignores unknown keys but still requires the ones it
 * knows about, and GitHub's release object carries dozens more than these five.
 */
@Serializable
internal data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val prerelease: Boolean = false,
    val body: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
internal data class GithubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
)

/** A newer stable release, already checked against the running build. */
data class UpdateInfo(
    /** The tag without its `v`, which is what CI stamps as this build's versionName. */
    val versionName: String,
    val versionCode: Int,
    /**
     * The tag's own message, which `release.yml` publishes as the release body. Markdown,
     * and rendered as such - see `ui/component/markdown/MarkdownContent.kt`.
     */
    val notes: String,
    /** The release asset, before any mirror is applied. */
    val apkUrl: String,
    val releaseUrl: String,
)

object UpdateApi {

    private val json = Json {
        // Mandatory, not a nicety: the first key we did not model would otherwise be a
        // SerializationException rather than a field we do not care about.
        ignoreUnknownKeys = true
    }

    /**
     * One client for the whole process, used for the release JSON and the APK body alike.
     *
     * `callTimeout` is the one that matters here, and a plain `readTimeout` would not do: the
     * read timeout is per-read, so a server that drips a byte a second holds the connection open
     * forever. A call timeout caps the whole exchange, which is what a download needs.
     */
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    /**
     * Is this build allowed to update itself at all?
     *
     * The package name keeps a fork from being offered the upstream APK, which it could not
     * install anyway - the signature would not match.
     *
     * It used to ask `!BuildConfig.DEBUG` as well, and that was the wrong question twice over.
     * It is the RUNNING build that was being judged, when what matters is whether the APK that
     * comes back is the project's own; and it left every development build unable to see an
     * update at all, which is exactly the build an update is being tested on. The signature
     * question moved to [isOurs], where the file it is about actually exists.
     */
    fun enabled(context: Context): Boolean =
        context.packageName == OFFICIAL_APPLICATION_ID

    /**
     * The certificate the project publishes releases under.
     *
     * Compared by SHA-256 of the certificate, not by its subject: a subject is free-form text
     * anybody can put in a self-signed certificate, and this is the one value that cannot be
     * forged without the private key. Taken from the published `v0.0.5` APK, which is signed
     * with this and nothing else.
     */
    private const val RELEASE_CERT_SHA256 =
        "f5b62166f821734dd854c94a254223b2c49252e3681ee3d31238d1814ad5e6d5"

    /**
     * Is [file] an APK this project is willing to install?
     *
     * EITHER certificate is enough, and that is the point rather than a leniency. The two cannot
     * both match: a development build is signed with the developer's debug key and a release
     * never is, so demanding an exact match with the running build is precisely what stopped a
     * debug install from ever seeing an update. What is being asked here is only "is this ours",
     * and both of those keys answer yes.
     *
     * Whether the system will then accept the swap across two different keys is the installer's
     * business and not this app's: a stock device refuses it, and a device with the signature
     * check patched out - CorePatch and its kin - goes through. Refusing here would take that
     * decision away from the people who have made it possible.
     */
    fun isOurs(context: Context, file: File): Boolean {
        val signer = signerOf(context, file) ?: return false
        return signer == RELEASE_CERT_SHA256 || signer == ownSigner(context)
    }

    /** SHA-256 of the certificate [file] is signed with, lowercase hex, or null if unreadable. */
    private fun signerOf(context: Context, file: File): String? = try {
        context.packageManager
            .getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
            ?.signingInfo?.apkContentsSigners?.firstOrNull()
            ?.toByteArray()?.let(::sha256Hex)
    } catch (_: Exception) {
        null
    }

    /** The same, for the app that is running. */
    private fun ownSigner(context: Context): String? = try {
        context.packageManager
            .getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
            .signingInfo?.apkContentsSigners?.firstOrNull()
            ?.toByteArray()?.let(::sha256Hex)
    } catch (_: Exception) {
        null
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The newest stable release, or null when there is nothing newer or nothing was answered. */
    suspend fun latest(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_LATEST)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                toUpdateInfo(json.decodeFromString<GithubRelease>(response.body.string()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The APK body.
     *
     * A client of its own, with a much shorter connect timeout than the shared one. The download
     * hands over a list of candidate hosts and takes the first that answers, and the one that does
     * not is a host that never answers at all - measured on the project's test device, the direct
     * GitHub URL spends the full 15 seconds of a healthy connect timeout before failing. Paying
     * that on every update to discover the same thing again is the whole of what made the download
     * feel slow. Six seconds is still far longer than any working connection needs.
     */
    internal fun openApk(url: String) = downloadClient
        .newCall(Request.Builder().url(url).build())
        .execute()

    private val downloadClient by lazy {
        client.newBuilder().connectTimeout(6, TimeUnit.SECONDS).build()
    }

    /**
     * The release worth offering, or null.
     *
     * A release carrying no APK asset counts as no release rather than as an update that cannot
     * be installed: the CI contract guarantees `HyperMusicCover-<tag>.apk`, so its absence means
     * something is wrong upstream, and a "new version available" notice whose button cannot work
     * is worse than silence.
     */
    private fun toUpdateInfo(release: GithubRelease): UpdateInfo? {
        if (release.prerelease) return null
        val match = STABLE_TAG.matchEntire(release.tagName) ?: return null
        val (major, minor, patch) = match.destructured
        val apk = release.assets.firstOrNull { it.name == "HyperMusicCover-${release.tagName}.apk" }
            ?: release.assets.firstOrNull {
                it.name.startsWith("HyperMusicCover-v") && it.name.endsWith(".apk")
            }
            ?: return null
        val info = UpdateInfo(
            versionName = release.tagName.removePrefix("v"),
            versionCode = major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt(),
            notes = release.body.trim(),
            apkUrl = apk.url,
            releaseUrl = release.htmlUrl,
        )
        return if (isNewer(info)) info else null
    }

    /**
     * Is [info] newer than the build we are running?
     *
     * The dotted triple is compared part by part and preferred, because the packed versionCode
     * only orders correctly while minor and patch each stay under 100. `v1.2.345` packs to 10545
     * and would beat the later `v1.3.0` at 10300 - the release workflow accepts any numeric part,
     * so nothing upstream prevents that tag. The packed comparison stays as the fallback for a
     * local version name that does not parse at all.
     */
    private fun isNewer(info: UpdateInfo): Boolean {
        val local = LEADING_TRIPLE.find(BuildConfig.VERSION_NAME)?.value
        val remote = LEADING_TRIPLE.find(info.versionName)?.value
        if (local != null && remote != null) {
            val l = local.split('.').map(String::toInt)
            val r = remote.split('.').map(String::toInt)
            for (i in 0..2) if (r[i] != l[i]) return r[i] > l[i]
            return false
        }
        return info.versionCode > BuildConfig.VERSION_CODE
    }

    /**
     * Where to try fetching the APK, in order.
     *
     * The release page itself is on `github.com`, and that host is not reachable on every network
     * this app runs on - measured on the project's own test device, a direct connection to
     * `github.com:443` aborts after the 15 second connect timeout while a proxy in front of the
     * same URL returns all 2.9MB in under two seconds. So the direct URL is tried first and the
     * proxy is the fallback, with nothing for the user to configure: a download source is not a
     * preference anyone should have to know about to get an update.
     *
     * The proxy is a third party and is the one part of this that could go away without notice.
     * If it does, the direct URL is still first in this list, so nothing regresses for the
     * networks where that works.
     */
    fun apkCandidates(browserUrl: String): List<String> =
        listOf(browserUrl, PROXY_PREFIX + browserUrl)

    /**
     * [apkCandidates], but starting from whichever one worked last time.
     *
     * Which of them is reachable is a property of the network this device is on, and that does not
     * change between one update and the next. Trying the other one first every time would mean
     * paying its connect timeout on every update to relearn what the last one already knew.
     */
    fun orderedCandidates(context: Context, browserUrl: String): List<String> {
        val candidates = apkCandidates(browserUrl)
        val last = UpdateSource.last(context)
        if (last !in candidates.indices) return candidates
        return listOf(candidates[last]) + candidates.filterIndexed { i, _ -> i != last }
    }

    /** Records which candidate [orderedCandidates] returned, so the next call starts there. */
    fun rememberSource(context: Context, browserUrl: String, url: String) {
        UpdateSource.set(context, apkCandidates(browserUrl).indexOf(url))
    }

    /** ghproxy's convention: the whole GitHub URL is appended to the proxy's own. */
    private const val PROXY_PREFIX = "https://gh.sevencdn.com/"
}

/**
 * The download host that answered last time.
 *
 * No UI: this is not a preference anyone chooses, it is a fact about the network that the app
 * works out once and reuses. Kept out of [com.os4.musiccover.AppSettings] for the same reason the
 * old mirror setting was - it describes this device's connection, not the user.
 */
private object UpdateSource {

    private const val PREFS_NAME = "update_source"
    private const val KEY_INDEX = "candidate_index"

    fun last(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_INDEX, -1)

    fun set(context: Context, index: Int) {
        if (index < 0) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_INDEX, index)
            .apply()
    }
}

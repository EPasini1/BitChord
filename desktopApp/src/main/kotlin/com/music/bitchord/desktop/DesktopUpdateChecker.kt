package com.music.bitchord.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Looks at the repo's "latest release" on GitHub once per launch and says whether it is newer
 * than the running build. The installer is not run from here: the dialog opens the asset in the
 * browser, so the user installs it the way they installed the first one.
 *
 * GitHub's "latest" skips pre-releases and drafts, so a `-beta` desktop build is only ever
 * nudged toward a published release.
 */
internal object DesktopUpdateChecker {

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        /** The installer for this platform, or null when the release has none. */
        val downloadUrl: String?,
        val notes: String?,
    )

    private const val REPOSITORY = "EPasini1/BitChord"
    private const val RELEASES_URL = "https://github.com/$REPOSITORY/releases"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"

    val currentVersion: String = System.getProperty("bitchord.version") ?: "1.8-beta1"

    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
    }

    suspend fun check(client: HttpClient = http): UpdateInfo? {
        try {
            val response = client.get(LATEST_RELEASE_URL) {
                header("Accept", "application/vnd.github+json")
            }
            if (response.status == HttpStatusCode.NotFound) {
                DesktopTrackLog.log("updates: no published release in $REPOSITORY")
                return null
            }
            if (!response.status.isSuccess()) {
                DesktopTrackLog.log("updates: GitHub returned HTTP ${response.status.value} for $REPOSITORY")
                return null
            }
            val release = json.decodeFromString<Release>(response.bodyAsText())
            if (!release.url.startsWith("$RELEASES_URL/tag/")) {
                DesktopTrackLog.log("updates: ignored a release outside $REPOSITORY")
                return null
            }
            val latest = release.tag.removePrefix("v")
            if (!isNewer(latest, currentVersion)) return null
            return UpdateInfo(
                version = latest,
                releaseUrl = release.url,
                downloadUrl = installerUrl(release),
                notes = release.body,
            )
        } catch (error: IOException) {
            DesktopTrackLog.log("updates: request failed for $REPOSITORY: ${error.message}")
        } catch (error: UnresolvedAddressException) {
            DesktopTrackLog.log("updates: could not resolve GitHub for $REPOSITORY: ${error.message}")
        } catch (error: SerializationException) {
            DesktopTrackLog.log("updates: invalid release response for $REPOSITORY: ${error.message}")
        }
        return null
    }

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tag: String,
        @SerialName("html_url") val url: String,
        val body: String? = null,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String,
        val state: String,
        @SerialName("browser_download_url") val url: String,
    )

    /**
     * The release names its files `BitChord-<version>-windows-x64-setup.exe` and
     * `BitChord-<version>-linux-x86_64.AppImage` (see .github/workflows/release.yml); match on the
     * platform part so a version change does not matter.
     */
    private fun installerUrl(release: Release): String? {
        val suffixes = when {
            DesktopPlatform.isWindows -> listOf("-windows-x64-setup.exe")
            DesktopPlatform.isLinux -> listOf(".AppImage", "-linux-amd64.deb")
            else -> return null
        }
        for (suffix in suffixes) {
            val hit = release.assets.firstOrNull { asset ->
                asset.name.endsWith(suffix, ignoreCase = true) && asset.state == "uploaded"
            }
            if (hit != null) {
                if (hit.url.startsWith("$RELEASES_URL/download/")) return hit.url
                DesktopTrackLog.log("updates: ignored an installer outside $REPOSITORY")
            }
        }
        return null
    }

    private class Parsed(val parts: List<Int>, val preRelease: Boolean)

    private fun parse(raw: String): Parsed {
        val dash = raw.indexOf('-')
        val base = if (dash >= 0) raw.substring(0, dash) else raw
        return Parsed(base.split('.').map { it.toIntOrNull() ?: 0 }, dash >= 0)
    }

    /** Numeric comparison, with a `-betaN` build counted as older than the plain release it leads up to. */
    internal fun isNewer(latest: String, current: String): Boolean {
        val l = parse(latest)
        val c = parse(current)
        for (i in 0 until maxOf(l.parts.size, c.parts.size)) {
            val a = l.parts.getOrElse(i) { 0 }
            val b = c.parts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return c.preRelease && !l.preRelease
    }
}

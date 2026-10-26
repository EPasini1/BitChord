package com.music.bitchord.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopUpdateCheckerTest {
    @Test
    fun checksOnlyTheForkAndAcceptsNoPublishedRelease() = runBlocking {
        HttpClient(MockEngine { request ->
            assertEquals(
                "https://api.github.com/repos/EPasini1/BitChord/releases/latest",
                request.url.toString(),
            )
            respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound)
        }).use { client ->
            assertNull(DesktopUpdateChecker.check(client))
        }
    }

    @Test
    fun newerForkReleaseOffersItsOwnInstallerAndNotes() = runBlocking {
        client(release()).use { client ->
            val update = assertNotNull(DesktopUpdateChecker.check(client))
            assertEquals("999.0.0", update.version)
            assertEquals("https://github.com/EPasini1/BitChord/releases/tag/v999.0.0", update.releaseUrl)
            assertEquals("Fork release notes", update.notes)
            val asset = when {
                DesktopPlatform.isWindows -> "BitChord-windows-x64-setup.exe"
                DesktopPlatform.isLinux -> "BitChord-linux-x86_64.AppImage"
                else -> null
            }
            assertEquals(
                asset?.let { "https://github.com/EPasini1/BitChord/releases/download/v999.0.0/$it" },
                update.downloadUrl,
            )
        }
    }

    @Test
    fun refusesAnUpstreamReleaseEvenIfItsVersionIsNewer() = runBlocking {
        client(release(repository = "kushagrasinghx/BitChord")).use { client ->
            assertNull(DesktopUpdateChecker.check(client))
        }
    }

    @Test
    fun upstreamInstallerFallsBackOnlyToTheForkReleasePage() = runBlocking {
        client(release(assetRepository = "kushagrasinghx/BitChord")).use { client ->
            val update = assertNotNull(DesktopUpdateChecker.check(client))
            assertNull(update.downloadUrl)
            assertEquals("https://github.com/EPasini1/BitChord/releases/tag/v999.0.0", update.releaseUrl)
        }
    }

    @Test
    fun currentVersionDoesNotTriggerAnUpdate() = runBlocking {
        client(release(tag = "v${DesktopUpdateChecker.currentVersion}")).use { client ->
            assertNull(DesktopUpdateChecker.check(client))
        }
    }

    @Test
    fun failedRequestDoesNotFallBackToUpstream() = runBlocking {
        client("""{"message":"API rate limit exceeded"}""", HttpStatusCode.Forbidden).use { client ->
            assertNull(DesktopUpdateChecker.check(client))
        }
    }

    @Test
    fun malformedResponseDoesNotCrashTheApplication() = runBlocking {
        client("""{"unexpected":true}""").use { client ->
            assertNull(DesktopUpdateChecker.check(client))
        }
    }

    @Test
    fun networkFailureDoesNotCrashTheApplication() = runBlocking {
        HttpClient(MockEngine { throw IOException("Network unavailable") }).use { client ->
            assertNull(DesktopUpdateChecker.check(client))
        }
    }

    @Test
    fun requestTimeoutDoesNotCrashTheApplication() = runBlocking {
        HttpClient(MockEngine {
            delay(1_000)
            respond(release())
        }) {
            install(HttpTimeout) { requestTimeoutMillis = 20 }
        }.use { client ->
            assertNull(DesktopUpdateChecker.check(client))
        }
    }

    @Test
    fun cancellationIsNotSwallowed() = runBlocking<Unit> {
        HttpClient(MockEngine { throw CancellationException("Application closed") }).use { client ->
            assertFailsWith<CancellationException> {
                DesktopUpdateChecker.check(client)
            }
        }
    }

    @Test
    fun numericAndPrereleaseVersionOrderingIsPreserved() {
        assertTrue(DesktopUpdateChecker.isNewer("1.8.1", "1.8-beta1"))
        assertTrue(DesktopUpdateChecker.isNewer("1.10.0", "1.9.9"))
        assertTrue(DesktopUpdateChecker.isNewer("1.8", "1.8-beta1"))
        assertFalse(DesktopUpdateChecker.isNewer("1.8.1", "1.8.1"))
        assertFalse(DesktopUpdateChecker.isNewer("1.8.0", "1.8.1"))
        assertFalse(DesktopUpdateChecker.isNewer("1.8-beta1", "1.8"))
    }

    private fun client(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        HttpClient(MockEngine {
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })

    private fun release(
        repository: String = "EPasini1/BitChord",
        assetRepository: String = repository,
        tag: String = "v999.0.0",
    ): String = buildJsonObject {
        put("tag_name", tag)
        put("html_url", "https://github.com/$repository/releases/tag/$tag")
        put("body", "Fork release notes")
        put("assets", buildJsonArray {
            listOf("BitChord-windows-x64-setup.exe", "BitChord-linux-x86_64.AppImage").forEach { name ->
                add(buildJsonObject {
                    put("name", name)
                    put("state", "uploaded")
                    put("browser_download_url", "https://github.com/$assetRepository/releases/download/$tag/$name")
                })
            }
        })
    }.toString()
}

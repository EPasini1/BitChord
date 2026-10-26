package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.use
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.AppUi
import com.music.bitchord.ui.LocalPullToRefreshEnabled
import com.music.bitchord.ui.components.PlayingAccent
import com.music.bitchord.ui.screens.HomeScreen
import com.music.bitchord.sharedui.resources.Res as SharedRes
import com.music.bitchord.sharedui.resources.play
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
class DesktopShellTest {
    private val song = Song("preview-track", "A new sound for BitChord", "Your music, your way", null)

    @Test
    fun desktopPalettePreservesBitChordColorsAndReadableContrast() {
        fun contrast(foreground: Color, background: Color): Float {
            val light = maxOf(foreground.luminance(), background.luminance())
            val dark = minOf(foreground.luminance(), background.luminance())
            return (light + 0.05f) / (dark + 0.05f)
        }
        val scheme = desktopColorScheme()
        assertEquals(Color(0xFFFB4A62), scheme.primary)
        assertEquals(PlayingAccent, scheme.primary)
        assertEquals(scheme.primary, scheme.secondary)
        assertEquals(scheme.primary, scheme.tertiary)
        assertEquals(Color(0xFF202020), scheme.background)
        assertEquals(Color(0xFF0D0D0F), scheme.surface)
        assertEquals(Color(0xFF1C1C1E), scheme.surfaceVariant)
        assertEquals(Color(0xFF8E8E93), scheme.onSurfaceVariant)
        assertEquals(Color(0xFF2C2C2E), scheme.outlineVariant)
        assertEquals(Color(0x661C1C1E), DesktopGlass)
        assertEquals(Color(0xCC0D0D0F), DesktopGlassStrong)
        assertEquals(Color(0xFA1A1A1D), DesktopCardFill)
        assertEquals(Color(0xFFFB4A62), PlayingAccent)
        assertTrue(contrast(scheme.onBackground, scheme.background) >= 4.5f)
        assertTrue(contrast(scheme.onSurfaceVariant, scheme.background) >= 4.5f)
        assertTrue(contrast(scheme.onPrimary, scheme.primary) >= 4.5f)
        assertTrue(contrast(scheme.primary, scheme.background) >= 4.5f)
    }

    @Test
    fun updateDialogKeepsItsActionsAndFormattedNotes() = onUiThread {
        var dismissed = false
        var downloaded = false
        withScene(900, 600, {
            DesktopUpdateDialog(
                update = DesktopUpdateChecker.UpdateInfo(
                    version = "1.8.1",
                    releaseUrl = "https://github.com/EPasini1/BitChord/releases/tag/v1.8.1",
                    downloadUrl = null,
                    notes = "## Fork release notes",
                ),
                onDismiss = { dismissed = true },
                onDownload = { downloaded = true },
            )
        }) { scene ->
            assertTrue(scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                    text.text.contains("\n\nFork release notes") && !text.text.contains("##")
                } == true
            })
            scene.savePreview("desktop-update-dialog")
            scene.clickAt(scene.textNode(DesktopStrings["d_download", "Download"]).boundsInRoot.center)
            assertTrue(downloaded)
            scene.clickAt(scene.textNode(DesktopStrings["d_later", "Later"]).boundsInRoot.center)
            assertTrue(dismissed)
        }
    }

    @Test
    fun playbackButtonsKeepTheirActions() = onUiThread {
        val calls = mutableListOf<String>()
        withScene(1220, 96, { Player(onAction = calls::add) }) { scene ->
            listOf(
                DesktopStrings["play", "Play"] to "play",
                DesktopStrings["widget_previous", "Previous"] to "previous",
                DesktopStrings["widget_next", "Next"] to "next",
                DesktopStrings["like", "Like"] to "like",
                DesktopStrings["shuffle", "Shuffle"] to "shuffle:true",
                "Repeat Off" to "repeat:ALL",
                DesktopStrings["lyrics", "Lyrics"] to "lyrics",
                DesktopStrings["queue", "Queue"] to "queue",
                DesktopStrings["audio_output", "Audio output"] to "output",
            ).forEach { (description, expected) ->
                scene.clickDescription(description)
                assertEquals(expected, calls.last())
            }
            scene.clickAt(scene.textNode(song.title).boundsInRoot.center)
            assertEquals("expand", calls.last())
        }
    }

    @Test
    fun scrubberSeeksUsingTheTrackDuration() = onUiThread {
        var sought = -1L
        withScene(1220, 96, { Player(onSeek = { sought = it }) }) { scene ->
            val slider = scene.nodes().single {
                it.config.getOrNull(SemanticsActions.SetProgress) != null &&
                    it.config.getOrNull(SemanticsProperties.ContentDescription)
                        ?.contains(DesktopStrings["playback_channel_name", "Now playing"]) == true
            }
            scene.clickAt(slider.boundsInRoot.center)
            assertTrue(sought in 90_000L..110_000L, "Expected a seek near 50% of 200 seconds, got $sought")
        }
    }

    @Test
    fun muteRestoresThePreviousVolume() = onUiThread {
        val volume = mutableFloatStateOf(0.65f)
        withScene(1220, 96, { Player(volume = volume.floatValue, onVolume = { volume.floatValue = it }) }) { scene ->
            scene.clickDescription(DesktopStrings["d_volume", "Volume"])
            assertEquals(0f, volume.floatValue)
            scene.clickDescription(DesktopStrings["d_volume", "Volume"])
            assertEquals(0.65f, volume.floatValue)
        }
    }

    @Test
    fun noTrackDisablesPlaybackButKeepsAudioOutputAvailable() = onUiThread {
        withScene(900, 96, { Player(track = null, compact = true) }) { scene ->
            listOf(
                DesktopStrings["play", "Play"],
                DesktopStrings["widget_next", "Next"],
                DesktopStrings["like", "Like"],
            ).forEach { description ->
                assertTrue(scene.button(description).config.contains(SemanticsProperties.Disabled))
            }
            assertFalse(scene.button(DesktopStrings["audio_output", "Audio output"]).config.contains(SemanticsProperties.Disabled))
        }
    }

    @Test
    fun hoveringATrackRevealsItsPlayAction() = onUiThread {
        var opened: String? = null
        var trackPlayLabel = ""
        withScene(1220, 780, {
            trackPlayLabel = stringResource(SharedRes.string.play)
            Preview(onItemClick = { item, _ -> opened = item.title })
        }) { scene ->
            val playLabels = setOf(DesktopStrings["play", "Play"], trackPlayLabel)
            val card = scene.textNode("Daily mix 1")
            scene.sendPointerEvent(PointerEventType.Move, card.boundsInRoot.center)
            repeat(3) { scene.render(System.nanoTime()).close() }
            val playButtons = scene.nodes().filter {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any(playLabels::contains) == true &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null
            }
            assertEquals(2, playButtons.size, "The hovered track and bottom player each offer Play")
            scene.clickAt(playButtons.first().boundsInRoot.center)
            assertEquals("Daily mix 1", opened)
            scene.savePreview("desktop-hover")
        }
    }

    @Test
    fun headerSearchAndNavigationStayInteractive() = onUiThread {
        var query = ""
        var back = false
        var home = false
        var accounts = false
        withScene(900, 64, {
            DesktopHeader(
                compact = true,
                query = query,
                onQueryChange = { query = it },
                onSearch = {},
                onHome = { home = true },
                focusSearch = true,
                onSearchFocused = {},
                accountAvatar = null,
                onOpenAccounts = { accounts = true },
                canGoBack = true,
                onBack = { back = true },
            )
        }) { scene ->
            val field = scene.nodes().single { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("My music")))
            assertEquals("My music", query)
            scene.clickDescription(DesktopStrings["back", "Back"])
            scene.clickDescription(DesktopStrings["home", "Home"])
            assertTrue(back)
            assertTrue(home)
            val account = scene.nodes().first {
                it.config.getOrNull(SemanticsActions.OnClick) != null && it.boundsInRoot.left > 800f
            }
            scene.clickAt(account.boundsInRoot.center)
            assertTrue(accounts)
        }
    }

    @Test
    fun wideLayoutKeepsThePlayerBelowThePage() = onUiThread {
        withScene(1220, 780, { Preview() }) { scene ->
            assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Settings" } == true })
            assertTrue(scene.textNode(song.title).boundsInRoot.top >= 684f)
            val title = scene.nodes().filter {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Home" } == true
            }.maxBy { it.boundsInRoot.left }
            assertTrue(title.boundsInRoot.left >= 256f)
            assertTrue(title.boundsInRoot.top >= 64f)
            scene.savePreview("desktop-wide")
        }
    }

    @Test
    fun compactLayoutKeepsSearchAndPlayerWithoutSidebar() = onUiThread {
        withScene(900, 600, { Preview() }) { scene ->
            assertFalse(scene.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Settings" } == true })
            assertTrue(scene.textNode(song.title).boundsInRoot.top >= 504f)
            val field = scene.nodes().single { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(field.boundsInRoot.width > 200f)
            listOf(
                DesktopStrings["play", "Play"],
                DesktopStrings["widget_next", "Next"],
                DesktopStrings["lyrics", "Lyrics"],
                DesktopStrings["queue", "Queue"],
                DesktopStrings["audio_output", "Audio output"],
            ).forEach { description ->
                val bounds = scene.button(description).boundsInRoot
                assertTrue(bounds.left >= 0f && bounds.right <= 900f, "$description must stay inside the compact window")
                assertTrue(bounds.width >= 28f, "$description must retain a usable hit target")
            }
            scene.savePreview("desktop-compact")
        }
    }

    @Composable
    private fun Player(
        track: Song? = song,
        compact: Boolean = false,
        volume: Float = 0.65f,
        onVolume: (Float) -> Unit = {},
        onSeek: (Long) -> Unit = {},
        onAction: (String) -> Unit = {},
    ) {
        DesktopPlaybackBar(
            compact = compact,
            song = track,
            isPlaying = false,
            isLoading = false,
            previousEnabled = true,
            positionMs = 60_000L,
            durationMs = if (track == null) 0L else 200_000L,
            volume = volume,
            shuffle = false,
            repeatMode = DesktopRepeatMode.OFF,
            liked = false,
            onToggleLike = { onAction("like") },
            onSeek = onSeek,
            onPlayPause = { onAction("play") },
            onPrevious = { onAction("previous") },
            onNext = { onAction("next") },
            onShuffleChange = { onAction("shuffle:$it") },
            onRepeatModeChange = { onAction("repeat:$it") },
            onOpenNowPlaying = { onAction("expand") },
            onVolumeChange = onVolume,
            onOpenAudioOutput = { onAction("output") },
            onOpenLyrics = { onAction("lyrics") },
            onOpenQueue = { onAction("queue") },
            sidePanel = null,
        )
    }

    @Composable
    private fun Preview(onItemClick: (ShelfItem, String) -> Unit = { _, _ -> }) {
        DesktopFrame(
            containerColor = DesktopBackground,
            backdrop = { androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(DesktopBackground)) },
            topBar = { compact ->
                DesktopHeader(compact, "", {}, {}, {}, false, {}, null, {}, false, {})
            },
            sidebar = {
                DesktopSidebar(
                    DesktopDestination.LISTEN_NOW, false, emptyList(), emptyList(), null,
                    {}, {}, {}, {},
                )
            },
            bottomBar = { compact ->
                Column {
                    if (compact) DesktopFloatingNavigation(DesktopDestination.LISTEN_NOW) {}
                    Player(compact = compact)
                }
            },
        ) { padding ->
            HomeScreen(
                state = UiState.Success(
                    listOf(
                        HomeShelf("Made for you", items = (1..6).map {
                            ShelfItem("Daily mix $it", "A soundtrack for your day", null, "track-$it", null)
                        }),
                        HomeShelf("Recently played", items = (1..6).map {
                            ShelfItem("Favorite tracks $it", "Rediscover your music", null, "recent-$it", null)
                        }),
                    ),
                ),
                listState = rememberLazyListState(),
                onItemClick = onItemClick,
                onRetry = {},
                refreshing = false,
                onRefresh = {},
                pullState = rememberPullToRefreshState(),
                contentPadding = padding,
                title = "Home",
                leadHero = false,
            )
        }
    }

    private fun withScene(
        width: Int,
        height: Int,
        content: @Composable () -> Unit,
        check: (ImageComposeScene) -> Unit,
    ) {
        AppUi.install(DesktopAppUiHost)
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        try {
            ImageComposeScene(width, height) {
                CompositionLocalProvider(
                    LocalLifecycleOwner provides owner,
                    LocalPullToRefreshEnabled provides false,
                    LocalContentColor provides Color.White,
                ) {
                    MaterialTheme(colorScheme = desktopColorScheme(), typography = desktopTypography(), content = content)
                }
            }.use { scene ->
                repeat(3) { scene.render(System.nanoTime()).close() }
                check(scene)
            }
        } finally {
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::descendants)
        return semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
    }

    private fun ImageComposeScene.button(description: String): SemanticsNode = nodes().first {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true &&
            it.config.getOrNull(SemanticsActions.OnClick) != null
    }

    private fun ImageComposeScene.textNode(text: String): SemanticsNode = nodes().first {
        it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
    }

    private fun ImageComposeScene.clickDescription(description: String) = clickAt(button(description).boundsInRoot.center)

    private fun ImageComposeScene.clickAt(point: Offset) {
        sendPointerEvent(PointerEventType.Press, point)
        sendPointerEvent(PointerEventType.Release, point)
        repeat(3) { render(System.nanoTime()).close() }
    }

    private fun ImageComposeScene.savePreview(name: String) {
        val output = File("build/reports/desktop-ui/$name.png")
        output.parentFile.mkdirs()
        render(System.nanoTime()).use { image ->
            checkNotNull(image.encodeToData()).use { output.writeBytes(it.bytes) }
        }
    }

    private fun <T> onUiThread(block: () -> T): T {
        val task = FutureTask(Callable(block))
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
}

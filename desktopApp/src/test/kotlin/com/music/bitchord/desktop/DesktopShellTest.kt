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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import androidx.compose.ui.window.WindowPlacement
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
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
class DesktopShellTest {
    private val song = Song("preview-track", "A new sound for BitChord", "Your music, your way", null)
    private var frameTimeNanos = 0L

    @Test
    fun captionControlsFollowPlatformOrderInBothTitleBarModes() = onUiThread {
        val minimize = DesktopStrings["d_minimize_window", "Minimize window"]
        val maximize = DesktopStrings["d_maximize_window", "Maximize window"]
        val close = DesktopStrings["d_close_window", "Close window"]
        val originalPlacement = DesktopWindowMode.placement.value
        DesktopWindowMode.adopt(WindowPlacement.Floating)
        try {
            for (widthDp in listOf(900, 1220, 1920, 2560)) {
                for (density in listOf(1f, 1.25f, 1.5f)) {
                    for (titleBar in listOf(false, true)) {
                        val calls = mutableListOf<String>()
                        val width = (widthDp * density).roundToInt()
                        withScene(width, (100 * density).roundToInt(), {
                            CompositionLocalProvider(
                                LocalDesktopWindowActions provides DesktopWindowActions(
                                    minimize = { calls += "minimize" },
                                    toggleMaximize = { calls += "maximize" },
                                    close = { calls += "close" },
                                ),
                            ) {
                                Column {
                                    DesktopTitleBar(enabled = titleBar)
                                    DesktopHeader(
                                        widthDp < 980, "", {}, {}, {}, false, {},
                                        null, { calls += "accounts" }, true, {},
                                        titleBarEnabled = titleBar,
                                    )
                                }
                            }
                        }, density = density) { scene ->
                            val captionNodes = scene.nodes().filter {
                                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { label ->
                                    label in listOf(minimize, maximize, close)
                                } == true && it.config.getOrNull(SemanticsActions.OnClick) != null
                            }
                            assertEquals(if (DesktopPlatform.drawsOwnWindowFrame) 3 else 0, captionNodes.size)
                            val field = scene.nodes().single { it.config.getOrNull(SemanticsActions.SetText) != null }
                            assertTrue(field.boundsInRoot.width >= 200 * density, "Search must remain usable")
                            if (DesktopPlatform.drawsOwnWindowFrame) {
                                val minBounds = scene.button(minimize).boundsInRoot
                                val maxBounds = scene.button(maximize).boundsInRoot
                                val closeBounds = scene.button(close).boundsInRoot
                                if (DesktopPlatform.isWindows) {
                                    assertTrue(minBounds.right <= maxBounds.left + 1f)
                                    assertTrue(maxBounds.right <= closeBounds.left + 1f)
                                    assertTrue(minBounds.left > width / 2f)
                                    val rightInset = if (titleBar) 0f else 16 * density
                                    assertTrue(abs(closeBounds.right - (width - rightInset)) <= 1f)
                                    captionNodes.forEach {
                                        assertTrue(it.boundsInRoot.width >= 46 * density - 1f)
                                        assertTrue(it.boundsInRoot.height >= 32 * density - 1f)
                                    }
                                    if (titleBar) {
                                        assertTrue(closeBounds.bottom <= field.boundsInRoot.top)
                                    } else {
                                        assertTrue(field.boundsInRoot.right < minBounds.left)
                                        val account = scene.nodes().single {
                                            it.config.getOrNull(SemanticsActions.OnClick) != null &&
                                                it.boundsInRoot.left > field.boundsInRoot.right &&
                                                it.boundsInRoot.right < minBounds.left
                                        }
                                        scene.clickAt(account.boundsInRoot.center)
                                        assertEquals(listOf("accounts"), calls)
                                        calls.clear()
                                    }
                                } else {
                                    assertTrue(closeBounds.right <= minBounds.left + 1f)
                                    assertTrue(minBounds.right <= maxBounds.left + 1f)
                                    assertTrue(closeBounds.left <= 18 * density)
                                }
                                scene.clickDescription(minimize)
                                scene.clickDescription(maximize)
                                scene.clickDescription(close)
                                assertEquals(listOf("minimize", "maximize", "close"), calls)
                            }
                            if (widthDp == 1220 && density == 1f) {
                                scene.savePreview(if (titleBar) "desktop-caption-title-bar" else "desktop-caption-header")
                            }
                        }
                    }
                }
            }
        } finally {
            DesktopWindowMode.adopt(originalPlacement)
        }
    }

    @Test
    fun captionMaximizeSwitchesToRestoreWithoutClosingTheWindow() = onUiThread {
        val originalPlacement = DesktopWindowMode.placement.value
        DesktopWindowMode.adopt(WindowPlacement.Floating)
        var closed = false
        var toggles = 0
        try {
            withScene(300, 64, {
                CompositionLocalProvider(
                    LocalDesktopWindowActions provides DesktopWindowActions(
                        minimize = {},
                        toggleMaximize = {
                            toggles++
                            DesktopWindowMode.adopt(
                                if (DesktopWindowMode.maximized.value) WindowPlacement.Floating
                                else WindowPlacement.Maximized,
                            )
                        },
                        close = { closed = true },
                    ),
                ) {
                    DesktopWindowButtons()
                }
            }) { scene ->
                if (DesktopPlatform.drawsOwnWindowFrame) {
                    scene.clickDescription(DesktopStrings["d_maximize_window", "Maximize window"])
                    assertTrue(DesktopWindowMode.maximized.value)
                    scene.clickDescription(DesktopStrings["d_restore_window", "Restore window"])
                    assertFalse(DesktopWindowMode.maximized.value)
                    assertEquals(2, toggles)
                    assertFalse(closed)
                    scene.clickDescription(DesktopStrings["d_close_window", "Close window"])
                    assertTrue(closed)
                } else {
                    assertFalse(scene.nodes().any { it.config.getOrNull(SemanticsActions.OnClick) != null })
                }
            }
        } finally {
            DesktopWindowMode.adopt(originalPlacement)
        }
    }

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
    fun transportStaysCenteredAtEveryWindowWidthAndScale() = onUiThread {
        val failures = mutableListOf<String>()
        val measurements = mutableListOf("widthDp,widthPx,density,hideVolume,centerPx,expectedPx,driftPx")
        for (density in listOf(1f, 1.25f, 1.5f)) {
            for (widthDp in listOf(900, 980, 1220, 1920, 1936, 2560, 3440)) {
                for (hideVolume in listOf(false, true)) {
                    val width = (widthDp * density).roundToInt()
                    withScene(width, (96 * density).roundToInt(), {
                        Player(compact = widthDp < 980, hideVolume = hideVolume)
                    }, density = density) { scene ->
                        val center = scene.button(DesktopStrings["play", "Play"]).boundsInRoot.center.x
                        val expected = width / 2f
                        val drift = abs(center - expected)
                        measurements += "$widthDp,$width,$density,$hideVolume,$center,$expected,$drift"
                        if (drift > 1f) {
                            failures += "${widthDp}dp @ ${density}x, hidden volume=$hideVolume: drift=${drift}px"
                        }
                    }
                }
            }
        }
        val report = File("build/reports/desktop-ui/transport-alignment.csv")
        report.parentFile.mkdirs()
        report.writeText(measurements.joinToString("\n"))
        assertTrue(failures.isEmpty(), "Transport must remain within 1px of the window center:\n${failures.joinToString("\n")}")
    }

    @Test
    fun maximizedMonitorLayoutsKeepControlsVisibleAndInteractive() = onUiThread {
        for ((width, height, density) in listOf(
            Triple(1920, 1032, 1f),
            Triple(2560, 1392, 1f),
            Triple(2560, 1392, 1.25f),
            Triple(2560, 1392, 1.5f),
        )) {
            val calls = mutableListOf<String>()
            withScene(width, height, { Preview(onPlayerAction = calls::add) }, density = density) { scene ->
                scene.assertCenteredTransport(width, height)
                listOf(
                    DesktopStrings["play", "Play"] to "play",
                    DesktopStrings["widget_previous", "Previous"] to "previous",
                    DesktopStrings["widget_next", "Next"] to "next",
                    DesktopStrings["queue", "Queue"] to "queue",
                    DesktopStrings["lyrics", "Lyrics"] to "lyrics",
                    DesktopStrings["audio_output", "Audio output"] to "output",
                ).forEach { (description, expected) ->
                    val bounds = scene.button(description).boundsInRoot
                    assertTrue(bounds.width >= 28f * density, "$description must retain its hit target at ${density}x")
                    scene.clickDescription(description)
                    assertEquals(expected, calls.last())
                }
                val field = scene.nodes().single { it.config.getOrNull(SemanticsActions.SetText) != null }
                assertTrue(field.boundsInRoot.left >= 0f && field.boundsInRoot.right <= width)
                scene.savePreview("desktop-maximized-${width}x${height}-${(density * 100).roundToInt()}")
            }
        }
    }

    @Test
    fun maximizeRestoreAndCompactTransitionsKeepControlsWorking() = onUiThread {
        val calls = mutableListOf<String>()
        withScene(2560, 1392, { Preview(onPlayerAction = calls::add) }) { scene ->
            for ((width, height) in listOf(
                1220 to 780,
                1920 to 1032,
                2560 to 1392,
                900 to 600,
                1220 to 780,
            )) {
                scene.constraints = Constraints.fixed(width, height)
                scene.renderUntilIdle()
                scene.assertCenteredTransport(width, height)
                scene.clickDescription(DesktopStrings["play", "Play"])
                assertEquals("play", calls.last())
                val settingsVisible = scene.nodes().any {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Settings" } == true
                }
                assertEquals(width >= 980, settingsVisible, "Sidebar visibility must follow the resized width")
                scene.clickDescription(DesktopStrings["queue", "Queue"])
                assertEquals("queue", calls.last())
            }
        }
    }

    @Test
    fun longTrackMetadataDoesNotPushTheTransportOutOfPlace() = onUiThread {
        val longSong = song.copy(title = song.title.repeat(20), artist = song.artist.repeat(20))
        for (width in listOf(900, 1920, 2560)) {
            withScene(width, 96, { Player(track = longSong, compact = width < 980) }) { scene ->
                scene.assertCenteredTransport(width, 96)
                assertTrue(
                    scene.textNode(longSong.title).boundsInRoot.right <=
                        scene.button(DesktopStrings["like", "Like"]).boundsInRoot.left,
                )
            }
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
            repeat(3) { scene.renderFrame().close() }
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
        hideVolume: Boolean = false,
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
            hideVolume = hideVolume,
        )
    }

    @Composable
    private fun Preview(
        onItemClick: (ShelfItem, String) -> Unit = { _, _ -> },
        onPlayerAction: (String) -> Unit = {},
    ) {
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
                    Player(compact = compact, onAction = onPlayerAction)
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
        density: Float = 1f,
        check: (ImageComposeScene) -> Unit,
    ) {
        AppUi.install(DesktopAppUiHost)
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        try {
            ImageComposeScene(width, height, density = Density(density)) {
                CompositionLocalProvider(
                    LocalLifecycleOwner provides owner,
                    LocalPullToRefreshEnabled provides false,
                    LocalContentColor provides Color.White,
                ) {
                    MaterialTheme(colorScheme = desktopColorScheme(), typography = desktopTypography(), content = content)
                }
            }.use { scene ->
                repeat(3) { scene.renderFrame().close() }
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

    private fun ImageComposeScene.assertCenteredTransport(width: Int, height: Int) {
        val play = button(DesktopStrings["play", "Play"]).boundsInRoot
        assertTrue(abs(play.center.x - width / 2f) <= 1f, "Transport center ${play.center.x} must match ${width / 2f}")
        listOf(
            DesktopStrings["play", "Play"],
            DesktopStrings["widget_previous", "Previous"],
            DesktopStrings["widget_next", "Next"],
            DesktopStrings["like", "Like"],
            DesktopStrings["lyrics", "Lyrics"],
            DesktopStrings["queue", "Queue"],
            DesktopStrings["audio_output", "Audio output"],
            DesktopStrings["d_volume", "Volume"],
        ).forEach { description ->
            val bounds = button(description).boundsInRoot
            assertTrue(
                bounds.left >= 0f && bounds.top >= 0f && bounds.right <= width && bounds.bottom <= height,
                "$description must stay inside ${width}x$height, got $bounds",
            )
        }
    }

    private fun ImageComposeScene.clickAt(point: Offset) {
        sendPointerEvent(PointerEventType.Press, point)
        sendPointerEvent(PointerEventType.Release, point)
        repeat(3) { renderFrame().close() }
    }

    private fun ImageComposeScene.renderFrame(): Image {
        frameTimeNanos += 16_666_667L
        return render(frameTimeNanos)
    }

    private fun ImageComposeScene.renderUntilIdle() {
        repeat(120) {
            renderFrame().close()
            if (!hasInvalidations()) return
        }
        error("Compose did not settle after resizing within 120 frames")
    }

    private fun ImageComposeScene.savePreview(name: String) {
        val output = File("build/reports/desktop-ui/$name.png")
        output.parentFile.mkdirs()
        renderFrame().use { image ->
            checkNotNull(image.encodeToData()).use { output.writeBytes(it.bytes) }
        }
    }

    private fun <T> onUiThread(block: () -> T): T {
        val task = FutureTask(Callable(block))
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
}

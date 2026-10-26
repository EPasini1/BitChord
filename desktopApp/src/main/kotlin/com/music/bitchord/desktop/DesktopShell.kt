package com.music.bitchord.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bitchord.desktopapp.generated.resources.Res
import bitchord.desktopapp.generated.resources.logo
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.icons.BitChordIcons
import kotlinx.coroutines.yield
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun DesktopHeader(
    compact: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onHome: () -> Unit,
    focusSearch: Boolean,
    onSearchFocused: () -> Unit,
    accountAvatar: String?,
    onOpenAccounts: () -> Unit,
    canGoBack: Boolean,
    onBack: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(focusSearch) {
        if (focusSearch) {
            focusRequester.requestFocus()
            onSearchFocused()
        }
    }
    LaunchedEffect(windowInfo.isWindowFocused) {
        if (!windowInfo.isWindowFocused) {
            restoreFocus = focused
        } else if (restoreFocus) {
            yield()
            focusRequester.requestFocus()
            restoreFocus = false
        }
    }
    val titleBarEnabled by DesktopTitleBarSetting.enabled.collectAsState()
    DesktopTitleBarDragArea(Modifier.fillMaxWidth().height(64.dp).desktopWindowGlass()) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (DesktopPlatform.drawsOwnWindowFrame && !titleBarEnabled) {
                DesktopWindowButtons()
            }
            Row(
                Modifier.width(if (compact) 40.dp else 156.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Image(
                    painterResource(Res.drawable.logo),
                    contentDescription = "BitChord",
                    modifier = Modifier.size(32.dp),
                )
                if (!compact) Text("BitChord", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            }
            DesktopToolbarButton(onBack, enabled = canGoBack, size = 36.dp) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    DesktopStrings["back", "Back"],
                    tint = if (canGoBack) Color.White else DesktopSecondary.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp),
                )
            }
            DesktopToolbarButton(onHome, size = 40.dp) {
                Icon(BitChordIcons.Home, DesktopStrings["home", "Home"], modifier = Modifier.size(24.dp))
            }
            DesktopSearchField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                modifier = Modifier.weight(1f),
                focusRequester = focusRequester,
                onFocusChanged = { focused = it },
                placeholder = DesktopStrings["search", "Search"],
            )
            Spacer(Modifier.width(8.dp))
            DesktopAccountButton(accountAvatar, onOpenAccounts)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopPlaybackBar(
    compact: Boolean,
    song: Song?,
    isPlaying: Boolean,
    isLoading: Boolean,
    previousEnabled: Boolean,
    positionMs: Long,
    durationMs: Long,
    volume: Float,
    shuffle: Boolean,
    repeatMode: DesktopRepeatMode,
    liked: Boolean,
    onToggleLike: () -> Unit,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShuffleChange: (Boolean) -> Unit,
    onRepeatModeChange: (DesktopRepeatMode) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onOpenAudioOutput: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    sidePanel: DesktopSidePanel?,
) {
    val hideVolume by DesktopAppearanceSettings.hideVolumeBar.collectAsState()
    var seekFraction by remember(song?.videoId) { mutableStateOf<Float?>(null) }
    var unmutedVolume by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(volume) {
        if (volume > 0f) unmutedVolume = volume
    }
    val progress = seekFraction ?: if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val canSeek = song != null && durationMs > 0L
    Row(
        Modifier.fillMaxWidth().height(96.dp).background(DesktopSurface).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = song != null, onClick = onOpenNowPlaying)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (song != null) {
                    DesktopArtwork(song.thumbnailUrl, Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)), px = ROW_ART_PX)
                } else {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(DesktopBackground),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(BitChordIcons.MusicNote, null, tint = DesktopSecondary, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song?.title ?: DesktopStrings["playback_channel_name", "Now playing"],
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (song != null) {
                        Text(
                            song.artist,
                            color = DesktopSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            PlaybackButton(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                DesktopStrings["like", "Like"],
                onToggleLike,
                enabled = song != null,
                active = liked,
            )
        }
        Column(
            Modifier.weight(1.3f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PlaybackButton(BitChordIcons.Shuffle, DesktopStrings["shuffle", "Shuffle"], { onShuffleChange(!shuffle) }, song != null, shuffle)
                PlaybackButton(Icons.Rounded.SkipPrevious, DesktopStrings["widget_previous", "Previous"], onPrevious, song != null && previousEnabled)
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .background(if (song != null) MaterialTheme.colorScheme.primary else DesktopSecondary.copy(alpha = 0.3f))
                        .clickable(enabled = song != null, onClick = onPlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            DesktopStrings[if (isPlaying) "pause" else "play", if (isPlaying) "Pause" else "Play"],
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                PlaybackButton(Icons.Rounded.SkipNext, DesktopStrings["widget_next", "Next"], onNext, song != null)
                Box {
                    PlaybackButton(
                        BitChordIcons.Repeat,
                        "Repeat ${repeatMode.label()}",
                        { onRepeatModeChange(repeatMode.next()) },
                        song != null,
                        repeatMode != DesktopRepeatMode.OFF,
                    )
                    if (repeatMode == DesktopRepeatMode.ONE) {
                        Text("1", color = DesktopAccent, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.TopEnd))
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    formatTime(seekFraction?.let { (it * durationMs).toLong() } ?: positionMs),
                    color = DesktopSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = progress,
                    onValueChange = { seekFraction = it },
                    onValueChangeFinished = {
                        seekFraction?.let { onSeek((it * durationMs).toLong()) }
                        seekFraction = null
                    },
                    enabled = canSeek,
                    colors = SliderDefaults.colors(
                        activeTrackColor = DesktopAccent,
                        inactiveTrackColor = DesktopDivider,
                        disabledInactiveTrackColor = DesktopDivider,
                    ),
                    thumb = { SliderThumb() },
                    track = { PlaybackTrack(it, enabled = canSeek) },
                    modifier = Modifier.weight(1f).height(22.dp)
                        .semantics { contentDescription = DesktopStrings["playback_channel_name", "Now playing"] },
                )
                Text(formatTime(durationMs), color = DesktopSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(
            Modifier.width(if (hideVolume) 128.dp else if (compact) 196.dp else 236.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaybackButton(BitChordIcons.LyricsQuote, DesktopStrings["lyrics", "Lyrics"], onOpenLyrics, song != null, sidePanel == DesktopSidePanel.LYRICS)
            PlaybackButton(BitChordIcons.Queue, DesktopStrings["queue", "Queue"], onOpenQueue, song != null, sidePanel == DesktopSidePanel.QUEUE)
            PlaybackButton(Icons.Rounded.Headphones, DesktopStrings["audio_output", "Audio output"], onOpenAudioOutput)
            PlaybackButton(
                if (volume == 0f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                DesktopStrings["d_volume", "Volume"],
                { onVolumeChange(if (volume > 0f) 0f else unmutedVolume) },
            )
            if (!hideVolume) {
                Slider(
                    value = volume.coerceIn(0f, 1f),
                    onValueChange = onVolumeChange,
                    colors = SliderDefaults.colors(activeTrackColor = DesktopAccent, inactiveTrackColor = DesktopDivider),
                    thumb = { SliderThumb() },
                    track = { PlaybackTrack(it) },
                    modifier = Modifier.weight(1f).height(22.dp)
                        .semantics { contentDescription = DesktopStrings["d_volume", "Volume"] },
                )
            }
        }
    }
}

@Composable
private fun SliderThumb() {
    Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackTrack(state: SliderState, enabled: Boolean = true) {
    SliderDefaults.Track(
        sliderState = state,
        modifier = Modifier.height(4.dp),
        enabled = enabled,
        colors = SliderDefaults.colors(
            activeTrackColor = DesktopAccent,
            inactiveTrackColor = DesktopDivider,
            disabledInactiveTrackColor = DesktopDivider,
        ),
        drawStopIndicator = null,
        thumbTrackGapSize = 0.dp,
        trackInsideCornerSize = 2.dp,
    )
}

@Composable
private fun PlaybackButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    DesktopToolbarButton(onClick, enabled = enabled, size = 32.dp) {
        Icon(
            icon,
            description,
            tint = when {
                !enabled -> DesktopSecondary.copy(alpha = 0.3f)
                active -> DesktopAccent
                else -> DesktopSecondary
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

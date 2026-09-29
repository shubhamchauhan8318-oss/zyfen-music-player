package com.zyfen.music.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.zyfen.music.data.media.Song
import com.zyfen.music.playback.PlayerManager
import com.zyfen.music.ui.components.Artwork
import com.zyfen.music.ui.components.ScreenHeader
import com.zyfen.music.ui.theme.*
import kotlin.math.roundToLong

@Composable
fun NowPlayingScreen(
    player: PlayerManager,
    vm: LibraryViewModel,
    onOpenQueue: () -> Unit,
    onBack: () -> Unit
) {
    val song by player.currentSong.collectAsState()
    val isPlaying by player.isPlaying.collectAsState()
    val pos by player.position.collectAsState()
    val dur by player.duration.collectAsState()
    val shuffle by player.shuffle.collectAsState()
    val repeat by player.repeat.collectAsState()
    val songs by vm.songs.collectAsState()
    val isFavorite = song != null && songs.any { it.id == song?.id && it.isFavorite }
    val preparing by player.isPreparing.collectAsState()
    val stage by player.resolveStage.collectAsState()

    NowPlayingContent(
        song = song,
        isPlaying = isPlaying,
        pos = pos,
        dur = dur,
        shuffle = shuffle,
        repeat = repeat,
        isFavorite = isFavorite,
        preparing = preparing,
        stage = stage,
        onSeek = { player.seekTo(it) },
        onToggle = { player.togglePlayPause() },
        onNext = { player.next() },
        onPrev = { player.previous() },
        onShuffle = { player.toggleShuffle() },
        onRepeat = { player.cycleRepeat() },
        onFav = { song?.let { vm.toggleFavorite(it) } },
        onRetry = { player.retryCurrentSong() },
        onOpenQueue = onOpenQueue,
        onBack = onBack
    )
}

@Composable
fun NowPlayingContent(
    song: Song?,
    isPlaying: Boolean,
    pos: Long,
    dur: Long,
    shuffle: Boolean,
    repeat: Int,
    onSeek: (Long) -> Unit = {},
    onToggle: () -> Unit = {},
    onNext: () -> Unit = {},
    onPrev: () -> Unit = {},
    onShuffle: () -> Unit = {},
    onRepeat: () -> Unit = {},
    onFav: () -> Unit = {},
    onRetry: () -> Unit = {},
    isFavorite: Boolean = false,
    preparing: Boolean = false,
    stage: String = "",
    onOpenQueue: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val progress = if (dur > 0) pos.toFloat() / dur else 0f
    val playCorner by animateDpAsState(
        targetValue = if (isPlaying) 32.dp else 16.dp,
        label = "playCorner"
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(ZyfenSurface)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.KeyboardArrowDown, "Close", tint = ZyfenText)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onShuffle) {
                Icon(
                    Icons.Filled.Shuffle, "Shuffle",
                    tint = if (shuffle) ZyfenPurple else ZyfenTextDisabled
                )
            }
            IconButton(onClick = onOpenQueue) {
                Icon(Icons.Filled.QueueMusic, "Queue", tint = ZyfenText)
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1.25f)
                .fillMaxWidth()
        ) {
            Artwork(
                song?.artworkUri,
                Modifier.fillMaxWidth().aspectRatio(1f).padding(horizontal = 32.dp, vertical = 8.dp),
                16
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            Text(
                song?.title ?: "Nothing playing",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = ZyfenText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                song?.artist ?: "Select a track to play",
                style = MaterialTheme.typography.bodyLarge,
                color = ZyfenTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(0.4f))

            if (preparing) {
                Text(
                    stage.ifBlank { "Connecting audio stream…" },
                    style = MaterialTheme.typography.bodySmall,
                    color = ZyfenPurple,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = ZyfenPurple, trackColor = Color.Transparent
                )
                Spacer(Modifier.height(6.dp))
            } else if (!isPlaying && song != null && !song.isLocal && pos == 0L) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onRetry) {
                        Icon(Icons.Filled.Refresh, null, tint = ZyfenPurple, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Retry Stream", color = ZyfenPurple, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Slider(
                value = progress.coerceIn(0f, 1f),
                onValueChange = { onSeek((it * dur).roundToLong()) },
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = ZyfenPurple,
                    activeTrackColor = ZyfenPurple,
                    inactiveTrackColor = ZyfenCard
                )
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(fmt(pos), style = MaterialTheme.typography.bodySmall, color = ZyfenTextSecondary)
                Text(fmt(dur), style = MaterialTheme.typography.bodySmall, color = ZyfenTextSecondary)
            }

            Spacer(Modifier.weight(0.6f))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onFav, modifier = Modifier.weight(1f)) {
                    Icon(
                        if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        "Favorite",
                        tint = if (isFavorite) ZyfenNeon else ZyfenText,
                        modifier = Modifier.size(24.dp)
                    )
                }
                IconButton(onClick = onPrev, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.SkipPrevious, "Previous", tint = ZyfenText, modifier = Modifier.size(28.dp))
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(playCorner))
                        .background(ZyfenPurple)
                        .clickable(onClick = onToggle)
                ) {
                    if (preparing) {
                        CircularProgressIndicator(
                            Modifier.size(26.dp), color = Color.White, strokeWidth = 2.5.dp
                        )
                    } else {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                IconButton(onClick = onNext, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.SkipNext, "Next", tint = ZyfenText, modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = onRepeat, modifier = Modifier.weight(1f)) {
                    Icon(
                        if (repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        "Repeat",
                        tint = if (repeat != Player.REPEAT_MODE_OFF) ZyfenPurple else ZyfenTextDisabled,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(Modifier.weight(0.6f))
        }
    }
}

private fun fmt(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

@Composable
fun QueueScreen(player: PlayerManager, vm: LibraryViewModel, onBack: () -> Unit) {
    val q by player.queue.collectAsState()
    val idx by player.currentIndex.collectAsState()
    QueueContent(
        queue = q,
        currentIndex = idx,
        onSelect = { player.playSongs(q, it) },
        onBack = onBack
    )
}

@Composable
fun QueueContent(
    queue: List<Song>,
    currentIndex: Int,
    onSelect: (Int) -> Unit = {},
    onBack: () -> Unit = {}
) {
    Column(Modifier.fillMaxSize().background(ZyfenBg)) {
        ScreenHeader(
            "Current Queue",
            leading = {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = ZyfenText) }
            }
        )
        if (queue.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Queue is empty", color = ZyfenTextSecondary)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(queue.size) { i ->
                    val s = queue[i]
                    ListItem(
                        headlineContent = {
                            Text(
                                s.title,
                                color = if (i == currentIndex) ZyfenPurple else ZyfenText,
                                maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        },
                        supportingContent = { Text(s.artist, color = ZyfenTextSecondary, maxLines = 1) },
                        leadingContent = {
                            Artwork(s.artworkUri, Modifier.size(48.dp), 8)
                        },
                        trailingContent = {
                            if (i == currentIndex) {
                                Icon(Icons.Filled.VolumeUp, null, tint = ZyfenPurple, modifier = Modifier.size(20.dp))
                            }
                        },
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .clickable { onSelect(i) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
        }
    }
}

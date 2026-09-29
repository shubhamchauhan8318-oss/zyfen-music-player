package com.zyfen.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.zyfen.music.data.media.Song
import com.zyfen.music.playback.PlayerManager
import com.zyfen.music.ui.theme.*

@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, corner: Int = 8) {
    if (url.isNullOrBlank()) {
        Box(
            modifier.background(ZyfenCard, RoundedCornerShape(corner.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.MusicNote, null, tint = ZyfenTextDisabled, modifier = Modifier.size(40.dp))
        }
    } else {
        AsyncImage(
            model = url, contentDescription = null,
            modifier = modifier.clip(RoundedCornerShape(corner.dp)),
            contentScale = ContentScale.Crop
        )
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp)
            .padding(horizontal = 16.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center)
        )
        if (leading != null) {
            Box(Modifier.align(Alignment.CenterStart)) { leading() }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.align(Alignment.BottomEnd)
        ) { actions() }
    }
}

@Composable
fun SearchFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(16.dp)
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ZyfenCard)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.Search, "Search", tint = ZyfenText)
    }
}

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(ZyfenCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = ZyfenText, maxLines = 1)
    }
}

@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    onFav: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Artwork(song.artworkUri, Modifier.size(54.dp), 8)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = ZyfenText
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    song.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = ZyfenTextSecondary,
                    modifier = Modifier.weight(1f)
                )
                val dur = formatDuration(song.durationMs)
                if (dur.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        dur,
                        style = MaterialTheme.typography.bodySmall,
                        color = ZyfenTextSecondary
                    )
                }
            }
        }
        IconButton(onClick = onFav, modifier = Modifier.size(36.dp)) {
            Icon(
                if (song.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                "Favorite",
                tint = if (song.isFavorite) ZyfenNeon else ZyfenTextDisabled,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun MiniPlayer(
    song: Song?,
    isPlaying: Boolean,
    progress: Float,
    preparing: Boolean = false,
    stage: String = "",
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit
) {
    if (song == null) return
    Column(
        Modifier
            .fillMaxWidth()
            .background(ZyfenSurface)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .clickable(onClick = onOpen)
    ) {
        if (preparing) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = ZyfenText, trackColor = Color.Transparent
            )
        } else {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = ZyfenText, trackColor = Color.Transparent
            )
        }
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Artwork(song.artworkUri, Modifier.size(46.dp), 8)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = ZyfenText
                )
                Text(
                    if (preparing && stage.isNotBlank()) stage else song.artist,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = ZyfenTextSecondary
                )
            }
            IconButton(onClick = onToggle, modifier = Modifier.size(40.dp)) {
                if (preparing) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp), color = ZyfenText, strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        null, tint = ZyfenText, modifier = Modifier.size(22.dp)
                    )
                }
            }
            IconButton(onClick = onNext, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.SkipNext, null, tint = ZyfenText, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
fun ConnectedMiniPlayer(player: PlayerManager, onOpen: () -> Unit) {
    val song by player.currentSong.collectAsState()
    val isPlaying by player.isPlaying.collectAsState()
    val pos by player.position.collectAsState()
    val dur by player.duration.collectAsState()
    val preparing by player.isPreparing.collectAsState()
    val stage by player.resolveStage.collectAsState()
    MiniPlayer(
        song = song,
        isPlaying = isPlaying,
        progress = if (dur > 0) pos.toFloat() / dur else 0f,
        preparing = preparing,
        stage = stage,
        onToggle = { player.togglePlayPause() },
        onNext = { player.next() },
        onOpen = onOpen
    )
}

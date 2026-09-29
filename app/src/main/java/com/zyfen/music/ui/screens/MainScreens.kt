package com.zyfen.music.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyfen.music.data.local.HistoryEntity
import com.zyfen.music.data.local.PlaylistEntity
import com.zyfen.music.data.media.Song
import com.zyfen.music.data.media.toSong
import com.zyfen.music.ui.components.*
import com.zyfen.music.ui.theme.*

// -------------------------------------------------------------
// 1. HOME SCREEN
// -------------------------------------------------------------
@Composable
fun HomeScreen(
    vm: LibraryViewModel,
    onOpenPlayer: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenSpotifyImport: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenOffline: () -> Unit = {},
    hasAudioPermission: () -> Boolean = { true },
    onRequestAudio: () -> Unit = {}
) {
    val songs by vm.songs.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val history by vm.history.collectAsState()
    val local = remember(songs) { songs.filter { it.isLocal } }
    val hasPerm = hasAudioPermission()

    LaunchedEffect(local.isEmpty(), hasPerm) {
        if (local.isEmpty() && hasPerm) {
            vm.refresh()
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            // Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "ZYFEN MUSIC",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            ),
                            color = ZyfenText
                        )
                        Text(
                            "Your personal music player",
                            style = MaterialTheme.typography.bodySmall,
                            color = ZyfenTextSecondary
                        )
                    }
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, "Search", tint = ZyfenText)
                    }
                }
            }

            // Quick Category Shortcuts
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = false,
                        onClick = onOpenFavorites,
                        label = { Text("Favorites (${favorites.size})") },
                        leadingIcon = { Icon(Icons.Filled.Favorite, null, tint = ZyfenNeon, modifier = Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = false,
                        onClick = onOpenOffline,
                        label = { Text("Offline (${local.size})") },
                        leadingIcon = { Icon(Icons.Filled.Folder, null, tint = ZyfenPurple, modifier = Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = false,
                        onClick = onOpenHistory,
                        label = { Text("History") },
                        leadingIcon = { Icon(Icons.Filled.History, null, tint = ZyfenTextSecondary, modifier = Modifier.size(16.dp)) }
                    )
                }
            }

            // Audio Permission Card if needed
            if (!hasPerm) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(containerColor = ZyfenCard),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Local Music Access", style = MaterialTheme.typography.titleMedium, color = ZyfenText)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Grant storage access to scan and play all audio files on your device (MP3, M4A, FLAC, WAV, OGG).",
                                style = MaterialTheme.typography.bodySmall,
                                color = ZyfenTextSecondary
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = onRequestAudio,
                                colors = ButtonDefaults.buttonColors(containerColor = ZyfenPurple)
                            ) {
                                Text("Allow Access")
                            }
                        }
                    }
                }
            }

            // Recently Played Carousel
            if (history.isNotEmpty()) {
                item {
                    SectionHeader("Recently Played", onSeeAll = onOpenHistory)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(history.take(10)) { h ->
                            HistoryCard(h, onClick = {
                                val s = Song(
                                    id = h.songId,
                                    title = h.title,
                                    artist = h.artist,
                                    album = h.album,
                                    durationMs = h.durationMs,
                                    contentUri = h.uri,
                                    artworkUri = h.artworkUri,
                                    isLocal = h.isLocal
                                )
                                vm.player.playSongs(listOf(s), 0)
                                onOpenPlayer()
                            })
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            // Playlists Section
            item {
                SectionHeader("Playlists", onAction = onOpenSpotifyImport, actionLabel = "Import Spotify")
                if (playlists.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenSpotifyImport,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.CloudDownload, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Import Spotify Playlist or Create New")
                        }
                    }
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(playlists) { p ->
                            PlaylistCard(p, onClick = { onOpenPlaylist(p.id) })
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Quick Picks (Songs)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Quick Picks",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = ZyfenText
                    )
                    if (songs.isNotEmpty()) {
                        Row {
                            TextButton(onClick = { vm.playAll(0); onOpenPlayer() }) {
                                Icon(Icons.Filled.PlayArrow, null, tint = ZyfenPurple, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Play all", color = ZyfenPurple)
                            }
                        }
                    }
                }
            }

            if (songs.isEmpty()) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (hasPerm) "No songs found on device. Tap search or import Spotify playlists."
                            else "Grant media permission to view local songs.",
                            color = ZyfenTextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                itemsIndexed(songs.take(25), key = { _, s -> s.id }) { index, s ->
                    SongRow(
                        song = s,
                        onClick = {
                            vm.player.playSongs(songs, index)
                            onOpenPlayer()
                        },
                        onFav = { vm.toggleFavorite(s) }
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// 2. SEARCH SCREEN
// -------------------------------------------------------------
@Composable
fun SearchScreen(
    vm: LibraryViewModel,
    onOpenPlayer: () -> Unit
) {
    val q by vm.query.collectAsState()
    val list by vm.filtered.collectAsState()
    var filterLocalOnly by remember { mutableStateOf(false) }

    val displayed = remember(list, filterLocalOnly) {
        if (filterLocalOnly) list.filter { it.isLocal } else list
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp)
    ) {
        ScreenHeader("Search Music")

        OutlinedTextField(
            value = q,
            onValueChange = vm::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("Search songs, artists, albums…") },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = ZyfenPurple) },
            trailingIcon = {
                if (q.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) {
                        Icon(Icons.Filled.Clear, "Clear", tint = ZyfenTextSecondary)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ZyfenPurple,
                unfocusedBorderColor = ZyfenCard,
                focusedContainerColor = ZyfenCard,
                unfocusedContainerColor = ZyfenCard
            )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !filterLocalOnly,
                onClick = { filterLocalOnly = false },
                label = { Text("All Tracks") }
            )
            FilterChip(
                selected = filterLocalOnly,
                onClick = { filterLocalOnly = true },
                label = { Text("Device Audio Only") }
            )
        }

        if (displayed.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (q.isBlank()) "Type title, artist, or album name to search"
                    else "No matching tracks found for '$q'",
                    color = ZyfenTextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(displayed, key = { _, s -> s.id }) { i, s ->
                    SongRow(
                        song = s,
                        onClick = {
                            vm.player.playSongs(displayed, i)
                            onOpenPlayer()
                        },
                        onFav = { vm.toggleFavorite(s) }
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// 3. LIBRARY SCREEN
// -------------------------------------------------------------
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    onOpenPlayer: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenSpotifyImport: () -> Unit
) {
    val songs by vm.songs.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val history by vm.history.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Songs", "Playlists", "Favorites", "History")

    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp)
    ) {
        ScreenHeader(
            "My Library",
            actions = {
                IconButton(onClick = onOpenSpotifyImport) {
                    Icon(Icons.Filled.CloudDownload, "Import", tint = ZyfenText)
                }
            }
        )

        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding = 16.dp,
            containerColor = Color.Transparent,
            contentColor = ZyfenPurple,
            indicator = {},
            divider = {}
        ) {
            tabs.forEachIndexed { index, title ->
                val selected = selectedTab == index
                Tab(
                    selected = selected,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            title,
                            color = if (selected) ZyfenPurple else ZyfenTextSecondary,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        when (selectedTab) {
            0 -> { // All Songs
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${songs.size} tracks", color = ZyfenTextSecondary, style = MaterialTheme.typography.bodySmall)
                            PillButton("Play All", onClick = { vm.playAll(0); onOpenPlayer() })
                        }
                    }
                    itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
                        SongRow(
                            song = s,
                            onClick = { vm.player.playSongs(songs, i); onOpenPlayer() },
                            onFav = { vm.toggleFavorite(s) }
                        )
                    }
                }
            }
            1 -> { // Playlists
                PlaylistsScreen(vm, onOpenPlaylist, onOpenSpotifyImport)
            }
            2 -> { // Favorites
                FavoritesScreen(vm, onOpenPlayer)
            }
            3 -> { // History
                HistoryScreen(vm, onOpenPlayer)
            }
        }
    }
}

// -------------------------------------------------------------
// 4. PLAYLISTS SCREEN
// -------------------------------------------------------------
@Composable
fun PlaylistsScreen(
    vm: LibraryViewModel,
    onOpenPlaylist: (String) -> Unit,
    onOpenSpotifyImport: () -> Unit
) {
    val playlists by vm.playlists.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${playlists.size} playlists", color = ZyfenTextSecondary, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { showCreate = true },
                colors = ButtonDefaults.buttonColors(containerColor = ZyfenPurple)
            ) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New Playlist")
            }
        }

        if (playlists.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No playlists yet", color = ZyfenTextSecondary)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { showCreate = true }) {
                        Text("Create Playlist")
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(playlists, key = { it.id }) { p ->
                    ListItem(
                        headlineContent = {
                            Text(p.name, color = ZyfenText, style = MaterialTheme.typography.titleSmall)
                        },
                        supportingContent = {
                            Text(if (p.isSpotifyImport) "Spotify Import" else "Custom Playlist", color = ZyfenTextSecondary)
                        },
                        leadingContent = { Artwork(p.artworkUrl, Modifier.size(54.dp), 10) },
                        trailingContent = {
                            IconButton(onClick = { vm.deletePlaylist(p.id) }) {
                                Icon(Icons.Filled.DeleteOutline, "Delete", tint = ZyfenTextSecondary)
                            }
                        },
                        modifier = Modifier
                            .clickable { onOpenPlaylist(p.id) }
                            .padding(horizontal = 8.dp),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Create New Playlist", color = ZyfenText) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Playlist Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ZyfenPurple,
                        focusedLabelColor = ZyfenPurple
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        vm.createPlaylist(name.trim())
                        name = ""
                        showCreate = false
                    }
                }) {
                    Text("Create", color = ZyfenPurple)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("Cancel", color = ZyfenTextSecondary) }
            },
            containerColor = ZyfenSurface
        )
    }
}

// -------------------------------------------------------------
// 5. FAVORITES SCREEN
// -------------------------------------------------------------
@Composable
fun FavoritesScreen(
    vm: LibraryViewModel,
    onOpenPlayer: () -> Unit
) {
    val favorites by vm.favorites.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp)
    ) {
        ScreenHeader("Favorites")

        if (favorites.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.FavoriteBorder, null, tint = ZyfenTextDisabled, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No favorite songs yet", color = ZyfenTextSecondary)
                    Text("Tap the heart icon on any song to add it here", color = ZyfenTextDisabled, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${favorites.size} songs", color = ZyfenTextSecondary, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Play All", onClick = {
                        vm.player.playSongs(favorites, 0)
                        onOpenPlayer()
                    })
                    PillButton("Shuffle", onClick = {
                        vm.player.playSongs(favorites.shuffled(), 0)
                        onOpenPlayer()
                    })
                }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(favorites, key = { _, s -> s.id }) { i, s ->
                    SongRow(
                        song = s,
                        onClick = {
                            vm.player.playSongs(favorites, i)
                            onOpenPlayer()
                        },
                        onFav = { vm.toggleFavorite(s) }
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// 6. HISTORY SCREEN
// -------------------------------------------------------------
@Composable
fun HistoryScreen(
    vm: LibraryViewModel,
    onOpenPlayer: () -> Unit
) {
    val history by vm.history.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp)
    ) {
        ScreenHeader(
            "Listening History",
            actions = {
                if (history.isNotEmpty()) {
                    IconButton(onClick = { vm.clearHistory() }) {
                        Icon(Icons.Filled.DeleteSweep, "Clear History", tint = ZyfenTextSecondary)
                    }
                }
            }
        )

        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.History, null, tint = ZyfenTextDisabled, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No playback history yet", color = ZyfenTextSecondary)
                    Text("Songs you play will show up here", color = ZyfenTextDisabled, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(history, key = { it.historyId }) { h ->
                    val s = Song(
                        id = h.songId,
                        title = h.title,
                        artist = h.artist,
                        album = h.album,
                        durationMs = h.durationMs,
                        contentUri = h.uri,
                        artworkUri = h.artworkUri,
                        isLocal = h.isLocal
                    )
                    ListItem(
                        headlineContent = {
                            Text(h.title, color = ZyfenText, maxLines = 1, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                        },
                        supportingContent = {
                            Text("${h.artist} • ${formatTimeAgo(h.playedAt)}", color = ZyfenTextSecondary, maxLines = 1)
                        },
                        leadingContent = {
                            Artwork(h.artworkUri, Modifier.size(48.dp), 8)
                        },
                        trailingContent = {
                            IconButton(onClick = { vm.deleteHistoryItem(h.historyId) }) {
                                Icon(Icons.Filled.Close, "Remove", tint = ZyfenTextDisabled, modifier = Modifier.size(16.dp))
                            }
                        },
                        modifier = Modifier
                            .clickable {
                                vm.player.playSongs(listOf(s), 0)
                                onOpenPlayer()
                            }
                            .padding(horizontal = 8.dp),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// 7. OFFLINE / DOWNLOADS MUSIC SCREEN
// -------------------------------------------------------------
@Composable
fun OfflineScreen(
    vm: LibraryViewModel,
    onOpenPlayer: () -> Unit
) {
    val songs by vm.songs.collectAsState()
    val localSongs = remember(songs) { songs.filter { it.isLocal } }
    var selectedFormat by remember { mutableStateOf("All") }
    val formats = listOf("All", "MP3", "M4A", "FLAC", "WAV", "OGG")

    val filteredByFormat = remember(localSongs, selectedFormat) {
        if (selectedFormat == "All") localSongs
        else localSongs.filter { s ->
            s.filePath?.endsWith(".$selectedFormat", ignoreCase = true) == true ||
            s.contentUri.endsWith(".$selectedFormat", ignoreCase = true)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp)
    ) {
        ScreenHeader(
            "Offline Music",
            actions = {
                IconButton(onClick = { vm.refresh() }) {
                    Icon(Icons.Filled.Refresh, "Rescan", tint = ZyfenText)
                }
            }
        )

        // Format selector
        ScrollableTabRow(
            selectedTabIndex = formats.indexOf(selectedFormat).coerceAtLeast(0),
            edgePadding = 16.dp,
            containerColor = Color.Transparent,
            contentColor = ZyfenPurple,
            indicator = {},
            divider = {}
        ) {
            formats.forEach { fmt ->
                val sel = selectedFormat == fmt
                FilterChip(
                    selected = sel,
                    onClick = { selectedFormat = fmt },
                    label = { Text(fmt) },
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${filteredByFormat.size} offline tracks", color = ZyfenTextSecondary, style = MaterialTheme.typography.bodySmall)
            if (filteredByFormat.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Play All", onClick = {
                        vm.player.playSongs(filteredByFormat, 0)
                        onOpenPlayer()
                    })
                    PillButton("Shuffle", onClick = {
                        vm.player.playSongs(filteredByFormat.shuffled(), 0)
                        onOpenPlayer()
                    })
                }
            }
        }

        if (filteredByFormat.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.FolderOpen, null, tint = ZyfenTextDisabled, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No $selectedFormat tracks found offline", color = ZyfenTextSecondary)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Rescan Device Audio")
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(filteredByFormat, key = { _, s -> s.id }) { i, s ->
                    SongRow(
                        song = s,
                        onClick = {
                            vm.player.playSongs(filteredByFormat, i)
                            onOpenPlayer()
                        },
                        onFav = { vm.toggleFavorite(s) }
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// PLAYLIST DETAIL SCREEN
// -------------------------------------------------------------
@Composable
fun PlaylistDetailScreen(
    vm: LibraryViewModel,
    playlistId: String,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit
) {
    LaunchedEffect(playlistId) { vm.loadPlaylist(playlistId) }
    val detail by vm.detail.collectAsState()
    val p = detail?.playlist
    val songs = remember(detail) { detail?.songs.orEmpty().map { it.toSong() } }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp)
    ) {
        DetailHeader(
            p?.name ?: "Playlist",
            onBack = onBack
        )

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Artwork(
                    p?.artworkUrl,
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(horizontal = 48.dp, vertical = 8.dp),
                    16
                )
                Spacer(Modifier.height(12.dp))

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(p?.name ?: "", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = ZyfenText)
                        Text("${songs.size} songs", style = MaterialTheme.typography.bodySmall, color = ZyfenTextSecondary)
                    }
                    Row {
                        IconButton(onClick = { renameText = p?.name ?: ""; showRename = true }) {
                            Icon(Icons.Filled.Edit, "Rename", tint = ZyfenTextSecondary)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (songs.isNotEmpty()) {
                        PillButton(
                            "Play All",
                            onClick = {
                                vm.player.playSongs(songs, 0)
                                onOpenPlayer()
                            },
                            icon = { Icon(Icons.Filled.PlayArrow, null, tint = ZyfenText, modifier = Modifier.size(18.dp)) }
                        )
                        PillButton(
                            "Shuffle",
                            onClick = {
                                vm.player.playSongs(songs.shuffled(), 0)
                                onOpenPlayer()
                            },
                            icon = { Icon(Icons.Filled.Shuffle, null, tint = ZyfenText, modifier = Modifier.size(18.dp)) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f)) {
                        SongRow(
                            song = s,
                            onClick = {
                                vm.player.playSongs(songs, i)
                                onOpenPlayer()
                            },
                            onFav = { vm.toggleFavorite(s) }
                        )
                    }
                    IconButton(onClick = { vm.removeSongFromPlaylist(playlistId, s.id) }) {
                        Icon(Icons.Filled.RemoveCircleOutline, "Remove", tint = ZyfenTextDisabled, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename Playlist", color = ZyfenText) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("New Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) {
                        vm.renamePlaylist(playlistId, renameText.trim())
                        showRename = false
                    }
                }) {
                    Text("Save", color = ZyfenPurple)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Cancel", color = ZyfenTextSecondary) }
            },
            containerColor = ZyfenSurface
        )
    }
}

// -------------------------------------------------------------
// HELPERS & SUB-COMPONENTS
// -------------------------------------------------------------
@Composable
fun SectionHeader(
    title: String,
    onSeeAll: (() -> Unit)? = null,
    onAction: (() -> Unit)? = null,
    actionLabel: String = "Action"
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = ZyfenText)
        if (onSeeAll != null) {
            TextButton(onClick = onSeeAll) {
                Text("See all", color = ZyfenPurple, style = MaterialTheme.typography.labelMedium)
            }
        } else if (onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, color = ZyfenPurple, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun HistoryCard(h: HistoryEntity, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(110.dp)
            .clickable(onClick = onClick)
    ) {
        Artwork(h.artworkUri, Modifier.size(110.dp), 12)
        Spacer(Modifier.height(6.dp))
        Text(h.title, color = ZyfenText, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(h.artist, color = ZyfenTextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PlaylistCard(p: PlaylistEntity, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onClick)
    ) {
        Artwork(p.artworkUrl, Modifier.size(120.dp), 12)
        Spacer(Modifier.height(6.dp))
        Text(p.name, color = ZyfenText, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (p.isSpotifyImport) "Spotify" else "Playlist", color = ZyfenTextSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

fun formatTimeAgo(timeMs: Long): String {
    val diff = System.currentTimeMillis() - timeMs
    val minutes = diff / 60000L
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1440 -> "${minutes / 60}h ago"
        else -> "${minutes / 1440}d ago"
    }
}

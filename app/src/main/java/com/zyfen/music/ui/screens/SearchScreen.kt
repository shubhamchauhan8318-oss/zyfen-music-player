package com.zyfen.music.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyfen.music.ZyfenApp
import com.zyfen.music.data.media.Song
import com.zyfen.music.data.media.toSong
import com.zyfen.music.data.online.OnlineMusicRepository
import com.zyfen.music.ui.components.Artwork
import com.zyfen.music.ui.components.formatDuration
import com.zyfen.music.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FullSearchScreen(
    vm: LibraryViewModel,
    onOpenPlayer: () -> Unit,
    onlineRepo: OnlineMusicRepository = remember { ZyfenApp.container.onlineMusicRepo }
) {
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val settingsStore = remember { ZyfenApp.container.settingsStore }
    val recentSearches by settingsStore.searchHistory.collectAsState(initial = emptyList())
    val accent = LocalAccentColor.current

    var searchQuery by remember { mutableStateOf("") }
    var searchMode by remember { mutableIntStateOf(0) } // 0: Online Global, 1: My Device
    var isSearching by remember { mutableStateOf(false) }
    var onlineResults by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    val localFiltered by vm.filtered.collectAsState()

    androidx.activity.compose.BackHandler(enabled = searchQuery.isNotEmpty()) {
        searchQuery = ""
        onlineResults = emptyList()
        vm.setQuery("")
    }

    val quickSearches = remember {
        listOf(
            "Trending Now",
            "Arijit Singh",
            "Diljit Dosanjh",
            "Top Global 50",
            "Karan Aujla",
            "Anuv Jain",
            "Lo-Fi Chill",
            "Taylor Swift",
            "Shreya Ghoshal",
            "Ed Sheeran",
            "Sidhu Moose Wala",
            "Bollywood Romance"
        )
    }

    fun executeOnlineSearch(query: String) {
        val q = query.trim()
        if (q.isBlank()) {
            onlineResults = emptyList()
            isSearching = false
            return
        }
        coroutineScope.launch {
            settingsStore.addSearchHistory(q)
        }
        searchJob?.cancel()
        searchJob = coroutineScope.launch {
            isSearching = true
            try {
                val results = onlineRepo.searchSongs(q)
                onlineResults = results
            } catch (_: Exception) {
                onlineResults = emptyList()
            } finally {
                isSearching = false
            }
        }
    }

    fun onQueryChange(newQuery: String) {
        searchQuery = newQuery
        if (searchMode == 1) {
            vm.setQuery(newQuery)
        } else {
            searchJob?.cancel()
            if (newQuery.length >= 2) {
                searchJob = coroutineScope.launch {
                    delay(300) // Debounce 300ms
                    executeOnlineSearch(newQuery)
                }
            } else if (newQuery.isBlank()) {
                onlineResults = emptyList()
                isSearching = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(bottom = 80.dp)
    ) {
        // Top Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Text(
                "Search",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    shadow = LiquidGlassTokens.TextShadow
                ),
                color = Color.White
            )
            Text(
                "Find any song, artist, album or soundtrack instantly",
                style = MaterialTheme.typography.bodySmall.copy(
                    shadow = LiquidGlassTokens.SubtleTextShadow
                ),
                color = Color(0xFFD8B4FE)
            )
        }

        // Frosted Glass Search Input Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { onQueryChange(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .shadow(10.dp, RoundedCornerShape(20.dp), spotColor = Color.Black.copy(alpha = 0.40f)),
            placeholder = {
                Text(
                    if (searchMode == 0) "Search online songs, Bollywood, Pop, Artists…"
                    else "Search offline device songs…",
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "Search",
                    tint = accent
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = {
                        searchQuery = ""
                        onlineResults = emptyList()
                        vm.setQuery("")
                    }) {
                        Icon(Icons.Filled.Clear, "Clear", tint = Color.White.copy(alpha = 0.7f))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focusManager.clearFocus()
                if (searchMode == 0) executeOnlineSearch(searchQuery)
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = Color.White.copy(alpha = 0.22f),
                focusedContainerColor = Color.White.copy(alpha = 0.12f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.08f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )

        // Mode Switcher (Online vs Device)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GlassPillButton(
                text = "Online Music",
                icon = Icons.Filled.Cloud,
                selected = searchMode == 0,
                accentColor = accent,
                onClick = {
                    searchMode = 0
                    if (searchQuery.isNotBlank() && onlineResults.isEmpty()) {
                        executeOnlineSearch(searchQuery)
                    }
                }
            )

            GlassPillButton(
                text = "Device Audio",
                icon = Icons.Filled.PhoneAndroid,
                selected = searchMode == 1,
                accentColor = accent,
                onClick = {
                    searchMode = 1
                    vm.setQuery(searchQuery)
                }
            )
        }

        // Quick Category Suggestions and Recent Searches
        if (searchQuery.isBlank() && searchMode == 0) {
            if (recentSearches.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Recent Searches",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            shadow = LiquidGlassTokens.SubtleTextShadow
                        ),
                        color = Color.White
                    )
                    TextButton(
                        onClick = { coroutineScope.launch { settingsStore.clearSearchHistory() } },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Clear All", style = MaterialTheme.typography.labelSmall, color = accent)
                    }
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(recentSearches) { item ->
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = Color.White.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                            modifier = Modifier.clickable {
                                searchQuery = item
                                executeOnlineSearch(item)
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Filled.History, null, tint = accent, modifier = Modifier.size(14.dp))
                                Text(
                                    item,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = Color.White
                                )
                                IconButton(
                                    onClick = { coroutineScope.launch { settingsStore.removeSearchHistory(item) } },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(Icons.Filled.Close, "Remove", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            Text(
                "Explore Trending Music",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    shadow = LiquidGlassTokens.SubtleTextShadow
                ),
                color = Color(0xFFD8B4FE),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(quickSearches) { tag ->
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White.copy(alpha = 0.09f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                        modifier = Modifier.clickable {
                            searchQuery = tag
                            executeOnlineSearch(searchQuery)
                        }
                    ) {
                        Text(
                            tag,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = Color.White
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Loading state
        if (isSearching) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = accent
                    )
                    Text("Searching songs instantly…", color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Results Section
        if (searchMode == 0) {
            // Online Results
            if (onlineResults.isEmpty() && !isSearching) {
                if (searchQuery.isNotBlank()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.SearchOff, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("No online tracks found for '$searchQuery'", color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.MusicNote, null, tint = accent.copy(alpha = 0.6f), modifier = Modifier.size(56.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Type any song name, artist, or Bollywood track", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                            Text("Plays instantly in ultra high quality!", color = accent, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    itemsIndexed(onlineResults, key = { _, s -> s.id }) { index, s ->
                        OnlineSongRow(
                            song = s,
                            onPlay = {
                                vm.player.playSongs(onlineResults, index)
                                onOpenPlayer()
                            },
                            onToggleFav = {
                                vm.toggleFavorite(s)
                            }
                        )
                    }
                }
            }
        } else {
            // Offline Device Library Results
            val displayed = localFiltered
            if (displayed.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (searchQuery.isBlank()) "Type song title to search device audio"
                        else "No device tracks matching '$searchQuery'",
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    itemsIndexed(displayed, key = { _, s -> s.id }) { index, s ->
                        OnlineSongRow(
                            song = s,
                            onPlay = {
                                vm.player.playSongs(displayed, index)
                                onOpenPlayer()
                            },
                            onToggleFav = {
                                vm.toggleFavorite(s)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun OnlineSongRow(
    song: Song,
    onPlay: () -> Unit,
    onToggleFav: () -> Unit
) {
    val accent = LocalAccentColor.current
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.08f),
                        Color.White.copy(alpha = 0.03f)
                    )
                )
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.20f),
                        Color.White.copy(alpha = 0.04f)
                    )
                ),
                shape
            )
            .clickable(onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Artwork(song.artworkUri, Modifier.size(50.dp), 12, seed = song.id, title = song.title)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        shadow = LiquidGlassTokens.SubtleTextShadow
                    ),
                    color = Color.White
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        song.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall.copy(
                            shadow = LiquidGlassTokens.SubtleTextShadow
                        ),
                        color = Color(0xFFD8B4FE),
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    val dur = formatDuration(song.durationMs)
                    if (dur.isNotEmpty()) {
                        Text(
                            " • $dur",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            IconButton(onClick = onPlay, modifier = Modifier.size(38.dp)) {
                Icon(
                    Icons.Filled.PlayCircleFilled,
                    contentDescription = "Play",
                    tint = accent,
                    modifier = Modifier.size(28.dp)
                )
            }
            IconButton(onClick = onToggleFav, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (song.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (song.isFavorite) accent else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

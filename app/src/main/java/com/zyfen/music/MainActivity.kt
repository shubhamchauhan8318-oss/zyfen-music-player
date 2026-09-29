package com.zyfen.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.zyfen.music.playback.PlayerManager
import com.zyfen.music.ui.components.ConnectedMiniPlayer
import com.zyfen.music.ui.components.RailTab
import com.zyfen.music.ui.components.ZyfenNavigationRail
import com.zyfen.music.ui.nav.Route
import com.zyfen.music.ui.screens.*
import com.zyfen.music.ui.theme.ZyfenTheme

class MainActivity : ComponentActivity() {

    private val player: PlayerManager by lazy { ZyfenApp.container.playerManager }

    private var audioGranted by mutableStateOf(false)

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        audioGranted = hasAudioPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioGranted = hasAudioPermission()
        requestAudioPerms()
        setContent {
            ZyfenTheme {
                val nav = rememberNavController()
                val lib: LibraryViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            val c = ZyfenApp.container
                            return LibraryViewModel(c.localRepo, c.songDao, c.playlistDao, c.playerManager) as T
                        }
                    }
                )

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    player.events.collect {
                        android.widget.Toast.makeText(
                            this@MainActivity, it, android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                val back by nav.currentBackStackEntryAsState()
                val route = back?.destination?.route ?: Route.Home.path
                val chromeVisible = route != Route.NowPlaying.path && route != Route.Queue.path

                val tabs = remember {
                    listOf(
                        RailTab("Quick picks", Icons.Filled.AutoAwesome),
                        RailTab("Songs", Icons.Filled.MusicNote),
                        RailTab("Playlists", Icons.Filled.QueueMusic),
                        RailTab("Artists", Icons.Filled.Person),
                        RailTab("Albums", Icons.Filled.Album),
                        RailTab("Search", Icons.Filled.Search)
                    )
                }
                val tabRoutes = remember {
                    listOf(
                        Route.Home.path, Route.Songs.path, Route.Library.path,
                        Route.Artists.path, Route.Albums.path, Route.Search.path
                    )
                }
                val selectedIndex = when {
                    route.startsWith("playlist/") -> 2
                    route.startsWith("album/") -> 4
                    route.startsWith("artist/") -> 3
                    else -> tabRoutes.indexOf(route)
                }

                val openSearch: () -> Unit = {
                    nav.navigate(Route.Search.path) {
                        popUpTo(Route.Home.path) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }

                Row(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .windowInsetsPadding(
                            WindowInsets.statusBars.only(
                                WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                            )
                        )
                ) {
                    if (chromeVisible) {
                        ZyfenNavigationRail(
                            tabs = tabs,
                            selectedIndex = selectedIndex,
                            onSelected = { i ->
                                nav.navigate(tabRoutes[i]) {
                                    popUpTo(Route.Home.path) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            onTopIconClick = {
                                nav.navigate(Route.Settings.path) { launchSingleTop = true }
                            },
                            topIcon = Icons.Filled.Settings
                        )
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        NavHost(
                            nav, startDestination = Route.Home.path,
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        ) {
                            composable(Route.Home.path) {
                                HomeScreen(lib,
                                    onOpenPlayer = { nav.navigate(Route.NowPlaying.path) },
                                    onOpenPlaylist = { nav.navigate(Route.PlaylistDetail.id(it)) },
                                    onOpenSpotifyImport = { nav.navigate(Route.SpotifyImport.path) },
                                    onOpenSearch = openSearch,
                                    hasAudioPermission = { audioGranted },
                                    onRequestAudio = { requestAudioPerms() })
                            }
                            composable(Route.Songs.path) {
                                SongsScreen(lib, onOpenPlayer = { nav.navigate(Route.NowPlaying.path) }, onOpenSearch = openSearch)
                            }
                            composable(Route.Search.path) {
                                SearchScreen(lib, onOpenPlayer = { nav.navigate(Route.NowPlaying.path) })
                            }
                            composable(Route.Library.path) {
                                LibraryScreen(lib,
                                    onOpenPlayer = { nav.navigate(Route.NowPlaying.path) },
                                    onOpenPlaylist = { nav.navigate(Route.PlaylistDetail.id(it)) },
                                    onOpenSpotifyImport = { nav.navigate(Route.SpotifyImport.path) })
                            }
                            composable(Route.Artists.path) {
                                ArtistsScreen(lib, onOpenArtist = { nav.navigate(Route.ArtistDetail.name(it)) }, onOpenSearch = openSearch)
                            }
                            composable(Route.Albums.path) {
                                AlbumsScreen(lib, onOpenAlbum = { nav.navigate(Route.AlbumDetail.name(it)) }, onOpenSearch = openSearch)
                            }
                            composable(Route.SpotifyImport.path) {
                                SpotifyImportScreen(lib = lib, onBack = { nav.popBackStack() })
                            }
                            composable(Route.Settings.path) {
                                SettingsScreen(onBack = { nav.popBackStack() })
                            }
                            composable(
                                route = Route.PlaylistDetail.path,
                                arguments = listOf(navArgument("id") { type = NavType.StringType })
                            ) { entry ->
                                val pid = entry.arguments?.getString("id") ?: return@composable
                                PlaylistDetailScreen(
                                    lib, pid,
                                    onBack = { nav.popBackStack() },
                                    onOpenPlayer = { nav.navigate(Route.NowPlaying.path) }
                                )
                            }
                            composable(
                                route = Route.AlbumDetail.path,
                                arguments = listOf(navArgument("name") { type = NavType.StringType })
                            ) { entry ->
                                val name = entry.arguments?.getString("name") ?: return@composable
                                AlbumDetailScreen(
                                    lib, name,
                                    onBack = { nav.popBackStack() },
                                    onOpenPlayer = { nav.navigate(Route.NowPlaying.path) }
                                )
                            }
                            composable(
                                route = Route.ArtistDetail.path,
                                arguments = listOf(navArgument("name") { type = NavType.StringType })
                            ) { entry ->
                                val name = entry.arguments?.getString("name") ?: return@composable
                                ArtistDetailScreen(
                                    lib, name,
                                    onBack = { nav.popBackStack() },
                                    onOpenPlayer = { nav.navigate(Route.NowPlaying.path) }
                                )
                            }
                            composable(Route.NowPlaying.path) {
                                NowPlayingScreen(player, lib,
                                    onOpenQueue = { nav.navigate(Route.Queue.path) },
                                    onBack = { nav.popBackStack() })
                            }
                            composable(Route.Queue.path) {
                                QueueScreen(player, lib, onBack = { nav.popBackStack() })
                            }
                        }
                        if (chromeVisible) {
                            ConnectedMiniPlayer(player) {
                                nav.navigate(Route.NowPlaying.path)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        audioGranted = hasAudioPermission()
    }

    private fun requestAudioPerms() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (check(Manifest.permission.READ_MEDIA_AUDIO)) perms += Manifest.permission.READ_MEDIA_AUDIO
            if (check(Manifest.permission.POST_NOTIFICATIONS)) perms += Manifest.permission.POST_NOTIFICATIONS
        } else {
            if (check(Manifest.permission.READ_EXTERNAL_STORAGE)) perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (perms.isNotEmpty()) permLauncher.launch(perms.toTypedArray())
        else audioGranted = hasAudioPermission()
    }

    private fun hasAudioPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    }

    private fun check(p: String) =
        ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED
}

package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.*
import com.example.ui.screens.*
import com.example.ui.theme.*
import com.example.viewmodel.AppTab
import com.example.viewmodel.MusicViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                SoundFlowApp()
            }
        }
    }
}

@Composable
fun SoundFlowApp(
    viewModel: MusicViewModel = viewModel()
) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) {}
        LaunchedEffect(Unit) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val filteredSongs by viewModel.filteredSongs.collectAsStateWithLifecycle()
    val allSongs by viewModel.allSongs.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()

    val currentPlayingSong by viewModel.playerManager.currentSong.collectAsStateWithLifecycle()
    val isPlaying by viewModel.playerManager.isPlaying.collectAsStateWithLifecycle()
    val currentPositionMs by viewModel.playerManager.currentPositionMs.collectAsStateWithLifecycle()
    val durationMs by viewModel.playerManager.durationMs.collectAsStateWithLifecycle()
    val isShuffle by viewModel.playerManager.isShuffle.collectAsStateWithLifecycle()
    val repeatMode by viewModel.playerManager.repeatMode.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playerManager.playbackSpeed.collectAsStateWithLifecycle()
    val equalizerPreset by viewModel.playerManager.equalizerPreset.collectAsStateWithLifecycle()
    val sleepTimerMinutes by viewModel.playerManager.sleepTimerMinutesLeft.collectAsStateWithLifecycle()
    val customBandLevels by viewModel.playerManager.customBandLevels.collectAsStateWithLifecycle()
    val customBassBoost by viewModel.playerManager.customBassBoost.collectAsStateWithLifecycle()

    val libraryFilter by viewModel.libraryFilter.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    val inputUrl by viewModel.inputUrl.collectAsStateWithLifecycle()
    val isAnalyzing by viewModel.isAnalyzing.collectAsStateWithLifecycle()
    val analyzedMetadata by viewModel.analyzedMetadata.collectAsStateWithLifecycle()
    val selectedQuality by viewModel.selectedQuality.collectAsStateWithLifecycle()
    val selectedPlaylistId by viewModel.selectedPlaylistId.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()

    val showNowPlayingSheet by viewModel.showNowPlayingSheet.collectAsStateWithLifecycle()
    val showQualityDialog by viewModel.showQualityDialog.collectAsStateWithLifecycle()
    val showCreatePlaylistDialog by viewModel.showCreatePlaylistDialog.collectAsStateWithLifecycle()
    val songToAddToPlaylist by viewModel.songToAddToPlaylist.collectAsStateWithLifecycle()
    val selectedPlaylistForDetail by viewModel.selectedPlaylistForDetail.collectAsStateWithLifecycle()
    val playlistDetailSongs by viewModel.playlistDetailSongs.collectAsStateWithLifecycle()

    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val successMessage by viewModel.successMessage.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it, withDismissAction = true)
            viewModel.clearMessages()
        }
    }

    LaunchedEffect(successMessage) {
        successMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearMessages()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = DarkBackground,
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.padding(bottom = if (currentPlayingSong != null) 140.dp else 80.dp)
                ) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = DarkSurfaceVariant,
                        contentColor = TextPrimary,
                        actionColor = NeonCyan,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            bottomBar = {
                Column {
                    // Mini Player (Visible when a song is playing or loaded)
                    AnimatedVisibility(
                        visible = currentPlayingSong != null && !showNowPlayingSheet,
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                    ) {
                        currentPlayingSong?.let { song ->
                            MiniPlayer(
                                song = song,
                                isPlaying = isPlaying,
                                currentPositionMs = currentPositionMs,
                                durationMs = durationMs,
                                onPlayPauseClick = { viewModel.togglePlayPause() },
                                onNextClick = { viewModel.playNext() },
                                onExpandClick = { viewModel.openNowPlayingSheet() }
                            )
                        }
                    }

                    // Navigation Bar
                    NavigationBar(
                        containerColor = DarkSurface,
                        tonalElevation = 8.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                            .testTag("bottom_nav_bar")
                    ) {
                        // Library Tab
                        NavigationBarItem(
                            selected = currentTab == AppTab.LIBRARY,
                            onClick = {
                                viewModel.setTab(AppTab.LIBRARY)
                                viewModel.selectPlaylistForDetail(null)
                            },
                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Thư viện") },
                            label = { Text("Thư viện", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF031A24),
                                selectedTextColor = NeonCyan,
                                indicatorColor = NeonCyan,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary
                            ),
                            modifier = Modifier.testTag("nav_tab_library")
                        )

                        // Downloader Tab (YouTube / TikTok)
                        NavigationBarItem(
                            selected = currentTab == AppTab.DOWNLOADER,
                            onClick = {
                                viewModel.setTab(AppTab.DOWNLOADER)
                                viewModel.selectPlaylistForDetail(null)
                            },
                            icon = { Icon(Icons.Default.Download, contentDescription = "Tải từ Link") },
                            label = { Text("Tải từ Link", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF031A24),
                                selectedTextColor = NeonCyan,
                                indicatorColor = NeonCyan,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary
                            ),
                            modifier = Modifier.testTag("nav_tab_downloader")
                        )

                        // Playlists Tab
                        NavigationBarItem(
                            selected = currentTab == AppTab.PLAYLISTS,
                            onClick = { viewModel.setTab(AppTab.PLAYLISTS) },
                            icon = { Icon(Icons.Default.QueueMusic, contentDescription = "Playlists") },
                            label = { Text("Playlists", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF031A24),
                                selectedTextColor = NeonCyan,
                                indicatorColor = NeonCyan,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary
                            ),
                            modifier = Modifier.testTag("nav_tab_playlists")
                        )
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when (currentTab) {
                    AppTab.LIBRARY -> {
                        LibraryScreen(
                            songs = filteredSongs,
                            currentPlayingSong = currentPlayingSong,
                            isPlaying = isPlaying,
                            currentFilter = libraryFilter,
                            searchQuery = searchQuery,
                            isImporting = isImporting,
                            onFilterChange = { viewModel.setLibraryFilter(it) },
                            onSearchChange = { viewModel.setSearchQuery(it) },
                            onSongClick = { song -> viewModel.playSong(song, filteredSongs) },
                            onToggleFavorite = { song -> viewModel.toggleFavorite(song) },
                            onAddToPlaylistClick = { song -> viewModel.openAddToPlaylist(song) },
                            onDeleteSongClick = { song -> viewModel.deleteSong(song) },
                            onScanDeviceAudio = { viewModel.scanDeviceAudio() },
                            onImportUris = { uris -> viewModel.importAudioFiles(uris) },
                            onNavigateToDownloader = { viewModel.setTab(AppTab.DOWNLOADER) }
                        )
                    }

                    AppTab.DOWNLOADER -> {
                        DownloaderScreen(
                            inputUrl = inputUrl,
                            isAnalyzing = isAnalyzing,
                            activeDownloads = activeDownloads,
                            allSongs = allSongs,
                            onUrlChange = { viewModel.setInputUrl(it) },
                            onAnalyzeClick = { viewModel.analyzeUrl() },
                            onRemoveDownloadTask = { id -> viewModel.removeDownloadTask(id) },
                            onPlayCompletedSong = { song -> viewModel.playSong(song, allSongs) }
                        )
                    }

                    AppTab.PLAYLISTS -> {
                        PlaylistsScreen(
                            playlists = playlists,
                            selectedPlaylist = selectedPlaylistForDetail,
                            playlistSongs = playlistDetailSongs,
                            currentPlayingSong = currentPlayingSong,
                            isPlaying = isPlaying,
                            onPlaylistClick = { playlist -> viewModel.selectPlaylistForDetail(playlist) },
                            onBackFromDetail = { viewModel.selectPlaylistForDetail(null) },
                            onCreatePlaylistClick = { viewModel.openCreatePlaylistDialog() },
                            onPlayPlaylist = { playlist, shuffle -> viewModel.playPlaylist(playlist, shuffle) },
                            onDeletePlaylist = { playlist -> viewModel.deletePlaylist(playlist) },
                            onSongClick = { song -> viewModel.playSong(song, playlistDetailSongs) },
                            onRemoveSongFromPlaylist = { playlist, song -> viewModel.removeSongFromPlaylist(playlist.id, song) }
                        )
                    }
                }
            }
        }

        // Full Screen Now Playing Sheet
        AnimatedVisibility(
            visible = showNowPlayingSheet && currentPlayingSong != null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            currentPlayingSong?.let { song ->
                NowPlayingSheet(
                    song = song,
                    isPlaying = isPlaying,
                    currentPositionMs = currentPositionMs,
                    durationMs = durationMs,
                    isShuffle = isShuffle,
                    repeatMode = repeatMode,
                    playbackSpeed = playbackSpeed,
                    equalizerPreset = equalizerPreset,
                    sleepTimerMinutes = sleepTimerMinutes,
                    customBandLevels = customBandLevels,
                    customBassBoost = customBassBoost,
                    onPlayPauseClick = { viewModel.togglePlayPause() },
                    onNextClick = { viewModel.playNext() },
                    onPreviousClick = { viewModel.playPrevious() },
                    onSeekTo = { pos -> viewModel.seekTo(pos) },
                    onSkipForward = { viewModel.skipForward() },
                    onSkipBackward = { viewModel.skipBackward() },
                    onToggleShuffle = { viewModel.toggleShuffle() },
                    onCycleRepeat = { viewModel.cycleRepeatMode() },
                    onSpeedChange = { speed -> viewModel.setPlaybackSpeed(speed) },
                    onEqualizerChange = { preset -> viewModel.setEqualizerPreset(preset) },
                    onCustomBandChange = { band, level -> viewModel.playerManager.setCustomBand(band, level) },
                    onCustomBassBoostChange = { strength -> viewModel.playerManager.setCustomBassBoost(strength) },
                    onResetCustomEqualizer = { viewModel.playerManager.resetCustomEqualizer() },
                    onSleepTimerChange = { mins -> viewModel.setSleepTimer(mins) },
                    onToggleFavorite = { s -> viewModel.toggleFavorite(s) },
                    onAddToPlaylistClick = { s -> viewModel.openAddToPlaylist(s) },
                    onDismiss = { viewModel.closeNowPlayingSheet() }
                )
            }
        }

        // Quality & Playlist Selection Dialog (After analyzing YouTube/TikTok link)
        if (showQualityDialog && analyzedMetadata != null) {
            analyzedMetadata?.let { meta ->
                QualitySelectionDialog(
                    metadata = meta,
                    playlists = playlists,
                    selectedQuality = selectedQuality,
                    selectedPlaylistId = selectedPlaylistId,
                    onQualitySelected = { q -> viewModel.selectQuality(q) },
                    onPlaylistSelected = { pId -> viewModel.selectTargetPlaylist(pId) },
                    onConfirmDownload = { title, artist ->
                        viewModel.confirmDownload(title, artist)
                    },
                    onCreateNewPlaylistClick = {
                        viewModel.openCreatePlaylistDialog()
                    },
                    onDismiss = { viewModel.dismissQualityDialog() }
                )
            }
        }

        // Create Playlist Dialog
        if (showCreatePlaylistDialog) {
            CreatePlaylistDialog(
                onDismiss = { viewModel.closeCreatePlaylistDialog() },
                onConfirm = { name, desc, color ->
                    viewModel.createPlaylist(name, desc, color)
                }
            )
        }

        // Add Song to Playlist Dialog
        songToAddToPlaylist?.let { song ->
            AddToPlaylistDialog(
                song = song,
                playlists = playlists,
                onPlaylistChosen = { playlistId ->
                    viewModel.addSongToPlaylist(playlistId, song)
                },
                onCreateNewClick = {
                    viewModel.openCreatePlaylistDialog()
                },
                onDismiss = { viewModel.closeAddToPlaylist() }
            )
        }
    }
}

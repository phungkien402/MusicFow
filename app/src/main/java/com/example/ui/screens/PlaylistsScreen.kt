package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Playlist
import com.example.data.model.Song
import com.example.ui.theme.*

@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>,
    selectedPlaylist: Playlist?,
    playlistSongs: List<Song>,
    currentPlayingSong: Song?,
    isPlaying: Boolean,
    onPlaylistClick: (Playlist) -> Unit,
    onBackFromDetail: () -> Unit,
    onCreatePlaylistClick: () -> Unit,
    onPlayPlaylist: (Playlist, Boolean) -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
    onSongClick: (Song) -> Unit,
    onRemoveSongFromPlaylist: (Playlist, Song) -> Unit
) {
    if (selectedPlaylist != null) {
        PlaylistDetailContent(
            playlist = selectedPlaylist,
            songs = playlistSongs,
            currentPlayingSong = currentPlayingSong,
            isPlaying = isPlaying,
            onBack = onBackFromDetail,
            onPlayAll = { onPlayPlaylist(selectedPlaylist, false) },
            onShufflePlay = { onPlayPlaylist(selectedPlaylist, true) },
            onDeletePlaylist = { onDeletePlaylist(selectedPlaylist) },
            onSongClick = onSongClick,
            onRemoveSong = { song -> onRemoveSongFromPlaylist(selectedPlaylist, song) }
        )
    } else {
        PlaylistListContent(
            playlists = playlists,
            onPlaylistClick = onPlaylistClick,
            onCreatePlaylistClick = onCreatePlaylistClick,
            onPlayPlaylist = onPlayPlaylist,
            onDeletePlaylist = onDeletePlaylist
        )
    }
}

@Composable
fun PlaylistListContent(
    playlists: List<Playlist>,
    onPlaylistClick: (Playlist) -> Unit,
    onCreatePlaylistClick: () -> Unit,
    onPlayPlaylist: (Playlist, Boolean) -> Unit,
    onDeletePlaylist: (Playlist) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .testTag("playlists_screen")
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Danh sách phát",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = NeonCyan
                )
                Text(
                    text = "${playlists.size} playlists đã tạo",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Button(
                onClick = onCreatePlaylistClick,
                colors = ButtonDefaults.buttonColors(containerColor = NeonPurple),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("create_playlist_header_btn")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Tạo mới", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(DarkSurfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.QueueMusic, contentDescription = null, tint = TextMuted, modifier = Modifier.size(36.dp))
                    }
                    Text(
                        text = "Chưa có Playlist nào",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Tạo playlist để phân loại nhạc YouTube, TikTok hoặc nhạc offline theo gu của bạn!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Button(
                        onClick = onCreatePlaylistClick,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonPurple),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("+ Tạo playlist ngay", color = Color.White)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    var showMenu by remember { mutableStateOf(false) }
                    val accentColor = try {
                        Color(android.graphics.Color.parseColor(playlist.coverColorHex))
                    } catch (e: Exception) {
                        NeonCyan
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp))
                            .clickable { onPlaylistClick(playlist) }
                            .testTag("playlist_card_${playlist.id}"),
                        color = DarkSurface
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(accentColor.copy(alpha = 0.2f))
                                    .border(1.dp, accentColor, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.QueueMusic,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (playlist.description.isNotBlank()) {
                                    Text(
                                        text = playlist.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = "${playlist.songCount} bài hát",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeonCyan
                                )
                            }

                            // Quick Play Playlist Button
                            IconButton(
                                onClick = { onPlayPlaylist(playlist, false) },
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(NeonCyan.copy(alpha = 0.15f))
                                    .testTag("play_playlist_${playlist.id}")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Phát playlist", tint = NeonCyan)
                            }

                            // Menu
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = null, tint = TextMuted)
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                    modifier = Modifier
                                        .background(DarkSurfaceVariant)
                                        .border(1.dp, DarkCardBorder, RoundedCornerShape(8.dp))
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Phát ngẫu nhiên", color = TextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.Shuffle, contentDescription = null, tint = NeonPurple) },
                                        onClick = {
                                            showMenu = false
                                            onPlayPlaylist(playlist, true)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Xoá playlist", color = NeonPink) },
                                        leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = NeonPink) },
                                        onClick = {
                                            showMenu = false
                                            onDeletePlaylist(playlist)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistDetailContent(
    playlist: Playlist,
    songs: List<Song>,
    currentPlayingSong: Song?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShufflePlay: () -> Unit,
    onDeletePlaylist: () -> Unit,
    onSongClick: (Song) -> Unit,
    onRemoveSong: (Song) -> Unit
) {
    BackHandler { onBack() }

    val accentColor = try {
        Color(android.graphics.Color.parseColor(playlist.coverColorHex))
    } catch (e: Exception) {
        NeonCyan
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .testTag("playlist_detail_screen")
    ) {
        // App bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("playlist_detail_back_btn")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại", tint = TextPrimary)
            }

            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            IconButton(onClick = onDeletePlaylist) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Xoá playlist", tint = NeonPink)
            }
        }

        // Playlist Banner Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(16.dp),
            color = DarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(accentColor.copy(alpha = 0.2f))
                        .border(1.dp, accentColor, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.QueueMusic, contentDescription = null, tint = accentColor, modifier = Modifier.size(32.dp))
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    if (playlist.description.isNotBlank()) {
                        Text(
                            text = playlist.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Text(
                        text = "${songs.size} bài hát",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonCyan
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Play All and Shuffle Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onPlayAll,
                enabled = songs.isNotEmpty(),
                modifier = Modifier
                    .weight(1f)
                    .testTag("playlist_play_all_btn"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF031A24))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Phát tất cả", color = Color(0xFF031A24), fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = onShufflePlay,
                enabled = songs.isNotEmpty(),
                modifier = Modifier
                    .weight(1f)
                    .testTag("playlist_shuffle_btn"),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = null, tint = NeonPurple)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Xáo trộn", color = TextPrimary)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Song list inside playlist
        if (songs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Playlist này chưa có bài hát nào.\nHãy vào Thư viện hoặc Tải từ Link rồi chọn thêm vào playlist này!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(songs, key = { it.id }) { song ->
                    val isCurrent = currentPlayingSong?.id == song.id
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, if (isCurrent) NeonCyan.copy(alpha = 0.6f) else DarkCardBorder, RoundedCornerShape(12.dp))
                            .clickable { onSongClick(song) },
                        color = if (isCurrent) NeonCyan.copy(alpha = 0.08f) else DarkSurface
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isCurrent && isPlaying) Icons.Default.VolumeUp else Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = if (isCurrent) NeonCyan else TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (isCurrent) NeonCyan else TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${song.artist} • ${song.format.displayName} (${song.bitrate})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { onRemoveSong(song) }) {
                                Icon(Icons.Default.RemoveCircleOutline, contentDescription = "Xoá khỏi playlist", tint = TextMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

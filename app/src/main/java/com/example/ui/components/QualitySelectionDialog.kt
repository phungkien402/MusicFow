package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.compose.ui.window.Dialog
import com.example.data.downloader.ParsedLinkMetadata
import com.example.data.model.AudioQuality
import com.example.data.model.Playlist
import com.example.data.model.SourceType
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySelectionDialog(
    metadata: ParsedLinkMetadata,
    playlists: List<Playlist>,
    selectedQuality: AudioQuality,
    selectedPlaylistId: Long?,
    onQualitySelected: (AudioQuality) -> Unit,
    onPlaylistSelected: (Long?) -> Unit,
    onConfirmDownload: (customTitle: String, customArtist: String) -> Unit,
    onCreateNewPlaylistClick: () -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(metadata) { mutableStateOf(metadata.title) }
    var artist by remember(metadata) { mutableStateOf(metadata.artist) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .testTag("quality_selection_dialog"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header: Source Badge & Dialog Title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Tuỳ chọn tải nhạc",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Chọn định dạng âm thanh & Playlist",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }

                    // Source Tag
                    val (sourceColor, sourceText) = when (metadata.source) {
                        SourceType.YOUTUBE -> Pair(YouTubeRed, "YouTube")
                        SourceType.TIKTOK -> Pair(TikTokPink, "TikTok")
                        else -> Pair(NeonCyan, "Web Audio")
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = sourceColor.copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, sourceColor.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = sourceText,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = sourceColor
                        )
                    }
                }

                // Song Info input fields (editable)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Tên bài hát") },
                        leadingIcon = { Icon(Icons.Default.MusicNote, contentDescription = null, tint = NeonCyan) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_song_title"),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = DarkCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    OutlinedTextField(
                        value = artist,
                        onValueChange = { artist = it },
                        label = { Text("Nghệ sĩ / Kênh") },
                        leadingIcon = { Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = NeonPurple) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_artist_name"),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonPurple,
                            unfocusedBorderColor = DarkCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }

                HorizontalDivider(color = DarkCardBorder)

                // Quality Options Menu
                Text(
                    text = "1. CHỌN CHẤT LƯỢNG ÂM THANH",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = NeonCyan
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AudioQuality.QUALITIES.forEach { quality ->
                        val isSelected = quality.id == selectedQuality.id
                        val borderColor = if (isSelected) NeonCyan else DarkCardBorder
                        val bgColor = if (isSelected) NeonCyan.copy(alpha = 0.12f) else DarkSurfaceVariant

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(bgColor)
                                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                                .clickable { onQualitySelected(quality) }
                                .padding(12.dp)
                                .testTag("quality_item_${quality.id}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onQualitySelected(quality) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = NeonCyan,
                                    unselectedColor = TextSecondary
                                )
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = quality.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) NeonCyan else TextPrimary
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (quality.format.isLossless) NeonPink.copy(alpha = 0.2f) else NeonPurple.copy(alpha = 0.2f)
                                    ) {
                                        Text(
                                            text = quality.badge,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (quality.format.isLossless) NeonPink else NeonPurple
                                        )
                                    }
                                }
                                Text(
                                    text = quality.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = DarkCardBorder)

                // Playlist Selection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "2. THÊM VÀO PLAYLIST",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = NeonPurple
                    )

                    TextButton(
                        onClick = onCreateNewPlaylistClick,
                        modifier = Modifier.testTag("create_playlist_in_dialog_btn")
                    ) {
                        Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("+ Tạo mới", color = NeonCyan, style = MaterialTheme.typography.labelMedium)
                    }
                }

                // Playlist chips / radio
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Option: No playlist (direct to library)
                    val isNoneSelected = selectedPlaylistId == null
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isNoneSelected) NeonPurple.copy(alpha = 0.12f) else DarkSurfaceVariant)
                            .border(1.dp, if (isNoneSelected) NeonPurple else DarkCardBorder, RoundedCornerShape(10.dp))
                            .clickable { onPlaylistSelected(null) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("playlist_none_option"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isNoneSelected,
                            onClick = { onPlaylistSelected(null) },
                            colors = RadioButtonDefaults.colors(selectedColor = NeonPurple)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Chỉ lưu vào Thư viện chính",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                    }

                    // Existing playlists
                    playlists.forEach { playlist ->
                        val isSelected = selectedPlaylistId == playlist.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) NeonPurple.copy(alpha = 0.12f) else DarkSurfaceVariant)
                                .border(1.dp, if (isSelected) NeonPurple else DarkCardBorder, RoundedCornerShape(10.dp))
                                .clickable { onPlaylistSelected(playlist.id) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("playlist_option_${playlist.id}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onPlaylistSelected(playlist.id) },
                                colors = RadioButtonDefaults.colors(selectedColor = NeonPurple)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) NeonPurple else TextPrimary
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "(${playlist.songCount} bài)",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cancel_download_dialog_btn"),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                    ) {
                        Text("Huỷ", color = TextSecondary)
                    }

                    Button(
                        onClick = { onConfirmDownload(title, artist) },
                        modifier = Modifier
                            .weight(1.5f)
                            .testTag("confirm_download_btn"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF031A24))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Tải về ngay",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF031A24)
                        )
                    }
                }
            }
        }
    }
}

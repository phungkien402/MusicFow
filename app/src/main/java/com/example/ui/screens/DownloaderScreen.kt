package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DownloadStatus
import com.example.data.model.DownloadTask
import com.example.data.model.Song
import com.example.data.model.SourceType
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloaderScreen(
    inputUrl: String,
    isAnalyzing: Boolean,
    activeDownloads: List<DownloadTask>,
    allSongs: List<Song>,
    onUrlChange: (String) -> Unit,
    onAnalyzeClick: () -> Unit,
    onRemoveDownloadTask: (String) -> Unit,
    onPlayCompletedSong: (Song) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current

    val sampleLinks = listOf(
        Pair("🔥 TikTok Viral Beat", "https://vt.tiktok.com/ZS6sample123"),
        Pair("🎵 YouTube Lofi Chill", "https://youtu.be/jfKfPfyJRdk"),
        Pair("⚡ Cyberpunk Bass EDM", "https://youtube.com/watch?v=sample_edm_777"),
        Pair("🎸 Acoustic Sunset Guitar", "https://youtu.be/sample_acoustic_strings")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .testTag("downloader_screen")
    ) {
        // Screen Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                text = "Tải nhạc từ Link",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = NeonCyan
            )
            Text(
                text = "Hỗ trợ tách âm thanh YouTube, TikTok & Web URL",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            // Hero Card
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = DarkSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        YouTubeRed.copy(alpha = 0.08f),
                                        NeonPurple.copy(alpha = 0.08f),
                                        TikTokCyan.copy(alpha = 0.08f)
                                    )
                                )
                            )
                            .padding(18.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = YouTubeRed.copy(alpha = 0.2f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, YouTubeRed)
                                ) {
                                    Text(
                                        text = "YouTube",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = YouTubeRed
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = TikTokPink.copy(alpha = 0.2f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, TikTokPink)
                                ) {
                                    Text(
                                        text = "TikTok",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = TikTokPink
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = NeonCyan.copy(alpha = 0.2f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan)
                                ) {
                                    Text(
                                        text = "Direct Audio",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = NeonCyan
                                    )
                                }
                            }

                            Text(
                                text = "Dán liên kết bất kỳ để tải âm thanh ngoại tuyến với tuỳ chọn chất lượng (MP3 320k, M4A 256k, FLAC Lossless, WAV) và tự động xếp vào Playlist mong muốn!",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }

            // Input Box Section
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = DarkSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = inputUrl,
                            onValueChange = onUrlChange,
                            placeholder = { Text("Dán link YouTube hoặc TikTok vào đây...", fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Link, contentDescription = null, tint = NeonCyan)
                            },
                            trailingIcon = {
                                if (inputUrl.isNotEmpty()) {
                                    IconButton(onClick = { onUrlChange("") }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Xoá", tint = TextSecondary)
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("url_input_field"),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = DarkCardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Paste from Clipboard Button
                            OutlinedButton(
                                onClick = {
                                    val clip = clipboardManager.getText()?.text
                                    if (!clip.isNullOrBlank()) {
                                        onUrlChange(clip.trim())
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("paste_clipboard_btn"),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Dán link", color = TextPrimary)
                            }

                            // Analyze & Select Quality Button
                            Button(
                                onClick = onAnalyzeClick,
                                enabled = !isAnalyzing && inputUrl.isNotBlank(),
                                modifier = Modifier
                                    .weight(1.4f)
                                    .testTag("analyze_url_btn"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                            ) {
                                if (isAnalyzing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = Color(0xFF031A24),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Đang xử lý...", color = Color(0xFF031A24), fontWeight = FontWeight.Bold)
                                } else {
                                    Icon(Icons.Default.Tune, contentDescription = null, tint = Color(0xFF031A24), modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Chọn chất lượng", color = Color(0xFF031A24), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Quick Samples Chips
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "GỢI Ý LIÊN KẾT MẪU",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary,
                        letterSpacing = 1.sp
                    )

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sampleLinks) { (label, link) ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = DarkSurfaceVariant,
                                border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onUrlChange(link) }
                                    .testTag("sample_link_${label.take(8)}")
                            ) {
                                Text(
                                    text = label,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }
            }

            // Download Queue Section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "TIẾN TRÌNH & LỊCH SỬ TẢI",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = NeonPurple,
                        letterSpacing = 1.sp
                    )

                    Text(
                        text = "${activeDownloads.size} mục",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            if (activeDownloads.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = DarkSurface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.DownloadDone, contentDescription = null, tint = TextMuted, modifier = Modifier.size(32.dp))
                            Text(
                                text = "Chưa có tiến trình tải nào",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            Text(
                                text = "Nhập liên kết ở trên để bắt đầu tải bài hát về bộ nhớ thiết bị.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(activeDownloads, key = { it.id }) { task ->
                    DownloadTaskCard(
                        task = task,
                        onRemove = { onRemoveDownloadTask(task.id) },
                        onPlayClick = {
                            val matchedSong = allSongs.find { s -> s.id == task.completedSongId || s.title == task.title }
                            if (matchedSong != null) {
                                onPlayCompletedSong(matchedSong)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DownloadTaskCard(
    task: DownloadTask,
    onRemove: () -> Unit,
    onPlayClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp))
            .testTag("download_task_${task.id}"),
        color = DarkSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Source Icon & Title
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val sourceColor = when (task.source) {
                            SourceType.YOUTUBE -> YouTubeRed
                            SourceType.TIKTOK -> TikTokPink
                            else -> NeonCyan
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = sourceColor.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = task.source.displayName,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = sourceColor,
                                fontSize = 10.sp
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = NeonPurple.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = task.quality.badge,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = NeonPurple,
                                fontSize = 10.sp
                            )
                        }

                        if (task.targetPlaylistName != null) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = DarkSurfaceVariant
                            ) {
                                Text(
                                    text = "📁 ${task.targetPlaylistName}",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = task.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Status or Action Button
                if (task.status == DownloadStatus.COMPLETED) {
                    IconButton(
                        onClick = onPlayClick,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(NeonCyan.copy(alpha = 0.2f))
                            .testTag("play_downloaded_${task.id}")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Phát bài đã tải", tint = NeonCyan)
                    }
                } else {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Huỷ", tint = TextMuted)
                    }
                }
            }

            // Progress Bar
            if (task.status == DownloadStatus.DOWNLOADING) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { task.progressPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = NeonCyan,
                        trackColor = DarkSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Đang tải: ${task.progressPercent}% • ${task.downloadSpeedText}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonCyan
                        )
                        Text(
                            text = task.quality.format.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            } else if (task.status == DownloadStatus.COMPLETED) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldGreen, modifier = Modifier.size(16.dp))
                    Text(
                        text = "Đã tải xong & lưu ngoại tuyến",
                        style = MaterialTheme.typography.bodySmall,
                        color = EmeraldGreen,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else if (task.status == DownloadStatus.FAILED) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = NeonPink, modifier = Modifier.size(16.dp))
                    Text(
                        text = task.errorMessage ?: "Tải thất bại",
                        style = MaterialTheme.typography.bodySmall,
                        color = NeonPink
                    )
                }
            }
        }
    }
}

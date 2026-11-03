package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.example.data.model.Song
import com.example.data.model.SourceType
import com.example.data.player.EqualizerPreset
import com.example.data.player.RepeatMode
import com.example.data.player.ReverbMode
import com.example.ui.theme.*
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(
    song: Song,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    isShuffle: Boolean,
    repeatMode: RepeatMode,
    playbackSpeed: Float,
    equalizerPreset: EqualizerPreset,
    sleepTimerMinutes: Int?,
    isStopAfterSong: Boolean = false,
    crossfadeSeconds: Int = 0,
    playQueue: List<Song> = emptyList(),
    currentQueueIndex: Int = 0,
    customBandLevels: List<Float> = listOf(0f, 0f, 0f, 0f, 0f),
    customBassBoost: Float = 0f,
    customVirtualizer: Float = 0f,
    customTrebleClarity: Float = 0f,
    loudnessBoosterEnabled: Boolean = false,
    reverbMode: ReverbMode = ReverbMode.OFF,
    antiClippingEnabled: Boolean = true,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onEqualizerChange: (EqualizerPreset) -> Unit,
    onAntiClippingChange: (Boolean) -> Unit = {},
    onCustomBandChange: (Int, Float) -> Unit = { _, _ -> },
    onCustomBassBoostChange: (Float) -> Unit = {},
    onCustomVirtualizerChange: (Float) -> Unit = {},
    onCustomTrebleClarityChange: (Float) -> Unit = {},
    onLoudnessBoosterChange: (Boolean) -> Unit = {},
    onReverbModeChange: (ReverbMode) -> Unit = {},
    onResetCustomEqualizer: () -> Unit = {},
    onSleepTimerChange: (Int?) -> Unit,
    onCrossfadeChange: (Int) -> Unit = {},
    onPlayQueueSong: (Int) -> Unit = {},
    onRemoveFromQueue: (Int) -> Unit = {},
    onMoveQueueItem: (Int, Int) -> Unit = { _, _ -> },
    onClearQueueExceptCurrent: () -> Unit = {},
    onToggleFavorite: (Song) -> Unit,
    onAddToPlaylistClick: (Song) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }

    var isUserScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableStateOf(0f) }

    var showSpeedDialog by remember { mutableStateOf(false) }
    var showEqualizerDialog by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showCrossfadeDialog by remember { mutableStateOf(false) }
    var showQueueDialog by remember { mutableStateOf(false) }

    // Vinyl rotation animation - only ticks when playing to save CPU/GPU cycles
    val infiniteTransition = rememberInfiniteTransition(label = "vinyl_rotate")
    val rawRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "rotation_angle"
    )
    val rotation = if (isPlaying) rawRotation else 0f

    // Visualizer bars animation - only updates when playing to minimize recompositions
    val waveTransition = rememberInfiniteTransition(label = "wave_bars")
    val rawWavePhase by waveTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "wave_phase"
    )
    val wavePhase = if (isPlaying) rawWavePhase else 0.2f

    val currentSliderValue = if (isUserScrubbing) {
        scrubPosition
    } else {
        if (durationMs > 0) currentPositionMs.toFloat() else 0f
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .testTag("now_playing_screen")
    ) {
        val isCompactScreen = maxHeight < 640.dp
        // Prominent, full-size vinyl record: fills ~78% of phone width (typically 280dp - 315dp)
        // filling the upper half elegantly and eliminating the empty void at the bottom
        val discSize = if (isCompactScreen) {
            (maxHeight * 0.28f).coerceIn(160.dp, 220.dp)
        } else {
            (maxWidth * 0.78f).coerceIn(260.dp, 320.dp).coerceAtMost(maxHeight * 0.38f)
        }
        // Prominent edge-to-edge Album Art Background - Scaled to fill screen and sharper
        if (!song.thumbnailUrl.isNullOrBlank()) {
            AsyncImage(
                model = song.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(14.dp)
                    .alpha(0.85f)
            )
        } else {
            // Ambient Music Glow Background for tracks without cover image
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            listOf(
                                NeonPurple.copy(alpha = 0.40f),
                                NeonCyan.copy(alpha = 0.22f),
                                DarkBackground
                            ),
                            radius = 1000f
                        )
                    )
            )
        }

        // Ambient gradient overlay: clear in center so artwork is vivid and distinct, soft darkening at top & bottom
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            DarkBackground.copy(alpha = 0.40f),
                            Color(0x12090B10), // transparent center so background art shines through large and clear
                            DarkBackground.copy(alpha = 0.75f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 12.dp)
                .then(
                    if (isCompactScreen) Modifier.verticalScroll(rememberScrollState())
                    else Modifier
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (isCompactScreen) Arrangement.spacedBy(8.dp) else Arrangement.SpaceBetween
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp).testTag("close_now_playing_btn")
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Thu gọn",
                        tint = TextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Audio Quality & Hi-Fi Mode Indicator Pill (Clickable -> Opens Studio Equalizer)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = DarkSurfaceVariant,
                    border = BorderStroke(1.dp, if (equalizerPreset == EqualizerPreset.BALANCED) DarkCardBorder else NeonCyan.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { showEqualizerDialog = true }
                        .testTag("audio_quality_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = if (equalizerPreset == EqualizerPreset.BALANCED) NeonCyan else NeonPink,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = equalizerPreset.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = NeonCyan.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "Hi-Fi",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                color = NeonCyan
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Queue Button
                    IconButton(
                        onClick = { showQueueDialog = true },
                        modifier = Modifier.size(36.dp).testTag("now_playing_queue_btn")
                    ) {
                        BadgeBox(
                            badge = {
                                if (playQueue.isNotEmpty()) {
                                    Badge(containerColor = NeonCyan) {
                                        Text("${playQueue.size}", fontSize = 8.sp, color = Color(0xFF031A24))
                                    }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.QueueMusic,
                                contentDescription = "Hàng đợi phát",
                                tint = TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Add to Playlist Button
                    IconButton(
                        onClick = { onAddToPlaylistClick(song) },
                        modifier = Modifier.size(36.dp).testTag("now_playing_add_playlist_btn")
                    ) {
                        Icon(
                            Icons.Default.PlaylistAdd,
                            contentDescription = "Thêm vào playlist",
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Prominent Full-Sized Vinyl Record (Realistic proportions, elegant neon glow)
            Box(
                modifier = Modifier.size(discSize + 16.dp),
                contentAlignment = Alignment.Center
            ) {
                    // Ambient radial glow behind disc
                    Box(
                        modifier = Modifier
                            .size(discSize + 10.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        NeonCyan.copy(alpha = 0.35f),
                                        NeonPurple.copy(alpha = 0.25f),
                                        Color.Transparent
                                    )
                                )
                            )
                            .blur(20.dp)
                    )

                    // Spinning Vinyl Record Cover Art
                    Box(
                        modifier = Modifier
                            .size(discSize)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        NeonPurple.copy(alpha = 0.25f),
                                        Color(0xFF1E2235),
                                        Color(0xFF0D0F18)
                                    )
                                )
                            )
                            .border(2.5.dp, NeonCyan.copy(alpha = 0.6f), CircleShape)
                            .rotate(rotation),
                        contentAlignment = Alignment.Center
                    ) {
                        // Vinyl grooves rings
                        Box(
                            modifier = Modifier
                                .size(discSize * 0.82f)
                                .border(1.dp, Color(0xFF2E344E), CircleShape)
                        )
                        Box(
                            modifier = Modifier
                                .size(discSize * 0.64f)
                                .border(1.dp, Color(0xFF38405F), CircleShape)
                        )

                        // Center Label
                        Box(
                            modifier = Modifier
                                .size(discSize * 0.44f)
                                .clip(CircleShape)
                                .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!song.thumbnailUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = song.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.linearGradient(
                                                listOf(NeonCyan, NeonPurple, NeonPink)
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(discSize * 0.18f)
                                    )
                                }
                            }
                        }

                        // Center Spindle
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(DarkBackground)
                                .border(1.5.dp, Color.White, CircleShape)
                        )
                    }
                }

            // Animated Visualizer Bars (Dancing with music)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Bottom
            ) {
                val barHeights = listOf(0.4f, 0.7f, 0.9f, 0.5f, 0.8f, 1.0f, 0.6f, 0.85f, 0.45f, 0.75f, 0.35f, 0.65f)
                barHeights.forEachIndexed { index, baseHeight ->
                    val factor = if (isPlaying) {
                        (baseHeight * (0.4f + 0.6f * ((wavePhase + index * 0.1f) % 1.0f))).coerceIn(0.15f, 1.0f)
                    } else {
                        0.2f
                    }
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight(factor)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(NeonCyan, NeonPurple)
                                )
                            )
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                }
            }

            // Song Info & Format Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = song.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (song.format.isLossless) NeonPink.copy(alpha = 0.2f) else NeonCyan.copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (song.format.isLossless) NeonPink else NeonCyan)
                        ) {
                            Text(
                                text = song.format.displayName,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (song.format.isLossless) NeonPink else NeonCyan,
                                fontSize = 9.sp
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = DarkSurfaceVariant
                        ) {
                            Text(
                                text = song.bitrate,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                fontSize = 9.sp
                            )
                        }
                    }
                }

                // Favorite Heart Button
                IconButton(
                    onClick = { onToggleFavorite(song) },
                    modifier = Modifier.size(36.dp).testTag("now_playing_fav_btn")
                ) {
                    Icon(
                        imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Yêu thích",
                        tint = if (song.isFavorite) NeonPink else TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Playback Seekbar & Timestamps & Tua 10s
            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = currentSliderValue,
                    onValueChange = {
                        isUserScrubbing = true
                        scrubPosition = it
                    },
                    onValueChangeFinished = {
                        isUserScrubbing = false
                        onSeekTo(scrubPosition.toLong())
                    },
                    valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = NeonCyan,
                        activeTrackColor = NeonCyan,
                        inactiveTrackColor = DarkSurfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .testTag("audio_seekbar")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val currentSec = (currentSliderValue / 1000).toInt()
                    val totalSec = (durationMs / 1000).toInt()
                    Text(
                        text = "%d:%02d".format(currentSec / 60, currentSec % 60),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )

                    // Quick 10s seek buttons placed in center under seekbar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        IconButton(
                            onClick = onSkipBackward,
                            modifier = Modifier.size(32.dp).testTag("skip_backward_10s")
                        ) {
                            Icon(
                                Icons.Default.Replay10,
                                contentDescription = "Tua lùi 10s",
                                tint = NeonCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = onSkipForward,
                            modifier = Modifier.size(32.dp).testTag("skip_forward_10s")
                        ) {
                            Icon(
                                Icons.Default.Forward10,
                                contentDescription = "Tua tới 10s",
                                tint = NeonCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Text(
                        text = "%d:%02d".format(totalSec / 60, totalSec % 60),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            // Primary Playback Controls: Shuffle, Prev, Play/Pause, Next, Repeat
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Shuffle
                IconButton(
                    onClick = onToggleShuffle,
                    modifier = Modifier.size(40.dp).testTag("shuffle_btn")
                ) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "Phát ngẫu nhiên",
                        tint = if (isShuffle) NeonCyan else TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Previous
                IconButton(
                    onClick = onPreviousClick,
                    modifier = Modifier.size(44.dp).testTag("prev_btn")
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Bài trước",
                        tint = TextPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Big Play / Pause Neon Button
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(NeonCyan, NeonPurple)
                            )
                        )
                        .clickable { onPlayPauseClick() }
                        .testTag("main_play_pause_btn"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Tạm dừng" else "Phát",
                        tint = Color(0xFF031A24),
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Next
                IconButton(
                    onClick = onNextClick,
                    modifier = Modifier.size(44.dp).testTag("next_btn")
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Bài tiếp theo",
                        tint = TextPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Repeat Mode
                IconButton(
                    onClick = onCycleRepeat,
                    modifier = Modifier.size(40.dp).testTag("repeat_btn")
                ) {
                    Icon(
                        imageVector = when (repeatMode) {
                            RepeatMode.ONE -> Icons.Default.RepeatOne
                            RepeatMode.ALL -> Icons.Default.Repeat
                            RepeatMode.OFF -> Icons.Default.Repeat
                        },
                        contentDescription = repeatMode.label,
                        tint = if (repeatMode != RepeatMode.OFF) NeonCyan else TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Extra Audio Enhancements Bar: Speed, EQ, Timer, Crossfade (Visible immediately without scrolling)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(DarkSurfaceVariant)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Playback Speed
                TextButton(
                    onClick = { showSpeedDialog = true },
                    modifier = Modifier.testTag("speed_btn")
                ) {
                    Text(
                        text = "${playbackSpeed}x",
                        color = NeonCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                // Equalizer Preset
                IconButton(
                    onClick = { showEqualizerDialog = true },
                    modifier = Modifier.size(38.dp).testTag("equalizer_btn")
                ) {
                    Icon(Icons.Default.GraphicEq, contentDescription = "Bộ chỉnh âm Equalizer", tint = NeonPurple, modifier = Modifier.size(20.dp))
                }

                // Sleep Timer
                IconButton(
                    onClick = { showSleepTimerDialog = true },
                    modifier = Modifier.size(38.dp).testTag("sleep_timer_btn")
                ) {
                    val isTimerActive = isStopAfterSong || sleepTimerMinutes != null
                    BadgeBox(
                        badge = {
                            if (isStopAfterSong) {
                                Badge(containerColor = NeonPink) {
                                    Text("1", fontSize = 8.sp)
                                }
                            } else if (sleepTimerMinutes != null) {
                                Badge(containerColor = NeonPink) {
                                    Text("${sleepTimerMinutes}m", fontSize = 8.sp)
                                }
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.Bedtime,
                            contentDescription = "Hẹn giờ tắt",
                            tint = if (isTimerActive) NeonPink else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Crossfade
                IconButton(
                    onClick = { showCrossfadeDialog = true },
                    modifier = Modifier.size(38.dp).testTag("crossfade_btn")
                ) {
                    BadgeBox(
                        badge = {
                            if (crossfadeSeconds > 0) {
                                Badge(containerColor = NeonCyan) {
                                    Text("${crossfadeSeconds}s", fontSize = 8.sp, color = Color(0xFF031A24))
                                }
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = "Chuyển bài mượt mà Crossfade",
                            tint = if (crossfadeSeconds > 0) NeonCyan else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    // Playback Speed Dialog
    if (showSpeedDialog) {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        Dialog(onDismissRequest = { showSpeedDialog = false }) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Tốc độ phát", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(modifier = Modifier.height(12.dp))
                    speeds.forEach { speed ->
                        val isSelected = playbackSpeed == speed
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) NeonCyan.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable {
                                    onSpeedChange(speed)
                                    showSpeedDialog = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${speed}x ${if (speed == 1.0f) "(Chuẩn)" else ""}",
                                color = if (isSelected) NeonCyan else TextPrimary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }

    // Studio Audio Quality & Equalizer Hub Dialog
    if (showEqualizerDialog) {
        Dialog(onDismissRequest = { showEqualizerDialog = false }) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .testTag("audio_quality_dialog")
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Chất lượng Âm thanh & EQ",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                            Text(
                                text = "DSP Hi-Fi Studio • Âm thanh vòm 3D • Chống rè",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                        IconButton(onClick = { showEqualizerDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Đóng", tint = TextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Active Preset Description Banner
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = NeonCyan.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Cấu hình: ${equalizerPreset.label}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonCyan
                                )
                                Text(
                                    text = equalizerPreset.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Preset Chips
                    Text(
                        text = "Cấu hình âm thanh chất lượng cao",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // FlowRow of presets
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        EqualizerPreset.entries.forEach { preset ->
                            val isSelected = equalizerPreset == preset
                            FilterChip(
                                selected = isSelected,
                                onClick = { onEqualizerChange(preset) },
                                label = {
                                    Text(
                                        text = preset.label,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 11.sp
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = when (preset) {
                                        EqualizerPreset.HIFI_MASTER -> NeonCyan
                                        EqualizerPreset.TIKTOK_BOOST -> NeonPink
                                        EqualizerPreset.CUSTOM -> NeonCyan
                                        else -> NeonPurple
                                    },
                                    selectedLabelColor = if (preset == EqualizerPreset.HIFI_MASTER || preset == EqualizerPreset.CUSTOM) Color.Black else Color.White,
                                    containerColor = DarkSurfaceVariant,
                                    labelColor = TextPrimary
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = DarkCardBorder)
                    Spacer(modifier = Modifier.height(14.dp))

                    // ADVANCED AUDIO ENHANCEMENTS SECTION
                    Text(
                        text = "Cải thiện Âm thanh Chuyên sâu",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // 1. 3D Spatial Surround Sound (Virtualizer)
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SurroundSound, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Âm thanh vòm 3D (Spatial Audio)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                            Text(
                                text = "${(customVirtualizer * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = NeonCyan
                            )
                        }
                        Text(
                            text = "Mở rộng âm trường stereo đa chiều, mang lại không gian 3D sống động như sân khấu thực tế",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontSize = 10.5.sp
                        )
                        Slider(
                            value = customVirtualizer,
                            onValueChange = { onCustomVirtualizerChange(it) },
                            valueRange = 0.0f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = NeonCyan,
                                activeTrackColor = NeonCyan,
                                inactiveTrackColor = DarkSurfaceVariant
                            )
                        )
                    }

                    // 2. Deep Sub-Bass (Bass Boost)
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Bolt, contentDescription = null, tint = NeonPink, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Siêu trầm Sub-Bass (Bass Boost)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                            Text(
                                text = "${(customBassBoost * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = NeonPink
                            )
                        }
                        Text(
                            text = "Kích hoạt dải trầm sâu, đầm chắc cho nhạc EDM, Vinahouse & TikTok",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontSize = 10.5.sp
                        )
                        Slider(
                            value = customBassBoost,
                            onValueChange = { onCustomBassBoostChange(it) },
                            valueRange = 0.0f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = NeonPink,
                                activeTrackColor = NeonPink,
                                inactiveTrackColor = DarkSurfaceVariant
                            )
                        )
                    }

                    // 3. HD Crystal Clarity (Treble Sparkle)
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Độ trong trẻo HD (Crystal Clarity)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                            Text(
                                text = "${(customTrebleClarity * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = NeonPurple
                            )
                        }
                        Text(
                            text = "Tôn rõ giọng hát ca sĩ & hài âm nhạc cụ, làm sáng âm thanh mà không chói tai",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontSize = 10.5.sp
                        )
                        Slider(
                            value = customTrebleClarity,
                            onValueChange = { onCustomTrebleClarityChange(it) },
                            valueRange = 0.0f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = NeonPurple,
                                activeTrackColor = NeonPurple,
                                inactiveTrackColor = DarkSurfaceVariant
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // 4. Dynamic Loudness Booster Switch
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = if (loudnessBoosterEnabled) DarkSurfaceVariant else DarkSurface),
                        border = BorderStroke(1.dp, if (loudnessBoosterEnabled) NeonPink.copy(alpha = 0.5f) else DarkCardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.VolumeUp, contentDescription = null, tint = if (loudnessBoosterEnabled) NeonPink else TextSecondary, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Khuếch đại năng lượng âm thanh (Dynamic Booster)",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (loudnessBoosterEnabled) NeonPink else TextPrimary
                                    )
                                }
                                Text(
                                    text = "Nâng công suất âm thanh toàn dải căng đầy và có lực bằng thuật toán nén dải động",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    fontSize = 10.sp
                                )
                            }
                            Switch(
                                checked = loudnessBoosterEnabled,
                                onCheckedChange = { onLoudnessBoosterChange(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = NeonPink,
                                    checkedTrackColor = NeonPink.copy(alpha = 0.35f),
                                    uncheckedThumbColor = TextSecondary,
                                    uncheckedTrackColor = DarkCardBorder
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 5. Anti-Clipping Switch Toggle Card
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = if (antiClippingEnabled) DarkSurfaceVariant else DarkSurface),
                        border = BorderStroke(1.dp, if (antiClippingEnabled) NeonCyan.copy(alpha = 0.5f) else DarkCardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Security,
                                        contentDescription = null,
                                        tint = if (antiClippingEnabled) NeonCyan else TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Chống rè & Méo tiếng (Anti-Clipping Limiter)",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (antiClippingEnabled) NeonCyan else TextPrimary
                                    )
                                }
                                Text(
                                    text = "Tự động duy trì Headroom âm lượng, loại bỏ hoàn toàn rè xé tiếng khi bật âm lượng lớn",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    fontSize = 10.sp
                                )
                            }
                            Switch(
                                checked = antiClippingEnabled,
                                onCheckedChange = { onAntiClippingChange(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = NeonCyan,
                                    checkedTrackColor = NeonCyan.copy(alpha = 0.35f),
                                    uncheckedThumbColor = TextSecondary,
                                    uncheckedTrackColor = DarkCardBorder
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 6. Acoustic Reverb Ambience
                    Text(
                        text = "🏛️ Không gian âm thanh Reverb",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ReverbMode.entries.forEach { mode ->
                            val isSelected = reverbMode == mode
                            FilterChip(
                                selected = isSelected,
                                onClick = { onReverbModeChange(mode) },
                                label = { Text(mode.label, fontSize = 10.5.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = NeonPurple,
                                    selectedLabelColor = Color.White,
                                    containerColor = DarkSurfaceVariant,
                                    labelColor = TextPrimary
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = DarkCardBorder)
                    Spacer(modifier = Modifier.height(14.dp))

                    // Custom 5-Band Slider Section
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Tùy chỉnh 5 dải tần (Custom EQ)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = NeonCyan
                            )
                        }

                        TextButton(onClick = onResetCustomEqualizer) {
                            Text("Đặt lại", color = TextSecondary, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    val bandLabels = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")
                    val bandDescriptions = listOf("Sub Bass", "Bass", "Mid", "High Mid", "Treble")

                    bandLabels.forEachIndexed { index, freqLabel ->
                        val currentLevel = customBandLevels.getOrNull(index) ?: 0f
                        val db = (currentLevel * 12).toInt()
                        val dbText = if (db > 0) "+$db dB" else "$db dB"

                        Column(modifier = Modifier.padding(vertical = 2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "$freqLabel (${bandDescriptions[index]})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = dbText,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (db > 0) NeonCyan else if (db < 0) NeonPink else TextSecondary
                                )
                            }
                            Slider(
                                value = currentLevel,
                                onValueChange = { newVal -> onCustomBandChange(index, newVal) },
                                valueRange = -1.0f..1.0f,
                                colors = SliderDefaults.colors(
                                    thumbColor = NeonCyan,
                                    activeTrackColor = NeonCyan,
                                    inactiveTrackColor = DarkSurfaceVariant
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { showEqualizerDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonPurple)
                    ) {
                        Text("Áp dụng & Đóng", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // Sleep Timer Dialog
    if (showSleepTimerDialog) {
        val timerOptions = listOf(
            Triple(null, "Tắt hẹn giờ", "Không hẹn giờ tắt"),
            Triple(-1, "Hết bài hát hiện tại", "Tắt nhạc ngay khi bài đang nghe phát xong"),
            Triple(5, "5 phút", "Thích hợp nghe nhanh hoặc thử nghiệm"),
            Triple(10, "10 phút", "Thư giãn ngắn"),
            Triple(15, "15 phút", "Hẹn giờ 15 phút"),
            Triple(30, "30 phút", "Nghe trước khi ngủ (30 phút)"),
            Triple(45, "45 phút", "Ngủ sâu (45 phút)"),
            Triple(60, "60 phút", "1 tiếng nghe nhạc"),
            Triple(90, "90 phút", "1.5 tiếng nghe nhạc")
        )
        Dialog(onDismissRequest = { showSleepTimerDialog = false }) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Hẹn giờ tắt nhạc",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        if (isStopAfterSong || sleepTimerMinutes != null) {
                            Text(
                                text = if (isStopAfterSong) "Đang hẹn: Hết bài" else "Còn: ${sleepTimerMinutes}m",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonPink,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(timerOptions) { (mins, label, sub) ->
                            val isSelected = when (mins) {
                                -1 -> isStopAfterSong
                                null -> !isStopAfterSong && sleepTimerMinutes == null
                                else -> !isStopAfterSong && sleepTimerMinutes == mins
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) NeonPink.copy(alpha = 0.15f) else Color.Transparent)
                                    .clickable {
                                        onSleepTimerChange(mins)
                                        showSleepTimerDialog = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) NeonPink else TextPrimary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = sub,
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = NeonPink,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Play Queue Dialog
    if (showQueueDialog) {
        PlayQueueDialog(
            queue = playQueue,
            currentIndex = currentQueueIndex,
            onSongClick = { idx ->
                onPlayQueueSong(idx)
            },
            onRemoveSong = { idx ->
                onRemoveFromQueue(idx)
            },
            onMoveUp = { idx ->
                onMoveQueueItem(idx, idx - 1)
            },
            onMoveDown = { idx ->
                onMoveQueueItem(idx, idx + 1)
            },
            onClearQueue = {
                onClearQueueExceptCurrent()
            },
            onDismiss = { showQueueDialog = false }
        )
    }

    // Crossfade Dialog
    if (showCrossfadeDialog) {
        CrossfadeDialog(
            currentSeconds = crossfadeSeconds,
            onSelectSeconds = { secs ->
                onCrossfadeChange(secs)
            },
            onDismiss = { showCrossfadeDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BadgeBox(
    badge: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Box {
        content()
        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            badge()
        }
    }
}

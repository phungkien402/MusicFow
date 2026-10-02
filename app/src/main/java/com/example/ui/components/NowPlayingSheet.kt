package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.example.data.model.Song
import com.example.data.model.SourceType
import com.example.data.player.EqualizerPreset
import com.example.data.player.RepeatMode
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
    customBandLevels: List<Float> = listOf(0f, 0f, 0f, 0f, 0f),
    customBassBoost: Float = 0f,
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
    onCustomBandChange: (Int, Float) -> Unit = { _, _ -> },
    onCustomBassBoostChange: (Float) -> Unit = {},
    onResetCustomEqualizer: () -> Unit = {},
    onSleepTimerChange: (Int?) -> Unit,
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .testTag("now_playing_screen")
    ) {
        // Blurred Album Art Background - Scale according to width (FillWidth) touching edges
        if (!song.thumbnailUrl.isNullOrBlank()) {
            AsyncImage(
                model = song.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .blur(28.dp)
                    .alpha(0.65f)
            )
        }

        // Ambient gradient overlay: clear in center behind vinyl disc, fading gently at top and bottom
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            DarkBackground.copy(alpha = 0.82f),
                            Color(0x22090B10), // transparent center so blurred art shines through full-width behind vinyl
                            DarkBackground.copy(alpha = 0.90f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_now_playing_btn")
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Thu gọn",
                        tint = TextPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "ĐANG PHÁT TỪ",
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.5.sp,
                        color = TextSecondary
                    )
                    Text(
                        text = song.album,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan
                    )
                }

                IconButton(
                    onClick = { onAddToPlaylistClick(song) },
                    modifier = Modifier.testTag("now_playing_add_playlist_btn")
                ) {
                    Icon(
                        Icons.Default.PlaylistAdd,
                        contentDescription = "Thêm vào playlist",
                        tint = TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Vinyl Record floating seamlessly over full-width blurred background
            Box(
                modifier = Modifier.size(240.dp),
                contentAlignment = Alignment.Center
            ) {
                // Ambient radial glow behind disc
                Box(
                    modifier = Modifier
                        .size(230.dp)
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
                        .size(220.dp)
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
                        .border(2.dp, NeonCyan.copy(alpha = 0.5f), CircleShape)
                        .rotate(rotation),
                    contentAlignment = Alignment.Center
                ) {
                    // Vinyl grooves rings
                    Box(
                        modifier = Modifier
                            .size(175.dp)
                            .border(1.dp, Color(0xFF2E344E), CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .size(130.dp)
                            .border(1.dp, Color(0xFF38405F), CircleShape)
                    )

                    // Center Label
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .border(2.dp, Color.White, CircleShape),
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
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }

                    // Center Spindle
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(DarkBackground)
                            .border(1.dp, Color.White, CircleShape)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Animated Visualizer Bars (Dancing with music)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp),
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
                            .width(4.dp)
                            .fillMaxHeight(factor)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(NeonCyan, NeonPurple)
                                )
                            )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Song Info & Format Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Audio Specs Badges: Format, Bitrate, Source
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (song.format.isLossless) NeonPink.copy(alpha = 0.2f) else NeonCyan.copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (song.format.isLossless) NeonPink else NeonCyan)
                        ) {
                            Text(
                                text = song.format.displayName,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (song.format.isLossless) NeonPink else NeonCyan
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = DarkSurfaceVariant
                        ) {
                            Text(
                                text = song.bitrate,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = DarkSurfaceVariant
                        ) {
                            Text(
                                text = song.sourceType.displayName,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }
                }

                // Favorite Heart Button
                IconButton(
                    onClick = { onToggleFavorite(song) },
                    modifier = Modifier.testTag("now_playing_fav_btn")
                ) {
                    Icon(
                        imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Yêu thích",
                        tint = if (song.isFavorite) NeonPink else TextSecondary,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Playback Seekbar & Timestamps
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
                    modifier = Modifier.testTag("audio_seekbar")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val currentSec = (currentSliderValue / 1000).toInt()
                    val totalSec = (durationMs / 1000).toInt()
                    Text(
                        text = "%d:%02d".format(currentSec / 60, currentSec % 60),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "%d:%02d".format(totalSec / 60, totalSec % 60),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Primary Playback Controls: Shuffle, Prev, Play/Pause, Next, Repeat
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Shuffle
                IconButton(
                    onClick = onToggleShuffle,
                    modifier = Modifier.testTag("shuffle_btn")
                ) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "Phát ngẫu nhiên",
                        tint = if (isShuffle) NeonCyan else TextMuted
                    )
                }

                // Previous
                IconButton(
                    onClick = onPreviousClick,
                    modifier = Modifier.testTag("prev_btn")
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Bài trước",
                        tint = TextPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Big Play / Pause Neon Button
                Box(
                    modifier = Modifier
                        .size(68.dp)
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
                        modifier = Modifier.size(38.dp)
                    )
                }

                // Next
                IconButton(
                    onClick = onNextClick,
                    modifier = Modifier.testTag("next_btn")
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Bài tiếp theo",
                        tint = TextPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Repeat Mode
                IconButton(
                    onClick = onCycleRepeat,
                    modifier = Modifier.testTag("repeat_btn")
                ) {
                    Icon(
                        imageVector = when (repeatMode) {
                            RepeatMode.ONE -> Icons.Default.RepeatOne
                            RepeatMode.ALL -> Icons.Default.Repeat
                            RepeatMode.OFF -> Icons.Default.Repeat
                        },
                        contentDescription = repeatMode.label,
                        tint = if (repeatMode != RepeatMode.OFF) NeonCyan else TextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Extra Audio Enhancements: Speed, Equalizer, Sleep Timer, Skip 10s
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Rewind 10s
                IconButton(onClick = onSkipBackward) {
                    Icon(Icons.Default.Replay10, contentDescription = "Tua lại 10s", tint = TextSecondary)
                }

                // Playback Speed
                TextButton(
                    onClick = { showSpeedDialog = true },
                    modifier = Modifier.testTag("speed_btn")
                ) {
                    Text(
                        text = "${playbackSpeed}x",
                        color = NeonCyan,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                // Equalizer Preset
                IconButton(
                    onClick = { showEqualizerDialog = true },
                    modifier = Modifier.testTag("equalizer_btn")
                ) {
                    Icon(Icons.Default.GraphicEq, contentDescription = "Bộ chỉnh âm Equalizer", tint = NeonPurple)
                }

                // Sleep Timer
                IconButton(
                    onClick = { showSleepTimerDialog = true },
                    modifier = Modifier.testTag("sleep_timer_btn")
                ) {
                    BadgeBox(
                        badge = {
                            if (sleepTimerMinutes != null) {
                                Badge(containerColor = NeonPink) {
                                    Text("${sleepTimerMinutes}m")
                                }
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.Bedtime,
                            contentDescription = "Hẹn giờ tắt",
                            tint = if (sleepTimerMinutes != null) NeonPink else TextSecondary
                        )
                    }
                }

                // Forward 10s
                IconButton(onClick = onSkipForward) {
                    Icon(Icons.Default.Forward10, contentDescription = "Tua tới 10s", tint = TextSecondary)
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

    // Equalizer Presets & Custom Profile Dialog
    if (showEqualizerDialog) {
        Dialog(onDismissRequest = { showEqualizerDialog = false }) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 580.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Bộ chỉnh âm Equalizer",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        IconButton(onClick = { showEqualizerDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Đóng", tint = TextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Preset Chips
                    Text(
                        text = "Cấu hình sẵn có",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Row 1 of presets
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(EqualizerPreset.BALANCED, EqualizerPreset.BASS_BOOST, EqualizerPreset.VOCAL).forEach { preset ->
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
                                    selectedContainerColor = NeonPurple,
                                    selectedLabelColor = Color.White,
                                    containerColor = DarkSurfaceVariant,
                                    labelColor = TextPrimary
                                )
                            )
                        }
                    }

                    // Row 2 of presets
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(EqualizerPreset.ELECTRONIC, EqualizerPreset.ROCK, EqualizerPreset.ACOUSTIC, EqualizerPreset.CUSTOM).forEach { preset ->
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
                                    selectedContainerColor = if (preset == EqualizerPreset.CUSTOM) NeonCyan else NeonPurple,
                                    selectedLabelColor = if (preset == EqualizerPreset.CUSTOM) Color.Black else Color.White,
                                    containerColor = DarkSurfaceVariant,
                                    labelColor = TextPrimary
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = DarkCardBorder)
                    Spacer(modifier = Modifier.height(16.dp))

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
                                text = "Tùy chỉnh 5 dải tần (Custom Profile)",
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
                        val currentLevel = if (index < customBandLevels.size) customBandLevels[index] else 0f
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

                    Spacer(modifier = Modifier.height(8.dp))

                    // Bass Boost Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Siêu trầm Bass Boost",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = NeonPink
                        )
                        Text(
                            text = "${(customBassBoost * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = NeonPink
                        )
                    }
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
            Pair(null, "Tắt hẹn giờ"),
            Pair(15, "15 phút"),
            Pair(30, "30 phút"),
            Pair(45, "45 phút"),
            Pair(60, "60 phút")
        )
        Dialog(onDismissRequest = { showSleepTimerDialog = false }) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Hẹn giờ tắt nhạc", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(modifier = Modifier.height(12.dp))
                    timerOptions.forEach { (mins, label) ->
                        val isSelected = sleepTimerMinutes == mins
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) NeonPink.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable {
                                    onSleepTimerChange(mins)
                                    showSleepTimerDialog = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) NeonPink else TextPrimary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
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

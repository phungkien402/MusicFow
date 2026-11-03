package com.example.ui.theme

import androidx.compose.ui.graphics.Color

val NeonCyan = Color(0xFF00F5FF)
val NeonPurple = Color(0xFFA855F7)
val NeonPink = Color(0xFFFF2A85)
val EmeraldGreen = Color(0xFF10B981)
val AmberOrange = Color(0xFFFF9F1C)

val DarkBackground = Color(0xFF0B0E14)
val DarkSurface = Color(0xFF131822)
val DarkSurfaceVariant = Color(0xFF1B2232)
val DarkCardBorder = Color(0xFF2B354D)

val TextPrimary = Color(0xFFF1F5F9)
val TextSecondary = Color(0xFF94A3B8)
val TextMuted = Color(0xFF64748B)

val YouTubeRed = Color(0xFFFF0000)
val TikTokCyan = Color(0xFF00F2FE)
val TikTokPink = Color(0xFFFE2C55)

enum class AppTheme(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val cardBorder: Color
) {
    CYBERPUNK(
        id = "cyberpunk",
        displayName = "Cyberpunk Neon",
        subtitle = "Xanh Cyan & Tím huyền bí",
        primary = Color(0xFF00F5FF),
        secondary = Color(0xFFA855F7),
        accent = Color(0xFFFF2A85),
        background = Color(0xFF0B0E14),
        surface = Color(0xFF131822),
        surfaceVariant = Color(0xFF1B2232),
        cardBorder = Color(0xFF2B354D)
    ),
    SUNSET(
        id = "sunset",
        displayName = "Sunset Amber",
        subtitle = "Cam hoàng hôn & Hồng ấm áp",
        primary = Color(0xFFFF9F1C),
        secondary = Color(0xFFFF4081),
        accent = Color(0xFFFFD166),
        background = Color(0xFF140D0B),
        surface = Color(0xFF1F1512),
        surfaceVariant = Color(0xFF2C1E1A),
        cardBorder = Color(0xFF4A322B)
    ),
    EMERALD(
        id = "emerald",
        displayName = "Emerald Forest",
        subtitle = "Xanh lục bảo & Bạc hà thanh mát",
        primary = Color(0xFF10B981),
        secondary = Color(0xFF06D6A0),
        accent = Color(0xFF34D399),
        background = Color(0xFF08140F),
        surface = Color(0xFF0F2119),
        surfaceVariant = Color(0xFF172E24),
        cardBorder = Color(0xFF234A3A)
    ),
    AMOLED(
        id = "amoled",
        displayName = "AMOLED Black",
        subtitle = "Đen tuyền tuyệt đối tiết kiệm pin",
        primary = Color(0xFF38BDF8),
        secondary = Color(0xFF818CF8),
        accent = Color(0xFFF43F5E),
        background = Color(0xFF000000),
        surface = Color(0xFF0F0F0F),
        surfaceVariant = Color(0xFF1A1A1A),
        cardBorder = Color(0xFF2B2B2B)
    ),
    MIDNIGHT(
        id = "midnight",
        displayName = "Midnight Royal",
        subtitle = "Xanh đại dương & Tím hoàng gia",
        primary = Color(0xFF6366F1),
        secondary = Color(0xFF8B5CF6),
        accent = Color(0xFF06B6D4),
        background = Color(0xFF0A0F1D),
        surface = Color(0xFF11192E),
        surfaceVariant = Color(0xFF1A2645),
        cardBorder = Color(0xFF2A3C6B)
    )
}

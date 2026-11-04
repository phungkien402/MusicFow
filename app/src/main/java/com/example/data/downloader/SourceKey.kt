package com.example.data.downloader

object SourceKey {
    private val YOUTU_BE_REGEX = Regex("""(?i:\byoutu\.be/)([a-zA-Z0-9_-]{11})""")
    private val YT_WATCH_REGEX = Regex("""[?&]v=([a-zA-Z0-9_-]{11})""")
    private val YT_PATH_REGEX = Regex("""/(?:shorts|embed)/([a-zA-Z0-9_-]{11})""")
    private val TIKTOK_REGEX = Regex("""(?i:\btiktok\.com).*/video/(\d+)""")

    fun of(url: String): String {
        val raw = url.trim()
        if (raw.isEmpty()) return ""

        // YouTube check
        YOUTU_BE_REGEX.find(raw)?.let {
            return "yt:${it.groupValues[1]}"
        }

        if (raw.contains("youtube.com", ignoreCase = true)) {
            YT_WATCH_REGEX.find(raw)?.let {
                return "yt:${it.groupValues[1]}"
            }
            YT_PATH_REGEX.find(raw)?.let {
                return "yt:${it.groupValues[1]}"
            }
        }

        // TikTok check
        TIKTOK_REGEX.find(raw)?.let {
            return "tt:${it.groupValues[1]}"
        }

        // Other URLs: scheme + host (lowercase) + path, query and fragment removed
        val noFragment = raw.substringBefore('#')
        val noQuery = noFragment.substringBefore('?')
        val schemeIndex = noQuery.indexOf("://")
        return if (schemeIndex != -1) {
            val scheme = noQuery.substring(0, schemeIndex).lowercase()
            val rest = noQuery.substring(schemeIndex + 3)
            val slashIndex = rest.indexOf('/')
            if (slashIndex != -1) {
                val host = rest.substring(0, slashIndex).lowercase()
                val path = rest.substring(slashIndex)
                "$scheme://$host$path"
            } else {
                val host = rest.lowercase()
                "$scheme://$host"
            }
        } else {
            noQuery.lowercase()
        }
    }
}

package com.example.data.lyrics

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class LyricLine(
    val timestampMs: Long,
    val text: String
)

object LyricsSynchronizerService {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val lrcTagPattern = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")

    /**
     * Parses LRC string content into a sorted list of LyricLine.
     * Returns empty list if no valid LRC timestamps were found.
     */
    fun parseLrc(content: String): List<LyricLine> {
        if (content.isBlank()) return emptyList()

        val lines = content.lines()
        val result = mutableListOf<LyricLine>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val matcher = lrcTagPattern.matcher(trimmed)
            val timestamps = mutableListOf<Long>()
            var lastMatchEnd = 0

            while (matcher.find()) {
                val min = matcher.group(1)?.toLongOrNull() ?: 0L
                val sec = matcher.group(2)?.toLongOrNull() ?: 0L
                val fracStr = matcher.group(3) ?: "0"
                val millis = when (fracStr.length) {
                    1 -> fracStr.toLong() * 100
                    2 -> fracStr.toLong() * 10
                    3 -> fracStr.toLong()
                    else -> fracStr.take(3).toLongOrNull() ?: 0L
                }
                val totalMs = (min * 60 + sec) * 1000L + millis
                timestamps.add(totalMs)
                lastMatchEnd = matcher.end()
            }

            if (timestamps.isNotEmpty()) {
                val lyricText = trimmed.substring(lastMatchEnd).trim()
                if (lyricText.isNotEmpty()) {
                    for (ts in timestamps) {
                        result.add(LyricLine(ts, lyricText))
                    }
                }
            }
        }

        return result.sortedBy { it.timestampMs }
    }

    /**
     * Formats milliseconds to standard [mm:ss.xx]
     */
    fun formatTimestamp(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val hundredths = (ms % 1000) / 10
        return "[%02d:%02d.%02d]".format(min, sec, hundredths)
    }

    /**
     * Smart extractor for title & artist candidates from messy YouTube/video titles.
     * e.g. "LƯU NIÊN - NGUYỄN ĐÌNH VŨ | JACK J97 | TAM THÁI TỬ | COVER"
     * -> Title: "Lưu Niên", Artist: "Nguyễn Đình Vũ"
     */
    fun extractSearchCandidates(rawTitle: String, rawArtist: String): List<Pair<String, String>> {
        val candidates = mutableListOf<Pair<String, String>>()

        // Clean common tags
        val cleaned = rawTitle
            .replace(Regex("\\((?:Official|Audio|Video|Lyric|MV|Vietsub|Remix|Cover|Full|Prod).*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[(?:Official|Audio|Video|Lyric|MV|Vietsub|Remix|Cover|Full|4K|HD|Prod).*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(Mashup|Drill Mix|Remix|Cover|Speed Up|Slowed|Tiktok|Bassboosted)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b\\d+in\\d+\\b", RegexOption.IGNORE_CASE), "")
            .trim()

        // 1. Split by delimiters like '-', '–', '|'
        val segments = cleaned.split(Regex("""\s*\|\s*|\s+[-–—]\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.size >= 2) {
            val titlePart = segments[0]
            val artistPart = segments[1]
            candidates.add(titlePart to artistPart)
            candidates.add(titlePart to "")
            candidates.add("$titlePart $artistPart" to "")
        } else if (segments.isNotEmpty()) {
            candidates.add(segments[0] to "")
        }

        // 2. Collab " x "
        if (cleaned.contains(" x ", ignoreCase = true)) {
            val parts = cleaned.split(Regex(" x ", RegexOption.IGNORE_CASE))
            for (p in parts) {
                val cleanPart = p.split(Regex("""\s*\|\s*|\s+[-–—]\s+""")).firstOrNull()?.trim() ?: ""
                if (cleanPart.isNotEmpty()) {
                    candidates.add(cleanPart to "")
                }
            }
        }

        // 3. Fallback cleaned raw
        val basicClean = cleanSongTitle(rawTitle)
        val validArtist = if (rawArtist.equals("Unknown", true) || rawArtist.equals("YouTube Creator", true)) "" else rawArtist
        candidates.add(basicClean to validArtist)

        return candidates.distinct()
    }

    /**
     * Strips punctuation and noise
     */
    fun cleanSongTitle(rawTitle: String): String {
        return rawTitle
            .replace(Regex("\\((?:Official|Audio|Video|Lyric|MV|Vietsub|Remix|Cover|Full).*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[(?:Official|Audio|Video|Lyric|MV|Vietsub|Remix|Cover|Full|4K|HD).*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(Mashup|Drill Mix|Remix|Cover|Speed Up|Slowed|Tiktok|Bassboosted)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b\\d+in\\d+\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[-–—|:]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Attempts to fetch synchronized lyrics from LRCLIB using smart search candidates.
     */
    suspend fun fetchFromLrclib(title: String, artist: String, durationSec: Long): String? = withContext(Dispatchers.IO) {
        val candidates = extractSearchCandidates(title, artist)

        for ((candTitle, candArtist) in candidates) {
            if (candTitle.isBlank()) continue

            // 1. Try exact get endpoint
            if (candArtist.isNotBlank()) {
                try {
                    val encodedTitle = URLEncoder.encode(candTitle, "UTF-8")
                    val encodedArtist = URLEncoder.encode(candArtist, "UTF-8")
                    val url = "https://lrclib.net/api/get?track_name=$encodedTitle&artist_name=$encodedArtist&duration=$durationSec"

                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "SoundFlowMusicApp/2.0 (contact: support@soundflow.app)")
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string()
                            if (!body.isNullOrBlank()) {
                                val json = JSONObject(body)
                                val syncedLyrics = json.optString("syncedLyrics", "")
                                if (syncedLyrics.isNotBlank() && parseLrc(syncedLyrics).isNotEmpty()) {
                                    return@withContext syncedLyrics
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("LyricsSynchronizer", "Exact fetch candidate failed ($candTitle): ${e.message}")
                }
            }

            // 2. Try search endpoint
            try {
                val searchQuery = if (candArtist.isNotBlank()) "$candTitle $candArtist" else candTitle
                val encodedQuery = URLEncoder.encode(searchQuery, "UTF-8")
                val searchUrl = "https://lrclib.net/api/search?q=$encodedQuery"

                val searchRequest = Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", "SoundFlowMusicApp/2.0 (contact: support@soundflow.app)")
                    .build()

                httpClient.newCall(searchRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            val array = JSONArray(body)
                            for (i in 0 until array.length()) {
                                val item = array.getJSONObject(i)
                                val syncedLyrics = item.optString("syncedLyrics", "")
                                if (syncedLyrics.isNotBlank() && parseLrc(syncedLyrics).isNotEmpty()) {
                                    return@withContext syncedLyrics
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("LyricsSynchronizer", "Search candidate failed ($candTitle): ${e.message}")
            }
        }

        null
    }

    /**
     * Generates real lyrics with synchronized timestamps via Gemini AI
     */
    private suspend fun fetchFromGemini(title: String, artist: String, durationSec: Long): String? = withContext(Dispatchers.IO) {
        val apiKey = try {
            val field = BuildConfig::class.java.getField("GEMINI_API_KEY")
            field.get(null) as? String
        } catch (e: Exception) {
            null
        }

        if (apiKey.isNullOrBlank() || apiKey.equals("MY_GEMINI_API_KEY", true)) {
            return@withContext null
        }

        try {
            val candidates = extractSearchCandidates(title, artist)
            val cleanTitle = candidates.firstOrNull()?.first ?: cleanSongTitle(title)
            val cleanArtist = candidates.firstOrNull()?.second ?: artist

            val prompt = """
                Bạn là cơ sở dữ liệu lời bài hát âm nhạc.
                Bài hát: "$cleanTitle"
                Nghệ sĩ: "$cleanArtist"
                Thời lượng bài hát: $durationSec giây.
                
                Nhiệm vụ: Cung cấp lời bài hát THẬT, CHÍNH XÁC của bài hát này, định dạng chuẩn LRC có nhãn thời gian [mm:ss.xx] cho từng câu hát từ đầu đến hết bài.
                Quy tắc bắt buộc:
                1. Phải là lời bài hát thực tế của "$cleanTitle", không tự bịa đặt lời giả hoặc lời sáo rỗng.
                2. Phân bố các mốc thời gian [mm:ss.xx] tự nhiên theo nhịp điệu bài hát (dạo đầu ~12s, kết bài ~15s trước khi hết).
                3. CHỈ xuất danh sách các dòng [mm:ss.xx] câu hát. Tuyệt đối không thêm giải thích, không dùng markdown ```.
            """.trimIndent()

            val jsonBody = JSONObject().apply {
                val contents = JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                }
                put("contents", contents)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val respBody = response.body?.string()
                    if (!respBody.isNullOrBlank()) {
                        val json = JSONObject(respBody)
                        val candidatesArray = json.optJSONArray("candidates")
                        if (candidatesArray != null && candidatesArray.length() > 0) {
                            val content = candidatesArray.getJSONObject(0).optJSONObject("content")
                            val parts = content?.optJSONArray("parts")
                            if (parts != null && parts.length() > 0) {
                                val text = parts.getJSONObject(0).optString("text", "")
                                val cleanedText = text
                                    .replace("```lrc", "")
                                    .replace("```", "")
                                    .trim()
                                if (parseLrc(cleanedText).size >= 3) {
                                    return@withContext cleanedText
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("LyricsSynchronizer", "Gemini lyrics fetch failed: ${e.message}")
        }
        null
    }

    /**
     * Generates a timeline-synchronized LRC format from plain lyrics,
     * calculating timestamps proportionally across the track's duration.
     */
    fun generateSmartTimeline(plainLyrics: String, durationMs: Long): String {
        val lines = plainLyrics.lines()
            .map { it.replace(lrcTagPattern.toRegex(), "").trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""

        val total = if (durationMs > 0L) durationMs else 210000L
        val intro = minOf(12000L, total / 10L)
        val outro = minOf(15000L, total / 10L)
        val available = total - outro - intro
        val stepMs = available / lines.size

        val sb = StringBuilder()
        sb.append(formatTimestamp(0L)).append(" 🎵 (Nhạc dạo mở đầu)\n")

        lines.forEachIndexed { index, cleanLine ->
            val lineTimeMs = intro + (index * stepMs)
            sb.append(formatTimestamp(lineTimeMs)).append(" ").append(cleanLine).append("\n")
        }

        sb.append(formatTimestamp(total - outro / 2L)).append(" 🎶 (Phần kết)\n")
        return sb.toString().trim()
    }

    /**
     * Primary entry point:
     * 1. Returns existing lyrics if already in LRC format.
     * 2. Attempts fetching online from LRCLIB using smart title/artist segmentation.
     * 3. Attempts fetching real lyrics via Gemini AI.
     * 4. If plain lyrics already exist, time-aligns them across the duration.
     * 5. Returns empty string if nothing could be found (NEVER returns fake placeholder lyrics).
     */
    suspend fun getOrGenerateSyncedLyrics(
        title: String,
        artist: String,
        durationMs: Long,
        existingLyrics: String?
    ): String {
        // 1. If already valid LRC with timestamps, keep as is
        if (!existingLyrics.isNullOrBlank()) {
            val parsed = parseLrc(existingLyrics)
            if (parsed.size >= 2) {
                return existingLyrics
            }
        }

        val durationSec = (durationMs / 1000).coerceAtLeast(60)

        // 2. Try online sync from LRCLIB
        val onlineLrc = fetchFromLrclib(title, artist, durationSec)
        if (!onlineLrc.isNullOrBlank()) {
            return onlineLrc
        }

        // 3. Try Gemini AI for genuine lyrics with timestamps
        val geminiLrc = fetchFromGemini(title, artist, durationSec)
        if (!geminiLrc.isNullOrBlank()) {
            return geminiLrc
        }

        // 4. If plain lyrics exist, time-align them across song duration
        if (!existingLyrics.isNullOrBlank()) {
            return generateSmartTimeline(existingLyrics, durationMs)
        }

        // 5. If not found, return empty string so user is guided to paste real lyrics
        return ""
    }
}

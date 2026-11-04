package com.example.data.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit

data class AudioCandidate(
    val url: String,
    val mimeType: String,
    val bitrateKbps: Int,
    val isVideo: Boolean
)

object YouTubeStreamPicker {
    fun pickBestAudio(c: List<AudioCandidate>): AudioCandidate? {
        val audios = c.filter { !it.isVideo }
        if (audios.isEmpty()) return null
        return audios.maxWithOrNull(
            compareBy<AudioCandidate> { it.bitrateKbps }
                .thenBy {
                    val m = it.mimeType.lowercase()
                    if (m.contains("mp4") || m.contains("m4a")) 1 else 0
                }
        )
    }
}

data class YouTubeInfo(
    val title: String,
    val uploader: String,
    val thumbnailUrl: String?,
    val durationMs: Long,
    val audio: AudioCandidate?
)

interface YouTubeAudioResolver {
    suspend fun resolve(videoId: String): Result<YouTubeInfo>
}

class NewPipeYouTubeResolver : YouTubeAudioResolver {
    companion object {
        @Volatile
        private var initialized = false
        private val initLock = Any()

        fun ensureInitialized() {
            if (!initialized) {
                synchronized(initLock) {
                    if (!initialized) {
                        val client = OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .followRedirects(true)
                            .build()
                        NewPipe.init(NewPipeOkHttpDownloader(client))
                        initialized = true
                    }
                }
            }
        }
    }

    override suspend fun resolve(videoId: String): Result<YouTubeInfo> = withContext(Dispatchers.IO) {
        runCatching {
            ensureInitialized()
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            val audioStreams = streamInfo.audioStreams ?: emptyList()
            val progressiveAudio = audioStreams.filter {
                it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.url.isNullOrBlank()
            }
            val candidates = progressiveAudio.mapNotNull { s ->
                val streamUrl = s.url ?: return@mapNotNull null
                val mime = s.format?.mimeType ?: "audio/mp4"
                val bitrate = if (s.averageBitrate > 0) s.averageBitrate else s.bitrate
                AudioCandidate(
                    url = streamUrl,
                    mimeType = mime,
                    bitrateKbps = bitrate,
                    isVideo = false
                )
            }
            val bestAudio = YouTubeStreamPicker.pickBestAudio(candidates)
            val bestThumb = streamInfo.thumbnails?.maxByOrNull { it.width * it.height }?.url
                ?: streamInfo.thumbnails?.firstOrNull()?.url
            val durationMs = streamInfo.duration * 1000L

            YouTubeInfo(
                title = streamInfo.name ?: "Unknown Title",
                uploader = streamInfo.uploaderName ?: "Unknown Artist",
                thumbnailUrl = bestThumb,
                durationMs = durationMs,
                audio = bestAudio
            )
        }
    }
}

class NewPipeOkHttpDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val reqBuilder = okhttp3.Request.Builder().url(url)
        headers?.forEach { (key, values) ->
            values.forEach { v -> reqBuilder.addHeader(key, v) }
        }
        if (headers == null || headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            reqBuilder.header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
        }

        val body = if (dataToSend != null) {
            dataToSend.toRequestBody(null)
        } else if (httpMethod.equals("POST", ignoreCase = true)) {
            ByteArray(0).toRequestBody(null)
        } else null

        reqBuilder.method(httpMethod, body)

        client.newCall(reqBuilder.build()).execute().use { resp ->
            val code = resp.code
            val message = resp.message
            val responseHeaders = resp.headers.toMultimap()
            val responseBody = resp.body?.string() ?: ""
            val latestUrl = resp.request.url.toString()
            return Response(code, message, responseHeaders, responseBody, latestUrl)
        }
    }
}

package com.example.spec

/*
 * HOP DONG DOT F (Claude viet). KHONG SUA FILE NAY. Robolectric + MockWebServer, KHONG goi YouTube that.
 *  - interface com.example.data.downloader.YouTubeAudioResolver { suspend fun resolve(videoId: String): Result<YouTubeInfo> }
 *    data class YouTubeInfo(title: String, uploader: String, thumbnailUrl: String?, durationMs: Long, audio: AudioCandidate?)
 *    Ban that: NewPipeYouTubeResolver (NewPipeExtractor).
 *  - AudioLinkExtractor(context, youTubeResolver: YouTubeAudioResolver = NewPipeYouTubeResolver())
 *      analyzeUrl(link YouTube): lay title/uploader/thumbnail/duration tu resolver.
 *      downloadAudio(YOUTUBE): LUON goi resolver.resolve(videoId) moi (link luong YouTube het han sau vai gio), tai audio.url,
 *      KHONG dung loader.to / Invidious / Piped nua. Resolver loi hoac audio null -> Result.failure, khong de file rac.
 *  - Cai dat crossfade duoc luu: AudioPlayerManager moi doc lai gia tri da dat.
 */

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.downloader.AudioCandidate
import com.example.data.downloader.AudioLinkExtractor
import com.example.data.downloader.YouTubeAudioResolver
import com.example.data.downloader.YouTubeInfo
import com.example.data.model.AudioFormat
import com.example.data.model.AudioQuality
import com.example.data.model.SourceType
import com.example.data.player.AudioPlayerManager
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YouTubeSourceSpecTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    private class FakeResolver(val make: (String) -> Result<YouTubeInfo>) : YouTubeAudioResolver {
        val calls = mutableListOf<String>()
        override suspend fun resolve(videoId: String): Result<YouTubeInfo> { calls += videoId; return make(videoId) }
    }

    private val downloadsDir get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")
    private fun files() = downloadsDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
    private fun m4a(size: Int = 40_000) = Buffer().write(byteArrayOf(0, 0, 0, 0x20) + "ftypM4A ".toByteArray() + ByteArray(size) { (it % 251).toByte() })

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() { server.shutdown() }

    private fun info(audioPath: String? = "/yt/audio") = YouTubeInfo(
        title = "Lạc Trôi", uploader = "Sơn Tùng M-TP Official", thumbnailUrl = null, durationMs = 233_000L,
        audio = audioPath?.let { AudioCandidate(server.url(it).toString(), "audio/mp4", 128, false) }
    )

    @Test
    fun `TC-YTS-01 analyzeUrl YouTube lay thong tin tu resolver`() = runBlocking {
        val r = FakeResolver { Result.success(info()) }
        val m = AudioLinkExtractor(context, r).analyzeUrl("https://www.youtube.com/watch?v=Llw9Q6akRo4&t=3").getOrThrow()
        assertEquals(SourceType.YOUTUBE, m.source)
        assertEquals("Lạc Trôi", m.title)
        assertEquals("Sơn Tùng M-TP Official", m.artist)
        assertEquals(233_000L, m.estimatedDurationMs)
        assertEquals(listOf("Llw9Q6akRo4"), r.calls)
    }

    @Test
    fun `TC-YTS-02 tai YouTube - resolve lai luc tai, luu file M4A that`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mp4").setBody(m4a()))
        val r = FakeResolver { Result.success(info()) }
        val ex = AudioLinkExtractor(context, r)
        val meta = ex.analyzeUrl("https://youtu.be/Llw9Q6akRo4").getOrThrow()
        val song = ex.downloadAudio(meta, AudioQuality.AUTO) { _, _, _, _ -> }.getOrThrow()
        assertEquals("phai resolve lai khi tai", 2, r.calls.size)
        assertEquals(AudioFormat.M4A, song.format)
        assertTrue(song.filePath.endsWith(".m4a"))
        assertTrue(File(song.filePath).length() > 40_000)
        assertEquals(SourceType.YOUTUBE, song.sourceType)
        assertTrue(server.requestCount >= 1)
    }

    @Test
    fun `TC-YTS-03 resolver loi hoac khong co luong tieng - that bai, khong file rac`() = runBlocking {
        val before = files()
        val bad = FakeResolver { Result.failure(java.io.IOException("YouTube doi ma hoa")) }
        val ex = AudioLinkExtractor(context, bad)
        val meta = com.example.data.downloader.ParsedLinkMetadata("https://youtu.be/Llw9Q6akRo4", SourceType.YOUTUBE, "x", "y", null, 1000L, null)
        assertTrue(ex.downloadAudio(meta, AudioQuality.AUTO) { _, _, _, _ -> }.isFailure)
        val noAudio = AudioLinkExtractor(context, FakeResolver { Result.success(info(audioPath = null)) })
        assertTrue(noAudio.downloadAudio(meta, AudioQuality.AUTO) { _, _, _, _ -> }.isFailure)
        assertEquals(before, files())
    }

    @Test
    fun `TC-YTS-04 khong con goi loader-to, Invidious, Piped`() {
        val src = File("src/main/java/com/example/data/downloader/AudioLinkExtractor.kt").readText()
        listOf("loader.to", "invidious", "piped", "video-download-api").forEach {
            assertFalse("con dung nguon chet: $it", src.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun `TC-XFS-01 cai dat crossfade duoc luu qua lan mo app`() {
        AudioPlayerManager(context).setCrossfadeSeconds(7)
        assertEquals(7, AudioPlayerManager(context).crossfadeSeconds.value)
    }
}

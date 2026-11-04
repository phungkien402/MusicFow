package com.example.spec

/*
 * HOP DONG (Claude viet). KHONG SUA FILE NAY.
 * Pham vi: AudioLinkExtractor - phan tich link truc tiep va tai file qua MockWebServer (khong can Internet).
 * Spec Quyet dinh 1: that bai thi bao loi ro rang, KHONG luu file gia / nhac mau thay bai that.
 * Link YouTube/TikTok can mang that -> khong test o day (Claude review bang doc code).
 */

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.downloader.AudioLinkExtractor
import com.example.data.downloader.ParsedLinkMetadata
import com.example.data.model.AudioQuality
import com.example.data.model.SourceType
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
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadSpecTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var extractor: AudioLinkExtractor

    private val downloadsDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")

    private fun filesNow(): Set<String> = downloadsDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()

    private fun meta(path: String = "/nhac/bai.mp3"): ParsedLinkMetadata {
        val url = server.url(path).toString()
        return ParsedLinkMetadata(
            originalUrl = url, source = SourceType.DIRECT_URL, title = "Bai test", artist = "Web Source",
            thumbnailUrl = null, estimatedDurationMs = 180_000L, directStreamUrl = url
        )
    }

    private fun audioBody(size: Int) = Buffer().write(ByteArray(size).also { Random(1).nextBytes(it); it[0] = 'I'.code.toByte(); it[1] = 'D'.code.toByte(); it[2] = '3'.code.toByte() })

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
        extractor = AudioLinkExtractor(context)
    }

    @After
    fun tearDown() { server.shutdown() }

    @Test
    fun `TC-DL-01 tai link truc tiep thanh cong - file ton tai dung kich thuoc, thong tin bai dung`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(audioBody(65_536)))
        val r = extractor.downloadAudio(meta(), AudioQuality.MP3_320) { _, _, _, _ -> }
        assertTrue("that bai: ${r.exceptionOrNull()}", r.isSuccess)
        val s = r.getOrThrow()
        val f = File(s.filePath)
        assertTrue(f.exists())
        assertEquals(65_536L, f.length())
        assertEquals(65_536L, s.fileSizeBytes)
        assertEquals(SourceType.DIRECT_URL, s.sourceType)
        assertEquals("Bai test", s.title)
        assertTrue(f.name.endsWith(".mp3"))
    }

    @Test
    fun `TC-DL-02 server tra 404 - that bai, khong de lai file`() = runBlocking {
        val before = filesNow()
        server.enqueue(MockResponse().setResponseCode(404))
        val r = extractor.downloadAudio(meta(), AudioQuality.MP3_320) { _, _, _, _ -> }
        assertTrue(r.isFailure)
        assertEquals(before, filesNow())
    }

    @Test
    fun `TC-DL-03 file qua nho (1 KB) - that bai, khong de lai file`() = runBlocking {
        val before = filesNow()
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(audioBody(1000)))
        val r = extractor.downloadAudio(meta(), AudioQuality.MP3_320) { _, _, _, _ -> }
        assertTrue(r.isFailure)
        assertEquals(before, filesNow())
    }

    @Test
    fun `TC-DL-04 server tra trang HTML 200 OK (trang loi, captcha) - KHONG duoc luu thanh bai hat`() = runBlocking {
        val before = filesNow()
        val html = "<!DOCTYPE html><html><body>" + "Vui long xac minh ban khong phai robot. ".repeat(600) + "</body></html>"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(html))
        val r = extractor.downloadAudio(meta(), AudioQuality.MP3_320) { _, _, _, _ -> }
        assertTrue("HTML bi luu thanh bai hat: ${r.getOrNull()?.filePath}", r.isFailure)
        assertEquals(before, filesNow())
    }

    @Test
    fun `TC-DL-05 tien trinh - phan tram khong giam, trong 0-100, ket thuc 100`() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(audioBody(160_000))
                .throttleBody(16_384, 150, TimeUnit.MILLISECONDS)
        )
        val ps = mutableListOf<Int>()
        val r = extractor.downloadAudio(meta(), AudioQuality.MP3_320) { p, _, _, _ -> synchronized(ps) { ps.add(p) } }
        assertTrue(r.isSuccess)
        assertTrue("it nhat 1 moc giua chung: $ps", ps.size >= 2)
        assertTrue(ps.all { it in 0..100 })
        ps.zipWithNext().forEach { (a, b) -> assertTrue("giam: $ps", a <= b) }
        assertEquals(100, ps.last())
    }

    @Test
    fun `TC-DL-06 analyzeUrl rong - that bai voi thong bao`() = runBlocking {
        val r = extractor.analyzeUrl("   ")
        assertTrue(r.isFailure)
    }

    @Test
    fun `TC-DL-07 analyzeUrl link truc tiep - giai ma ten tieng Viet, bo query, cat khoang trang`() = runBlocking {
        val url = "https://example.com/music/B%C3%A0i%20h%C3%A1t%20hay.mp3?token=1"
        val r = extractor.analyzeUrl("  $url  ")
        val m = r.getOrThrow()
        assertEquals(SourceType.DIRECT_URL, m.source)
        assertEquals("Bài hát hay", m.title)
        assertEquals(url, m.directStreamUrl)
    }

    @Test
    fun `TC-DL-08 THIET KE - ten file tai ve doc duoc (Lac Troi khong thanh L_c_Tr_i)`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(audioBody(20_000)))
        val m = meta().copy(title = "Lạc Trôi")
        val s = extractor.downloadAudio(m, AudioQuality.MP3_320) { _, _, _, _ -> }.getOrThrow()
        val name = File(s.filePath).name
        assertTrue("ten file: $name", name.contains("Lac", ignoreCase = true) || name.contains("Lạc", ignoreCase = true))
    }
}

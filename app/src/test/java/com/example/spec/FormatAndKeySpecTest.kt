package com.example.spec

/*
 * HOP DONG DOT C (Claude viet). KHONG SUA FILE NAY.
 * JVM thuan (khong Robolectric): code duoc goi KHONG dung android.net.Uri / android.util.*.
 *  - AudioFormatDetector.detect(header): nhan dang dinh dang THAT tu vai byte dau file.
 *  - AudioQuality: chi cho chon dinh dang nguon thuc su tra ve (YouTube/TikTok -> AUTO; link truc tiep -> dung 1 dinh dang cua file).
 *  - SourceKey.of(url): khoa chuan hoa de nhan ra bai da tai (cung video YouTube du link khac nhau).
 */

import com.example.data.downloader.AudioFormatDetector
import com.example.data.downloader.SourceKey
import com.example.data.model.AudioFormat
import com.example.data.model.AudioQuality
import com.example.data.model.SourceType
import org.junit.Assert.*
import org.junit.Test

class FormatAndKeySpecTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() } + ByteArray(32)
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII) + ByteArray(32)

    // ---------- Nhan dang dinh dang that ----------

    @Test
    fun `TC-FMT-01 MP3 co the ID3 hoac khung MPEG`() {
        assertEquals(AudioFormat.MP3, AudioFormatDetector.detect(ascii("ID3")))
        assertEquals(AudioFormat.MP3, AudioFormatDetector.detect(bytes(0xFF, 0xFB, 0x90, 0x64)))
        assertEquals(AudioFormat.MP3, AudioFormatDetector.detect(bytes(0xFF, 0xF3, 0x48, 0xC4)))
    }

    @Test
    fun `TC-FMT-02 AAC ADTS, M4A ftyp, WAV, FLAC, OGG, WEBM`() {
        assertEquals(AudioFormat.AAC, AudioFormatDetector.detect(bytes(0xFF, 0xF1, 0x50, 0x80)))
        assertEquals(AudioFormat.M4A, AudioFormatDetector.detect(bytes(0, 0, 0, 0x20) + ascii("ftypM4A ")))
        assertEquals(AudioFormat.WAV, AudioFormatDetector.detect(ascii("RIFF") + bytes(0x24, 0, 0, 0) + ascii("WAVEfmt ")))
        assertEquals(AudioFormat.FLAC, AudioFormatDetector.detect(ascii("fLaC")))
        assertEquals(AudioFormat.OGG, AudioFormatDetector.detect(ascii("OggS")))
        assertEquals(AudioFormat.WEBM, AudioFormatDetector.detect(bytes(0x1A, 0x45, 0xDF, 0xA3)))
    }

    @Test
    fun `TC-FMT-03 khong nhan ra, rong, qua ngan - null`() {
        assertNull(AudioFormatDetector.detect(ascii("<!DOCTYPE html>")))
        assertNull(AudioFormatDetector.detect(ByteArray(0)))
        assertNull(AudioFormatDetector.detect(byteArrayOf(0x49)))
        assertNull(AudioFormatDetector.detect(ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("AVI LIST")))
    }

    @Test
    fun `TC-FMT-04 AudioFormat co WEBM, fromExtension nhan webm`() {
        assertEquals(AudioFormat.WEBM, AudioFormat.fromExtension("webm"))
        assertEquals("webm", AudioFormat.WEBM.extension)
        assertFalse(AudioFormat.WEBM.isLossless)
    }

    // ---------- Chi cho chon dinh dang that ----------

    @Test
    fun `TC-Q-AUTO-01 YouTube va TikTok - chi 1 lua chon AUTO (dinh dang goc tu nguon)`() {
        assertEquals(listOf(AudioQuality.AUTO), AudioQuality.getAvailableQualities(SourceType.YOUTUBE, "https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(listOf(AudioQuality.AUTO), AudioQuality.getAvailableQualities(SourceType.TIKTOK, "https://www.tiktok.com/@a/video/123"))
        assertEquals("auto", AudioQuality.AUTO.id)
        assertTrue(AudioQuality.AUTO.label.contains("gốc", ignoreCase = true))
        assertFalse("AUTO khong duoc hua kbps", AudioQuality.AUTO.bitrate.contains("kbps"))
    }

    @Test
    fun `TC-Q-AUTO-02 link truc tiep - dung 1 lua chon theo duoi file, khong ro duoi thi AUTO`() {
        fun f(url: String) = AudioQuality.getAvailableQualities(SourceType.DIRECT_URL, url).map { it.format }
        assertEquals(listOf(AudioFormat.FLAC), f("https://a.com/x.FLAC?t=1"))
        assertEquals(listOf(AudioFormat.WAV), f("https://a.com/x.wav"))
        assertEquals(listOf(AudioFormat.M4A), f("https://a.com/x.m4a"))
        assertEquals(listOf(AudioFormat.MP3), f("https://cdn.flac-music.vn/songs/bai.mp3"))
        assertEquals(listOf(AudioQuality.AUTO), AudioQuality.getAvailableQualities(SourceType.DIRECT_URL, "https://a.com/stream?id=9"))
    }

    @Test
    fun `TC-Q-AUTO-03 byId tim duoc moi chat luong, id la thi AUTO`() {
        assertEquals(AudioQuality.AUTO, AudioQuality.byId("auto"))
        assertEquals(AudioQuality.FLAC_LOSSLESS, AudioQuality.byId(AudioQuality.FLAC_LOSSLESS.id))
        assertEquals(AudioQuality.AUTO, AudioQuality.byId("khong-co"))
    }

    // ---------- Khoa nguon de chong tai trung ----------

    @Test
    fun `TC-KEY-01 cac dang link YouTube cung 1 video - cung khoa`() {
        val k = SourceKey.of("https://youtu.be/dQw4w9WgXcQ")
        assertEquals("yt:dQw4w9WgXcQ", k)
        listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s",
            "https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVM",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?si=abc",
            "  https://YOUTU.BE/dQw4w9WgXcQ  "
        ).forEach { assertEquals(it, k, SourceKey.of(it)) }
        assertNotEquals(k, SourceKey.of("https://youtu.be/aaaaaaaaaaa"))
    }

    @Test
    fun `TC-KEY-02 TikTok theo id video`() {
        assertEquals("tt:7300000000000000001", SourceKey.of("https://www.tiktok.com/@ca.si/video/7300000000000000001?is_from_webapp=1"))
    }

    @Test
    fun `TC-KEY-03 link truc tiep - bo query, fragment, host khong phan biet hoa thuong`() {
        assertEquals(SourceKey.of("https://CDN.a.com/nhac/x.mp3?token=1#t"), SourceKey.of("https://cdn.a.com/nhac/x.mp3?token=2"))
        assertNotEquals(SourceKey.of("https://cdn.a.com/nhac/x.mp3"), SourceKey.of("https://cdn.a.com/nhac/y.mp3"))
    }
}

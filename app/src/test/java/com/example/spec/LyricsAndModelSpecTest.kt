package com.example.spec

/*
 * HOP DONG (Claude viet). KHONG SUA FILE NAY.
 * Pham vi: phan tich LRC, tao timeline loi bai hat, tach ten bai/ca si, model AudioFormat/AudioQuality/Song/SongEntity.
 * Test JVM thuan (khong Robolectric).
 */

import com.example.data.local.SongEntity
import com.example.data.lyrics.LyricsSynchronizerService as L
import com.example.data.model.AudioFormat
import com.example.data.model.AudioQuality
import com.example.data.model.Song
import com.example.data.model.SourceType
import org.junit.Assert.*
import org.junit.Test

class LyricsAndModelSpecTest {

    // ---------- LRC ----------

    @Test
    fun `TC-LRC-01 dong LRC co ban`() {
        val r = L.parseLrc("[00:12.50]Xin chao")
        assertEquals(1, r.size)
        assertEquals(12_500L, r[0].timestampMs)
        assertEquals("Xin chao", r[0].text)
    }

    @Test
    fun `TC-LRC-02 nhieu tag tren 1 dong - tach thanh nhieu dong va sap xep`() {
        val r = L.parseLrc("[00:30.00][00:10.00]Dep\n[00:20.00]Giua")
        assertEquals(listOf(10_000L, 20_000L, 30_000L), r.map { it.timestampMs })
        assertEquals(listOf("Dep", "Giua", "Dep"), r.map { it.text })
    }

    @Test
    fun `TC-LRC-03 phan le 1, 2, 3 chu so`() {
        assertEquals(62_345L, L.parseLrc("[01:02.345]a")[0].timestampMs)
        assertEquals(62_340L, L.parseLrc("[01:02.34]a")[0].timestampMs)
        assertEquals(62_300L, L.parseLrc("[01:02.3]a")[0].timestampMs)
        assertEquals(62_000L, L.parseLrc("[01:02]a")[0].timestampMs)
    }

    @Test
    fun `TC-LRC-04 bo qua tag metadata, dong khong chu, chuoi rong`() {
        assertTrue(L.parseLrc("").isEmpty())
        assertTrue(L.parseLrc("   \n  ").isEmpty())
        val r = L.parseLrc("[ar:Son Tung]\n[ti:Lac Troi]\n[00:05.00]\n[00:06.00]Co chu")
        assertEquals(1, r.size)
        assertEquals("Co chu", r[0].text)
    }

    @Test
    fun `TC-LRC-05 formatTimestamp va parse nguoc lai lech toi da 10ms`() {
        assertEquals("[01:02.34]", L.formatTimestamp(62_345L))
        assertEquals("[00:00.00]", L.formatTimestamp(0L))
        for (ms in listOf(0L, 999L, 61_010L, 3_599_990L)) {
            val back = L.parseLrc(L.formatTimestamp(ms) + "x")[0].timestampMs
            assertTrue("ms=$ms back=$back", ms - back in 0..9)
        }
    }

    // ---------- Timeline tu loi tho ----------

    private fun timeline(lines: Int, durationMs: Long) =
        L.parseLrc(L.generateSmartTimeline((1..lines).joinToString("\n") { "Cau so $it" }, durationMs))

    @Test
    fun `TC-TL-01 bai 4 phut - moc thoi gian tang dan, nam trong do dai bai`() {
        val r = timeline(20, 240_000L)
        assertEquals(22, r.size) // 20 cau + nhac dao + phan ket
        r.zipWithNext().forEach { (a, b) -> assertTrue("${a.timestampMs} > ${b.timestampMs}", a.timestampMs <= b.timestampMs) }
        assertTrue(r.all { it.timestampMs in 0..240_000L })
    }

    @Test
    fun `TC-TL-02 clip TikTok 15 giay - moi moc phai nam trong 15 giay`() {
        val r = timeline(4, 15_000L)
        assertTrue("moc vuot do dai: ${r.map { it.timestampMs }}", r.all { it.timestampMs <= 15_000L })
    }

    @Test
    fun `TC-TL-03 bai 25 giay - dong Phan ket phai sau cau loi cuoi`() {
        val r = timeline(8, 25_000L)
        val outro = r.last()
        assertTrue("dong cuoi phai la phan ket: ${outro.text}", outro.text.contains("Phần kết"))
        assertTrue(r.all { it.timestampMs <= 25_000L })
    }

    @Test
    fun `TC-TL-04 loi rong - chuoi rong, dong co san tag thi bo tag cu`() {
        assertEquals("", L.generateSmartTimeline("  \n ", 200_000L))
        val out = L.generateSmartTimeline("[00:01.00]Cau mot", 200_000L)
        assertFalse(out.contains("[00:01.00]Cau mot"))
        assertTrue(out.contains("Cau mot"))
    }

    // ---------- Tach ten bai / ca si ----------

    @Test
    fun `TC-TITLE-01 cleanSongTitle bo tag Official MV`() {
        assertEquals("Lạc Trôi", L.cleanSongTitle("Lạc Trôi (Official MV)"))
        assertEquals("Chạy Ngay Đi", L.cleanSongTitle("Chạy Ngay Đi [Official Music Video]"))
    }

    @Test
    fun `TC-TITLE-02 tieu de YouTube kieu TEN - CA SI - nhieu muc`() {
        val c = L.extractSearchCandidates("LƯU NIÊN - NGUYỄN ĐÌNH VŨ | JACK J97 | TAM THÁI TỬ | COVER", "YouTube Creator")
        val first = c.first()
        assertTrue(first.first.equals("LƯU NIÊN", ignoreCase = true))
        assertTrue(first.second.equals("NGUYỄN ĐÌNH VŨ", ignoreCase = true))
    }

    @Test
    fun `TC-TITLE-03 ten ca si co dau gach (Son Tung M-TP) khong bi cat`() {
        val c = L.extractSearchCandidates("Em Của Ngày Hôm Qua - Sơn Tùng M-TP", "Sơn Tùng M-TP Official")
        assertTrue("candidates=$c", c.any { it.first == "Em Của Ngày Hôm Qua" && it.second == "Sơn Tùng M-TP" })
    }

    // ---------- Model ----------

    @Test
    fun `TC-MOD-01 AudioFormat fromExtension khong phan biet hoa thuong, la thi MP3`() {
        assertEquals(AudioFormat.FLAC, AudioFormat.fromExtension("FLAC"))
        assertEquals(AudioFormat.OGG, AudioFormat.fromExtension("ogg"))
        assertEquals(AudioFormat.MP3, AudioFormat.fromExtension("xyz"))
    }

    @Test
    fun `TC-MOD-02 (doi o dot C) YouTube, TikTok chi co AUTO - khong hua FLAC hay kbps`() {
        for (src in listOf(SourceType.YOUTUBE, SourceType.TIKTOK)) {
            assertEquals(listOf(AudioQuality.AUTO), AudioQuality.getAvailableQualities(src, "https://youtu.be/abcdefghijk"))
        }
    }

    @Test
    fun `TC-MOD-03 link truc tiep flac (ke ca viet hoa) - FLAC dung dau`() {
        assertEquals(AudioFormat.FLAC, AudioQuality.getAvailableQualities(SourceType.DIRECT_URL, "https://a.com/x.flac")[0].format)
        assertEquals(AudioFormat.FLAC, AudioQuality.getAvailableQualities(SourceType.DIRECT_URL, "https://a.com/X.FLAC?t=1")[0].format)
    }

    @Test
    fun `TC-MOD-04 (doi o dot C) link mp3 nhung ten mien chua chu flac - chi 1 lua chon MP3`() {
        val q = AudioQuality.getAvailableQualities(SourceType.DIRECT_URL, "https://cdn.flac-music.vn/songs/bai.mp3")
        assertEquals(listOf(AudioFormat.MP3), q.map { it.format })
    }

    @Test
    fun `TC-MOD-05 durationFormatted`() {
        fun d(ms: Long) = Song(title = "t", artist = "a", durationMs = ms, filePath = "").durationFormatted
        assertEquals("0:00", d(0))
        assertEquals("0:00", d(-500))
        assertEquals("1:01", d(61_000))
        assertEquals("3:59", d(239_999))
    }

    @Test
    fun `TC-MOD-06 SongEntity chuyen qua lai giu nguyen moi truong`() {
        for (src in SourceType.entries) for (fmt in AudioFormat.entries) {
            val s = Song(
                id = 7, title = "T", artist = "A", album = "Al", durationMs = 1234, filePath = "/p.$fmt",
                sourceUrl = "https://x", format = fmt, bitrate = "256 kbps", fileSizeBytes = 999, isDownloaded = false,
                isFavorite = true, sourceType = src, thumbnailUrl = "/t.jpg", addedTimestamp = 42, playCount = 3, lyrics = "[00:01.00]a"
            )
            assertEquals(s, SongEntity.fromSong(s).toSong())
        }
    }

    @Test
    fun `TC-MOD-07 THIET KE - chua biet dung luong thi khong duoc bao bua 2_8 MB`() {
        val s = Song(title = "t", artist = "a", durationMs = 1, filePath = "", fileSizeBytes = 0)
        assertNotEquals("2.8 MB", s.fileSizeFormatted)
    }
}

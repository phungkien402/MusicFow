package com.example.spec

/*
 * TEST DO THAT TREN DIEN THOAI (Claude viet). KHONG SUA FILE NAY.
 * Chay bang: adb shell am instrument (KHONG dung connectedAndroidTest vi no go app + xoa du lieu).
 * ExoPlayer that + file WAV that, lay mau moi 50ms qua AudioPlayerManager.activeVolumes(): Map<songId, am luong hieu dung>
 * (moi trinh phat dang phat 1 bai -> 1 phan tu; luc crossfade co 2 phan tu).
 * In so do vao logcat tag "XFADE".
 */

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.downloader.SampleAudioGenerator
import com.example.data.model.Song
import com.example.data.player.AudioPlayerManager
import com.example.data.player.RepeatMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrossfadeDeviceTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var player: AudioPlayerManager
    private val FADE_S = 2
    private val SONG_S = 7

    data class Sample(val t: Long, val current: Long?, val vols: Map<Long, Float>, val playing: Boolean, val pos: Long) {
        fun v(id: Long) = vols[id] ?: 0f
    }

    private fun <T> main(block: () -> T): T { var r: Any? = null; inst.runOnMainSync { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }

    private fun song(id: Long, freq: Double): Song {
        val f = SampleAudioGenerator.createSampleWav(context, "xfade_test_$id.wav", SONG_S, listOf(freq))
        return Song(id = 900_000 + id, title = "XF $id", artist = "test", durationMs = SONG_S * 1000L, filePath = f.absolutePath)
    }

    private fun record(ms: Long, label: String): List<Sample> {
        val t0 = System.currentTimeMillis()
        val out = mutableListOf<Sample>()
        while (System.currentTimeMillis() - t0 < ms) {
            out += main { Sample(System.currentTimeMillis() - t0, player.currentSong.value?.id, player.activeVolumes(), player.isPlaying.value, player.currentPositionMs.value) }
            Thread.sleep(50)
        }
        out.filterIndexed { i, _ -> i % 4 == 0 }.forEach { s ->
            Log.i("XFADE", "$label t=${s.t} cur=${s.current?.minus(900_000)} vols=${s.vols.map { (k, v) -> "${k - 900_000}:${"%.2f".format(v)}" }} playing=${s.playing} pos=${s.pos}")
        }
        return out
    }

    private fun setRepeat(m: RepeatMode) = main { repeat(3) { if (player.repeatMode.value != m) player.cycleRepeatMode() } }
    private val base get() = main { player.getBaseVolume() }

    private fun waitPlaying() { val end = System.currentTimeMillis() + 5000; while (System.currentTimeMillis() < end && main { player.currentPositionMs.value } < 300) Thread.sleep(50) }

    @Before
    fun setUp() {
        context = inst.targetContext
        player = main { AudioPlayerManager(context) }
        main { player.setCrossfadeSeconds(FADE_S) }
    }

    @After
    fun tearDown() { main { runCatching { player.stopPlaybackCompletely() }; runCatching { player.cleanup() } } }

    @Test
    fun xf1_crossfade_that_chong_len_nhau_khong_khoang_lang() {
        val a = song(1, 440.0); val b = song(2, 660.0)
        setRepeat(RepeatMode.OFF)
        main { player.setQueue(listOf(a, b), 0) }
        val s = record((SONG_S * 2 + 2) * 1000L, "xf1")
        val bv = base
        assertTrue("khong chuyen sang bai B", s.any { it.current == b.id })
        val overlap = s.filter { it.v(a.id) in 0.15f..0.9f && it.v(b.id) in 0.15f..0.9f }
        Log.i("XFADE", "xf1 KET QUA: so mau 2 bai cung keu = ${overlap.size} (~${overlap.size * 50}ms)")
        assertTrue("2 bai khong phat chong len nhau", overlap.size >= 6)
        val firstB = s.indexOfFirst { it.v(b.id) > 0.05f }
        val lastA = s.indexOfLast { it.v(a.id) > 0.05f }
        assertTrue("bai B bat dau truoc khi bai A ket thuc", firstB in 0 until lastA)
        val window = s.subList(s.indexOfFirst { it.v(a.id) > 0.9f * bv }.coerceAtLeast(0), s.indexOfFirst { it.current == b.id && it.v(b.id) >= 0.9f * bv }.coerceAtLeast(1))
        val silent = window.count { it.vols.values.maxOrNull() ?: 0f < 0.1f }
        assertTrue("co khoang lang giua 2 bai: $silent mau", silent == 0)
        val midB = s.filter { it.current == b.id && it.vols.size == 1 && it.pos in (FADE_S * 1000L + 300)..(SONG_S * 1000L - FADE_S * 1000L - 300) }
        assertTrue("bai B khong len du am luong: ${midB.map { it.v(b.id) }}", midB.isNotEmpty() && midB.all { it.v(b.id) >= 0.9f * bv })
    }

    @Test
    fun xf2_lap_mot_bai_khong_crossfade_am_luong_du() {
        val a = song(3, 523.0)
        setRepeat(RepeatMode.ONE)
        main { player.setQueue(listOf(a), 0) }
        val s = record((SONG_S * 2 + 2) * 1000L, "xf2")
        val bv = base
        val loop2 = s.dropWhile { it.pos < SONG_S * 1000L - 1500 }.dropWhile { it.pos > 1500 }
            .filter { it.pos in 1000L..(SONG_S * 1000L - 1500) }
        assertTrue("khong lap lai bai", loop2.isNotEmpty())
        assertTrue("vong lap 2 am luong khong du: ${loop2.map { it.v(a.id) }}", loop2.all { it.v(a.id) >= 0.9f * bv })
    }

    @Test
    fun xf3_bam_next_giua_bai_van_crossfade_va_khong_ket_am() {
        val a = song(4, 392.0); val b = song(5, 587.0)
        setRepeat(RepeatMode.OFF)
        main { player.setQueue(listOf(a, b), 0) }
        record(2500, "xf3-truoc")
        main { player.playNext() }
        val s = record(FADE_S * 1000L + 2000, "xf3-sau")
        val bv = base
        assertEquals(b.id, s.last().current)
        val tail = s.filter { it.t >= FADE_S * 1000L + 800 }
        assertTrue("sau khi bam Next am luong bai B khong du: ${tail.map { it.v(b.id) }}", tail.all { it.v(b.id) >= 0.9f * bv && it.v(a.id) < 0.05f })
    }

    @Test
    fun xf4_dung_giua_luc_chuyen_bai_roi_phat_lai_am_luong_ve_du() {
        val a = song(6, 349.0); val b = song(7, 494.0)
        setRepeat(RepeatMode.OFF)
        main { player.setQueue(listOf(a, b), 0) }
        waitPlaying()
        main { player.seekTo(SONG_S * 1000L - FADE_S * 1000L - 300) }
        record(900, "xf4-dang-chuyen")
        main { player.pause() }
        val paused = record(600, "xf4-dung")
        assertTrue("dung roi ma van con tieng: ${paused.map { it.vols }}", paused.drop(2).all { it.vols.values.all { v -> v < 0.01f } || !it.playing })
        main { player.resume() }
        val after = record(FADE_S * 1000L + 2000, "xf4-sau")
        val bv = base
        val cur = after.last().current!!
        val tail = after.filter { it.t >= FADE_S * 1000L + 800 }
        assertTrue("sau khi phat lai am luong khong ve du: ${tail.map { it.vols }}", tail.isNotEmpty() && tail.all { it.v(cur) >= 0.9f * bv })
    }

    @Test
    fun xf5_tat_crossfade_chuyen_bai_lien_mach() {
        main { player.setCrossfadeSeconds(0) }
        val a = song(8, 440.0); val b = song(9, 660.0)
        setRepeat(RepeatMode.OFF)
        main { player.setQueue(listOf(a, b), 0) }
        waitPlaying()
        main { player.seekTo(SONG_S * 1000L - 1500) }
        val s = record(3500, "xf5")
        assertTrue(s.any { it.current == b.id })
        val i = s.indexOfFirst { it.current == b.id }
        val silent = s.subList((i - 6).coerceAtLeast(0), (i + 6).coerceAtMost(s.size)).count { it.vols.values.maxOrNull() ?: 0f < 0.1f }
        Log.i("XFADE", "xf5 KET QUA: mau im lang quanh cho chuyen bai = $silent (~${silent * 50}ms)")
        assertTrue("chuyen bai khi tat crossfade bi im qua 200ms", silent <= 4)
    }
}

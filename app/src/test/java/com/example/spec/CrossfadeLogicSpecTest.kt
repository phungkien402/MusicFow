package com.example.spec

/*
 * HOP DONG DOT F (Claude viet). KHONG SUA FILE NAY. JVM thuan.
 *  - com.example.data.player.Crossfade (object):
 *      equalPowerGains(progress: Float): Pair<Float, Float>  (out = bai cu, in = bai moi), progress kep trong [0,1]
 *      shouldStart(positionMs, durationMs, crossfadeMs, hasNext, repeatOne): Boolean
 *  - com.example.data.downloader.YouTubeStreamPicker.pickBestAudio(candidates): AudioCandidate?
 *      data class AudioCandidate(url: String, mimeType: String, bitrateKbps: Int, isVideo: Boolean)
 */

import com.example.data.downloader.AudioCandidate
import com.example.data.downloader.YouTubeStreamPicker
import com.example.data.player.Crossfade
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CrossfadeLogicSpecTest {

    private fun near(a: Float, b: Float, eps: Float = 0.02f) = abs(a - b) <= eps

    @Test
    fun `TC-CF-01 duong cong equal-power - dau, giua, cuoi`() {
        val (o0, i0) = Crossfade.equalPowerGains(0f)
        assertTrue(near(o0, 1f) && near(i0, 0f))
        val (o1, i1) = Crossfade.equalPowerGains(1f)
        assertTrue(near(o1, 0f) && near(i1, 1f))
        val (oh, ih) = Crossfade.equalPowerGains(0.5f)
        assertTrue("giua phai ~0.707: $oh $ih", near(oh, 0.707f) && near(ih, 0.707f))
    }

    @Test
    fun `TC-CF-02 tong cong suat khong doi (khong bi trung giua 2 bai), bai cu giam deu, bai moi tang deu`() {
        var lastO = 2f; var lastI = -1f
        for (k in 0..20) {
            val (o, i) = Crossfade.equalPowerGains(k / 20f)
            assertTrue("cong suat lech tai $k: ${o * o + i * i}", near(o * o + i * i, 1f, 0.03f))
            assertTrue(o <= lastO + 1e-4f && i >= lastI - 1e-4f)
            lastO = o; lastI = i
        }
        assertEquals(Crossfade.equalPowerGains(-3f), Crossfade.equalPowerGains(0f))
        assertEquals(Crossfade.equalPowerGains(7f), Crossfade.equalPowerGains(1f))
    }

    @Test
    fun `TC-CF-03 shouldStart - chi bat dau khi con lai it hon thoi gian crossfade va co bai ke`() {
        assertTrue(Crossfade.shouldStart(195_000, 200_000, 6_000, hasNext = true, repeatOne = false))
        assertFalse(Crossfade.shouldStart(190_000, 200_000, 6_000, hasNext = true, repeatOne = false))
        assertFalse("khong co bai ke", Crossfade.shouldStart(197_000, 200_000, 6_000, hasNext = false, repeatOne = false))
        assertFalse("lap 1 bai", Crossfade.shouldStart(197_000, 200_000, 6_000, hasNext = true, repeatOne = true))
        assertFalse("crossfade tat", Crossfade.shouldStart(199_000, 200_000, 0, hasNext = true, repeatOne = false))
        assertFalse("thoi luong chua biet", Crossfade.shouldStart(1_000, 0, 6_000, hasNext = true, repeatOne = false))
        assertFalse("bai qua ngan (< 2 lan crossfade)", Crossfade.shouldStart(9_000, 10_000, 6_000, hasNext = true, repeatOne = false))
    }

    @Test
    fun `TC-YT-01 chon luong tieng tot nhat - bo luong video, bitrate cao nhat, bang nhau thi uu tien m4a`() {
        val c = listOf(
            AudioCandidate("v", "video/mp4", 2000, isVideo = true),
            AudioCandidate("a48", "audio/webm", 48, false),
            AudioCandidate("a128m4a", "audio/mp4", 128, false),
            AudioCandidate("a128webm", "audio/webm", 128, false),
            AudioCandidate("a70", "audio/webm", 70, false)
        )
        assertEquals("a128m4a", YouTubeStreamPicker.pickBestAudio(c)?.url)
        assertEquals("a160", YouTubeStreamPicker.pickBestAudio(c + AudioCandidate("a160", "audio/webm", 160, false))?.url)
        assertNull(YouTubeStreamPicker.pickBestAudio(listOf(AudioCandidate("v", "video/mp4", 999, true))))
        assertNull(YouTubeStreamPicker.pickBestAudio(emptyList()))
    }
}

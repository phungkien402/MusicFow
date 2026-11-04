package com.example.spec

/*
 * DO THAT TREN DIEN THOAI (Claude viet): goi YouTube that qua NewPipeExtractor, tai luong tieng that.
 * Chay bang adb shell am instrument. Ket qua in logcat tag "YTREAL". Xoa file tai xong.
 */

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.downloader.AudioLinkExtractor
import com.example.data.downloader.NewPipeYouTubeResolver
import com.example.data.model.AudioFormat
import com.example.data.model.AudioQuality
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class YouTubeRealDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun yt1_resolve_va_tai_that() = runBlocking {
        val t0 = System.currentTimeMillis()
        val info = NewPipeYouTubeResolver().resolve("WGfg0lZL9rU").getOrThrow()
        val t1 = System.currentTimeMillis()
        Log.i("YTREAL", "resolve ${t1 - t0}ms title='${info.title}' uploader='${info.uploader}' dur=${info.durationMs} audio=${info.audio?.mimeType} ${info.audio?.bitrateKbps}kbps")
        assertNotNull("khong co luong tieng", info.audio)
        assertTrue(info.title.isNotBlank())

        val ex = AudioLinkExtractor(context)
        val meta = ex.analyzeUrl("https://youtu.be/WGfg0lZL9rU").getOrThrow()
        var lastPct = 0
        val t2 = System.currentTimeMillis()
        val song = ex.downloadAudio(meta, AudioQuality.AUTO) { p, d, t, s -> if (p >= lastPct + 20) { lastPct = p; Log.i("YTREAL", "tien do $p% $d/$t $s") } }.getOrThrow()
        val t3 = System.currentTimeMillis()
        val f = File(song.filePath)
        Log.i("YTREAL", "tai xong ${t3 - t2}ms file=${f.name} size=${f.length()} format=${song.format} bitrate=${song.bitrate} dur=${song.durationMs}")
        try {
            assertTrue("file qua nho", f.length() > 1_000_000)
            assertTrue("dinh dang la: ${song.format}", song.format == AudioFormat.M4A || song.format == AudioFormat.WEBM)
            assertTrue("thoi luong sai: ${song.durationMs}", song.durationMs > 60_000)
        } finally {
            f.delete()
            song.thumbnailUrl?.let { if (it.startsWith("/")) File(it).delete() }
        }
    }
}

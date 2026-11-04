package com.example.spec

/*
 * HOP DONG DOT D (Claude viet). KHONG SUA FILE NAY.
 * Tai nhac chay nen bang WorkManager: com.example.data.downloader.DownloadWorker (CoroutineWorker).
 *  - DownloadWorker.buildInput(metadata, quality, targetPlaylistId): Data
 *  - Thanh cong -> Result.success(output KEY_SONG_ID = id bai da luu), bai nam trong DB (MusicDatabase.getInstance), vao playlist neu co.
 *  - That bai: runAttemptCount + 1 < MAX_ATTEMPTS -> Result.retry(); nguoc lai Result.failure(output KEY_ERROR = thong diep).
 *  - Link da co trong thu vien (SourceKey trung) -> Result.success voi KEY_SONG_ID = id bai cu, KHONG tai lai, KHONG them ban sao.
 */

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.data.downloader.DownloadWorker
import com.example.data.downloader.ParsedLinkMetadata
import com.example.data.local.MusicDatabase
import com.example.data.model.AudioQuality
import com.example.data.model.SourceType
import com.example.data.repository.MusicRepository
import kotlinx.coroutines.flow.first
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadWorkerSpecTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        MusicDatabase.destroyInstance()
        context.deleteDatabase("soundflow_music.db")
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        MusicDatabase.destroyInstance()
    }

    private fun repo() = MusicRepository(context, MusicDatabase.getInstance(context).musicDao())

    private fun meta(path: String = "/nhac/bai.mp3", title: String = "Bai nen"): ParsedLinkMetadata {
        val url = server.url(path).toString()
        return ParsedLinkMetadata(url, SourceType.DIRECT_URL, title, "Web", null, 180_000L, url)
    }

    private fun mp3(size: Int = 30_000) = Buffer().write("ID3".toByteArray() + ByteArray(size) { (it % 251).toByte() })

    private fun worker(meta: ParsedLinkMetadata, playlistId: Long? = null, attempt: Int = 0): DownloadWorker =
        TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(DownloadWorker.buildInput(meta, AudioQuality.AUTO, playlistId))
            .setRunAttemptCount(attempt)
            .build()

    @Test
    fun `TC-WK-01 tai thanh cong - tra id bai, bai co trong DB va trong playlist dich`() = runBlocking {
        val pid = repo().createPlaylist("Nen", "", "#00F5FF")
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(mp3()))
        val result = worker(meta(), pid).doWork()
        assertTrue("ket qua: $result", result is ListenableWorker.Result.Success)
        val id = result.outputData.getLong(DownloadWorker.KEY_SONG_ID, -1)
        assertTrue(id > 0)
        assertEquals("Bai nen", repo().allSongs.first().single { it.id == id }.title)
        assertEquals(listOf(id), repo().getSongsForPlaylist(pid).first().map { it.id })
    }

    @Test
    fun `TC-WK-02 loi lan dau - xin chay lai (retry), khong luu gi`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val result = worker(meta(), attempt = 0).doWork()
        assertTrue("ket qua: $result", result is ListenableWorker.Result.Retry)
        assertTrue(repo().allSongs.first().isEmpty())
    }

    @Test
    fun `TC-WK-03 het so lan thu - that bai kem thong diep loi`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val result = worker(meta(), attempt = DownloadWorker.MAX_ATTEMPTS - 1).doWork()
        assertTrue("ket qua: $result", result is ListenableWorker.Result.Failure)
        assertFalse(result.outputData.getString(DownloadWorker.KEY_ERROR).isNullOrBlank())
    }

    @Test
    fun `TC-WK-04 link da tai roi - khong tai lai, tra id bai cu`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(mp3()))
        val first = worker(meta()).doWork()
        val oldId = first.outputData.getLong(DownloadWorker.KEY_SONG_ID, -1)
        val requestsBefore = server.requestCount
        val second = worker(meta()).doWork()
        assertTrue(second is ListenableWorker.Result.Success)
        assertEquals(oldId, second.outputData.getLong(DownloadWorker.KEY_SONG_ID, -1))
        assertEquals(requestsBefore, server.requestCount)
        assertEquals(1, repo().allSongs.first().size)
    }

    @Test
    fun `TC-WK-05 buildInput giu du thong tin (ten, nghe si, nguon, chat luong, playlist)`() {
        val m = meta(title = "Lạc Trôi").copy(artist = "Sơn Tùng M-TP", thumbnailUrl = "https://img/x.jpg")
        val data = DownloadWorker.buildInput(m, AudioQuality.FLAC_LOSSLESS, 7L)
        val back = DownloadWorker.readInput(data)
        assertEquals(m, back.metadata)
        assertEquals(AudioQuality.FLAC_LOSSLESS, back.quality)
        assertEquals(7L, back.targetPlaylistId)
        assertNull(DownloadWorker.readInput(DownloadWorker.buildInput(m, AudioQuality.AUTO, null)).targetPlaylistId)
    }
}

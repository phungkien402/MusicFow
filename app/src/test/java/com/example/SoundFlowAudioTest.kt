package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.downloader.SampleAudioGenerator
import com.example.data.model.AudioFormat
import com.example.data.model.AudioQuality
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoundFlowAudioTest {

    @Test
    fun testAudioFormatParsing() {
        assertEquals(AudioFormat.MP3, AudioFormat.fromExtension("mp3"))
        assertEquals(AudioFormat.FLAC, AudioFormat.fromExtension("flac"))
        assertEquals(AudioFormat.WAV, AudioFormat.fromExtension("wav"))
        assertEquals(AudioFormat.M4A, AudioFormat.fromExtension("m4a"))
        assertTrue(AudioFormat.FLAC.isLossless)
        assertTrue(AudioFormat.WAV.isLossless)
        assertFalse(AudioFormat.MP3.isLossless)
    }

    @Test
    fun testAudioQualityPresets() {
        val qualities = AudioQuality.QUALITIES
        assertTrue(qualities.isNotEmpty())
        val defaultQuality = AudioQuality.DEFAULT
        assertEquals("320 kbps", defaultQuality.bitrate)
    }

    @Test
    fun testSampleWavGeneration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wavFile = SampleAudioGenerator.createSampleWav(
            context = context,
            fileName = "test_track.wav",
            durationSeconds = 2,
            chordFrequencies = listOf(440.0, 523.25)
        )

        assertTrue(wavFile.exists())
        assertTrue(wavFile.length() > 44) // Contains RIFF header + PCM data
    }

    @Test
    fun testEqualizerPresets() {
        val presets = com.example.data.player.EqualizerPreset.values()
        assertTrue(presets.contains(com.example.data.player.EqualizerPreset.HIFI_MASTER))
        assertTrue(presets.contains(com.example.data.player.EqualizerPreset.BALANCED))
        assertTrue(presets.contains(com.example.data.player.EqualizerPreset.TIKTOK_BOOST))
        val tiktokPreset = com.example.data.player.EqualizerPreset.TIKTOK_BOOST
        assertEquals("TikTok Bass Đầm", tiktokPreset.label)
        assertTrue(tiktokPreset.description.contains("TikTok"))

        val reverbModes = com.example.data.player.ReverbMode.values()
        assertTrue(reverbModes.contains(com.example.data.player.ReverbMode.OFF))
        assertTrue(reverbModes.contains(com.example.data.player.ReverbMode.STUDIO))
    }

    @Test
    fun testAudioFileExtensionHandling() {
        val extensions = listOf("mp3", "m4a", "wav", "flac", "aac", "ogg")
        for (ext in extensions) {
            val format = AudioFormat.fromExtension(ext)
            assertNotNull(format)
            assertEquals(ext, format.extension)
        }
    }

    @Test
    fun testDeletedSongStopsWhenQueueEmpty() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val player = com.example.data.player.AudioPlayerManager(context)
        val song = com.example.data.model.Song(
            id = 999L,
            title = "Test Song",
            artist = "Artist",
            durationMs = 180000L,
            filePath = "/test/path.mp3"
        )
        player.setQueue(listOf(song), 0)
        assertEquals(song.id, player.currentSong.value?.id)

        player.onSongDeleted(song)
        assertNull(player.currentSong.value)
        assertFalse(player.isPlaying.value)
        assertTrue(player.playQueue.value.isEmpty())
    }

    @Test
    fun testDeletedSongAdvancesToNextWhenQueueHasRemaining() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val player = com.example.data.player.AudioPlayerManager(context)
        val song1 = com.example.data.model.Song(
            id = 101L,
            title = "Song 1",
            artist = "Artist 1",
            durationMs = 180000L,
            filePath = "/test/path1.mp3"
        )
        val song2 = com.example.data.model.Song(
            id = 102L,
            title = "Song 2",
            artist = "Artist 2",
            durationMs = 210000L,
            filePath = "/test/path2.mp3"
        )
        player.setQueue(listOf(song1, song2), 0)
        assertEquals(song1.id, player.currentSong.value?.id)

        player.onSongDeleted(song1)
        assertEquals(song2.id, player.currentSong.value?.id)
        assertEquals(1, player.playQueue.value.size)
        assertEquals(song2.id, player.playQueue.value[0].id)
    }
}

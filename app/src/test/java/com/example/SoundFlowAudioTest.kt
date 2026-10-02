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
    fun testAudioFileExtensionHandling() {
        val extensions = listOf("mp3", "m4a", "wav", "flac", "aac", "ogg")
        for (ext in extensions) {
            val format = AudioFormat.fromExtension(ext)
            assertNotNull(format)
            assertEquals(ext, format.extension)
        }
    }
}

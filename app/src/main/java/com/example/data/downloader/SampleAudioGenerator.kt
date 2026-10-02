package com.example.data.downloader

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

object SampleAudioGenerator {

    /**
     * Generates a playable WAV audio file with musical synthesizer chords/arpeggio
     * @param sampleRate usually 44100
     * @param durationSeconds length of the audio track
     * @param chordFrequencies base frequencies in Hz for the chord progression
     */
    fun createSampleWav(
        context: Context,
        fileName: String,
        durationSeconds: Int = 18,
        chordFrequencies: List<Double> = listOf(261.63, 329.63, 392.00, 523.25), // C major chord
        tempoBpm: Int = 100
    ): File {
        val musicDir = context.getExternalFilesDir(null) ?: context.filesDir
        val audioDir = File(musicDir, "offline_tracks").apply { mkdirs() }
        val targetFile = File(audioDir, fileName)

        if (targetFile.exists() && targetFile.length() > 1000) {
            return targetFile
        }

        return createSampleWav(targetFile, durationSeconds, chordFrequencies, tempoBpm)
    }

    /**
     * Directly generates and writes valid WAV audio to the specified target file.
     */
    fun createSampleWav(
        targetFile: File,
        durationSeconds: Int = 18,
        chordFrequencies: List<Double> = listOf(261.63, 329.63, 392.00, 523.25),
        tempoBpm: Int = 100
    ): File {
        targetFile.parentFile?.mkdirs()
        if (targetFile.exists() && targetFile.length() > 1000) {
            return targetFile
        }

        val sampleRate = 44100
        val numSamples = durationSeconds * sampleRate
        val pcmData = ShortArray(numSamples)

        val beatDurationSamples = (60.0 / tempoBpm * sampleRate).toInt()
        val numChords = chordFrequencies.size

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val chordIndex = ((i / (beatDurationSamples * 2)) % numChords)
            val baseFreq = chordFrequencies[chordIndex]

            // Multi-oscillator synthesis: Root, third, fifth harmonic + subtle bass sub
            val osc1 = sin(2.0 * Math.PI * baseFreq * t)
            val osc2 = sin(2.0 * Math.PI * (baseFreq * 1.25) * t) * 0.5 // major third
            val osc3 = sin(2.0 * Math.PI * (baseFreq * 1.5) * t) * 0.4  // fifth
            val subBass = sin(2.0 * Math.PI * (baseFreq * 0.5) * t) * 0.6 // sub-octave

            // Rhythmic pulse envelope
            val pulse = (i % beatDurationSamples).toDouble() / beatDurationSamples
            val envelope = kotlin.math.exp(-3.5 * pulse)

            val mixed = (osc1 + osc2 + osc3 + subBass) * envelope * 0.35
            val clamped = mixed.coerceIn(-1.0, 1.0)
            pcmData[i] = (clamped * Short.MAX_VALUE).toInt().toShort()
        }

        writeWavFile(targetFile, pcmData, sampleRate)
        return targetFile
    }

    private fun writeWavFile(file: File, pcmData: ShortArray, sampleRate: Int) {
        val totalAudioLen = pcmData.size * 2
        val totalDataLen = totalAudioLen + 36
        val channels = 1
        val byteRate = sampleRate * channels * 2

        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk
        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalDataLen)
        buffer.put("WAVE".toByteArray())

        // fmt chunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // Subchunk1Size for PCM
        buffer.putShort(1) // AudioFormat 1 = PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort((channels * 2).toShort()) // BlockAlign
        buffer.putShort(16) // BitsPerSample

        // data chunk
        buffer.put("data".toByteArray())
        buffer.putInt(totalAudioLen)

        FileOutputStream(file).use { fos ->
            fos.write(header)
            val byteBuffer = ByteBuffer.allocate(pcmData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcmData) {
                byteBuffer.putShort(sample)
            }
            fos.write(byteBuffer.array())
        }
    }
}

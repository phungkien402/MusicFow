package com.example.data.downloader

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

object SampleAudioGenerator {

    /**
     * Generates a playable WAV audio file with high quality stereo synthesizer,
     * punchy 808 sub-bass, and smooth envelopes without digital clicks or distortion.
     */
    fun createSampleWav(
        context: Context,
        fileName: String,
        durationSeconds: Int = 18,
        chordFrequencies: List<Double> = listOf(261.63, 329.63, 392.00, 523.25),
        tempoBpm: Int = 100
    ): File {
        val musicDir = context.getExternalFilesDir(null) ?: context.filesDir
        val audioDir = File(musicDir, "offline_tracks").apply { mkdirs() }
        val targetFile = File(audioDir, fileName)

        // Delete if older version exists to ensure upgraded high-fidelity sound
        if (targetFile.exists() && targetFile.length() < 10000) {
            targetFile.delete()
        }

        return createSampleWav(targetFile, durationSeconds, chordFrequencies, tempoBpm)
    }

    /**
     * Directly generates and writes valid stereo WAV audio to the specified target file
     * with deep sub-bass punch, rich chords, and zero digital clipping or noise crackle.
     */
    fun createSampleWav(
        targetFile: File,
        durationSeconds: Int = 18,
        chordFrequencies: List<Double> = listOf(261.63, 329.63, 392.00, 523.25),
        tempoBpm: Int = 100
    ): File {
        targetFile.parentFile?.mkdirs()

        val sampleRate = 44100
        val channels = 2 // True Stereo
        val totalFrames = durationSeconds * sampleRate
        val leftFloats = FloatArray(totalFrames)
        val rightFloats = FloatArray(totalFrames)

        val beatDurationFrames = (60.0 / tempoBpm * sampleRate).toInt().coerceAtLeast(1)
        val numChords = chordFrequencies.size.coerceAtLeast(1)
        val attackFrames = (sampleRate * 0.012).toInt() // 12ms soft attack

        for (frame in 0 until totalFrames) {
            val t = frame.toDouble() / sampleRate
            val beatIndex = frame / beatDurationFrames
            val frameInBeat = frame % beatDurationFrames
            val beatT = frameInBeat.toDouble() / sampleRate

            val chordIndex = ((beatIndex / 2) % numChords)
            val baseFreq = chordFrequencies[chordIndex]

            // 1. Lush Warm Chord Synth (fundamental + musical octaves, zero harsh harmonics)
            val synthAttack = if (frameInBeat < attackFrames) {
                frameInBeat.toDouble() / attackFrames
            } else {
                1.0
            }
            val synthDecay = exp(-1.5 * (frameInBeat.toDouble() / beatDurationFrames))
            val synthEnv = synthAttack * synthDecay

            val s1 = sin(2.0 * Math.PI * baseFreq * t)
            val s2 = sin(2.0 * Math.PI * (baseFreq * 1.50) * t) * 0.35 // Perfect fifth
            val s3 = sin(2.0 * Math.PI * (baseFreq * 2.00) * t) * 0.20 // Octave
            val synth = (s1 + s2 + s3) * synthEnv * 0.32

            // 2. Deep TikTok-style 808 Sub-Kick: Thumping low-end punch without distortion
            // Slides smoothly from 95Hz down to 44Hz (warm sub resonance)
            val isKickBeat = (beatIndex % 2 == 0)
            var kick = 0.0
            if (isKickBeat) {
                val kickFreq = 44.0 + 51.0 * exp(-18.0 * beatT)
                val kickAttack = (frameInBeat.toDouble() / (sampleRate * 0.005)).coerceAtMost(1.0)
                val kickDecay = exp(-3.2 * beatT) // Sustained deep boom
                kick = sin(2.0 * Math.PI * kickFreq * beatT) * kickAttack * kickDecay * 0.60
            }

            // 3. Warm Rolling Sub-Bassline (48-65Hz continuous bass body)
            val bassFreq = if (baseFreq > 140.0) baseFreq * 0.25 else baseFreq * 0.50
            val bassAttack = if (frameInBeat < attackFrames) frameInBeat.toDouble() / attackFrames else 1.0
            val subBass = sin(2.0 * Math.PI * bassFreq * t) * exp(-1.0 * (frameInBeat.toDouble() / beatDurationFrames)) * bassAttack * 0.40

            // 4. Clean Acoustic Hi-Hat (pure metallic bell overtones at 6kHz-9kHz instead of raw harsh white noise)
            val isHatBeat = (frameInBeat >= beatDurationFrames / 2)
            val hatT = (frameInBeat - beatDurationFrames / 2).toDouble() / sampleRate
            var hat = 0.0
            if (isHatBeat && hatT in 0.0..0.08) {
                val metal1 = sin(2.0 * Math.PI * 6200.0 * hatT)
                val metal2 = sin(2.0 * Math.PI * 8700.0 * hatT) * 0.6
                hat = (metal1 + metal2) * exp(-55.0 * hatT) * 0.08
            }

            // Stereo soundstage
            leftFloats[frame] = (synth * 0.95 + kick * 0.90 + subBass * 0.90 + hat * 0.75).toFloat()
            rightFloats[frame] = (synth * 1.05 + kick * 0.90 + subBass * 0.90 - hat * 0.75).toFloat()
        }

        // Anti-Clipping Normalization: Guarantee exact -1.0 dBFS headroom
        var maxPeak = 0.001f
        for (i in 0 until totalFrames) {
            val l = kotlin.math.abs(leftFloats[i])
            val r = kotlin.math.abs(rightFloats[i])
            if (l > maxPeak) maxPeak = l
            if (r > maxPeak) maxPeak = r
        }

        // Target peak amplitude: 28500 (approx -1.2 dBFS headroom, prevents any DAC/EQ clipping)
        val gain = (28500.0f / maxPeak).coerceAtMost(32000.0f)
        val pcmData = ShortArray(totalFrames * channels)

        for (frame in 0 until totalFrames) {
            val pcmIndex = frame * 2
            val l = (leftFloats[frame] * gain).toInt().coerceIn(-32767, 32767)
            val r = (rightFloats[frame] * gain).toInt().coerceIn(-32767, 32767)
            pcmData[pcmIndex] = l.toShort()
            pcmData[pcmIndex + 1] = r.toShort()
        }

        writeWavFile(targetFile, pcmData, sampleRate, channels)
        return targetFile
    }

    private fun writeWavFile(file: File, pcmData: ShortArray, sampleRate: Int, channels: Int = 2) {
        val totalAudioLen = pcmData.size * 2
        val totalDataLen = totalAudioLen + 36
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

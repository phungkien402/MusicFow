package com.example.data.downloader

import com.example.data.model.AudioFormat

object AudioFormatDetector {
    fun detect(header: ByteArray): AudioFormat? {
        if (header.size < 2) return null

        // ID3 -> MP3
        if (header.size >= 3 &&
            header[0] == 'I'.code.toByte() &&
            header[1] == 'D'.code.toByte() &&
            header[2] == '3'.code.toByte()
        ) {
            return AudioFormat.MP3
        }

        // byte0=0xFF va (byte1 & 0xE0)==0xE0: neu (byte1 & 0x06)==0 thi AAC (ADTS) nguoc lai MP3
        val b0 = header[0].toInt() and 0xFF
        val b1 = header[1].toInt() and 0xFF
        if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
            return if ((b1 and 0x06) == 0) AudioFormat.AAC else AudioFormat.MP3
        }

        // 1A 45 DF A3 -> WEBM
        if (header.size >= 4 &&
            (header[0].toInt() and 0xFF) == 0x1A &&
            (header[1].toInt() and 0xFF) == 0x45 &&
            (header[2].toInt() and 0xFF) == 0xDF &&
            (header[3].toInt() and 0xFF) == 0xA3
        ) {
            return AudioFormat.WEBM
        }

        // "fLaC" -> FLAC
        if (header.size >= 4 &&
            header[0] == 'f'.code.toByte() &&
            header[1] == 'L'.code.toByte() &&
            header[2] == 'a'.code.toByte() &&
            header[3] == 'C'.code.toByte()
        ) {
            return AudioFormat.FLAC
        }

        // "OggS" -> OGG
        if (header.size >= 4 &&
            header[0] == 'O'.code.toByte() &&
            header[1] == 'g'.code.toByte() &&
            header[2] == 'g'.code.toByte() &&
            header[3] == 'S'.code.toByte()
        ) {
            return AudioFormat.OGG
        }

        // "RIFF".. "WAVE" -> WAV
        if (header.size >= 12 &&
            header[0] == 'R'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() &&
            header[3] == 'F'.code.toByte()
        ) {
            val maxSearch = (header.size - 4).coerceAtMost(128)
            for (i in 4..maxSearch) {
                if (header[i] == 'W'.code.toByte() &&
                    header[i + 1] == 'A'.code.toByte() &&
                    header[i + 2] == 'V'.code.toByte() &&
                    header[i + 3] == 'E'.code.toByte()
                ) {
                    return AudioFormat.WAV
                }
            }
        }

        // byte[4..7]="ftyp" or ftyp box header -> M4A
        if (header.size >= 8) {
            val maxSearch = (header.size - 4).coerceAtMost(128)
            for (i in 0..maxSearch) {
                if (header[i] == 'f'.code.toByte() &&
                    header[i + 1] == 't'.code.toByte() &&
                    header[i + 2] == 'y'.code.toByte() &&
                    header[i + 3] == 'p'.code.toByte()
                ) {
                    return AudioFormat.M4A
                }
            }
        }

        return null
    }
}

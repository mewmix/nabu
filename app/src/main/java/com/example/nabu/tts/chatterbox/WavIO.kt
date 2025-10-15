package com.example.nabu.tts.chatterbox

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavIO {
    @Throws(IOException::class)
    fun readMonoFloat(file: File, expectedSampleRate: Int): FloatArray {
        file.inputStream().buffered().use { input ->
            val header = ByteArray(12)
            if (input.read(header) != header.size) {
                throw IOException("Invalid WAV header")
            }
            if (!header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray())) {
                throw IOException("Invalid WAV header: missing RIFF")
            }
            if (!header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())) {
                throw IOException("Invalid WAV header: missing WAVE")
            }

            var audioFormat = 0
            var numChannels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var dataRead = false

            while (true) {
                val chunkHeader = ByteArray(8)
                val read = input.read(chunkHeader)
                if (read == -1) break
                if (read != 8) throw IOException("Unexpected end of WAV chunks")

                val chunkId = String(chunkHeader, 0, 4)
                val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val chunkData = ByteArray(chunkSize)
                var offset = 0
                while (offset < chunkSize) {
                    val readCount = input.read(chunkData, offset, chunkSize - offset)
                    if (readCount == -1) {
                        throw IOException("Unexpected end of WAV data")
                    }
                    offset += readCount
                }

                when (chunkId) {
                    "fmt " -> {
                        val buffer = ByteBuffer.wrap(chunkData).order(ByteOrder.LITTLE_ENDIAN)
                        audioFormat = buffer.short.toInt() and 0xFFFF
                        numChannels = buffer.short.toInt() and 0xFFFF
                        sampleRate = buffer.int
                        buffer.int // byteRate (unused)
                        buffer.short // blockAlign (unused)
                        bitsPerSample = buffer.short.toInt() and 0xFFFF
                    }
                    "data" -> {
                        val bytesPerSample = bitsPerSample / 8
                        if (bytesPerSample <= 0 || numChannels <= 0) {
                            throw IOException("Invalid WAV format metadata")
                        }
                        val totalFrames = chunkSize / (bytesPerSample * numChannels)
                        val buffer = ByteBuffer.wrap(chunkData).order(ByteOrder.LITTLE_ENDIAN)
                        val floats = FloatArray(totalFrames)
                        if (audioFormat == 1 && bitsPerSample != 16) {
                            throw IOException("Unsupported PCM bit depth: $bitsPerSample")
                        }
                        if (audioFormat == 3 && bitsPerSample != 32) {
                            throw IOException("Unsupported float bit depth: $bitsPerSample")
                        }
                        for (frame in 0 until totalFrames) {
                            var sum = 0f
                            for (channel in 0 until numChannels) {
                                val sample = when (audioFormat) {
                                    1 -> buffer.short.toInt() / Short.MAX_VALUE.toFloat()
                                    3 -> {
                                        buffer.float
                                    }
                                    else -> throw IOException("Unsupported WAV format code: $audioFormat")
                                }
                                sum += sample
                            }
                            floats[frame] = sum / numChannels
                        }
                        if (sampleRate != expectedSampleRate) {
                            throw IOException("Expected sample rate $expectedSampleRate but found $sampleRate")
                        }
                        if (numChannels < 1) {
                            throw IOException("Invalid channel count: $numChannels")
                        }
                        dataRead = true
                        return floats
                    }
                    else -> {
                        // ignore other chunks
                    }
                }
            }
        }
        throw IOException("Missing data chunk in WAV file")
    }
}

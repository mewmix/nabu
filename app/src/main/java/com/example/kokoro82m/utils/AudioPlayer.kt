package com.example.kokoro82m.utils

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.example.kokoro82m.utils.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Lightweight audio player with optional debug logging. */

class AudioPlayer {
    private var audioTrack: AudioTrack? = null
    private var pcmData: ByteArray? = null
    private var position: Int = 0
    private var isPaused: Boolean = false
    private var isRunning: Boolean = false
    private val sampleRate = 22050

    fun prepare(audio: FloatArray) {
        DebugLogger.log("AudioPlayer prepare length=${audio.size}")
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        audioTrack = AudioTrack(
            attributes,
            AudioFormat.Builder()
                .setEncoding(audioFormat)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        val byteBuffer = ByteBuffer.allocate(audio.size * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val shortBuffer = byteBuffer.asShortBuffer()
        for (s in audio) {
            val pcmValue = (s * Short.MAX_VALUE).toInt().toShort()
            shortBuffer.put(pcmValue)
        }
        pcmData = byteBuffer.array()
        position = 0
    }

    suspend fun play() = withContext(Dispatchers.IO) {
        val track = audioTrack ?: return@withContext
        val data = pcmData ?: return@withContext
        DebugLogger.log("AudioPlayer start play")
        isRunning = true
        track.play()
        while (position < data.size) {
            if (isPaused) {
                withContext(Dispatchers.IO) { kotlinx.coroutines.delay(50) }
                continue
            }
            val written = track.write(data, position, data.size - position)
            if (written <= 0) break
            position += written
        }
        track.stop()
        track.release()
        DebugLogger.log("AudioPlayer finished play")
        isRunning = false
        position = 0
    }

    fun pause() {
        DebugLogger.log("AudioPlayer pause")
        isPaused = true
        audioTrack?.pause()
    }

    fun resume() {
        DebugLogger.log("AudioPlayer resume")
        isPaused = false
        audioTrack?.play()
    }

    fun isPlaying(): Boolean = isRunning && !isPaused
}

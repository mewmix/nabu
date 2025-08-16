package com.example.nabu.utils

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class KokoroAudioPlayer(
    private val scope: CoroutineScope,
    private val onStateChanged: (PlayerState) -> Unit
) : AudioPlayer {
    private var audioTrack: AudioTrack? = null
    private var pcmData: ByteArray? = null
    private var audioFloats: FloatArray? = null
    private var position: Int = 0
    private var currentState: PlayerState = PlayerState.IDLE
        set(value) {
            field = value
            onStateChanged(value)
        }

    private val sampleRate = 22050

    override fun prepare(audio: FloatArray, position: Int) {
        DebugLogger.log("KokoroAudioPlayer prepare length=${audio.size}, position=$position")
        release() // Release any existing track
        audioFloats = audio
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
        this.position = position
    }

    override fun play() {
        val track = audioTrack ?: return
        val data = pcmData ?: return

        if (currentState == PlayerState.PAUSED) {
            resume()
            return
        }

        scope.launch(Dispatchers.IO) {
            DebugLogger.log("KokoroAudioPlayer start play from position $position")
            currentState = PlayerState.PLAYING
            track.play()
            val chunkSize = 4096
            while (position < data.size && currentState == PlayerState.PLAYING && isActive) {
                val remaining = data.size - position
                val toWrite = min(chunkSize, remaining)
                val bytePos = position
                val written = track.write(data, position, toWrite)
                if (written > 0) {
                    val actualSamples = written / 2
                    audioFloats?.let { PcmTap.pushFloats(it, bytePos / 2, actualSamples) }
                    position += written
                } else {
                    break
                }
            }
            while (track.playbackHeadPosition < data.size / 2 && currentState == PlayerState.PLAYING && isActive) {
                kotlinx.coroutines.delay(10)
            }
            if (currentState != PlayerState.PAUSED) {
                stop()
            }
        }
    }

    override suspend fun playBlocking() {
        val track = audioTrack ?: return
        val data = pcmData ?: return

        if (currentState == PlayerState.PAUSED) {
            resume()
            return
        }

        withContext(Dispatchers.IO) {
            DebugLogger.log("KokoroAudioPlayer start play blocking from position $position")
            currentState = PlayerState.PLAYING
            track.play()
            val chunkSize = 4096
            while (position < data.size && currentState == PlayerState.PLAYING && coroutineContext.isActive) {
                val remaining = data.size - position
                val toWrite = min(chunkSize, remaining)
                val bytePos = position
                val written = track.write(data, position, toWrite)
                if (written > 0) {
                    val actualSamples = written / 2
                    audioFloats?.let { PcmTap.pushFloats(it, bytePos / 2, actualSamples) }
                    position += written
                } else {
                    break
                }
            }
            while (track.playbackHeadPosition < data.size / 2 && currentState == PlayerState.PLAYING && coroutineContext.isActive) {
                kotlinx.coroutines.delay(10)
            }
            if (currentState != PlayerState.PAUSED) {
                stop()
            }
        }
    }

    override fun pause() {
        if (currentState == PlayerState.PLAYING) {
            position += audioTrack?.playbackHeadPosition ?: 0
            audioTrack?.pause()
            currentState = PlayerState.PAUSED
            DebugLogger.log("KokoroAudioPlayer pause at $position")
        }
    }

    private fun resume() {
        if (currentState == PlayerState.PAUSED) {
            DebugLogger.log("KokoroAudioPlayer resume from $position")
            audioTrack?.play()
            currentState = PlayerState.PLAYING
        }
    }

    override fun stop() {
        if (currentState != PlayerState.IDLE) {
            DebugLogger.log("KokoroAudioPlayer stop")
            currentState = PlayerState.IDLE
            release()
        }
    }

    private fun release() {
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.release()
        audioTrack = null
        audioFloats = null
        position = 0
    }

    override fun isPlaying(): Boolean = currentState == PlayerState.PLAYING

    override fun getState(): PlayerState = currentState

    override fun getPosition(): Int {
        if (currentState == PlayerState.PLAYING) {
            return position + (audioTrack?.playbackHeadPosition ?: 0)
        }
        return position
    }
}

package com.example.nabu.utils

import android.media.AudioFormat
import android.media.AudioFormat.CHANNEL_OUT_MONO
import android.media.AudioManager
import android.media.AudioTrack
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

fun playAudio(
    audioData: FloatArray,
    sampleRate: Int,
    context: Context,
    scope: CoroutineScope,
    onComplete: () -> Unit
) {
    scope.launch(Dispatchers.IO) {
        PlaybackNotificationManager.show(context)

        val channelConfig = CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        val audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize,
            AudioTrack.MODE_STREAM
        )

        val byteBuffer = ByteBuffer.allocate(audioData.size * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val shortBuffer = byteBuffer.asShortBuffer()

        for (sample in audioData) {
            val pcmValue = (sample * Short.MAX_VALUE).toInt().toShort()
            shortBuffer.put(pcmValue)
        }

        audioTrack.play()

        val pcmBytes = byteBuffer.array()
        val chunkSize = 4096
        var pos = 0
        while (pos < pcmBytes.size) {
            val remaining = pcmBytes.size - pos
            val toWrite = min(chunkSize, remaining)
            val floatStart = pos / 2
            val written = audioTrack.write(pcmBytes, pos, toWrite)
            if (written > 0) {
                PcmTap.pushFloats(audioData, floatStart, written / 2)
                pos += written
            } else {
                break
            }
        }

        audioTrack.stop()
        audioTrack.release()

        PlaybackNotificationManager.hide(context)

        withContext(Dispatchers.Main) {
            onComplete()
        }
    }
}

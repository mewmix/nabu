package com.mewmix.nabu.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.mewmix.nabu.chat.LlmAudioInput
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class VoiceAttachmentRecorder(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var output: ByteArrayOutputStream? = null
    private val recording = AtomicBoolean(false)

    val isRecording: Boolean
        get() = recording.get()

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (recording.get()) return
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE / 2)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize
        )
        val audioOutput = ByteArrayOutputStream()
        audioRecord = recorder
        output = audioOutput
        recording.set(true)
        recorder.startRecording()
        recordingThread = Thread({
            val buffer = ByteArray(minBufferSize)
            while (recording.get()) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    synchronized(audioOutput) {
                        audioOutput.write(buffer, 0, read)
                    }
                }
            }
        }, "NabuVoiceAttachmentRecorder").apply { start() }
    }

    fun stop(): LlmAudioInput? {
        if (!recording.getAndSet(false)) return null
        val recorder = audioRecord
        audioRecord = null
        runCatching { recorder?.stop() }
        recordingThread?.join(1_000)
        recordingThread = null
        recorder?.release()
        val pcmBytes = synchronized(output ?: return null) {
            output?.toByteArray()
        } ?: return null
        output = null
        if (pcmBytes.isEmpty()) return null
        val wavBytes = createWavBytes(pcmBytes)
        return LlmAudioInput(
            bytes = wavBytes,
            displayName = "voice_${System.currentTimeMillis()}.wav"
        )
    }

    fun cancel() {
        if (!recording.getAndSet(false)) return
        val recorder = audioRecord
        audioRecord = null
        runCatching { recorder?.stop() }
        recordingThread?.join(1_000)
        recordingThread = null
        recorder?.release()
        output = null
    }

    private fun createWavBytes(pcmBytes: ByteArray): ByteArray {
        return ByteArrayOutputStream(pcmBytes.size + WAV_HEADER_BYTES).use { wav ->
            wav.write(createWavHeader(pcmBytes.size))
            wav.write(pcmBytes)
            wav.toByteArray()
        }
    }

    private fun createWavHeader(pcmByteCount: Int): ByteArray {
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcmByteCount)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * BYTES_PER_SAMPLE)
        header.putShort(BYTES_PER_SAMPLE.toShort())
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmByteCount)
        return header.array()
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val WAV_HEADER_BYTES = 44
    }
}

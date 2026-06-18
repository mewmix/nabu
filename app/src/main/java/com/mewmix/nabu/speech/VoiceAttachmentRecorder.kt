package com.mewmix.nabu.speech

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.mewmix.nabu.chat.LlmAudioInput
import java.io.File

class VoiceAttachmentRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isRecording: Boolean
        get() = recorder != null

    fun start() {
        if (recorder != null) return
        val dir = File(context.cacheDir, "voice_attachments").apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mediaRecorder.setAudioEncodingBitRate(96_000)
        mediaRecorder.setAudioSamplingRate(44_100)
        mediaRecorder.setOutputFile(file.absolutePath)
        mediaRecorder.prepare()
        mediaRecorder.start()
        recorder = mediaRecorder
        outputFile = file
    }

    fun stop(): LlmAudioInput? {
        val activeRecorder = recorder ?: return null
        val file = outputFile
        recorder = null
        outputFile = null
        return try {
            activeRecorder.stop()
            file?.takeIf { it.exists() && it.length() > 0L }?.let {
                LlmAudioInput(
                    bytes = it.readBytes(),
                    displayName = it.name
                )
            }
        } catch (_: RuntimeException) {
            null
        } finally {
            activeRecorder.release()
            file?.delete()
        }
    }

    fun cancel() {
        val activeRecorder = recorder ?: return
        val file = outputFile
        recorder = null
        outputFile = null
        runCatching { activeRecorder.stop() }
        activeRecorder.release()
        file?.delete()
    }
}

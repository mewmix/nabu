package com.mewmix.nabu.voicestudio.android

import android.content.Context
import android.media.MediaPlayer
import com.mewmix.nabu.kokoro.KokoroEngine
import com.mewmix.nabu.utils.PhonemeConverter
import com.mewmix.nabu.utils.StyleLoader
import com.mewmix.nabu.voicestudio.core.*
import java.io.File
import java.io.FileOutputStream

class AndroidOnnxTtsEngine(
    private val context: Context,
    private val kokoroEngine: KokoroEngine,
    private val phonemeConverter: PhonemeConverter,
    private val styleLoader: StyleLoader
) : TtsEngine {
    override suspend fun isReady(modelId: String): Boolean = true

    override suspend fun synthesize(request: TtsSynthesisRequest, onProgress: suspend (GenerationProgress) -> Unit): TtsSynthesisResult {
        onProgress(GenerationProgress("normalizing", 0.2f))
        val phonemes = phonemeConverter.textToPhonemes(request.text)
        onProgress(GenerationProgress("inference", 0.6f))
        val style = styleLoader.getStyleArray(request.voiceId)
        val audio = kokoroEngine.synth(com.mewmix.nabu.utils.Tokenizer.tokenize("$phonemes").let {
            LongArray(it.size + 2).also { arr -> arr[0] = 0; it.copyInto(arr, 1); arr[arr.lastIndex] = 0 }
        }, style = style, speed = request.speed)
        onProgress(GenerationProgress("encoding", 0.9f))
        val pcm16 = ByteArray(audio.size * 2)
        audio.forEachIndexed { i, sample ->
            val s = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            pcm16[i * 2] = (s.toInt() and 0xFF).toByte()
            pcm16[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        onProgress(GenerationProgress("done", 1f))
        return TtsSynthesisResult(pcm16, kokoroEngine.sampleRate, 1)
    }
}

class AndroidAudioExporter(private val context: Context) : AudioExporter {
    override suspend fun export(pcm16: ByteArray, sampleRateHz: Int, channelCount: Int, format: ExportFormat, fileName: String): ExportedAudio {
        val outFile = File(context.cacheDir, fileName)
        FileOutputStream(outFile).use { fos ->
            fos.write(wavHeader(pcm16.size, sampleRateHz, channelCount))
            fos.write(pcm16)
        }
        return ExportedAudio(outFile.absolutePath, ExportFormat.WAV, outFile.length())
    }
    private fun wavHeader(dataSize: Int, sampleRate: Int, channels: Int): ByteArray {
        val byteRate = sampleRate * channels * 2
        val chunkSize = 36 + dataSize
        return byteArrayOf(
            'R'.code.toByte(),'I'.code.toByte(),'F'.code.toByte(),'F'.code.toByte(),
            chunkSize.toByte(),(chunkSize shr 8).toByte(),(chunkSize shr 16).toByte(),(chunkSize shr 24).toByte(),
            'W'.code.toByte(),'A'.code.toByte(),'V'.code.toByte(),'E'.code.toByte(),
            'f'.code.toByte(),'m'.code.toByte(),'t'.code.toByte(),' '.code.toByte(),
            16,0,0,0,1,0,channels.toByte(),0,
            sampleRate.toByte(),(sampleRate shr 8).toByte(),(sampleRate shr 16).toByte(),(sampleRate shr 24).toByte(),
            byteRate.toByte(),(byteRate shr 8).toByte(),(byteRate shr 16).toByte(),(byteRate shr 24).toByte(),
            (channels * 2).toByte(),0,16,0,
            'd'.code.toByte(),'a'.code.toByte(),'t'.code.toByte(),'a'.code.toByte(),
            dataSize.toByte(),(dataSize shr 8).toByte(),(dataSize shr 16).toByte(),(dataSize shr 24).toByte()
        )
    }
}

class AndroidAudioPlayer : AudioPlayer {
    private var mediaPlayer: MediaPlayer? = null
    override suspend fun load(path: String) { mediaPlayer?.release(); mediaPlayer = MediaPlayer().apply { setDataSource(path); prepare() } }
    override fun play() { mediaPlayer?.start() }
    override fun pause() { mediaPlayer?.pause() }
    override fun stop() { mediaPlayer?.stop() }
    override fun release() { mediaPlayer?.release(); mediaPlayer = null }
}

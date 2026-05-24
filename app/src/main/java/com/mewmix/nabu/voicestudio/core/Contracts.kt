package com.mewmix.nabu.voicestudio.core

interface TtsEngine {
    suspend fun isReady(modelId: String): Boolean
    suspend fun synthesize(request: TtsSynthesisRequest, onProgress: suspend (GenerationProgress) -> Unit): TtsSynthesisResult
}

interface AudioExporter {
    suspend fun export(pcm16: ByteArray, sampleRateHz: Int, channelCount: Int, format: ExportFormat, fileName: String): ExportedAudio
}

interface AudioPlayer {
    suspend fun load(path: String)
    fun play()
    fun pause()
    fun stop()
    fun release()
}

interface TextImporter {
    suspend fun importText(source: ImportSource): ImportedText
}

class VoicePresetCatalog(private val presets: List<VoicePreset>) {
    fun all(): List<VoicePreset> = presets
    fun getById(id: String): VoicePreset = presets.first { it.id == id }
}

class NarrationGenerator(
    private val ttsEngine: TtsEngine,
    private val audioExporter: AudioExporter,
    private val voicePresetCatalog: VoicePresetCatalog
) {
    suspend fun generate(request: NarrationRequest, onProgress: suspend (GenerationProgress) -> Unit): NarrationResult {
        val voice = voicePresetCatalog.getById(request.voicePresetId)
        val synthesis = ttsEngine.synthesize(TtsSynthesisRequest(request.text, voice.modelId, voice.voiceId, request.speed, 24_000), onProgress)
        val exported = audioExporter.export(synthesis.pcm16, synthesis.sampleRateHz, synthesis.channelCount, request.outputFormat, "${request.draftId}.wav")
        return NarrationResult(request.draftId, exported.path, exported.format, 0, synthesis.sampleRateHz, System.currentTimeMillis())
    }
}

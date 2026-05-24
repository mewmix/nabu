package com.mewmix.nabu.voicestudio.core

enum class VoiceStudioStep { SETUP_REQUIRED, READY_TO_IMPORT, EDITING_TEXT, CHOOSING_VOICE, GENERATING, COMPLETE, FAILED, CANCELED }

enum class SourceType { PASTE, TXT, PDF, EPUB, ARTICLE }

enum class ExportFormat { WAV }

data class NarrationDraft(
    val id: String,
    val title: String,
    val sourceType: SourceType,
    val text: String,
    val wordCount: Int,
    val estimatedDurationSeconds: Int,
    val createdAtEpochMs: Long
)

data class VoicePreset(
    val id: String,
    val displayName: String,
    val styleLabel: String,
    val engineKey: String,
    val modelId: String,
    val voiceId: String,
    val defaultSpeed: Float,
    val previewText: String
)

data class NarrationRequest(val draftId: String, val text: String, val voicePresetId: String, val speed: Float, val outputFormat: ExportFormat)

data class NarrationResult(val projectId: String, val outputPath: String, val format: ExportFormat, val durationSeconds: Int, val sampleRateHz: Int, val createdAtEpochMs: Long)

data class GenerationProgress(val stage: String, val fraction: Float)

data class TtsSynthesisRequest(val text: String, val modelId: String, val voiceId: String, val speed: Float, val sampleRateHz: Int)

data class TtsSynthesisResult(val pcm16: ByteArray, val sampleRateHz: Int, val channelCount: Int)

data class ExportedAudio(val path: String, val format: ExportFormat, val sizeBytes: Long)

data class ImportedText(val text: String, val sourceType: SourceType)

sealed class ImportSource {
    data class PlainText(val text: String) : ImportSource()
    data class FilePath(val path: String, val type: SourceType) : ImportSource()
}

sealed class VoiceStudioError {
    data object MissingModel : VoiceStudioError()
    data class ImportFailed(val message: String) : VoiceStudioError()
    data class GenerationFailed(val message: String) : VoiceStudioError()
    data class ExportFailed(val message: String) : VoiceStudioError()
}

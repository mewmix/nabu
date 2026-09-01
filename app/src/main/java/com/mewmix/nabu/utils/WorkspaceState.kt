package com.mewmix.nabu.utils

import android.content.Context
import java.io.File

data class GeneratedAudioRef(
    val path: String,
    val sampleRate: Int,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class TtsWorkspaceSelection(
    val engineId: String? = null,
    val voiceId: String? = null,
    val parameters: Map<String, String> = emptyMap(),
)

data class MixerSnapshot(
    val voiceMix: VoiceMixConfig,
    val speed: Float,
    val selection: TtsWorkspaceSelection? = null,
)

data class AudioWorkspaceState(
    val text: String,
    val style: String,
    val speed: Float,
    val lastAudio: GeneratedAudioRef? = null,
    val selection: TtsWorkspaceSelection? = null,
)

data class MixerWorkspaceState(
    val text: String,
    val voiceMix: VoiceMixConfig,
    val speed: Float,
    val settingsExpanded: Boolean = false,
    val lastAudio: GeneratedAudioRef? = null,
    val selection: TtsWorkspaceSelection? = null,
    val snapshotA: MixerSnapshot? = null,
    val snapshotB: MixerSnapshot? = null,
)

data class ChatWorkspaceState(
    val draft: String = "",
    val voiceMix: VoiceMixConfig,
    val speed: Float,
    val conversationSettingsExpanded: Boolean = false,
    val modelSettingsExpanded: Boolean = false,
)

data class SystemPromptProfile(
    val name: String,
    val prompt: String,
)

fun persistWorkspaceAudio(
    context: Context,
    workspace: String,
    audio: FloatArray,
    sampleRate: Int,
): GeneratedAudioRef {
    val directory = File(context.filesDir, "workspace_audio").apply { mkdirs() }
    val file = File(directory, "${workspace}_last.wav")
    saveAudioInternal(audio, file, sampleRate)
    return GeneratedAudioRef(file.absolutePath, sampleRate)
}

fun loadWorkspaceAudio(ref: GeneratedAudioRef?): Pair<FloatArray, Int>? {
    if (ref == null || ref.sampleRate <= 0) return null
    val file = File(ref.path)
    if (!file.isFile) return null
    return runCatching { loadAudioInternal(file) to ref.sampleRate }.getOrNull()
}

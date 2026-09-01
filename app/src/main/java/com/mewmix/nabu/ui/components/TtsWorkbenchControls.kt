package com.mewmix.nabu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.BrutalIconButton
import com.mewmix.nabu.ui.brutalist.BrutalSlider
import com.mewmix.nabu.ui.brutalist.PanelRow
import com.mewmix.nabu.utils.PlayerState
import com.mewmix.nabu.utils.formatBytes
import com.mewmix.nabu.voicelab.VoiceLabEngineInfo
import com.mewmix.nabu.voicelab.VoiceLabParameter
import com.mewmix.nabu.voicelab.VoiceLabSynthesisResult
import com.mewmix.nabu.voicelab.VoiceLabVoice
import java.util.Locale

object TtsWorkbenchTestTags {
    const val AudioScreen = "tts_audio_screen"
    const val MixerScreen = "tts_mixer_screen"
    const val ScriptInput = "tts_script_input"
    const val EngineSelector = "tts_engine_selector"
    const val VoiceSelector = "tts_voice_selector"
    const val ParameterControls = "tts_parameter_controls"
    const val PreviewButton = "tts_preview_button"
    const val RenderFullButton = "tts_render_full_button"
    const val PlaybackControls = "tts_playback_controls"
    const val Diagnostics = "tts_diagnostics"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsEngineSelector(
    engines: List<VoiceLabEngineInfo>,
    selectedEngineId: String?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (String) -> Unit,
) {
    val selected = engines.firstOrNull { it.id == selectedEngineId }
    androidx.compose.material3.ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { onExpandedChange(!expanded) },
    ) {
        TextField(
            value = selected?.let { "${it.name} - ${it.status}" }.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Engine") },
            trailingIcon = {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Select engine")
            },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
                .testTag(TtsWorkbenchTestTags.EngineSelector),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            engines.forEach { engine ->
                DropdownMenuItem(
                    text = { Text("${engine.name} (${engine.status})") },
                    onClick = { onSelected(engine.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsVoiceSelector(
    voices: List<VoiceLabVoice>,
    selectedVoiceId: String?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (String) -> Unit,
    showMetadata: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { onExpandedChange(!expanded) },
        ) {
            TextField(
                value = selectedVoiceId ?: "No voices exposed",
                onValueChange = {},
                readOnly = true,
                label = { Text("Voice") },
                trailingIcon = {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Select voice")
                },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
                    .testTag(TtsWorkbenchTestTags.VoiceSelector),
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
            ) {
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = { Text(voice.displayName) },
                        onClick = { onSelected(voice.id) },
                    )
                }
            }
        }
        if (showMetadata) {
            voices.firstOrNull { it.id == selectedVoiceId }?.let { voice ->
                Text(
                    text = "Engine: ${voice.engineId}   Model: ${voice.modelId}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsParameterControls(
    parameters: List<VoiceLabParameter>,
    values: Map<String, String>,
    onValueChange: (String, String) -> Unit,
    onReset: () -> Unit,
    title: String = "Advanced engine controls",
) {
    Column(
        modifier = Modifier.testTag(TtsWorkbenchTestTags.ParameterControls),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            BrutalButton(onClick = onReset) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reset parameters")
                Spacer(modifier = Modifier.width(6.dp))
                Text("Reset")
            }
        }
        parameters.forEach { parameter ->
            when (parameter) {
                is VoiceLabParameter.FloatValue -> {
                    val value = values[parameter.id]?.toFloatOrNull()
                        ?.coerceIn(parameter.min, parameter.max)
                        ?: parameter.defaultValue
                    PanelRow(name = parameter.label) {
                        BrutalSlider(
                            value = value,
                            onValueChange = {
                                onValueChange(parameter.id, String.format(Locale.US, "%.3f", it))
                            },
                            range = parameter.min..parameter.max,
                            modifier = Modifier.weight(1f),
                        )
                        Text(String.format(Locale.US, "%.2f", value))
                    }
                }
                is VoiceLabParameter.IntValue -> {
                    PanelRow(name = parameter.label) {
                        TextField(
                            value = values[parameter.id] ?: parameter.defaultValue.toString(),
                            onValueChange = { raw ->
                                raw.filter(Char::isDigit).toIntOrNull()?.let {
                                    onValueChange(parameter.id, it.coerceIn(parameter.min, parameter.max).toString())
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(96.dp),
                        )
                        Text("${parameter.min}-${parameter.max}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                is VoiceLabParameter.ChoiceValue -> {
                    val expandedState = remember(parameter.id) { mutableStateOf(false) }
                    androidx.compose.material3.ExposedDropdownMenuBox(
                        expanded = expandedState.value,
                        onExpandedChange = { expandedState.value = !expandedState.value },
                    ) {
                        TextField(
                            value = values[parameter.id] ?: parameter.defaultValue,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(parameter.label) },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Select ${parameter.label}",
                                )
                            },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        DropdownMenu(
                            expanded = expandedState.value,
                            onDismissRequest = { expandedState.value = false },
                        ) {
                            parameter.choices.forEach { choice ->
                                DropdownMenuItem(
                                    text = { Text(choice) },
                                    onClick = {
                                        onValueChange(parameter.id, choice)
                                        expandedState.value = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TtsPlaybackControls(
    audioDurationSeconds: Float?,
    playerState: PlayerState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onRestart: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier.testTag(TtsWorkbenchTestTags.PlaybackControls),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Duration: ${
                audioDurationSeconds?.let { String.format(Locale.US, "%.2fs", it) } ?: "--"
            }",
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BrutalIconButton(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play",
                onClick = onPlay,
                enabled = audioDurationSeconds != null && playerState != PlayerState.PLAYING,
            )
            BrutalIconButton(
                imageVector = Icons.Filled.Pause,
                contentDescription = "Pause",
                onClick = onPause,
                enabled = audioDurationSeconds != null && playerState == PlayerState.PLAYING,
            )
            BrutalIconButton(
                imageVector = Icons.Filled.RestartAlt,
                contentDescription = "Restart",
                onClick = onRestart,
                enabled = audioDurationSeconds != null,
            )
            BrutalIconButton(
                imageVector = Icons.Filled.Save,
                contentDescription = "Export WAV",
                onClick = onExport,
                enabled = audioDurationSeconds != null,
            )
        }
    }
}

@Composable
fun TtsDiagnosticsCard(result: VoiceLabSynthesisResult) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TtsWorkbenchTestTags.Diagnostics)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
        Text("${result.engineName} / ${result.voiceId ?: "default"}")
        Text(
            "${result.generationTimeMs} ms generation · " +
                "${String.format(Locale.US, "%.2f", result.audioDurationSeconds)} s audio · " +
                "RTF ${String.format(Locale.US, "%.3f", result.realTimeFactor)}",
        )
        Text("${formatBytes(result.wavSizeBytes)} WAV · ${result.modelId} · ${result.backend}")
    }
}

fun defaultParameterValues(parameters: List<VoiceLabParameter>): Map<String, String> =
    parameters.associate { parameter ->
        parameter.id to when (parameter) {
            is VoiceLabParameter.FloatValue -> parameter.defaultValue.toString()
            is VoiceLabParameter.IntValue -> parameter.defaultValue.toString()
            is VoiceLabParameter.ChoiceValue -> parameter.defaultValue
        }
    }

package com.mewmix.nabu.voicestudio.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun VoiceStudioRoot(viewModel: VoiceStudioViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ImportContentScreen(state.text, viewModel::onTextChanged)
        ContentPreviewScreen(state.text)
        ChooseVoiceScreen(viewModel.presets.map { it.id to "${it.displayName} — ${it.styleLabel}" }, state.selectedVoicePresetId, viewModel::onChooseVoice)
        GenerateExportScreen(state, onGenerate = viewModel::generate, onPlay = viewModel::play, onPause = viewModel::pause)
    }
}

@Composable fun ModelSetupScreen() { Text("Model setup required", style = MaterialTheme.typography.titleMedium) }
@Composable fun ImportContentScreen(text: String, onTextChanged: (String) -> Unit) { OutlinedTextField(value = text, onValueChange = onTextChanged, label = { Text("Paste text") }) }
@Composable fun ContentPreviewScreen(text: String) { Text("Preview: ${text.take(100)}") }
@Composable fun ChooseVoiceScreen(voices: List<Pair<String, String>>, selected: String?, onSelected: (String) -> Unit) { voices.forEach { (id, label) -> Button(onClick = { onSelected(id) }) { Text(if (id == selected) "✓ $label" else label) } } }
@Composable fun GenerateExportScreen(state: VoiceStudioState, onGenerate: () -> Unit, onPlay: () -> Unit, onPause: () -> Unit) {
    Button(onClick = onGenerate, enabled = state.text.isNotBlank() && state.selectedVoicePresetId != null && !state.isGenerating) { Text("Generate WAV") }
    if (state.isGenerating) LinearProgressIndicator(progress = { state.progress })
    state.outputPath?.let { Text("Saved: $it"); Button(onClick = onPlay) { Text("Play") }; Button(onClick = onPause) { Text("Pause") } }
    state.error?.let { Text("Error: $it") }
}

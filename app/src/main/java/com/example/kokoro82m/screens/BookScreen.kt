package com.example.kokoro82m.screens

import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import com.example.kokoro82m.utils.AudioPlayer
import com.example.kokoro82m.utils.InterpolationMode
import com.example.kokoro82m.utils.PhonemeConverter
import com.example.kokoro82m.utils.StyleLoader
import com.example.kokoro82m.utils.createAudioFromStyleVector
import com.example.kokoro82m.utils.mixStyles
import com.example.kokoro82m.utils.saveAudio
import com.example.kokoro82m.utils.SettingsManager
import com.example.kokoro82m.utils.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    session: OrtSession,
    phonemeConverter: PhonemeConverter,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentLine by remember { mutableIntStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    val audioPlayer = remember { AudioPlayer() }
    val styleLoader = remember { StyleLoader(context) }
    var selectedStyles by remember { mutableStateOf(listOf("af_sarah")) }
    var weights by remember { mutableStateOf(mapOf("af_sarah" to 1f)) }
    var interpolationMode by remember { mutableStateOf(InterpolationMode.LINEAR) }
    var speed by remember { mutableFloatStateOf(SettingsManager.getSpeed(context)) }
    var debugMessage by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val text = readTextFromUri(context, it)
                lines = text.lines()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StyleSelector(
            styleNames = styleLoader.names,
            selectedStyles = selectedStyles,
            onAddStyle = { style ->
                selectedStyles = selectedStyles + style
                weights = weights + (style to 1f)
            },
            onRemoveStyle = { style ->
                selectedStyles = selectedStyles - style
                weights = weights - style
            }
        )

        WeightSliders(
            selectedStyles = selectedStyles,
            weights = weights,
            onWeightChanged = { style, value ->
                weights = weights.toMutableMap().apply { put(style, value) }
            }
        )

        InterpolationModeSelector(
            currentMode = interpolationMode,
            onModeSelected = { interpolationMode = it }
        )

        Text("Speed: $speed")
        Slider(
            value = speed,
            onValueChange = {
                speed = it
                SettingsManager.setSpeed(context, it)
            },
            valueRange = 0.5f..2.0f,
            steps = 5,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { launcher.launch(arrayOf("text/plain")) }) {
                Text("Open File")
            }
            Button(
                onClick = {
                    if (isPlaying) {
                        audioPlayer.pause()
                        isPlaying = false
                    } else {
                        if (currentLine == -1 && lines.isNotEmpty()) {
                            try {
                                debugMessage = null
                                playBook(
                                    session = session,
                                    phonemeConverter = phonemeConverter,
                                    styleLoader = styleLoader,
                                    selectedStyles = selectedStyles,
                                    weights = weights,
                                    mode = interpolationMode,
                                    speed = speed,
                                    lines = lines,
                                    audioPlayer = audioPlayer,
                                    context = context,
                                    scope = scope,
                                    onLineChanged = { currentLine = it },
                                    onFinished = { isPlaying = false }
                                )
                            } catch (e: Exception) {
                                debugMessage = e.localizedMessage
                            }
                        } else {
                            audioPlayer.resume()
                        }
                        isPlaying = true
                    }
                },
                enabled = lines.isNotEmpty()
            ) {
                Text(if (isPlaying) "Pause" else "Play")
            }
            Button(
                onClick = {
                    scope.launch {
                        try {
                            debugMessage = null
                            val mixedVector = mixStyles(
                                styleLoader = styleLoader,
                                styles = selectedStyles,
                                weights = weights,
                                mode = interpolationMode
                            )
                            val audioData = mutableListOf<Float>()
                            for (line in lines) {
                                val phonemes = phonemeConverter.phonemize(line)
                                val (audio, _) = createAudioFromStyleVector(
                                    phonemes = phonemes,
                                    voice = mixedVector,
                                    speed = speed,
                                    session = session
                                )
                                audioData.addAll(audio.toList())
                            }
                            saveAudio(audioData.toFloatArray(), context)
                        } catch (e: Exception) {
                            debugMessage = e.localizedMessage
                        }
                    }
                },
                enabled = lines.isNotEmpty()
            ) {
                Text("Save")
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(lines) { index, line ->
                Text(
                    text = line,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (index == currentLine) Color.Yellow else Color.Transparent)
                        .padding(4.dp)
                )
            }
        }

        debugMessage?.let {
            Text(
                text = it,
                color = Color.Red,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (SettingsManager.isDebug(context)) {
            val logs = DebugLogger.getLogs().joinToString("\n")
            Text(logs, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private fun playBook(
    session: OrtSession,
    phonemeConverter: PhonemeConverter,
    styleLoader: StyleLoader,
    selectedStyles: List<String>,
    weights: Map<String, Float>,
    mode: InterpolationMode,
    speed: Float,
    lines: List<String>,
    audioPlayer: AudioPlayer,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onLineChanged: (Int) -> Unit,
    onFinished: () -> Unit
) {
    scope.launch(Dispatchers.IO) {
        try {
            val mixedVector = mixStyles(
                styleLoader = styleLoader,
                styles = selectedStyles,
                weights = weights,
                mode = mode
            )
            for ((index, line) in lines.withIndex()) {
                onLineChanged(index)
                val phonemes = phonemeConverter.phonemize(line)
                val (audio, _) = createAudioFromStyleVector(
                    phonemes = phonemes,
                    voice = mixedVector,
                    speed = speed,
                    session = session
                )
                audioPlayer.prepare(audio)
                audioPlayer.play()
            }
            onLineChanged(-1)
            withContext(Dispatchers.Main) { onFinished() }
        } catch (e: Exception) {
            DebugLogger.log("playBook failed: ${e.localizedMessage}")
        }
    }
}

private suspend fun readTextFromUri(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun StyleSelector(
    styleNames: List<String>,
    selectedStyles: List<String>,
    onAddStyle: (String) -> Unit,
    onRemoveStyle: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text("Selected Styles:", style = MaterialTheme.typography.labelLarge)

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            selectedStyles.forEach { style ->
                SuggestionChip(
                    onClick = { onRemoveStyle(style) },
                    label = { Text(style) },
                    icon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove"
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            TextField(
                value = "",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                placeholder = { Text("Add style...") },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                styleNames.filter { it !in selectedStyles }.forEach { style ->
                    DropdownMenuItem(
                        text = { Text(style) },
                        onClick = {
                            onAddStyle(style)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun WeightSliders(
    selectedStyles: List<String>,
    weights: Map<String, Float>,
    onWeightChanged: (String, Float) -> Unit
) {
    Column {
        Text("Style Weights:", style = MaterialTheme.typography.labelLarge)

        selectedStyles.forEach { style ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = style, modifier = Modifier.width(120.dp))
                Slider(
                    value = weights[style] ?: 0f,
                    onValueChange = { onWeightChanged(style, it) },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f)
                )
                Text(text = "%.2f".format(weights[style] ?: 0f))
            }
        }
    }
}

@Composable
private fun InterpolationModeSelector(
    currentMode: InterpolationMode,
    onModeSelected: (InterpolationMode) -> Unit
) {
    Column {
        Text("Interpolation Mode:", style = MaterialTheme.typography.labelLarge)

        Row(horizontalArrangement = Arrangement.SpaceEvenly) {
            InterpolationMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onModeSelected(mode) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = currentMode == mode,
                        onClick = { onModeSelected(mode) }
                    )
                    Text(mode.displayName)
                }
            }
        }
    }
}

package com.example.kokoro82m.screens

import ai.onnxruntime.OrtSession
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SuggestionChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.kokoro82m.utils.InterpolationMode
import com.example.kokoro82m.utils.PhonemeConverter
import com.example.kokoro82m.utils.StyleLoader
import com.example.kokoro82m.utils.createAudioFromStyleVector
import com.example.kokoro82m.utils.createKittenAudioFromStyleVector
import com.example.kokoro82m.utils.mixStyles
import com.example.kokoro82m.utils.playAudio
import com.example.kokoro82m.utils.saveAudio
import com.example.kokoro82m.utils.SettingsManager
import com.example.kokoro82m.utils.TtsEngine
import com.example.kokoro82m.utils.DebugLogger
import com.example.kokoro82m.utils.buildStyleFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Simplified mixer screen showing a static style configuration. */
@Composable
fun MixerScreen(
    session: OrtSession,
    phonemeConverter: PhonemeConverter,
    styleLoader: StyleLoader,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var text by remember { mutableStateOf("This is her warm heart, her warmest kokoro, unwavering love and comfort.") }
    var speed by remember { mutableFloatStateOf(SettingsManager.getSpeed(context)) }
    var isProcessing by remember { mutableStateOf(false) }
    var shouldSaveFile by remember { mutableStateOf(false) }

    val initial = remember {
        loadStyleConfig(context) ?: Triple(
            listOf("af_sarah"),
            mapOf("af_sarah" to 1f),
            InterpolationMode.LINEAR
        )
    }

    var selectedStyles by remember { mutableStateOf(initial.first) }
    var weights by remember { mutableStateOf(initial.second) }
    var interpolationMode by remember { mutableStateOf(initial.third) }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            minLines = 3,
            maxLines = 12,
            label = { Text("Text to speak") },
            modifier = Modifier.fillMaxWidth()
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

        StyleSelector(
            styleNames = styleLoader.names,
            selectedStyles = selectedStyles,
            onAddStyle = {
                selectedStyles = selectedStyles + it
                weights = weights + (it to 1f)
                saveStyleConfig(context, selectedStyles, weights, interpolationMode)
            },
            onRemoveStyle = {
                selectedStyles = selectedStyles - it
                weights = weights - it
                saveStyleConfig(context, selectedStyles, weights, interpolationMode)
            }
        )

        WeightSliders(
            selectedStyles = selectedStyles,
            weights = weights,
            onWeightChanged = { style, value ->
                weights = weights.toMutableMap().apply { put(style, value) }
                saveStyleConfig(context, selectedStyles, weights, interpolationMode)
            }
        )

        InterpolationModeSelector(
            currentMode = interpolationMode,
            onModeSelected = {
                interpolationMode = it
                saveStyleConfig(context, selectedStyles, weights, interpolationMode)
            }
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    shouldSaveFile = false
                    isProcessing = true
                    scope.launch {
                        val mixed = mixStyles(styleLoader, selectedStyles, weights, interpolationMode)
                        generateAudio(
                            text,
                            mixed,
                            speed,
                            shouldSaveFile,
                            null,
                            session,
                            phonemeConverter,
                            scope,
                            context
                        ) {
                            isProcessing = false
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isProcessing
            ) { Text(if (isProcessing) "Mixing..." else "Play") }

            Button(
                onClick = {
                    shouldSaveFile = true
                    isProcessing = true
                    scope.launch {
                        val mixed = mixStyles(styleLoader, selectedStyles, weights, interpolationMode)
                        val fileName = buildStyleFileName(selectedStyles, weights, interpolationMode)
                        generateAudio(
                            text,
                            mixed,
                            speed,
                            shouldSaveFile,
                            fileName,
                            session,
                            phonemeConverter,
                            scope,
                            context
                        ) {
                            isProcessing = false
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isProcessing
            ) { Text(if (isProcessing) "Mixing..." else "Play & Save") }
        }

        if (SettingsManager.isDebug(context)) {
            val logs = DebugLogger.getLogs().joinToString("\n")
            Text(logs, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun loadStyleConfig(context: android.content.Context): Triple<List<String>, Map<String, Float>, InterpolationMode>? {
    val prefs = context.getSharedPreferences("mixer_config", android.content.Context.MODE_PRIVATE)
    val saved = prefs.getString("styles", null) ?: return null
    val mode = prefs.getString("mode", InterpolationMode.LINEAR.name) ?: InterpolationMode.LINEAR.name
    val styles = mutableListOf<String>()
    val weights = mutableMapOf<String, Float>()
    saved.split(',').forEach { entry ->
        val parts = entry.split('|')
        if (parts.size == 2) {
            styles.add(parts[0])
            weights[parts[0]] = parts[1].toFloatOrNull() ?: 1f
        }
    }
    return Triple(styles, weights, InterpolationMode.valueOf(mode))
}

private fun generateAudio(
    text: String,
    style: Array<FloatArray>,
    speed: Float,
    shouldSaveFile: Boolean,
    fileName: String?,
    session: OrtSession,
    phonemeConverter: PhonemeConverter,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    onComplete: () -> Unit
) {
    scope.launch(Dispatchers.IO) {
        try {
            val phonemes = phonemeConverter.phonemize(text)
            val engine = SettingsManager.getTtsEngine(context)
            val (audio, _) = if (engine == TtsEngine.KITTEN) {
                createKittenAudioFromStyleVector(
                    phonemes = phonemes,
                    voice = style,
                    speed = speed,
                    session = session
                )
            } else {
                createAudioFromStyleVector(
                    phonemes = phonemes,
                    voice = style,
                    speed = speed,
                    session = session
                )
            }
            if (shouldSaveFile && fileName != null) {
                saveAudio(audio, context, fileName)
            }
            playAudio(audio, scope) {}
        } catch (e: Exception) {
            DebugLogger.log("Mixer error: ${e.message}")
        } finally {
            withContext(Dispatchers.Main) { onComplete() }
        }
    }
}

private fun saveStyleConfig(
    context: android.content.Context,
    styles: List<String>,
    weights: Map<String, Float>,
    mode: InterpolationMode
) {
    val prefs = context.getSharedPreferences("mixer_config", android.content.Context.MODE_PRIVATE)
    val styleString = styles.joinToString(",") { "${it}|${weights[it] ?: 1f}" }
    prefs.edit()
        .putString("styles", styleString)
        .putString("mode", mode.name)
        .apply()
}


@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class,
    ExperimentalMaterial3Api::class
)
@Composable
fun StyleSelector(
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
fun WeightSliders(
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
fun InterpolationModeSelector(
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

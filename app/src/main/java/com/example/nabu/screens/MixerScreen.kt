package com.example.nabu.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.mewmix.nabu.ui.brutalist.Brutal
import com.mewmix.nabu.ui.brutalist.PanelBox
import com.example.nabu.utils.InterpolationMode
import com.example.nabu.utils.PhonemeConverter
import com.example.nabu.utils.KittenPhonemizer
import com.example.nabu.utils.StyleLoader
import com.example.nabu.utils.createAudioFromStyleVector
import com.example.nabu.utils.createKittenAudioFromStyleVector
import com.example.nabu.utils.mixStyles
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.BrutalSlider
import com.mewmix.nabu.ui.brutalist.PanelRow
import com.example.nabu.utils.playAudio
import com.example.nabu.utils.saveAudio
import com.example.nabu.utils.SettingsManager
import com.example.nabu.utils.TtsEngine
import com.example.nabu.utils.DebugLogger
import com.example.nabu.utils.OnnxRuntimeManager
import com.example.nabu.utils.buildStyleFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Simplified mixer screen showing a static style configuration. */
@Composable
fun MixerScreen(
    phonemeConverter: PhonemeConverter,
    styleLoader: StyleLoader,
    requestNotificationPermission: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val engine by rememberUpdatedState(SettingsManager.getTtsEngine(context))

    LaunchedEffect(engine) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            OnnxRuntimeManager.initialize(context.applicationContext)
        }
    }

    var text by remember { mutableStateOf("Made with love and brought to you from outer space.") }
    var speed by remember { mutableFloatStateOf(SettingsManager.getSpeed(context)) }
    var isProcessing by remember { mutableStateOf(false) }
    var shouldSaveFile by remember { mutableStateOf(false) }

    val defaultVoice = styleLoader.names.firstOrNull() ?: "af_sky"
    val initial = remember {
        loadStyleConfig(context) ?: Triple(
            listOf(defaultVoice),
            mapOf(defaultVoice to 1f),
            InterpolationMode.LINEAR
        )
    }

    var selectedStyles by remember { mutableStateOf(initial.first) }
    var weights by remember { mutableStateOf(initial.second) }
    var interpolationMode by remember { mutableStateOf(initial.third) }

    PanelBox(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
    ) {
        Column(
            modifier = Modifier.verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TextField(
            value = text,
            onValueChange = { text = it },
            minLines = 3,
            maxLines = 12,
            label = { Text("TEXT TO SPEAK") },
            modifier = Modifier.fillMaxWidth()
            )

        PanelRow(name = "Speed") {
            BrutalSlider(
                value = speed,
                onValueChange = {
                    speed = it
                    SettingsManager.setSpeed(context, it)
                },
                range = 0.5f..2.0f,
                modifier = Modifier.weight(1f)
            )
            Text("%.2f".format(speed))
        }

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
            BrutalButton(
                onClick = {
                    requestNotificationPermission()
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
            ) { Text(if (isProcessing) "MIXING..." else "PLAY") }

            BrutalButton(
                onClick = {
                    requestNotificationPermission()
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
            ) { Text(if (isProcessing) "MIXING..." else "PLAY & SAVE") }
        }

        // Debug logs moved to dedicated screen
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
    phonemeConverter: PhonemeConverter,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    onComplete: () -> Unit
) {
    val session = OnnxRuntimeManager.getSession()
    scope.launch(Dispatchers.IO) {
        try {
            val engine = SettingsManager.getTtsEngine(context)
            val (audio, sampleRate) = if (engine == TtsEngine.KITTEN) {
                val (_, tokens) = KittenPhonemizer.phonemize(text)
                createKittenAudioFromStyleVector(
                    tokens = tokens,
                    voice = style,
                    speed = speed,
                    session = session
                )
            } else {
                val phonemes = phonemeConverter.phonemize(text)
                createAudioFromStyleVector(
                    phonemes = phonemes,
                    voice = style,
                    speed = speed,
                    session = session
                )
            }
            if (shouldSaveFile && fileName != null) {
                saveAudio(audio, context, fileName, sampleRate)
            }
            playAudio(audio, sampleRate, context, scope) {}
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun StyleSelector(
    styleNames: List<String>,
    selectedStyles: List<String>,
    onAddStyle: (String) -> Unit,
    onRemoveStyle: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(
            "SELECTED STYLES:",
            style = MaterialTheme.typography.labelLarge,
            color = Brutal.textBright
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            selectedStyles.forEach { style ->
                SuggestionChip(
                    onClick = { onRemoveStyle(style) },
                    label = { Text(style.uppercase(), color = Brutal.textBright) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = Brutal.panelBg,
                        iconContentColor = Brutal.textBright,
                        labelColor = Brutal.textBright
                    )
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
                trailingIcon = {
                    Text(if (expanded) "▲" else "▼", color = Brutal.textBright)
                },
                placeholder = { Text("ADD STYLE...", color = Brutal.textDim) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Brutal.panelBg,
                    unfocusedContainerColor = Brutal.panelBg,
                    focusedIndicatorColor = Brutal.hairline,
                    unfocusedIndicatorColor = Brutal.hairline,
                    cursorColor = Brutal.amber,
                    focusedTextColor = Brutal.textBright,
                    unfocusedTextColor = Brutal.textBright,
                    focusedPlaceholderColor = Brutal.textDim,
                    unfocusedPlaceholderColor = Brutal.textDim
                ),
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
                    .border(1.dp, Brutal.hairline, RoundedCornerShape(4.dp))
            )

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .background(Brutal.panelBg)
                    .border(1.dp, Brutal.hairline, RoundedCornerShape(4.dp))
            ) {
                styleNames.filter { it !in selectedStyles }.forEach { style ->
                    DropdownMenuItem(
                        text = { Text(style.uppercase(), color = Brutal.textBright) },
                        onClick = {
                            onAddStyle(style)
                            expanded = false
                        },
                        colors = MenuDefaults.itemColors(textColor = Brutal.textBright)
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
        Text("STYLE WEIGHTS:", style = MaterialTheme.typography.labelLarge, color = Brutal.textBright)

        selectedStyles.forEach { style ->
            PanelRow(name = style) {
                BrutalSlider(
                    value = weights[style] ?: 0f,
                    onValueChange = { onWeightChanged(style, it) },
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
        Text("INTERPOLATION MODE:", style = MaterialTheme.typography.labelLarge, color = Brutal.textBright)

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
                    Text(mode.displayName.uppercase())
                }
            }
        }
    }
}
package com.mewmix.nabu.screens

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.mewmix.nabu.ui.brutalist.Brutal
import com.mewmix.nabu.ui.brutalist.PanelBox
import com.mewmix.nabu.utils.InterpolationMode
import com.mewmix.nabu.utils.PhonemeConverter
import com.mewmix.nabu.utils.StyleLoader
import com.mewmix.nabu.utils.createAudioFromStyleVector
import com.mewmix.nabu.utils.mixStyles
import com.mewmix.nabu.utils.SettingsManager
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.BrutalButtonText
import com.mewmix.nabu.ui.brutalist.BrutalSlider
import com.mewmix.nabu.ui.brutalist.PanelRow
import com.mewmix.nabu.utils.playAudio
import com.mewmix.nabu.utils.saveAudio
import com.mewmix.nabu.utils.DebugLogger
import com.mewmix.nabu.utils.OnnxRuntimeManager
import com.mewmix.nabu.utils.buildStyleFileName
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.ModelType
import com.mewmix.nabu.soprano.SopranoEngine
import com.mewmix.nabu.soprano.SopranoSamplingConfig
import com.mewmix.nabu.supertonic.DebugSupertonicEngine
import com.mewmix.nabu.ui.components.RuntimeStatusLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Simplified mixer screen showing a static style configuration. */
@Composable
fun MixerScreen(
    phonemeConverter: PhonemeConverter,
    styleLoader: StyleLoader,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val modelManager = remember { ModelManager(context) }
    val scrollState = rememberScrollState()
    var preferredEngine by remember { mutableStateOf(SettingsManager.getTtsEngine(context)) }
    var isKokoroLoaded by remember { mutableStateOf(false) }
    var isSupertonicLoaded by remember { mutableStateOf(false) }
    var isSopranoLoaded by remember { mutableStateOf(false) }
    var hasSupertonicModels by remember { mutableStateOf(false) }
    var supertonicTotalStep by remember { mutableFloatStateOf(SettingsManager.getSupertonicTotalStep(context).toFloat()) }
    var sopranoTopK by remember { mutableFloatStateOf(SettingsManager.getSopranoTopK(context).toFloat()) }
    var sopranoTopP by remember { mutableFloatStateOf(SettingsManager.getSopranoTopP(context)) }
    var sopranoTemperature by remember { mutableFloatStateOf(SettingsManager.getSopranoTemperature(context)) }
    var sopranoRepPenalty by remember { mutableFloatStateOf(SettingsManager.getSopranoRepetitionPenalty(context)) }

    LaunchedEffect(Unit) {
        preferredEngine = SettingsManager.getTtsEngine(context)
        if (preferredEngine == "supertonic") {
            val selectedId = SettingsManager.getSupertonicModelId(context)
            val downloadedModels = modelManager.models.filter { model ->
                model.type == ModelType.TTS && model.isDownloaded
            }
            val selectedModel = selectedId?.let { id -> downloadedModels.firstOrNull { it.id == id } }
            hasSupertonicModels = if (selectedId != null) selectedModel != null else downloadedModels.isNotEmpty()
        }

        val engine = withContext(Dispatchers.IO) {
            com.mewmix.nabu.tts.TTSManager.getEngine(context, modelManager)
        }
        val rawEngine = if (engine is com.mewmix.nabu.tts.BenchmarkingTTSEngine) engine.delegate else engine
        isKokoroLoaded = rawEngine is com.mewmix.nabu.kokoro.KokoroEngine
        isSupertonicLoaded = rawEngine is DebugSupertonicEngine
        isSopranoLoaded = rawEngine is SopranoEngine

        if (preferredEngine == "kokoro" && !isKokoroLoaded) {
            val result = withContext(Dispatchers.IO) {
                OnnxRuntimeManager.initialize(
                    context.applicationContext,
                    allowDownload = SettingsManager.isKokoroAutoDownloadEnabled(context)
                )
            }
            result.onFailure { DebugLogger.log("Mixer failed to init runtime: ${it.message}") }
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
            RuntimeStatusLine()
            if (preferredEngine == "supertonic" && !hasSupertonicModels) {
                Text(
                    text = if (SettingsManager.getSupertonicModelId(context) != null) {
                        "Selected Supertonic model is not downloaded yet. Open Models to download it."
                    } else {
                        "No Supertonic voice models found. Open Models to download one."
                    },
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (preferredEngine == "soprano" && !isSopranoLoaded) {
                Text(
                    text = "Soprano is selected but not loaded. Download the model, then reopen Mixer.",
                    color = MaterialTheme.colorScheme.error
                )
            }

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

        if (isKokoroLoaded) {
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
        }

        if (isKokoroLoaded) {
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
        }

        val supertonicStyles = if (isSupertonicLoaded) styleLoader.names else emptyList()
        LaunchedEffect(isSupertonicLoaded, supertonicStyles) {
            if (isSupertonicLoaded && supertonicStyles.isNotEmpty() && selectedStyles.firstOrNull() !in supertonicStyles) {
                selectedStyles = listOf(supertonicStyles.first())
            }
        }

        if (isSupertonicLoaded) {
            SingleStyleSelector(
                styleNames = supertonicStyles,
                selectedStyle = selectedStyles.firstOrNull(),
                onSelected = { selectedStyles = listOf(it) }
            )

            PanelRow(name = "Supertonic Steps") {
                BrutalSlider(
                    value = supertonicTotalStep,
                    onValueChange = { value ->
                        val rounded = value.roundToInt().coerceIn(1, 12)
                        supertonicTotalStep = rounded.toFloat()
                        SettingsManager.setSupertonicTotalStep(context, rounded)
                    },
                    range = 1f..12f,
                    modifier = Modifier.weight(1f)
                )
                Text(supertonicTotalStep.roundToInt().toString())
            }
        }

        if (isSopranoLoaded) {
            Text("SOPRANO SAMPLING", style = MaterialTheme.typography.labelLarge, color = Brutal.textBright)
            PanelRow(name = "Top P") {
                BrutalSlider(
                    value = sopranoTopP,
                    onValueChange = { value ->
                        sopranoTopP = value
                        SettingsManager.setSopranoTopP(context, value)
                    },
                    range = 0f..1f,
                    modifier = Modifier.weight(1f)
                )
                Text("%.2f".format(sopranoTopP))
            }
            PanelRow(name = "Top K") {
                BrutalSlider(
                    value = sopranoTopK,
                    onValueChange = { value ->
                        val rounded = value.roundToInt().coerceIn(1, 256)
                        sopranoTopK = rounded.toFloat()
                        SettingsManager.setSopranoTopK(context, rounded)
                    },
                    range = 1f..256f,
                    modifier = Modifier.weight(1f)
                )
                Text(sopranoTopK.roundToInt().toString())
            }
            PanelRow(name = "Temperature") {
                BrutalSlider(
                    value = sopranoTemperature,
                    onValueChange = { value ->
                        sopranoTemperature = value
                        SettingsManager.setSopranoTemperature(context, value)
                    },
                    range = 0f..2f,
                    modifier = Modifier.weight(1f)
                )
                Text("%.2f".format(sopranoTemperature))
            }
            PanelRow(name = "Rep Penalty") {
                BrutalSlider(
                    value = sopranoRepPenalty,
                    onValueChange = { value ->
                        sopranoRepPenalty = value
                        SettingsManager.setSopranoRepetitionPenalty(context, value)
                    },
                    range = 0.5f..2f,
                    modifier = Modifier.weight(1f)
                )
                Text("%.2f".format(sopranoRepPenalty))
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            BrutalButton(
                onClick = {
                    shouldSaveFile = false
                    isProcessing = true
                    scope.launch {
                        // Decide style mixing based on actual engine resolved at runtime
                        val engine = com.mewmix.nabu.tts.TTSManager.getEngine(context, modelManager)
                        val rawEngine = if (engine is com.mewmix.nabu.tts.BenchmarkingTTSEngine) engine.delegate else engine
                        val mixed = if (rawEngine is com.mewmix.nabu.kokoro.KokoroEngine) {
                            mixStyles(styleLoader, selectedStyles, weights, interpolationMode)
                        } else {
                            emptyArray()
                        }
                        generateAudio(
                            text,
                            mixed,
                            speed,
                            shouldSaveFile,
                            null,
                            phonemeConverter,
                            scope,
                            context,
                            selectedStyles.firstOrNull()
                        ) {
                            isProcessing = false
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isProcessing &&
                    (preferredEngine != "supertonic" || hasSupertonicModels) &&
                    (preferredEngine != "soprano" || isSopranoLoaded)
            ) { BrutalButtonText(if (isProcessing) "MIXING..." else "PLAY") }

            BrutalButton(
                onClick = {
                    shouldSaveFile = true
                    isProcessing = true
                    scope.launch {
                        // Decide style mixing based on actual engine resolved at runtime
                        val engine = com.mewmix.nabu.tts.TTSManager.getEngine(context, modelManager)
                        val rawEngine = if (engine is com.mewmix.nabu.tts.BenchmarkingTTSEngine) engine.delegate else engine
                        val mixed = if (rawEngine is com.mewmix.nabu.kokoro.KokoroEngine) {
                            mixStyles(styleLoader, selectedStyles, weights, interpolationMode)
                        } else {
                            emptyArray()
                        }
                        val fileName = if (isSupertonicLoaded) {
                             val modelId = SettingsManager.getSupertonicModelId(context)
                             val modelName = modelManager.models.find { it.id == modelId }?.name ?: "supertonic"
                             "${modelName}_${System.currentTimeMillis()}"
                        } else {
                             buildStyleFileName(selectedStyles, weights, interpolationMode)
                        }
                        generateAudio(
                            text,
                            mixed,
                            speed,
                            shouldSaveFile,
                            fileName,
                            phonemeConverter,
                            scope,
                            context,
                            selectedStyles.firstOrNull()
                        ) {
                            isProcessing = false
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isProcessing &&
                    (preferredEngine != "supertonic" || hasSupertonicModels) &&
                    (preferredEngine != "soprano" || isSopranoLoaded)
            ) { BrutalButtonText(if (isProcessing) "MIXING..." else "PLAY & SAVE") }
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
    style: Array<FloatArray>, // This is still used for Kokoro mixing
    speed: Float,
    shouldSaveFile: Boolean,
    fileName: String?,
    phonemeConverter: PhonemeConverter,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    // Add selectedStyleName for Supertonic fallback/usage since we can't mix
    selectedStyleName: String? = null,
    onComplete: () -> Unit
) {
    val modelManager = com.mewmix.nabu.data.ModelManager(context)
    scope.launch(Dispatchers.IO) {
        try {
            val engine = com.mewmix.nabu.tts.TTSManager.getEngine(context, modelManager)
            if (engine == null) {
                 withContext(Dispatchers.Main) {
                    // Toast or log? MixerScreen doesn't have easy toast access context?
                    // It has context.
                 }
                 return@launch
            }

            val rawEngine = if (engine is com.mewmix.nabu.tts.BenchmarkingTTSEngine) engine.delegate else engine

            val (audio, sampleRate) = if (rawEngine is com.mewmix.nabu.kokoro.KokoroEngine) {
                val phonemes = phonemeConverter.phonemize(text)
                createAudioFromStyleVector(
                    phonemes = phonemes,
                    voice = style,
                    speed = speed,
                    engine = rawEngine
                )
            } else if (rawEngine is DebugSupertonicEngine) {
                if (selectedStyleName != null) {
                    rawEngine.setStyle(selectedStyleName)
                }
                val totalStep = SettingsManager.getSupertonicTotalStep(context)
                val result = rawEngine.synthesize(text = text, speed = speed, totalStep = totalStep)
                result.wav to result.sampleRate
            } else if (rawEngine is SopranoEngine) {
                rawEngine.updateSamplingConfig(
                    SopranoSamplingConfig(
                        temperature = SettingsManager.getSopranoTemperature(context),
                        topK = SettingsManager.getSopranoTopK(context),
                        topP = SettingsManager.getSopranoTopP(context),
                        repetitionPenalty = SettingsManager.getSopranoRepetitionPenalty(context)
                    )
                )
                val result = engine.synthesize(text, speed)
                result.wav to result.sampleRate
            } else {
                 val result = engine.synthesize(text, speed)
                 result.wav to result.sampleRate
            }

            if (shouldSaveFile && fileName != null) {
                saveAudio(audio, context, fileName, sampleRate)
            }
            playAudio(audio, sampleRate, scope) {}
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleStyleSelector(
    styleNames: List<String>,
    selectedStyle: String?,
    onSelected: (String) -> Unit
) {
    if (styleNames.isEmpty()) {
        Text(
            text = "No Supertonic styles found in the selected model.",
            color = MaterialTheme.colorScheme.error
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        TextField(
            value = selectedStyle ?: styleNames.first(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Supertonic Style") },
            trailingIcon = {
                Text(if (expanded) "▲" else "▼", color = Brutal.textBright)
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Brutal.panelBg,
                unfocusedContainerColor = Brutal.panelBg,
                focusedIndicatorColor = Brutal.hairline,
                unfocusedIndicatorColor = Brutal.hairline,
                cursorColor = Brutal.amber,
                focusedTextColor = Brutal.textBright,
                unfocusedTextColor = Brutal.textBright
            ),
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
            styleNames.forEach { style ->
                DropdownMenuItem(
                    text = { Text(style.uppercase(), color = Brutal.textBright) },
                    onClick = {
                        onSelected(style)
                        expanded = false
                    },
                    colors = MenuDefaults.itemColors(textColor = Brutal.textBright)
                )
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

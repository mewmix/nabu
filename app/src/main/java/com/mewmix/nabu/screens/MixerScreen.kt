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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
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
import com.mewmix.nabu.utils.VoiceMixConfig
import com.mewmix.nabu.utils.VoiceMixFavorite
import com.mewmix.nabu.utils.filterToAvailableStyles
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
    val initial = remember(defaultVoice) {
        SettingsManager.getVoiceMixConfig(context, defaultVoice)
    }

    var selectedStyles by remember { mutableStateOf(initial.styles) }
    var weights by remember { mutableStateOf(initial.weights) }
    var interpolationMode by remember { mutableStateOf(initial.interpolationMode) }
    var favorites by remember {
        mutableStateOf(SettingsManager.getVoiceMixFavorites(context))
    }

    // Debounced persistence for Speed
    LaunchedEffect(speed) {
        if (speed != SettingsManager.getSpeed(context)) {
            kotlinx.coroutines.delay(300)
            SettingsManager.setSpeed(context, speed)
        }
    }

    // Debounced persistence for Voice Mix Config
    LaunchedEffect(selectedStyles, weights, interpolationMode) {
        // We only persist if it's different from the saved one to avoid initial redundant write
        // but for simplicity and since we have 300ms debounce, it's fine.
        kotlinx.coroutines.delay(500)
        SettingsManager.setVoiceMixConfig(
            context,
            VoiceMixConfig(selectedStyles, weights, interpolationMode)
        )
    }

    fun persistVoiceMixConfig(
        styles: List<String> = selectedStyles,
        styleWeights: Map<String, Float> = weights,
        mode: InterpolationMode = interpolationMode,
    ) {
        SettingsManager.setVoiceMixConfig(
            context,
            VoiceMixConfig(styles, styleWeights, mode)
        )
    }

    fun persistFavorites(updated: List<VoiceMixFavorite>) {
        favorites = updated
        SettingsManager.setVoiceMixFavorites(context, updated)
    }

    LaunchedEffect(styleLoader.names) {
        if (styleLoader.names.isNotEmpty()) {
            val fallback = styleLoader.names.first()
            val sanitized = VoiceMixConfig(
                styles = selectedStyles,
                weights = weights,
                interpolationMode = interpolationMode
            ).filterToAvailableStyles(styleLoader.names, fallback)
            if (sanitized.styles != selectedStyles ||
                sanitized.weights != weights ||
                sanitized.interpolationMode != interpolationMode
            ) {
                selectedStyles = sanitized.styles
                weights = sanitized.weights
                interpolationMode = sanitized.interpolationMode
                persistVoiceMixConfig(sanitized.styles, sanitized.weights, sanitized.interpolationMode)
            }
            val sanitizedFavorites = favorites.mapNotNull { favorite ->
                favorite.copy(
                    styles = favorite.styles.filter { it in styleLoader.names }.ifEmpty { listOf(fallback) },
                    weights = favorite.styles
                        .filter { it in styleLoader.names }
                        .ifEmpty { listOf(fallback) }
                        .associateWith { style -> (favorite.weights[style] ?: 1f).coerceIn(0f, 1f) }
                )
            }
            if (sanitizedFavorites != favorites) {
                persistFavorites(sanitizedFavorites)
            }
        }
    }

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
                onValueChange = { speed = it },
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
                    val nextStyles = selectedStyles + it
                    val nextWeights = weights + (it to 1f)
                    selectedStyles = nextStyles
                    weights = nextWeights
                    persistVoiceMixConfig(nextStyles, nextWeights)
                },
                onRemoveStyle = {
                    var nextStyles = selectedStyles - it
                    var nextWeights = weights - it
                    if (nextStyles.isEmpty()) {
                        nextStyles = listOf(defaultVoice)
                        nextWeights = mapOf(defaultVoice to 1f)
                    }
                    selectedStyles = nextStyles
                    weights = nextWeights
                    persistVoiceMixConfig(nextStyles, nextWeights)
                }
            )
        }

        if (isKokoroLoaded) {
            VoiceFavoritesSection(
                favorites = favorites,
                onSaveFavorite = { name ->
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) {
                        val updated = favorites.filterNot { it.name.equals(trimmed, ignoreCase = true) } +
                            VoiceMixFavorite(
                                name = trimmed,
                                styles = selectedStyles,
                                weights = weights,
                                interpolationMode = interpolationMode
                            )
                        persistFavorites(updated)
                    }
                },
                onApplyFavorite = { favorite ->
                    val config = VoiceMixConfig(
                        styles = favorite.styles,
                        weights = favorite.weights,
                        interpolationMode = favorite.interpolationMode
                    ).filterToAvailableStyles(styleLoader.names, defaultVoice)
                    selectedStyles = config.styles
                    weights = config.weights
                    interpolationMode = config.interpolationMode
                    persistVoiceMixConfig(config.styles, config.weights, config.interpolationMode)
                },
                onDeleteFavorite = { name ->
                    persistFavorites(favorites.filterNot { it.name.equals(name, ignoreCase = true) })
                }
            )

            WeightSliders(
                selectedStyles = selectedStyles,
                weights = weights,
                onWeightChanged = { style, value ->
                    val nextWeights = weights.toMutableMap().apply { put(style, value.coerceIn(0f, 1f)) }
                    weights = nextWeights
                    persistVoiceMixConfig(styleWeights = nextWeights)
                }
            )

            InterpolationModeSelector(
                currentMode = interpolationMode,
                onModeSelected = {
                    interpolationMode = it
                    persistVoiceMixConfig(mode = it)
                }
            )
        }

        val supertonicStyles = if (isSupertonicLoaded) styleLoader.names else emptyList()
        LaunchedEffect(isSupertonicLoaded, supertonicStyles) {
            if (isSupertonicLoaded && supertonicStyles.isNotEmpty() && selectedStyles.firstOrNull() !in supertonicStyles) {
                val nextStyles = listOf(supertonicStyles.first())
                val nextWeights = mapOf(nextStyles.first() to (weights[nextStyles.first()] ?: 1f))
                selectedStyles = nextStyles
                weights = nextWeights
                persistVoiceMixConfig(nextStyles, nextWeights)
            }
        }

        if (isSupertonicLoaded) {
            SingleStyleSelector(
                styleNames = supertonicStyles,
                selectedStyle = selectedStyles.firstOrNull(),
                onSelected = {
                    val nextStyles = listOf(it)
                    val nextWeights = mapOf(it to (weights[it] ?: 1f))
                    selectedStyles = nextStyles
                    weights = nextWeights
                    persistVoiceMixConfig(nextStyles, nextWeights)
                }
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
                val currentWeight = (weights[style] ?: 0f).coerceIn(0f, 1f)
                key(style, currentWeight) {
                    var input by remember { mutableStateOf(formatWeightInput(currentWeight)) }
                    BrutalSlider(
                        value = currentWeight,
                        onValueChange = {
                            input = formatWeightInput(it)
                            onWeightChanged(style, it)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextField(
                        value = input,
                        onValueChange = { candidate ->
                            if (isValidWeightInput(candidate)) {
                                input = candidate
                                candidate.toFloatOrNull()?.let { onWeightChanged(style, it.coerceIn(0f, 1f)) }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.width(88.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Brutal.panelBg,
                            unfocusedContainerColor = Brutal.panelBg,
                            focusedIndicatorColor = Brutal.hairline,
                            unfocusedIndicatorColor = Brutal.hairline,
                            focusedTextColor = Brutal.textBright,
                            unfocusedTextColor = Brutal.textBright
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun VoiceFavoritesSection(
    favorites: List<VoiceMixFavorite>,
    onSaveFavorite: (String) -> Unit,
    onApplyFavorite: (VoiceMixFavorite) -> Unit,
    onDeleteFavorite: (String) -> Unit,
) {
    var favoriteName by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("FAVORITES:", style = MaterialTheme.typography.labelLarge, color = Brutal.textBright)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = favoriteName,
                onValueChange = { favoriteName = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Name this mix", color = Brutal.textDim) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Brutal.panelBg,
                    unfocusedContainerColor = Brutal.panelBg,
                    focusedIndicatorColor = Brutal.hairline,
                    unfocusedIndicatorColor = Brutal.hairline,
                    focusedTextColor = Brutal.textBright,
                    unfocusedTextColor = Brutal.textBright
                )
            )
            BrutalButton(
                onClick = {
                    val trimmed = favoriteName.trim()
                    if (trimmed.isNotEmpty()) {
                        onSaveFavorite(trimmed)
                        favoriteName = ""
                    }
                },
                enabled = favoriteName.isNotBlank()
            ) {
                Text("Save", color = Brutal.textBright)
            }
        }

        if (favorites.isEmpty()) {
            Text(
                text = "No saved mixes yet.",
                style = MaterialTheme.typography.bodySmall,
                color = Brutal.textDim
            )
        } else {
            favorites.forEach { favorite ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Brutal.hairline, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(favorite.name, color = Brutal.textBright, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${favorite.styles.joinToString(" + ")} • ${favorite.interpolationMode.displayName}",
                        color = Brutal.textDim,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BrutalButton(onClick = { onApplyFavorite(favorite) }) {
                            Text("Load", color = Brutal.textBright)
                        }
                        BrutalButton(onClick = { onDeleteFavorite(favorite.name) }) {
                            Text("Delete", color = Brutal.red)
                        }
                    }
                }
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

private fun formatWeightInput(value: Float): String =
    "%.2f".format(value.coerceIn(0f, 1f))

private fun isValidWeightInput(input: String): Boolean {
    if (input.isEmpty() || input == ".") return true
    return WEIGHT_INPUT_REGEX.matches(input)
}

private val WEIGHT_INPUT_REGEX = Regex("^(0?(\\.\\d{0,2})?|1(\\.0{0,2})?)$")

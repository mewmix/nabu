package com.mewmix.nabu.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.BrutalSection
import com.mewmix.nabu.ui.brutalist.PanelBox
import com.mewmix.nabu.ui.components.RuntimeStatusLine
import com.mewmix.nabu.ui.components.TtsDiagnosticsCard
import com.mewmix.nabu.ui.components.TtsEngineSelector
import com.mewmix.nabu.ui.components.TtsParameterControls
import com.mewmix.nabu.ui.components.TtsPlaybackControls
import com.mewmix.nabu.ui.components.TtsVoiceSelector
import com.mewmix.nabu.ui.components.TtsWorkbenchTestTags
import com.mewmix.nabu.ui.components.defaultParameterValues
import com.mewmix.nabu.utils.InterpolationMode
import com.mewmix.nabu.utils.KokoroAudioPlayer
import com.mewmix.nabu.utils.MixerSnapshot
import com.mewmix.nabu.utils.MixerWorkspaceState
import com.mewmix.nabu.utils.PlayerState
import com.mewmix.nabu.utils.SettingsManager
import com.mewmix.nabu.utils.StyleLoader
import com.mewmix.nabu.utils.TtsWorkspaceSelection
import com.mewmix.nabu.utils.VoiceMixConfig
import com.mewmix.nabu.utils.VoiceMixFavorite
import com.mewmix.nabu.utils.filterToAvailableStyles
import com.mewmix.nabu.utils.loadWorkspaceAudio
import com.mewmix.nabu.utils.mixStyles
import com.mewmix.nabu.utils.persistWorkspaceAudio
import com.mewmix.nabu.utils.saveAudioWithDisplayName
import com.mewmix.nabu.viewmodel.TtsWorkbenchViewModel
import com.mewmix.nabu.voicelab.VoiceLabRepository
import com.mewmix.nabu.voicelab.VoiceLabRequest
import com.mewmix.nabu.voicelab.VoiceLabSynthesisResult
import com.mewmix.nabu.voicelab.VoiceLabText
import com.mewmix.nabu.voicelab.VoiceLabVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MixerScreen(
    styleLoader: StyleLoader,
    workbench: TtsWorkbenchViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalog by workbench.catalog.collectAsState()
    val scrollState = rememberScrollState()
    val defaultVoice = styleLoader.names.firstOrNull() ?: "af_sky"
    val initialWorkspace = remember(defaultVoice) {
        SettingsManager.getMixerWorkspace(context, defaultVoice)
    }
    val initialMix = remember(initialWorkspace, styleLoader.names) {
        initialWorkspace.voiceMix.filterToAvailableStyles(styleLoader.names, defaultVoice)
    }
    var text by remember { mutableStateOf(initialWorkspace.text) }
    var selectedEngineId by remember { mutableStateOf(initialWorkspace.selection?.engineId) }
    var voices by remember { mutableStateOf<List<VoiceLabVoice>>(emptyList()) }
    var selectedVoiceId by remember { mutableStateOf(initialWorkspace.selection?.voiceId) }
    var parameterValues by remember { mutableStateOf(initialWorkspace.selection?.parameters.orEmpty()) }
    var selectedStyles by remember { mutableStateOf(initialMix.styles) }
    var weights by remember { mutableStateOf(initialMix.weights) }
    var interpolationMode by remember { mutableStateOf(initialMix.interpolationMode) }
    var favorites by remember { mutableStateOf(SettingsManager.getVoiceMixFavorites(context)) }
    var settingsExpanded by remember { mutableStateOf(initialWorkspace.settingsExpanded) }
    var engineExpanded by remember { mutableStateOf(false) }
    var voiceExpanded by remember { mutableStateOf(false) }
    var isRendering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastResult by remember { mutableStateOf<VoiceLabSynthesisResult?>(null) }
    var lastAudioRef by remember { mutableStateOf(initialWorkspace.lastAudio) }
    var playbackAudio by remember { mutableStateOf(loadWorkspaceAudio(initialWorkspace.lastAudio)) }
    var snapshotA by remember { mutableStateOf(initialWorkspace.snapshotA) }
    var snapshotB by remember { mutableStateOf(initialWorkspace.snapshotB) }
    var comparisonExpanded by remember { mutableStateOf(true) }
    var playerState by remember { mutableStateOf(PlayerState.IDLE) }
    val player = remember { KokoroAudioPlayer(scope) { playerState = it } }
    val selectedEngine = catalog.engines.firstOrNull { it.id == selectedEngineId }
    val isKokoro = selectedEngineId == VoiceLabRepository.KOKORO_ID

    fun currentSelection(): TtsWorkspaceSelection = TtsWorkspaceSelection(
        engineId = selectedEngineId,
        voiceId = if (isKokoro) selectedStyles.firstOrNull() else selectedVoiceId,
        parameters = parameterValues,
    )

    fun persistWorkspace() {
        SettingsManager.setMixerWorkspace(
            context,
            MixerWorkspaceState(
                text = text,
                voiceMix = VoiceMixConfig(selectedStyles, weights, interpolationMode),
                speed = parameterValues["speed"]?.toFloatOrNull() ?: initialWorkspace.speed,
                settingsExpanded = settingsExpanded,
                lastAudio = lastAudioRef,
                selection = currentSelection(),
                snapshotA = snapshotA,
                snapshotB = snapshotB,
            ),
        )
    }

    fun captureSnapshot(): MixerSnapshot = MixerSnapshot(
        voiceMix = VoiceMixConfig(selectedStyles, weights, interpolationMode),
        speed = parameterValues["speed"]?.toFloatOrNull() ?: initialWorkspace.speed,
        selection = currentSelection(),
    )

    fun applySnapshot(snapshot: MixerSnapshot) {
        val mix = snapshot.voiceMix.filterToAvailableStyles(styleLoader.names, defaultVoice)
        selectedStyles = mix.styles
        weights = mix.weights
        interpolationMode = mix.interpolationMode
        snapshot.selection?.let { selection ->
            selectedEngineId = selection.engineId
            selectedVoiceId = selection.voiceId
            parameterValues = selection.parameters + ("speed" to snapshot.speed.toString())
        }
        persistWorkspace()
    }

    fun persistFavorites(updated: List<VoiceMixFavorite>) {
        favorites = updated
        SettingsManager.setVoiceMixFavorites(context, updated)
    }

    DisposableEffect(Unit) {
        onDispose { player.stop() }
    }

    LaunchedEffect(catalog.engines) {
        if (catalog.engines.isEmpty()) return@LaunchedEffect
        if (catalog.engines.none { it.id == selectedEngineId }) {
            val preferredId = when (SettingsManager.getTtsEngine(context)) {
                "supertonic" -> SettingsManager.getSupertonicModelId(context)
                    ?: catalog.engines.firstOrNull { it.id.startsWith("supertonic") }?.id
                "soprano" -> VoiceLabRepository.SOPRANO_ID
                else -> VoiceLabRepository.KOKORO_ID
            }
            selectedEngineId = preferredId?.takeIf { id -> catalog.engines.any { it.id == id } }
                ?: catalog.engines.firstOrNull { it.isAvailable }?.id
                ?: catalog.engines.first().id
        }
    }

    LaunchedEffect(selectedEngineId, selectedEngine?.parameters) {
        val engine = selectedEngine ?: return@LaunchedEffect
        voices = workbench.voices(engine.id)
        selectedVoiceId = selectedVoiceId?.takeIf { saved -> voices.any { it.id == saved } }
            ?: voices.firstOrNull()?.id
        val defaults = defaultParameterValues(engine.parameters).toMutableMap()
        if (defaults.containsKey("speed") && "speed" !in parameterValues) {
            defaults["speed"] = initialWorkspace.speed.toString()
        }
        val supportedParameters = engine.parameters.mapTo(mutableSetOf()) { it.id }
        defaults.putAll(parameterValues.filterKeys { it in supportedParameters })
        parameterValues = defaults
        persistWorkspace()
    }

    LaunchedEffect(styleLoader.names) {
        if (styleLoader.names.isEmpty()) return@LaunchedEffect
        val sanitized = VoiceMixConfig(selectedStyles, weights, interpolationMode)
            .filterToAvailableStyles(styleLoader.names, styleLoader.names.first())
        selectedStyles = sanitized.styles
        weights = sanitized.weights
        interpolationMode = sanitized.interpolationMode
        persistWorkspace()
    }

    fun render(script: String) {
        val engine = selectedEngine ?: return
        val normalized = VoiceLabText.renderableTextOrNull(script)
        if (normalized == null) {
            error = "Enter text before rendering."
            return
        }
        if (!engine.isAvailable) {
            error = engine.status
            return
        }
        if (isKokoro && selectedStyles.sumOf { weights[it]?.toDouble() ?: 0.0 } <= 0.0) {
            error = "Give at least one selected voice a weight above zero."
            return
        }
        isRendering = true
        error = null
        scope.launch {
            try {
                val result = workbench.synthesize(
                    VoiceLabRequest(
                        engineId = engine.id,
                        voiceId = if (isKokoro) {
                            selectedStyles.singleOrNull()
                                ?: "mix:${selectedStyles.joinToString("+")}"
                        } else {
                            selectedVoiceId
                        },
                        text = normalized,
                        parameters = parameterValues,
                        kokoroStyleVector = if (isKokoro) {
                            mixStyles(styleLoader, selectedStyles, weights, interpolationMode)
                        } else {
                            null
                        },
                    ),
                )
                lastResult = result
                playbackAudio = result.audio to result.sampleRate
                player.prepare(result.audio, result.sampleRate)
                player.play()
                lastAudioRef = withContext(Dispatchers.IO) {
                    persistWorkspaceAudio(context, "mixer", result.audio, result.sampleRate)
                }
                persistWorkspace()
            } catch (failure: Exception) {
                error = failure.message ?: failure.toString()
            } finally {
                isRendering = false
            }
        }
    }

    PanelBox(
        title = "Mixer",
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
            .testTag(TtsWorkbenchTestTags.MixerScreen),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .padding(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RuntimeStatusLine()
            if (catalog.isLoading) {
                Text("Discovering speech engines…")
            }
            catalog.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TtsEngineSelector(
                engines = catalog.engines,
                selectedEngineId = selectedEngineId,
                expanded = engineExpanded,
                onExpandedChange = { engineExpanded = it },
                onSelected = {
                    selectedEngineId = it
                    engineExpanded = false
                },
            )
            TextField(
                value = text,
                onValueChange = {
                    text = it
                    persistWorkspace()
                },
                minLines = 5,
                maxLines = 12,
                label = { Text("Text to speak") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TtsWorkbenchTestTags.ScriptInput),
            )

            BrutalSection(
                title = if (isKokoro) "Voice blend" else "Voice shaping",
                expanded = settingsExpanded,
                onToggle = {
                    settingsExpanded = !settingsExpanded
                    persistWorkspace()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isKokoro) {
                    StyleSelector(
                        styleNames = styleLoader.names,
                        selectedStyles = selectedStyles,
                        onAddStyle = { style ->
                            selectedStyles = selectedStyles + style
                            weights = weights + (style to 1f)
                            persistWorkspace()
                        },
                        onRemoveStyle = { style ->
                            selectedStyles = (selectedStyles - style).ifEmpty { listOf(defaultVoice) }
                            weights = (weights - style).ifEmpty { mapOf(defaultVoice to 1f) }
                            persistWorkspace()
                        },
                    )
                    VoiceFavoritesSection(
                        favorites = favorites,
                        onSaveFavorite = { name ->
                            val normalized = name.trim()
                            if (normalized.isNotEmpty()) {
                                persistFavorites(
                                    favorites.filterNot { it.name.equals(normalized, true) } +
                                        VoiceMixFavorite(normalized, selectedStyles, weights, interpolationMode),
                                )
                            }
                        },
                        onApplyFavorite = { favorite ->
                            val mix = VoiceMixConfig(
                                favorite.styles,
                                favorite.weights,
                                favorite.interpolationMode,
                            ).filterToAvailableStyles(styleLoader.names, defaultVoice)
                            selectedStyles = mix.styles
                            weights = mix.weights
                            interpolationMode = mix.interpolationMode
                            persistWorkspace()
                        },
                        onDeleteFavorite = { name ->
                            persistFavorites(favorites.filterNot { it.name.equals(name, true) })
                        },
                    )
                    WeightSliders(
                        selectedStyles = selectedStyles,
                        weights = weights,
                        onWeightChanged = { style, value ->
                            weights = weights + (style to value.coerceIn(0f, 1f))
                            persistWorkspace()
                        },
                    )
                    InterpolationModeSelector(
                        currentMode = interpolationMode,
                        onModeSelected = {
                            interpolationMode = it
                            persistWorkspace()
                        },
                    )
                } else {
                    TtsVoiceSelector(
                        voices = voices,
                        selectedVoiceId = selectedVoiceId,
                        expanded = voiceExpanded,
                        onExpandedChange = { voiceExpanded = it },
                        onSelected = {
                            selectedVoiceId = it
                            voiceExpanded = false
                            persistWorkspace()
                        },
                    )
                }
                TtsParameterControls(
                    parameters = selectedEngine?.parameters.orEmpty(),
                    values = parameterValues,
                    onValueChange = { key, value ->
                        parameterValues = parameterValues + (key to value)
                        persistWorkspace()
                    },
                    onReset = {
                        parameterValues = defaultParameterValues(selectedEngine?.parameters.orEmpty())
                        persistWorkspace()
                    },
                    title = "Engine parameters",
                )
            }

            BrutalSection(
                title = "A/B configurations",
                expanded = comparisonExpanded,
                onToggle = { comparisonExpanded = !comparisonExpanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("A", modifier = Modifier.weight(1f))
                        BrutalButton(onClick = {
                            snapshotA = captureSnapshot()
                            persistWorkspace()
                        }) { Text("Save") }
                        BrutalButton(
                            onClick = { snapshotA?.let(::applySnapshot) },
                            enabled = snapshotA != null,
                        ) { Text("Apply") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("B", modifier = Modifier.weight(1f))
                        BrutalButton(onClick = {
                            snapshotB = captureSnapshot()
                            persistWorkspace()
                        }) { Text("Save") }
                        BrutalButton(
                            onClick = { snapshotB?.let(::applySnapshot) },
                            enabled = snapshotB != null,
                        ) { Text("Apply") }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            ) {
                BrutalButton(
                    onClick = { render(VoiceLabText.previewText(text)) },
                    enabled = !isRendering && selectedEngine?.isAvailable == true,
                    modifier = Modifier.testTag(TtsWorkbenchTestTags.PreviewButton),
                ) { Text(if (isRendering) "Rendering" else "Preview mix") }
                BrutalButton(
                    onClick = { render(text) },
                    enabled = !isRendering && selectedEngine?.isAvailable == true,
                    modifier = Modifier.testTag(TtsWorkbenchTestTags.RenderFullButton),
                ) { Text("Render mix") }
            }

            TtsPlaybackControls(
                audioDurationSeconds = playbackAudio?.let { (audio, sampleRate) ->
                    if (sampleRate > 0) audio.size.toFloat() / sampleRate else null
                },
                playerState = playerState,
                onPlay = {
                    playbackAudio?.let { (audio, sampleRate) ->
                        if (playerState == PlayerState.PAUSED) player.play()
                        else {
                            player.prepare(audio, sampleRate)
                            player.play()
                        }
                    }
                },
                onPause = player::pause,
                onRestart = {
                    playbackAudio?.let { (audio, sampleRate) ->
                        player.prepare(audio, sampleRate)
                        player.play()
                    }
                },
                onExport = {
                    playbackAudio?.let { (audio, sampleRate) ->
                        saveAudioWithDisplayName(
                            audio,
                            context,
                            "MIXER_${lastResult?.engineId ?: selectedEngineId.orEmpty()}_" +
                                (lastResult?.voiceId ?: currentSelection().voiceId ?: "blend"),
                            sampleRate,
                        )
                    }
                },
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            lastResult?.let { TtsDiagnosticsCard(it) }
        }
    }
}

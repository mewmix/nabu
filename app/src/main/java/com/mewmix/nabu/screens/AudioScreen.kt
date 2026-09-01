package com.mewmix.nabu.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.mewmix.nabu.utils.AudioWorkspaceState
import com.mewmix.nabu.utils.KokoroAudioPlayer
import com.mewmix.nabu.utils.PlayerState
import com.mewmix.nabu.utils.SettingsManager
import com.mewmix.nabu.utils.TtsWorkspaceSelection
import com.mewmix.nabu.utils.loadWorkspaceAudio
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
fun AudioScreen(workbench: TtsWorkbenchViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalog by workbench.catalog.collectAsState()
    val initialWorkspace = remember { SettingsManager.getAudioWorkspace(context) }
    var text by remember { mutableStateOf(initialWorkspace.text) }
    var selectedEngineId by remember { mutableStateOf(initialWorkspace.selection?.engineId) }
    var voices by remember { mutableStateOf<List<VoiceLabVoice>>(emptyList()) }
    var selectedVoiceId by remember {
        mutableStateOf(initialWorkspace.selection?.voiceId ?: initialWorkspace.style.takeIf(String::isNotBlank))
    }
    var parameterValues by remember {
        mutableStateOf(initialWorkspace.selection?.parameters.orEmpty())
    }
    var engineExpanded by remember { mutableStateOf(false) }
    var voiceExpanded by remember { mutableStateOf(false) }
    var controlsExpanded by remember { mutableStateOf(false) }
    var isRendering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastResult by remember { mutableStateOf<VoiceLabSynthesisResult?>(null) }
    var lastAudioRef by remember { mutableStateOf(initialWorkspace.lastAudio) }
    var playbackAudio by remember { mutableStateOf(loadWorkspaceAudio(initialWorkspace.lastAudio)) }
    var playerState by remember { mutableStateOf(PlayerState.IDLE) }
    val player = remember { KokoroAudioPlayer(scope) { playerState = it } }
    val selectedEngine = catalog.engines.firstOrNull { it.id == selectedEngineId }

    fun persistWorkspace() {
        SettingsManager.setAudioWorkspace(
            context,
            AudioWorkspaceState(
                text = text,
                style = selectedVoiceId.orEmpty(),
                speed = parameterValues["speed"]?.toFloatOrNull() ?: initialWorkspace.speed,
                lastAudio = lastAudioRef,
                selection = TtsWorkspaceSelection(
                    engineId = selectedEngineId,
                    voiceId = selectedVoiceId,
                    parameters = parameterValues,
                ),
            ),
        )
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
        isRendering = true
        error = null
        scope.launch {
            try {
                val result = workbench.synthesize(
                    VoiceLabRequest(
                        engineId = engine.id,
                        voiceId = selectedVoiceId,
                        text = normalized,
                        parameters = parameterValues,
                    ),
                )
                lastResult = result
                playbackAudio = result.audio to result.sampleRate
                player.prepare(result.audio, result.sampleRate)
                player.play()
                val ref = withContext(Dispatchers.IO) {
                    persistWorkspaceAudio(context, "audio", result.audio, result.sampleRate)
                }
                lastAudioRef = ref
                persistWorkspace()
            } catch (failure: Exception) {
                error = failure.message ?: failure.toString()
            } finally {
                isRendering = false
            }
        }
    }

    PanelBox(
        title = "Audio",
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
            .testTag(TtsWorkbenchTestTags.AudioScreen),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { RuntimeStatusLine() }
            if (catalog.isLoading) {
                item { Text("Discovering speech engines…") }
            }
            catalog.error?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error) }
            }
            item {
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
            }
            item {
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
            item {
                TextField(
                    value = text,
                    onValueChange = {
                        text = it
                        persistWorkspace()
                    },
                    label = { Text("Text to speak") },
                    minLines = 6,
                    maxLines = 14,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TtsWorkbenchTestTags.ScriptInput),
                )
            }
            item {
                BrutalSection(
                    title = "Advanced engine controls",
                    expanded = controlsExpanded,
                    onToggle = { controlsExpanded = !controlsExpanded },
                    modifier = Modifier.fillMaxWidth(),
                ) {
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
                        title = "Parameters",
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                ) {
                    BrutalButton(
                        onClick = { render(VoiceLabText.previewText(text)) },
                        enabled = !isRendering && selectedEngine?.isAvailable == true,
                        modifier = Modifier.testTag(TtsWorkbenchTestTags.PreviewButton),
                    ) {
                        Text(if (isRendering) "Rendering" else "Preview")
                    }
                    BrutalButton(
                        onClick = { render(text) },
                        enabled = !isRendering && selectedEngine?.isAvailable == true,
                        modifier = Modifier.testTag(TtsWorkbenchTestTags.RenderFullButton),
                    ) {
                        Text("Render full")
                    }
                }
            }
            item {
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
                                "AUDIO_${lastResult?.engineId ?: selectedEngineId.orEmpty()}_" +
                                    (lastResult?.voiceId ?: selectedVoiceId ?: "default"),
                                sampleRate,
                            )
                        }
                    },
                )
            }
            error?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error) }
            }
            lastResult?.let { result ->
                item { TtsDiagnosticsCard(result) }
            }
        }
    }
}

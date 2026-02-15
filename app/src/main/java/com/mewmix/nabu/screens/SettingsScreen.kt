package com.mewmix.nabu.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mewmix.nabu.kokoro.RunEp
import com.mewmix.nabu.components.VersionPlate
import com.mewmix.nabu.utils.SettingsManager
import com.mewmix.nabu.utils.OnnxRuntimeManager
import com.mewmix.nabu.utils.getAppVersion
import com.mewmix.nabu.api.ApiServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mewmix.nabu.ui.brutalist.PanelBox
import com.mewmix.nabu.ui.brutalist.SwitchToggle
import com.mewmix.nabu.BuildConfig
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.HorizontalDivider
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.ModelType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val versionName = remember { getAppVersion(context) }
    var debug by remember { mutableStateOf(SettingsManager.isDebug(context)) }
    var benchmark by remember { mutableStateOf(SettingsManager.isBenchmark(context)) }
    var methodTracing by remember { mutableStateOf(SettingsManager.isMethodTracingEnabled(context)) }
    var apiEnabled by remember { mutableStateOf(SettingsManager.isApiEnabled(context)) }
    var apiLanEnabled by remember { mutableStateOf(SettingsManager.isApiLanEnabled(context)) }
    var runtime by remember { mutableStateOf(SettingsManager.getRuntimePreference(context)) }
    val storedTtsEngine = SettingsManager.getTtsEngine(context)
    var ttsEngine by remember { mutableStateOf(storedTtsEngine) }
    var expanded by remember { mutableStateOf(false) }
    val modelManager = remember { ModelManager(context) }
    // Only Supertonic models should appear in Supertonic selector
    val supertonicModels = modelManager.models.filter { it.type == ModelType.TTS && it.id.startsWith("supertonic") }
    val ttsEngineOptions = listOf("kokoro", "supertonic", "soprano")
    var supertonicExpanded by remember { mutableStateOf(false) }
    var supertonicModelId by remember { mutableStateOf(SettingsManager.getSupertonicModelId(context)) }
    val runtimeOptions = if (ttsEngine == "supertonic" || ttsEngine == "soprano") listOf(RunEp.CPU) else RunEp.values().toList()
    val allowRuntimeSelection = runtimeOptions.size > 1
    val displayRuntime = if (ttsEngine == "supertonic" || ttsEngine == "soprano") RunEp.CPU else runtime
    var llmThreadsAuto by remember { mutableStateOf(SettingsManager.isLlmThreadsAuto(context)) }
    var llmThreads by remember { mutableStateOf(SettingsManager.getLlmThreads(context).toString()) }
    var llmMaxTokens by remember { mutableStateOf(SettingsManager.getLlmMaxNewTokens(context).toString()) }
    var llmTtftTimeout by remember { mutableStateOf(SettingsManager.getLlmTtftTimeoutMs(context).toString()) }
    var llmTotalTimeout by remember { mutableStateOf(SettingsManager.getLlmTotalTimeoutMs(context).toString()) }
    var mediaPipeBackendExpanded by remember { mutableStateOf(false) }
    var mediaPipeBackend by remember { mutableStateOf(SettingsManager.getMediaPipeBackend(context)) }
    var mediaPipeMaxTokens by remember { mutableStateOf(SettingsManager.getMediaPipeMaxTokens(context).toString()) }
    var mediaPipeMaxTopK by remember { mutableStateOf(SettingsManager.getMediaPipeMaxTopK(context).toString()) }
    var mediaPipeTopK by remember { mutableStateOf(SettingsManager.getMediaPipeTopK(context).toString()) }
    var mediaPipeTopP by remember { mutableStateOf(SettingsManager.getMediaPipeTopP(context).toString()) }
    var mediaPipeTemperature by remember { mutableStateOf(SettingsManager.getMediaPipeTemperature(context).toString()) }
    var mediaPipeRandomSeed by remember { mutableStateOf(SettingsManager.getMediaPipeRandomSeed(context).toString()) }

    LaunchedEffect(runtime, ttsEngine) {
        if (ttsEngine == "kokoro") {
            withContext(Dispatchers.IO) {
                OnnxRuntimeManager.initialize(
                    context.applicationContext,
                    runtime,
                    allowDownload = SettingsManager.isKokoroAutoDownloadEnabled(context)
                )
            }
        }
    }

    PanelBox(
        title = "Settings",
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SwitchToggle(
                checked = debug,
                onToggle = {
                    debug = it
                    SettingsManager.setDebug(context, it)
                },
                label = "Debug Mode"
            )

            SwitchToggle(
                checked = benchmark,
                onToggle = {
                    benchmark = it
                    SettingsManager.setBenchmark(context, it)
                },
                label = "Benchmark Mode"
            )

            SwitchToggle(
                checked = methodTracing,
                onToggle = { enabled ->
                    methodTracing = enabled
                    SettingsManager.setMethodTracingEnabled(context, enabled)
                    if (enabled) {
                        com.mewmix.nabu.utils.MethodTraceManager.start(context)
                    } else {
                        com.mewmix.nabu.utils.MethodTraceManager.stop()
                    }
                },
                label = "Method Tracing"
            )

            SwitchToggle(
                checked = apiEnabled,
                onToggle = { enabled ->
                    apiEnabled = enabled
                    SettingsManager.setApiEnabled(context, enabled)
                    ApiServerManager.syncWithSettings(context.applicationContext)
                },
                label = "Local API Server"
            )

            SwitchToggle(
                checked = apiLanEnabled,
                onToggle = { enabled ->
                    apiLanEnabled = enabled
                    SettingsManager.setApiLanEnabled(context, enabled)
                    ApiServerManager.syncWithSettings(context.applicationContext)
                },
                label = "Expose API on LAN"
            )

            if (apiEnabled) {
                val host = ApiServerManager.currentHost() ?: ApiServerManager.configuredHost(context.applicationContext)
                val port = ApiServerManager.currentPort()
                Text(
                    text = "Listening on http://$host:$port",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (apiLanEnabled) {
                    Text(
                        text = "LAN mode: use your device IP on the same network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Trace status line
            run {
                val running = com.mewmix.nabu.utils.MethodTraceManager.isRunning()
                val path = com.mewmix.nabu.utils.MethodTraceManager.tracePath(context) ?: "unavailable"
                val status = if (running) "Running" else "Off"
                Text(
                    text = "Tracing: $status — $path",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            HorizontalDivider()

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { if (allowRuntimeSelection) expanded = it }
            ) {
                TextField(
                    value = "${ttsEngine.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }} / ${displayRuntime.name}",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Active Engine / Provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    enabled = allowRuntimeSelection,
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    runtimeOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.name) },
                            onClick = {
                                runtime = option
                                SettingsManager.setRuntimePreference(context, option)
                                expanded = false
                            }
                        )
                    }
                }
            }

            // TTS Engine Selection
            var ttsEngineExpanded by remember { mutableStateOf(false) }

            ExposedDropdownMenuBox(
                expanded = ttsEngineExpanded,
                onExpandedChange = { ttsEngineExpanded = it }
            ) {
                TextField(
                    value = ttsEngine.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("TTS Engine") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ttsEngineExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                DropdownMenu(
                    expanded = ttsEngineExpanded,
                    onDismissRequest = { ttsEngineExpanded = false }
                ) {
                    ttsEngineOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }) },
                            onClick = {
                                ttsEngine = option
                                SettingsManager.setTtsEngine(context, option)
                                com.mewmix.nabu.utils.DebugLogger.log("Settings: TTS Engine set to $option")
                                ttsEngineExpanded = false
                            }
                        )
                    }
                }
            }

            if (ttsEngine == "supertonic" && supertonicModels.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = supertonicExpanded,
                    onExpandedChange = { supertonicExpanded = it }
                ) {
                    TextField(
                        value = supertonicModels.firstOrNull { it.id == supertonicModelId }?.name ?: "Select Supertonic model",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Supertonic Model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = supertonicExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = supertonicExpanded,
                        onDismissRequest = { supertonicExpanded = false }
                    ) {
                        supertonicModels.forEach { model ->
                            val label = if (model.isDownloaded) {
                                model.name
                            } else {
                                "${model.name} (not downloaded)"
                            }
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    supertonicModelId = model.id
                                    SettingsManager.setSupertonicModelId(context, model.id)
                                    supertonicExpanded = false
                                }
                            )
                        }
                    }
                }
            } else if (ttsEngine == "supertonic") {
                Text(
                    text = "No Supertonic models available. Open Models to download one.",
                    color = MaterialTheme.colorScheme.error
                )
            }

            HorizontalDivider()

            Text(
                text = "LLAMA Settings (.gguf)",
                style = MaterialTheme.typography.titleMedium
            )

            SwitchToggle(
                checked = llmThreadsAuto,
                onToggle = {
                    llmThreadsAuto = it
                    SettingsManager.setLlmThreadsAuto(context, it)
                },
                label = "Threads: Auto"
            )

            if (!llmThreadsAuto) {
                TextField(
                    value = llmThreads,
                    onValueChange = { value ->
                        val filtered = value.filter { it.isDigit() }
                        llmThreads = filtered
                        filtered.toIntOrNull()?.let { SettingsManager.setLlmThreads(context, it) }
                    },
                    label = { Text("Threads") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            TextField(
                value = llmMaxTokens,
                onValueChange = { value ->
                    val filtered = value.filter { it.isDigit() }
                    llmMaxTokens = filtered
                    filtered.toIntOrNull()?.let { SettingsManager.setLlmMaxNewTokens(context, it) }
                },
                label = { Text("Max New Tokens") },
                modifier = Modifier.fillMaxWidth()
            )

            TextField(
                value = llmTtftTimeout,
                onValueChange = { value ->
                    val filtered = value.filter { it.isDigit() }
                    llmTtftTimeout = filtered
                    filtered.toLongOrNull()?.let { SettingsManager.setLlmTtftTimeoutMs(context, it) }
                },
                label = { Text("TTFT Timeout (ms)") },
                modifier = Modifier.fillMaxWidth()
            )

            TextField(
                value = llmTotalTimeout,
                onValueChange = { value ->
                    val filtered = value.filter { it.isDigit() }
                    llmTotalTimeout = filtered
                    filtered.toLongOrNull()?.let { SettingsManager.setLlmTotalTimeoutMs(context, it) }
                },
                label = { Text("Total Timeout (ms)") },
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            Text(
                text = "LiteRT (.task) / MediaPipe Settings",
                style = MaterialTheme.typography.titleMedium
            )

            ExposedDropdownMenuBox(
                expanded = mediaPipeBackendExpanded,
                onExpandedChange = { mediaPipeBackendExpanded = it }
            ) {
                TextField(
                    value = when (mediaPipeBackend) {
                        "cpu" -> "CPU"
                        "gpu" -> "GPU"
                        else -> "Default"
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Preferred Backend") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = mediaPipeBackendExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                DropdownMenu(
                    expanded = mediaPipeBackendExpanded,
                    onDismissRequest = { mediaPipeBackendExpanded = false }
                ) {
                    listOf("default" to "Default", "cpu" to "CPU", "gpu" to "GPU").forEach { (id, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                mediaPipeBackend = id
                                SettingsManager.setMediaPipeBackend(context, id)
                                mediaPipeBackendExpanded = false
                            }
                        )
                    }
                }
            }

            TextField(
                value = mediaPipeMaxTokens,
                onValueChange = { value ->
                    val filtered = value.filter { it.isDigit() }
                    mediaPipeMaxTokens = filtered
                    filtered.toIntOrNull()?.let { SettingsManager.setMediaPipeMaxTokens(context, it) }
                },
                label = { Text("Max Tokens") },
                modifier = Modifier.fillMaxWidth()
            )

            TextField(
                value = mediaPipeMaxTopK,
                onValueChange = { value ->
                    val filtered = value.filter { it.isDigit() }
                    mediaPipeMaxTopK = filtered
                    filtered.toIntOrNull()?.let { SettingsManager.setMediaPipeMaxTopK(context, it) }
                },
                label = { Text("Max Top-K") },
                modifier = Modifier.fillMaxWidth()
            )

            TextField(
                value = mediaPipeTopK,
                onValueChange = { value ->
                    val filtered = value.filter { it.isDigit() }
                    mediaPipeTopK = filtered
                    filtered.toIntOrNull()?.let { SettingsManager.setMediaPipeTopK(context, it) }
                },
                label = { Text("Top-K") },
                modifier = Modifier.fillMaxWidth()
            )

            TextField(
                value = mediaPipeTopP,
                onValueChange = { value ->
                    val filtered = value.filter { it.isDigit() || it == '.' }
                    mediaPipeTopP = filtered
                    filtered.toFloatOrNull()?.let { SettingsManager.setMediaPipeTopP(context, it) }
                },
                label = { Text("Top-P (0.0-1.0)") },
                modifier = Modifier.fillMaxWidth()
            )

            TextField(
                value = mediaPipeTemperature,
                onValueChange = { value ->
                    val filtered = value.filter { it.isDigit() || it == '.' }
                    mediaPipeTemperature = filtered
                    filtered.toFloatOrNull()?.let { SettingsManager.setMediaPipeTemperature(context, it) }
                },
                label = { Text("Temperature (0.0-2.0)") },
                modifier = Modifier.fillMaxWidth()
            )

            TextField(
                value = mediaPipeRandomSeed,
                onValueChange = { value ->
                    val filtered = value.filterIndexed { index, c ->
                        c.isDigit() || (c == '-' && index == 0)
                    }
                    mediaPipeRandomSeed = filtered
                    filtered.toIntOrNull()?.let { SettingsManager.setMediaPipeRandomSeed(context, it) }
                },
                label = { Text("Random Seed (-1 = default)") },
                modifier = Modifier.fillMaxWidth()
            )

            val commitHash = BuildConfig.GIT_COMMIT_HASH.ifBlank { "unknown" }
            val shortHash = commitHash.take(7)
            val versionText = "v$versionName ($shortHash)"
            VersionPlate(version = versionText, onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mewmix/nabu/commit/$commitHash"))
                context.startActivity(intent)
            })
        }
    }
}

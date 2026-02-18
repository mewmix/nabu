package com.mewmix.nabu.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mewmix.nabu.kokoro.RunEp
import com.mewmix.nabu.components.VersionPlate
import com.mewmix.nabu.utils.SettingsManager
import com.mewmix.nabu.utils.OnnxRuntimeManager
import com.mewmix.nabu.utils.UpdateChecker
import com.mewmix.nabu.utils.getAppVersion
import com.mewmix.nabu.api.ApiServerManager
import com.mewmix.nabu.auth.GeminiAuthenticator
import com.mewmix.nabu.auth.CodexAuthenticator
import com.mewmix.nabu.auth.GeminiApiClient
import com.mewmix.nabu.auth.CodexApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mewmix.nabu.ui.brutalist.PanelBox
import com.mewmix.nabu.ui.brutalist.SwitchToggle
import com.mewmix.nabu.BuildConfig
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.HorizontalDivider
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.ModelType
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onRuntimeSettingsChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    var updateStatus by remember { mutableStateOf(UpdateChecker.cachedStatus(context)) }
    var checkingForUpdate by remember { mutableStateOf(false) }
    var updateError by remember { mutableStateOf<String?>(null) }
    val geminiAuth = remember { GeminiAuthenticator() }
    val codexAuth = remember { CodexAuthenticator() }
    val geminiClient = remember { GeminiApiClient(geminiAuth) }
    val codexClient = remember { CodexApiClient(codexAuth) }
    var geminiOAuthClientId by remember { mutableStateOf(SettingsManager.getGeminiOAuthClientId(context)) }
    var geminiOAuthRedirectUri by remember { mutableStateOf(SettingsManager.getGeminiOAuthRedirectUri(context)) }
    var geminiConnected by remember { mutableStateOf(geminiAuth.hasStoredSession(context)) }
    var codexConnected by remember { mutableStateOf(codexAuth.hasStoredSession(context)) }
    var testingGemini by remember { mutableStateOf(false) }
    var testingCodex by remember { mutableStateOf(false) }
    var geminiStatus by remember { mutableStateOf<String?>(null) }
    var codexStatus by remember { mutableStateOf<String?>(null) }

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
    LaunchedEffect(Unit) {
        updateStatus = withContext(Dispatchers.IO) { UpdateChecker.cachedStatus(context) }
        geminiConnected = geminiAuth.hasStoredSession(context.applicationContext)
        codexConnected = codexAuth.hasStoredSession(context.applicationContext)
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
                                onRuntimeSettingsChanged()
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
                                onRuntimeSettingsChanged()
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
                                    onRuntimeSettingsChanged()
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

            HorizontalDivider()

            Text(
                text = "Integrations (Experimental)",
                style = MaterialTheme.typography.titleMedium
            )

            TextField(
                value = geminiOAuthClientId,
                onValueChange = { value ->
                    geminiOAuthClientId = value
                    SettingsManager.setGeminiOAuthClientId(context, value)
                },
                label = { Text("Gemini OAuth Client ID") },
                modifier = Modifier.fillMaxWidth()
            )

            TextField(
                value = geminiOAuthRedirectUri,
                onValueChange = { value ->
                    geminiOAuthRedirectUri = value
                    SettingsManager.setGeminiOAuthRedirectUri(context, value)
                },
                label = { Text("Gemini Redirect URI") },
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = if (geminiConnected) "Gemini: Connected" else "Gemini: Not connected",
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = {
                    if (geminiOAuthClientId.isBlank()) {
                        geminiStatus = "Gemini OAuth Client ID is required."
                    } else {
                        geminiAuth.initiateLogin(context)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (geminiConnected) "Reconnect Gemini Account" else "Connect Gemini Account")
            }

            Button(
                onClick = {
                    scope.launch {
                        testingGemini = true
                        geminiStatus = null
                        val result = geminiClient.sendPrompt(
                            context = context.applicationContext,
                            prompt = "Reply with: Gemini connection OK."
                        )
                        geminiStatus = result.fold(
                            onSuccess = { "Gemini response: $it" },
                            onFailure = { "Gemini call failed: ${it.message}" }
                        )
                        geminiConnected = geminiAuth.hasStoredSession(context.applicationContext)
                        testingGemini = false
                    }
                },
                enabled = geminiConnected && !testingGemini,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (testingGemini) "Testing Gemini..." else "Test Gemini Call")
            }

            Button(
                onClick = {
                    geminiAuth.logout(context.applicationContext)
                    geminiConnected = false
                    geminiStatus = "Gemini disconnected."
                },
                enabled = geminiConnected,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Disconnect Gemini")
            }
            geminiStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = if (codexConnected) "Codex: Connected" else "Codex: Not connected",
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = { codexAuth.initiateLogin(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (codexConnected) "Reconnect Codex Account" else "Connect Codex (OpenAI) Account")
            }

            Button(
                onClick = {
                    scope.launch {
                        testingCodex = true
                        codexStatus = null
                        val responseResult = codexClient.sendPrompt(
                            context = context.applicationContext,
                            prompt = "Reply with: Codex connection OK."
                        )
                        codexStatus = responseResult.fold(
                            onSuccess = { "Codex response: $it" },
                            onFailure = { callError ->
                                val usage = codexClient.fetchUsageSummary(context.applicationContext)
                                usage.fold(
                                    onSuccess = { "Codex usage reachable: $it (prompt failed: ${callError.message})" },
                                    onFailure = { "Codex call failed: ${callError.message}" }
                                )
                            }
                        )
                        codexConnected = codexAuth.hasStoredSession(context.applicationContext)
                        testingCodex = false
                    }
                },
                enabled = codexConnected && !testingCodex,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (testingCodex) "Testing Codex..." else "Test Codex Call")
            }

            Button(
                onClick = {
                    codexAuth.logout(context.applicationContext)
                    codexConnected = false
                    codexStatus = "Codex disconnected."
                },
                enabled = codexConnected,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Disconnect Codex")
            }
            codexStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            HorizontalDivider()

            Text(
                text = "App Updates (GitHub Releases)",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "Current: v$versionName",
                style = MaterialTheme.typography.bodySmall
            )
            updateStatus.latestVersion?.let { latest ->
                Text(
                    text = "Latest: $latest",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = if (updateStatus.updateAvailable) "Update available" else "No update detected",
                style = MaterialTheme.typography.bodySmall
            )
            if (updateStatus.lastCheckedAt > 0L) {
                val checkedAt = remember(updateStatus.lastCheckedAt) {
                    DateFormat.getDateTimeInstance().format(Date(updateStatus.lastCheckedAt))
                }
                Text(
                    text = "Last checked: $checkedAt",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            updateError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        checkingForUpdate = true
                        updateError = null
                        val result = withContext(Dispatchers.IO) {
                            UpdateChecker.checkForUpdate(context, force = true)
                        }
                        updateStatus = withContext(Dispatchers.IO) { UpdateChecker.cachedStatus(context) }
                        if (!result.success) {
                            updateError = "Update check failed. Using cached result."
                        }
                        checkingForUpdate = false
                    }
                },
                enabled = !checkingForUpdate,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (checkingForUpdate) "Checking..." else "Check for Updates")
            }

            if (updateStatus.updateAvailable && !updateStatus.releaseUrl.isNullOrBlank()) {
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateStatus.releaseUrl))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Latest Release")
                }
            }

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

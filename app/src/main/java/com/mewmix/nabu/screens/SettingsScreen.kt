package com.mewmix.nabu.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mewmix.nabu.kokoro.RunEp
import com.mewmix.nabu.components.VersionPlate
import com.mewmix.nabu.utils.SettingsManager
import com.mewmix.nabu.utils.OnnxRuntimeManager
import com.mewmix.nabu.utils.UpdateChecker
import com.mewmix.nabu.utils.ThemeManager
import com.mewmix.nabu.utils.getAppVersion
import com.mewmix.nabu.ui.theme.AppTheme
import com.mewmix.nabu.api.ApiServerManager
import com.mewmix.nabu.api.ApiServerRuntime
import com.mewmix.nabu.auth.CodexAuthenticator
import com.mewmix.nabu.auth.CodexApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mewmix.nabu.ui.brutalist.BrutalSection
import com.mewmix.nabu.ui.brutalist.PanelBox
import com.mewmix.nabu.ui.brutalist.SwitchToggle
import com.mewmix.nabu.ui.components.ColorPickerField
import com.mewmix.nabu.BuildConfig
import android.content.Intent
import android.net.Uri
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.ModelType
import com.mewmix.nabu.supertonic.SupertonicLanguages
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onRuntimeSettingsChanged: () -> Unit = {},
    onThemeChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val versionName = remember { getAppVersion(context) }
    var debug by remember { mutableStateOf(SettingsManager.isDebug(context)) }
    var benchmark by remember { mutableStateOf(SettingsManager.isBenchmark(context)) }
    var methodTracing by remember { mutableStateOf(SettingsManager.isMethodTracingEnabled(context)) }
    var apiEnabled by remember { mutableStateOf(SettingsManager.isApiEnabled(context)) }
    var apiLanEnabled by remember { mutableStateOf(SettingsManager.isApiLanEnabled(context)) }
    var apiBackgroundEnabled by remember { mutableStateOf(SettingsManager.isApiBackgroundEnabled(context)) }
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
    var supertonicLanguageExpanded by remember { mutableStateOf(false) }
    var supertonicLanguage by remember { mutableStateOf(SettingsManager.getSupertonicLanguage(context)) }
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
    val codexAuth = remember { CodexAuthenticator() }
    val codexClient = remember { CodexApiClient(codexAuth) }
    var codexConnected by remember { mutableStateOf(codexAuth.hasStoredSession(context)) }
    var testingCodex by remember { mutableStateOf(false) }
    var codexStatus by remember { mutableStateOf<String?>(null) }
    var themeMode by remember { mutableStateOf(ThemeManager.getThemeMode(context)) }
    var themeModeExpanded by remember { mutableStateOf(false) }
    var savedCustomThemes by remember { mutableStateOf(ThemeManager.getSavedCustomThemes(context)) }
    var selectedCustomThemeName by remember { mutableStateOf(savedCustomThemes.keys.firstOrNull() ?: "My Theme") }
    var customThemeName by remember { mutableStateOf(selectedCustomThemeName) }
    var savedCustomThemesExpanded by remember { mutableStateOf(false) }
    var customThemeDraft by remember { mutableStateOf(ThemeManager.getTheme(context)) }
    var customPrimary by remember { mutableStateOf(formatThemeHex(customThemeDraft.primary)) }
    var customSecondary by remember { mutableStateOf(formatThemeHex(customThemeDraft.secondary)) }
    var customBackground by remember { mutableStateOf(formatThemeHex(customThemeDraft.background)) }
    var customSurface by remember { mutableStateOf(formatThemeHex(customThemeDraft.surface)) }
    var customOnSurface by remember { mutableStateOf(formatThemeHex(customThemeDraft.onSurface)) }
    var customSurfaceVariant by remember { mutableStateOf(formatThemeHex(customThemeDraft.surfaceVariant)) }
    var customOutline by remember { mutableStateOf(formatThemeHex(customThemeDraft.outline)) }
    var customPanelRadius by remember { mutableStateOf(formatThemeNumber(customThemeDraft.panelRadiusDp ?: 24f)) }
    var customControlRadius by remember { mutableStateOf(formatThemeNumber(customThemeDraft.controlRadiusDp ?: 18f)) }
    var customBorderWidth by remember { mutableStateOf(formatThemeNumber(customThemeDraft.borderWidthDp ?: 1f)) }
    var customThemeError by remember { mutableStateOf<String?>(null) }
    var appearanceExpanded by remember { mutableStateOf(false) }
    var diagnosticsExpanded by remember { mutableStateOf(false) }
    var speechRuntimeExpanded by remember { mutableStateOf(false) }
    var llamaExpanded by remember { mutableStateOf(false) }
    var mediaPipeExpanded by remember { mutableStateOf(false) }
    var permissionsExpanded by remember { mutableStateOf(false) }
    var integrationsExpanded by remember { mutableStateOf(false) }
    var updatesExpanded by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

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
        codexConnected = codexAuth.hasStoredSession(context.applicationContext)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                codexConnected = codexAuth.hasStoredSession(context.applicationContext)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
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
            BrutalSection(
                title = "Appearance",
                expanded = appearanceExpanded,
                onToggle = { appearanceExpanded = !appearanceExpanded }
            ) {
                ExposedDropdownMenuBox(
                    expanded = themeModeExpanded,
                    onExpandedChange = { themeModeExpanded = it }
                ) {
                    TextField(
                        value = themeMode.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Theme Mode") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = themeModeExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = themeModeExpanded,
                        onDismissRequest = { themeModeExpanded = false }
                    ) {
                        ThemeManager.ThemeMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.label) },
                                onClick = {
                                    themeMode = mode
                                    ThemeManager.setThemeMode(context, mode)
                                    onThemeChanged()
                                    themeModeExpanded = false
                                }
                            )
                        }
                    }
                }

                if (themeMode == ThemeManager.ThemeMode.CUSTOM) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        ExposedDropdownMenuBox(
                            expanded = savedCustomThemesExpanded,
                            onExpandedChange = { savedCustomThemesExpanded = it },
                            modifier = Modifier.weight(1f)
                        ) {
                            TextField(
                                value = selectedCustomThemeName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Saved Custom Themes") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = savedCustomThemesExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            DropdownMenu(
                                expanded = savedCustomThemesExpanded,
                                onDismissRequest = { savedCustomThemesExpanded = false }
                            ) {
                                savedCustomThemes.keys.forEach { themeName ->
                                    DropdownMenuItem(
                                        text = { Text(themeName) },
                                        onClick = {
                                            selectedCustomThemeName = themeName
                                            customThemeName = themeName
                                            val loadedTheme = savedCustomThemes[themeName]
                                            if (loadedTheme != null) {
                                                customThemeDraft = loadedTheme
                                                customPrimary = formatThemeHex(loadedTheme.primary)
                                                customSecondary = formatThemeHex(loadedTheme.secondary)
                                                customBackground = formatThemeHex(loadedTheme.background)
                                                customSurface = formatThemeHex(loadedTheme.surface)
                                                customOnSurface = formatThemeHex(loadedTheme.onSurface)
                                                customSurfaceVariant = formatThemeHex(loadedTheme.surfaceVariant)
                                                customOutline = formatThemeHex(loadedTheme.outline)
                                                customPanelRadius = formatThemeNumber(loadedTheme.panelRadiusDp ?: 24f)
                                                customControlRadius = formatThemeNumber(loadedTheme.controlRadiusDp ?: 18f)
                                                customBorderWidth = formatThemeNumber(loadedTheme.borderWidthDp ?: 1f)
                                                ThemeManager.saveTheme(context, loadedTheme)
                                                onThemeChanged()
                                            }
                                            savedCustomThemesExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        
                        IconButton(
                            onClick = {
                                ThemeManager.deleteCustomTheme(context, selectedCustomThemeName)
                                savedCustomThemes = ThemeManager.getSavedCustomThemes(context)
                                val newFirst = savedCustomThemes.keys.firstOrNull() ?: "My Theme"
                                selectedCustomThemeName = newFirst
                                customThemeName = newFirst
                                if (savedCustomThemes.isEmpty()) {
                                    customThemeDraft = ThemeManager.DEFAULT_LIGHT
                                    customPrimary = formatThemeHex(customThemeDraft.primary)
                                    customSecondary = formatThemeHex(customThemeDraft.secondary)
                                    customBackground = formatThemeHex(customThemeDraft.background)
                                    customSurface = formatThemeHex(customThemeDraft.surface)
                                    customOnSurface = formatThemeHex(customThemeDraft.onSurface)
                                    customSurfaceVariant = formatThemeHex(customThemeDraft.surfaceVariant)
                                    customOutline = formatThemeHex(customThemeDraft.outline)
                                    customPanelRadius = formatThemeNumber(customThemeDraft.panelRadiusDp ?: 24f)
                                    customControlRadius = formatThemeNumber(customThemeDraft.controlRadiusDp ?: 18f)
                                    customBorderWidth = formatThemeNumber(customThemeDraft.borderWidthDp ?: 1f)
                                    ThemeManager.saveTheme(context, customThemeDraft)
                                    onThemeChanged()
                                }
                            },
                            enabled = savedCustomThemes.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete Theme")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextField(
                        value = customThemeName,
                        onValueChange = { customThemeName = it },
                        label = { Text("Theme Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    ColorPickerField(
                        value = customPrimary,
                        onValueChange = { customPrimary = it },
                        label = "Primary (#RRGGBB or #AARRGGBB)",
                        modifier = Modifier.fillMaxWidth()
                    )
                    ColorPickerField(
                        value = customSecondary,
                        onValueChange = { customSecondary = it },
                        label = "Secondary",
                        modifier = Modifier.fillMaxWidth()
                    )
                    ColorPickerField(
                        value = customBackground,
                        onValueChange = { customBackground = it },
                        label = "Background",
                        modifier = Modifier.fillMaxWidth()
                    )
                    ColorPickerField(
                        value = customSurface,
                        onValueChange = { customSurface = it },
                        label = "Surface",
                        modifier = Modifier.fillMaxWidth()
                    )
                    ColorPickerField(
                        value = customOnSurface,
                        onValueChange = { customOnSurface = it },
                        label = "Text / On Surface",
                        modifier = Modifier.fillMaxWidth()
                    )
                    ColorPickerField(
                        value = customSurfaceVariant,
                        onValueChange = { customSurfaceVariant = it },
                        label = "Surface Variant",
                        modifier = Modifier.fillMaxWidth()
                    )
                    ColorPickerField(
                        value = customOutline,
                        onValueChange = { customOutline = it },
                        label = "Outline",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextField(
                            value = customPanelRadius,
                            onValueChange = { customPanelRadius = it },
                            label = { Text("Panel radius") },
                            modifier = Modifier.weight(1f)
                        )
                        TextField(
                            value = customControlRadius,
                            onValueChange = { customControlRadius = it },
                            label = { Text("Control radius") },
                            modifier = Modifier.weight(1f)
                        )
                        TextField(
                            value = customBorderWidth,
                            onValueChange = { customBorderWidth = it },
                            label = { Text("Border") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Button(
                        onClick = {
                            val parsedPrimary = parseThemeHex(customPrimary)
                            val parsedSecondary = parseThemeHex(customSecondary)
                            val parsedBackground = parseThemeHex(customBackground)
                            val parsedSurface = parseThemeHex(customSurface)
                            val parsedOnSurface = parseThemeHex(customOnSurface)
                            val parsedSurfaceVariant = parseThemeHex(customSurfaceVariant)
                            val parsedOutline = parseThemeHex(customOutline)
                            val parsedPanelRadius = parseThemeFloat(customPanelRadius, 0f, 48f)
                            val parsedControlRadius = parseThemeFloat(customControlRadius, 0f, 36f)
                            val parsedBorderWidth = parseThemeFloat(customBorderWidth, 0f, 6f)
                            if (customThemeName.isBlank()) {
                                customThemeError = "Please enter a name for your theme."
                            } else if (
                                parsedPrimary == null ||
                                parsedSecondary == null ||
                                parsedBackground == null ||
                                parsedSurface == null ||
                                parsedOnSurface == null ||
                                parsedSurfaceVariant == null ||
                                parsedOutline == null
                            ) {
                                customThemeError = "Use #RRGGBB or #AARRGGBB hex colors."
                            } else if (parsedPanelRadius == null || parsedControlRadius == null || parsedBorderWidth == null) {
                                customThemeError = "Shape values must be numbers in the allowed range."
                            } else {
                                customThemeDraft = customThemeDraft.copy(
                                    primary = parsedPrimary,
                                    secondary = parsedSecondary,
                                    background = parsedBackground,
                                    surface = parsedSurface,
                                    onSurface = parsedOnSurface,
                                    surfaceVariant = parsedSurfaceVariant,
                                    outline = parsedOutline,
                                    panelRadiusDp = parsedPanelRadius,
                                    controlRadiusDp = parsedControlRadius,
                                    borderWidthDp = parsedBorderWidth
                                )
                                ThemeManager.saveCustomThemeWithName(context, customThemeName, customThemeDraft)
                                savedCustomThemes = ThemeManager.getSavedCustomThemes(context)
                                selectedCustomThemeName = customThemeName
                                themeMode = ThemeManager.ThemeMode.CUSTOM
                                customThemeError = null
                                onThemeChanged()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Custom Theme")
                    }
                    customThemeError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            BrutalSection(
                title = "Diagnostics & API",
                expanded = diagnosticsExpanded,
                onToggle = { diagnosticsExpanded = !diagnosticsExpanded }
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
                    ApiServerRuntime.syncWithSettings(context.applicationContext)
                },
                label = "Local API Server"
            )

            SwitchToggle(
                checked = apiLanEnabled,
                onToggle = { enabled ->
                    apiLanEnabled = enabled
                    SettingsManager.setApiLanEnabled(context, enabled)
                    ApiServerRuntime.syncWithSettings(context.applicationContext)
                },
                label = "Expose API on LAN"
            )

            SwitchToggle(
                checked = apiBackgroundEnabled,
                onToggle = { enabled ->
                    apiBackgroundEnabled = enabled
                    SettingsManager.setApiBackgroundEnabled(context, enabled)
                    ApiServerRuntime.syncWithSettings(context.applicationContext)
                },
                label = "Keep API running in background"
            )

            if (apiBackgroundEnabled) {
                Text(
                    text = "Background mode keeps a persistent notification and may use more battery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (apiEnabled) {
                val host = ApiServerManager.currentHost() ?: ApiServerManager.configuredHost(context.applicationContext)
                val port = ApiServerManager.currentPort()
                val lanIp = if (apiLanEnabled) ApiServerManager.localLanIpAddress() else null
                Text(
                    text = if (lanIp != null) "Listening on http://$lanIp:$port (bound: $host)" else "Listening on http://$host:$port",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (apiLanEnabled) {
                    Text(
                        text = if (lanIp != null) {
                            "LAN mode: share http://$lanIp:$port on the same network."
                        } else {
                            "LAN mode is enabled; connect to your device IP on the same network."
                        },
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
            }

            BrutalSection(
                title = "Speech Runtime",
                expanded = speechRuntimeExpanded,
                onToggle = { speechRuntimeExpanded = !speechRuntimeExpanded }
            ) {
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

                ExposedDropdownMenuBox(
                    expanded = supertonicLanguageExpanded,
                    onExpandedChange = { supertonicLanguageExpanded = it }
                ) {
                    TextField(
                        value = supertonicLanguage,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Supertonic Language") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = supertonicLanguageExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = supertonicLanguageExpanded,
                        onDismissRequest = { supertonicLanguageExpanded = false }
                    ) {
                        SupertonicLanguages.available.forEach { language ->
                            DropdownMenuItem(
                                text = { Text(language) },
                                onClick = {
                                    supertonicLanguage = language
                                    SettingsManager.setSupertonicLanguage(context, language)
                                    onRuntimeSettingsChanged()
                                    supertonicLanguageExpanded = false
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
            }

            BrutalSection(
                title = "LLAMA Settings (.gguf)",
                expanded = llamaExpanded,
                onToggle = { llamaExpanded = !llamaExpanded }
            ) {
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
            }

            BrutalSection(
                title = "LiteRT / MediaPipe",
                expanded = mediaPipeExpanded,
                onToggle = { mediaPipeExpanded = !mediaPipeExpanded }
            ) {

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
            }

            BrutalSection(
                title = "Optional Permissions",
                expanded = permissionsExpanded,
                onToggle = { permissionsExpanded = !permissionsExpanded }
            ) {
                OptionalPermissionsSection(showContinue = false)

                Button(
                    onClick = { SettingsManager.setOptionalPermissionsReviewed(context, false) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Show Permission Review On Next Startup")
                }
            }

            BrutalSection(
                title = "Integrations",
                expanded = integrationsExpanded,
                onToggle = { integrationsExpanded = !integrationsExpanded }
            ) {

            Text(
                text = "Glaive adds external tools that Nabu can call when both apps are installed from matching builds.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mewmix/glaive"))
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Download Glaive")
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
            }

            BrutalSection(
                title = "App Updates",
                expanded = updatesExpanded,
                onToggle = { updatesExpanded = !updatesExpanded }
            ) {

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
}

private fun formatThemeHex(value: Long): String =
    "#%08X".format(value)

private fun formatThemeNumber(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else "%.1f".format(value)

private fun parseThemeHex(input: String): Long? {
    val cleaned = input.trim().removePrefix("#")
    val withAlpha = when (cleaned.length) {
        6 -> "FF$cleaned"
        8 -> cleaned
        else -> return null
    }
    return withAlpha.toLongOrNull(16)?.let { 0x00000000FFFFFFFFL and it }
}

private fun parseThemeFloat(input: String, min: Float, max: Float): Float? =
    input.trim().toFloatOrNull()?.takeIf { it in min..max }

@Suppress("unused")
private fun AppTheme.withCoreColors(primary: Long, secondary: Long, background: Long, surface: Long): AppTheme =
    copy(primary = primary, secondary = secondary, background = background, surface = surface)

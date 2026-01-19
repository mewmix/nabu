package com.mewmix.nabu.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mewmix.nabu.utils.SettingsManager
import com.mewmix.nabu.utils.OnnxRuntimeManager
import com.mewmix.nabu.utils.formatBytes
import com.mewmix.nabu.kokoro.Downloader
import com.mewmix.nabu.data.ModelDownloader
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.ModelType
import com.mewmix.nabu.data.Model
import com.mewmix.nabu.data.UserPreferencesRepository
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.BrutalButtonText
import com.mewmix.nabu.ui.brutalist.Brutal
import com.mewmix.nabu.ui.brutalist.PanelBox
import com.mewmix.nabu.ui.brutalist.SwitchToggle
import com.mewmix.nabu.utils.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InitScreen(
    userPreferencesRepository: UserPreferencesRepository,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var engine by remember { mutableStateOf(SettingsManager.getTtsEngine(context)) }
    var downloadNow by remember { mutableStateOf(SettingsManager.isKokoroAutoDownloadEnabled(context)) }
    var engineExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Downloader.DownloadProgress?>(null) }
    var downloadTargetId by remember { mutableStateOf<String?>(null) }

    val modelManager = remember { ModelManager(context) }
    val modelDownloader = remember { ModelDownloader(context, userPreferencesRepository) }
    val progressMap by modelDownloader.progress.collectAsState()
    val ttsModels = modelManager.models.filter { it.type == ModelType.TTS }
    var selectedModel by remember { mutableStateOf<Model?>(ttsModels.firstOrNull()) }

    LaunchedEffect(engine) {
        if (engine != "kokoro") {
            downloadNow = false
        }
    }
    LaunchedEffect(progressMap) {
        val targetId = downloadTargetId
        if (targetId != null && !progressMap.containsKey(targetId)) {
            val completed = modelManager.models.firstOrNull { it.id == targetId }?.isDownloaded == true
            if (completed) {
                SettingsManager.setInitComplete(context, true)
                isDownloading = false
                downloadTargetId = null
                onComplete()
            }
        }
    }

    PanelBox(
        title = "Welcome",
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "Welcome to Nabu! Please pick which text-to-voice model you want to use.\n\nThis can be changed later from settings. If you don't know which one to pick, just use the default.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            ExposedDropdownMenuBox(
                expanded = engineExpanded,
                onExpandedChange = { engineExpanded = it }
            ) {
                TextField(
                    value = engine.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("TTS Engine") },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                DropdownMenu(
                    expanded = engineExpanded,
                    onDismissRequest = { engineExpanded = false }
                ) {
                    listOf("kokoro", "supertonic").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }) },
                            onClick = {
                                engine = option
                                engineExpanded = false
                            }
                        )
                    }
                }
            }

            if (engine == "kokoro") {
                SwitchToggle(
                    checked = downloadNow,
                    onToggle = { downloadNow = it },
                    label = "Download Kokoro voice models now"
                )
            } else {
                SwitchToggle(
                    checked = downloadNow,
                    onToggle = { downloadNow = it },
                    label = "Download Supertonic voice models now"
                )
                if (ttsModels.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = modelExpanded,
                        onExpandedChange = { modelExpanded = it }
                    ) {
                        TextField(
                            value = selectedModel?.name ?: "Select model",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Supertonic Model") },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        DropdownMenu(
                            expanded = modelExpanded,
                            onDismissRequest = { modelExpanded = false }
                        ) {
                            ttsModels.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model.name) },
                                    onClick = {
                                        selectedModel = model
                                        modelExpanded = false
                                    }
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = "No Supertonic models available.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (engine == "kokoro") {
                progress?.let { current ->
                    if (current.totalBytes > 0L) {
                        val ratio = current.downloadedBytes.toFloat() / current.totalBytes.toFloat()
                        LinearProgressIndicator(
                            progress = { ratio.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "${formatBytes(current.downloadedBytes)} / ${formatBytes(current.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Brutal.textBright
                        )
                    }
                }
            }

            BrutalButton(
                onClick = {
                    SettingsManager.setTtsEngine(context, engine)
                    if (engine == "supertonic") {
                        SettingsManager.setSupertonicModelId(context, selectedModel?.id)
                    }
                    if (engine == "kokoro") {
                        SettingsManager.setKokoroAutoDownload(context, downloadNow)
                    }
                    if (engine == "kokoro" && downloadNow) {
                        isDownloading = true
                        progress = null
                        scope.launch(Dispatchers.IO) {
                            DebugLogger.log("InitScreen: Starting Kokoro download")
                            val result = OnnxRuntimeManager.initialize(
                                context.applicationContext,
                                allowDownload = true,
                                onProgress = { update -> scope.launch { progress = update } }
                            )
                            withContext(Dispatchers.Main) {
                                isDownloading = false
                                result.onSuccess {
                                    DebugLogger.log("InitScreen: Kokoro download complete")
                                    SettingsManager.setInitComplete(context, true)
                                    onComplete()
                                }.onFailure { error ->
                                    DebugLogger.log("InitScreen: Kokoro download failed: ${error.message}")
                                    Toast.makeText(
                                        context,
                                        error.message ?: "Failed to download models",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    } else if (engine == "supertonic" && downloadNow) {
                        val model = selectedModel
                        if (model == null) {
                            Toast.makeText(
                                context,
                                "Select a Supertonic model to download.",
                                Toast.LENGTH_LONG
                            ).show()
                            return@BrutalButton
                        }
                        if (model.isDownloaded) {
                            SettingsManager.setInitComplete(context, true)
                            onComplete()
                            return@BrutalButton
                        }
                        DebugLogger.log("InitScreen: Starting Supertonic download for ${model.name}")
                        isDownloading = true
                        downloadTargetId = model.id
                        modelDownloader.downloadModel(model)
                    } else {
                        SettingsManager.setInitComplete(context, true)
                        onComplete()
                    }
                },
                enabled = !isDownloading
            ) {
                BrutalButtonText(if (isDownloading) "Downloading..." else "Continue")
            }
        }
    }
}

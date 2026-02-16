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
import com.mewmix.nabu.kokoro.ManifestProvider
import com.mewmix.nabu.data.ModelDownloader
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.ModelType
import com.mewmix.nabu.data.Model
import com.mewmix.nabu.data.TtsModelValidator
import com.mewmix.nabu.data.UserPreferencesRepository
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.BrutalButtonText
import com.mewmix.nabu.ui.brutalist.Brutal
import com.mewmix.nabu.ui.brutalist.PanelBox
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
    var engineExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var kokoroDownloading by remember { mutableStateOf(false) }
    var kokoroProgress by remember { mutableStateOf<Downloader.DownloadProgress?>(null) }
    var kokoroDownloaded by remember { mutableStateOf(false) }
    var downloadTargetId by remember { mutableStateOf<String?>(null) }

    val modelManager = remember { ModelManager(context) }
    val modelDownloader = remember { ModelDownloader(context, userPreferencesRepository) }
    val progressMap by modelDownloader.progress.collectAsState()
    val detailedProgressMap by modelDownloader.detailedProgress.collectAsState()
    val ttsModels = modelManager.models.filter { it.type == ModelType.TTS }
    val supertonicModels = ttsModels.filter { it.id.startsWith("supertonic") }
    var selectedModel by remember { mutableStateOf<Model?>(supertonicModels.firstOrNull()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            kokoroDownloaded = Downloader.modelsAvailable(context.applicationContext, ManifestProvider.kokoroV1())
        }
    }
    LaunchedEffect(progressMap) {
        val targetId = downloadTargetId
        if (targetId != null && !progressMap.containsKey(targetId)) {
            val completedModel = modelManager.models.firstOrNull { it.id == targetId }
            val completed = completedModel?.isDownloaded == true
            if (completed) {
                DebugLogger.log("InitScreen: TTS download complete for ${completedModel?.name ?: targetId}")
                downloadTargetId = null
            } else {
                DebugLogger.log("InitScreen: TTS download incomplete for ${completedModel?.name ?: targetId}")
            }
        }
    }
    val isDownloading = kokoroDownloading || progressMap.isNotEmpty()

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
                    listOf("kokoro", "supertonic", "soprano").forEach { option ->
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

            if (engine == "supertonic") {
                if (supertonicModels.isNotEmpty()) {
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
                            supertonicModels.forEach { model ->
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

            Text(
                text = "Downloads",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    Text(
                        text = "Kokoro (Default)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "The default text-to-speech engine.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (kokoroDownloading && kokoroProgress != null) {
                        kokoroProgress?.let { current ->
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
                    } else {
                        val kokoroButtonLabel = if (kokoroDownloaded) "Downloaded" else "Download"
                        BrutalButton(
                            onClick = {
                                if (!kokoroDownloaded) {
                                    kokoroDownloading = true
                                    kokoroProgress = null
                                    scope.launch(Dispatchers.IO) {
                                        DebugLogger.log("InitScreen: Starting Kokoro download")
                                        val result = OnnxRuntimeManager.initialize(
                                            context.applicationContext,
                                            allowDownload = true,
                                            onProgress = { update -> scope.launch { kokoroProgress = update } }
                                        )
                                        withContext(Dispatchers.Main) {
                                            kokoroDownloading = false
                                            result.onSuccess {
                                                DebugLogger.log("InitScreen: Kokoro download complete")
                                                kokoroDownloaded = true
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
                                }
                            },
                            enabled = !kokoroDownloaded && !isDownloading
                        ) {
                            BrutalButtonText(kokoroButtonLabel)
                        }
                    }
                }

                ttsModels.forEach { model ->
                    Column {
                        Text(
                            text = model.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = model.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val modelProgress = progressMap[model.id]
                        val modelDetail = detailedProgressMap[model.id]
                        if (modelProgress != null) {
                            LinearProgressIndicator(
                                progress = { modelProgress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "${(modelProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = Brutal.textBright
                            )
                            modelDetail?.let { detail ->
                                val bytesLabel = if (detail.totalBytes > 0L) {
                                    "${formatBytes(detail.downloadedBytes)} / ${formatBytes(detail.totalBytes)}"
                                } else {
                                    formatBytes(detail.downloadedBytes)
                                }
                                Text(
                                    "${detail.currentFile}: $bytesLabel",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Brutal.textBright
                                )
                            }
                        } else {
                            val label = when {
                                model.isDownloaded -> "Downloaded"
                                model.hasPartial -> "Resume"
                                else -> "Download"
                            }
                            BrutalButton(
                                onClick = {
                                    if (!model.isDownloaded) {
                                        DebugLogger.log("InitScreen: Starting TTS model download for ${model.name}")
                                        downloadTargetId = model.id
                                        modelDownloader.downloadModel(model)
                                    }
                                },
                                enabled = !model.isDownloaded && !isDownloading
                            ) {
                                BrutalButtonText(label)
                            }
                        }
                    }
                }
            }

            BrutalButton(
                onClick = {
                    if (engine == "supertonic") {
                        val selected = selectedModel
                        if (selected == null || !selected.isDownloaded) {
                            Toast.makeText(
                                context,
                                "Select and download a Supertonic model before continuing.",
                                Toast.LENGTH_LONG
                            ).show()
                            return@BrutalButton
                        }
                    }
                    if (engine == "soprano") {
                        val modelId = "soprano-80m-onnx"
                        val modelDir = java.io.File(context.filesDir, "models/$modelId")
                        val partialDir = java.io.File(context.filesDir, "models/${modelId}_partial")
                        val missing = TtsModelValidator.missingFiles(modelId, modelDir, partialDir)
                        if (missing.isNotEmpty()) {
                            Toast.makeText(
                                context,
                                "Soprano download incomplete. Missing: ${missing.joinToString()}",
                                Toast.LENGTH_LONG
                            ).show()
                            return@BrutalButton
                        }
                    }
                    SettingsManager.setTtsEngine(context, engine)
                    if (engine == "supertonic") {
                        SettingsManager.setSupertonicModelId(context, selectedModel?.id)
                    }
                    SettingsManager.setInitComplete(context, true)
                    onComplete()
                },
                enabled = !isDownloading
            ) {
                BrutalButtonText("Continue")
            }
        }
    }
}

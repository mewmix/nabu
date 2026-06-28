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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mewmix.nabu.utils.SettingsManager
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
import kotlinx.coroutines.withContext
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InitScreen(
    userPreferencesRepository: UserPreferencesRepository,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    var engine by remember { mutableStateOf(SettingsManager.getTtsEngine(context)) }
    var engineExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var kokoroDownloading by remember { mutableStateOf(false) }
    var kokoroDownloaded by remember { mutableStateOf(false) }
    var downloadTargetId by remember { mutableStateOf<String?>(null) }

    val modelManager = remember { ModelManager(context) }
    val modelDownloader = remember { ModelDownloader(context, userPreferencesRepository) }
    val progressMap by modelDownloader.progress.collectAsState()
    val detailedProgressMap by modelDownloader.detailedProgress.collectAsState()
    val kokoroProgress = progressMap[ModelDownloader.KOKORO_MODEL_ID]
    val kokoroDetail = detailedProgressMap[ModelDownloader.KOKORO_MODEL_ID]
    val ttsModels = modelManager.models.filter { it.type == ModelType.TTS }
    val supertonicModels = ttsModels.filter { it.id.startsWith("supertonic") }
    var selectedModel by remember { mutableStateOf<Model?>(supertonicModels.firstOrNull()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            kokoroDownloaded = Downloader.modelsAvailable(context.applicationContext, ManifestProvider.kokoroV1())
        }
    }
    LaunchedEffect(progressMap) {
        if (kokoroDownloading && !progressMap.containsKey(ModelDownloader.KOKORO_MODEL_ID)) {
            val available = withContext(Dispatchers.IO) {
                Downloader.modelsAvailable(context.applicationContext, ManifestProvider.kokoroV1())
            }
            kokoroDownloaded = available
            kokoroDownloading = false
            if (!available) {
                Toast.makeText(context, "Failed to download Kokoro models", Toast.LENGTH_LONG).show()
            }
        }

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
                    if (kokoroProgress != null) {
                        LinearProgressIndicator(
                            progress = { kokoroProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        kokoroDetail?.let { detail ->
                            val bytesLabel = if (detail.totalBytes > 0L) {
                                "${formatBytes(detail.downloadedBytes)} / ${formatBytes(detail.totalBytes)}"
                            } else {
                                formatBytes(detail.downloadedBytes)
                            }
                            Text(
                                "${detail.currentFile}: $bytesLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        val kokoroButtonLabel = if (kokoroDownloaded) "Downloaded" else "Download"
                        BrutalButton(
                            onClick = {
                                if (!kokoroDownloaded) {
                                    kokoroDownloading = true
                                    DebugLogger.log("InitScreen: Starting Kokoro download")
                                    modelDownloader.downloadKokoroDefault()
                                }
                            },
                            enabled = !kokoroDownloaded 
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
                                color = MaterialTheme.colorScheme.onSurface
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
                                    color = MaterialTheme.colorScheme.onSurface
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
                                enabled = !model.isDownloaded 
                            ) {
                                BrutalButtonText(label)
                            }
                        }
                    }
                }
            }

            BrutalButton(
                onClick = {
                    SettingsManager.setTtsEngine(context, engine)
                    if (engine == "supertonic") {
                        SettingsManager.setSupertonicModelId(context, selectedModel?.id)
                    }
                    SettingsManager.setInitComplete(context, true)
                    onComplete()
                }
            ) {
                BrutalButtonText("Continue")
            }
        }
    }
}

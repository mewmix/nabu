package com.mewmix.nabu.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import com.mewmix.nabu.ui.components.ProgressDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.mewmix.nabu.data.importableLlmMetadata
import com.mewmix.nabu.data.Model
import com.mewmix.nabu.data.ModelDownloader
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.UserPreferencesRepository
import com.mewmix.nabu.utils.DebugLogger
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.PanelBox
import com.mewmix.nabu.utils.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    userPreferencesRepository: UserPreferencesRepository,
    onModelArtifactsChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val modelManager = remember { ModelManager(context) }
    val modelDownloader = remember { ModelDownloader(context, userPreferencesRepository) }
    val models = modelManager.models
    val progressMap by modelDownloader.progress.collectAsState()
    val detailedProgressMap by modelDownloader.detailedProgress.collectAsState()
    val scope = rememberCoroutineScope()

    var showDialog by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf<Model?>(null) }
    var hfToken by remember { mutableStateOf("") }
    var hadActiveDownloads by remember { mutableStateOf(false) }

    // Kokoro specific state
    var kokoroDownloaded by remember { mutableStateOf(false) }
    var kokoroDownloading by remember { mutableStateOf(false) }
    val kokoroProgress = progressMap[ModelDownloader.KOKORO_MODEL_ID]
    val kokoroDetail = detailedProgressMap[ModelDownloader.KOKORO_MODEL_ID]

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            kokoroDownloaded = com.mewmix.nabu.kokoro.Downloader.modelsAvailable(
                context.applicationContext,
                com.mewmix.nabu.kokoro.ManifestProvider.kokoroV1()
            )
        }
    }

    LaunchedEffect(progressMap) {
        if (kokoroDownloading && !progressMap.containsKey(ModelDownloader.KOKORO_MODEL_ID)) {
            val available = withContext(Dispatchers.IO) {
                com.mewmix.nabu.kokoro.Downloader.modelsAvailable(
                    context.applicationContext,
                    com.mewmix.nabu.kokoro.ManifestProvider.kokoroV1()
                )
            }
            kokoroDownloaded = available
            kokoroDownloading = false
        }

        val hasActiveDownloads = progressMap.isNotEmpty()
        if (hadActiveDownloads && !hasActiveDownloads) {
            onModelArtifactsChanged()
        }
        hadActiveDownloads = hasActiveDownloads
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val document = DocumentFile.fromSingleUri(context, it)
            val name = document?.name ?: return@let
            val importable = importableLlmMetadata(name)
            if (importable != null) {
                val (id, backend) = importable
                val modelDir = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }
                context.contentResolver.openInputStream(it)?.use { inputStream ->
                    File(modelDir, name).outputStream().use { output ->
                        inputStream.copyTo(output)
                    }
                }
                val model = Model(
                    id = id,
                    name = id,
                    description = "Local model ($backend)",
                    repo = "",
                    downloadUrl = "",
                    gated = false,
                    initialIsDownloaded = true,
                    initialBackend = backend
                )
                modelManager.addLocalModel(model)
                DebugLogger.log("ModelsScreen: Imported $name")
                onModelArtifactsChanged()
            } else {
                DebugLogger.log("ModelsScreen: Selected file is not a .task, .litertlm, or .gguf file")
            }
        }
    }

    LaunchedEffect(Unit) {
        userPreferencesRepository.hfToken.collect { token ->
            if (token != null) {
                hfToken = token
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Hugging Face Token") },
            text = {
                Column {
                    Text("This model is gated. Please enter your Hugging Face token and ensure you have accepted the EULA to download.")
                    TextField(
                        value = hfToken,
                        onValueChange = {
                            hfToken = it
                            scope.launch {
                                userPreferencesRepository.saveHfToken(it)
                            }
                        },
                        label = { Text("Token") }
                    )
                }
            },
            confirmButton = {
                BrutalButton(
                    onClick = {
                        selectedModel?.let {
                            DebugLogger.log("ModelsScreen: Starting TTS model download for ${it.name}")
                            modelDownloader.downloadModel(it)
                        }
                        showDialog = false
                    }
                ) {
                    Text("Download")
                }
            },
            dismissButton = {
                BrutalButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PanelBox(
            title = "Models",
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    BrutalButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Text("Import Local Model")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Kokoro entry
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), RoundedCornerShape(22.dp))
                            .padding(14.dp)
                    ) {
                        Text(text = "Kokoro (Default)", style = MaterialTheme.typography.titleMedium)
                        Text(text = "The default text-to-speech engine.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(8.dp))

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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (kokoroDownloaded) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = "Downloaded",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    BrutalButton(onClick = {
                                         // Deleting Kokoro logic if needed, but for now we just show downloaded
                                    }, enabled = false) { // Disabled delete for safety/simplicity as per request just to add it
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete model")
                                    }
                                } else {
                                    BrutalButton(onClick = {
                                        kokoroDownloading = true
                                        DebugLogger.log("ModelsScreen: Starting Kokoro download")
                                        modelDownloader.downloadKokoroDefault()
                                    }, enabled = !kokoroDownloading) {
                                        Icon(
                                            Icons.Filled.CloudDownload,
                                            contentDescription = "Download model",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                items(models) { model ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), RoundedCornerShape(22.dp))
                            .padding(14.dp)
                    ) {
                        Text(text = model.name, style = MaterialTheme.typography.titleMedium)
                        Text(text = model.description, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        val progress = progressMap[model.id]
                        val detail = detailedProgressMap[model.id]
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            detail?.let {
                                val bytesLabel = if (it.totalBytes > 0L) {
                                    "${formatBytes(it.downloadedBytes)} / ${formatBytes(it.totalBytes)}"
                                } else {
                                    formatBytes(it.downloadedBytes)
                                }
                                Text(
                                    text = "${it.currentFile}: $bytesLabel",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (model.isDownloaded) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = "Downloaded",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    BrutalButton(onClick = {
                                        DebugLogger.log("ModelsScreen: Deleting ${model.name}")
                                        modelManager.deleteModel(model)
                                        onModelArtifactsChanged()
                                    }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete model")
                                    }
                                } else {
                                    BrutalButton(onClick = {
                                        if (model.gated) {
                                            selectedModel = model
                                            showDialog = true
                                            DebugLogger.log("ModelsScreen: Prompting token for ${model.name}")
                                        } else {
                                            DebugLogger.log("ModelsScreen: Starting TTS model download for ${model.name}")
                                            modelDownloader.downloadModel(model)
                                        }
                                    }) {
                                        Icon(
                                            Icons.Filled.CloudDownload,
                                            contentDescription = if (model.hasPartial) "Resume download" else "Download model",
                                        )
                                    }
                                    if (model.hasPartial) {
                                        BrutalButton(onClick = {
                                            DebugLogger.log("ModelsScreen: Deleting partial ${model.name}")
                                            modelManager.deleteModel(model)
                                            onModelArtifactsChanged()
                                        }) {
                                            Icon(Icons.Filled.Delete, contentDescription = "Delete partial model")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (progressMap.isNotEmpty()) {
            val entry = progressMap.entries.first()
            val downloading = models.find { it.id == entry.key }
            val progress = entry.value
            val detail = detailedProgressMap[entry.key]
            val bytesLabel = detail?.let {
                if (it.totalBytes > 0L) {
                    "${formatBytes(it.downloadedBytes)} / ${formatBytes(it.totalBytes)}"
                } else {
                    formatBytes(it.downloadedBytes)
                }
            }
            ProgressDialog(
                message = buildString {
                    append("Downloading ${downloading?.name ?: "model"} ${(progress * 100).toInt()}%")
                    if (!bytesLabel.isNullOrBlank()) {
                        append("\n")
                        append(detail?.currentFile ?: "file")
                        append(": ")
                        append(bytesLabel)
                    }
                },
                progress = progress,
            )
        }
    }
}

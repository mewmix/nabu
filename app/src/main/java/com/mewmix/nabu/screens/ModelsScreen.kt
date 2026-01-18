package com.mewmix.nabu.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.mewmix.nabu.data.Model
import com.mewmix.nabu.data.ModelDownloader
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.UserPreferencesRepository
import com.mewmix.nabu.utils.DebugLogger
import java.io.File
import kotlinx.coroutines.launch
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.PanelBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(userPreferencesRepository: UserPreferencesRepository) {
    val context = LocalContext.current
    val modelManager = remember { ModelManager(context) }
    val modelDownloader = remember { ModelDownloader(context, userPreferencesRepository) }
    val models = modelManager.models
    val progressMap by modelDownloader.progress.collectAsState()
    val scope = rememberCoroutineScope()

    var showDialog by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf<Model?>(null) }
    var hfToken by remember { mutableStateOf("") }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val document = DocumentFile.fromSingleUri(context, it)
            val name = document?.name ?: return@let
            if (name.endsWith(".task")) {
                val modelDir = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }
                context.contentResolver.openInputStream(it)?.use { inputStream ->
                    File(modelDir, name).outputStream().use { output ->
                        inputStream.copyTo(output)
                    }
                }
                val id = name.removeSuffix(".task")
                val model = Model(
                    id = id,
                    name = id,
                    description = "Local model",
                    repo = "",
                    downloadUrl = "",
                    gated = false,
                    isDownloaded = true
                )
                modelManager.addLocalModel(model)
                DebugLogger.log("ModelsScreen: Imported $name")
            } else {
                DebugLogger.log("ModelsScreen: Selected file is not a .task file")
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
                            DebugLogger.log("ModelsScreen: Downloading ${it.name}")
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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    BrutalButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Text("Import Local Model")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                items(models) { model ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(text = model.name, style = MaterialTheme.typography.titleMedium)
                        Text(text = model.description, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        val progress = progressMap[model.id]
                        if (progress != null) {
                            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
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
                                            DebugLogger.log("ModelsScreen: Downloading ${model.name}")
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
            ProgressDialog(
                message = "Downloading ${downloading?.name ?: "model"} ${(progress * 100).toInt()}%",
                progress = progress,
            )
        }
    }
}

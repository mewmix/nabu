package com.example.kokoro82m.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
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
import com.example.kokoro82m.data.Model
import com.example.kokoro82m.data.ModelDownloader
import com.example.kokoro82m.data.ModelManager
import com.example.kokoro82m.data.UserPreferencesRepository
import kotlinx.coroutines.launch

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
                    Text("This model is gated. Please enter your Hugging Face token to download.")
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
                Button(
                    onClick = {
                        selectedModel?.let {
                            modelDownloader.downloadModel(it)
                        }
                        showDialog = false
                    }
                ) {
                    Text("Download")
                }
            },
            dismissButton = {
                Button(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(models) { model ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = model.name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    Text(text = model.description, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                }
                val progress = progressMap[model.id]
                if (model.isDownloaded) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Downloaded")
                        Button(onClick = { modelManager.deleteModel(model) }) { Text("Delete") }
                    }
                } else if (progress != null) {
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (model.gated) {
                                selectedModel = model
                                showDialog = true
                            } else {
                                modelDownloader.downloadModel(model)
                            }
                        }) {
                            Text(if (model.hasPartial) "Resume" else "Download")
                        }
                        if (model.hasPartial) {
                            Button(onClick = { modelManager.deleteModel(model) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

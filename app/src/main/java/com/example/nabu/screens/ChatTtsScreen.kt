package com.example.nabu.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.kokoro.chat.MessageBubble
import com.example.nabu.utils.PlayerState
import com.example.nabu.viewmodel.ChatTtsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTtsScreen(
    viewModel: ChatTtsViewModel,
    modelName: String,
    onBackPressed: () -> Unit
) {
    val chatMessages by viewModel.chatMessages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isSynthesizing by viewModel.isSynthesizing.collectAsState()
    val playerState by viewModel.playerState.collectAsState()

    val selectedStyles by viewModel.selectedStyles.collectAsState()
    val weights by viewModel.weights.collectAsState()
    val interpolationMode by viewModel.interpolationMode.collectAsState()
    val speed by viewModel.speed.collectAsState()

    var message by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showMixerSettings by remember { mutableStateOf(false) }

    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$modelName Chat & TTS") },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {

            // Mixer Settings in a Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showMixerSettings = !showMixerSettings },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Voice Mixer Settings",
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            imageVector = if (showMixerSettings) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (showMixerSettings) "Collapse" else "Expand",
                        )
                    }
                    if (showMixerSettings) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            StyleSelector(
                                styleNames = viewModel.styleLoader.names,
                                selectedStyles = selectedStyles,
                                onAddStyle = viewModel::addStyle,
                                onRemoveStyle = viewModel::removeStyle,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            WeightSliders(
                                selectedStyles = selectedStyles,
                                weights = weights,
                                onWeightChanged = viewModel::updateWeight,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            InterpolationModeSelector(
                                currentMode = interpolationMode,
                                onModeSelected = viewModel::updateInterpolationMode,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Speed: ${"%.2f".format(speed)}")
                            Slider(
                                value = speed,
                                onValueChange = { viewModel.updateSpeed(it) },
                                valueRange = 0.5f..2.0f,
                                steps = 15,
                            )
                        }
                    }
                }
            }

            // Status Indicators
            if (isLoading || isSynthesizing || playerState == PlayerState.PLAYING) {
                val statusText = when {
                    isLoading -> "Assistant is thinking..."
                    isSynthesizing -> "Synthesizing audio..."
                    playerState == PlayerState.PLAYING -> "Speaking..."
                    else -> ""
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = statusText, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.width(8.dp))
                    if(isLoading || isSynthesizing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            // Chat Messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            ) {
                items(chatMessages) { chatMessage ->
                    MessageBubble(chatMessage)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            // Message Input
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Message") },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (message.isNotBlank()) {
                            viewModel.sendMessage(message)
                            message = ""
                        }
                    },
                    enabled = message.isNotBlank() && !isLoading && !isSynthesizing && playerState == PlayerState.IDLE
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
}

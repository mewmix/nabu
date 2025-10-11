package com.example.nabu.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import com.example.nabu.ui.components.WaveformVisualizer
import com.example.nabu.utils.PcmTap
import com.example.nabu.utils.PlayerState
import com.example.nabu.viewmodel.ChatViewModel
import com.mewmix.nabu.ui.brutalist.Brutal
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.BrutalSection
import com.mewmix.nabu.ui.brutalist.BrutalSlider
import com.mewmix.nabu.ui.brutalist.PanelBox

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    initialMessage: String = "",
) {
    val chatMessages by viewModel.chatMessages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isSynthesizing by viewModel.isSynthesizing.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val ttsEnabled by viewModel.ttsEnabled.collectAsState()
    val conversationSummaries by viewModel.conversationSummaries.collectAsState()
    val activeConversationId by viewModel.activeConversationId.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val activeModel by viewModel.activeModel.collectAsState()

    LaunchedEffect(playerState) {
        PcmTap.enabled = playerState == PlayerState.PLAYING
    }

    val selectedStyles by viewModel.selectedStyles.collectAsState()
    val weights by viewModel.weights.collectAsState()
    val interpolationMode by viewModel.interpolationMode.collectAsState()
    val speed by viewModel.speed.collectAsState()

    var message by remember { mutableStateOf(initialMessage) }
    val listState = rememberLazyListState()
    var showMixerSettings by remember { mutableStateOf(false) }
    var showConversationSettings by remember { mutableStateOf(true) }
    var conversationMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Long?>(null) }
    val activeConversation = conversationSummaries.firstOrNull { it.id == activeConversationId }

    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    LaunchedEffect(conversationSummaries) {
        renameTarget?.let { target ->
            if (conversationSummaries.none { it.id == target }) {
                renameTarget = null
            }
        }
        deleteTarget?.let { target ->
            if (conversationSummaries.none { it.id == target }) {
                deleteTarget = null
            }
        }
    }

    Scaffold { paddingValues ->
        PanelBox(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {

            BrutalSection(
                title = "Conversation Settings",
                expanded = showConversationSettings,
                onToggle = { showConversationSettings = !showConversationSettings },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Column {
                    Text(
                        text = "Conversation",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brutal.textBright
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        BrutalButton(
                            onClick = { conversationMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = conversationSummaries.isNotEmpty()
                        ) {
                            Text(
                                text = activeConversation?.title ?: "No conversations",
                                color = Brutal.textBright
                            )
                        }
                        DropdownMenu(
                            expanded = conversationMenuExpanded,
                            onDismissRequest = { conversationMenuExpanded = false }
                        ) {
                            if (conversationSummaries.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No conversations available") },
                                    onClick = {},
                                    enabled = false
                                )
                            } else {
                                conversationSummaries.forEach { summary ->
                                    DropdownMenuItem(
                                        text = { Text(summary.title) },
                                        onClick = {
                                            conversationMenuExpanded = false
                                            viewModel.selectConversation(summary.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BrutalButton(onClick = { viewModel.createConversation() }) {
                            Text("New", color = Brutal.textBright)
                        }
                        BrutalButton(
                            onClick = {
                                if (activeConversationId != null) {
                                    renameText = activeConversation?.title.orEmpty()
                                    renameTarget = activeConversationId
                                }
                            },
                            enabled = activeConversationId != null
                        ) {
                            Text("Rename", color = Brutal.textBright)
                        }
                        BrutalButton(
                            onClick = {
                                if (activeConversationId != null) {
                                    deleteTarget = activeConversationId
                                }
                            },
                            enabled = activeConversationId != null
                        ) {
                            Text("Delete", color = Brutal.red)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val voiceColor = if (ttsEnabled) Brutal.textBright else Brutal.textDim
                        BrutalButton(
                            onClick = { viewModel.toggleTtsEnabled() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (ttsEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                                contentDescription = if (ttsEnabled) "Disable voice playback" else "Enable voice playback",
                                tint = voiceColor
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (ttsEnabled) "Voice On" else "Voice Off",
                                color = voiceColor
                            )
                        }
                        BrutalButton(
                            onClick = { viewModel.stopPlayback() },
                            enabled = playerState == PlayerState.PLAYING || isSynthesizing
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = "Stop voice playback",
                                tint = Brutal.red
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Stop", color = Brutal.red)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Model",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brutal.textBright
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        BrutalButton(
                            onClick = { modelMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = availableModels.isNotEmpty()
                        ) {
                            Text(
                                text = activeModel?.name ?: "Select model",
                                color = Brutal.textBright
                            )
                        }
                        DropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false }
                        ) {
                            if (availableModels.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No models available") },
                                    onClick = {},
                                    enabled = false
                                )
                            } else {
                                availableModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = { Text(model.name) },
                                        onClick = {
                                            modelMenuExpanded = false
                                            viewModel.selectModel(model.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            BrutalSection(
                title = "Voice Mixer Settings",
                expanded = showMixerSettings,
                onToggle = { showMixerSettings = !showMixerSettings },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (viewModel.supportsStyleMixing) {
                        StyleSelector(
                            styleNames = viewModel.voiceOptions,
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
                    } else {
                        var voiceMenuExpanded by remember { mutableStateOf(false) }
                        val currentVoice = selectedStyles.firstOrNull().orEmpty()
                        Text("VOICE", color = Brutal.textBright)
                        Spacer(modifier = Modifier.height(4.dp))
                        BrutalButton(
                            onClick = { voiceMenuExpanded = !voiceMenuExpanded },
                            enabled = viewModel.voiceOptions.isNotEmpty()
                        ) {
                            Text(
                                text = if (currentVoice.isEmpty()) "SELECT VOICE" else currentVoice.uppercase(),
                                color = Brutal.textBright
                            )
                        }
                        DropdownMenu(
                            expanded = voiceMenuExpanded,
                            onDismissRequest = { voiceMenuExpanded = false }
                        ) {
                            if (viewModel.voiceOptions.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No voices available") },
                                    onClick = {},
                                    enabled = false
                                )
                            } else {
                                viewModel.voiceOptions.forEach { voice ->
                                    DropdownMenuItem(
                                        text = { Text(voice.uppercase()) },
                                        onClick = {
                                            voiceMenuExpanded = false
                                            viewModel.addStyle(voice)
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Speed: ${"%.2f".format(speed)}", color = Brutal.textBright)
                    BrutalSlider(
                        value = speed,
                        onValueChange = { viewModel.updateSpeed(it) },
                        range = 0.5f..2.0f
                    )
                }
            }

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
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = Brutal.textBright
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isLoading || isSynthesizing) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Brutal.amber,
                            trackColor = Brutal.panelStroke
                        )
                    }
                }
            }

            WaveformVisualizer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                visible = playerState == PlayerState.PLAYING
            )

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
                    CircularProgressIndicator(color = Brutal.amber)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, Brutal.hairline, RoundedCornerShape(24.dp)),
                    placeholder = { Text("Message", color = Brutal.textDim) },
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Brutal.panelBg,
                        unfocusedContainerColor = Brutal.panelBg,
                        focusedIndicatorColor = Brutal.hairline,
                        unfocusedIndicatorColor = Brutal.hairline,
                        cursorColor = Brutal.amber,
                        focusedTextColor = Brutal.textBright,
                        unfocusedTextColor = Brutal.textBright,
                        focusedPlaceholderColor = Brutal.textDim,
                        unfocusedPlaceholderColor = Brutal.textDim
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.width(8.dp))
                BrutalButton(
                    onClick = {
                        if (message.isNotBlank()) {
                            viewModel.sendMessage(message)
                            message = ""
                        }
                    },
                    enabled = message.isNotBlank() && !isLoading && !isSynthesizing && playerState == PlayerState.IDLE
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send", tint = Brutal.textBright)
                }
            }
        }
    }

    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Conversation") },
            text = {
                TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true
                )
            },
            confirmButton = {
                BrutalButton(
                    onClick = {
                        renameTarget?.let { viewModel.renameConversation(it, renameText) }
                        renameTarget = null
                    },
                    enabled = renameText.isNotBlank()
                ) {
                    Text("Save", color = Brutal.textBright)
                }
            },
            dismissButton = {
                BrutalButton(onClick = { renameTarget = null }) {
                    Text("Cancel", color = Brutal.textBright)
                }
            }
        )
    }

    if (deleteTarget != null) {
        val targetTitle = conversationSummaries.firstOrNull { it.id == deleteTarget }?.title ?: "this conversation"
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Conversation") },
            text = { Text("Are you sure you want to delete \"$targetTitle\"? This action cannot be undone.") },
            confirmButton = {
                BrutalButton(onClick = {
                    deleteTarget?.let { viewModel.deleteConversation(it) }
                    deleteTarget = null
                }) {
                    Text("Delete", color = Brutal.red)
                }
            },
            dismissButton = {
                BrutalButton(onClick = { deleteTarget = null }) {
                    Text("Cancel", color = Brutal.textBright)
                }
            }
        )
    }
}

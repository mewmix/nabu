package com.mewmix.nabu.screens

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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.mewmix.nabu.chat.LlmImageInput
import com.mewmix.nabu.chat.MessageBubble
import com.mewmix.nabu.tools.Tool
import com.mewmix.nabu.tools.ToolRegistry
import com.mewmix.nabu.ui.components.WaveformVisualizer
import com.mewmix.nabu.utils.PcmTap
import com.mewmix.nabu.utils.PlayerState
import com.mewmix.nabu.viewmodel.ChatContextMode
import com.mewmix.nabu.viewmodel.ChatViewModel
import com.mewmix.nabu.ui.brutalist.Brutal
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.BrutalSection
import com.mewmix.nabu.ui.brutalist.BrutalSlider
import com.mewmix.nabu.ui.brutalist.PanelBox
import com.mewmix.nabu.ui.components.RuntimeStatusLine

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    initialMessage: String = "",
) {
    val context = LocalContext.current
    val chatMessages by viewModel.chatMessages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isSynthesizing by viewModel.isSynthesizing.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val ttsEnabled by viewModel.ttsEnabled.collectAsState()
    val conversationSummaries by viewModel.conversationSummaries.collectAsState()
    val activeConversationId by viewModel.activeConversationId.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val activeModel by viewModel.activeModel.collectAsState()
    val chatContextMode by viewModel.chatContextMode.collectAsState()
    val availableTools by ToolRegistry.tools.collectAsState()
    val pendingImage by viewModel.pendingImage.collectAsState()

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val bitmap = if (android.os.Build.VERSION.SDK_INT < 28) {
                @Suppress("DEPRECATION")
                android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, it)
            } else {
                val source = android.graphics.ImageDecoder.createSource(context.contentResolver, it)
                android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                }
            }
            viewModel.setPendingImage(LlmImageInput(bitmap))
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshStyles()
    }

    LaunchedEffect(playerState) {
        PcmTap.enabled = playerState == PlayerState.PLAYING
    }

    val selectedStyles by viewModel.selectedStyles.collectAsState()
    val weights by viewModel.weights.collectAsState()
    val interpolationMode by viewModel.interpolationMode.collectAsState()
    val speed by viewModel.speed.collectAsState()
    val voiceFavorites by viewModel.voiceFavorites.collectAsState()

    var message by remember { mutableStateOf(initialMessage) }
    val listState = rememberLazyListState()
    var showMixerSettings by remember { mutableStateOf(false) }
    var showConversationSettings by remember { mutableStateOf(true) }
    var conversationMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Long?>(null) }
    var showToolPicker by remember { mutableStateOf(false) }
    var toolPrefillMode by remember { mutableStateOf(chatContextMode) }
    val activeConversation = conversationSummaries.firstOrNull { it.id == activeConversationId }

    LaunchedEffect(chatContextMode) {
        toolPrefillMode = chatContextMode
    }

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
            RuntimeStatusLine(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

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
                        BrutalButton(onClick = { showToolPicker = true }) {
                            Text("Tools", color = Brutal.textBright)
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
                                        text = {
                                            Column {
                                                Text(model.name)
                                                if (model.description.isNotBlank()) {
                                                    Text(
                                                        text = model.description,
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                }
                                            }
                                        },
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
                title = "Model Settings",
                expanded = showMixerSettings, // Reusing mixer settings state for now, or rename it
                onToggle = { showMixerSettings = !showMixerSettings },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                val systemPrompt by viewModel.systemPrompt.collectAsState()
                val tokenUsage by viewModel.tokenUsage.collectAsState()
                
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = "System Prompt",
                        style = MaterialTheme.typography.labelMedium,
                        color = Brutal.textBright
                    )
                    TextField(
                        value = systemPrompt,
                        onValueChange = { viewModel.updateSystemPrompt(it) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Brutal.panelBg,
                            unfocusedContainerColor = Brutal.panelBg,
                            focusedTextColor = Brutal.textBright,
                            unfocusedTextColor = Brutal.textBright
                        ),
                        minLines = 2,
                        maxLines = 5
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Context Mode",
                        style = MaterialTheme.typography.labelMedium,
                        color = Brutal.textBright
                    )
                    Text(
                        text = if (chatContextMode == ChatContextMode.LONG_CONTEXT) {
                            "Long Context keeps history and uses compaction when the window gets tight."
                        } else {
                            "Single Turn only sends the latest user message."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Brutal.textDim
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BrutalButton(
                            onClick = { viewModel.updateChatContextMode(ChatContextMode.LONG_CONTEXT) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (chatContextMode == ChatContextMode.LONG_CONTEXT) {
                                    "Long Context On"
                                } else {
                                    "Long Context"
                                },
                                color = Brutal.textBright
                            )
                        }
                        BrutalButton(
                            onClick = { viewModel.updateChatContextMode(ChatContextMode.SINGLE_TURN) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (chatContextMode == ChatContextMode.SINGLE_TURN) {
                                    "Single Turn On"
                                } else {
                                    "Single Turn"
                                },
                                color = Brutal.textBright
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "Context Usage",
                        style = MaterialTheme.typography.labelMedium,
                        color = Brutal.textBright
                    )
                    val (current, max) = tokenUsage
                    val usagePercent = if (max > 0) current.toFloat() / max else 0f
                    LinearProgressIndicator(
                        progress = { usagePercent },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = if (usagePercent > 0.9f) Brutal.red else Brutal.amber,
                        trackColor = Brutal.panelStroke,
                    )
                    Text(
                        text = "$current / $max tokens",
                        style = MaterialTheme.typography.bodySmall,
                        color = Brutal.textDim,
                        modifier = Modifier.align(Alignment.End)
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Voice Settings", style = MaterialTheme.typography.labelLarge, color = Brutal.textBright)

                    VoiceFavoritesSection(
                        favorites = voiceFavorites,
                        onSaveFavorite = viewModel::saveCurrentFavorite,
                        onApplyFavorite = viewModel::applyFavorite,
                        onDeleteFavorite = viewModel::deleteFavorite,
                    )
                    
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

            if (pendingImage != null) {
                Box(
                    modifier = Modifier
                        .padding(8.dp)
                        .size(100.dp)
                        .border(1.dp, Brutal.amber, RoundedCornerShape(8.dp))
                ) {
                    Image(
                        bitmap = pendingImage!!.bitmap.asImageBitmap(),
                        contentDescription = "Pending image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear image",
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .clickable { viewModel.setPendingImage(null) },
                        tint = Brutal.red
                    )
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
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = "Add image",
                            modifier = Modifier.clickable { imagePicker.launch("image/*") },
                            tint = Brutal.textDim
                        )
                    },
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
                if (isLoading) {
                    BrutalButton(
                        onClick = { viewModel.cancelGeneration() },
                        enabled = true
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Brutal.textBright)
                    }
                } else {
                    BrutalButton(
                        onClick = {
                            if (message.isNotBlank()) {
                                viewModel.sendMessage(message)
                                message = ""
                            }
                        },
                        enabled = message.isNotBlank() && !isSynthesizing && playerState == PlayerState.IDLE
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = Brutal.textBright)
                    }
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

    if (showToolPicker) {
        AlertDialog(
            onDismissRequest = { showToolPicker = false },
            title = { Text("Tool Prefill") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = "Pick a context mode, then choose a tool to prefill a direct call.",
                        color = Brutal.textDim
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BrutalButton(
                            onClick = { toolPrefillMode = ChatContextMode.SINGLE_TURN },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (toolPrefillMode == ChatContextMode.SINGLE_TURN) {
                                    "Single Turn On"
                                } else {
                                    "Single Turn"
                                },
                                color = Brutal.textBright
                            )
                        }
                        BrutalButton(
                            onClick = { toolPrefillMode = ChatContextMode.LONG_CONTEXT },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (toolPrefillMode == ChatContextMode.LONG_CONTEXT) {
                                    "Long Context On"
                                } else {
                                    "Long Context"
                                },
                                color = Brutal.textBright
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    availableTools
                        .filter { it.isAvailable }
                        .sortedBy { it.name }
                        .forEach { tool ->
                            BrutalButton(
                                onClick = {
                                    viewModel.updateChatContextMode(toolPrefillMode)
                                    message = buildToolPrefill(tool)
                                    showToolPicker = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(tool.name, color = Brutal.textBright)
                                    if (tool.description.isNotBlank()) {
                                        Text(tool.description, color = Brutal.textDim)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                }
            },
            confirmButton = {
                BrutalButton(onClick = { showToolPicker = false }) {
                    Text("Close", color = Brutal.textBright)
                }
            },
            dismissButton = {}
        )
    }
}

private fun buildToolPrefill(tool: Tool): String {
    if (tool.parameters.isEmpty()) {
        return "/tool ${tool.name}"
    }
    val args = tool.parameters.keys.joinToString(", ") { key ->
        "\"$key\":${defaultPrefillJsonValue(key)}"
    }
    return "/tool ${tool.name} {$args}"
}

private fun defaultPrefillJsonValue(key: String): String = when (key) {
    "enabled" -> "true"
    "hour" -> "7"
    "minute" -> "30"
    "seconds" -> "60"
    "level" -> "50"
    else -> "\"\""
}

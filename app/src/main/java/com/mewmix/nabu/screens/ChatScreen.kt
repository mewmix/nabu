package com.mewmix.nabu.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import android.widget.Toast
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.mewmix.nabu.chat.LlmAudioInput
import com.mewmix.nabu.chat.LlmImageInput
import com.mewmix.nabu.chat.ActionTrace
import com.mewmix.nabu.chat.MessageBubble
import com.mewmix.nabu.chat.ModelCapabilityResolver
import com.mewmix.nabu.speech.VoiceAttachmentRecorder
import com.mewmix.nabu.tools.Tool
import com.mewmix.nabu.tools.ToolRegistry
import com.mewmix.nabu.ui.components.WaveformVisualizer
import com.mewmix.nabu.utils.PcmTap
import com.mewmix.nabu.utils.PlayerState
import com.mewmix.nabu.viewmodel.ChatContextMode
import com.mewmix.nabu.viewmodel.ChatViewModel
import com.mewmix.nabu.viewmodel.OrchestrationUiState
import com.mewmix.nabu.ui.brutalist.Brutal
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.BrutalIconButton
import com.mewmix.nabu.ui.brutalist.BrutalSection
import com.mewmix.nabu.ui.brutalist.BrutalSlider
import com.mewmix.nabu.ui.brutalist.PanelBox
import com.mewmix.nabu.ui.components.RuntimeStatusLine
import com.mewmix.nabu.ui.design.LocalNabuChrome
import com.mewmix.nabu.utils.TextExtractor

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    initialMessage: String = "",
    startVoice: Boolean = false,
    onExit: () -> Unit = {},
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
    val llmRuntimeDescription by viewModel.llmRuntimeDescription.collectAsState()
    val chatContextMode by viewModel.chatContextMode.collectAsState()
    val availableTools by ToolRegistry.tools.collectAsState()
    val pendingImage by viewModel.pendingImage.collectAsState()
    val pendingAudio by viewModel.pendingAudioInput.collectAsState()
    val pendingToolApproval by viewModel.pendingToolApproval.collectAsState()
    val pendingAppSelection by viewModel.pendingAppSelection.collectAsState()
    val pendingUiActionConfirmation by viewModel.pendingUiActionConfirmation.collectAsState()
    val orchestration by viewModel.orchestration.collectAsState()
    val activeModelSupportsAudio = ModelCapabilityResolver.supportsAudioInput(context, activeModel)
    val clipboardManager = LocalClipboardManager.current
    val voiceRecorder = remember { VoiceAttachmentRecorder(context.applicationContext) }
    var isRecordingVoice by remember { mutableStateOf(false) }
    var startVoiceHandled by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(initialMessage) }
    var attachmentMenuExpanded by remember { mutableStateOf(false) }
    var actionTraceToShow by remember { mutableStateOf<ActionTrace?>(null) }

    BackHandler(onBack = onExit)

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
    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            if (!activeModelSupportsAudio) {
                Toast.makeText(context, "Select an audio-capable model before attaching audio.", Toast.LENGTH_LONG).show()
            } else {
                val bytes = context.contentResolver.openInputStream(it)?.use { stream -> stream.readBytes() }
                if (bytes != null) {
                    viewModel.setPendingAudio(
                        LlmAudioInput(
                            bytes = bytes,
                            displayName = displayNameForUri(context, it) ?: "audio"
                        )
                    )
                }
            }
        }
    }
    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            runCatching {
                val (chunks, meta) = TextExtractor.extract(context, it, chunkSize = 2400)
                val extracted = chunks.take(6).joinToString("\n\n").trim()
                message = if (extracted.isBlank()) {
                    "I opened ${meta.displayName}, but no readable text was extracted. Help me inspect or summarize what is available."
                } else {
                    "Use this document to start the conversation: ${meta.displayName}\n\n$extracted"
                }
            }.onFailure { error ->
                Toast.makeText(context, "Could not read document: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            runCatching {
                voiceRecorder.start()
                isRecordingVoice = true
            }.onFailure { error ->
                Toast.makeText(context, "Could not start recording: ${error.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "Microphone permission is required for voice messages.", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshStyles()
    }

    LaunchedEffect(playerState) {
        PcmTap.enabled = playerState == PlayerState.PLAYING
    }

    LaunchedEffect(startVoice) {
        if (startVoice && !startVoiceHandled) {
            startVoiceHandled = true
            if (!activeModelSupportsAudio) {
                Toast.makeText(context, "Select an audio-capable model before recording voice.", Toast.LENGTH_LONG).show()
            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                runCatching {
                    voiceRecorder.start()
                    isRecordingVoice = true
                }.onFailure { error ->
                    Toast.makeText(context, "Could not start recording: ${error.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    LaunchedEffect(activeModel?.id, pendingAudio) {
        if (pendingAudio != null && !activeModelSupportsAudio) {
            viewModel.setPendingAudio(null)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (voiceRecorder.isRecording) {
                voiceRecorder.cancel()
            }
        }
    }

    val selectedStyles by viewModel.selectedStyles.collectAsState()
    val weights by viewModel.weights.collectAsState()
    val interpolationMode by viewModel.interpolationMode.collectAsState()
    val speed by viewModel.speed.collectAsState()
    val voiceFavorites by viewModel.voiceFavorites.collectAsState()

    val listState = rememberLazyListState()
    var showMixerSettings by remember { mutableStateOf(false) }
    var showConversationSettings by remember { mutableStateOf(false) }
    var conversationMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Long?>(null) }
    var showToolPicker by remember { mutableStateOf(false) }
    var toolPrefillMode by remember { mutableStateOf(chatContextMode) }
    var editMessageIndex by remember { mutableStateOf<Int?>(null) }
    var editMessageText by remember { mutableStateOf("") }
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

    pendingToolApproval?.let { toolCall ->
        AlertDialog(
            onDismissRequest = { viewModel.resolveToolApproval(false) },
            title = { Text("Approve Action") },
            text = { Text("The assistant wants to execute a destructive action:\n\nTool: ${toolCall.toolName}\nArguments: ${toolCall.arguments}") },
            confirmButton = {
                BrutalButton(onClick = { viewModel.resolveToolApproval(true) }) {
                    Text("Allow")
                }
            },
            dismissButton = {
                BrutalButton(onClick = { viewModel.resolveToolApproval(false) }) {
                    Text("Deny")
                }
            }
        )
    }

    pendingAppSelection?.let { request ->
        AlertDialog(
            onDismissRequest = { viewModel.resolveAppSelection(null) },
            title = { Text("Choose App") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Multiple apps match \"${request.query}\".",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        request.candidates.forEach { candidate ->
                            BrutalButton(
                                onClick = { viewModel.resolveAppSelection(candidate.packageName) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = candidate.label,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Text(
                                        text = candidate.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                BrutalButton(onClick = { viewModel.resolveAppSelection(null) }) {
                    Text("Cancel")
                }
            }
        )
    }

    pendingUiActionConfirmation?.let { request ->
        AlertDialog(
            onDismissRequest = { viewModel.resolveUiActionConfirmation(false) },
            title = { Text("Confirm UI Action") },
            text = {
                Text(
                    text = request.description,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                )
            },
            confirmButton = {
                BrutalButton(onClick = { viewModel.resolveUiActionConfirmation(true) }) {
                    Text("Allow")
                }
            },
            dismissButton = {
                BrutalButton(onClick = { viewModel.resolveUiActionConfirmation(false) }) {
                    Text("Deny")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            PanelBox(
                title = "Chat",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                headerTrailing = {
                    BrutalIconButton(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Exit chat",
                        onClick = onExit
                    )
                }
            ) {
            RuntimeStatusLine(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                ttsEnabled = ttsEnabled,
                llmRuntimeDescription = llmRuntimeDescription
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Tools: ${availableTools.count { it.isAvailable }} available",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                BrutalButton(onClick = { showToolPicker = true }) {
                    Text("View Tools", color = MaterialTheme.colorScheme.onSurface)
                }
            }

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
                        color = MaterialTheme.colorScheme.onSurface
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
                                color = MaterialTheme.colorScheme.onSurface
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
                            Text("New", color = MaterialTheme.colorScheme.onSurface)
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
                            Text("Rename", color = MaterialTheme.colorScheme.onSurface)
                        }
                        BrutalButton(
                            onClick = {
                                if (activeConversationId != null) {
                                    deleteTarget = activeConversationId
                                }
                            },
                            enabled = activeConversationId != null
                        ) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                        BrutalButton(onClick = { showToolPicker = true }) {
                            Text("Tools", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val voiceColor = if (ttsEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        BrutalButton(
                            onClick = { viewModel.toggleTtsEnabled() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (ttsEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                                contentDescription = if (ttsEnabled) "Disable voice playback" else "Enable voice playback",
                                tint = voiceColor
                            )
                        }
                        BrutalButton(
                            onClick = { viewModel.stopPlayback() },
                            enabled = playerState == PlayerState.PLAYING || isSynthesizing
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = "Stop voice playback",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Model",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
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
                                color = MaterialTheme.colorScheme.onSurface
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
                val systemPromptFavorites by viewModel.systemPromptFavorites.collectAsState()
                val tokenUsage by viewModel.tokenUsage.collectAsState()
                
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = "System Prompt",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    TextField(
                        value = systemPrompt,
                        onValueChange = { viewModel.updateSystemPrompt(it) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                        minLines = 2,
                        maxLines = 5
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BrutalButton(
                            onClick = { viewModel.saveCurrentSystemPromptFavorite() },
                            modifier = Modifier.weight(1f),
                            enabled = systemPrompt.isNotBlank()
                        ) {
                            Text("Save Prompt", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if (systemPromptFavorites.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Prompt Favorites",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        systemPromptFavorites.forEach { prompt ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = prompt.lineSequence().firstOrNull().orEmpty().take(42),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                BrutalButton(onClick = { viewModel.applySystemPromptFavorite(prompt) }) {
                                    Text("Load", color = MaterialTheme.colorScheme.onSurface)
                                }
                                BrutalButton(onClick = { viewModel.deleteSystemPromptFavorite(prompt) }) {
                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Context Mode",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (chatContextMode == ChatContextMode.LONG_CONTEXT) {
                            "Long Context keeps history and uses compaction when the window gets tight."
                        } else {
                            "Single Turn only sends the latest user message."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                color = MaterialTheme.colorScheme.onSurface
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
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "Context Usage",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val (current, max) = tokenUsage
                    val usagePercent = if (max > 0) current.toFloat() / max else 0f
                    LinearProgressIndicator(
                        progress = { usagePercent },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = if (usagePercent > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                        trackColor = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        text = "$current / $max tokens",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.End)
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Voice Settings", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)

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
                    Text("Speed: ${"%.2f".format(speed)}", color = MaterialTheme.colorScheme.onSurface)
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
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isLoading || isSynthesizing) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.tertiary,
                            trackColor = MaterialTheme.colorScheme.outline
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
                contentPadding = PaddingValues(bottom = 18.dp)
            ) {
                itemsIndexed(chatMessages) { index, chatMessage ->
                    MessageBubble(chatMessage)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (chatMessage.isFromUser) {
                            Arrangement.spacedBy(8.dp, Alignment.End)
                        } else {
                            Arrangement.spacedBy(8.dp, Alignment.Start)
                        }
                    ) {
                        BrutalIconButton(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy message",
                            onClick = {
                                val formatted = chatMessage.message.replace("\\n", "\n").replace("/n", "\n")
                                clipboardManager.setText(AnnotatedString(formatted))
                                Toast.makeText(context, "Copied message", Toast.LENGTH_SHORT).show()
                            },
                            size = 48.dp
                        )
                        BrutalIconButton(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "Edit message",
                            onClick = {
                                editMessageIndex = index
                                editMessageText = chatMessage.message.replace("\\n", "\n").replace("/n", "\n")
                            },
                            enabled = !isLoading,
                            size = 48.dp
                        )
                        BrutalIconButton(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Regenerate response",
                            onClick = { viewModel.regenerateFrom(index) },
                            enabled = !isLoading && !isSynthesizing,
                            size = 48.dp
                        )
                        if (!chatMessage.isFromUser && chatMessage.actionTrace != null) {
                            BrutalIconButton(
                                imageVector = Icons.Filled.Description,
                                contentDescription = "View action trace",
                                onClick = { actionTraceToShow = chatMessage.actionTrace },
                                enabled = !isLoading,
                                size = 48.dp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            if (pendingImage != null) {
                Box(
                    modifier = Modifier
                        .padding(8.dp)
                        .size(100.dp)
                        .border(1.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(8.dp))
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
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (pendingAudio != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .border(1.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = pendingAudio?.displayName ?: "Audio attachment",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear audio",
                        modifier = Modifier
                            .padding(4.dp)
                            .clickable { viewModel.setPendingAudio(null) },
                        tint = MaterialTheme.colorScheme.error
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
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.tertiary)
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
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp)),
                    placeholder = { Text("Message", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    leadingIcon = {
                        Row {
                            Box {
                                Icon(
                                    imageVector = Icons.Default.AttachFile,
                                    contentDescription = "Add attachment",
                                    modifier = Modifier.clickable { attachmentMenuExpanded = true },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                DropdownMenu(
                                    expanded = attachmentMenuExpanded,
                                    onDismissRequest = { attachmentMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Image") },
                                        leadingIcon = {
                                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                                        },
                                        onClick = {
                                            attachmentMenuExpanded = false
                                            imagePicker.launch("image/*")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Document") },
                                        leadingIcon = {
                                            Icon(Icons.Default.Description, contentDescription = null)
                                        },
                                        onClick = {
                                            attachmentMenuExpanded = false
                                            documentPicker.launch("*/*")
                                        }
                                    )
                                    if (activeModelSupportsAudio) {
                                        DropdownMenuItem(
                                            text = { Text("Audio file") },
                                            leadingIcon = {
                                                Icon(Icons.Default.AttachFile, contentDescription = null)
                                            },
                                            onClick = {
                                                attachmentMenuExpanded = false
                                                audioPicker.launch("audio/*")
                                            }
                                        )
                                    }
                                }
                            }
                            if (activeModelSupportsAudio) {
                                Spacer(modifier = Modifier.width(10.dp))
                                Icon(
                                    imageVector = if (isRecordingVoice) Icons.Default.Stop else Icons.Default.Mic,
                                    contentDescription = if (isRecordingVoice) "Stop recording" else "Record voice",
                                    modifier = Modifier.clickable {
                                        if (isRecordingVoice) {
                                            val audio = voiceRecorder.stop()
                                            isRecordingVoice = false
                                            if (audio != null) {
                                                viewModel.setPendingAudio(audio)
                                                if (message.isBlank()) {
                                                    message = "Please process this voice recording."
                                                }
                                            } else {
                                                Toast.makeText(context, "No voice recording captured.", Toast.LENGTH_SHORT).show()
                                            }
                                        } else if (ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.RECORD_AUDIO
                                            ) == PackageManager.PERMISSION_GRANTED
                                        ) {
                                            runCatching {
                                                voiceRecorder.start()
                                                isRecordingVoice = true
                                            }.onFailure { error ->
                                                Toast.makeText(context, "Could not start recording: ${error.message}", Toast.LENGTH_LONG).show()
                                            }
                                        } else {
                                            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = MaterialTheme.colorScheme.outline,
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
                        cursorColor = MaterialTheme.colorScheme.tertiary,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (isLoading) {
                    BrutalIconButton(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop",
                        onClick = { viewModel.cancelGeneration() },
                        enabled = true
                    )
                } else {
                    BrutalIconButton(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        onClick = {
                            if (message.isNotBlank() || pendingImage != null || pendingAudio != null) {
                                val outgoing = when {
                                    message.isNotBlank() -> message
                                    pendingAudio != null -> "Please process this voice recording."
                                    pendingImage != null -> "Please inspect this image."
                                    else -> ""
                                }
                                viewModel.sendMessage(outgoing)
                                message = ""
                            }
                        },
                    enabled = (message.isNotBlank() || pendingImage != null || pendingAudio != null) &&
                        !isSynthesizing &&
                        playerState == PlayerState.IDLE
                    )
                }
            }
            }
        }

        orchestration?.takeIf { it.isVisible }?.let { state ->
            OrchestrationModal(
                state = state,
                onStop = viewModel::cancelGeneration,
                onDismiss = viewModel::dismissOrchestration
            )
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
                    Text("Save", color = MaterialTheme.colorScheme.onSurface)
                }
            },
            dismissButton = {
                BrutalButton(onClick = { renameTarget = null }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
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
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                BrutalButton(onClick = { deleteTarget = null }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    if (editMessageIndex != null) {
        AlertDialog(
            onDismissRequest = { editMessageIndex = null },
            title = { Text("Edit Message") },
            text = {
                TextField(
                    value = editMessageText,
                    onValueChange = { editMessageText = it },
                    minLines = 3,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                BrutalButton(
                    onClick = {
                        editMessageIndex?.let { viewModel.editMessage(it, editMessageText) }
                        editMessageIndex = null
                    },
                    enabled = editMessageText.isNotBlank()
                ) {
                    Text("Save", color = MaterialTheme.colorScheme.onSurface)
                }
            },
            dismissButton = {
                BrutalButton(onClick = { editMessageIndex = null }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    actionTraceToShow?.let { trace ->
        AlertDialog(
            onDismissRequest = { actionTraceToShow = null },
            title = { Text(trace.title) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    trace.entries.forEach { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = entry.phase,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (entry.isError) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                            if (!entry.toolName.isNullOrBlank()) {
                                Text(
                                    text = "Tool: ${entry.toolName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = entry.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val output = entry.output
                            if (!output.isNullOrBlank()) {
                                Text(
                                    text = output,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                BrutalButton(onClick = { actionTraceToShow = null }) {
                    Text("Hide", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    if (showToolPicker) {
        AlertDialog(
            onDismissRequest = { showToolPicker = false },
            title = { Text("Available Tools") },
            text = {
                Column {
                    Text(
                        text = "${availableTools.count { it.isAvailable }} tools registered. Pick a context mode, then choose a tool to prefill a direct call.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                color = MaterialTheme.colorScheme.onSurface
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
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    val visibleTools = remember(availableTools) {
                        availableTools.filter { it.isAvailable }.sortedBy { it.name }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 440.dp)
                    ) {
                        itemsIndexed(visibleTools, key = { _, tool -> tool.name }) { index, tool ->
                            BrutalButton(
                                onClick = {
                                    viewModel.updateChatContextMode(toolPrefillMode)
                                    message = buildToolPrefill(tool)
                                    showToolPicker = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(tool.name, color = MaterialTheme.colorScheme.onSurface)
                                    if (tool.description.isNotBlank()) {
                                        Text(tool.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            if (index < visibleTools.lastIndex) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                BrutalButton(onClick = { showToolPicker = false }) {
                    Text("Close", color = MaterialTheme.colorScheme.onSurface)
                }
            },
            dismissButton = {}
        )
    }
}

@Composable
private fun OrchestrationModal(
    state: OrchestrationUiState,
    onStop: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val chrome = LocalNabuChrome.current
    val statusShape = RoundedCornerShape(chrome.controlRadius)
    var showLiveOutput by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrim.copy(alpha = 0.46f))
            .clickable(enabled = !state.isRunning, onClick = onDismiss)
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        PanelBox(
            title = state.title,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.primaryContainer, statusShape)
                    .border(chrome.borderWidth, colors.primary.copy(alpha = 0.28f), statusShape)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (state.isRunning) "In progress" else "Finished",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary
                )
                Text(
                    text = state.status,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onPrimaryContainer
                )
                if (state.isRunning) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.primary,
                        trackColor = colors.secondaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                state.entries.forEachIndexed { index, entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(
                                    if (entry.isError) colors.errorContainer else colors.secondaryContainer,
                                    RoundedCornerShape(14.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = (index + 1).toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (entry.isError) colors.onErrorContainer else colors.onSecondaryContainer
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = entry.phase,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (entry.isError) colors.error else colors.onSurface
                                )
                                entry.toolName?.let { toolName ->
                                    Text(
                                        text = toolName,
                                        modifier = Modifier
                                            .background(colors.secondaryContainer, RoundedCornerShape(chrome.chipRadius))
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.onSecondaryContainer,
                                        maxLines = 1
                                    )
                                }
                            }
                            Text(
                                text = entry.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (entry.isError) colors.error else colors.onSurfaceVariant
                            )
                            entry.output?.takeIf { entry.isError && it.isNotBlank() }?.let { output ->
                                Text(
                                    text = output.take(240),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.error
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            BrutalButton(
                onClick = { showLiveOutput = !showLiveOutput },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (showLiveOutput) "Hide live model output" else "View live model output")
            }
            if (showLiveOutput) {
                Spacer(modifier = Modifier.height(10.dp))
                SelectionContainer {
                    Text(
                        text = state.liveOutput.ifBlank { "Waiting for model output..." },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                            .background(colors.surfaceVariant, statusShape)
                            .border(chrome.borderWidth, colors.outline.copy(alpha = 0.35f), statusShape)
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = colors.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            BrutalButton(
                onClick = if (state.isRunning) onStop else onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isRunning) "Stop" else "Close")
            }
        }
    }
}

private fun displayNameForUri(context: android.content.Context, uri: Uri): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index)
        }
    }
    return uri.lastPathSegment
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

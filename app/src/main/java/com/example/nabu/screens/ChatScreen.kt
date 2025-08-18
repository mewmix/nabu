package com.example.nabu.screens

import androidx.compose.foundation.border
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
import androidx.compose.material3.CircularProgressIndicator
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
import com.mewmix.nabu.ui.brutalist.PanelBox
import com.mewmix.nabu.ui.brutalist.BrutalSection
import com.mewmix.nabu.ui.brutalist.Brutal
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.BrutalSlider
import com.example.kokoro.chat.MessageBubble
import com.example.nabu.utils.PlayerState
import com.example.nabu.utils.PcmTap
import com.example.nabu.viewmodel.ChatViewModel
import com.example.nabu.ui.components.WaveformVisualizer

@Composable
fun ChatScreen(
    viewModel: ChatViewModel
) {
    val chatMessages by viewModel.chatMessages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isSynthesizing by viewModel.isSynthesizing.collectAsState()
    val playerState by viewModel.playerState.collectAsState()

    LaunchedEffect(playerState) {
        PcmTap.enabled = playerState == PlayerState.PLAYING
    }

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

    Scaffold { paddingValues ->
        PanelBox(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {

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
                    CircularProgressIndicator(color = Brutal.amber)
                }
            }

            // Message Input
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
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
}

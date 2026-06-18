package com.mewmix.nabu.chat

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.PanelBox
import dev.jeziellago.compose.markdowntext.MarkdownText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel
) {
    val chatState by viewModel.chatState.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var message by remember { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        PanelBox(
            title = "Chat",
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    reverseLayout = true
                ) {
                    items(chatState.reversed()) { chatMessage ->
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 80.dp),
                        label = { Text("Message") },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 5,
                        minLines = 3
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BrutalButton(
                        onClick = {
                            if (message.isNotBlank()) {
                                viewModel.sendMessage(message)
                                message = ""
                            }
                        },
                        enabled = message.isNotBlank() && !isLoading
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(chatMessage: ChatMessage) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val formatted = remember(chatMessage.message) {
        chatMessage.message
            .replace("\\n", "\n")
            .replace("/n", "\n")
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (chatMessage.isFromUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier
                .padding(4.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        clipboardManager.setText(AnnotatedString(formatted))
                        Toast.makeText(context, "Copied message", Toast.LENGTH_SHORT).show()
                    }
                ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (chatMessage.isFromUser) 16.dp else 0.dp,
                bottomEnd = if (chatMessage.isFromUser) 0.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (chatMessage.isFromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column {
                chatMessage.image?.let { img ->
                    Image(
                        bitmap = img.bitmap.asImageBitmap(),
                        contentDescription = "Message image",
                        modifier = Modifier
                            .padding(8.dp)
                            .size(200.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                chatMessage.audio?.let { audio ->
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = audio.displayName ?: "Audio attachment",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (formatted.isNotBlank()) {
                    SelectionContainer {
                        MarkdownText(
                            markdown = formatted,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

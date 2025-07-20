package com.example.kokoro.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.example.kokoro.galleryport.PromptLabPipeline
import com.example.kokoro.galleryport.PerfHud
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(ctx: Context, hud: Boolean) {
    val vm: ChatVM = viewModel()
    val msgs by vm.msgs.collectAsState()

    Scaffold { padding ->
        Column(Modifier.padding(padding)) {
            LazyColumn(Modifier.weight(1f)) {
                items(msgs) { Text(it) }
            }
            var txt by remember { mutableStateOf("") }
            Row {
                TextField(txt, { txt = it }, Modifier.weight(1f))
                Button(onClick = { vm.send(ctx, txt); txt = "" }) { Text("Send") }
            }
        }
        if (hud) PerfHud.Overlay()
    }
}

class ChatVM : ViewModel() {
    val msgs = MutableStateFlow(listOf<String>())
    fun send(ctx: Context, prompt: String) = viewModelScope.launch {
        msgs.value += "> $prompt"
        PromptLabPipeline.run(ctx, prompt).collect { tok ->
            msgs.value = msgs.value.dropLast(1) + (msgs.value.last() + tok)
        }
    }
}

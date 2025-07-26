package com.example.kokoro82m

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.kokoro.chat.ChatScreen
import com.example.kokoro.chat.ChatViewModel
import com.example.kokoro.chat.LlmInference
import java.io.File

class ChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val modelId = "gemma-3n-E4B-it-int4"
        val modelFile = File(filesDir, "models/$modelId.task")

        val llmInference = LlmInference(
            context = applicationContext,
            modelPath = modelFile.absolutePath
        )
        llmInference.initialize()

        val viewModel: ChatViewModel by viewModels {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ChatViewModel(llmInference) as T
                }
            }
        }

        setContent {
            ChatScreen(
                viewModel = viewModel,
                onBackPressed = { finish() }
            )
        }
    }
}

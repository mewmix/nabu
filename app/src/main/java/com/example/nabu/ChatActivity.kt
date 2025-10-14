package com.example.nabu

import NabuTheme
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.nabu.data.ModelManager
import com.example.nabu.data.Model
import com.example.nabu.screens.ChatScreen
import com.example.nabu.utils.OnnxRuntimeManager
import com.example.nabu.utils.DebugLogger
import com.example.nabu.utils.SettingsManager
import com.example.kokoro.galleryport.PerfHud
import com.example.nabu.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatActivity : ComponentActivity() {
    companion object {
        const val EXTRA_INITIAL_PROMPT = "extra_initial_prompt"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLogger.initialize(this)

        val initialPrompt = intent.getStringExtra(EXTRA_INITIAL_PROMPT)

        lifecycleScope.launch {
            val initResult = withContext(Dispatchers.IO) {
                OnnxRuntimeManager.initialize(applicationContext)
            }
            if (initResult.isFailure) {
                Toast.makeText(
                    this@ChatActivity,
                    "Kokoro models unavailable: ${initResult.exceptionOrNull()?.message}",
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }

            val modelManager = ModelManager(applicationContext)
            val downloaded = modelManager.models.filter { it.isDownloaded }

            if (downloaded.isEmpty()) {
                Toast.makeText(
                    this@ChatActivity,
                    "No chat models downloaded. Redirecting to model page.",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(
                    Intent(this@ChatActivity, MainActivity::class.java).apply {
                        putExtra(EXTRA_START_SCREEN, "Models")
                    }
                )
                finish()
                return@launch
            }

            if (downloaded.size == 1) {
                startChat(downloaded.first(), initialPrompt)
            } else {
                selectModel(downloaded, initialPrompt)
            }
        }
    }

    private fun selectModel(models: List<Model>, initialPrompt: String?) {
        val names = models.map { it.name }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select Chat Model")
            .setItems(names) { _, which ->
                startChat(models[which], initialPrompt)
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun startChat(model: Model, initialPrompt: String?) {
        val viewModel: ChatViewModel by viewModels {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return ChatViewModel(applicationContext, model.id) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
        }

        setContent {
            NabuTheme {
                ChatScreen(
                    viewModel = viewModel,
                    initialMessage = initialPrompt.orEmpty()
                )
                if (SettingsManager.isBenchmark(this@ChatActivity)) {
                    PerfHud.Overlay()
                }
            }
        }
    }
}

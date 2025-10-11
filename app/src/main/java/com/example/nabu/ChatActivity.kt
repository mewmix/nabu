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
import com.example.nabu.data.ModelManager
import com.example.nabu.data.Model
import com.example.nabu.screens.ChatScreen
import com.example.nabu.utils.OnnxRuntimeManager
import com.example.nabu.utils.DebugLogger
import com.example.nabu.utils.SettingsManager
import com.example.nabu.utils.TtsEngine
import com.example.kokoro.galleryport.PerfHud
import com.example.nabu.viewmodel.ChatViewModel

class ChatActivity : ComponentActivity() {
    companion object {
        const val EXTRA_INITIAL_PROMPT = "extra_initial_prompt"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLogger.initialize(this)

        val ortSession = OnnxRuntimeManager.getSessionOrNull()
        val engine = SettingsManager.getTtsEngine(this)
        if (ortSession == null && engine != TtsEngine.SHERPA) {
            Toast.makeText(
                this,
                "ONNX session not available for $engine engine.",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        val modelManager = ModelManager(applicationContext)
        val downloaded = modelManager.models.filter { it.isDownloaded }
        val initialPrompt = intent.getStringExtra(EXTRA_INITIAL_PROMPT)

        if (downloaded.isEmpty()) {
            Toast.makeText(
                this,
                "No chat models downloaded. Redirecting to model page.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra(EXTRA_START_SCREEN, "Models")
                }
            )
            finish()
            return
        }

        if (downloaded.size == 1) {
            startChat(downloaded.first(), ortSession, initialPrompt)
        } else {
            selectModel(downloaded, ortSession, initialPrompt)
        }

        // Chat will start in startChat or selectModel
    }

    private fun selectModel(models: List<Model>, session: ai.onnxruntime.OrtSession?, initialPrompt: String?) {
        val names = models.map { it.name }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select Chat Model")
            .setItems(names) { _, which ->
                startChat(models[which], session, initialPrompt)
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun startChat(model: Model, session: ai.onnxruntime.OrtSession?, initialPrompt: String?) {
        val viewModel: ChatViewModel by viewModels {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return ChatViewModel(applicationContext, session, model.id) as T
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

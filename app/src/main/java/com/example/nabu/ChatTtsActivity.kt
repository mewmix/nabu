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
import com.example.kokoro.chat.LlmInference
import com.example.nabu.data.ModelManager
import com.example.nabu.data.Model
import com.example.nabu.screens.ChatTtsScreen
import com.example.nabu.utils.OnnxRuntimeManager
import com.example.nabu.utils.DebugLogger
import com.example.nabu.utils.SettingsManager
import com.example.kokoro.galleryport.PerfHud
import com.example.nabu.viewmodel.ChatTtsViewModel
import java.io.File

class ChatTtsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLogger.initialize(this)

        val ortSession = OnnxRuntimeManager.getSession()
        val modelManager = ModelManager(applicationContext)
        val downloaded = modelManager.models.filter { it.isDownloaded }

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
            startChat(downloaded.first(), ortSession)
        } else {
            selectModel(downloaded, ortSession)
        }

        // Chat will start in startChat or selectModel
    }

    private fun selectModel(models: List<Model>, session: ai.onnxruntime.OrtSession) {
        val names = models.map { it.name }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select Chat Model")
            .setItems(names) { _, which ->
                startChat(models[which], session)
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun startChat(model: Model, session: ai.onnxruntime.OrtSession) {
        val modelFile = File(filesDir, "models/${model.id}.task")
        val llmInference = LlmInference(applicationContext, modelFile.absolutePath)

        val viewModel: ChatTtsViewModel by viewModels {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ChatTtsViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return ChatTtsViewModel(applicationContext, session, llmInference) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
        }

        setContent {
            NabuTheme {
                ChatTtsScreen(viewModel = viewModel, modelName = model.name, onBackPressed = { finish() })
                if (SettingsManager.isBenchmark(this@ChatTtsActivity)) {
                    PerfHud.Overlay()
                }
            }
        }
    }
}
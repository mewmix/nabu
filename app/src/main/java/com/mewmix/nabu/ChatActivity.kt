package com.mewmix.nabu

import NabuTheme
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.Model
import com.mewmix.nabu.data.ModelType
import com.mewmix.nabu.data.OAuthRemoteModels
import com.mewmix.nabu.screens.ChatScreen
import com.mewmix.nabu.utils.OnnxRuntimeManager
import com.mewmix.nabu.utils.DebugLogger
import com.mewmix.nabu.utils.SettingsManager
import com.mewmix.nabu.galleryport.PerfHud
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.PanelBox
import com.mewmix.nabu.viewmodel.ChatViewModel
import com.mewmix.nabu.chat.LlmRuntimeOverrides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatActivity : ComponentActivity() {
    companion object {
        const val EXTRA_INITIAL_PROMPT = "extra_initial_prompt"
        const val EXTRA_LLM_THREADS_AUTO = "llm_threads_auto"
        const val EXTRA_LLM_THREADS = "llm_threads"
        const val EXTRA_LLM_THREADS_BATCH = "llm_threads_batch"
        const val EXTRA_LLM_MAX_NEW_TOKENS = "llm_max_new_tokens"
        const val EXTRA_LLM_N_CTX = "llm_n_ctx"
        const val EXTRA_LLM_N_BATCH = "llm_n_batch"
        const val EXTRA_LLM_TTFT_TIMEOUT_MS = "llm_ttft_timeout_ms"
        const val EXTRA_LLM_TOTAL_TIMEOUT_MS = "llm_total_timeout_ms"
    }

    private val llmOverrides: LlmRuntimeOverrides? by lazy { readLlmOverrides(intent) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLogger.initialize(this)

        val initialPrompt = intent.getStringExtra(EXTRA_INITIAL_PROMPT)

        lifecycleScope.launch(Dispatchers.IO) {
            if (SettingsManager.getTtsEngine(applicationContext) == "kokoro") {
                val initResult = OnnxRuntimeManager.initialize(
                    applicationContext,
                    allowDownload = SettingsManager.isKokoroAutoDownloadEnabled(applicationContext)
                )
                if (initResult.isFailure) {
                    val message = initResult.exceptionOrNull()?.message ?: "Kokoro runtime unavailable"
                    DebugLogger.log("ChatActivity: Kokoro warm-up failed: $message")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@ChatActivity,
                            "Kokoro models unavailable: $message",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        lifecycleScope.launch {
            val modelManager = ModelManager(applicationContext)
            val downloaded = modelManager.models.filter { it.isDownloaded && it.type == ModelType.LLM }
            val remote = OAuthRemoteModels.connectedModels(applicationContext)
            val available = (downloaded + remote).distinctBy { it.id }

            if (available.isEmpty()) {
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

            if (available.size == 1) {
                startChat(available.first(), initialPrompt)
            } else {
                selectModel(available, initialPrompt)
            }
        }
    }

    private fun selectModel(models: List<Model>, initialPrompt: String?) {
        setContent {
            NabuTheme {
                ChatModelPicker(
                    models = models,
                    onSelect = { startChat(it, initialPrompt) },
                    onCancel = { finish() }
                )
            }
        }
    }

    private fun startChat(model: Model, initialPrompt: String?) {
        val viewModel: ChatViewModel by viewModels {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return ChatViewModel(applicationContext, model.id, llmOverrides) as T
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

    private fun readLlmOverrides(source: Intent?): LlmRuntimeOverrides? {
        if (source == null) return null
        var hasAny = false

        fun <T> take(extra: String, getter: () -> T): T? {
            return if (source.hasExtra(extra)) {
                hasAny = true
                getter()
            } else {
                null
            }
        }

        val overrides = LlmRuntimeOverrides(
            threadsAuto = take(EXTRA_LLM_THREADS_AUTO) {
                source.getBooleanExtra(EXTRA_LLM_THREADS_AUTO, true)
            },
            nThreads = take(EXTRA_LLM_THREADS) {
                source.getIntExtra(EXTRA_LLM_THREADS, 0)
            },
            nThreadsBatch = take(EXTRA_LLM_THREADS_BATCH) {
                source.getIntExtra(EXTRA_LLM_THREADS_BATCH, 0)
            },
            maxNewTokens = take(EXTRA_LLM_MAX_NEW_TOKENS) {
                source.getIntExtra(EXTRA_LLM_MAX_NEW_TOKENS, 0)
            },
            nCtx = take(EXTRA_LLM_N_CTX) {
                source.getIntExtra(EXTRA_LLM_N_CTX, 0)
            },
            nBatch = take(EXTRA_LLM_N_BATCH) {
                source.getIntExtra(EXTRA_LLM_N_BATCH, 0)
            },
            ttftTimeoutMs = take(EXTRA_LLM_TTFT_TIMEOUT_MS) {
                source.getLongExtra(EXTRA_LLM_TTFT_TIMEOUT_MS, 0L)
            },
            totalTimeoutMs = take(EXTRA_LLM_TOTAL_TIMEOUT_MS) {
                source.getLongExtra(EXTRA_LLM_TOTAL_TIMEOUT_MS, 0L)
            }
        )

        return if (hasAny) overrides else null
    }
}

@Composable
private fun ChatModelPicker(
    models: List<Model>,
    onSelect: (Model) -> Unit,
    onCancel: () -> Unit
) {
    BackHandler(onBack = onCancel)
    PanelBox(
        title = "Select Chat Model",
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Choose the local or connected model for this conversation.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(models) { model ->
                    BrutalButton(
                        onClick = { onSelect(model) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    RoundedCornerShape(18.dp)
                                )
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                    RoundedCornerShape(18.dp)
                                )
                                .padding(10.dp)
                        ) {
                            Text(
                                text = model.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (model.description.isNotBlank()) {
                                Text(
                                    text = model.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            BrutalButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel")
            }
        }
    }
}

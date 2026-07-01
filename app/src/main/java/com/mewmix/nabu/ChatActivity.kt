package com.mewmix.nabu

import NabuTheme
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.Model
import com.mewmix.nabu.data.ModelType
import com.mewmix.nabu.data.ConversationRepository
import com.mewmix.nabu.data.OAuthRemoteModels
import com.mewmix.nabu.screens.ChatScreen
import com.mewmix.nabu.utils.OnnxRuntimeManager
import com.mewmix.nabu.utils.DebugLogger
import com.mewmix.nabu.utils.SettingsManager
import com.mewmix.nabu.utils.TextExtractor
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
        const val EXTRA_START_VOICE = "extra_start_voice"
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

        val startVoice = intent.getBooleanExtra(EXTRA_START_VOICE, false)

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
            val initialPrompt = withContext(Dispatchers.IO) {
                readInitialPrompt(intent)
            }
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

            val preferred = if (startVoice) preferredVoiceLaunchModel(available) else null
            val recentChatModel = preferredRecentChatModel(available)
            if (preferred != null) {
                DebugLogger.log("ChatActivity: quick voice using model ${preferred.id}")
                startChat(preferred, initialPrompt, startVoice)
            } else if (recentChatModel != null) {
                DebugLogger.log("ChatActivity: restoring recent chat with model ${recentChatModel.id}")
                startChat(recentChatModel, initialPrompt, startVoice)
            } else if (available.size == 1) {
                startChat(available.first(), initialPrompt, startVoice)
            } else {
                selectModel(available, initialPrompt, startVoice)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_START_VOICE, false) ||
            intent.hasExtra(EXTRA_INITIAL_PROMPT) ||
            intent.action == Intent.ACTION_SEND ||
            intent.action == Intent.ACTION_VIEW
        ) {
            recreate()
        }
    }

    private fun preferredVoiceLaunchModel(models: List<Model>): Model? {
        if (models.size == 1) return models.first()
        return preferredRecentChatModel(models)
    }

    private fun preferredRecentChatModel(models: List<Model>): Model? {
        val modelsById = models.associateBy { it.id }
        val normalizedModelsById = models.associateBy { OAuthRemoteModels.normalizeModelId(it.id) }
        ConversationRepository.getConversationSummaries(applicationContext)
            .sortedByDescending { it.updatedAt }
            .forEach { summary ->
                val modelId = summary.modelId ?: return@forEach
                modelsById[modelId]?.let { return it }
                normalizedModelsById[OAuthRemoteModels.normalizeModelId(modelId)]?.let { return it }
            }
        return null
    }

    private fun selectModel(models: List<Model>, initialPrompt: String?, startVoice: Boolean) {
        setContent {
            NabuTheme {
                ChatModelPicker(
                    models = models,
                    onSelect = { startChat(it, initialPrompt, startVoice) },
                    onCancel = ::exitChat
                )
            }
        }
    }

    private fun exitChat() {
        if (isTaskRoot) {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        }
        finish()
    }

    private fun startChat(model: Model, initialPrompt: String?, startVoice: Boolean) {
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
                val isInitializing by viewModel.isInitializing.collectAsState()
                
                if (isInitializing) {
                    ChatModelPickerLoading(
                        model = model,
                        onCancel = ::exitChat
                    )
                } else {
                    ChatScreen(
                        viewModel = viewModel,
                        initialMessage = initialPrompt.orEmpty(),
                        startVoice = startVoice,
                        onExit = ::exitChat
                    )
                }
                
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

    private fun readInitialPrompt(source: Intent?): String? {
        if (source == null) return null
        source.getStringExtra(EXTRA_INITIAL_PROMPT)?.takeIf { it.isNotBlank() }?.let { return it }

        if (source.action == Intent.ACTION_SEND) {
            source.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { return it }
            val stream = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                source.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                source.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            stream?.let { return promptFromDocumentUri(it) }
        }

        if (source.action == Intent.ACTION_VIEW) {
            source.data?.let { return promptFromDocumentUri(it) }
        }

        return null
    }

    private fun promptFromDocumentUri(uri: Uri): String? {
        runCatching {
            if (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return runCatching {
            val (chunks, meta) = TextExtractor.extract(applicationContext, uri, chunkSize = 2400)
            val extracted = chunks.take(6).joinToString("\n\n").trim()
            if (extracted.isBlank()) {
                "I opened ${meta.displayName}, but no readable text was extracted. Help me inspect or summarize what is available."
            } else {
                "Use this document to start the conversation: ${meta.displayName}\n\n$extracted"
            }
        }.onFailure { error ->
            DebugLogger.log("ChatActivity: failed to extract shared document $uri: ${error.message}")
        }.getOrNull()
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

@Composable
private fun ChatModelPickerLoading(
    model: Model,
    onCancel: () -> Unit
) {
    BackHandler(onBack = onCancel)
    PanelBox(
        title = "Loading Runtime",
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Initializing ${model.name}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            val estimateText = if (model.name.contains("gemma", ignoreCase = true)) {
                "Loading runtime (Est. ~25s)..."
            } else {
                "Loading runtime..."
            }
            
            Text(
                text = estimateText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(0.8f),
                color = MaterialTheme.colorScheme.tertiary,
                trackColor = MaterialTheme.colorScheme.outline
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            BrutalButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(0.5f)
            ) {
                Text("Cancel")
            }
        }
    }
}

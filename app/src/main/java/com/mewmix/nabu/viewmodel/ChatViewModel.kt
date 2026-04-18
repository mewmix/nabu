package com.mewmix.nabu.viewmodel

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mewmix.nabu.chat.ChatMessage
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.CodexOAuthBackend
import com.mewmix.nabu.chat.LiteRtLmBackend
import com.mewmix.nabu.chat.LlamaCppBackend
import com.mewmix.nabu.chat.LlmRuntimeOverrides
import com.mewmix.nabu.chat.MediaPipeBackend
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.data.Conversation
import com.mewmix.nabu.data.ConversationRepository
import com.mewmix.nabu.data.ConversationRole
import com.mewmix.nabu.data.ConversationSummary
import com.mewmix.nabu.data.ConversationTurn
import com.mewmix.nabu.data.Model
import com.mewmix.nabu.data.findDownloadedLlmArtifact
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.ModelType
import com.mewmix.nabu.data.OAuthRemoteModels
import com.mewmix.nabu.kokoro.KokoroEngine
import com.mewmix.nabu.supertonic.DebugSupertonicEngine
import com.mewmix.nabu.tts.TTSManager
import com.mewmix.nabu.utils.AudioPlayer
import com.mewmix.nabu.utils.BenchmarkManager
import com.mewmix.nabu.utils.DebugLogger
import com.mewmix.nabu.utils.InterpolationMode
import com.mewmix.nabu.utils.KokoroAudioPlayer
import com.mewmix.nabu.utils.OnnxRuntimeManager
import com.mewmix.nabu.utils.PhonemeConverter
import com.mewmix.nabu.utils.PlayerState
import com.mewmix.nabu.utils.SettingsManager
import com.mewmix.nabu.utils.StyleLoader
import com.mewmix.nabu.utils.VoiceMixConfig
import com.mewmix.nabu.utils.VoiceMixFavorite
import com.mewmix.nabu.utils.createAudioFromStyleVector
import com.mewmix.nabu.utils.filterToAvailableStyles
import com.mewmix.nabu.utils.mixStyles
import com.mewmix.nabu.utils.normalized
import com.mewmix.nabu.utils.toConfig
import com.mewmix.nabu.tools.GlaiveBridge
import com.mewmix.nabu.tools.ToolCall
import com.mewmix.nabu.tools.ToolCallProtocol
import com.mewmix.nabu.tools.ToolRegistry
import com.mewmix.nabu.tools.ToolResult
import com.mewmix.nabu.actions.ActionTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale

enum class ChatContextMode(val storageValue: String) {
    LONG_CONTEXT("long_context"),
    SINGLE_TURN("single_turn");

    companion object {
        fun fromStorage(value: String): ChatContextMode =
            entries.firstOrNull { it.storageValue == value } ?: LONG_CONTEXT
    }
}

class ChatViewModel(
    private val context: Context,
    initialModelId: String,
    private val llmOverrides: LlmRuntimeOverrides? = null
) : ViewModel() {

    companion object {
        private const val DEFAULT_MAX_CONTEXT_TOKENS = 1024
        private const val MAX_TOOL_CALLS_PER_TURN = 4
        private const val TOOL_EXECUTION_TIMEOUT_MS = 30_000L
        private const val DEFAULT_SYSTEM_PROMPT = "You are a helpful AI assistant."
        private val TOKEN_REGEX = Regex("\\S+")

        internal fun inferToolCallFromModelFailure(
            userMessage: String,
            availableToolNames: Set<String>
        ): ToolCall? {
            val normalized = userMessage.trim()
            if (normalized.isBlank()) return null

            if ("set_alarm" in availableToolNames) {
                parseAlarmFallback(normalized)?.let { return it }
            }

            if ("set_timer" in availableToolNames) {
                parseTimerFallback(normalized)?.let { return it }
            }

            if ("search_web_context" in availableToolNames) {
                val query = sequenceOf(
                    Regex("""(?is)^\s*search\s+(?:the\s+)?web\s+for\s+(.+?)\s*$""").find(normalized)?.groupValues?.getOrNull(1),
                    Regex("""(?is)^\s*look\s+up\s+(.+?)\s*$""").find(normalized)?.groupValues?.getOrNull(1)
                ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

                if (query.isNotBlank()) {
                    return ToolCall(
                        toolName = "search_web_context",
                        arguments = mapOf("query" to query)
                    )
                }
            }

            if ("open_url" in availableToolNames) {
                Regex("""(?is)^\s*open\s+url\s+(\S+)\s*$""").find(normalized)?.groupValues?.getOrNull(1)?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { url ->
                        return ToolCall("open_url", mapOf("url" to url))
                    }
            }

            if ("open_app" in availableToolNames || "launch_package" in availableToolNames) {
                Regex("""(?is)^\s*(?:open\s+app|launch\s+package)\s+(.+?)\s*$""").find(normalized)?.groupValues?.getOrNull(1)?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { appName ->
                        val toolName = if ("open_app" in availableToolNames) "open_app" else "launch_package"
                        return ToolCall(toolName, mapOf("app_name" to appName))
                    }
            }

            if ("send_sms" in availableToolNames) {
                parseSmsFallback(normalized)?.let { return it }
            }

            if ("place_call" in availableToolNames) {
                parseCallFallback(normalized)?.let { return it }
            }

            if ("schedule_action" in availableToolNames) {
                val scheduleSearchMatch = Regex(
                    pattern = """(?is)^\s*(?:use\s+)?schedule_action\s+to\s+run\s+search_web_context\s+query\s+(.+?)\s+at\s+(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2})(?:\s+title\s+(.+?))?\s*$"""
                ).find(normalized)
                if (scheduleSearchMatch != null) {
                    val query = scheduleSearchMatch.groupValues[1].trim()
                    val runAtLocal = scheduleSearchMatch.groupValues[2].trim()
                    val title = scheduleSearchMatch.groupValues.getOrNull(3)?.trim().orEmpty()
                    if (query.isNotBlank() && runAtLocal.isNotBlank()) {
                        return ToolCall(
                            toolName = "schedule_action",
                            arguments = buildMap {
                                put("run_at_local", runAtLocal)
                                put("tool_name", "search_web_context")
                                put("tool_arguments", mapOf("query" to query))
                                if (title.isNotBlank()) {
                                    put("title", title)
                                }
                                put(
                                    "instruction",
                                    "Run scheduled tool search_web_context with arguments query."
                                )
                            }
                        )
                    }
                }
            }

            return null
        }

        private fun parseAlarmFallback(normalized: String): ToolCall? {
            val match = Regex(
                pattern = """(?is)^\s*(?:set\s+)?(?:an\s+)?alarm(?:\s+for)?\s+(\d{1,2})(?:(?::|\s+)(\d{2}))\s*(am|pm)?\b(?:.*?\b(?:called|named|message)\b\s+(.+))?\s*$"""
            ).find(normalized) ?: return null

            val rawHour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            val meridiem = match.groupValues[3].trim().lowercase()
            val message = match.groupValues.getOrNull(4)?.trim().orEmpty()

            val hour = when (meridiem) {
                "am" -> if (rawHour == 12) 0 else rawHour
                "pm" -> if (rawHour in 1..11) rawHour + 12 else rawHour
                else -> rawHour
            }

            if (hour !in 0..23 || minute !in 0..59) return null

            return ToolCall(
                toolName = "set_alarm",
                arguments = buildMap {
                    put("hour", hour)
                    put("minute", minute)
                    if (message.isNotBlank()) {
                        put("message", message)
                    }
                }
            )
        }

        private fun parseTimerFallback(normalized: String): ToolCall? {
            val match = Regex(
                pattern = """(?is)^\s*(?:set\s+)?(?:a\s+)?timer(?:\s+for)?\s+(.+?)(?:\s+\b(?:called|named|message)\b\s+(.+))?\s*$"""
            ).find(normalized) ?: return null

            val durationSpec = match.groupValues[1].trim()
            val message = match.groupValues.getOrNull(2)?.trim().orEmpty()
            val seconds = parseDurationSeconds(durationSpec) ?: return null

            return ToolCall(
                toolName = "set_timer",
                arguments = buildMap {
                    put("seconds", seconds)
                    if (message.isNotBlank()) {
                        put("message", message)
                    }
                }
            )
        }

        private fun parseSmsFallback(normalized: String): ToolCall? {
            Regex("""(?is)^\s*(?:send\s+sms|text)\s+to\s+([+()\-\d\s]+?)(?:\s+(?:saying|message)\s+(.+?))?\s*$""")
                .find(normalized)
                ?.let { match ->
                    val phoneNumber = match.groupValues[1].trim()
                    val message = match.groupValues.getOrNull(2)?.trim().orEmpty()
                    if (phoneNumber.isNotBlank()) {
                        return ToolCall(
                            "send_sms",
                            buildMap {
                                put("phone_number", phoneNumber)
                                if (message.isNotBlank()) {
                                    put("message", message)
                                }
                            }
                        )
                    }
                }

            val contactMatch = sequenceOf(
                Regex("""(?is)^\s*(?:send\s+sms|text)\s+to\s+(.+?)\s+(?:saying|message(?:\s+that)?)\s+(.+?)\s*$""").find(normalized),
                Regex("""(?is)^\s*(?:send\s+sms|text)\s+to\s+(.+?)\s*,\s*(.+?)\s*$""").find(normalized),
                Regex("""(?is)^\s*(?:send\s+sms|text)\s+to\s+(.+?)\s*:\s*(.+?)\s*$""").find(normalized)
            ).firstOrNull()

            if (contactMatch != null) {
                val recipient = contactMatch.groupValues[1].trim()
                val message = contactMatch.groupValues[2].trim()
                if (recipient.isNotBlank() && recipient.any { it.isLetter() }) {
                    return ToolCall(
                        "send_sms",
                        buildMap {
                            put("recipient", recipient)
                            if (message.isNotBlank()) {
                                put("message", message)
                            }
                        }
                    )
                }
            }

            Regex("""(?is)^\s*(?:send\s+sms|text)\s+to\s+(.+?)\s*$""").find(normalized)
                ?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotBlank() && it.any(Char::isLetter) }
                ?.let { recipient ->
                    return ToolCall("send_sms", mapOf("recipient" to recipient))
                }

            return null
        }

        private fun parseCallFallback(normalized: String): ToolCall? {
            Regex("""(?is)^\s*(?:call|place\s+call)\s+([+()\-\d\s]+)\s*$""")
                .find(normalized)
                ?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { phoneNumber ->
                    return ToolCall("place_call", mapOf("phone_number" to phoneNumber))
                }

            Regex("""(?is)^\s*(?:call|place\s+call)\s+(.+?)\s*$""")
                .find(normalized)
                ?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotBlank() && it.any(Char::isLetter) }
                ?.let { recipient ->
                    return ToolCall("place_call", mapOf("recipient" to recipient))
                }

            return null
        }

        private fun parseDurationSeconds(durationSpec: String): Int? {
            val unitRegex = Regex("""(?is)(\d+)\s*(hour|hours|hr|hrs|minute|minutes|min|mins|second|seconds|sec|secs)\b""")
            val matches = unitRegex.findAll(durationSpec).toList()
            if (matches.isEmpty()) return null

            var totalSeconds = 0
            for (match in matches) {
                val amount = match.groupValues[1].toIntOrNull() ?: return null
                val unit = match.groupValues[2].lowercase()
                totalSeconds += when (unit) {
                    "hour", "hours", "hr", "hrs" -> amount * 3600
                    "minute", "minutes", "min", "mins" -> amount * 60
                    "second", "seconds", "sec", "secs" -> amount
                    else -> return null
                }
            }

            return totalSeconds.takeIf { it > 0 }
        }
    }

    // Dependencies
    private val phonemeConverter = PhonemeConverter(context)
    val styleLoader = StyleLoader(context)
    private val defaultVoice = styleLoader.names.firstOrNull() ?: "af_sky"
    private val initialVoiceMixConfig = SettingsManager.getVoiceMixConfig(context, defaultVoice)
    private val audioPlayer: AudioPlayer = KokoroAudioPlayer(viewModelScope) { newState ->
        _playerState.value = newState
    }

    private val modelManager = ModelManager(context)
    private var llmBackend: LlmBackend? = null
    private val conversationHistory = mutableListOf<ConversationTurn>()

    // Chat State
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages = _chatMessages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    // Conversation State
    private val _conversationSummaries = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val conversationSummaries = _conversationSummaries.asStateFlow()

    private val _activeConversationId = MutableStateFlow<Long?>(null)
    val activeConversationId = _activeConversationId.asStateFlow()

    private val _availableModels = MutableStateFlow<List<Model>>(emptyList())
    val availableModels = _availableModels.asStateFlow()

    private val _activeModel = MutableStateFlow<Model?>(null)
    val activeModel = _activeModel.asStateFlow()

    // TTS State
    private val _isSynthesizing = MutableStateFlow(false)
    val isSynthesizing = _isSynthesizing.asStateFlow()

    private val _playerState = MutableStateFlow(PlayerState.IDLE)
    val playerState = _playerState.asStateFlow()

    private val _ttsEnabled = MutableStateFlow(SettingsManager.isTtsEnabled(context))
    val ttsEnabled = _ttsEnabled.asStateFlow()

    // Mixer State
    private val _selectedStyles = MutableStateFlow(initialVoiceMixConfig.styles)
    val selectedStyles = _selectedStyles.asStateFlow()

    private val _weights = MutableStateFlow(initialVoiceMixConfig.weights)
    val weights = _weights.asStateFlow()

    private val _interpolationMode = MutableStateFlow(initialVoiceMixConfig.interpolationMode)
    val interpolationMode = _interpolationMode.asStateFlow()

    private val _speed = MutableStateFlow(SettingsManager.getSpeed(context))
    val speed = _speed.asStateFlow()

    private val _voiceFavorites = MutableStateFlow(SettingsManager.getVoiceMixFavorites(context))
    val voiceFavorites = _voiceFavorites.asStateFlow()

    private data class QueuedAudio(val index: Int, val audio: FloatArray, val sampleRate: Int)

    private val audioQueue = Channel<QueuedAudio>(Channel.UNLIMITED)
    private val pendingAudio = mutableMapOf<Int, QueuedAudio>()
    private var nextPlaybackIndex = 0
    private var dropQueuedAudio = false
    private var lineIndex = 0

    private val ttsMutex = kotlinx.coroutines.sync.Mutex()

    fun refreshAvailableModels() {
        val downloadedModels = modelManager.models.filter { it.isDownloaded && it.type == ModelType.LLM }
        val remoteModels = OAuthRemoteModels.connectedModels(context)
        _availableModels.value = (downloadedModels + remoteModels).distinctBy { it.id }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            OnnxRuntimeManager.initialize(context.applicationContext)
            ActionTools.tools.forEach { ToolRegistry.register(it) }

            // Initialize Glaive tools if available
            if (GlaiveBridge.isInstalled(context.applicationContext)) {
                GlaiveBridge.registerDefaultTools(context.applicationContext)
                DebugLogger.log("ChatViewModel: Glaive tools registered.")
            }
        }
        // Launch a coroutine to play queued audio in the order they were generated
        viewModelScope.launch {
            for (item in audioQueue) {
                pendingAudio[item.index] = item
                while (pendingAudio.containsKey(nextPlaybackIndex)) {
                    val queued = pendingAudio.remove(nextPlaybackIndex)!!
                    if (!_ttsEnabled.value || dropQueuedAudio) {
                        nextPlaybackIndex++
                        continue
                    }
                    try {
                        audioPlayer.prepare(queued.audio, queued.sampleRate)
                        audioPlayer.playBlocking()
                    } catch (e: Exception) {
                        DebugLogger.log("Audio playback error: ${e.localizedMessage}")
                    }
                    nextPlaybackIndex++
                }
            }
        }

        refreshAvailableModels()
        
        val startingModel = _availableModels.value.find { it.id == initialModelId } ?: _availableModels.value.firstOrNull()
        startingModel?.let { setActiveModel(it, persistConversation = false) }

        refreshConversations()
        refreshStyles()
    }

    fun refreshStyles() {
        val available = styleLoader.names
        if (available.isNotEmpty()) {
            val fallback = available.first()
            val sanitized = currentVoiceMixConfig()
                .filterToAvailableStyles(available, fallback)
            if (sanitized != currentVoiceMixConfig()) {
                applyVoiceMixConfig(sanitized, persist = true)
            }
            val sanitizedFavorites = _voiceFavorites.value.mapNotNull { favorite ->
                favorite.toConfig()
                    .filterToAvailableStyles(available, fallback)
                    .takeIf { it.styles.isNotEmpty() }
                    ?.let { config ->
                        favorite.copy(
                            styles = config.styles,
                            weights = config.weights,
                            interpolationMode = config.interpolationMode
                        )
                    }
            }
            if (sanitizedFavorites != _voiceFavorites.value) {
                _voiceFavorites.value = sanitizedFavorites
                SettingsManager.setVoiceMixFavorites(context, sanitizedFavorites)
            }
        }
    }

    fun toggleTtsEnabled() {
        val enabled = !_ttsEnabled.value
        _ttsEnabled.value = enabled
        SettingsManager.setTtsEnabled(context, enabled)
        if (!enabled) {
            stopPlayback()
        } else {
            dropQueuedAudio = false
        }
    }

    fun stopPlayback() {
        dropQueuedAudio = true
        pendingAudio.clear()
        drainAudioQueue()
        audioPlayer.stop()
        _playerState.value = PlayerState.IDLE
        _isSynthesizing.value = false
    }

    fun selectConversation(conversationId: Long) {
        if (_activeConversationId.value == conversationId) return
        loadConversation(conversationId)
    }

    fun createConversation() {
        viewModelScope.launch(Dispatchers.IO) {
            val conversation = ConversationRepository.createConversation(
                context,
                generateConversationTitle(),
                _activeModel.value?.id,
                emptyList()
            )
            refreshConversations(conversation.id)
        }
    }

    fun renameConversation(conversationId: Long, newTitle: String) {
        val title = newTitle.trim()
        if (title.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val updatedAt = ConversationRepository.renameConversation(context, conversationId, title)
            withContext(Dispatchers.Main) {
                updateConversationSummary(conversationId) { summary ->
                    summary.copy(title = title, updatedAt = updatedAt)
                }
            }
        }
    }

    fun deleteConversation(conversationId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            ConversationRepository.deleteConversation(context, conversationId)
            refreshConversations(
                desiredActiveId = if (_activeConversationId.value == conversationId) null else _activeConversationId.value
            )
        }
    }

    fun selectModel(modelId: String) {
        val model = resolveModelById(modelId)
        if (model != null && model.isDownloaded) {
            setActiveModel(model)
        }
    }

    fun sendMessage(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return
        val conversationId = _activeConversationId.value ?: run {
            DebugLogger.log("No active conversation; ignoring message")
            return
        }
        val directToolCall = ToolCallProtocol.parseDirectUserToolCommand(trimmed)

        dropQueuedAudio = false
        DebugLogger.log("ChatViewModel sendMessage: $trimmed")
        _chatMessages.value += ChatMessage(trimmed, true)
        conversationHistory.add(ConversationTurn(ConversationRole.USER, trimmed))
        persistConversationMessages()
        _isLoading.value = true

        val benchmarkEnabled = SettingsManager.isBenchmark(context)
        if (benchmarkEnabled) {
            BenchmarkManager.startLlm()
        }

        val responseBuilder = StringBuilder()
        val sentenceBuilder = StringBuilder()
        _chatMessages.value += ChatMessage("...", false) // placeholder

        if (directToolCall != null) {
            viewModelScope.launch(Dispatchers.IO) {
                DebugLogger.log(
                    "ChatViewModel: executing direct tool command ${directToolCall.toolName} with ${directToolCall.arguments}"
                )
                val result = executeToolCallInternal(context.applicationContext, directToolCall)
                finalizeDirectToolResponse(summarizeToolResultMessage(result))
            }
            return
        }

        val backend = llmBackend ?: run {
            DebugLogger.log("No LLM backend available; ignoring message")
            finalizeDirectToolResponse("No LLM backend available.")
            return
        }

        val runtimeConfig = SettingsManager.getLlmRuntimeConfig(context, llmOverrides)
        val backendMaxTokens = when (backend) {
            is LlamaCppBackend -> {
                backend.updateConfig(runtimeConfig)
                backend.currentConfig.nCtx
            }
            is MediaPipeBackend -> {
                val mediaPipeConfig = SettingsManager.getMediaPipeRuntimeConfig(context)
                backend.updateConfig(mediaPipeConfig)
                mediaPipeConfig.maxTokens
            }
            else -> DEFAULT_MAX_CONTEXT_TOKENS
        }

        val conversationForModel = prepareConversationForModel(backendMaxTokens)
        val appContext = context.applicationContext

        viewModelScope.launch(Dispatchers.IO) {
            fun formatSyntheticToolCall(toolCall: ToolCall): String {
                val args = toolCall.arguments.entries.joinToString(",") { (key, value) ->
                    val encodedValue = when (value) {
                        is Number, is Boolean -> value.toString()
                        else -> "\"" + value.toString()
                            .replace("\\", "\\\\")
                            .replace("\"", "\\\"") + "\""
                    }
                    "\"$key\":$encodedValue"
                }
                return """<tool_call>{"name":"${toolCall.toolName}","arguments":{$args}}</tool_call>"""
            }

            fun updateAssistantPlaceholder(content: String) {
                viewModelScope.launch {
                    val last = _chatMessages.value.lastOrNull()
                    if (last != null) {
                        _chatMessages.value =
                            _chatMessages.value.dropLast(1) + last.copy(message = content)
                    }
                }
            }

            fun finalizeAssistantResponse(finalResponse: String, speakOutput: Boolean) {
                viewModelScope.launch {
                    _isLoading.value = false
                    DebugLogger.log("ChatViewModel response complete")
                    val last = _chatMessages.value.lastOrNull()
                    if (last != null) {
                        _chatMessages.value =
                            _chatMessages.value.dropLast(1) + last.copy(message = finalResponse)
                    }
                    conversationHistory.add(ConversationTurn(ConversationRole.AGENT, finalResponse))
                    persistConversationMessages()
                    if (speakOutput) {
                        processSentences(sentenceBuilder, true)
                    } else {
                        sentenceBuilder.clear()
                        dropQueuedAudio = false
                    }
                }
            }

            fun runInference(
                conversation: List<LlmMessage>,
                remainingToolCalls: Int,
                lastToolResult: ToolResult? = null,
                usedBlankRecovery: Boolean = false
            ) {
                responseBuilder.clear()
                sentenceBuilder.clear()
                var suppressSpeechForThisPass = false

                backend.sendMessage(conversation) { partial, done ->
                    if (benchmarkEnabled && !done) {
                        BenchmarkManager.recordPartial(partial)
                    }

                    if (!done) {
                        responseBuilder.append(partial)
                        if (partial.contains("<tool_call>", ignoreCase = true)) {
                            suppressSpeechForThisPass = true
                            sentenceBuilder.clear()
                        } else {
                            sentenceBuilder.append(partial)
                        }
                        updateAssistantPlaceholder(responseBuilder.toString())
                        if (!suppressSpeechForThisPass) {
                            processSentences(sentenceBuilder, false)
                        }
                        return@sendMessage
                    }

                    if (partial.isNotEmpty()) {
                        responseBuilder.append(partial)
                        if (partial.contains("<tool_call>", ignoreCase = true)) {
                            suppressSpeechForThisPass = true
                            sentenceBuilder.clear()
                        } else {
                            sentenceBuilder.append(partial)
                        }
                    }

                    val finalResponse = responseBuilder.toString()
                    val toolCall = ToolCallProtocol.extractToolCall(finalResponse)
                    val looksLikeMalformedToolAttempt =
                        finalResponse.trim().startsWith("```") ||
                            finalResponse.contains("<tool_call", ignoreCase = true)
                    val availableToolNames = ToolRegistry.tools.value
                        .filter { it.isAvailable }
                        .map { it.name }
                        .toSet()
                    val inferredToolCall =
                        if (toolCall == null && lastToolResult == null &&
                            (finalResponse.isBlank() || looksLikeMalformedToolAttempt)
                        ) {
                            inferToolCallFromModelFailure(trimmed, availableToolNames)
                        } else {
                            null
                        }
                    val effectiveToolCall = toolCall ?: inferredToolCall
                    DebugLogger.log(
                        "ChatViewModel: final response len=${finalResponse.length}, " +
                            "toolDetected=${effectiveToolCall != null}, remainingToolCalls=$remainingToolCalls"
                    )
                    if (toolCall == null && inferredToolCall != null) {
                        DebugLogger.log(
                            "ChatViewModel: inferred tool ${inferredToolCall.toolName} from failed model response " +
                                "using user message: ${trimmed.take(160)}"
                        )
                    }
                    if (effectiveToolCall == null && finalResponse.isNotBlank()) {
                        DebugLogger.log("ChatViewModel: final response preview: ${finalResponse.take(220)}")
                    }
                    if (effectiveToolCall != null && remainingToolCalls > 0) {
                        DebugLogger.log(
                            "ChatViewModel: executing tool ${effectiveToolCall.toolName} with ${effectiveToolCall.arguments}"
                        )
                        updateAssistantPlaceholder("Running tool ${effectiveToolCall.toolName}...")
                        viewModelScope.launch(Dispatchers.IO) {
                            val result = executeToolCallInternal(appContext, effectiveToolCall)
                            DebugLogger.log(
                                "ChatViewModel: tool ${effectiveToolCall.toolName} finished " +
                                    "(error=${result.isError}, outputLength=${result.output.length})"
                            )
                            val modelToolCallMessage = if (toolCall != null) {
                                finalResponse
                            } else {
                                formatSyntheticToolCall(effectiveToolCall)
                            }
                            val followUpConversation = conversation + listOf(
                                LlmMessage(role = "model", content = modelToolCallMessage),
                                LlmMessage(
                                    role = "user",
                                    content = ToolCallProtocol.formatToolResultForModel(result)
                                )
                            )
                            runInference(
                                followUpConversation,
                                remainingToolCalls - 1,
                                lastToolResult = result,
                                usedBlankRecovery = false
                            )
                        }
                        return@sendMessage
                    }

                    val shouldRetryAfterBlankResponse =
                        finalResponse.isBlank() &&
                            lastToolResult == null &&
                            !usedBlankRecovery
                    if (shouldRetryAfterBlankResponse) {
                        val recoveryConversation = prepareConversationForModel(
                            maxTokens = backendMaxTokens,
                            forceSingleTurn = true,
                            recoveryMode = true
                        )
                        val recoveryWouldChangePrompt = recoveryConversation != conversation
                        if (recoveryWouldChangePrompt) {
                            DebugLogger.log(
                                "ChatViewModel: blank model response, retrying once with compacted single-turn recovery context"
                            )
                            updateAssistantPlaceholder("Retrying with compacted context...")
                            runInference(
                                recoveryConversation,
                                remainingToolCalls = remainingToolCalls,
                                lastToolResult = null,
                                usedBlankRecovery = true
                            )
                            return@sendMessage
                        }
                        DebugLogger.log(
                            "ChatViewModel: blank model response; skipping compacted-context retry because recovery prompt is unchanged"
                        )
                    }

                    val exhaustedToolBudget = effectiveToolCall != null && remainingToolCalls <= 0
                    val shouldFallbackToToolResult = lastToolResult != null && (
                        finalResponse.isBlank() ||
                            effectiveToolCall != null ||
                            finalResponse.contains("<tool_call>", ignoreCase = true)
                        )
                    val resolvedResponse = when {
                        exhaustedToolBudget && lastToolResult != null -> summarizeToolResultMessage(lastToolResult)
                        exhaustedToolBudget -> "Tool-call limit reached for this turn."
                        shouldFallbackToToolResult -> summarizeToolResultMessage(lastToolResult!!)
                        finalResponse.isBlank() ->
                            if (usedBlankRecovery) {
                                "Model returned no usable output after retry."
                            } else {
                                "Model returned no usable output."
                            }
                        else -> finalResponse
                    }

                    if (benchmarkEnabled) {
                        BenchmarkManager.finishLlm()
                        BenchmarkManager.profileSystem(context)
                    }
                    finalizeAssistantResponse(
                        finalResponse = resolvedResponse,
                        speakOutput = toolCall == null && !shouldFallbackToToolResult && !exhaustedToolBudget
                    )
                }
            }

            runInference(conversationForModel, MAX_TOOL_CALLS_PER_TURN)
        }
    }

    fun cancelGeneration() {
        llmBackend?.cancel()
    }

    private fun refreshConversations(desiredActiveId: Long? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            var summaries = ConversationRepository.getConversationSummaries(context)
                .sortedByDescending { it.updatedAt }
            var activeId = desiredActiveId ?: _activeConversationId.value
            if (activeId == null || summaries.none { it.id == activeId }) {
                activeId = summaries.firstOrNull()?.id
            }
            if (activeId == null) {
                val conversation = ConversationRepository.createConversation(
                    context,
                    generateConversationTitle(),
                    _activeModel.value?.id,
                    emptyList()
                )
                summaries = ConversationRepository.getConversationSummaries(context)
                    .sortedByDescending { it.updatedAt }
                activeId = conversation.id
            }
            val conversation = ConversationRepository.getConversation(context, activeId)
            withContext(Dispatchers.Main) {
                _conversationSummaries.value = summaries
                _activeConversationId.value = activeId
                applyConversation(conversation)
            }
            conversation?.modelId?.let { modelId ->
                val model = resolveModelById(modelId)
                if (model != null) {
                    withContext(Dispatchers.Main) {
                        setActiveModel(model, persistConversation = false)
                    }
                }
            }
        }
    }

    private fun loadConversation(conversationId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val conversation = ConversationRepository.getConversation(context, conversationId)
            withContext(Dispatchers.Main) {
                _activeConversationId.value = conversationId
                applyConversation(conversation)
            }
            conversation?.modelId?.let { modelId ->
                val model = resolveModelById(modelId)
                if (model != null) {
                    withContext(Dispatchers.Main) {
                        setActiveModel(model, persistConversation = false)
                    }
                }
            }
        }
    }

    private fun updateConversationSummary(
        conversationId: Long,
        transformer: (ConversationSummary) -> ConversationSummary
    ) {
        val current = _conversationSummaries.value.toMutableList()
        val index = current.indexOfFirst { it.id == conversationId }
        if (index >= 0) {
            val updated = transformer(current[index])
            current.removeAt(index)
            current.add(updated)
            current.sortByDescending { it.updatedAt }
            _conversationSummaries.value = current
        } else {
            refreshConversations(conversationId)
        }
    }

    private fun applyConversation(conversation: Conversation?) {
        conversationHistory.clear()
        if (conversation != null) {
            conversationHistory.addAll(conversation.messages)
            _chatMessages.value = conversation.messages.map { ChatMessage(it.content, it.role == ConversationRole.USER) }
        } else {
            _chatMessages.value = emptyList()
        }
        clearPendingAudio()
    }

    private fun clearPendingAudio() {
        lineIndex = 0
        nextPlaybackIndex = 0
        pendingAudio.clear()
        dropQueuedAudio = false
        drainAudioQueue()
        audioPlayer.stop()
        _playerState.value = PlayerState.IDLE
        _isSynthesizing.value = false
        _isLoading.value = false
    }

    private fun setActiveModel(model: Model, persistConversation: Boolean = true) {
        if (_activeModel.value?.id == model.id && llmBackend != null) {
            _activeModel.value = model
            return
        }

        val remoteSelection = OAuthRemoteModels.detectSelection(model.id, model.backend)
        when (remoteSelection?.provider) {
            OAuthRemoteModels.Provider.CODEX -> {
                _activeModel.value = model
                llmBackend?.close()
                llmBackend = CodexOAuthBackend(
                    context = context,
                    model = remoteSelection.modelSlug
                )
                DebugLogger.log(
                    "ChatViewModel: selected remote model provider=codex model=${remoteSelection.modelSlug} " +
                        "endpoint=chatgpt.com/backend-api/codex/responses"
                )
                llmBackend?.initialize()
            }
            null -> {
                val artifact = findDownloadedLlmArtifact(File(context.filesDir, "models"), model.id, model.backend)
                if (artifact == null) {
                    DebugLogger.log("Model file not found for ${model.id} (.task/.litertlm/.gguf)")
                    llmBackend = null
                    return
                }
                _activeModel.value = model
                llmBackend?.close()
                llmBackend = when (artifact.backend) {
                    "llama" -> {
                        val runtimeConfig = SettingsManager.getLlmRuntimeConfig(context, llmOverrides)
                        LlamaCppBackend(context, artifact.file.absolutePath, runtimeConfig).also { llama ->
                            viewModelScope.launch(Dispatchers.IO) {
                                llama.initialize()
                            }
                        }
                    }
                    "litertlm" -> {
                        LiteRtLmBackend(
                            context = context,
                            modelPath = artifact.file.absolutePath
                        ).also { liteRtLm ->
                            liteRtLm.initialize()
                        }
                    }
                    else -> {
                        MediaPipeBackend(
                            context = context,
                            modelPath = artifact.file.absolutePath,
                            initialConfig = SettingsManager.getMediaPipeRuntimeConfig(context)
                        ).also { mediaPipe ->
                            mediaPipe.initialize()
                        }
                    }
                }
            }
        }
        if (persistConversation) {
            val conversationId = _activeConversationId.value
            if (conversationId != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    val updatedAt = ConversationRepository.updateModel(context, conversationId, model.id)
                    withContext(Dispatchers.Main) {
                        updateConversationSummary(conversationId) { summary ->
                            summary.copy(modelId = model.id, updatedAt = updatedAt)
                        }
                    }
                }
            }
        }
    }

    private fun persistConversationMessages() {
        val conversationId = _activeConversationId.value ?: return
        val messagesSnapshot = conversationHistory.toList()
        viewModelScope.launch(Dispatchers.IO) {
            val updatedAt = ConversationRepository.updateMessages(context, conversationId, messagesSnapshot)
            withContext(Dispatchers.Main) {
                updateConversationSummary(conversationId) { summary ->
                    summary.copy(modelId = _activeModel.value?.id ?: summary.modelId, updatedAt = updatedAt)
                }
            }
        }
    }

    private fun resolveModelById(modelId: String): Model? {
        val normalizedId = OAuthRemoteModels.normalizeModelId(modelId)
        return _availableModels.value.find { it.id == normalizedId && it.isDownloaded }
            ?: _availableModels.value.find { it.id == modelId && it.isDownloaded }
            ?: modelManager.getModel(normalizedId)?.takeIf { it.isDownloaded }
            ?: modelManager.getModel(modelId)?.takeIf { it.isDownloaded }
            ?: OAuthRemoteModels.syntheticModelForId(normalizedId)
            ?: OAuthRemoteModels.syntheticModelForId(modelId)
    }

    private fun generateConversationTitle(): String {
        val formatter = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
        return "Conversation ${formatter.format(Date())}"
    }

    // System Prompt & Token Usage
    private val _systemPrompt =
        MutableStateFlow(SettingsManager.getChatSystemPrompt(context, DEFAULT_SYSTEM_PROMPT))
    val systemPrompt = _systemPrompt.asStateFlow()

    private val _chatContextMode =
        MutableStateFlow(ChatContextMode.fromStorage(SettingsManager.getChatContextMode(context)))
    val chatContextMode = _chatContextMode.asStateFlow()

    private val _tokenUsage = MutableStateFlow(0 to DEFAULT_MAX_CONTEXT_TOKENS)
    val tokenUsage = _tokenUsage.asStateFlow()

    fun updateSystemPrompt(newPrompt: String) {
        _systemPrompt.value = newPrompt
        SettingsManager.setChatSystemPrompt(context, newPrompt)
    }

    fun updateChatContextMode(mode: ChatContextMode) {
        _chatContextMode.value = mode
        SettingsManager.setChatContextMode(context, mode.storageValue)
    }

    private fun prepareConversationForModel(
        maxTokens: Int,
        forceSingleTurn: Boolean = false,
        recoveryMode: Boolean = false
    ): List<LlmMessage> {
        val availableTools = ToolRegistry.tools.value.filter { it.isAvailable }
        val promptTools = selectPromptToolsForConversation(conversationHistory, availableTools)
        val systemPrompt = ToolCallProtocol.buildSystemPrompt(
            basePrompt = _systemPrompt.value,
            tools = promptTools
        )
        val systemMsg = LlmMessage(role = "system", content = systemPrompt)
        val systemTokens = estimateTokenCount(systemMsg.content)
        val availableTokens = maxTokens - systemTokens

        val effectiveContextMode = if (forceSingleTurn) {
            ChatContextMode.SINGLE_TURN
        } else {
            _chatContextMode.value
        }
        val selectedConversation = when (effectiveContextMode) {
            ChatContextMode.SINGLE_TURN -> takeSingleTurnConversation(conversationHistory)
            ChatContextMode.LONG_CONTEXT -> compactConversationToTokenLimit(
                conversation = conversationHistory,
                maxTokens = availableTokens,
                aggressive = recoveryMode
            )
        }
        val trimmedConversation =
            if (selectedConversation.sumOf { estimateTokenCount(it.content) } > availableTokens) {
                trimConversationToTokenLimit(selectedConversation, availableTokens)
            } else {
                selectedConversation
            }
        val totalTokens = trimmedConversation.sumOf { estimateTokenCount(it.content) } + systemTokens
        
        _tokenUsage.value = totalTokens to maxTokens
        DebugLogger.log(
            "Prepared ${trimmedConversation.size} conversation turns + system prompt " +
                "(~${totalTokens} tokens, toolsInPrompt=${promptTools.size}, registeredTools=${availableTools.size}, selectedTools=${promptTools.joinToString(",") { it.name }}, contextMode=${effectiveContextMode.storageValue}, recovery=$recoveryMode)"
        )
        
        val messages = mutableListOf<LlmMessage>()
        if (systemMsg.content.isNotBlank()) {
            messages.add(systemMsg)
        }
        
        messages.addAll(trimmedConversation.map { turn ->
            val role = when (turn.role) {
                ConversationRole.USER -> "user"
                ConversationRole.AGENT -> "model" // Changed from "agent" to "model"
            }
            LlmMessage(role = role, content = turn.content)
        })
        return messages
    }

    private fun selectPromptToolsForConversation(
        conversation: List<ConversationTurn>,
        availableTools: List<com.mewmix.nabu.tools.Tool>
    ): List<com.mewmix.nabu.tools.Tool> {
        val latestUserMessage = conversation
            .asReversed()
            .firstOrNull { it.role == ConversationRole.USER }
            ?.content
            ?.trim()
            .orEmpty()
        if (latestUserMessage.isBlank()) return emptyList()

        val toolsByName = availableTools.associateBy { it.name }
        val selectedNames = LinkedHashSet<String>()
        val normalized = latestUserMessage.lowercase(Locale.US)

        fun addTool(name: String) {
            if (name in toolsByName) {
                selectedNames += name
            }
        }

        ToolCallProtocol.parseDirectUserToolCommand(latestUserMessage)?.toolName?.let(::addTool)

        if (containsAny(normalized, "alarm", "wake me")) addTool("set_alarm")
        if (containsAny(normalized, "timer", "countdown")) addTool("set_timer")
        if (containsAny(normalized, "weather", "forecast", "temperature")) addTool("get_weather")
        if (containsAny(normalized, "search", "look up", "web", "news")) addTool("search_web_context")
        if (containsAny(normalized, "remember", "save memory", "memorize")) addTool("save_memory")
        if (containsAny(normalized, "what do you remember", "retrieve memory", "recall memory")) addTool("retrieve_memory")
        if (containsAny(normalized, "scheduled actions", "list scheduled")) addTool("list_scheduled_actions")
        if (containsAny(normalized, "schedule", "remind later", "run later", "background")) addTool("schedule_action")
        if (containsAny(normalized, "open url", "open link", "http://", "https://", "www.")) addTool("open_url")
        if (containsAny(normalized, "open app", "launch app", "launch package")) {
            addTool("open_app")
            addTool("launch_package")
        }
        if (containsAny(normalized, "send sms", "text ", "text me", "message ")) addTool("send_sms")
        if (containsAny(normalized, "call ", "dial ", "place call")) addTool("place_call")
        if (containsAny(normalized, "brightness", "dim", "brighter")) addTool("set_brightness")
        if (containsAny(normalized, "flashlight", "torch")) addTool("toggle_flashlight")
        if (containsAny(normalized, "volume", "louder", "quieter")) addTool("set_volume")
        if (containsAny(normalized, "mute", "unmute")) addTool("mute")
        if (containsAny(normalized, "play media", "resume media", "resume playback")) addTool("play_media")
        if (containsAny(normalized, "pause media", "pause playback")) addTool("pause_media")
        if (containsAny(normalized, "next track", "skip track", "skip song")) addTool("next_track")
        if (containsAny(normalized, "calendar", "event", "meeting")) addTool("create_calendar_event")
        if (containsAny(normalized, "navigate to", "directions to", "route to")) addTool("navigate_to")
        if (containsAny(normalized, "take a photo", "take photo", "camera")) addTool("take_photo")
        if (containsAny(normalized, "record a video", "record video")) addTool("record_video")
        if (containsAny(normalized, "wifi", "wi-fi")) addTool("toggle_wifi")
        if (containsAny(normalized, "bluetooth")) addTool("toggle_bluetooth")
        if (containsAny(normalized, "share this", "share text", "share ")) addTool("share_text")

        return selectedNames
            .take(6)
            .mapNotNull { toolsByName[it] }
    }

    private fun containsAny(text: String, vararg needles: String): Boolean =
        needles.any { text.contains(it) }

    private fun summarizeToolResultMessage(result: ToolResult): String {
        val cleaned = result.output.trim()
        val clipped = if (cleaned.length > 700) "${cleaned.take(700)}..." else cleaned
        return if (result.isError) {
            "Tool ${result.toolName} failed: ${if (clipped.isNotEmpty()) clipped else "no error details"}"
        } else {
            "Tool ${result.toolName} result:\n$clipped"
        }
    }

    private suspend fun executeToolCallInternal(appContext: Context, toolCall: ToolCall): ToolResult {
        val tool = ToolRegistry.getTool(toolCall.toolName)
        if (tool == null || !tool.isAvailable) {
            return ToolResult(
                toolName = toolCall.toolName,
                output = "Tool '${toolCall.toolName}' is unavailable.",
                isError = true
            )
        }
        ActionTools.execute(appContext, toolCall)?.let { return it }
        if (!GlaiveBridge.isInstalled(appContext)) {
            return ToolResult(
                toolName = toolCall.toolName,
                output = "Glaive is not installed.",
                isError = true
            )
        }
        return withTimeoutOrNull(TOOL_EXECUTION_TIMEOUT_MS) {
            GlaiveBridge.executeTool(appContext, toolCall)
        } ?: ToolResult(
            toolName = toolCall.toolName,
            output = "Tool '${toolCall.toolName}' timed out after ${TOOL_EXECUTION_TIMEOUT_MS / 1000}s.",
            isError = true
        )
    }

    private fun finalizeDirectToolResponse(finalResponse: String) {
        viewModelScope.launch(Dispatchers.Main) {
            _isLoading.value = false
            DebugLogger.log("ChatViewModel response complete")
            val last = _chatMessages.value.lastOrNull()
            if (last != null) {
                _chatMessages.value =
                    _chatMessages.value.dropLast(1) + last.copy(message = finalResponse)
            }
            conversationHistory.add(ConversationTurn(ConversationRole.AGENT, finalResponse))
            persistConversationMessages()
        }
    }

    private fun takeSingleTurnConversation(conversation: List<ConversationTurn>): List<ConversationTurn> {
        if (conversation.isEmpty()) return emptyList()
        return listOfNotNull(conversation.lastOrNull { it.role == ConversationRole.USER } ?: conversation.lastOrNull())
    }

    private fun compactConversationToTokenLimit(
        conversation: List<ConversationTurn>,
        maxTokens: Int,
        aggressive: Boolean = false
    ): List<ConversationTurn> {
        if (conversation.isEmpty() || maxTokens <= 0) {
            return emptyList()
        }
        val totalTokens = conversation.sumOf { estimateTokenCount(it.content) }
        if (totalTokens <= maxTokens) {
            return conversation
        }

        val reserveForSummary = when {
            aggressive -> minOf(96, maxTokens / 2)
            else -> minOf(160, maxTokens / 3)
        }.coerceAtLeast(32)
        val recentBudget = (maxTokens - reserveForSummary).coerceAtLeast(16)

        var recentTokens = 0
        val recentTurns = ArrayDeque<ConversationTurn>()
        for (turn in conversation.asReversed()) {
            val tokenCount = estimateTokenCount(turn.content)
            if (recentTurns.isEmpty() || recentTokens + tokenCount <= recentBudget) {
                recentTurns.addFirst(turn)
                recentTokens += tokenCount
            } else {
                break
            }
        }

        val olderTurns = conversation.dropLast(recentTurns.size)
        val remainingBudget = (maxTokens - recentTokens).coerceAtLeast(0)
        if (olderTurns.isEmpty() || remainingBudget < 8) {
            return trimConversationToTokenLimit(conversation, maxTokens)
        }

        val compactedSummary = buildCompactedHistorySummary(olderTurns, remainingBudget)
        return if (compactedSummary == null) {
            trimConversationToTokenLimit(conversation, maxTokens)
        } else {
            listOf(compactedSummary) + recentTurns.toList()
        }
    }

    private fun buildCompactedHistorySummary(
        turns: List<ConversationTurn>,
        tokenBudget: Int
    ): ConversationTurn? {
        if (turns.isEmpty() || tokenBudget <= 0) return null
        val lines = mutableListOf("[Earlier conversation, compacted]")
        for (turn in turns) {
            val prefix = if (turn.role == ConversationRole.USER) "User" else "Assistant"
            lines += "$prefix: ${turn.content.trim()}"
        }
        val compacted = takeLastTokens(lines.joinToString("\n"), tokenBudget)
        if (compacted.isBlank()) return null
        return ConversationTurn(ConversationRole.AGENT, compacted)
    }

    private fun trimConversationToTokenLimit(
        conversation: List<ConversationTurn>,
        maxTokens: Int
    ): List<ConversationTurn> {
        if (conversation.isEmpty() || maxTokens <= 0) {
            return emptyList()
        }
        var remainingTokens = maxTokens
        val trimmed = ArrayDeque<ConversationTurn>()
        for (turn in conversation.asReversed()) {
            if (remainingTokens <= 0) break
            val tokenCount = estimateTokenCount(turn.content)
            if (tokenCount <= remainingTokens) {
                trimmed.addFirst(turn)
                remainingTokens -= tokenCount
            } else {
                val truncatedContent = takeLastTokens(turn.content, remainingTokens)
                if (truncatedContent.isNotBlank()) {
                    trimmed.addFirst(turn.copy(content = truncatedContent))
                }
                break
            }
        }
        return trimmed.toList()
    }

    private fun estimateTokenCount(text: String): Int {
        if (text.isBlank()) return 0
        return TOKEN_REGEX.findAll(text).count()
    }

    private fun takeLastTokens(text: String, tokenLimit: Int): String {
        if (tokenLimit <= 0) return ""
        val tokens = TOKEN_REGEX.findAll(text).map { it.value }.toList()
        if (tokens.isEmpty()) return ""
        if (tokens.size <= tokenLimit) {
            return text.trim()
        }
        return tokens.takeLast(tokenLimit).joinToString(" ")
    }

    private fun processSentences(builder: StringBuilder, done: Boolean) {
        var text = builder.toString()
        val regex = Regex("[.!?]+|\n")
        var match = regex.find(text)
        while (match != null) {
            val end = match.range.last + 1
            val sentence = text.substring(0, end).trim()
            DebugLogger.log("Queueing sentence: $sentence")
            synthesizeAndQueue(cleanText(sentence))
            text = text.substring(end)
            match = regex.find(text)
        }
        builder.clear()
        builder.append(text)
        if (done && builder.isNotEmpty()) {
            val sentence = builder.toString().trim()
            builder.clear()
            synthesizeAndQueue(cleanText(sentence))
        }
        if (done) {
            dropQueuedAudio = false
        }
    }

    private fun synthesizeAndQueue(text: String) {
        if (!_ttsEnabled.value || dropQueuedAudio) return
        val currentIndex = lineIndex++
        viewModelScope.launch {
            _isSynthesizing.value = true
            try {
                val benchmark = SettingsManager.isBenchmark(context)
                if (benchmark) {
                    BenchmarkManager.handoff()
                }
                DebugLogger.log("Synthesizing: ${text}")
                val audioData = withContext(Dispatchers.IO) {
                    ttsMutex.withLock {
                        val engine = TTSManager.getEngine(context, modelManager)
                            ?: throw IllegalStateException("No TTS engine available")

                        val ttsStart = SystemClock.elapsedRealtime()

                        val realEngine = if (engine is com.mewmix.nabu.tts.BenchmarkingTTSEngine) engine.delegate else engine

                        if (realEngine is KokoroEngine) {
                            DebugLogger.log("ChatViewModel: Using KokoroEngine. Phonemizing '$text'...")
                        } else {
                            DebugLogger.log("ChatViewModel: Using ${realEngine.name}. Synthesizing '$text'...")
                        }

                        val (data, sampleRate) = if (realEngine is KokoroEngine) {
                            val mixedVector = mixStyles(
                                styleLoader,
                                _selectedStyles.value,
                                _weights.value,
                                _interpolationMode.value
                            )
                            val phonemes = phonemeConverter.phonemize(text)
                            DebugLogger.log("ChatViewModel: Phonemes generated: $phonemes")

                            createAudioFromStyleVector(
                                phonemes = phonemes,
                                voice = mixedVector,
                                speed = _speed.value,
                                engine = realEngine
                            )
                        } else {
                            // For Supertonic or other engines, use the interface directly
                            // Note: Supertonic does its own text normalization/phonemization internally or via TextProcessor
                            if (realEngine is DebugSupertonicEngine) {
                                val styleName = _selectedStyles.value.firstOrNull() ?: "F1"
                                DebugLogger.log("ChatViewModel: Setting Supertonic style to '$styleName'")
                                realEngine.setStyle(styleName)
                            }
                            val result = engine.synthesize(text, _speed.value)
                            result.wav to result.sampleRate
                        }

                        val genMs = SystemClock.elapsedRealtime() - ttsStart
                        if (benchmark) {
                            val audioMs = data.size * 1000L / sampleRate
                            // Note: Benchmark might need adjustment for Supertonic details
                            BenchmarkManager.recordTts(OnnxRuntimeManager.currentBundle(), genMs, audioMs)
                            BenchmarkManager.profileSystem(context)
                        }
                        QueuedAudio(currentIndex, data, sampleRate)
                    }
                }
                if (!dropQueuedAudio) {
                    audioQueue.send(audioData)
                }
            } catch (e: Exception) {
                DebugLogger.log("Error synthesizing sentence: ${e.localizedMessage}")
            } finally {
                _isSynthesizing.value = false
            }
        }
    }

    private fun drainAudioQueue() {
        while (true) {
            val result = audioQueue.tryReceive()
            if (result.isFailure) break
        }
    }

    private fun cleanText(text: String): String {
        val replaced = text.replace("\\n", "\n").replace("/n", "\n")
        val markdownRegex = Regex("[*_`>#\\[\\](){},]")
        return replaced.replace(markdownRegex, "")
    }

    // --- Mixer State Updaters ---
    fun addStyle(style: String) {
        if (style !in _selectedStyles.value) {
            _selectedStyles.value += style
            _weights.value += (style to 1f)
            persistVoiceMixConfig()
        }
    }

    fun removeStyle(style: String) {
        _selectedStyles.value -= style
        _weights.value -= style
        if (_selectedStyles.value.isEmpty()) {
            addStyle(defaultVoice) // Ensure at least one style is always selected
        } else {
            persistVoiceMixConfig()
        }
    }

    fun updateWeight(style: String, value: Float) {
        _weights.value = _weights.value.toMutableMap().apply { this[style] = value.coerceIn(0f, 1f) }
        persistVoiceMixConfig()
    }

    fun updateInterpolationMode(mode: InterpolationMode) {
        _interpolationMode.value = mode
        persistVoiceMixConfig()
    }

    fun updateSpeed(newSpeed: Float) {
        _speed.value = newSpeed
        persistSpeed(newSpeed)
    }

    private var speedPersistenceJob: kotlinx.coroutines.Job? = null
    private fun persistSpeed(value: Float) {
        speedPersistenceJob?.cancel()
        speedPersistenceJob = viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            SettingsManager.setSpeed(context, value)
        }
    }

    fun saveCurrentFavorite(name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return
        val favorite = VoiceMixFavorite(
            name = trimmedName,
            styles = _selectedStyles.value,
            weights = _weights.value,
            interpolationMode = _interpolationMode.value
        )
        val updated = _voiceFavorites.value
            .filterNot { it.name.equals(trimmedName, ignoreCase = true) } + favorite
        _voiceFavorites.value = updated
        SettingsManager.setVoiceMixFavorites(context, updated)
    }

    fun applyFavorite(favorite: VoiceMixFavorite) {
        applyVoiceMixConfig(favorite.toConfig(), persist = true)
    }

    fun deleteFavorite(name: String) {
        val updated = _voiceFavorites.value.filterNot { it.name.equals(name, ignoreCase = true) }
        _voiceFavorites.value = updated
        SettingsManager.setVoiceMixFavorites(context, updated)
    }

    override fun onCleared() {
        super.onCleared()
        llmBackend?.close()
        audioPlayer.stop()
    }

    private fun currentVoiceMixConfig(): VoiceMixConfig =
        VoiceMixConfig(
            styles = _selectedStyles.value,
            weights = _weights.value,
            interpolationMode = _interpolationMode.value
        )

    private fun applyVoiceMixConfig(config: VoiceMixConfig, persist: Boolean) {
        val normalized = config.normalized(defaultVoice)
        _selectedStyles.value = normalized.styles
        _weights.value = normalized.weights
        _interpolationMode.value = normalized.interpolationMode
        if (persist) {
            persistVoiceMixConfig()
        }
    }

    private var configPersistenceJob: kotlinx.coroutines.Job? = null
    private fun persistVoiceMixConfig() {
        configPersistenceJob?.cancel()
        configPersistenceJob = viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            SettingsManager.setVoiceMixConfig(context, currentVoiceMixConfig())
        }
    }
}

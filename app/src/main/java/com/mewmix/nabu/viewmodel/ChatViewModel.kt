package com.mewmix.nabu.viewmodel

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mewmix.nabu.agent.AgentTurnRunner
import com.mewmix.nabu.agent.ActionPlanner
import com.mewmix.nabu.chat.ActionTrace
import com.mewmix.nabu.chat.ActionTraceEntry
import com.mewmix.nabu.chat.ChatMessage
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmAudioInput
import com.mewmix.nabu.chat.LlamaCppBackend
import com.mewmix.nabu.chat.LlmBackendFactory
import com.mewmix.nabu.chat.LlmImageInput
import com.mewmix.nabu.chat.LlmRuntimeOverrides
import com.mewmix.nabu.chat.MediaPipeBackend
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.data.Conversation
import com.mewmix.nabu.data.ConversationRepository
import com.mewmix.nabu.data.ConversationRole
import com.mewmix.nabu.data.ConversationSummary
import com.mewmix.nabu.data.ConversationTurn
import com.mewmix.nabu.data.Model
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
import com.mewmix.nabu.actions.DeviceAction
import com.mewmix.nabu.uiagent.UiAutomationOrchestrator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.io.File
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

data class AppSelectionRequest(
    val query: String,
    val candidates: List<DeviceAction.AppCandidate>
)

data class UiActionConfirmationRequest(val description: String)

data class OrchestrationUiState(
    val title: String,
    val status: String,
    val entries: List<ActionTraceEntry>,
    val isRunning: Boolean,
    val isVisible: Boolean = true
)

class ChatViewModel(
    private val context: Context,
    initialModelId: String,
    private val llmOverrides: LlmRuntimeOverrides? = null
) : ViewModel() {
    private val requestedInitialModelId = initialModelId

    private val _pendingImage = MutableStateFlow<LlmImageInput?>(null)
    val pendingImage = _pendingImage.asStateFlow()

    private val _pendingAudioInput = MutableStateFlow<LlmAudioInput?>(null)
    val pendingAudioInput = _pendingAudioInput.asStateFlow()

    fun setPendingImage(image: LlmImageInput?) {
        _pendingImage.value = image
    }

    fun setPendingAudio(audio: LlmAudioInput?) {
        _pendingAudioInput.value = audio
    }

    private fun saveImageToInternalStorage(bitmap: android.graphics.Bitmap): String {
        val filename = "img_${System.currentTimeMillis()}.png"
        val file = File(context.filesDir, "chat_images").apply { if (!exists()) mkdirs() }
        val imageFile = File(file, filename)
        java.io.FileOutputStream(imageFile).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        return imageFile.absolutePath
    }

    private fun loadBitmapFromPath(path: String): android.graphics.Bitmap? {
        return try {
            android.graphics.BitmapFactory.decodeFile(path)
        } catch (e: Exception) {
            DebugLogger.log("Error loading bitmap from $path: ${e.message}")
            null
        }
    }

    private fun saveAudioToInternalStorage(audio: LlmAudioInput): String {
        val safeName = audio.displayName
            ?.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
            ?.takeIf { it.isNotBlank() }
            ?: "audio_${System.currentTimeMillis()}.bin"
        val file = File(context.filesDir, "chat_audio").apply { if (!exists()) mkdirs() }
        val audioFile = File(file, "${System.currentTimeMillis()}_$safeName")
        audioFile.writeBytes(audio.bytes)
        return audioFile.absolutePath
    }

    private fun loadAudioFromPath(path: String, displayName: String? = null): LlmAudioInput? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            LlmAudioInput(
                bytes = file.readBytes(),
                displayName = displayName ?: file.name,
                absolutePath = file.absolutePath
            )
        } catch (e: Exception) {
            DebugLogger.log("Error loading audio from $path: ${e.message}")
            null
        }
    }

    companion object {
        private const val DEFAULT_MAX_CONTEXT_TOKENS = LlmBackendFactory.DEFAULT_MAX_CONTEXT_TOKENS
        private const val MAX_TOOL_CALLS_PER_TURN = 4
        private const val TOOL_EXECUTION_TIMEOUT_MS = 30_000L
        private const val DEFAULT_SYSTEM_PROMPT = "You are a helpful AI assistant."
        private val TOKEN_REGEX = Regex("\\S+")
        private val DIRECT_RESULT_TOOL_NAMES = setOf(
            "list_tools",
            "send_sms",
            "place_call",
            "schedule_action",
            "list_scheduled_actions",
            "open_url",
            "open_app",
            "launch_package",
            "set_alarm",
            "set_timer",
            "set_brightness",
            "toggle_flashlight",
            "set_volume",
            "mute",
            "play_media",
            "pause_media",
            "next_track",
            "create_calendar_event",
            "navigate_to",
            "take_photo",
            "record_video",
            "toggle_wifi",
            "toggle_bluetooth",
            "share_text",
            UiAutomationOrchestrator.CONTROL_UI_TOOL
        )

        internal data class DirectActionPlan(
            val toolCalls: List<ToolCall>,
            val response: String
        )

        private const val DURATION_PATTERN =
            """(?:\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|a|an)\s+(?:seconds?|secs?|minutes?|mins?|hours?|hrs?)"""

        private data class TimedHoldPlan(
            val toolCalls: List<ToolCall>,
            val subject: ToolCall
        )

        internal fun planDirectActionChain(
            userMessage: String,
            availableToolNames: Set<String>
        ): DirectActionPlan? {
            val normalized = userMessage.trim()
            if (normalized.isBlank()) return null

            val clauses = splitActionClauses(normalized)
            if (clauses.size < 2) return null

            val toolCalls = mutableListOf<ToolCall>()
            val responseParts = mutableListOf<String>()
            var lastActionSubject: ToolCall? = null
            var lastTimedHoldInverse: ToolCall? = null
            clauses.forEach { clause ->
                val trimmedClause = clause.trim().trimEnd('.', '!', '?')
                if (trimmedClause.isBlank()) return@forEach

                parseConversationalClause(trimmedClause)?.let {
                    responseParts += it
                    return@forEach
                }

                parseTimedHoldClause(trimmedClause, availableToolNames)?.let {
                    toolCalls += it.toolCalls
                    lastActionSubject = it.subject
                    lastTimedHoldInverse = it.toolCalls.lastOrNull()?.let(::scheduledInnerToolCall)
                    return@forEach
                }

                val resolvedClause = resolvePronounClause(trimmedClause, lastActionSubject)

                parseDelayedActionClause(resolvedClause, availableToolNames, lastActionSubject)?.let {
                    toolCalls += it
                    subjectForPronouns(it)?.let { subject -> lastActionSubject = subject }
                    return@forEach
                }

                inferToolCallFromModelFailure(resolvedClause, availableToolNames)?.let {
                    if (it == lastTimedHoldInverse) return@forEach
                    toolCalls += it
                    subjectForPronouns(it)?.let { subject -> lastActionSubject = subject }
                }
            }

            if (toolCalls.size < 2 && responseParts.isEmpty()) return null
            if (toolCalls.isEmpty()) return null

            return DirectActionPlan(
                toolCalls = toolCalls,
                response = buildDirectActionPlanResponse(toolCalls, responseParts)
            )
        }

        private fun splitActionClauses(userMessage: String): List<String> {
            val withImplicitTellSeparator = Regex("""(?is)\b((?:flashlight|torch|wifi|wi-fi|bluetooth|volume|media|playback|brightness|alarm|timer))\s+(?=(?:tell|say)\b)""")
                .replace(userMessage) { "${it.groupValues[1]} then " }
            return withImplicitTellSeparator
                .split(Regex("""(?is)\s*(?:,|;|\bthen\b|\band then\b|\band\b)\s*"""))
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }

        private fun parseDelayedActionClause(
            clause: String,
            availableToolNames: Set<String>,
            lastActionSubject: ToolCall? = null
        ): ToolCall? {
            if ("schedule_action" !in availableToolNames) return null

            val prefixDelay = Regex("""(?is)^\s*(?:after|in)\s+($DURATION_PATTERN)\s+(.+?)\s*$""")
                .find(clause)
                ?.let { it.groupValues[1].trim() to it.groupValues[2].trim() }

            val suffixDelay = Regex("""(?is)^\s*(.+?)\s+(?:after|in)\s+($DURATION_PATTERN)\s*$""")
                .find(clause)
                ?.let { it.groupValues[2].trim() to it.groupValues[1].trim() }

            val embeddedDelay = Regex("""(?is)^\s*(.+?)\s+(?:after|in)\s+($DURATION_PATTERN)\s+(.+?)\s*$""")
                .find(clause)
                ?.let {
                    val actionText = "${it.groupValues[1].trim()} ${it.groupValues[3].trim()}".trim()
                    it.groupValues[2].trim() to actionText
                }

            val (durationSpec, actionText) = prefixDelay ?: suffixDelay ?: embeddedDelay ?: return null
            val seconds = parseDurationSeconds(durationSpec) ?: return null
            val resolvedActionText = resolvePronounClause(actionText, lastActionSubject)
            val actionTool = inferToolCallFromModelFailure(
                resolvedActionText,
                availableToolNames - "schedule_action"
            ) ?: return null

            return scheduledToolCall(actionTool, seconds)
        }

        private fun scheduledToolCall(actionTool: ToolCall, seconds: Int): ToolCall {
            return ToolCall(
                toolName = "schedule_action",
                arguments = mapOf(
                    "title" to titleForScheduledTool(actionTool),
                    "instruction" to "Run ${actionTool.toolName} after $seconds seconds.",
                    "delay_seconds" to seconds,
                    "tool_name" to actionTool.toolName,
                    "tool_arguments" to actionTool.arguments
                )
            )
        }

        private fun parseTimedHoldClause(
            clause: String,
            availableToolNames: Set<String>
        ): TimedHoldPlan? {
            if ("schedule_action" !in availableToolNames) return null
            val match = Regex("""(?is)^\s*(.+?)\s+for\s+($DURATION_PATTERN)\s*$""")
                .find(clause) ?: return null

            val actionText = match.groupValues[1].trim()
            val seconds = parseDurationSeconds(match.groupValues[2].trim()) ?: return null
            val immediate = inferToolCallFromModelFailure(
                actionText,
                availableToolNames - "schedule_action"
            ) ?: return null
            val inverse = inverseToolCallForTimedHold(immediate, availableToolNames) ?: return null

            return TimedHoldPlan(
                toolCalls = listOf(immediate, scheduledToolCall(inverse, seconds)),
                subject = immediate
            )
        }

        private fun inverseToolCallForTimedHold(
            actionTool: ToolCall,
            availableToolNames: Set<String>
        ): ToolCall? {
            return when (actionTool.toolName) {
                "toggle_flashlight" -> {
                    val enabled = actionTool.arguments["enabled"] as? Boolean ?: return null
                    ToolCall("toggle_flashlight", mapOf("enabled" to !enabled))
                }
                "mute" -> {
                    val enabled = actionTool.arguments["enabled"] as? Boolean ?: return null
                    ToolCall("mute", mapOf("enabled" to !enabled))
                }
                "play_media" -> if ("pause_media" in availableToolNames) ToolCall("pause_media", emptyMap()) else null
                "pause_media" -> if ("play_media" in availableToolNames) ToolCall("play_media", emptyMap()) else null
                else -> null
            }
        }

        private fun resolvePronounClause(clause: String, lastActionSubject: ToolCall?): String {
            val subject = lastActionSubject ?: return clause
            return when (subject.toolName) {
                "toggle_flashlight" -> Regex("""(?is)^\s*(?:turn\s+)?it\s+(on|off)(?:\s+again)?(.*)$""")
                    .find(clause)
                    ?.let { "turn flashlight ${it.groupValues[1]}${it.groupValues[2]}" }
                    ?: clause
                "play_media", "pause_media" -> when {
                    Regex("""(?is)^\s*(?:pause|stop)\s+it(?:\s+again)?\s*$""").matches(clause) -> "pause media"
                    Regex("""(?is)^\s*(?:play|resume)\s+it(?:\s+again)?\s*$""").matches(clause) -> "play media"
                    else -> clause
                }
                else -> clause
            }
        }

        private fun subjectForPronouns(toolCall: ToolCall): ToolCall? {
            val subject = if (toolCall.toolName == "schedule_action") {
                scheduledInnerToolCall(toolCall)
            } else {
                toolCall
            } ?: return null
            return subject
                .takeIf { inverseToolCallForTimedHold(it, DIRECT_RESULT_TOOL_NAMES) != null }
        }

        private fun scheduledInnerToolCall(toolCall: ToolCall): ToolCall? {
            if (toolCall.toolName != "schedule_action") return null
            val scheduledToolName = toolCall.arguments["tool_name"]?.toString() ?: return null
            val scheduledArguments = (toolCall.arguments["tool_arguments"] as? Map<*, *>)
                ?.mapNotNull { (key, value) ->
                    if (key != null && value != null) key.toString() to value else null
                }
                ?.toMap()
                ?: emptyMap<String, Any>()
            return ToolCall(scheduledToolName, scheduledArguments)
        }

        private fun parseConversationalClause(clause: String): String? {
            val normalized = clause.trim().lowercase(Locale.US)
            if (!Regex("""(?is)^(?:tell\s+(?:me\s+)?a\s+joke|say\s+something\s+funny|make\s+me\s+laugh)\b""")
                    .containsMatchIn(normalized)
            ) {
                return null
            }
            return "Why don't scientists trust atoms? Because they make up everything."
        }

        private fun buildDirectActionPlanResponse(
            toolCalls: List<ToolCall>,
            responseParts: List<String>
        ): String {
            val actionSummaries = toolCalls.map { call ->
                when (call.toolName) {
                    "schedule_action" -> {
                        val delay = call.arguments["delay_seconds"]?.toString()?.toDoubleOrNull()?.toInt()
                        val tool = call.arguments["tool_name"]?.toString().orEmpty()
                        if (delay != null && tool.isNotBlank()) {
                            "I scheduled $tool in $delay seconds."
                        } else {
                            "I scheduled the requested action."
                        }
                    }
                    "toggle_flashlight" -> {
                        val enabled = call.arguments["enabled"] as? Boolean
                        if (enabled == false) "Flashlight turned off." else "Flashlight turned on."
                    }
                    else -> "I ran ${call.toolName}."
                }
            }
            return (actionSummaries + responseParts).joinToString(" ")
        }

        private fun titleForScheduledTool(toolCall: ToolCall): String {
            if (toolCall.toolName == "toggle_flashlight") {
                val enabled = toolCall.arguments["enabled"] as? Boolean
                return if (enabled == false) "Turn flashlight off" else "Turn flashlight on"
            }
            return "Run ${toolCall.toolName}"
        }

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

            if ("schedule_action" in availableToolNames && "toggle_flashlight" in availableToolNames) {
                parseScheduledFlashlightFallback(normalized)?.let { return it }
            }

            if ("schedule_action" in availableToolNames) {
                parseDelayedActionClause(normalized, availableToolNames)?.let { return it }
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
                Regex("""(?is)^\s*(?:open\s+url|open\s+link|open)\s+((?:https?://|www\.)\S+)\s*$""").find(normalized)?.groupValues?.getOrNull(1)?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { url ->
                        return ToolCall("open_url", mapOf("url" to url))
                    }
            }

            if ("open_app" in availableToolNames || "launch_package" in availableToolNames) {
                Regex("""(?is)^\s*(?:open|launch)(?:\s+(?:app|application|package))?\s+(.+?)\s*$""").find(normalized)?.groupValues?.getOrNull(1)?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { appName ->
                        val toolName = if ("open_app" in availableToolNames) "open_app" else "launch_package"
                        return ToolCall(toolName, mapOf("app_name" to appName))
                    }
            }

            val screenReadText = normalized.lowercase(Locale.US)
            if ("read_screen" in availableToolNames &&
                "screen" in screenReadText &&
                listOf("read", "describe", "what", "tell me", "inspect").any(screenReadText::contains)
            ) {
                return ToolCall("read_screen", emptyMap())
            }

            val uiGoalText = normalized.lowercase(Locale.US)
            if (UiAutomationOrchestrator.CONTROL_UI_TOOL in availableToolNames &&
                listOf(
                    "on this screen",
                    "current screen",
                    "tap ",
                    "press ",
                    "scroll ",
                    "dark mode",
                    "visible toggle",
                    "visible button"
                ).any(uiGoalText::contains)
            ) {
                return ToolCall(
                    UiAutomationOrchestrator.CONTROL_UI_TOOL,
                    mapOf("goal" to normalized)
                )
            }

            if ("send_sms" in availableToolNames) {
                parseSmsFallback(normalized)?.let { return it }
            }

            if ("place_call" in availableToolNames) {
                parseCallFallback(normalized)?.let { return it }
            }

            if ("navigate_to" in availableToolNames) {
                Regex("""(?is)^\s*(?:navigate\s+to|directions\s+to|route\s+to)\s+(.+?)\s*$""")
                    .find(normalized)?.groupValues?.getOrNull(1)?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return ToolCall("navigate_to", mapOf("destination" to it)) }
            }

            if ("take_photo" in availableToolNames &&
                Regex("""(?is)^\s*(?:take\s+a\s+photo|take\s+photo|open\s+camera|camera)\s*$""").containsMatchIn(normalized)
            ) {
                return ToolCall("take_photo", emptyMap())
            }

            if ("record_video" in availableToolNames &&
                Regex("""(?is)^\s*(?:record\s+a\s+video|record\s+video|take\s+a\s+video|take\s+video)\s*$""").containsMatchIn(normalized)
            ) {
                return ToolCall("record_video", emptyMap())
            }

            if ("toggle_wifi" in availableToolNames) {
                Regex("""(?is)^\s*(?:turn\s+)?(?:wifi|wi-fi)(?:\s+(on|off))?\s*$""")
                    .find(normalized)?.let { match ->
                        return ToolCall(
                            "toggle_wifi",
                            match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                                ?.let { mapOf("enabled" to (it.equals("on", ignoreCase = true))) }
                                ?: emptyMap()
                        )
                    }
            }

            if ("toggle_bluetooth" in availableToolNames) {
                Regex("""(?is)^\s*(?:turn\s+)?bluetooth(?:\s+(on|off))?\s*$""")
                    .find(normalized)?.let { match ->
                        return ToolCall(
                            "toggle_bluetooth",
                            match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                                ?.let { mapOf("enabled" to (it.equals("on", ignoreCase = true))) }
                                ?: emptyMap()
                        )
                    }
            }

            if ("share_text" in availableToolNames) {
                Regex("""(?is)^\s*(?:share\s+text|share)\s+(.+?)\s*$""")
                    .find(normalized)?.groupValues?.getOrNull(1)?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return ToolCall("share_text", mapOf("text" to it)) }
            }

            if ("set_volume" in availableToolNames) {
                Regex("""(?is)^\s*(?:set\s+)?(?:media\s+)?volume(?:\s+to)?\s+(\d{1,3})(?:\s*%)?(?:\s+(music|media|ring|ringer|alarm|notification|notifications))?\s*$""")
                    .find(normalized)?.let { match ->
                        val level = match.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: return@let
                        val stream = match.groupValues.getOrNull(2)?.trim().orEmpty()
                        return ToolCall(
                            "set_volume",
                            buildMap {
                                put("level", level)
                                if (stream.isNotBlank()) put("stream", stream)
                            }
                        )
                    }
            }

            if ("mute" in availableToolNames) {
                Regex("""(?is)^\s*(mute|unmute)(?:\s+(?:media|volume))?\s*$""")
                    .find(normalized)?.let { match ->
                        return ToolCall("mute", mapOf("enabled" to match.groupValues[1].equals("mute", ignoreCase = true)))
                    }
            }

            if ("play_media" in availableToolNames && Regex("""(?is)^\s*(?:play|resume)(?:\s+media|\s+playback)?\s*$""").containsMatchIn(normalized)) {
                return ToolCall("play_media", emptyMap())
            }

            if ("pause_media" in availableToolNames && Regex("""(?is)^\s*pause(?:\s+media|\s+playback)?\s*$""").containsMatchIn(normalized)) {
                return ToolCall("pause_media", emptyMap())
            }

            if ("next_track" in availableToolNames && Regex("""(?is)^\s*(?:next\s+track|skip\s+track|skip\s+song)\s*$""").containsMatchIn(normalized)) {
                return ToolCall("next_track", emptyMap())
            }

            if ("set_brightness" in availableToolNames) {
                Regex("""(?is)^\s*(?:set\s+)?brightness(?:\s+to)?\s+(\d{1,3})(?:\s*%)?\s*$""")
                    .find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?.let { return ToolCall("set_brightness", mapOf("level" to it.coerceIn(0, 100))) }
            }

            if ("toggle_flashlight" in availableToolNames) {
                Regex(
                    """(?is)^\s*(?:turn\s+)?(?:(on|off)\s+(?:my\s+|the\s+)?(?:flashlight|torch)|(?:my\s+|the\s+)?(?:flashlight|torch)(?:\s+(on|off))?)(?:\s+(?:right\s+now|now))?\s*$"""
                )
                    .find(normalized)?.let { match ->
                        val enabled = listOf(
                            match.groupValues.getOrNull(1),
                            match.groupValues.getOrNull(2)
                        ).firstOrNull { !it.isNullOrBlank() }?.trim()
                            ?.equals("on", ignoreCase = true)
                            ?: true
                        return ToolCall("toggle_flashlight", mapOf("enabled" to enabled))
                    }
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

        private fun parseScheduledFlashlightFallback(normalized: String): ToolCall? {
            val match = Regex(
                pattern = """(?is)^\s*(?:turn\s+)?(?:(on|off)\s+(?:my\s+)?(?:flashlight|torch)|(?:my\s+)?(?:flashlight|torch)\s+(on|off))\s+(?:after|in)\s+(.+?)\s*$"""
            ).find(normalized) ?: return null

            val state = listOf(match.groupValues[1], match.groupValues[2])
                .firstOrNull { it.isNotBlank() }
                ?.lowercase()
                ?: return null
            val durationSpec = match.groupValues[3].trim()
            val seconds = parseDurationSeconds(durationSpec) ?: return null
            val enabled = state == "on"
            val title = if (enabled) "Turn flashlight on" else "Turn flashlight off"

            return ToolCall(
                toolName = "schedule_action",
                arguments = mapOf(
                    "title" to title,
                    "instruction" to "$title after $seconds seconds.",
                    "delay_seconds" to seconds,
                    "tool_name" to "toggle_flashlight",
                    "tool_arguments" to mapOf("enabled" to enabled)
                )
            )
        }

        private fun parseSmsFallback(normalized: String): ToolCall? {
            val smsVerb = """(?:send\s+sms|send\s+a\s+text|send\s+text|text|compose\s+(?:a\s+)?text|draft\s+(?:a\s+)?text)"""

            Regex("""(?is)^\s*$smsVerb\s+to\s+([+()\-\d\s]+?)(?:\s+(?:saying|message(?:\s+that)?|that\s+says)\s+(.+?))?\s*$""")
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

            Regex("""(?is)^\s*$smsVerb\s+(?:saying|message(?:\s+that)?|that\s+says)\s+(.+?)\s+to\s+([+()\-\d\s]+?)\s*$""")
                .find(normalized)
                ?.let { match ->
                    val message = match.groupValues[1].trim()
                    val phoneNumber = match.groupValues[2].trim()
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
                Regex("""(?is)^\s*$smsVerb\s+to\s+(.+?)\s+(?:saying|message(?:\s+that)?|that\s+says)\s+(.+?)\s*$""").find(normalized),
                Regex("""(?is)^\s*$smsVerb\s+to\s+(.+?)\s*,\s*(.+?)\s*$""").find(normalized),
                Regex("""(?is)^\s*$smsVerb\s+to\s+(.+?)\s*:\s*(.+?)\s*$""").find(normalized)
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

            Regex("""(?is)^\s*$smsVerb\s+to\s+(.+?)\s*$""").find(normalized)
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
            val unitRegex = Regex("""(?is)(\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty)\s*(hour|hours|hr|hrs|minute|minutes|min|mins|second|seconds|sec|secs)\b""")
            val matches = unitRegex.findAll(durationSpec).toList()
            if (matches.isEmpty()) return null

            var totalSeconds = 0
            for (match in matches) {
                val amount = parseDurationAmount(match.groupValues[1]) ?: return null
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

        private fun parseDurationAmount(value: String): Int? {
            value.toIntOrNull()?.let { return it }
            return when (value.lowercase()) {
                "one" -> 1
                "two" -> 2
                "three" -> 3
                "four" -> 4
                "five" -> 5
                "six" -> 6
                "seven" -> 7
                "eight" -> 8
                "nine" -> 9
                "ten" -> 10
                "eleven" -> 11
                "twelve" -> 12
                "thirteen" -> 13
                "fourteen" -> 14
                "fifteen" -> 15
                "sixteen" -> 16
                "seventeen" -> 17
                "eighteen" -> 18
                "nineteen" -> 19
                "twenty" -> 20
                "thirty" -> 30
                "forty" -> 40
                "fifty" -> 50
                "sixty" -> 60
                else -> null
            }
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

    private val _isInitializing = MutableStateFlow(false)
    val isInitializing = _isInitializing.asStateFlow()

    private val _pendingToolApproval = MutableStateFlow<ToolCall?>(null)
    val pendingToolApproval = _pendingToolApproval.asStateFlow()
    private var pendingToolApprovalDeferred: CompletableDeferred<Boolean>? = null

    private val _pendingAppSelection = MutableStateFlow<AppSelectionRequest?>(null)
    val pendingAppSelection = _pendingAppSelection.asStateFlow()
    private var pendingAppSelectionDeferred: CompletableDeferred<String?>? = null

    private val _pendingUiActionConfirmation = MutableStateFlow<UiActionConfirmationRequest?>(null)
    val pendingUiActionConfirmation = _pendingUiActionConfirmation.asStateFlow()
    private var pendingUiActionConfirmationDeferred: CompletableDeferred<Boolean>? = null

    private val _orchestration = MutableStateFlow<OrchestrationUiState?>(null)
    val orchestration = _orchestration.asStateFlow()

    fun resolveToolApproval(allow: Boolean) {
        pendingToolApprovalDeferred?.complete(allow)
        _pendingToolApproval.value = null
    }

    fun resolveAppSelection(packageName: String?) {
        pendingAppSelectionDeferred?.complete(packageName)
        pendingAppSelectionDeferred = null
        _pendingAppSelection.value = null
    }

    fun resolveUiActionConfirmation(allow: Boolean) {
        pendingUiActionConfirmationDeferred?.complete(allow)
        pendingUiActionConfirmationDeferred = null
        _pendingUiActionConfirmation.value = null
    }

    fun dismissOrchestration() {
        if (_orchestration.value?.isRunning != true) {
            _orchestration.value = null
        }
    }

    private fun startOrchestration(title: String, status: String, detail: String) {
        _orchestration.value = OrchestrationUiState(
            title = title,
            status = status,
            entries = listOf(ActionTraceEntry("Intent", detail)),
            isRunning = true
        )
    }

    private fun recordOrchestration(
        phase: String,
        detail: String,
        toolName: String? = null,
        output: String? = null,
        isError: Boolean = false
    ) {
        val current = _orchestration.value ?: return
        _orchestration.value = current.copy(
            status = detail,
            entries = current.entries + ActionTraceEntry(phase, detail, toolName, output, isError)
        )
    }

    private fun finishOrchestration(status: String, isError: Boolean = false) {
        val current = _orchestration.value ?: return
        _orchestration.value = current.copy(
            status = status,
            entries = current.entries + ActionTraceEntry(
                phase = if (isError) "Failed" else "Complete",
                detail = status,
                isError = isError
            ),
            isRunning = false
        )
    }

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

    private val _systemPromptFavorites =
        MutableStateFlow(SettingsManager.getChatSystemPromptFavorites(context))
    val systemPromptFavorites = _systemPromptFavorites.asStateFlow()

    private data class QueuedAudio(val index: Int, val audio: FloatArray, val sampleRate: Int)

    private val audioQueue = Channel<QueuedAudio>(Channel.UNLIMITED)
    private val pendingPlaybackAudio = mutableMapOf<Int, QueuedAudio>()
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
            if (_ttsEnabled.value) {
                OnnxRuntimeManager.initialize(context.applicationContext)
            } else {
                DebugLogger.log("ChatViewModel: TTS disabled; skipping runtime initialization")
            }
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
                pendingPlaybackAudio[item.index] = item
                while (pendingPlaybackAudio.containsKey(nextPlaybackIndex)) {
                    val queued = pendingPlaybackAudio.remove(nextPlaybackIndex)!!
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

        refreshConversations(preferredModelId = requestedInitialModelId)
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
            viewModelScope.launch(Dispatchers.IO) {
                ttsMutex.withLock {
                    TTSManager.close()
                    OnnxRuntimeManager.close()
                }
            }
        } else {
            dropQueuedAudio = false
        }
    }

    fun stopPlayback() {
        dropQueuedAudio = true
        pendingPlaybackAudio.clear()
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
        val image = _pendingImage.value
        val audio = _pendingAudioInput.value
        if (trimmed.isEmpty() && image == null && audio == null) return
        val conversationId = _activeConversationId.value ?: run {
            DebugLogger.log("No active conversation; ignoring message")
            return
        }
        val availableToolNames = ToolRegistry.tools.value
            .filter { it.isAvailable }
            .map { it.name }
            .toSet()
        val explicitToolCall = ToolCallProtocol.parseDirectUserToolCommand(trimmed)
        val inferredToolCall = if (explicitToolCall == null && image == null && audio == null) {
            inferToolCallFromModelFailure(trimmed, availableToolNames)
        } else {
            null
        }
        val directToolCall = explicitToolCall ?: inferredToolCall?.takeUnless { it.toolName == "read_screen" }

        dropQueuedAudio = false
        _orchestration.value = null
        DebugLogger.log("ChatViewModel sendMessage: $trimmed (hasImage=${image != null}, hasAudio=${audio != null})")
        _chatMessages.value += ChatMessage(trimmed, true, image, audio)
        
        val imagePath = image?.let { saveImageToInternalStorage(it.bitmap) }
        val audioPath = audio?.let { saveAudioToInternalStorage(it) }
        conversationHistory.add(ConversationTurn(ConversationRole.USER, trimmed, imagePath, audioPath, audio?.displayName))
        
        _pendingImage.value = null
        _pendingAudioInput.value = null
        persistConversationMessages()
        _isLoading.value = true

        val benchmarkEnabled = SettingsManager.isBenchmark(context)
        if (benchmarkEnabled) {
            BenchmarkManager.startLlm()
        }

        val sentenceBuilder = StringBuilder()
        var hasStreamedAudio = false
        _chatMessages.value += ChatMessage("...", false) // placeholder

        val directActionPlan = if (image == null && audio == null) {
            planDirectActionChain(trimmed, availableToolNames)
        } else {
            null
        }
        if (directActionPlan != null) {
            startOrchestration(
                title = "Model Orchestration",
                status = "Preparing action chain",
                detail = trimmed
            )
            viewModelScope.launch(Dispatchers.IO) {
                DebugLogger.log(
                    "ChatViewModel: executing direct action plan with " +
                        directActionPlan.toolCalls.joinToString(",") { it.toolName }
                )
                recordOrchestration(
                    "Plan",
                    directActionPlan.toolCalls.joinToString(" -> ") { it.toolName }
                )
                val results = directActionPlan.toolCalls.mapIndexed { index, call ->
                    recordOrchestration("Execute ${index + 1}", "Running ${call.toolName}", call.toolName)
                    executeToolCallInternal(context.applicationContext, call).also { result ->
                        recordOrchestration(
                            "Result ${index + 1}",
                            if (result.isError) "${call.toolName} failed" else "${call.toolName} completed",
                            call.toolName,
                            result.output,
                            result.isError
                        )
                    }
                }
                val firstError = results.firstOrNull { it.isError }
                val response = if (firstError != null) {
                    firstError.output
                } else {
                    directActionPlan.response
                }
                finalizeDirectToolResponse(
                    response,
                    buildActionTrace(
                        title = "Deterministic Action Chain",
                        source = "direct_chain_parser",
                        toolCalls = directActionPlan.toolCalls,
                        results = results
                    )
                )
            }
            return
        }

        if (directToolCall != null) {
            startOrchestration(
                title = "Model Orchestration",
                status = "Preparing tool call",
                detail = trimmed
            )
            viewModelScope.launch(Dispatchers.IO) {
                DebugLogger.log(
                    "ChatViewModel: executing direct tool command ${directToolCall.toolName} with ${directToolCall.arguments}"
                )
                recordOrchestration("Execute", "Running ${directToolCall.toolName}", directToolCall.toolName)
                val result = executeToolCallInternal(context.applicationContext, directToolCall)
                recordOrchestration(
                    "Result",
                    if (result.isError) "${directToolCall.toolName} failed" else "${directToolCall.toolName} completed",
                    directToolCall.toolName,
                    result.output,
                    result.isError
                )
                finalizeDirectToolResponse(
                    summarizeToolResultMessage(result),
                    buildActionTrace(
                        title = "Direct Tool Command",
                        source = "direct_tool_parser",
                        toolCalls = listOf(directToolCall),
                        results = listOf(result)
                    )
                )
            }
            return
        }

        val backend = llmBackend ?: run {
            DebugLogger.log("No LLM backend available; ignoring message")
            finalizeDirectToolResponse("No LLM backend available.")
            return
        }
        if (audio != null && !backend.supportsAudioInput()) {
            val modelName = _activeModel.value?.name ?: "selected model"
            DebugLogger.log("ChatViewModel: $modelName does not support audio input")
            finalizeDirectToolResponse(
                "$modelName does not support audio input. Select Gemma 4 E2B IT or send text instead."
            )
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
            fun updateAssistantPlaceholder(content: String) {
                viewModelScope.launch {
                    val last = _chatMessages.value.lastOrNull()
                    if (last != null) {
                        _chatMessages.value =
                            _chatMessages.value.dropLast(1) + last.copy(message = content)
                    }
                }
            }

            fun finalizeAssistantResponse(
                finalResponse: String,
                speakOutput: Boolean,
                actionTrace: ActionTrace? = null
            ) {
                viewModelScope.launch {
                    _isLoading.value = false
                    finishOrchestration("Response ready")
                    DebugLogger.log("ChatViewModel response complete")
                    val last = _chatMessages.value.lastOrNull()
                    if (last != null) {
                        _chatMessages.value =
                            _chatMessages.value.dropLast(1) + last.copy(
                                message = finalResponse,
                                actionTrace = actionTrace
                            )
                    }
                    val historyContent = if (actionTrace != null && actionTrace.entries.isNotEmpty()) {
                        val traceString = actionTrace.entries.joinToString("\n") { "${it.phase}: ${it.detail}" }
                        "[Tool Execution Trace:\n$traceString]\n$finalResponse"
                    } else {
                        finalResponse
                    }
                    conversationHistory.add(ConversationTurn(ConversationRole.AGENT, historyContent))
                    persistConversationMessages()
                    if (speakOutput) {
                        if (!hasStreamedAudio && sentenceBuilder.isBlank()) {
                            sentenceBuilder.append(finalResponse)
                        }
                        processSentences(sentenceBuilder, true)
                    } else {
                        sentenceBuilder.clear()
                        dropQueuedAudio = false
                    }
                }
            }

            val actionPlannerTools = if (image == null && audio == null) {
                selectPromptToolsForConversation(
                    conversationHistory,
                    ToolRegistry.tools.value.filter { it.isAvailable }
                )
            } else {
                emptyList()
            }
            if (ActionPlanner.shouldUseActionPlanner(trimmed, actionPlannerTools)) {
                startOrchestration(
                    title = "Model Orchestration",
                    status = "Selecting actions",
                    detail = trimmed
                )
                updateAssistantPlaceholder("Planning actions...")
                recordOrchestration(
                    "Planner",
                    "Considering ${actionPlannerTools.joinToString(", ") { it.name }}"
                )
                DebugLogger.log(
                    "ChatViewModel: action planner selected tools=${actionPlannerTools.joinToString(",") { it.name }}"
                )
                val actionPlan = ActionPlanner.planWithModel(
                    backend = backend,
                    userMessage = trimmed,
                    selectedTools = actionPlannerTools,
                    recentContext = conversationHistory
                        .dropLast(1)
                        .takeLast(4)
                        .map { turn ->
                            LlmMessage(
                                role = if (turn.role == ConversationRole.USER) "user" else "model",
                                content = turn.content
                            )
                        }
                )
                if (actionPlan != null) {
                    DebugLogger.log(
                        "ChatViewModel: executing ${actionPlan.source} with " +
                            actionPlan.toolCalls.joinToString(",") { it.toolName }
                    )
                    if (actionPlan.toolCalls.isEmpty()) {
                        recordOrchestration("Decision", "Planner requested confirmation or no execution")
                        finalizeAssistantResponse(
                            actionPlan.response,
                            speakOutput = true,
                            actionTrace = ActionTrace(
                                title = "Action Planner",
                                entries = listOf(
                                    ActionTraceEntry("Intent", "Action-shaped request detected."),
                                    ActionTraceEntry("Planner", "Planner source=${actionPlan.source}."),
                                    ActionTraceEntry("Decision", "Planner requested confirmation or produced no executable steps.")
                                )
                            )
                        )
                        return@launch
                    }
                    recordOrchestration(
                        "Plan",
                        actionPlan.toolCalls.joinToString(" -> ") { it.toolName }
                    )
                    val results = actionPlan.toolCalls.mapIndexed { index, call ->
                        recordOrchestration("Execute ${index + 1}", "Running ${call.toolName}", call.toolName)
                        executeToolCallInternal(appContext, call).also { result ->
                            recordOrchestration(
                                "Result ${index + 1}",
                                if (result.isError) "${call.toolName} failed" else "${call.toolName} completed",
                                call.toolName,
                                result.output,
                                result.isError
                            )
                        }
                    }
                    val firstError = results.firstOrNull { it.isError }
                    val response = if (firstError != null) {
                        summarizeToolResultMessage(firstError)
                    } else {
                        actionPlan.response
                    }
                    finalizeAssistantResponse(
                        response,
                        speakOutput = true,
                        actionTrace = buildActionTrace(
                            title = "Action Planner",
                            source = actionPlan.source,
                            toolCalls = actionPlan.toolCalls,
                            results = results
                        )
                    )
                    return@launch
                }
                DebugLogger.log("ChatViewModel: action planner did not return an executable plan; falling back to chat agent")
                recordOrchestration("Fallback", "Action planner returned no executable plan; using agent loop")
            }

            AgentTurnRunner(
                backend = backend,
                scope = viewModelScope,
                toolExecutor = { toolCall ->
                    withContext(Dispatchers.IO) {
                        executeToolCallInternal(appContext, toolCall).also { result ->
                            if (_orchestration.value == null) {
                                startOrchestration("Model Orchestration", "Analyzing tool result", trimmed)
                            }
                            recordOrchestration(
                                "Tool result",
                                if (result.isError) "${toolCall.toolName} failed" else "${toolCall.toolName} completed",
                                toolCall.toolName,
                                result.output,
                                result.isError
                            )
                        }
                    }
                },
                inferToolCallFromModelFailure = Companion::inferToolCallFromModelFailure,
                recoveryConversationProvider = {
                    prepareConversationForModel(
                        maxTokens = backendMaxTokens,
                        forceSingleTurn = true,
                        recoveryMode = true
                    )
                },
                shouldCompleteAfterToolResult = { call, _ ->
                    call.toolName in DIRECT_RESULT_TOOL_NAMES &&
                        !(call.toolName == "toggle_flashlight" && looksLikeDeferredActionRequest(trimmed))
                },
                logger = { DebugLogger.log(it) }
            ).run(
                initialConversation = conversationForModel,
                latestUserMessage = trimmed,
                availableToolNames = availableToolNames,
                maxToolCalls = MAX_TOOL_CALLS_PER_TURN,
                onPartialText = ::updateAssistantPlaceholder,
                onSpeakablePartial = { partial, done ->
                    hasStreamedAudio = true
                    sentenceBuilder.append(partial)
                    if (!done) {
                        processSentences(sentenceBuilder, false)
                    }
                },
                onSuppressSpeakablePartials = {
                    sentenceBuilder.clear()
                },
                onToolStart = { toolCall ->
                    if (_orchestration.value == null && toolCall.toolName != "read_screen") {
                        startOrchestration("Model Orchestration", "Executing tool", trimmed)
                    }
                    if (toolCall.toolName != "read_screen") {
                        recordOrchestration("Tool call", "Running ${toolCall.toolName}", toolCall.toolName)
                    }
                    updateAssistantPlaceholder("Running tool ${toolCall.toolName}...")
                },
                onBenchmarkPartial = { partial ->
                    if (benchmarkEnabled) {
                        BenchmarkManager.recordPartial(partial)
                    }
                },
                onRecoveryRetry = {
                    updateAssistantPlaceholder("Retrying with compacted context...")
                },
                onComplete = { result ->
                    if (benchmarkEnabled) {
                        BenchmarkManager.finishLlm()
                        BenchmarkManager.profileSystem(context)
                    }
                    finalizeAssistantResponse(
                        finalResponse = result.finalResponse,
                        speakOutput = result.speakOutput,
                        actionTrace = buildAgentTrace(result.transcript)
                    )
                }
            )
        }
    }

    fun cancelGeneration() {
        recordOrchestration("Cancel", "Stopping model and tool execution")
        llmBackend?.cancel()
    }

    private fun refreshConversations(
        desiredActiveId: Long? = null,
        preferredModelId: String? = null
    ) {
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
            val preferredResolvedModel = preferredModelId?.let(::resolveModelById)
            if (preferredResolvedModel != null) {
                withContext(Dispatchers.Main) {
                    setActiveModel(preferredResolvedModel, persistConversation = false)
                }
            } else {
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
            _chatMessages.value = conversation.messages.map { turn ->
                val image = if (!turn.imagePath.isNullOrBlank()) {
                    loadBitmapFromPath(turn.imagePath)?.let { LlmImageInput(it) }
                } else null
                val audio = if (!turn.audioPath.isNullOrBlank()) {
                    loadAudioFromPath(turn.audioPath, turn.audioName)
                } else null
                
                var displayMessage = turn.content
                var parsedActionTrace: ActionTrace? = null
                
                if (turn.role == ConversationRole.AGENT && displayMessage.startsWith("[Tool Execution Trace:\n")) {
                    val endBracketIdx = displayMessage.indexOf("]\n")
                    if (endBracketIdx != -1) {
                        val traceBlock = displayMessage.substring("[Tool Execution Trace:\n".length, endBracketIdx)
                        displayMessage = displayMessage.substring(endBracketIdx + 2).trimStart()
                        
                        val entries = traceBlock.lines().mapNotNull { line ->
                            val colonIdx = line.indexOf(":")
                            if (colonIdx != -1) {
                                val phase = line.substring(0, colonIdx).trim()
                                val detail = line.substring(colonIdx + 1).trim()
                                ActionTraceEntry(phase, detail)
                            } else null
                        }
                        if (entries.isNotEmpty()) {
                            parsedActionTrace = ActionTrace(title = "Restored Trace", entries = entries)
                        }
                    }
                }
                
                ChatMessage(displayMessage, turn.role == ConversationRole.USER, image, audio, parsedActionTrace)
            }
        } else {
            _chatMessages.value = emptyList()
        }
        clearPendingAudio()
    }

    private fun clearPendingAudio() {
        lineIndex = 0
        nextPlaybackIndex = 0
        pendingPlaybackAudio.clear()
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

        val created = LlmBackendFactory.create(
            context = context,
            modelId = model.id,
            llmOverrides = llmOverrides,
            initializeSynchronously = false
        )
        if (created == null) {
            llmBackend = null
            return
        }
        _activeModel.value = model
        llmBackend?.close()
        llmBackend = created.backend
        if (created.backend is LlamaCppBackend) {
            _isInitializing.value = true
            viewModelScope.launch(Dispatchers.IO) {
                created.backend.initialize()
                _isInitializing.value = false
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

    fun saveCurrentSystemPromptFavorite() {
        val prompt = _systemPrompt.value.trim()
        if (prompt.isEmpty()) return
        val updated = (_systemPromptFavorites.value.filterNot { it.equals(prompt, ignoreCase = true) } + prompt)
        _systemPromptFavorites.value = updated
        SettingsManager.setChatSystemPromptFavorites(context, updated)
    }

    fun applySystemPromptFavorite(prompt: String) {
        updateSystemPrompt(prompt)
    }

    fun deleteSystemPromptFavorite(prompt: String) {
        val updated = _systemPromptFavorites.value.filterNot { it.equals(prompt, ignoreCase = true) }
        _systemPromptFavorites.value = updated
        SettingsManager.setChatSystemPromptFavorites(context, updated)
    }

    fun editMessage(index: Int, content: String) {
        if (index !in conversationHistory.indices) return
        val updated = content.trim()
        if (updated.isEmpty()) return
        val turn = conversationHistory[index]
        conversationHistory[index] = turn.copy(content = updated)
        _chatMessages.value = _chatMessages.value.mapIndexed { messageIndex, message ->
            if (messageIndex == index) message.copy(message = updated) else message
        }
        persistConversationMessages()
    }

    fun regenerateFrom(index: Int) {
        if (conversationHistory.isEmpty()) return
        val userIndex = when {
            index in conversationHistory.indices && conversationHistory[index].role == ConversationRole.USER -> index
            index in conversationHistory.indices -> conversationHistory
                .subList(0, index + 1)
                .indexOfLast { it.role == ConversationRole.USER }
            else -> conversationHistory.indexOfLast { it.role == ConversationRole.USER }
        }
        if (userIndex < 0) return
        val userTurn = conversationHistory[userIndex]
        val image = userTurn.imagePath?.let { path -> loadBitmapFromPath(path)?.let { LlmImageInput(it) } }
        val audio = userTurn.audioPath?.let { path -> loadAudioFromPath(path, userTurn.audioName) }
        while (conversationHistory.size > userIndex) {
            conversationHistory.removeAt(conversationHistory.lastIndex)
        }
        _chatMessages.value = _chatMessages.value.take(userIndex)
        _pendingImage.value = image
        _pendingAudioInput.value = audio
        persistConversationMessages()
        sendMessage(userTurn.content)
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
            tools = promptTools,
            externalStoragePath = android.os.Environment.getExternalStorageDirectory().absolutePath
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
            val images = if (!turn.imagePath.isNullOrBlank()) {
                val bitmap = loadBitmapFromPath(turn.imagePath)
                if (bitmap != null) listOf(LlmImageInput(bitmap)) else emptyList()
            } else {
                emptyList()
            }
            val audios = if (!turn.audioPath.isNullOrBlank()) {
                loadAudioFromPath(turn.audioPath, turn.audioName)?.let { listOf(it) } ?: emptyList()
            } else {
                emptyList()
            }
            LlmMessage(role = role, content = turn.content, images = images, audios = audios)
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

        fun addToolsForText(text: String) {
            val normalizedText = text.lowercase(Locale.US)

            ToolCallProtocol.parseDirectUserToolCommand(text)?.toolName?.let(::addTool)
            ToolCallProtocol.extractToolCall(text)?.toolName?.let(::addTool)

            if (containsAny(normalizedText, "alarm", "wake me")) addTool("set_alarm")
            if (containsAny(normalizedText, "timer", "countdown")) addTool("set_timer")
            if (containsAny(normalizedText, "weather", "forecast", "temperature")) addTool("get_weather")
            if (containsAny(normalizedText, "search", "look up", "web", "news")) addTool("search_web_context")
            if (containsAny(normalizedText, "remember", "save memory", "memorize")) addTool("save_memory")
            if (containsAny(normalizedText, "what do you remember", "retrieve memory", "recall memory")) addTool("retrieve_memory")
            if (looksLikeFileManagementRequest(normalizedText)) {
                addTool("list_files")
                addTool("search_files")
                addTool("read_file")
                addTool("list_tools")
            }
            if (looksLikeFileWriteRequest(normalizedText)) {
                addTool("write_file")
                addTool("create_dir")
            }
            if (looksLikeFileDeleteRequest(normalizedText)) {
                addTool("delete_file")
            }
            if (containsAny(normalizedText, "scheduled actions", "list scheduled")) addTool("list_scheduled_actions")
            if (containsAny(normalizedText, "schedule", "remind later", "run later", "background") ||
                looksLikeDeferredActionRequest(normalizedText)
            ) {
                addTool("schedule_action")
            }
            if (containsAny(normalizedText, "open url", "open link", "http://", "https://", "www.")) addTool("open_url")
            if (containsAny(normalizedText, "open app", "launch app", "launch package")) {
                addTool("open_app")
                addTool("launch_package")
            }
            if (containsAny(
                    normalizedText,
                    "send sms",
                    "send text",
                    "compose a text",
                    "draft a text",
                    "text ",
                    "text me",
                    "message "
                )
            ) {
                addTool("send_sms")
            }
            if (looksLikeCallRequest(normalizedText)) addTool("place_call")
            if (containsAny(normalizedText, "brightness", "dim", "brighter")) addTool("set_brightness")
            if (containsAny(normalizedText, "flashlight", "torch", "light")) addTool("toggle_flashlight")
            if (containsAny(normalizedText, "volume", "louder", "quieter")) addTool("set_volume")
            if (containsAny(normalizedText, "mute", "unmute")) addTool("mute")
            if (containsAny(normalizedText, "play media", "resume media", "resume playback")) addTool("play_media")
            if (containsAny(normalizedText, "pause media", "pause playback")) addTool("pause_media")
            if (containsAny(normalizedText, "next track", "skip track", "skip song")) addTool("next_track")
            if (containsAny(normalizedText, "calendar", "event", "meeting")) addTool("create_calendar_event")
            if (containsAny(normalizedText, "navigate to", "directions to", "route to")) addTool("navigate_to")
            if (containsAny(normalizedText, "take a photo", "take photo", "camera")) addTool("take_photo")
            if (containsAny(normalizedText, "record a video", "record video")) addTool("record_video")
            if (containsAny(normalizedText, "wifi", "wi-fi")) addTool("toggle_wifi")
            if (containsAny(normalizedText, "bluetooth")) addTool("toggle_bluetooth")
            if (containsAny(normalizedText, "share this", "share text", "share ")) addTool("share_text")
            if (containsAny(
                    normalizedText,
                    "on this screen",
                    "current screen",
                    "tap ",
                    "press ",
                    "scroll ",
                    "dark mode",
                    "in settings",
                    "visible toggle",
                    "visible button"
                )
            ) {
                addTool(UiAutomationOrchestrator.CONTROL_UI_TOOL)
            }
            if (containsAny(normalizedText, "what can you do", "what tools", "available tools", "help me", "list tools")) addTool("list_tools")
            if (containsAny(normalizedText, "time", "clock", "hour")) addTool("get_current_time")
        }

        addToolsForText(latestUserMessage)

        if (looksLikeToolContinuation(normalized)) {
            conversation
                .asReversed()
                .dropWhile { it.content.trim() == latestUserMessage }
                .take(6)
                .asReversed()
                .forEach { turn -> addToolsForText(turn.content) }
            if (selectedNames.isNotEmpty()) {
                addTool("list_tools")
            }
        }

        val exactMatches = selectedNames.mapNotNull { toolsByName[it] }
        
        val fuzzyMatches = fuzzyMatchTools(normalized, availableTools, exactMatches.map { it.name }.toSet())
        
        val allMatches = (exactMatches + fuzzyMatches).distinctBy { it.name }
        
        return allMatches.take(10)
    }

    private fun fuzzyMatchTools(
        message: String,
        tools: List<com.mewmix.nabu.tools.Tool>,
        excludeNames: Set<String>
    ): List<com.mewmix.nabu.tools.Tool> {
        val messageWords = message.split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        if (messageWords.isEmpty()) return emptyList()
        
        val scored = tools
            .filter { it.isAvailable && it.name !in excludeNames }
            .map { tool ->
                val nameWords = tool.name.split("_").filter { it.length > 2 }.toSet()
                val descWords = tool.description.lowercase().split(Regex("[\\s,.-]+")).filter { it.length > 2 }.toSet()
                val toolWords = nameWords + descWords
                
                val nameScore = messageWords.intersect(nameWords).size * 3
                val descScore = messageWords.intersect(descWords).size
                val score = nameScore + descScore
                
                tool to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
        
        val topScore = scored.firstOrNull()?.second ?: 0
        val threshold = maxOf(1, topScore / 2)
        
        return scored
            .filter { it.second >= threshold && it.second >= 2 }
            .take(3)
            .map { it.first }
    }

    private fun containsAny(text: String, vararg needles: String): Boolean =
        needles.any { text.contains(it) }

    private fun looksLikeFileManagementRequest(text: String): Boolean =
        containsAny(
            text,
            "file",
            "folder",
            "directory",
            "download",
            "downloads",
            "document",
            "documents",
            "dcim",
            "photo",
            "photos",
            "picture",
            "pictures",
            "music",
            "movie",
            "video",
            "zip",
            "unzip",
            "archive",
            "compress",
            "extract"
        ) ||
            Regex("""(?is)\b(?:list|read|find|search|open|show)\b.+\b(?:/sdcard|/storage/emulated/0|downloads?|documents?|dcim)\b""")
                .containsMatchIn(text)

    private fun looksLikeFileWriteRequest(text: String): Boolean =
        looksLikeFileManagementRequest(text) &&
            containsAny(
                text,
                "write",
                "create",
                "make ",
                "new file",
                "new folder",
                "save ",
                "edit"
            )

    private fun looksLikeFileDeleteRequest(text: String): Boolean =
        looksLikeFileManagementRequest(text) &&
            containsAny(text, "delete", "remove", "trash")

    private fun looksLikeDeferredActionRequest(text: String): Boolean =
        Regex("""(?is)\b(?:after|in)\s+.+?\s+(?:turn|set|toggle|run|open|start|stop|send|text|message|compose|draft|list|read|find|search|write|create|delete|remove|compress|extract)\b""")
            .containsMatchIn(text) ||
            Regex("""(?is)\b(?:turn|set|toggle|run|open|start|stop|send|text|message|compose|draft|list|read|find|search|write|create|delete|remove|compress|extract)\b.+\b(?:at|by)\s+(?:\d{1,2}(?::\d{2})?\s*(?:am|pm)?|noon|midnight|tomorrow|tonight)\b""")
                .containsMatchIn(text)

    private fun looksLikeToolContinuation(text: String): Boolean =
        Regex(
            """(?is)^\s*(?:go ahead|do it|use (?:the )?tool(?:s)?(?: to do so)?|just use (?:the )?tool(?:s)?(?: to do so)?|and\??|yes|yeah|yep|ok(?:ay)?|continue|that one|it|make it .+|actually .+)\s*[.!?]*\s*$"""
        ).containsMatchIn(text)

    private fun looksLikeCallRequest(text: String): Boolean =
        Regex("""(?is)^\s*(?:call|dial|place\s+call)\b""").containsMatchIn(text)

    private fun summarizeToolResultMessage(result: ToolResult): String {
        return AgentTurnRunner.summarizeToolResultMessage(result)
    }

    private suspend fun executeToolCallInternal(appContext: Context, toolCall: ToolCall): ToolResult {
        var effectiveToolCall = toolCall
        if (toolCall.toolName == "open_app" || toolCall.toolName == "launch_package") {
            val packageName = toolCall.arguments["package_name"]?.toString()?.trim().orEmpty()
            val appName = toolCall.arguments["app_name"]?.toString()?.trim().orEmpty()
            if (packageName.isBlank() && appName.isNotBlank()) {
                val candidates = DeviceAction.findAppCandidates(appContext, appName)
                val selectedPackage = when (candidates.size) {
                    0 -> null
                    1 -> candidates.first().packageName
                    else -> {
                        val deferred = CompletableDeferred<String?>()
                        pendingAppSelectionDeferred = deferred
                        _pendingAppSelection.value = AppSelectionRequest(appName, candidates)
                        deferred.await()
                    }
                }
                if (selectedPackage == null && candidates.size > 1) {
                    return ToolResult(
                        toolName = toolCall.toolName,
                        output = "User canceled app selection for '$appName'.",
                        isError = true
                    )
                }
                if (selectedPackage != null) {
                    effectiveToolCall = toolCall.copy(
                        arguments = toolCall.arguments + ("package_name" to selectedPackage)
                    )
                }
            }
        }

        val destructiveTools = setOf("delete_file", "write_file", "move_file", "rename_file", "create_archive", "add_to_archive", "remove_from_archive")
        if (effectiveToolCall.toolName in destructiveTools) {
            val deferred = CompletableDeferred<Boolean>()
            pendingToolApprovalDeferred = deferred
            _pendingToolApproval.value = effectiveToolCall
            val allowed = deferred.await()
            if (!allowed) {
                return ToolResult(
                    toolName = effectiveToolCall.toolName,
                    output = "User denied permission to execute this tool.",
                    isError = true
                )
            }
        }
        
        val tool = ToolRegistry.getTool(effectiveToolCall.toolName)
        if (tool == null || !tool.isAvailable) {
            return ToolResult(
                toolName = effectiveToolCall.toolName,
                output = "Tool '${effectiveToolCall.toolName}' is unavailable.",
                isError = true
            )
        }
        if (effectiveToolCall.toolName == UiAutomationOrchestrator.CONTROL_UI_TOOL) {
            val goal = effectiveToolCall.arguments["goal"]?.toString()?.trim().orEmpty()
            val plannerBackend = llmBackend ?: return ToolResult(
                toolName = effectiveToolCall.toolName,
                output = "No LLM backend is available for UI planning.",
                isError = true
            )
            _orchestration.value = _orchestration.value?.copy(isVisible = false)
            delay(250L)
            return try {
                UiAutomationOrchestrator(
                    context = appContext,
                    backend = plannerBackend,
                    requestConfirmation = { description ->
                        val deferred = CompletableDeferred<Boolean>()
                        pendingUiActionConfirmationDeferred = deferred
                        _pendingUiActionConfirmation.value = UiActionConfirmationRequest(description)
                        deferred.await()
                    },
                    onProgress = { phase, detail ->
                        recordOrchestration(phase, detail, UiAutomationOrchestrator.CONTROL_UI_TOOL)
                    },
                    logger = DebugLogger::log
                ).run(goal)
            } finally {
                _orchestration.value = _orchestration.value?.copy(isVisible = true)
            }
        }
        ActionTools.execute(appContext, effectiveToolCall)?.let { return it }
        if (!GlaiveBridge.isInstalled(appContext)) {
            return ToolResult(
                toolName = effectiveToolCall.toolName,
                output = "Glaive is not installed.",
                isError = true
            )
        }
        val baseResult = withTimeoutOrNull(TOOL_EXECUTION_TIMEOUT_MS) {
            GlaiveBridge.executeTool(appContext, effectiveToolCall)
        } ?: ToolResult(
            toolName = effectiveToolCall.toolName,
            output = "Tool '${effectiveToolCall.toolName}' timed out after ${TOOL_EXECUTION_TIMEOUT_MS / 1000}s.",
            isError = true
        )

        if ((toolCall.toolName == "search_files" || toolCall.toolName == "list_files") && !baseResult.isError) {
            val lines = baseResult.output.split("\n")
            val page = toolCall.arguments["page"]?.toString()?.toIntOrNull() ?: 1
            val pageSize = 20
            val totalLines = lines.size
            if (totalLines > pageSize) {
                val start = (page - 1) * pageSize
                val end = (start + pageSize).coerceAtMost(totalLines)
                if (start < totalLines) {
                    val pagedLines = lines.subList(start, end)
                    val summary = "Showing ${start + 1}-$end of $totalLines results (use page=${page + 1} to see more)."
                    return baseResult.copy(output = pagedLines.joinToString("\n") + "\n\n$summary")
                } else {
                    return baseResult.copy(output = "Page $page is empty. Total results: $totalLines")
                }
            }
        }
        if (effectiveToolCall.toolName == "read_screen" && !baseResult.isError) {
            val path = runCatching { JSONObject(baseResult.output).optString("path") }.getOrDefault("")
            if (path.isNotBlank()) {
                val xmlResult = GlaiveBridge.executeTool(
                    appContext,
                    ToolCall("read_ui_xml", mapOf("path" to path))
                )
                if (!xmlResult.isError) {
                    return baseResult.copy(output = xmlResult.output)
                }
                return xmlResult.copy(toolName = "read_screen")
            }
        }
        if (toolCall.toolName == "take_screenshot" && !baseResult.isError) {
            try {
                val json = org.json.JSONObject(baseResult.output)
                val path = json.optString("path", "")
                if (path.isNotEmpty() && (path.endsWith(".png") || path.endsWith(".jpg"))) {
                    return baseResult.copy(attachedImagePath = path)
                }
            } catch (e: Exception) {
                // Ignore parse errors
            }
        }
        return baseResult
    }

    private fun finalizeDirectToolResponse(finalResponse: String, actionTrace: ActionTrace? = null) {
        viewModelScope.launch(Dispatchers.Main) {
            _isLoading.value = false
            val failed = actionTrace?.entries?.any { it.isError } == true
            finishOrchestration(
                status = if (failed) "Execution finished with an error" else "Execution complete",
                isError = failed
            )
            DebugLogger.log("ChatViewModel response complete")
            val last = _chatMessages.value.lastOrNull()
            if (last != null) {
                _chatMessages.value =
                    _chatMessages.value.dropLast(1) + last.copy(
                        message = finalResponse,
                        actionTrace = actionTrace
                    )
            }
            conversationHistory.add(ConversationTurn(ConversationRole.AGENT, finalResponse))
            persistConversationMessages()
        }
    }

    private fun buildActionTrace(
        title: String,
        source: String,
        toolCalls: List<ToolCall>,
        results: List<ToolResult>
    ): ActionTrace {
        val entries = mutableListOf<ActionTraceEntry>()
        entries += ActionTraceEntry("Intent", "Action-shaped request detected.")
        entries += ActionTraceEntry("Planner", "Plan source=$source; steps=${toolCalls.size}.")
        toolCalls.zip(results).forEachIndexed { index, (call, result) ->
            entries += ActionTraceEntry(
                phase = "Tool ${index + 1}",
                detail = "Call ${call.toolName} args=${call.arguments}",
                toolName = call.toolName
            )
            entries += ActionTraceEntry(
                phase = "Output ${index + 1}",
                detail = if (result.isError) "Tool returned an error." else "Tool completed.",
                toolName = result.toolName,
                output = result.output,
                isError = result.isError
            )
        }
        return ActionTrace(title = title, entries = entries)
    }

    private fun buildAgentTrace(transcript: List<AgentTurnRunner.ToolExchange>): ActionTrace? {
        if (transcript.isEmpty()) return null
        val entries = mutableListOf<ActionTraceEntry>()
        entries += ActionTraceEntry("Intent", "Model/tool loop used for this response.")
        transcript.forEachIndexed { index, exchange ->
            entries += ActionTraceEntry(
                phase = "Tool ${index + 1}",
                detail = "Call ${exchange.call.toolName} args=${exchange.call.arguments}" +
                    if (exchange.inferred) " (inferred from user request/model failure)" else "",
                toolName = exchange.call.toolName
            )
            entries += ActionTraceEntry(
                phase = "Output ${index + 1}",
                detail = if (exchange.result.isError) "Tool returned an error." else "Tool completed.",
                toolName = exchange.result.toolName,
                output = exchange.result.output,
                isError = exchange.result.isError
            )
        }
        return ActionTrace(title = "Tool Call Trace", entries = entries)
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
        return (text.length / 4.0).toInt().coerceAtLeast(1)
    }

    private fun takeLastTokens(text: String, tokenLimit: Int): String {
        if (tokenLimit <= 0) return ""
        val charLimit = tokenLimit * 4
        if (text.length <= charLimit) return text.trim()
        return text.substring(text.length - charLimit).trim()
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
                        if (!_ttsEnabled.value || dropQueuedAudio) {
                            return@withLock null
                        }
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
                if (audioData != null && _ttsEnabled.value && !dropQueuedAudio) {
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
        SettingsManager.setVoiceMixConfig(context, currentVoiceMixConfig())
        configPersistenceJob = viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            SettingsManager.setVoiceMixConfig(context, currentVoiceMixConfig())
        }
    }
}

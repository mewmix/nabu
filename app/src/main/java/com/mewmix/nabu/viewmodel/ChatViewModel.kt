package com.mewmix.nabu.viewmodel

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mewmix.nabu.agent.AgentTurnRunner
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
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
            "share_text"
        )

        internal data class DirectActionPlan(
            val toolCalls: List<ToolCall>,
            val response: String
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
            clauses.forEach { clause ->
                val trimmedClause = clause.trim()
                if (trimmedClause.isBlank()) return@forEach

                parseConversationalClause(trimmedClause)?.let {
                    responseParts += it
                    return@forEach
                }

                parseDelayedActionClause(trimmedClause, availableToolNames)?.let {
                    toolCalls += it
                    return@forEach
                }

                inferToolCallFromModelFailure(trimmedClause, availableToolNames)?.let {
                    toolCalls += it
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
            availableToolNames: Set<String>
        ): ToolCall? {
            if ("schedule_action" !in availableToolNames) return null
            val durationPattern =
                """(?:\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|a|an)\s+(?:seconds?|secs?|minutes?|mins?|hours?|hrs?)"""

            val prefixDelay = Regex("""(?is)^\s*(?:after|in)\s+($durationPattern)\s+(.+?)\s*$""")
                .find(clause)
                ?.let { it.groupValues[1].trim() to it.groupValues[2].trim() }

            val suffixDelay = Regex("""(?is)^\s*(.+?)\s+(?:after|in)\s+($durationPattern)\s*$""")
                .find(clause)
                ?.let { it.groupValues[2].trim() to it.groupValues[1].trim() }

            val (durationSpec, actionText) = prefixDelay ?: suffixDelay ?: return null
            val seconds = parseDurationSeconds(durationSpec) ?: return null
            val actionTool = inferToolCallFromModelFailure(
                actionText,
                availableToolNames - "schedule_action"
            ) ?: return null

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
                    """(?is)^\s*(?:turn\s+)?(?:(on|off)\s+(?:my\s+|the\s+)?(?:flashlight|torch)|(?:my\s+|the\s+)?(?:flashlight|torch)(?:\s+(on|off))?)\s*$"""
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
        val directToolCall = ToolCallProtocol.parseDirectUserToolCommand(trimmed)
            ?: if (image == null && audio == null) {
                inferToolCallFromModelFailure(trimmed, availableToolNames)
            } else {
                null
            }

        dropQueuedAudio = false
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
        _chatMessages.value += ChatMessage("...", false) // placeholder

        val directActionPlan = if (image == null && audio == null) {
            planDirectActionChain(trimmed, availableToolNames)
        } else {
            null
        }
        if (directActionPlan != null) {
            viewModelScope.launch(Dispatchers.IO) {
                DebugLogger.log(
                    "ChatViewModel: executing direct action plan with " +
                        directActionPlan.toolCalls.joinToString(",") { it.toolName }
                )
                val results = directActionPlan.toolCalls.map { executeToolCallInternal(context.applicationContext, it) }
                val firstError = results.firstOrNull { it.isError }
                val response = if (firstError != null) {
                    firstError.output
                } else {
                    directActionPlan.response
                }
                finalizeDirectToolResponse(response)
            }
            return
        }

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
                        if (sentenceBuilder.isBlank()) {
                            sentenceBuilder.append(finalResponse)
                        }
                        processSentences(sentenceBuilder, true)
                    } else {
                        sentenceBuilder.clear()
                        dropQueuedAudio = false
                    }
                }
            }

            AgentTurnRunner(
                backend = backend,
                scope = viewModelScope,
                toolExecutor = { toolCall ->
                    withContext(Dispatchers.IO) {
                        executeToolCallInternal(appContext, toolCall)
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
                    sentenceBuilder.append(partial)
                    if (!done) {
                        processSentences(sentenceBuilder, false)
                    }
                },
                onSuppressSpeakablePartials = {
                    sentenceBuilder.clear()
                },
                onToolStart = { toolCall ->
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
                        speakOutput = result.speakOutput
                    )
                }
            )
        }
    }

    fun cancelGeneration() {
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
                ChatMessage(turn.content, turn.role == ConversationRole.USER, image, audio)
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
            viewModelScope.launch(Dispatchers.IO) {
                created.backend.initialize()
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

        ToolCallProtocol.parseDirectUserToolCommand(latestUserMessage)?.toolName?.let(::addTool)

        if (containsAny(normalized, "alarm", "wake me")) addTool("set_alarm")
        if (containsAny(normalized, "timer", "countdown")) addTool("set_timer")
        if (containsAny(normalized, "weather", "forecast", "temperature")) addTool("get_weather")
        if (containsAny(normalized, "search", "look up", "web", "news")) addTool("search_web_context")
        if (containsAny(normalized, "remember", "save memory", "memorize")) addTool("save_memory")
        if (containsAny(normalized, "what do you remember", "retrieve memory", "recall memory")) addTool("retrieve_memory")
        if (containsAny(normalized, "scheduled actions", "list scheduled")) addTool("list_scheduled_actions")
        if (containsAny(normalized, "schedule", "remind later", "run later", "background") ||
            looksLikeDeferredActionRequest(normalized)
        ) {
            addTool("schedule_action")
        }
        if (containsAny(normalized, "open url", "open link", "http://", "https://", "www.")) addTool("open_url")
        if (containsAny(normalized, "open app", "launch app", "launch package")) {
            addTool("open_app")
            addTool("launch_package")
        }
        if (containsAny(normalized, "send sms", "text ", "text me", "message ")) addTool("send_sms")
        if (looksLikeCallRequest(normalized)) addTool("place_call")
        if (containsAny(normalized, "brightness", "dim", "brighter")) addTool("set_brightness")
        if (containsAny(normalized, "flashlight", "torch", "light")) addTool("toggle_flashlight")
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
        if (containsAny(normalized, "what can you do", "what tools", "available tools", "help me")) addTool("list_tools")
        if (containsAny(normalized, "time", "clock", "hour")) addTool("get_current_time")

        val exactMatches = selectedNames.mapNotNull { toolsByName[it] }
        
        val fuzzyMatches = fuzzyMatchTools(normalized, availableTools, exactMatches.map { it.name }.toSet())
        
        val allMatches = (exactMatches + fuzzyMatches).distinctBy { it.name }
        
        return allMatches.take(6)
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

    private fun looksLikeDeferredActionRequest(text: String): Boolean =
        Regex("""(?is)\b(?:after|in)\s+.+?\s+(?:turn|set|toggle|run|open|start|stop)\b""")
            .containsMatchIn(text)

    private fun looksLikeCallRequest(text: String): Boolean =
        Regex("""(?is)^\s*(?:call|dial|place\s+call)\b""").containsMatchIn(text)

    private fun summarizeToolResultMessage(result: ToolResult): String {
        return AgentTurnRunner.summarizeToolResultMessage(result)
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
        SettingsManager.setVoiceMixConfig(context, currentVoiceMixConfig())
        configPersistenceJob = viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            SettingsManager.setVoiceMixConfig(context, currentVoiceMixConfig())
        }
    }
}

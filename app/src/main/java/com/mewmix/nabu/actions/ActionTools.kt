package com.mewmix.nabu.actions

import android.content.Context
import com.mewmix.nabu.tools.Tool
import com.mewmix.nabu.tools.ToolCall
import com.mewmix.nabu.tools.ToolRegistry
import com.mewmix.nabu.tools.ToolResult
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ActionTools {
    const val SCHEDULED_AGENT_STEP_TOOL = "run_agent"

    private val schedulableToolNames = setOf(
        SCHEDULED_AGENT_STEP_TOOL,
        "search_web_context",
        "get_current_time",
        "get_weather",
        "save_memory",
        "retrieve_memory",
        "send_sms",
        "toggle_flashlight",
        "set_volume",
        "mute",
        "play_media",
        "pause_media",
        "next_track"
    )

    val tools: List<Tool> = listOf(
        Tool(
            name = "list_tools",
            description = "List all available tools with their descriptions and parameters. Call this to discover what tools you can use.",
            parameters = mapOf(
                "filter" to "Optional keyword to filter tools by name or description."
            )
        ),
        Tool(
            name = "schedule_action",
            description = "Schedule one or more background-safe tool steps at a specific local date-time or after a short delay.",
            parameters = mapOf(
                "instruction" to "Human-readable description of what should happen when due.",
                "run_at_local" to "Local datetime in yyyy-MM-dd HH:mm or yyyy-MM-dd HH:mm:ss format.",
                "delay_seconds" to "Optional relative delay in whole seconds. Use this for requests like in 10 seconds.",
                "title" to "Short title.",
                "recurrence" to "none, daily, or weekly",
                "tool_name" to "The background-safe tool name to execute when due.",
                "tool_arguments" to "JSON object of arguments for the deferred tool call.",
                "steps" to "Optional JSON array of step objects: title, tool_name, tool_arguments, continue_on_error."
            )
        ),
        Tool(
            name = "schedule_fuzzy_action",
            description = "Interpret a fuzzy scheduling request and create a scheduled action when possible.",
            parameters = mapOf(
                "request" to "Natural-language request like: remind me when the sun goes down in Berlin"
            )
        ),
        Tool(
            name = "list_scheduled_actions",
            description = "List scheduled actions with due/completed state and last run summary.",
            parameters = emptyMap()
        ),
        Tool(
            name = "search_web_context",
            description = "Search the web for context and return concise findings.",
            parameters = mapOf(
                "query" to "What to search for."
            )
        ),
        Tool(
            name = "get_current_time",
            description = "Get the current local date and time.",
            parameters = emptyMap()
        ),
        Tool(
            name = "get_weather",
            description = "Get the current weather for a specific location.",
            parameters = mapOf(
                "location" to "The location to get weather for."
            )
        ),
        Tool(
            name = "save_memory",
            description = "Save a user preference or fact to memory.",
            parameters = mapOf(
                "fact" to "The preference or fact to save."
            )
        ),
        Tool(
            name = "retrieve_memory",
            description = "Retrieve all saved user preferences and facts.",
            parameters = emptyMap()
        ),
        Tool(
            name = "set_alarm",
            description = "Set an audible alarm for a specific time.",
            parameters = mapOf(
                "hour" to "The hour (0-23) to set the alarm for.",
                "minute" to "The minute (0-59) to set the alarm for.",
                "message" to "A message or title for the alarm."
            )
        ),
        Tool(
            name = "set_timer",
            description = "Set an audible timer for a specific duration.",
            parameters = mapOf(
                "seconds" to "The duration of the timer in seconds.",
                "message" to "A message or title for the timer."
            )
        ),
        Tool(
            name = "open_app",
            description = "Open an installed app by package name or app name.",
            parameters = mapOf(
                "package_name" to "Android package name like com.spotify.music.",
                "app_name" to "Human app name like Spotify."
            )
        ),
        Tool(
            name = "launch_package",
            description = "Open an installed app by package name or app name.",
            parameters = mapOf(
                "package_name" to "Android package name like com.spotify.music.",
                "app_name" to "Human app name like Spotify."
            )
        ),
        Tool(
            name = "open_url",
            description = "Open a web URL in the device browser or a matching app.",
            parameters = mapOf(
                "url" to "The URL to open."
            )
        ),
        Tool(
            name = "send_sms",
            description = "Open the SMS composer with a recipient and optional draft message.",
            parameters = mapOf(
                "phone_number" to "Recipient phone number.",
                "recipient" to "Optional contact name to resolve when no phone number is provided.",
                "message" to "Optional SMS draft text."
            )
        ),
        Tool(
            name = "place_call",
            description = "Open the dialer with a phone number prepared.",
            parameters = mapOf(
                "phone_number" to "Phone number to dial.",
                "recipient" to "Optional contact name to resolve when no phone number is provided."
            )
        ),
        Tool(
            name = "set_brightness",
            description = "Set display brightness when write-settings access is already granted.",
            parameters = mapOf(
                "level" to "Brightness percentage from 0 to 100."
            )
        ),
        Tool(
            name = "toggle_flashlight",
            description = "Turn the device flashlight on or off.",
            parameters = mapOf(
                "enabled" to "True to turn it on, false to turn it off."
            )
        ),
        Tool(
            name = "set_volume",
            description = "Set device volume for a specific stream.",
            parameters = mapOf(
                "level" to "Volume percentage from 0 to 100.",
                "stream" to "music, ring, alarm, or notification."
            )
        ),
        Tool(
            name = "mute",
            description = "Mute or unmute media volume.",
            parameters = mapOf(
                "enabled" to "True to mute, false to unmute."
            )
        ),
        Tool(
            name = "play_media",
            description = "Send a play media command.",
            parameters = emptyMap()
        ),
        Tool(
            name = "pause_media",
            description = "Send a pause media command.",
            parameters = emptyMap()
        ),
        Tool(
            name = "next_track",
            description = "Send a next-track media command.",
            parameters = emptyMap()
        ),
        Tool(
            name = "create_calendar_event",
            description = "Open calendar event creation with optional timing details.",
            parameters = mapOf(
                "title" to "Event title.",
                "start_local" to "Optional start time in yyyy-MM-dd HH:mm format.",
                "end_local" to "Optional end time in yyyy-MM-dd HH:mm format.",
                "location" to "Optional event location.",
                "description" to "Optional event description."
            )
        ),
        Tool(
            name = "navigate_to",
            description = "Open a navigation app for a destination.",
            parameters = mapOf(
                "destination" to "Address, place name, or coordinates."
            )
        ),
        Tool(
            name = "take_photo",
            description = "Open the camera in photo mode.",
            parameters = emptyMap()
        ),
        Tool(
            name = "record_video",
            description = "Open the camera in video mode.",
            parameters = emptyMap()
        ),
        Tool(
            name = "toggle_wifi",
            description = "Open Wi-Fi controls. Direct toggles are restricted on modern Android.",
            parameters = mapOf(
                "enabled" to "Optional requested state."
            )
        ),
        Tool(
            name = "toggle_bluetooth",
            description = "Open Bluetooth controls. Direct toggles are restricted on modern Android.",
            parameters = mapOf(
                "enabled" to "Optional requested state."
            )
        ),
        Tool(
            name = "share_text",
            description = "Open the system share sheet with text content.",
            parameters = mapOf(
                "text" to "The text to share.",
                "subject" to "Optional share subject."
            )
        )
    )

    fun execute(context: Context, call: ToolCall): ToolResult? {
        return when (call.toolName) {
            "list_tools" -> runListTools(call)
            "schedule_action" -> runScheduleAction(context, call)
            "schedule_fuzzy_action" -> runFuzzyAction(context, call)
            "list_scheduled_actions" -> runListActions(context)
            "search_web_context" -> runWebSearch(call)
            "get_current_time" -> runGetCurrentTime(call)
            "get_weather" -> runGetWeather(call)
            "save_memory" -> runSaveMemory(context, call)
            "retrieve_memory" -> runRetrieveMemory(context, call)
            "set_alarm" -> runSetAlarm(context, call)
            "set_timer" -> runSetTimer(context, call)
            "open_app", "launch_package" -> runOpenApp(context, call)
            "open_url" -> runOpenUrl(context, call)
            "send_sms" -> runSendSms(context, call)
            "place_call" -> runPlaceCall(context, call)
            "set_brightness" -> runSetBrightness(context, call)
            "toggle_flashlight" -> runToggleFlashlight(context, call)
            "set_volume" -> runSetVolume(context, call)
            "mute" -> runMute(context, call)
            "play_media" -> runMediaAction(call) { DeviceAction.playMedia(context) }
            "pause_media" -> runMediaAction(call) { DeviceAction.pauseMedia(context) }
            "next_track" -> runMediaAction(call) { DeviceAction.nextTrack(context) }
            "create_calendar_event" -> runCreateCalendarEvent(context, call)
            "navigate_to" -> runNavigateTo(context, call)
            "take_photo" -> runMediaAction(call) { DeviceAction.takePhoto(context) }
            "record_video" -> runMediaAction(call) { DeviceAction.recordVideo(context) }
            "toggle_wifi" -> runToggleWifi(context, call)
            "toggle_bluetooth" -> runToggleBluetooth(context, call)
            "share_text" -> runShareText(context, call)
            else -> null
        }
    }

    fun isSchedulableTool(toolName: String): Boolean = toolName in schedulableToolNames

    fun schedulableToolsForAgent(): List<Tool> =
        tools.filter { tool ->
            tool.isAvailable &&
                tool.name != SCHEDULED_AGENT_STEP_TOOL &&
                isSchedulableTool(tool.name)
        }

    private fun runGetCurrentTime(call: ToolCall): ToolResult {
        val current = LocalDateTime.now(ZoneId.systemDefault())
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        return ToolResult(call.toolName, "Current local time is: ${current.format(formatter)}")
    }

    private fun runListTools(call: ToolCall): ToolResult {
        val filter = call.arguments["filter"]?.toString()?.trim()?.lowercase().orEmpty()
        val allTools = ToolRegistry.tools.value.filter { it.isAvailable && it.name != "list_tools" }
        val filtered = if (filter.isBlank()) {
            allTools
        } else {
            allTools.filter { tool ->
                tool.name.lowercase().contains(filter) ||
                tool.description.lowercase().contains(filter)
            }
        }
        if (filtered.isEmpty()) {
            return ToolResult(call.toolName, if (filter.isBlank()) "No tools available." else "No tools match filter: $filter")
        }
        val lines = filtered.map { tool ->
            val params = if (tool.parameters.isEmpty()) {
                ""
            } else {
                "(${tool.parameters.keys.sorted().joinToString(", ")})"
            }
            "- ${tool.name}$params: ${tool.description}"
        }
        return ToolResult(call.toolName, "Available tools:\n${lines.joinToString("\n")}")
    }

    private fun runGetWeather(call: ToolCall): ToolResult {
        val location = call.arguments["location"]?.toString()?.trim().orEmpty()
        if (location.isBlank()) {
            return ToolResult(call.toolName, "Missing required parameter: location", true)
        }
        val weather = WeatherAction.getWeather(location)
        return ToolResult(call.toolName, weather.message, weather.isError)
    }

    private fun runSaveMemory(context: Context, call: ToolCall): ToolResult {
        val fact = call.arguments["fact"]?.toString()?.trim().orEmpty()
        if (fact.isBlank()) {
            return ToolResult(call.toolName, "Missing required parameter: fact", true)
        }
        MemoryStore.saveMemory(context, fact)
        return ToolResult(call.toolName, "Saved memory: $fact")
    }

    private fun runRetrieveMemory(context: Context, call: ToolCall): ToolResult {
        val memories = MemoryStore.retrieveMemories(context)
        if (memories.isEmpty()) {
            return ToolResult(call.toolName, "No memories saved.")
        }
        return ToolResult(call.toolName, memories.joinToString("\n") { "- $it" })
    }

    private fun runSetAlarm(context: Context, call: ToolCall): ToolResult {
        val hourRaw = call.arguments["hour"]
        val minuteRaw = call.arguments["minute"]
        val message = call.arguments["message"]?.toString()?.trim().orEmpty()

        if (isMissingArgument(hourRaw) || isMissingArgument(minuteRaw)) {
            return ToolResult(call.toolName, "Missing required parameters: hour, minute", true)
        }

        val hour = parseWholeNumberArgument(hourRaw)
        val minute = parseWholeNumberArgument(minuteRaw)

        if (hour == null || minute == null) {
            return ToolResult(call.toolName, "Invalid hour or minute format.", true)
        }

        val result = AlarmTimerAction.setAlarm(context, hour, minute, message)
        return ToolResult(call.toolName, result.message, result.isError)
    }

    private fun runSetTimer(context: Context, call: ToolCall): ToolResult {
        val secondsRaw = call.arguments["seconds"]
        val message = call.arguments["message"]?.toString()?.trim().orEmpty()

        if (isMissingArgument(secondsRaw)) {
            return ToolResult(call.toolName, "Missing required parameter: seconds", true)
        }

        val seconds = parseWholeNumberArgument(secondsRaw)
        if (seconds == null) {
            return ToolResult(call.toolName, "Invalid seconds format.", true)
        }

        val result = AlarmTimerAction.setTimer(context, seconds, message)
        return ToolResult(call.toolName, result.message, result.isError)
    }

    private fun runOpenApp(context: Context, call: ToolCall): ToolResult {
        val packageName = call.arguments["package_name"]?.toString()?.trim().orEmpty()
        val appName = call.arguments["app_name"]?.toString()?.trim().orEmpty()
        val result = DeviceAction.openApp(context, packageName, appName)
        return ToolResult(call.toolName, result.message, result.isError)
    }

    private fun runOpenUrl(context: Context, call: ToolCall): ToolResult {
        val url = call.arguments["url"]?.toString()?.trim().orEmpty()
        val result = DeviceAction.openUrl(context, url)
        return ToolResult(call.toolName, result.message, result.isError)
    }

    private fun runSendSms(context: Context, call: ToolCall): ToolResult {
        val phoneNumber = call.arguments["phone_number"]?.toString()?.trim().orEmpty()
        val recipient = call.arguments["recipient"]?.toString()?.trim().orEmpty()
        val message = call.arguments["message"]?.toString()?.trim().orEmpty()
        val result = DeviceAction.sendSms(context, phoneNumber, recipient, message)
        return ToolResult(call.toolName, result.message, result.isError)
    }

    private fun runPlaceCall(context: Context, call: ToolCall): ToolResult {
        val phoneNumber = call.arguments["phone_number"]?.toString()?.trim().orEmpty()
        val recipient = call.arguments["recipient"]?.toString()?.trim().orEmpty()
        val result = DeviceAction.placeCall(context, phoneNumber, recipient)
        return ToolResult(call.toolName, result.message, result.isError)
    }

    private fun runSetBrightness(context: Context, call: ToolCall): ToolResult {
        val level = parseWholeNumberArgument(call.arguments["level"])
            ?: return ToolResult(call.toolName, "Invalid level format.", true)
        val result = DeviceAction.setBrightness(context, level)
        return ToolResult(call.toolName, result.message, result.isError)
    }

    private fun runToggleFlashlight(context: Context, call: ToolCall): ToolResult {
        val enabled = parseBooleanArgument(call.arguments["enabled"]) ?: true
        val result = DeviceAction.toggleFlashlight(context, enabled)
        return ToolResult(call.toolName, result.message, result.isError)
    }

    private fun runSetVolume(context: Context, call: ToolCall): ToolResult {
        val level = parseWholeNumberArgument(call.arguments["level"])
            ?: return ToolResult(call.toolName, "Invalid level format.", true)
        val stream = call.arguments["stream"]?.toString()?.trim().orEmpty()
        val result = DeviceAction.setVolume(context, level, stream)
        return ToolResult(call.toolName, result.message, result.isError)
    }

    private fun runMute(context: Context, call: ToolCall): ToolResult {
        val enabled = parseBooleanArgument(call.arguments["enabled"]) ?: true
        val result = DeviceAction.mute(context, enabled)
        return ToolResult(call.toolName, result.message, result.isError)
    }

    private fun runCreateCalendarEvent(context: Context, call: ToolCall): ToolResult {
        val title = call.arguments["title"]?.toString()?.trim().orEmpty()
        val startLocal = call.arguments["start_local"]?.toString()?.trim().orEmpty()
        val endLocal = call.arguments["end_local"]?.toString()?.trim().orEmpty()
        val location = call.arguments["location"]?.toString()?.trim().orEmpty()
        val description = call.arguments["description"]?.toString()?.trim().orEmpty()
        val result = DeviceAction.createCalendarEvent(context, title, startLocal, endLocal, location, description)
        return ToolResult(call.toolName, result.message, result.isError)
    }

    private fun runNavigateTo(context: Context, call: ToolCall): ToolResult {
        val destination = call.arguments["destination"]?.toString()?.trim().orEmpty()
        val result = DeviceAction.navigateTo(context, destination)
        return ToolResult(call.toolName, result.message, result.isError)
    }

    private fun runToggleWifi(context: Context, call: ToolCall): ToolResult {
        val enabled = parseBooleanArgument(call.arguments["enabled"])
        val result = DeviceAction.toggleWifi(context, enabled)
        return ToolResult(call.toolName, result.message, result.isError)
    }

    private fun runToggleBluetooth(context: Context, call: ToolCall): ToolResult {
        val enabled = parseBooleanArgument(call.arguments["enabled"])
        val result = DeviceAction.toggleBluetooth(context, enabled)
        return ToolResult(call.toolName, result.message, result.isError)
    }

    private fun runShareText(context: Context, call: ToolCall): ToolResult {
        val text = call.arguments["text"]?.toString()?.trim().orEmpty()
        val subject = call.arguments["subject"]?.toString()?.trim().orEmpty()
        val result = DeviceAction.shareText(context, text, subject)
        return ToolResult(call.toolName, result.message, result.isError)
    }

    private fun runMediaAction(call: ToolCall, action: () -> DeviceAction.ActionResult): ToolResult {
        val result = action()
        return ToolResult(call.toolName, result.message, result.isError)
    }

    private fun runScheduleAction(context: Context, call: ToolCall): ToolResult {
        val instruction = call.arguments["instruction"]?.toString()?.trim().orEmpty()
        val runAtLocal = call.arguments["run_at_local"]?.toString()?.trim().orEmpty()
        val delaySeconds = parseWholeNumberArgument(call.arguments["delay_seconds"])
        val title = call.arguments["title"]?.toString()?.trim().ifNullOrBlank("Scheduled action")
        val recurrence = call.arguments["recurrence"]?.toString()?.trim()?.lowercase().normalizeRecurrence()
        val toolName = call.arguments["tool_name"]?.toString()?.trim().orEmpty()
        val toolArguments = call.arguments["tool_arguments"].asStringKeyedMap()
        val parsedSteps = parseScheduledSteps(call.arguments["steps"])

        if (runAtLocal.isBlank() && delaySeconds == null) {
            return ToolResult(call.toolName, "Missing required parameter: run_at_local or delay_seconds", true)
        }
        if (delaySeconds != null && delaySeconds <= 0) {
            return ToolResult(call.toolName, "delay_seconds must be a positive whole number.", true)
        }
        if (instruction.isBlank() && toolName.isBlank() && parsedSteps.isEmpty()) {
            return ToolResult(call.toolName, "Provide at least one of: instruction, tool_name, or steps", true)
        }
        if (toolName.isNotBlank() && !isSchedulableTool(toolName)) {
            return ToolResult(
                call.toolName,
                "Tool '$toolName' cannot be scheduled from background execution. Allowed tools: ${schedulableToolNames.joinToString(", ")}",
                true
            )
        }
        if (toolName.isBlank() && toolArguments.isNotEmpty()) {
            return ToolResult(call.toolName, "tool_arguments requires tool_name", true)
        }
        val unsafeStep = parsedSteps.firstOrNull { !isSchedulableTool(it.toolName) }
        if (unsafeStep != null) {
            return ToolResult(
                call.toolName,
                "Tool '${unsafeStep.toolName}' cannot be scheduled from background execution. Allowed tools: ${schedulableToolNames.joinToString(", ")}",
                true
            )
        }

        val triggerAt = if (delaySeconds != null) {
            System.currentTimeMillis() + delaySeconds * 1000L
        } else {
            parseLocalDateTime(runAtLocal)
                ?: return ToolResult(call.toolName, "Invalid run_at_local format. Use yyyy-MM-dd HH:mm or yyyy-MM-dd HH:mm:ss", true)
        }

        val resolvedInstruction = instruction.ifBlank {
            if (parsedSteps.isNotEmpty()) {
                "Run ${parsedSteps.size} scheduled steps."
            } else if (toolArguments.isEmpty()) {
                "Run scheduled tool $toolName."
            } else {
                "Run scheduled tool $toolName with arguments ${toolArguments.keys.joinToString(", ")}."
            }
        }

        val action = ScheduledActionScheduler.createAndSchedule(
            context = context,
            title = title,
            instruction = resolvedInstruction,
            triggerAtEpochMs = triggerAt,
            recurrence = recurrence,
            toolName = toolName.ifBlank { null },
            toolArguments = toolArguments,
            steps = parsedSteps.takeIf { it.isNotEmpty() }
        )

        val executionSummary = when {
            action.effectiveSteps().isEmpty() -> "notification only"
            action.effectiveSteps().size == 1 -> "tool=${action.effectiveSteps().first().toolName}"
            else -> "steps=${action.effectiveSteps().size}"
        }
        return ToolResult(
            call.toolName,
            "Scheduled '${action.title}' for ${formatEpoch(action.triggerAtEpochMs)} ($executionSummary, recurrence=${action.recurrence}, id=${action.id})."
        )
    }

    private fun parseScheduledSteps(rawSteps: Any?): List<ActionStep> {
        val rawList = rawSteps as? List<*> ?: return emptyList()
        return rawList.mapIndexedNotNull { index, raw ->
            val map = raw.asStringKeyedMap()
            val tool = (map["tool_name"] ?: map["name"] ?: map["tool"])?.toString()?.trim().orEmpty()
            if (tool.isBlank()) return@mapIndexedNotNull null
            val arguments = (map["tool_arguments"] ?: map["arguments"] ?: map["args"]).asStringKeyedMap()
            val title = map["title"]?.toString()?.trim().orEmpty().ifBlank { "Step ${index + 1}: $tool" }
            ActionStep(
                id = map["id"]?.toString()?.trim().orEmpty().ifBlank { java.util.UUID.randomUUID().toString() },
                title = title,
                toolName = tool,
                toolArguments = arguments,
                continueOnError = parseBooleanArgument(map["continue_on_error"]) ?: false
            )
        }
    }

    private fun runFuzzyAction(context: Context, call: ToolCall): ToolResult {
        val request = call.arguments["request"]?.toString()?.trim().orEmpty()
        if (request.isBlank()) {
            return ToolResult(call.toolName, "Missing required parameter: request", true)
        }
        val resolved = FuzzyActionInterpreter.resolveInstruction(request)
            ?: inferFromWeb(request)
            ?: return ToolResult(
                call.toolName,
                "I couldn't confidently parse that request yet. Try explicit schedule_action with run_at_local.",
                true
            )

        val action = ScheduledActionScheduler.createAndSchedule(
            context,
            resolved.title,
            resolved.instruction,
            resolved.triggerAtEpochMs,
            resolved.recurrence
        )

        return ToolResult(
            call.toolName,
            "Scheduled fuzzy action '${action.title}' for ${formatEpoch(action.triggerAtEpochMs)} (recurrence=${action.recurrence}, id=${action.id})."
        )
    }

    private fun runListActions(context: Context): ToolResult {
        val actions = ScheduledActionStore.list(context)
        if (actions.isEmpty()) return ToolResult("list_scheduled_actions", "No scheduled actions.")
        val lines = actions.joinToString("\n") {
            val steps = it.effectiveSteps()
            val execution = when {
                steps.isEmpty() -> "notification only"
                steps.size == 1 -> "tool=${steps.first().toolName}"
                else -> "steps=${steps.size}"
            }
            val state = if (it.completedAtEpochMs != null) {
                "completed ${formatEpoch(it.completedAtEpochMs)}"
            } else {
                "due ${formatEpoch(it.triggerAtEpochMs)}"
            }
            val lastRun = it.lastRun?.let { run -> ", last=${run.status}: ${run.summary}" }.orEmpty()
            "- ${it.title} $state ($execution, recurrence=${it.recurrence}, id=${it.id}$lastRun)"
        }
        return ToolResult("list_scheduled_actions", lines)
    }

    private fun runWebSearch(call: ToolCall): ToolResult {
        val query = call.arguments["query"]?.toString()?.trim().orEmpty()
        if (query.isBlank()) {
            return ToolResult(call.toolName, "Missing required parameter: query", true)
        }
        val result = WebActionReasoner.search(query)
        return ToolResult(call.toolName, WebActionReasoner.summarize(result), result.isError)
    }

    private fun inferFromWeb(request: String): FuzzyActionInterpreter.ResolvedAction? {
        val inferredTime = WebActionReasoner.inferTimeFromWeb(request) ?: return null
        return FuzzyActionInterpreter.ResolvedAction(
            title = "Web-inferred reminder",
            instruction = request.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
            triggerAtEpochMs = inferredTime,
            recurrence = ScheduledAction.RECURRENCE_NONE
        )
    }

    private fun parseLocalDateTime(value: String): Long? {
        return listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")
            .firstNotNullOfOrNull { pattern ->
                runCatching {
                    LocalDateTime.parse(value, DateTimeFormatter.ofPattern(pattern))
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                }.getOrNull()
            }
    }

    private fun formatEpoch(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .toString()

    private fun String?.ifNullOrBlank(default: String): String = if (this.isNullOrBlank()) default else this

    private fun String?.normalizeRecurrence(): String = when (this) {
        ScheduledAction.RECURRENCE_DAILY -> ScheduledAction.RECURRENCE_DAILY
        ScheduledAction.RECURRENCE_WEEKLY -> ScheduledAction.RECURRENCE_WEEKLY
        else -> ScheduledAction.RECURRENCE_NONE
    }

    private fun isMissingArgument(value: Any?): Boolean =
        value == null || (value is String && value.isBlank())

    private fun parseWholeNumberArgument(value: Any?): Int? {
        return when (value) {
            null -> null
            is Int -> value
            is Long -> value.toIntOrNullExact()
            is Short -> value.toInt()
            is Byte -> value.toInt()
            is Double -> value.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toInt()
            is Float -> value.takeIf { it.isFinite() && it % 1f == 0f }?.toInt()
            is Number -> {
                val longValue = value.toLong()
                if (value.toDouble().isFinite() && value.toDouble() == longValue.toDouble()) {
                    longValue.toIntOrNullExact()
                } else {
                    null
                }
            }
            is String -> {
                val trimmed = value.trim()
                if (!trimmed.matches(Regex("^-?\\d+(?:\\.0+)?$"))) {
                    null
                } else {
                    trimmed.substringBefore('.').toIntOrNull()
                }
            }
            else -> null
        }
    }

    private fun Long.toIntOrNullExact(): Int? = if (this in Int.MIN_VALUE..Int.MAX_VALUE) toInt() else null

    private fun parseBooleanArgument(value: Any?): Boolean? {
        return when (value) {
            null -> null
            is Boolean -> value
            is String -> when (value.trim().lowercase()) {
                "true", "1", "yes", "on", "enable", "enabled" -> true
                "false", "0", "no", "off", "disable", "disabled" -> false
                else -> null
            }
            is Number -> value.toInt() != 0
            else -> null
        }
    }

    private fun Any?.asStringKeyedMap(): Map<String, Any> {
        return when (this) {
            null -> emptyMap()
            is Map<*, *> -> this.entries.mapNotNull { (key, value) ->
                val stringKey = key as? String ?: return@mapNotNull null
                value?.let { stringKey to it }
            }.toMap()
            else -> emptyMap()
        }
    }
}

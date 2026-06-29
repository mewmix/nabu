package com.mewmix.nabu.uiagent

import android.content.Context
import android.graphics.BitmapFactory
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmImageInput
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.tools.GlaiveBridge
import com.mewmix.nabu.tools.ToolCall
import com.mewmix.nabu.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class UiAutomationOrchestrator(
    private val context: Context,
    private val backend: LlmBackend,
    private val requestConfirmation: suspend (String) -> Boolean,
    private val onProgress: (phase: String, detail: String) -> Unit = { _, _ -> },
    private val logger: (String) -> Unit = {}
) {
    private data class Observation(
        val bridgeObservationId: String,
        val screen: UiScreenState,
        val screenshotPath: String?
    )

    suspend fun run(goal: String): ToolResult {
        if (goal.isBlank()) return failure("UI automation goal is blank.")
        onProgress("Observe", "Capturing the active window and accessibility tree")
        delay(INITIAL_OBSERVATION_DELAY_MS)
        var observation = observe() ?: return failure("Unable to observe the current UI through Glaive.")
        var unchangedCount = 0

        repeat(MAX_ACTIONS) { actionIndex ->
            onProgress("Plan ${actionIndex + 1}", "Choosing the next UI action")
            val rawPlan = plan(goal, observation)
                ?: return failure("The UI planner returned no usable plan.")
            val actionPlan = runCatching {
                UiActionPlanParser.parsePlannerOutput(
                    rawJson = extractJson(rawPlan),
                    knownGoal = goal,
                    knownScreenId = observation.screen.screenId
                )
            }
                .getOrElse { error ->
                    logger("UiAutomation planner parse failed: ${error.message}; output=${rawPlan.take(500)}")
                    return failure("The UI planner returned invalid action JSON: ${error.message}")
                }
            when (val decision = UiActionValidator.validate(actionPlan, observation.screen)) {
                UiPlanDecision.Allow -> Unit
                is UiPlanDecision.Invalid -> return failure(decision.reason)
                is UiPlanDecision.Block -> return failure(decision.reason)
                is UiPlanDecision.RequireConfirmation -> {
                    if (!requestConfirmation(describeConfirmation(actionPlan, decision.reason))) {
                        return failure("User denied UI action confirmation.")
                    }
                }
            }

            val action = actionPlan.steps.first { it !is UiActionStep.Assert }
            onProgress("Decision ${actionIndex + 1}", "Planner selected ${actionLabel(action)}")
            when (action) {
                is UiActionStep.Done -> return success(action.summary)
                is UiActionStep.AskUser -> return success("User input required: ${action.reason}")
                is UiActionStep.Wait -> delay(action.milliseconds)
                else -> {
                    onProgress("Execute ${actionIndex + 1}", "Running ${actionLabel(action)}")
                    val result = execute(action, observation)
                    if (result.isError) return result
                }
            }

            onProgress("Verify ${actionIndex + 1}", "Observing the resulting screen")
            val next = observe() ?: return failure("UI action ran, but the resulting screen could not be observed.")
            val assertion = actionPlan.steps.filterIsInstance<UiActionStep.Assert>().singleOrNull()?.condition
            if (assertion != null && assertionMatches(assertion, next.screen)) {
                return success("Completed UI goal: $goal")
            }

            unchangedCount = if (next.screen.screenId == observation.screen.screenId) unchangedCount + 1 else 0
            logger(
                "UiAutomation step=${actionIndex + 1} action=${action::class.simpleName} " +
                    "screen=${observation.screen.screenId}->${next.screen.screenId} unchanged=$unchangedCount"
            )
            if (unchangedCount >= MAX_UNCHANGED_OBSERVATIONS) {
                return failure("UI did not change after repeated actions; stopping automation.")
            }
            observation = next
        }
        return failure("UI action limit reached before the goal was verified.")
    }

    private suspend fun observe(): Observation? {
        val result = GlaiveBridge.executeTool(context, ToolCall("observe_ui", emptyMap()))
        if (result.isError) {
            logger("UiAutomation observe_ui failed: ${result.output}")
            return null
        }
        return runCatching {
            val envelope = JSONObject(result.output)
            require(envelope.optInt("schema_version") == 2) { "Unsupported observation schema." }
            val xmlPath = envelope.getString("xml_path")
            val xmlResult = GlaiveBridge.executeTool(
                context,
                ToolCall("read_ui_xml", mapOf("path" to xmlPath))
            )
            require(!xmlResult.isError) { xmlResult.output }
            val packageName = envelope.optString("package").takeIf(String::isNotBlank)
            val windowTitle = envelope.optString("window_title").takeIf(String::isNotBlank)
            Observation(
                bridgeObservationId = envelope.getString("observation_id"),
                screen = UiTreeIndexer.parse(xmlResult.output, packageName, windowTitle),
                screenshotPath = envelope.optString("screenshot_path").takeIf(String::isNotBlank)
            )
        }.onFailure { logger("UiAutomation observation parse failed: ${it.message}") }.getOrNull()
    }

    private suspend fun plan(goal: String, observation: Observation): String? {
        val userContent = buildPlannerInput(goal, observation.screen)
        val images = if (backend.supportsImageInput()) {
            observation.screenshotPath
                ?.let(BitmapFactory::decodeFile)
                ?.let(::LlmImageInput)
                ?.let(::listOf)
                .orEmpty()
        } else {
            emptyList()
        }
        val conversation = listOf(
            LlmMessage(role = "system", content = PLANNER_SYSTEM_PROMPT),
            LlmMessage(role = "user", content = userContent, images = images)
        )
        val completion = CompletableDeferred<String?>()
        val completed = AtomicBoolean(false)
        val output = StringBuilder()
        backend.sendMessage(conversation) { partial, done ->
            if (partial.isNotEmpty()) output.append(partial)
            if (done && completed.compareAndSet(false, true)) completion.complete(output.toString())
        }
        return withTimeoutOrNull(PLANNER_TIMEOUT_MS) { completion.await() }
    }

    private suspend fun execute(action: UiActionStep, observation: Observation): ToolResult {
        val arguments = linkedMapOf<String, Any>("observation_id" to observation.bridgeObservationId)
        val toolName = when (action) {
            is UiActionStep.Tap -> {
                addTarget(arguments, action.target, observation.screen)
                "ui_tap"
            }
            is UiActionStep.LongPress -> {
                addTarget(arguments, action.target, observation.screen)
                "ui_long_press"
            }
            is UiActionStep.TypeText -> {
                action.target?.let { addTarget(arguments, it, observation.screen) }
                arguments["text"] = action.text
                "ui_set_text"
            }
            is UiActionStep.Scroll -> {
                action.target?.let { addTarget(arguments, it, observation.screen) }
                arguments["direction"] = action.direction.name.lowercase()
                "ui_scroll"
            }
            UiActionStep.PressBack -> {
                arguments["global_action"] = "back"
                "ui_global_action"
            }
            UiActionStep.PressHome -> {
                arguments["global_action"] = "home"
                "ui_global_action"
            }
            else -> return failure("Unsupported executable UI action.")
        }
        return GlaiveBridge.executeTool(context, ToolCall(toolName, arguments))
    }

    private fun addTarget(
        arguments: MutableMap<String, Any>,
        target: UiTarget,
        screen: UiScreenState
    ) {
        val element = target.elementId?.let(screen::element)
        if (element != null) {
            arguments["selector"] = mapOf(
                "tree_path" to element.treePath,
                "resource_id" to element.resourceId.orEmpty(),
                "text" to element.text.orEmpty(),
                "content_desc" to element.contentDescription.orEmpty(),
                "class" to element.className.orEmpty()
            )
        }
        val bounds = element?.bounds ?: target.fallbackBounds
        if (bounds != null) arguments["fallback_bounds"] = bounds.toList()
    }

    private fun assertionMatches(assertion: UiAssertion, screen: UiScreenState): Boolean {
        val element = assertion.elementId?.let(screen::element)
        if (assertion.elementId != null && element == null) return false
        assertion.checked?.let { expected -> if (element?.checked != expected) return false }
        assertion.textContains?.let { expected ->
            val matches = if (element != null) {
                listOfNotNull(element.text, element.contentDescription).any { it.contains(expected, true) }
            } else {
                screen.elements.any { candidate ->
                    listOfNotNull(candidate.text, candidate.contentDescription).any { it.contains(expected, true) }
                }
            }
            if (!matches) return false
        }
        return true
    }

    private fun buildPlannerInput(goal: String, screen: UiScreenState): String {
        val elements = JsonArray()
        screen.elements.asSequence()
            .filter { it.visible }
            .filter {
                it.clickable || it.editable || it.scrollable || it.checkable ||
                    !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank()
            }
            .take(MAX_PROMPT_ELEMENTS)
            .forEach { element ->
                elements.add(JsonObject().apply {
                    addProperty("id", element.id)
                    element.text?.let { addProperty("text", it) }
                    element.contentDescription?.let { addProperty("content_desc", it) }
                    element.resourceId?.let { addProperty("resource_id", it) }
                    element.className?.let { addProperty("class", it) }
                    element.bounds?.let { bounds ->
                        add("bounds", JsonArray().apply { bounds.toList().forEach(::add) })
                    }
                    addProperty("clickable", element.clickable)
                    addProperty("enabled", element.enabled)
                    addProperty("editable", element.editable)
                    addProperty("scrollable", element.scrollable)
                    addProperty("checkable", element.checkable)
                    addProperty("checked", element.checked)
                    addProperty("password", element.password)
                })
            }
        return JsonObject().apply {
            addProperty("goal", goal)
            addProperty("screen_id", screen.screenId)
            addProperty("package", screen.packageName)
            addProperty("activity", screen.activityName)
            add("elements", elements)
        }.toString()
    }

    private fun describeConfirmation(plan: UiActionPlan, reason: String): String =
        "$reason\n\nGoal: ${plan.goal}\nAction: ${plan.steps.first { it !is UiActionStep.Assert }::class.simpleName}"

    private fun extractJson(raw: String): String {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        require(start >= 0 && end > start) { "No JSON object found." }
        return trimmed.substring(start, end + 1)
    }

    private fun success(output: String) = ToolResult(CONTROL_UI_TOOL, output)
    private fun failure(output: String) = ToolResult(CONTROL_UI_TOOL, output, true)

    private fun actionLabel(action: UiActionStep): String = when (action) {
        is UiActionStep.Tap -> "tap"
        is UiActionStep.LongPress -> "long press"
        is UiActionStep.TypeText -> "type text"
        UiActionStep.PressBack -> "press back"
        UiActionStep.PressHome -> "press home"
        is UiActionStep.Scroll -> "scroll ${action.direction.name.lowercase()}"
        is UiActionStep.Wait -> "wait"
        is UiActionStep.Assert -> "assert"
        is UiActionStep.AskUser -> "ask user"
        is UiActionStep.Done -> "done"
    }

    companion object {
        const val CONTROL_UI_TOOL = "control_ui"
        private const val MAX_ACTIONS = 5
        private const val MAX_UNCHANGED_OBSERVATIONS = 2
        private const val MAX_PROMPT_ELEMENTS = 100
        private const val PLANNER_TIMEOUT_MS = 45_000L
        private const val INITIAL_OBSERVATION_DELAY_MS = 500L

        private val PLANNER_SYSTEM_PROMPT = """
            You are an Android UI automation planner. Return only one valid JSON object matching this schema:
            {
              "goal": "the user's goal",
              "screen_id": "the exact screen_id provided",
              "steps": [
                {
                  "action": "tap|long_press|type_text|scroll|wait|ask_user|done|assert",
                  "target": {
                    "element_id": "optional element id",
                    "fallback_bounds": [0, 0, 100, 100]
                  },
                  "text": "text to type (if action=type_text)",
                  "direction": "UP|DOWN|LEFT|RIGHT (if action=scroll)",
                  "ms": 1000,
                  "reason": "reason to ask user (if action=ask_user)",
                  "summary": "summary of completion (if action=done)",
                  "condition": {
                    "element_id": "optional id",
                    "text_contains": "optional text",
                    "checked": true
                  }
                }
              ]
            }

            Use the supplied goal, screenshot when present, and indexed UI elements.
            The screen_id must exactly match the supplied screen_id.
            Emit exactly one non-assert action and optionally one trailing assert action in the steps array.
            Prefer element_id. Use fallback_bounds (exactly 4 integers: left, top, right, bottom) only when no reliable element exists.
            Supported actions: tap, long_press, type_text, press_back, press_home, scroll, wait, ask_user, done, assert.
            Use done with a short summary when the goal is already satisfied.
            Use ask_user when confidence is low or the target is ambiguous.
            Never plan payments, purchases, passwords, 2FA, account deletion, factory reset, permission escalation, or unknown APK installation.
            Include an assertion after state-changing actions when the expected result is visible.
        """.trimIndent()
    }
}

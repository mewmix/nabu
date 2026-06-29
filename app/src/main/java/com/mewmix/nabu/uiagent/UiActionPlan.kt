package com.mewmix.nabu.uiagent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class UiActionPlan(
    val goal: String,
    val screenId: String,
    val steps: List<UiActionStep>
)

sealed interface UiActionStep {
    data class Tap(val target: UiTarget) : UiActionStep
    data class LongPress(val target: UiTarget) : UiActionStep
    data class TypeText(val text: String, val target: UiTarget?) : UiActionStep
    data object PressBack : UiActionStep
    data object PressHome : UiActionStep
    data class Scroll(val direction: ScrollDirection, val target: UiTarget?) : UiActionStep
    data class Wait(val milliseconds: Long) : UiActionStep
    data class Assert(val condition: UiAssertion) : UiActionStep
    data class AskUser(val reason: String) : UiActionStep
    data class Done(val summary: String) : UiActionStep
}

data class UiTarget(
    val elementId: String?,
    val fallbackBounds: UiBounds?
)

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

data class UiAssertion(
    val elementId: String?,
    val textContains: String?,
    val checked: Boolean?
)

object UiActionPlanParser {
    fun parsePlannerOutput(rawJson: String, knownGoal: String, knownScreenId: String): UiActionPlan {
        val root = JsonParser.parseString(rawJson).asJsonObject
        if (!root.has("goal")) root.addProperty("goal", knownGoal)
        if (!root.has("screen_id")) root.addProperty("screen_id", knownScreenId)
        if (!root.has("steps") && root.has("action")) {
            val step = root.deepCopy().apply {
                remove("goal")
                remove("screen_id")
            }
            val action = step.get("action")?.asString.orEmpty()
            if (action in setOf("tap", "long_press", "type_text", "scroll") && !step.has("target")) {
                val target = JsonObject()
                step.remove("element_id")?.let { target.add("element_id", it) }
                step.remove("fallback_bounds")?.let { target.add("fallback_bounds", it) }
                if (target.size() > 0) step.add("target", target)
            }
            if (action == "assert" && !step.has("condition")) {
                val condition = JsonObject()
                listOf("element_id", "text_contains", "checked").forEach { name ->
                    step.remove(name)?.let { condition.add(name, it) }
                }
                if (condition.size() > 0) step.add("condition", condition)
            }
            root.add("steps", JsonArray().apply { add(step) })
        }
        return parse(root.toString())
    }

    fun parse(rawJson: String): UiActionPlan {
        val root = JsonParser.parseString(rawJson).asJsonObject
        val goal = root.requiredString("goal")
        val screenId = root.requiredString("screen_id")
        val rawSteps = root.getAsJsonArray("steps") ?: error("Missing steps array.")
        require(rawSteps.size() in 1..2) { "A plan must contain one action and at most one assertion." }
        val steps = rawSteps.map { parseStep(it.asJsonObject) }
        require(steps.count { it is UiActionStep.Assert } <= 1) { "A plan may contain at most one assertion." }
        require(steps.count { it !is UiActionStep.Assert } == 1) { "A plan must contain exactly one non-assert action." }
        require(steps.last() is UiActionStep.Assert || steps.size == 1) { "An assertion must be the final step." }
        return UiActionPlan(goal, screenId, steps)
    }

    private fun parseStep(json: JsonObject): UiActionStep = when (json.requiredString("action")) {
        "tap" -> UiActionStep.Tap(parseTarget(json.getAsJsonObject("target")))
        "long_press" -> UiActionStep.LongPress(parseTarget(json.getAsJsonObject("target")))
        "type_text" -> UiActionStep.TypeText(
            text = json.requiredString("text"),
            target = json.getAsJsonObject("target")?.let(::parseTarget)
        )
        "press_back" -> UiActionStep.PressBack
        "press_home" -> UiActionStep.PressHome
        "scroll" -> UiActionStep.Scroll(
            direction = ScrollDirection.valueOf(json.requiredString("direction").uppercase()),
            target = json.getAsJsonObject("target")?.let(::parseTarget)
        )
        "wait" -> UiActionStep.Wait(json.get("ms")?.asLong ?: error("Missing ms."))
        "assert" -> UiActionStep.Assert(parseAssertion(json.getAsJsonObject("condition")))
        "ask_user" -> UiActionStep.AskUser(json.requiredString("reason"))
        "done" -> UiActionStep.Done(json.requiredString("summary"))
        else -> error("Unsupported UI action '${json.get("action")?.asString.orEmpty()}'.")
    }

    private fun parseTarget(json: JsonObject): UiTarget {
        val elementId = json.optionalString("element_id")
        val bounds = json.getAsJsonArray("fallback_bounds")?.toBounds()
        require(elementId != null || bounds != null) { "A target requires element_id or fallback_bounds." }
        return UiTarget(elementId, bounds)
    }

    private fun parseAssertion(json: JsonObject): UiAssertion {
        val elementId = json.optionalString("element_id")
        val textContains = json.optionalString("text_contains")
        val checked = json.get("checked")?.asBoolean
        require(elementId != null || textContains != null || checked != null) { "Assertion condition is empty." }
        return UiAssertion(elementId, textContains, checked)
    }

    private fun JsonArray.toBounds(): UiBounds {
        require(size() == 4) { "Bounds must contain four integers." }
        return UiBounds(get(0).asInt, get(1).asInt, get(2).asInt, get(3).asInt).also {
            require(it.isValid) { "Bounds are invalid." }
        }
    }

    private fun JsonObject.requiredString(name: String): String =
        optionalString(name) ?: error("Missing $name.")

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }
}

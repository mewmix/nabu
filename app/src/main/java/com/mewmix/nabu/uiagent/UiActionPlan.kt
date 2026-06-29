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
        val rawSteps = root.optJsonArray("steps")
            ?: root.optJsonObject("steps")?.let { JsonArray().apply { add(it) } }
            ?: error("Missing steps array.")
        require(rawSteps.size() > 0) { "A plan must contain at least one step." }
        val steps = rawSteps.map { parseStep(it.asJsonObject) }
        require(steps.any { it !is UiActionStep.Assert }) { "A plan must contain at least one non-assert action." }
        return UiActionPlan(goal, screenId, steps)
    }

    private fun parseStep(json: JsonObject): UiActionStep = when (json.requiredString("action")) {
        "tap" -> UiActionStep.Tap(parseTarget(json.optJsonObject("target")) ?: error("Missing or invalid target."))
        "long_press" -> UiActionStep.LongPress(parseTarget(json.optJsonObject("target")) ?: error("Missing or invalid target."))
        "type_text" -> UiActionStep.TypeText(
            text = json.requiredString("text"),
            target = parseTarget(json.optJsonObject("target"))
        )
        "press_back" -> UiActionStep.PressBack
        "press_home" -> UiActionStep.PressHome
        "scroll" -> UiActionStep.Scroll(
            direction = ScrollDirection.valueOf(json.requiredString("direction").uppercase()),
            target = parseTarget(json.optJsonObject("target"))
        )
        "wait" -> UiActionStep.Wait(json.optLong("ms") ?: error("Missing ms."))
        "assert" -> UiActionStep.Assert(parseAssertion(json.optJsonObject("condition")) ?: error("Missing or invalid condition."))
        "ask_user" -> UiActionStep.AskUser(json.requiredString("reason"))
        "done" -> UiActionStep.Done(json.requiredString("summary"))
        else -> error("Unsupported UI action '${json.get("action")?.asString.orEmpty()}'.")
    }

    private fun parseTarget(json: JsonObject?): UiTarget? {
        if (json == null) return null
        val elementId = json.optionalString("element_id")
        val bounds = json.optJsonArray("fallback_bounds")?.toBounds()
        if (elementId == null && bounds == null) return null
        return UiTarget(elementId, bounds)
    }

    private fun parseAssertion(json: JsonObject?): UiAssertion? {
        if (json == null) return null
        val elementId = json.optionalString("element_id")
        val textContains = json.optionalString("text_contains")
        val checked = json.optBoolean("checked")
        if (elementId == null && textContains == null && checked == null) return null
        return UiAssertion(elementId, textContains, checked)
    }

    private fun JsonArray.toBounds(): UiBounds? {
        val ints = mapNotNull {
            if (it.isJsonPrimitive && it.asJsonPrimitive.isNumber) it.asInt
            else if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString.toIntOrNull()
            else null
        }
        if (ints.size == 4) {
            val bounds = UiBounds(ints[0], ints[1], ints[2], ints[3])
            if (bounds.isValid) return bounds
        }
        return null
    }

    private fun JsonObject.requiredString(name: String): String =
        optionalString(name) ?: error("Missing $name.")

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.optJsonObject(name: String): JsonObject? {
        val element = get(name) ?: return null
        if (element.isJsonObject) return element.asJsonObject
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            val str = element.asString.trim()
            if (str.startsWith("{")) {
                return runCatching { JsonParser.parseString(str).asJsonObject }.getOrNull()
            }
        }
        return null
    }

    private fun JsonObject.optJsonArray(name: String): JsonArray? {
        val element = get(name) ?: return null
        if (element.isJsonArray) return element.asJsonArray
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            val str = element.asString.trim()
            if (str.startsWith("[")) {
                return runCatching { JsonParser.parseString(str).asJsonArray }.getOrNull()
            }
        }
        return null
    }

    private fun JsonObject.optBoolean(name: String): Boolean? {
        val element = get(name) ?: return null
        if (element.isJsonNull) return null
        if (element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) return element.asBoolean
        return element.asString.equals("true", ignoreCase = true)
    }

    private fun JsonObject.optLong(name: String): Long? {
        val element = get(name) ?: return null
        if (element.isJsonNull) return null
        if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) return element.asLong
        return element.asString.toLongOrNull()
    }
}

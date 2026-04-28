package com.mewmix.nabu.actions

import android.content.Context
import com.mewmix.nabu.agent.AgentTurnRunner
import com.mewmix.nabu.chat.LlmBackendFactory
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.tools.ToolCall
import com.mewmix.nabu.tools.ToolCallProtocol
import com.mewmix.nabu.tools.ToolResult
import com.mewmix.nabu.utils.DebugLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

object ScheduledAgentStepExecutor {
    private const val DEFAULT_MAX_TOOL_CALLS = 4
    private const val DEFAULT_TIMEOUT_MS = 120_000L

    fun execute(context: Context, action: ScheduledAction, step: ActionStep): ToolResult {
        val instruction = step.toolArguments["instruction"]?.toString()?.trim().orEmpty()
        if (instruction.isBlank()) {
            return ToolResult(
                toolName = step.toolName,
                output = "Missing required parameter: instruction",
                isError = true
            )
        }
        val modelId = step.toolArguments["model_id"]?.toString()?.trim().orEmpty()
        if (modelId.isBlank()) {
            return ToolResult(
                toolName = step.toolName,
                output = "Missing required parameter: model_id",
                isError = true
            )
        }
        val maxToolCalls = parsePositiveInt(step.toolArguments["max_tool_calls"]) ?: DEFAULT_MAX_TOOL_CALLS
        val timeoutMs = parsePositiveLong(step.toolArguments["timeout_ms"]) ?: DEFAULT_TIMEOUT_MS

        return runBlocking {
            withTimeoutOrNull(timeoutMs) {
                executeBlocking(context.applicationContext, action, step, instruction, modelId, maxToolCalls)
            } ?: ToolResult(
                toolName = step.toolName,
                output = "Scheduled agent step timed out after ${timeoutMs / 1000}s.",
                isError = true
            )
        }
    }

    private suspend fun executeBlocking(
        context: Context,
        action: ScheduledAction,
        step: ActionStep,
        instruction: String,
        modelId: String,
        maxToolCalls: Int
    ): ToolResult = coroutineScope {
        val created = LlmBackendFactory.create(context, modelId)
            ?: return@coroutineScope ToolResult(
                toolName = step.toolName,
                output = "Could not create LLM backend for model_id '$modelId'.",
                isError = true
            )
        val completion = CompletableDeferred<AgentTurnRunner.Result>()
        try {
            val conversation = buildConversation(action, instruction, created.maxContextTokens)
            val availableToolNames = ActionTools.schedulableToolsForAgent()
                .map { it.name }
                .toSet()
            AgentTurnRunner(
                backend = created.backend,
                scope = this,
                toolExecutor = { call -> executeBackgroundTool(context, call) },
                inferToolCallFromModelFailure = { _, _ -> null },
                recoveryConversationProvider = { conversation.takeLast(2) },
                logger = { DebugLogger.log(it) }
            ).run(
                initialConversation = conversation,
                latestUserMessage = instruction,
                availableToolNames = availableToolNames,
                maxToolCalls = maxToolCalls,
                onPartialText = {},
                onSpeakablePartial = { _, _ -> },
                onSuppressSpeakablePartials = {},
                onToolStart = {},
                onComplete = { completion.complete(it) }
            )
            val result = completion.await()
            val transcript = result.transcript.joinToString("\n") { exchange ->
                val state = if (exchange.result.isError) "failed" else "ok"
                "- ${exchange.call.toolName} ($state): ${exchange.result.output.take(240)}"
            }
            val output = listOf(result.finalResponse.trim(), transcript)
                .filter { it.isNotBlank() }
                .joinToString("\n\nTool transcript:\n")
            val hasError = result.transcript.any { it.result.isError }
            ToolResult(
                toolName = step.toolName,
                output = output.ifBlank { "Scheduled agent produced no output." },
                isError = hasError
            )
        } finally {
            created.backend.close()
        }
    }

    private fun buildConversation(
        action: ScheduledAction,
        instruction: String,
        maxContextTokens: Int
    ): List<LlmMessage> {
        val tools = ActionTools.schedulableToolsForAgent()
        val system = ToolCallProtocol.buildSystemPrompt(
            basePrompt = """
                You are Nabu running a scheduled background action.
                Execute only background-safe tools when needed.
                Do not ask the user for clarification; use the scheduled instruction and available context.
                Finish with a concise summary of what happened.
            """.trimIndent(),
            tools = tools
        )
        val priorRuns = action.runHistory.orEmpty().take(3).joinToString("\n") { run ->
            "- ${run.status}: ${run.summary}"
        }
        val context = buildString {
            appendLine("Scheduled action: ${action.title}")
            appendLine("Action instruction: ${action.instruction}")
            if (priorRuns.isNotBlank()) {
                appendLine("Recent runs:")
                appendLine(priorRuns)
            }
            if (action.effectiveSteps().isNotEmpty()) {
                appendLine("Plan has ${action.effectiveSteps().size} total steps.")
            }
            appendLine("Current agent instruction: $instruction")
        }
        val messages = listOf(
            LlmMessage("system", system),
            LlmMessage("user", context)
        )
        return trimMessages(messages, maxContextTokens)
    }

    private fun trimMessages(messages: List<LlmMessage>, maxContextTokens: Int): List<LlmMessage> {
        val maxChars = (maxContextTokens.coerceAtLeast(256) * 4).coerceAtMost(24_000)
        val system = messages.firstOrNull { it.role == "system" }
        val user = messages.lastOrNull { it.role == "user" }
        if (system == null || user == null) return messages
        val budgetForUser = (maxChars - system.content.length).coerceAtLeast(1000)
        return listOf(
            system,
            user.copy(content = user.content.takeLast(budgetForUser))
        )
    }

    private fun executeBackgroundTool(context: Context, call: ToolCall): ToolResult {
        if (call.toolName == ActionTools.SCHEDULED_AGENT_STEP_TOOL) {
            return ToolResult(
                toolName = call.toolName,
                output = "Nested scheduled agents are not allowed.",
                isError = true
            )
        }
        if (!ActionTools.isSchedulableTool(call.toolName)) {
            return ToolResult(
                toolName = call.toolName,
                output = "Tool '${call.toolName}' cannot run from scheduled background execution.",
                isError = true
            )
        }
        return ActionTools.execute(context, call) ?: ToolResult(
            toolName = call.toolName,
            output = "Tool '${call.toolName}' is unavailable.",
            isError = true
        )
    }

    private fun parsePositiveInt(value: Any?): Int? {
        return when (value) {
            is Number -> value.toInt().takeIf { it > 0 }
            is String -> value.trim().toIntOrNull()?.takeIf { it > 0 }
            else -> null
        }
    }

    private fun parsePositiveLong(value: Any?): Long? {
        return when (value) {
            is Number -> value.toLong().takeIf { it > 0L }
            is String -> value.trim().toLongOrNull()?.takeIf { it > 0L }
            else -> null
        }
    }
}

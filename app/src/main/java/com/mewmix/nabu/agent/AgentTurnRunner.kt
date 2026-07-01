package com.mewmix.nabu.agent

import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.tools.ToolCall
import com.mewmix.nabu.tools.ToolCallProtocol
import com.mewmix.nabu.tools.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AgentTurnRunner(
    private val backend: LlmBackend,
    private val scope: CoroutineScope,
    private val toolExecutor: suspend (ToolCall) -> ToolResult,
    private val inferToolCallFromModelFailure: (String, Set<String>) -> ToolCall?,
    private val recoveryConversationProvider: () -> List<LlmMessage>,
    private val shouldCompleteAfterToolResult: (ToolCall, ToolResult) -> Boolean = { _, _ -> false },
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: (String) -> Unit = {}
) {
    data class Result(
        val finalResponse: String,
        val speakOutput: Boolean,
        val transcript: List<ToolExchange>
    )

    data class ToolExchange(
        val call: ToolCall,
        val result: ToolResult,
        val inferred: Boolean
    )

    fun run(
        initialConversation: List<LlmMessage>,
        latestUserMessage: String,
        availableToolNames: Set<String>,
        maxToolCalls: Int,
        onPartialText: (String) -> Unit,
        onSpeakablePartial: (partial: String, done: Boolean) -> Unit,
        onSuppressSpeakablePartials: () -> Unit,
        onToolStart: (ToolCall) -> Unit,
        onBenchmarkPartial: (String) -> Unit = {},
        onRecoveryRetry: () -> Unit = {},
        onComplete: (Result) -> Unit
    ) {
        val transcript = mutableListOf<ToolExchange>()
        val inferredDirectToolCall = inferToolCallFromModelFailure(latestUserMessage, availableToolNames)
        val mayCallTool = inferredDirectToolCall != null

        fun runInference(
            conversation: List<LlmMessage>,
            remainingToolCalls: Int,
            lastToolResult: ToolResult? = null,
            usedBlankRecovery: Boolean = false
        ) {
            val responseBuilder = StringBuilder()
            var suppressSpeechForThisPass = false

            backend.sendMessage(conversation) { partial, done ->
                if (!done) {
                    onBenchmarkPartial(partial)
                    responseBuilder.append(partial)
                    if (partial.contains("<tool_call", ignoreCase = true)) {
                        suppressSpeechForThisPass = true
                        onSuppressSpeakablePartials()
                    } else if (!suppressSpeechForThisPass && (!mayCallTool || lastToolResult != null)) {
                        onSpeakablePartial(partial, false)
                    }
                    if (!suppressSpeechForThisPass) {
                        onPartialText(responseBuilder.toString())
                    }
                    return@sendMessage
                }

                if (partial.isNotEmpty()) {
                    responseBuilder.append(partial)
                    if (partial.contains("<tool_call", ignoreCase = true)) {
                        suppressSpeechForThisPass = true
                        onSuppressSpeakablePartials()
                    } else if (!suppressSpeechForThisPass && (!mayCallTool || lastToolResult != null)) {
                        onSpeakablePartial(partial, true)
                    }
                }

                val finalResponse = responseBuilder.toString()
                val extractedToolCall = ToolCallProtocol.extractToolCall(finalResponse)
                val toolCall = extractedToolCall?.takeIf { it.toolName in availableToolNames }
                if (extractedToolCall != null && toolCall == null) {
                    logger(
                        "AgentTurnRunner: rejected unadvertised tool ${extractedToolCall.toolName}; " +
                            "allowed=${availableToolNames.sorted().joinToString(",")}"
                    )
                }
                val looksLikeMalformedToolAttempt =
                    finalResponse.trim().startsWith("```") ||
                        ToolCallProtocol.looksLikeToolControlText(finalResponse) ||
                        extractedToolCall != null && toolCall == null
                val looksLikeBackendFailure =
                    finalResponse.contains("generation failed", ignoreCase = true) ||
                        finalResponse.contains("inference failed", ignoreCase = true)
                val inferredToolCall =
                    if (toolCall == null && lastToolResult == null &&
                        (
                            finalResponse.isBlank() ||
                                looksLikeMalformedToolAttempt ||
                                looksLikeBackendFailure ||
                                inferredDirectToolCall != null
                            )
                    ) {
                        inferredDirectToolCall
                    } else {
                        null
                    }
                val effectiveToolCall = toolCall ?: inferredToolCall
                logger(
                    "AgentTurnRunner: final response len=${finalResponse.length}, " +
                        "toolDetected=${effectiveToolCall != null}, remainingToolCalls=$remainingToolCalls"
                )
                if (toolCall == null && inferredToolCall != null) {
                    logger(
                        "AgentTurnRunner: inferred tool ${inferredToolCall.toolName} from failed model response " +
                            "using user message: ${latestUserMessage.take(160)}"
                    )
                }
                if (effectiveToolCall == null && finalResponse.isNotBlank()) {
                    logger("AgentTurnRunner: final response preview: ${finalResponse.take(220)}")
                }

                if (effectiveToolCall != null && remainingToolCalls > 0) {
                    logger("AgentTurnRunner: executing tool ${effectiveToolCall.toolName} with ${effectiveToolCall.arguments}")
                    onToolStart(effectiveToolCall)
                    scope.launch(inferenceDispatcher) {
                        val result = toolExecutor(effectiveToolCall)
                        logger(
                            "AgentTurnRunner: tool ${effectiveToolCall.toolName} finished " +
                                "(error=${result.isError}, outputLength=${result.output.length})"
                        )
                        transcript += ToolExchange(
                            call = effectiveToolCall,
                            result = result,
                            inferred = toolCall == null
                        )
                        if (shouldCompleteAfterToolResult(effectiveToolCall, result)) {
                            val response = summarizeDirectToolResultMessage(result)
                            onComplete(
                                Result(
                                    finalResponse = response,
                                    speakOutput = true,
                                    transcript = transcript.toList()
                                )
                            )
                            return@launch
                        }
                        val modelToolCallMessage = if (toolCall != null) {
                            val endTag = "</tool_call>"
                            val firstCallIndex = finalResponse.indexOf(endTag, ignoreCase = true)
                            if (firstCallIndex != -1) {
                                finalResponse.substring(0, firstCallIndex + endTag.length)
                            } else {
                                finalResponse
                            }
                        } else {
                            formatSyntheticToolCall(effectiveToolCall)
                        }
                        val toolResultImages = if (result.attachedImagePath != null) {
                            val bitmap = android.graphics.BitmapFactory.decodeFile(result.attachedImagePath)
                            if (bitmap != null) listOf(com.mewmix.nabu.chat.LlmImageInput(bitmap)) else emptyList()
                        } else emptyList()
                        val followUpConversation = conversation + listOf(
                            LlmMessage(role = "model", content = modelToolCallMessage),
                            LlmMessage(
                                role = "user",
                                content = ToolCallProtocol.formatToolResultForModel(result),
                                images = toolResultImages
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
                    val recoveryConversation = recoveryConversationProvider()
                    val recoveryWouldChangePrompt = recoveryConversation != conversation
                    if (recoveryWouldChangePrompt) {
                        logger("AgentTurnRunner: blank model response, retrying once with compacted single-turn recovery context")
                        onRecoveryRetry()
                        runInference(
                            recoveryConversation,
                            remainingToolCalls = remainingToolCalls,
                            lastToolResult = null,
                            usedBlankRecovery = true
                        )
                        return@sendMessage
                    }
                    logger("AgentTurnRunner: blank model response; skipping compacted-context retry because recovery prompt is unchanged")
                }

                if (effectiveToolCall == null && looksLikeMalformedToolAttempt) {
                    logger("AgentTurnRunner: quarantined unparseable tool-like model response")
                    onComplete(
                        Result(
                            finalResponse = "I tried to call a tool, but could not parse the tool request.",
                            speakOutput = true,
                            transcript = transcript.toList()
                        )
                    )
                    return@sendMessage
                }

                val exhaustedToolBudget = effectiveToolCall != null && remainingToolCalls <= 0
                val shouldFallbackToToolResult = lastToolResult != null && (
                    finalResponse.isBlank() ||
                        effectiveToolCall != null ||
                        ToolCallProtocol.looksLikeToolControlText(finalResponse)
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

                onComplete(
                    Result(
                        finalResponse = resolvedResponse,
                        speakOutput = toolCall == null && !shouldFallbackToToolResult && !exhaustedToolBudget,
                        transcript = transcript.toList()
                    )
                )
            }
        }

        scope.launch(inferenceDispatcher) {
            runInference(initialConversation, maxToolCalls)
        }
    }

    companion object {
        fun summarizeToolResultMessage(result: ToolResult): String {
            val cleaned = result.output.trim()
            val clipped = if (cleaned.length > 700) "${cleaned.take(700)}..." else cleaned
            return if (result.isError) {
                "Tool ${result.toolName} failed: ${if (clipped.isNotEmpty()) clipped else "no error details"}"
            } else {
                "Tool ${result.toolName} result:\n$clipped"
            }
        }

        fun summarizeDirectToolResultMessage(result: ToolResult): String {
            val cleaned = result.output.trim()
            val clipped = if (cleaned.length > 700) "${cleaned.take(700)}..." else cleaned
            return if (result.isError) {
                "Tool ${result.toolName} failed: ${if (clipped.isNotEmpty()) clipped else "no error details"}"
            } else {
                clipped.ifBlank { "Done." }
            }
        }

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
    }
}

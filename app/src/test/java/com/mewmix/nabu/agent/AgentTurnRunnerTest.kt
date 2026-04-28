package com.mewmix.nabu.agent

import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmImageInput
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.tools.ToolCall
import com.mewmix.nabu.tools.ToolResult
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class AgentTurnRunnerTest {

    @Test
    fun run_executesToolAndFeedsResultBackToModel() = runTest {
        val backend = FakeBackend(
            listOf(
                """<tool_call>{"name":"get_weather","arguments":{"location":"Seattle"}}</tool_call>""",
                "It is raining in Seattle."
            )
        )
        val toolCalls = mutableListOf<ToolCall>()
        var finalResult: AgentTurnRunner.Result? = null

        AgentTurnRunner(
            backend = backend,
            scope = this,
            toolExecutor = { call ->
                toolCalls += call
                ToolResult(call.toolName, "rain")
            },
            inferToolCallFromModelFailure = { _, _ -> null },
            recoveryConversationProvider = { emptyList() },
            inferenceDispatcher = StandardTestDispatcher(testScheduler)
        ).run(
            initialConversation = listOf(LlmMessage("user", "weather?")),
            latestUserMessage = "weather?",
            availableToolNames = setOf("get_weather"),
            maxToolCalls = 4,
            onPartialText = {},
            onSpeakablePartial = { _, _ -> },
            onSuppressSpeakablePartials = {},
            onToolStart = {},
            onComplete = { finalResult = it }
        )

        testScheduler.advanceUntilIdle()

        assertEquals(listOf("get_weather"), toolCalls.map { it.toolName })
        assertEquals("Seattle", toolCalls.first().arguments["location"])
        assertEquals(2, backend.conversations.size)
        assertEquals("It is raining in Seattle.", finalResult?.finalResponse)
        assertEquals(1, finalResult?.transcript?.size)
    }

    @Test
    fun run_fallsBackToToolResultWhenModelKeepsCallingToolAfterResult() = runTest {
        val backend = FakeBackend(
            listOf(
                """<tool_call>{"name":"retrieve_memory","arguments":{}}</tool_call>""",
                """<tool_call>{"name":"retrieve_memory","arguments":{}}</tool_call>"""
            )
        )
        var finalResult: AgentTurnRunner.Result? = null

        AgentTurnRunner(
            backend = backend,
            scope = this,
            toolExecutor = { call -> ToolResult(call.toolName, "saved fact") },
            inferToolCallFromModelFailure = { _, _ -> null },
            recoveryConversationProvider = { emptyList() },
            inferenceDispatcher = StandardTestDispatcher(testScheduler)
        ).run(
            initialConversation = listOf(LlmMessage("user", "what do you remember?")),
            latestUserMessage = "what do you remember?",
            availableToolNames = setOf("retrieve_memory"),
            maxToolCalls = 1,
            onPartialText = {},
            onSpeakablePartial = { _, _ -> },
            onSuppressSpeakablePartials = {},
            onToolStart = {},
            onComplete = { finalResult = it }
        )

        testScheduler.advanceUntilIdle()

        assertEquals("Tool retrieve_memory result:\nsaved fact", finalResult?.finalResponse)
        assertFalse(finalResult?.speakOutput ?: true)
    }

    @Test
    fun run_retriesBlankResponseWithRecoveryConversation() = runTest {
        val backend = FakeBackend(listOf("", "Recovered response."))
        var retried = false
        var finalResult: AgentTurnRunner.Result? = null

        AgentTurnRunner(
            backend = backend,
            scope = this,
            toolExecutor = { call -> ToolResult(call.toolName, "unused") },
            inferToolCallFromModelFailure = { _, _ -> null },
            recoveryConversationProvider = { listOf(LlmMessage("user", "compact")) },
            inferenceDispatcher = StandardTestDispatcher(testScheduler)
        ).run(
            initialConversation = listOf(LlmMessage("system", "full"), LlmMessage("user", "hello")),
            latestUserMessage = "hello",
            availableToolNames = emptySet(),
            maxToolCalls = 4,
            onPartialText = {},
            onSpeakablePartial = { _, _ -> },
            onSuppressSpeakablePartials = {},
            onToolStart = {},
            onRecoveryRetry = { retried = true },
            onComplete = { finalResult = it }
        )

        testScheduler.advanceUntilIdle()

        assertEquals(2, backend.conversations.size)
        assertEquals("Recovered response.", finalResult?.finalResponse)
        assertNotNull(finalResult)
        assertEquals(true, retried)
    }

    private class FakeBackend(
        responses: List<String>
    ) : LlmBackend {
        private val responseQueue = ArrayDeque(responses)
        val conversations = mutableListOf<List<LlmMessage>>()

        override fun initialize() = Unit

        override fun sendMessage(
            conversation: List<LlmMessage>,
            resultListener: (partialResult: String, done: Boolean) -> Unit
        ) {
            conversations += conversation
            resultListener(responseQueue.removeFirstOrNull().orEmpty(), true)
        }

        override fun sendMessage(
            prompt: String,
            resultListener: (partialResult: String, done: Boolean) -> Unit
        ) {
            resultListener(responseQueue.removeFirstOrNull().orEmpty(), true)
        }

        override fun sendMessage(
            conversation: List<LlmMessage>,
            image: LlmImageInput,
            resultListener: (partialResult: String, done: Boolean) -> Unit
        ) {
            sendMessage(conversation, resultListener)
        }

        override fun cancel() = Unit

        override fun close() = Unit
    }
}

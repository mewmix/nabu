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

    @Test
    fun run_infersToolWhenBackendReportsGenerationFailure() = runTest {
        val backend = FakeBackend(listOf("LiteRT-LM generation failed.", "Done."))
        val toolCalls = mutableListOf<ToolCall>()
        var finalResult: AgentTurnRunner.Result? = null

        AgentTurnRunner(
            backend = backend,
            scope = this,
            toolExecutor = { call ->
                toolCalls += call
                ToolResult(call.toolName, "Opened SMS composer.")
            },
            inferToolCallFromModelFailure = { userMessage, availableTools ->
                if ("send_sms" in availableTools && userMessage.contains("send sms")) {
                    ToolCall(
                        "send_sms",
                        mapOf("phone_number" to "19492358485", "message" to "nabu audit test")
                    )
                } else {
                    null
                }
            },
            recoveryConversationProvider = { emptyList() },
            inferenceDispatcher = StandardTestDispatcher(testScheduler)
        ).run(
            initialConversation = listOf(LlmMessage("user", "send sms to 19492358485 saying nabu audit test")),
            latestUserMessage = "send sms to 19492358485 saying nabu audit test",
            availableToolNames = setOf("send_sms"),
            maxToolCalls = 4,
            onPartialText = {},
            onSpeakablePartial = { _, _ -> },
            onSuppressSpeakablePartials = {},
            onToolStart = {},
            onComplete = { finalResult = it }
        )

        testScheduler.advanceUntilIdle()

        assertEquals(listOf("send_sms"), toolCalls.map { it.toolName })
        assertEquals("19492358485", toolCalls.first().arguments["phone_number"])
        assertEquals("nabu audit test", toolCalls.first().arguments["message"])
        assertEquals("Done.", finalResult?.finalResponse)
    }

    @Test
    fun run_infersToolWhenModelAnswersWithProseForParseableRequest() = runTest {
        val backend = FakeBackend(listOf("Sure, I can do that.", "Done."))
        val toolCalls = mutableListOf<ToolCall>()
        var finalResult: AgentTurnRunner.Result? = null

        AgentTurnRunner(
            backend = backend,
            scope = this,
            toolExecutor = { call ->
                toolCalls += call
                ToolResult(call.toolName, "Opened SMS composer.")
            },
            inferToolCallFromModelFailure = { _, availableTools ->
                if ("send_sms" in availableTools) {
                    ToolCall("send_sms", mapOf("phone_number" to "1234567890", "message" to "hello"))
                } else {
                    null
                }
            },
            recoveryConversationProvider = { emptyList() },
            inferenceDispatcher = StandardTestDispatcher(testScheduler)
        ).run(
            initialConversation = listOf(LlmMessage("user", "send sms to 1234567890 saying hello")),
            latestUserMessage = "send sms to 1234567890 saying hello",
            availableToolNames = setOf("send_sms"),
            maxToolCalls = 4,
            onPartialText = {},
            onSpeakablePartial = { _, _ -> },
            onSuppressSpeakablePartials = {},
            onToolStart = {},
            onComplete = { finalResult = it }
        )

        testScheduler.advanceUntilIdle()

        assertEquals(listOf("send_sms"), toolCalls.map { it.toolName })
        assertEquals("Done.", finalResult?.finalResponse)
    }

    @Test
    fun run_doesNotSuppressSpeechForNormalChatWhenToolsAreAvailable() = runTest {
        val backend = FakeBackend(listOf("Normal answer."))
        val speakablePartials = mutableListOf<String>()
        var finalResult: AgentTurnRunner.Result? = null

        AgentTurnRunner(
            backend = backend,
            scope = this,
            toolExecutor = { call -> ToolResult(call.toolName, "unused") },
            inferToolCallFromModelFailure = { _, _ -> null },
            recoveryConversationProvider = { emptyList() },
            inferenceDispatcher = StandardTestDispatcher(testScheduler)
        ).run(
            initialConversation = listOf(LlmMessage("user", "how are you?")),
            latestUserMessage = "how are you?",
            availableToolNames = setOf("send_sms", "place_call"),
            maxToolCalls = 4,
            onPartialText = {},
            onSpeakablePartial = { partial, _ -> speakablePartials += partial },
            onSuppressSpeakablePartials = {},
            onToolStart = {},
            onComplete = { finalResult = it }
        )

        testScheduler.advanceUntilIdle()

        assertEquals(listOf("Normal answer."), speakablePartials)
        assertEquals("Normal answer.", finalResult?.finalResponse)
    }

    @Test
    fun run_completesDirectResultToolWithoutModelFollowUp() = runTest {
        val backend = FakeBackend(listOf("""<tool_call>{"name":"send_sms","arguments":{"phone_number":"1234567890","message":"hello"}}</tool_call>"""))
        val toolCalls = mutableListOf<ToolCall>()
        var finalResult: AgentTurnRunner.Result? = null

        AgentTurnRunner(
            backend = backend,
            scope = this,
            toolExecutor = { call ->
                toolCalls += call
                ToolResult(call.toolName, "Opened SMS composer for 1234567890 with draft message.")
            },
            inferToolCallFromModelFailure = { _, _ -> null },
            recoveryConversationProvider = { emptyList() },
            shouldCompleteAfterToolResult = { call, _ -> call.toolName == "send_sms" },
            inferenceDispatcher = StandardTestDispatcher(testScheduler)
        ).run(
            initialConversation = listOf(LlmMessage("user", "send sms to 1234567890 saying hello")),
            latestUserMessage = "send sms to 1234567890 saying hello",
            availableToolNames = setOf("send_sms"),
            maxToolCalls = 4,
            onPartialText = {},
            onSpeakablePartial = { _, _ -> },
            onSuppressSpeakablePartials = {},
            onToolStart = {},
            onComplete = { finalResult = it }
        )

        testScheduler.advanceUntilIdle()

        assertEquals(listOf("send_sms"), toolCalls.map { it.toolName })
        assertEquals(1, backend.conversations.size)
        assertEquals("Opened SMS composer for 1234567890 with draft message.", finalResult?.finalResponse)
    }

    @Test
    fun run_executesGemmaColonWrappedLooseToolCall() = runTest {
        val backend = FakeBackend(listOf("""<tool_call:toggle_flashlight(enabled: true) </tool_call>"""))
        val toolCalls = mutableListOf<ToolCall>()
        var finalResult: AgentTurnRunner.Result? = null

        AgentTurnRunner(
            backend = backend,
            scope = this,
            toolExecutor = { call ->
                toolCalls += call
                ToolResult(call.toolName, "Flashlight turned on.")
            },
            inferToolCallFromModelFailure = { _, _ -> null },
            recoveryConversationProvider = { emptyList() },
            shouldCompleteAfterToolResult = { call, _ -> call.toolName == "toggle_flashlight" },
            inferenceDispatcher = StandardTestDispatcher(testScheduler)
        ).run(
            initialConversation = listOf(LlmMessage("user", "turn flashlight on")),
            latestUserMessage = "turn flashlight on",
            availableToolNames = setOf("toggle_flashlight"),
            maxToolCalls = 4,
            onPartialText = {},
            onSpeakablePartial = { _, _ -> },
            onSuppressSpeakablePartials = {},
            onToolStart = {},
            onComplete = { finalResult = it }
        )

        testScheduler.advanceUntilIdle()

        assertEquals(listOf("toggle_flashlight"), toolCalls.map { it.toolName })
        assertEquals(true, toolCalls.first().arguments["enabled"])
        assertEquals("Flashlight turned on.", finalResult?.finalResponse)
    }

    @Test
    fun run_quarantinesUnparseableToolControlText() = runTest {
        val backend = FakeBackend(listOf("""<tool_call:toggle_flashlight enabled true</tool_call>"""))
        val toolCalls = mutableListOf<ToolCall>()
        var finalResult: AgentTurnRunner.Result? = null

        AgentTurnRunner(
            backend = backend,
            scope = this,
            toolExecutor = { call ->
                toolCalls += call
                ToolResult(call.toolName, "unused")
            },
            inferToolCallFromModelFailure = { _, _ -> null },
            recoveryConversationProvider = { emptyList() },
            inferenceDispatcher = StandardTestDispatcher(testScheduler)
        ).run(
            initialConversation = listOf(LlmMessage("user", "turn flashlight on")),
            latestUserMessage = "turn flashlight on",
            availableToolNames = setOf("toggle_flashlight"),
            maxToolCalls = 4,
            onPartialText = {},
            onSpeakablePartial = { _, _ -> },
            onSuppressSpeakablePartials = {},
            onToolStart = {},
            onComplete = { finalResult = it }
        )

        testScheduler.advanceUntilIdle()

        assertEquals(emptyList<ToolCall>(), toolCalls)
        assertEquals("I tried to call a tool, but could not parse the tool request.", finalResult?.finalResponse)
    }

    @Test
    fun run_rejectsToolThatWasNotAdvertisedForTurn() = runTest {
        val backend = FakeBackend(
            listOf("""<tool_call>{"name":"send_sms","arguments":{"message":"hello"}}</tool_call>""")
        )
        val toolCalls = mutableListOf<ToolCall>()
        var finalResult: AgentTurnRunner.Result? = null

        AgentTurnRunner(
            backend = backend,
            scope = this,
            toolExecutor = { call ->
                toolCalls += call
                ToolResult(call.toolName, "unused")
            },
            inferToolCallFromModelFailure = { _, _ -> null },
            recoveryConversationProvider = { emptyList() },
            inferenceDispatcher = StandardTestDispatcher(testScheduler)
        ).run(
            initialConversation = listOf(LlmMessage("user", "tap the visible send button")),
            latestUserMessage = "tap the visible send button",
            availableToolNames = setOf("control_ui"),
            maxToolCalls = 4,
            onPartialText = {},
            onSpeakablePartial = { _, _ -> },
            onSuppressSpeakablePartials = {},
            onToolStart = {},
            onComplete = { finalResult = it }
        )

        testScheduler.advanceUntilIdle()

        assertEquals(emptyList<ToolCall>(), toolCalls)
        assertEquals("I tried to call a tool, but could not parse the tool request.", finalResult?.finalResponse)
    }

    @Test
    fun run_suppressesAllStreamedChunksAfterToolControlStarts() = runTest {
        val backend = ChunkedBackend(
            listOf(
                listOf(
                    "<tool_call:list_tools{}",
                    "</tool_call>\nI have listed the available tools for you."
                ),
                listOf("Done.")
            )
        )
        val speakablePartials = mutableListOf<String>()
        val toolCalls = mutableListOf<ToolCall>()
        var finalResult: AgentTurnRunner.Result? = null

        AgentTurnRunner(
            backend = backend,
            scope = this,
            toolExecutor = { call ->
                toolCalls += call
                ToolResult(call.toolName, "tools")
            },
            inferToolCallFromModelFailure = { _, _ -> null },
            recoveryConversationProvider = { emptyList() },
            inferenceDispatcher = StandardTestDispatcher(testScheduler)
        ).run(
            initialConversation = listOf(LlmMessage("user", "list tools")),
            latestUserMessage = "list tools",
            availableToolNames = setOf("list_tools"),
            maxToolCalls = 4,
            onPartialText = {},
            onSpeakablePartial = { partial, _ -> speakablePartials += partial },
            onSuppressSpeakablePartials = {},
            onToolStart = {},
            onComplete = { finalResult = it }
        )

        testScheduler.advanceUntilIdle()

        assertEquals(listOf("list_tools"), toolCalls.map { it.toolName })
        assertEquals(listOf("Done."), speakablePartials)
        assertEquals("Done.", finalResult?.finalResponse)
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

    private class ChunkedBackend(
        responses: List<List<String>>
    ) : LlmBackend {
        private val responseQueue = ArrayDeque(responses)
        val conversations = mutableListOf<List<LlmMessage>>()

        override fun initialize() = Unit

        override fun sendMessage(
            conversation: List<LlmMessage>,
            resultListener: (partialResult: String, done: Boolean) -> Unit
        ) {
            conversations += conversation
            val chunks = responseQueue.removeFirstOrNull().orEmpty()
            chunks.dropLast(1).forEach { resultListener(it, false) }
            resultListener(chunks.lastOrNull().orEmpty(), true)
        }

        override fun sendMessage(
            prompt: String,
            resultListener: (partialResult: String, done: Boolean) -> Unit
        ) {
            val chunks = responseQueue.removeFirstOrNull().orEmpty()
            chunks.dropLast(1).forEach { resultListener(it, false) }
            resultListener(chunks.lastOrNull().orEmpty(), true)
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

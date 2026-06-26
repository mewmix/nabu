package com.mewmix.nabu.agent

import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmImageInput
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.tools.Tool
import com.mewmix.nabu.tools.ToolCall
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ActionPlannerTest {
    private val tools = listOf(
        Tool("schedule_action", "Schedule a background-safe action."),
        Tool("toggle_flashlight", "Turn the flashlight on or off.", mapOf("enabled" to "true or false")),
        Tool("send_sms", "Open an SMS composer.", mapOf("phone_number" to "recipient", "message" to "body")),
        Tool("get_current_time", "Get local time.")
    )

    @Test
    fun parsePlan_buildsImmediateAndScheduledToolCalls() {
        val plan = ActionPlanner.parsePlan(
            rawResponse = """
                {
                  "type":"action_plan",
                  "requires_confirmation":false,
                  "response":"Flashlight on, text scheduled, then I will check time.",
                  "steps":[
                    {"tool_name":"toggle_flashlight","tool_arguments":{"enabled":true}},
                    {"delay_seconds":10,"tool_name":"toggle_flashlight","tool_arguments":{"enabled":false},"title":"Flashlight off"},
                    {"delay_seconds":300,"tool_name":"send_sms","tool_arguments":{"phone_number":"949 771 4923","message":"five minutes is up"}},
                    {"tool_name":"get_current_time","tool_arguments":{}}
                  ]
                }
            """.trimIndent(),
            availableToolNames = tools.map { it.name }.toSet()
        )

        assertNotNull(plan)
        val parsed = requireNotNull(plan)
        assertEquals("model_action_plan", parsed.source)
        assertEquals(
            listOf(
                ToolCall("toggle_flashlight", mapOf("enabled" to true)),
                ToolCall(
                    "schedule_action",
                    mapOf(
                        "title" to "Flashlight off",
                        "instruction" to "Run toggle_flashlight after 10 seconds.",
                        "tool_name" to "toggle_flashlight",
                        "tool_arguments" to mapOf("enabled" to false),
                        "recurrence" to "none",
                        "delay_seconds" to 10L
                    )
                ),
                ToolCall(
                    "schedule_action",
                    mapOf(
                        "title" to "Run send_sms",
                        "instruction" to "Run send_sms after 300 seconds.",
                        "tool_name" to "send_sms",
                        "tool_arguments" to mapOf(
                            "phone_number" to "949 771 4923",
                            "message" to "five minutes is up"
                        ),
                        "recurrence" to "none",
                        "delay_seconds" to 300L
                    )
                ),
                ToolCall("get_current_time", emptyMap())
            ),
            parsed.toolCalls
        )
    }

    @Test
    fun parsePlan_rejectsUnknownTool() {
        val plan = ActionPlanner.parsePlan(
            rawResponse = """{"type":"action_plan","steps":[{"tool_name":"unknown_tool","tool_arguments":{}}]}""",
            availableToolNames = tools.map { it.name }.toSet()
        )

        assertNull(plan)
    }

    @Test
    fun parsePlan_returnsConfirmationMessageWithoutTools() {
        val plan = ActionPlanner.parsePlan(
            rawResponse = """
                {"type":"action_plan","requires_confirmation":true,"response":"Confirm before sending this text.","steps":[]}
            """.trimIndent(),
            availableToolNames = tools.map { it.name }.toSet()
        )

        assertNotNull(plan)
        assertEquals(emptyList<ToolCall>(), plan?.toolCalls)
        assertEquals("model_confirmation_required", plan?.source)
    }

    @Test
    fun planWithModel_usesIsolatedPlannerConversation() = runTest {
        val backend = FakeBackend(
            """
                {"type":"action_plan","response":"Done.","steps":[{"tool_name":"get_current_time","tool_arguments":{}}]}
            """.trimIndent()
        )

        val plan = ActionPlanner.planWithModel(
            backend = backend,
            userMessage = "get the time then turn flashlight off",
            selectedTools = tools,
            timeoutMs = 5_000L
        )

        assertNotNull(plan)
        assertEquals(listOf(ToolCall("get_current_time", emptyMap())), plan?.toolCalls)
        assertEquals(1, backend.conversations.size)
        assertEquals("system", backend.conversations.single().first().role)
        assertEquals("user", backend.conversations.single().last().role)
    }

    private class FakeBackend(private val response: String) : LlmBackend {
        val conversations = mutableListOf<List<LlmMessage>>()

        override fun initialize() = Unit

        override fun sendMessage(
            conversation: List<LlmMessage>,
            resultListener: (partialResult: String, done: Boolean) -> Unit
        ) {
            conversations += conversation
            resultListener(response, true)
        }

        override fun sendMessage(prompt: String, resultListener: (partialResult: String, done: Boolean) -> Unit) {
            resultListener(response, true)
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

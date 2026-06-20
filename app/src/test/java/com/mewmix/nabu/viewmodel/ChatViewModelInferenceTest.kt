package com.mewmix.nabu.viewmodel

import com.mewmix.nabu.tools.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChatViewModelInferenceTest {

    @Test
    fun inferToolCallFromModelFailure_parsesAlarmWithSpaceSeparatedTime() {
        val toolCall = ChatViewModel.inferToolCallFromModelFailure(
            userMessage = "set an alarm for 7 30 called wake up",
            availableToolNames = setOf("set_alarm")
        )

        assertNotNull(toolCall)
        assertEquals(
            ToolCall(
                "set_alarm",
                mapOf("hour" to 7, "minute" to 30, "message" to "wake up")
            ),
            toolCall
        )
    }

    @Test
    fun inferToolCallFromModelFailure_parsesTimerDuration() {
        val toolCall = ChatViewModel.inferToolCallFromModelFailure(
            userMessage = "set a timer for 13 seconds called tea",
            availableToolNames = setOf("set_timer")
        )

        assertNotNull(toolCall)
        assertEquals(
            ToolCall(
                "set_timer",
                mapOf("seconds" to 13, "message" to "tea")
            ),
            toolCall
        )
    }

    @Test
    fun inferToolCallFromModelFailure_parsesScheduledFlashlightDelay() {
        val toolCall = ChatViewModel.inferToolCallFromModelFailure(
            userMessage = "turn off my flashlight after ten seconds",
            availableToolNames = setOf("schedule_action", "toggle_flashlight")
        )

        assertEquals(
            ToolCall(
                "schedule_action",
                mapOf(
                    "title" to "Turn flashlight off",
                    "instruction" to "Turn flashlight off after 10 seconds.",
                    "delay_seconds" to 10,
                    "tool_name" to "toggle_flashlight",
                    "tool_arguments" to mapOf("enabled" to false)
                )
            ),
            toolCall
        )
    }

    @Test
    fun parseChainedFlashlightJokeSchedule_handlesSmokeCommand() {
        val command = ChatViewModel.parseChainedFlashlightJokeSchedule(
            userMessage = "turn on my flashlight Tell me a joke then after 10 seconds turn off the flashlight",
            availableToolNames = setOf("schedule_action", "toggle_flashlight")
        )

        assertNotNull(command)
        val parsed = requireNotNull(command)
        assertEquals(
            listOf(
                ToolCall("toggle_flashlight", mapOf("enabled" to true)),
                ToolCall(
                    "schedule_action",
                    mapOf(
                        "title" to "Turn flashlight off",
                        "instruction" to "Turn flashlight off after 10 seconds.",
                        "delay_seconds" to 10,
                        "tool_name" to "toggle_flashlight",
                        "tool_arguments" to mapOf("enabled" to false)
                    )
                )
            ),
            parsed.toolCalls
        )
    }

    @Test
    fun inferToolCallFromModelFailure_parsesOpenUrl() {
        val toolCall = ChatViewModel.inferToolCallFromModelFailure(
            userMessage = "open url https://example.com",
            availableToolNames = setOf("open_url")
        )

        assertEquals(
            ToolCall("open_url", mapOf("url" to "https://example.com")),
            toolCall
        )
    }

    @Test
    fun inferToolCallFromModelFailure_parsesSendSms() {
        val toolCall = ChatViewModel.inferToolCallFromModelFailure(
            userMessage = "send sms to 1234567890 saying hello from nabu",
            availableToolNames = setOf("send_sms")
        )

        assertEquals(
            ToolCall(
                "send_sms",
                mapOf("phone_number" to "1234567890", "message" to "hello from nabu")
            ),
            toolCall
        )
    }

    @Test
    fun inferToolCallFromModelFailure_parsesDeviceActionFallbacks() {
        assertEquals(
            ToolCall("navigate_to", mapOf("destination" to "1600 Amphitheatre Parkway")),
            ChatViewModel.inferToolCallFromModelFailure(
                userMessage = "navigate to 1600 Amphitheatre Parkway",
                availableToolNames = setOf("navigate_to")
            )
        )
        assertEquals(
            ToolCall("toggle_bluetooth", mapOf("enabled" to false)),
            ChatViewModel.inferToolCallFromModelFailure(
                userMessage = "turn bluetooth off",
                availableToolNames = setOf("toggle_bluetooth")
            )
        )
        assertEquals(
            ToolCall("share_text", mapOf("text" to "hello from nabu")),
            ChatViewModel.inferToolCallFromModelFailure(
                userMessage = "share text hello from nabu",
                availableToolNames = setOf("share_text")
            )
        )
        assertEquals(
            ToolCall("set_volume", mapOf("level" to 35, "stream" to "media")),
            ChatViewModel.inferToolCallFromModelFailure(
                userMessage = "set volume to 35 media",
                availableToolNames = setOf("set_volume")
            )
        )
        assertEquals(
            ToolCall("take_photo", emptyMap()),
            ChatViewModel.inferToolCallFromModelFailure(
                userMessage = "take a photo",
                availableToolNames = setOf("take_photo")
            )
        )
    }

    @Test
    fun inferToolCallFromModelFailure_rejectsUnmatchedAlarmText() {
        val toolCall = ChatViewModel.inferToolCallFromModelFailure(
            userMessage = "set an alarm sometime tomorrow morning",
            availableToolNames = setOf("set_alarm")
        )

        assertNull(toolCall)
    }
}

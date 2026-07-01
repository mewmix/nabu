package com.mewmix.nabu.viewmodel

import com.mewmix.nabu.tools.ToolCall
import com.mewmix.nabu.uiagent.UiAutomationOrchestrator
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
    fun planDirectActionChain_handlesSmokeCommand() {
        val plan = ChatViewModel.planDirectActionChain(
            userMessage = "turn on my flashlight Tell me a joke then after 10 seconds turn off the flashlight",
            availableToolNames = setOf("schedule_action", "toggle_flashlight")
        )

        assertNotNull(plan)
        val parsed = requireNotNull(plan)
        assertEquals(
            listOf(
                ToolCall("toggle_flashlight", mapOf("enabled" to true)),
                ToolCall(
                    "schedule_action",
                    mapOf(
                        "title" to "Turn flashlight off",
                        "instruction" to "Run toggle_flashlight after 10 seconds.",
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
    fun planDirectActionChain_opensAppBeforeVisibleUiGoal() {
        val plan = ChatViewModel.planDirectActionChain(
            userMessage = "open settings and turn on dark mode",
            availableToolNames = setOf("open_app", UiAutomationOrchestrator.CONTROL_UI_TOOL)
        )

        assertEquals(
            listOf("open_app", UiAutomationOrchestrator.CONTROL_UI_TOOL),
            plan?.toolCalls?.map { it.toolName }
        )
        assertEquals("settings", plan?.toolCalls?.first()?.arguments?.get("app_name"))
    }

    @Test
    fun explicitControlUiRequestBypassesMultiActionChain() {
        val request = "use control UI, the goal is to send a chat message within Nabu"

        assertEquals(
            null,
            ChatViewModel.planDirectActionChain(
                userMessage = request,
                availableToolNames = setOf("send_sms", "open_app", UiAutomationOrchestrator.CONTROL_UI_TOOL)
            )
        )
        assertEquals(
            ToolCall(
                UiAutomationOrchestrator.CONTROL_UI_TOOL,
                mapOf("goal" to "send a chat message within Nabu")
            ),
            ChatViewModel.inferToolCallFromModelFailure(
                userMessage = request,
                availableToolNames = setOf("send_sms", "open_app", UiAutomationOrchestrator.CONTROL_UI_TOOL)
            )
        )
    }

    @Test
    fun planDirectActionChain_schedulesOtherSupportedActions() {
        val plan = ChatViewModel.planDirectActionChain(
            userMessage = "pause media then in 15 seconds play media",
            availableToolNames = setOf("schedule_action", "pause_media", "play_media")
        )

        assertNotNull(plan)
        val parsed = requireNotNull(plan)
        assertEquals(
            listOf(
                ToolCall("pause_media", emptyMap()),
                ToolCall(
                    "schedule_action",
                    mapOf(
                        "title" to "Run play_media",
                        "instruction" to "Run play_media after 15 seconds.",
                        "delay_seconds" to 15,
                        "tool_name" to "play_media",
                        "tool_arguments" to emptyMap<String, Any>()
                    )
                )
            ),
            parsed.toolCalls
        )
    }

    @Test
    fun planDirectActionChain_handlesTimedFlashlightHold() {
        val plan = ChatViewModel.planDirectActionChain(
            userMessage = "turn on my flashlight for 60 seconds, then turn it off again.",
            availableToolNames = setOf("schedule_action", "toggle_flashlight")
        )

        assertNotNull(plan)
        val parsed = requireNotNull(plan)
        assertEquals(
            listOf(
                ToolCall("toggle_flashlight", mapOf("enabled" to true)),
                ToolCall(
                    "schedule_action",
                    mapOf(
                        "title" to "Turn flashlight off",
                        "instruction" to "Run toggle_flashlight after 60 seconds.",
                        "delay_seconds" to 60,
                        "tool_name" to "toggle_flashlight",
                        "tool_arguments" to mapOf("enabled" to false)
                    )
                )
            ),
            parsed.toolCalls
        )
    }

    @Test
    fun planDirectActionChain_handlesImmediateNowThenDelayedPronoun() {
        val plan = ChatViewModel.planDirectActionChain(
            userMessage = "turn on flashlight now, then in 10 minutes turn it off",
            availableToolNames = setOf("schedule_action", "toggle_flashlight")
        )

        assertNotNull(plan)
        val parsed = requireNotNull(plan)
        assertEquals(
            listOf(
                ToolCall("toggle_flashlight", mapOf("enabled" to true)),
                ToolCall(
                    "schedule_action",
                    mapOf(
                        "title" to "Turn flashlight off",
                        "instruction" to "Run toggle_flashlight after 600 seconds.",
                        "delay_seconds" to 600,
                        "tool_name" to "toggle_flashlight",
                        "tool_arguments" to mapOf("enabled" to false)
                    )
                )
            ),
            parsed.toolCalls
        )
    }

    @Test
    fun inferToolCallFromModelFailure_schedulesComposedText() {
        val toolCall = ChatViewModel.inferToolCallFromModelFailure(
            userMessage = "compose a text in 5 minutes saying five minutes is up to 949 771 4923",
            availableToolNames = setOf("schedule_action", "send_sms")
        )

        assertEquals(
            ToolCall(
                "schedule_action",
                mapOf(
                    "title" to "Run send_sms",
                    "instruction" to "Run send_sms after 300 seconds.",
                    "delay_seconds" to 300,
                    "tool_name" to "send_sms",
                    "tool_arguments" to mapOf(
                        "phone_number" to "949 771 4923",
                        "message" to "five minutes is up"
                    )
                )
            ),
            toolCall
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
    fun inferToolCallFromModelFailure_parsesNaturalAppLaunch() {
        assertEquals(
            ToolCall("open_app", mapOf("app_name" to "YouTube")),
            ChatViewModel.inferToolCallFromModelFailure(
                userMessage = "open YouTube",
                availableToolNames = setOf("open_app")
            )
        )
        assertEquals(
            ToolCall("launch_package", mapOf("app_name" to "YouTube Studio")),
            ChatViewModel.inferToolCallFromModelFailure(
                userMessage = "open application YouTube Studio",
                availableToolNames = setOf("launch_package")
            )
        )
    }

    @Test
    fun inferToolCallFromModelFailure_routesVisibleUiGoalToPlanner() {
        assertEquals(
            ToolCall(
                UiAutomationOrchestrator.CONTROL_UI_TOOL,
                mapOf("goal" to "turn on dark mode")
            ),
            ChatViewModel.inferToolCallFromModelFailure(
                userMessage = "turn on dark mode",
                availableToolNames = setOf(UiAutomationOrchestrator.CONTROL_UI_TOOL)
            )
        )
    }

    @Test
    fun inferToolCallFromModelFailure_readsCurrentScreenWithoutUiMutation() {
        assertEquals(
            ToolCall("read_screen", emptyMap()),
            ChatViewModel.inferToolCallFromModelFailure(
                userMessage = "Read this Nabu screen and tell me whether Model Settings is expanded.",
                availableToolNames = setOf("read_screen", UiAutomationOrchestrator.CONTROL_UI_TOOL)
            )
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

package com.mewmix.nabu.tools

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallProtocolTest {

    @Test
    fun extractToolCall_parsesTaggedJson() {
        val text = """
            <tool_call>{"name":"list_files","arguments":{"path":"/tmp","limit":5}}</tool_call>
        """.trimIndent()

        val toolCall = ToolCallProtocol.extractToolCall(text)

        assertNotNull(toolCall)
        assertEquals("list_files", toolCall?.toolName)
        assertEquals("/tmp", toolCall?.arguments?.get("path"))
        assertEquals(5, (toolCall?.arguments?.get("limit") as Number).toInt())
    }

    @Test
    fun extractToolCall_parsesBareJsonWithoutWrapper() {
        val text = "{" +
            "\"tool\":\"read_file\"," +
            "\"arguments\":{\"path\":\"/etc/hosts\"}" +
            "}"

        val toolCall = ToolCallProtocol.extractToolCall(text)

        assertNotNull(toolCall)
        assertEquals("read_file", toolCall?.toolName)
        assertEquals("/etc/hosts", toolCall?.arguments?.get("path"))
    }

    @Test
    fun extractToolCall_parsesMalformedFencedJsonFromLocalModel() {
        val text = """
            ```json
            {name":"list_files",arguments={"path": "/sdcard/Download"}}
            ```
        """.trimIndent()

        val toolCall = ToolCallProtocol.extractToolCall(text)

        assertNotNull(toolCall)
        assertEquals("list_files", toolCall?.toolName)
        assertEquals("/sdcard/Download", toolCall?.arguments?.get("path"))
    }

    @Test
    fun extractToolCall_parsesTaggedFunctionStyleCallFromLocalModel() {
        val toolCall = ToolCallProtocol.extractToolCall(
            """<tool_call>toggle_flashlight({"enabled": true})</tool_call>
            The flashlight is now on."""
        )

        assertNotNull(toolCall)
        assertEquals("toggle_flashlight", toolCall?.toolName)
        assertEquals(true, toolCall?.arguments?.get("enabled"))
    }

    @Test
    fun extractToolCall_parsesColonWrappedEmptyCallFromGemma() {
        val toolCall = ToolCallProtocol.extractToolCall("<tool_call:list_tools{}</tool_call>")

        assertNotNull(toolCall)
        assertEquals("list_tools", toolCall?.toolName)
        assertEquals(emptyMap<String, Any>(), toolCall?.arguments)
    }

    @Test
    fun extractToolCall_parsesColonWrappedLooseFunctionArgsFromGemma() {
        val toolCall = ToolCallProtocol.extractToolCall("<tool_call:toggle_flashlight(enabled: true) </tool_call>")

        assertNotNull(toolCall)
        assertEquals("toggle_flashlight", toolCall?.toolName)
        assertEquals(true, toolCall?.arguments?.get("enabled"))
    }

    @Test
    fun extractToolCall_parsesMalformedColonCommaArgumentsFromGemma() {
        val toolCall = ToolCallProtocol.extractToolCall("<tool_call:list_tools,arguments:{}<tool_call|>")

        assertNotNull(toolCall)
        assertEquals("list_tools", toolCall?.toolName)
        assertEquals(emptyMap<String, Any>(), toolCall?.arguments)
    }

    @Test
    fun extractToolCall_returnsNullForNonToolText() {
        val toolCall = ToolCallProtocol.extractToolCall("Normal assistant response with no tool call")
        assertNull(toolCall)
    }

    @Test
    fun extractToolCall_parsesCommandStyleListFiles() {
        val toolCall = ToolCallProtocol.extractToolCall(
            "list_files /sdcard/Download\n-- This query results in an empty list."
        )

        assertNotNull(toolCall)
        assertEquals("list_files", toolCall?.toolName)
        assertEquals("/sdcard/Download", toolCall?.arguments?.get("path"))
    }

    @Test
    fun extractToolCall_parsesMalformedAlarmCommandStyleFromLocalModel() {
        val toolCall = ToolCallProtocol.extractToolCall(
            "set alarm hour=7 minute=30 message=Wake up"
        )

        assertNotNull(toolCall)
        assertEquals("set_alarm", toolCall?.toolName)
        assertEquals(7, (toolCall?.arguments?.get("hour") as Number).toInt())
        assertEquals(30, (toolCall?.arguments?.get("minute") as Number).toInt())
        assertEquals("Wake up", toolCall?.arguments?.get("message"))
    }

    @Test
    fun formatToolResultForModel_returnsMachineReadablePayload() {
        val message = ToolCallProtocol.formatToolResultForModel(
            ToolResult(toolName = "list_files", output = "[\"a.txt\"]", isError = false)
        )

        assertTrue(message.startsWith("TOOL_RESULT "))
        val payload = JsonParser.parseString(message.removePrefix("TOOL_RESULT ")).asJsonObject
        assertEquals("list_files", payload.get("tool").asString)
        assertFalse(payload.get("is_error").asBoolean)
        assertEquals("[\"a.txt\"]", payload.get("output").asString)
    }

    @Test
    fun buildSystemPrompt_includesToolMetadata() {
        val prompt = ToolCallProtocol.buildSystemPrompt(
            basePrompt = "You are helpful",
            tools = listOf(
                Tool(
                    name = "read_file",
                    description = "Read file contents",
                    parameters = mapOf("path" to "Absolute path"),
                    isAvailable = true
                ),
                Tool(
                    name = "set_timer",
                    description = "Set a timer",
                    parameters = mapOf("seconds" to "Timer duration", "message" to "Timer label"),
                    isAvailable = true
                )
            )
        )

        assertTrue(prompt.contains("You are helpful"))
        assertTrue(prompt.contains("<tool_call>"))
        assertTrue(prompt.contains("read_file"))
        assertTrue(prompt.contains("The name must exactly match one of these tools"))
        assertTrue(prompt.contains("TOOL_RESULT"))
        assertTrue(prompt.contains("absolute Android paths"))
        assertTrue(prompt.contains("Android device through Glaive"))
        assertTrue(prompt.contains("not this Mac or repo"))
        assertTrue(prompt.contains("Filesystem path structure"))
        assertTrue(prompt.contains("root_path"))
        assertTrue(prompt.contains("/sdcard/Download"))
        assertTrue(prompt.contains("set_timer"))
        assertTrue(prompt.contains(""""name":"set_timer""""))
    }

    @Test
    fun buildSystemPrompt_omitsFilesystemPathStructureWhenNoFileToolsSelected() {
        val prompt = ToolCallProtocol.buildSystemPrompt(
            basePrompt = "You are helpful",
            tools = listOf(
                Tool(
                    name = "set_timer",
                    description = "Set a timer",
                    parameters = mapOf("seconds" to "Timer duration", "message" to "Timer label"),
                    isAvailable = true
                )
            )
        )

        assertFalse(prompt.contains("Filesystem path structure"))
    }
}

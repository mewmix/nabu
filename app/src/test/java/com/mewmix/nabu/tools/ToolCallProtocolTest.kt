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
    fun extractToolCall_ignoresBareJsonWithoutWrapper() {
        val text = "{" +
            "\"tool\":\"read_file\"," +
            "\"arguments\":{\"path\":\"/etc/hosts\"}" +
            "}"

        val toolCall = ToolCallProtocol.extractToolCall(text)

        assertNull(toolCall)
    }

    @Test
    fun extractToolCall_returnsNullForNonToolText() {
        val toolCall = ToolCallProtocol.extractToolCall("Normal assistant response with no tool call")
        assertNull(toolCall)
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
                )
            )
        )

        assertTrue(prompt.contains("You are helpful"))
        assertTrue(prompt.contains("<tool_call>"))
        assertTrue(prompt.contains("read_file"))
        assertTrue(prompt.contains("TOOL_RESULT"))
    }
}

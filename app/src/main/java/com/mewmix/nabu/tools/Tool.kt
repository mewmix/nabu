package com.mewmix.nabu.tools

/**
 * Represents a tool that the AI can call.
 */
data class Tool(
    val name: String,
    val description: String,
    val parameters: Map<String, String> = emptyMap(),
    val isAvailable: Boolean = true
)

/**
 * Represents a request from the AI to execute a tool.
 */
data class ToolCall(
    val toolName: String,
    val arguments: Map<String, Any>
)

/**
 * Represents the result of a tool execution.
 */
data class ToolResult(
    val toolName: String,
    val output: String,
    val isError: Boolean = false
)

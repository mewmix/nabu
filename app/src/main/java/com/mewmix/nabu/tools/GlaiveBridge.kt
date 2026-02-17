package com.mewmix.nabu.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.mewmix.nabu.utils.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object GlaiveBridge {
    private const val GLAIVE_PACKAGE = "com.mewmix.glaive"
    private const val ACTION_EXECUTE_TOOL = "com.mewmix.glaive.ACTION_EXECUTE_TOOL"
    private const val EXTRA_TOOL_NAME = "tool_name"
    private const val EXTRA_TOOL_ARGS = "tool_args"

    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(GLAIVE_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    suspend fun executeTool(context: Context, call: ToolCall): ToolResult = withContext(Dispatchers.IO) {
        if (!isInstalled(context)) {
            return@withContext ToolResult(call.toolName, "Glaive app is not installed.", true)
        }

        try {
            // Note: This is a simplified "fire and forget" or "broadcast" approach for now.
            // A real implementation would likely use `startActivityForResult` or a bound service
            // to get the return value. Since `startActivityForResult` requires an Activity context,
            // we will simulate the "request" part here.

            // For investigation purposes, we log the intent we WOULD send.
            DebugLogger.log("GlaiveBridge: Preparing to call ${call.toolName} with ${call.arguments}")

            val intent = Intent(ACTION_EXECUTE_TOOL).apply {
                `package` = GLAIVE_PACKAGE
                putExtra(EXTRA_TOOL_NAME, call.toolName)
                putExtra(EXTRA_TOOL_ARGS, JSONObject(call.arguments).toString())
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }

            // In a real scenario, we might use sendBroadcast or similar.
            // context.sendBroadcast(intent)

            // Since we can't easily get a result back without an Activity in this utility,
            // we return a placeholder result.
            ToolResult(call.toolName, "Tool execution request sent to Glaive (mock response).")
        } catch (e: Exception) {
            ToolResult(call.toolName, "Error executing tool: ${e.message}", true)
        }
    }

    // Stub to register default file manager tools if Glaive is detected
    fun registerDefaultTools() {
        ToolRegistry.register(
            Tool(
                name = "list_files",
                description = "List files in a directory.",
                parameters = mapOf("path" to "The directory path to list.")
            )
        )
        ToolRegistry.register(
            Tool(
                name = "read_file",
                description = "Read the content of a text file.",
                parameters = mapOf("path" to "The file path to read.")
            )
        )
        ToolRegistry.register(
            Tool(
                name = "write_file",
                description = "Write content to a file.",
                parameters = mapOf("path" to "The file path.", "content" to "The text content.")
            )
        )
    }
}

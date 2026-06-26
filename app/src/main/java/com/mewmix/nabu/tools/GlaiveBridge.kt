package com.mewmix.nabu.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.mewmix.nabu.utils.DebugLogger
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.net.Uri
import android.database.Cursor
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

object GlaiveBridge {
    private const val GLAIVE_PACKAGE = "com.mewmix.glaive"
    private const val GLAIVE_ACCESS_PERMISSION = "com.mewmix.glaive.permission.ACCESS_TOOLS"
    private const val ACTION_EXECUTE_TOOL = "com.mewmix.glaive.ACTION_EXECUTE_TOOL"
    private const val EXTRA_TOOL_NAME = "TOOL_NAME"
    private const val EXTRA_TOOL_PARAMS = "TOOL_PARAMS"
    private const val EXTRA_TOOL_RESULT = "TOOL_RESULT"
    private const val EXTRA_TOOL_ERROR = "TOOL_ERROR"

    private const val EXTRA_INTERNAL_TOOL_NAME = "internal_tool_name"
    private const val EXTRA_INTERNAL_TOOL_PARAMS = "internal_tool_params"
    private const val EXTRA_INTERNAL_RESULT_RECEIVER = "internal_result_receiver"
    private const val EXTRA_INTERNAL_RESULT_OUTPUT = "internal_result_output"
    private const val EXTRA_INTERNAL_RESULT_ERROR = "internal_result_error"

    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(GLAIVE_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    suspend fun executeTool(context: Context, call: ToolCall): ToolResult = suspendCancellableCoroutine { continuation ->
        if (!isInstalled(context)) {
            continuation.resume(ToolResult(call.toolName, "Glaive app is not installed.", true))
            return@suspendCancellableCoroutine
        }
        if (!hasBridgePermission(context)) {
            continuation.resume(
                ToolResult(
                    call.toolName,
                    "Nabu is missing Glaive bridge permission ($GLAIVE_ACCESS_PERMISSION). " +
                        "Update/reinstall both Nabu and Glaive from matching builds, then retry.",
                    true
                )
            )
            return@suspendCancellableCoroutine
        }

        try {
            DebugLogger.log("GlaiveBridge: Preparing to call ${call.toolName} with ${call.arguments}")
            val completed = AtomicBoolean(false)

            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (!completed.compareAndSet(false, true)) return
                    val error = resultData?.getString(EXTRA_INTERNAL_RESULT_ERROR)
                    val output = resultData?.getString(EXTRA_INTERNAL_RESULT_OUTPUT).orEmpty()
                    if (error != null) {
                        continuation.resume(ToolResult(call.toolName, error, true))
                    } else {
                        continuation.resume(ToolResult(call.toolName, output))
                    }
                }
            }

            continuation.invokeOnCancellation {
                completed.set(true)
            }

            val proxyIntent = Intent(context, GlaiveBridgeRelayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(EXTRA_INTERNAL_TOOL_NAME, call.toolName)
                putExtra(EXTRA_INTERNAL_TOOL_PARAMS, JSONObject(call.arguments).toString())
                putExtra(EXTRA_INTERNAL_RESULT_RECEIVER, receiver)
            }
            context.startActivity(proxyIntent)
        } catch (e: SecurityException) {
            if (continuation.isActive) {
                continuation.resume(
                    ToolResult(
                        call.toolName,
                        "Bridge launch denied: ${e.message}. " +
                            "Open Glaive once, then ensure both apps are installed from matching signed builds.",
                        true
                    )
                )
            }
        } catch (e: Exception) {
            if (continuation.isActive) {
                continuation.resume(ToolResult(call.toolName, "Error executing tool: ${e.message}", true))
            }
        }
    }

    // Stub to register default file manager tools if Glaive is detected
    fun registerDefaultTools(context: Context? = null) {
        val discoveredTools = context?.let { discoverTools(it.applicationContext) }.orEmpty()
        val toolsToRegister = if (discoveredTools.isNotEmpty()) discoveredTools else fallbackTools()
        toolsToRegister.forEach { tool ->
            ToolRegistry.register(tool)
        }
    }

    internal fun createExecutionIntent(toolName: String, toolParamsJson: String?): Intent {
        return Intent(ACTION_EXECUTE_TOOL).apply {
            setClassName(GLAIVE_PACKAGE, "com.mewmix.glaive.bridge.BridgeActivity")
            putExtra(EXTRA_TOOL_NAME, toolName)
            putExtra(EXTRA_TOOL_PARAMS, toolParamsJson ?: "{}")
        }
    }

    internal fun readToolOutput(data: Intent?): String? = data?.getStringExtra(EXTRA_TOOL_RESULT)
    internal fun readToolError(data: Intent?): String? = data?.getStringExtra(EXTRA_TOOL_ERROR)
    internal fun writeRelayResult(receiver: ResultReceiver, output: String?, error: String?) {
        val payload = Bundle().apply {
            putString(EXTRA_INTERNAL_RESULT_OUTPUT, output)
            putString(EXTRA_INTERNAL_RESULT_ERROR, error)
        }
        receiver.send(0, payload)
    }

    internal fun extractRelayToolName(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_INTERNAL_TOOL_NAME)

    internal fun extractRelayToolParams(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_INTERNAL_TOOL_PARAMS)

    internal fun relayReceiverExtraName(): String = EXTRA_INTERNAL_RESULT_RECEIVER

    private fun hasBridgePermission(context: Context): Boolean {
        return context.checkSelfPermission(GLAIVE_ACCESS_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    private fun discoverTools(context: Context): List<Tool> {
        val uri = Uri.parse("content://com.mewmix.glaive.tool_provider")
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null).use { cursor ->
                readToolsFromCursor(cursor)
            }
        }.getOrElse { error ->
            DebugLogger.log("GlaiveBridge: Failed to discover tools from provider: ${error.message}")
            emptyList()
        }
    }

    private fun readToolsFromCursor(cursor: Cursor?): List<Tool> {
        if (cursor == null) return emptyList()
        val nameIndex = cursor.getColumnIndex("name")
        val descriptionIndex = cursor.getColumnIndex("description")
        val parametersIndex = cursor.getColumnIndex("parameters")
        if (nameIndex < 0 || descriptionIndex < 0 || parametersIndex < 0) {
            return emptyList()
        }

        val tools = mutableListOf<Tool>()
        while (cursor.moveToNext()) {
            val name = cursor.getString(nameIndex)?.trim().orEmpty()
            if (name.isBlank()) continue
            val description = cursor.getString(descriptionIndex)?.trim().orEmpty()
            val parameterJson = cursor.getString(parametersIndex).orEmpty()
            var parameters = parseParameters(parameterJson)
            if (name == "search_files" || name == "list_files") {
                parameters = parameters + mapOf("page" to "Optional page number (default 1, 20 results per page).")
            }
            tools += Tool(
                name = name,
                description = if (description.isBlank()) "Tool provided by Glaive." else description,
                parameters = parameters
            )
        }
        return tools
    }

    private fun parseParameters(rawJson: String): Map<String, String> {
        if (rawJson.isBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(rawJson)
            val map = linkedMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = json.optString(key).ifBlank { "string" }
            }
            map.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun fallbackTools(): List<Tool> {
        return listOf(
            Tool(
                name = "list_files",
                description = "List files in a directory.",
                parameters = mapOf(
                    "path" to "The directory path to list.",
                    "page" to "Optional page number (default 1, 20 results per page)."
                )
            ),
            Tool(
                name = "read_file",
                description = "Read the content of a text file.",
                parameters = mapOf("path" to "The file path to read.")
            ),
            Tool(
                name = "write_file",
                description = "Write content to a file.",
                parameters = mapOf("path" to "The file path.", "content" to "The text content.")
            ),
            Tool(
                name = "create_dir",
                description = "Create a directory path recursively.",
                parameters = mapOf("path" to "The directory path to create.")
            ),
            Tool(
                name = "delete_file",
                description = "Delete a file or directory recursively.",
                parameters = mapOf("path" to "The file or directory path to delete.")
            ),
            Tool(
                name = "search_files",
                description = "Search files under a root path by query string.",
                parameters = mapOf(
                    "root_path" to "Root directory to search under.",
                    "query" to "Name fragment or search query.",
                    "page" to "Optional page number (default 1, 20 results per page)."
                )
            )
        )
    }
}

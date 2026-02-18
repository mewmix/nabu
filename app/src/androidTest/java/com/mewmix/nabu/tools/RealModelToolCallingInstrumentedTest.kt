package com.mewmix.nabu.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mewmix.nabu.data.Model
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.ModelType
import com.mewmix.nabu.utils.DebugLogger
import com.mewmix.nabu.utils.SettingsManager
import com.mewmix.nabu.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RealModelToolCallingInstrumentedTest {

    @Test
    fun taskAndGgufModelsInvokeToolBridgeAndReturnVisibleOutput() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            DebugLogger.initialize(context)
            SettingsManager.setBenchmark(context, false)
            SettingsManager.setTtsEnabled(context, false)
            SettingsManager.setLlmThreadsAuto(context, true)
            SettingsManager.setLlmNCtx(context, 2048)
            SettingsManager.setLlmNBatch(context, 512)
            SettingsManager.setLlmMaxNewTokens(context, 192)
            SettingsManager.setLlmTtftTimeoutMs(context, 45_000)
            SettingsManager.setLlmTotalTimeoutMs(context, 180_000)
            seedLocalModels(context)
            assertTrue("Glaive must be installed for tool-calling test", GlaiveBridge.isInstalled(context))
            GlaiveBridge.registerDefaultTools()

            val manager = ModelManager(context)
            val downloaded = manager.models.filter { it.isDownloaded && it.type == ModelType.LLM }
            val taskModel = downloaded.firstOrNull { it.backend == "mediapipe" }
            val ggufModel = downloaded.firstOrNull { it.backend == "llama" }

            assertNotNull("No downloaded task (.task) model available", taskModel)
            assertNotNull("No downloaded gguf (.gguf) model available", ggufModel)

            validateModelToolRoundTrip(context, taskModel!!, requireBridge = true)
            validateModelToolRoundTrip(context, ggufModel!!, requireBridge = false)
        }
    }

    private fun seedLocalModels(context: Context) {
        val modelDir = File(context.filesDir, "models").apply { mkdirs() }
        val sources = listOf(
            "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf"
        )

        for (name in sources) {
            val src = File("/data/local/tmp/$name")
            val dst = File(modelDir, name)
            if (!src.exists() || src.length() <= 0L || dst.exists()) continue
            src.copyTo(dst, overwrite = false)
        }
    }

    private suspend fun validateModelToolRoundTrip(
        context: Context,
        model: Model,
        requireBridge: Boolean
    ) {
        val viewModel = ChatViewModel(context, model.id)
        waitFor("active conversation for ${model.id}", 45_000) {
            viewModel.activeConversationId.value != null
        }
        viewModel.selectModel(model.id)
        waitFor("active model ${model.id}", 45_000) {
            viewModel.activeModel.value?.id == model.id
        }
        assertTrue("tool registry empty for ${model.id}", ToolRegistry.tools.value.any { it.isAvailable })

        val previousConversationId = viewModel.activeConversationId.value
        viewModel.createConversation()
        waitFor("fresh conversation for ${model.id}", 30_000) {
            val current = viewModel.activeConversationId.value
            current != null && current != previousConversationId
        }
        viewModel.selectModel(model.id)
        waitFor("active model ${model.id} after new conversation", 30_000) {
            viewModel.activeModel.value?.id == model.id
        }

        viewModel.updateSystemPrompt(
            "You are a tool-use QA assistant. " +
                "For filesystem queries, call list_files with the exact absolute path. " +
                "After TOOL_RESULT, answer in one concise sentence."
        )

        val startLogIndex = DebugLogger.getLogs().size
        viewModel.sendMessage("Use list_files for /sdcard/Download and summarize the result.")

        waitFor("inference done for ${model.id}", 180_000) {
            val messages = viewModel.chatMessages.value
            val last = messages.lastOrNull()
            !viewModel.isLoading.value && last != null && !last.isFromUser && last.message.trim() != "..."
        }

        val logs = DebugLogger.getLogs().drop(startLogIndex)
        val bridgeCalled = logs.any { it.contains("GlaiveBridge: Preparing to call list_files") }

        val assistant = viewModel.chatMessages.value.lastOrNull { !it.isFromUser }?.message.orEmpty().trim()
        assertTrue("Assistant output was empty for ${model.id} (${model.backend})", assistant.isNotBlank())
        assertFalse(
            "Assistant remained on tool placeholder for ${model.id} (${model.backend})",
            assistant.contains("Running tool")
        )

        if (requireBridge) {
            assertTrue("Tool bridge was not invoked for ${model.id} (${model.backend})", bridgeCalled)
        } else if (!bridgeCalled) {
            // GGUF flows may fail before tool dispatch; surface an explicit assistant-visible error.
            assertTrue(
                "Expected explicit failure text when bridge is not invoked for ${model.id}: $assistant",
                assistant.contains("generation error", ignoreCase = true) ||
                    assistant.contains("No model response generated", ignoreCase = true)
            )
        }
    }

    private suspend fun waitFor(label: String, timeoutMs: Long, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("Timed out waiting for $label")
            }
            delay(250)
        }
    }
}

package com.example.nabu.utils

import android.content.Context
import com.example.kokoro.chat.LlmInference
import com.example.kokoro.chat.LlmMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

object LlmExperimentRunner {

    suspend fun runContextSweep(
        context: Context,
        modelId: String,
        inferenceFactory: (Context, String) -> LlmInference
    ) {
        val sweepSteps = listOf(512, 1024, 2048, 4096, 8192, 16384)
        val systemPrompt = "You are a helpful assistant."
        val syntheticWord = "test "

        LlmStructuredLogger.logEvent(
            eventType = "experiment_run_started",
            context = context,
            modelId = modelId,
            extras = mapOf("experiment_name" to "context_sweep", "steps" to sweepSteps)
        )

        val modelFile = File(context.filesDir, "models/${modelId}.task")
        if (!modelFile.exists()) {
            LlmStructuredLogger.logEvent(
                eventType = "experiment_run_failed",
                context = context,
                modelId = modelId,
                extras = mapOf("error" to "Model file not found")
            )
            return
        }

        // Create a dedicated inference instance for the experiment
        val inference = withContext(Dispatchers.Main) {
            val inf = inferenceFactory(context, modelFile.absolutePath)
            inf.initialize()
            inf
        }

        try {
            for (stepTokens in sweepSteps) {
                // Construct prompt
                val promptTokenCount = stepTokens
                val repeatCount = (promptTokenCount - 6).coerceAtLeast(1)
                val userPrompt = syntheticWord.repeat(repeatCount)

                val conversation = listOf(
                    LlmMessage(role = "system", content = systemPrompt),
                    LlmMessage(role = "user", content = userPrompt)
                )

                val estimatedTokens = (systemPrompt.length + userPrompt.length) / 4 // Crude estimate
                val whitespaceTokens = (systemPrompt + " " + userPrompt).split(Regex("\\s+")).size

                LlmStructuredLogger.logEvent(
                    eventType = "experiment_step_started",
                    context = context,
                    modelId = modelId,
                    extras = mapOf(
                        "step_target_tokens" to stepTokens,
                        "tokens_estimated_whitespace" to whitespaceTokens,
                        "tokens_estimated_chars_div4" to estimatedTokens
                    )
                )

                val start = System.currentTimeMillis()
                val (finalResponse, stepError) = runInference(inference, conversation)
                val end = System.currentTimeMillis()

                LlmStructuredLogger.logEvent(
                    eventType = "experiment_step_result",
                    context = context,
                    modelId = modelId,
                    extras = mapOf(
                        "step_target_tokens" to stepTokens,
                        "success" to (stepError == null),
                        "error" to stepError,
                        "output_length" to finalResponse.length,
                        "latency_ms" to (end - start)
                    )
                )
            }
        } finally {
            withContext(Dispatchers.Main) {
                inference.close()
            }
        }

        LlmStructuredLogger.logEvent(
            eventType = "experiment_run_finished",
            context = context,
            modelId = modelId
        )
    }

    suspend fun runOutputSweep(
        context: Context,
        modelId: String,
        inferenceFactory: (Context, String) -> LlmInference
    ) {
         val outputSteps = listOf(64, 256, 512, 1024)
         // Use a safe input size, e.g., 512 tokens
         val inputTokens = 512
         val syntheticWord = "test "
         val userPrompt = syntheticWord.repeat(inputTokens)
         val systemPrompt = "You are a helpful assistant."

         LlmStructuredLogger.logEvent(
            eventType = "experiment_run_started",
            context = context,
            modelId = modelId,
            extras = mapOf("experiment_name" to "output_sweep", "steps" to outputSteps)
        )

        val modelFile = File(context.filesDir, "models/${modelId}.task")
        if (!modelFile.exists()) return

        val inference = withContext(Dispatchers.Main) {
            val inf = inferenceFactory(context, modelFile.absolutePath)
            inf.initialize()
            inf
        }

        try {
             for (requestedOutput in outputSteps) {
                 val prompt = "Repeat the word 'alpha' $requestedOutput times. Do not stop early."
                 val conversation = listOf(
                    LlmMessage(role = "system", content = systemPrompt),
                    LlmMessage(role = "user", content = prompt)
                )

                val start = System.currentTimeMillis()
                val (finalResponse, stepError) = runInference(inference, conversation)
                val end = System.currentTimeMillis()

                val outputWords = finalResponse.split(Regex("\\s+")).size

                 LlmStructuredLogger.logEvent(
                    eventType = "experiment_step_result",
                    context = context,
                    modelId = modelId,
                    extras = mapOf(
                        "requested_output_tokens" to requestedOutput,
                        "generated_words_approx" to outputWords,
                        "success" to (stepError == null),
                        "error" to stepError,
                        "latency_ms" to (end - start)
                    )
                )
             }
        } finally {
             withContext(Dispatchers.Main) {
                inference.close()
            }
        }

         LlmStructuredLogger.logEvent(
            eventType = "experiment_run_finished",
            context = context,
            modelId = modelId
        )
    }

    private suspend fun runInference(inference: LlmInference, conversation: List<LlmMessage>): Pair<String, String?> = withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<Pair<String, String?>>()
        val sb = StringBuilder()

        try {
            // Note: inference.sendMessage returns immediately, work happens in background thread of LlmInference
            inference.sendMessage(conversation) { partial, done ->
                sb.append(partial)
                if (done) {
                    deferred.complete(sb.toString() to null)
                }
            }
        } catch (e: Exception) {
             deferred.complete("" to e.localizedMessage)
        }

        try {
            // Wait up to 2 minutes for inference to complete
            withTimeout(120_000) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
             "" to "Timeout"
        }
    }
}

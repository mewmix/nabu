package com.example.nabu.kokoro

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.example.nabu.utils.DebugLogger
import java.io.File
import java.util.Locale

data class KokoroBundle(
    val session: OrtSession,
    val ep: RunEp,
    val graphId: String,
    val sampleRate: Int
)

object KokoroLoader {
    private const val TAG = "KokoroLoader"

    fun load(ctx: Context, manifest: Manifest, choice: RunEp): KokoroBundle {
        val root = File(ctx.filesDir, "")
        val fp16Entry = manifest.files.firstOrNull { it.id == "kokoro_fp16" }
        val int8Entry = manifest.files.firstOrNull { it.id == "kokoro_int8" }
        val fp16 = fp16Entry?.let { File(root, it.dest) }
        val int8 = int8Entry?.let { File(root, it.dest) }

        val attempts = sequence {
            if (choice == RunEp.AUTO || choice == RunEp.NNAPI) {
                yield(Attempt(RunEp.NNAPI, fp16Entry, fp16))
            }
            if (choice == RunEp.AUTO || choice == RunEp.CPU) {
                yield(Attempt(RunEp.CPU, fp16Entry, fp16))
            }
            yield(Attempt(RunEp.CPU, int8Entry, int8))
        }

        val env: OrtEnvironment = OrtFactory.env
        for (attempt in attempts) {
            val modelFile = attempt.file
            if (modelFile == null || !modelFile.exists()) {
                Log.w(TAG, "Skipping ${attempt.graphId} for ${attempt.ep}: file missing")
                DebugLogger.log("KokoroLoader skipping ${attempt.graphId} (${attempt.ep}) missing file=${modelFile?.absolutePath}")
                continue
            }
            val cachePath = attempt.cacheFile(ctx, manifest)
            val options = OrtFactory.sessionOptions(attempt.ep, cachePath.absolutePath)
            try {
                val session = env.createSession(modelFile.absolutePath, options)
                options.close()
                return KokoroBundle(
                    session = session,
                    ep = attempt.ep,
                    graphId = attempt.graphId,
                    sampleRate = manifest.sampleRate
                )
            } catch (err: Exception) {
                options.close()
                Log.w(TAG, "Failed to create session for ${attempt.graphId} on ${attempt.ep}", err)
                DebugLogger.log("KokoroLoader session create failed id=${attempt.graphId} ep=${attempt.ep} error=${err.message}")
            }
        }
        throw IllegalStateException("No valid Kokoro ONNX found. Run Downloader.ensureModels first.")
    }

    private data class Attempt(
        val ep: RunEp,
        val manifestFile: ManifestFile?,
        val file: File?
    ) {
        val graphId: String = manifestFile?.id ?: "custom"

        fun cacheFile(ctx: Context, manifest: Manifest): File {
            val parts = buildList {
                add(manifest.name)
                add(manifest.version)
                add(graphId)
                manifestFile?.sha256?.take(8)?.let { add(it) }
                add(ep.name.lowercase(Locale.US))
            }.joinToString("_") { it.safeSegment() }

            val fileName = "kokoro_${parts}.opt.onnx"
            return File(ctx.cacheDir, fileName)
        }

        private fun String.safeSegment(): String =
            lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "x" }
    }
}

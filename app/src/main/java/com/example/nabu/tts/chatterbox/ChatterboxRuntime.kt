package com.example.nabu.tts.chatterbox

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import com.example.nabu.tts.NabuPaths
import com.example.nabu.utils.SettingsManager
import java.io.File

object ChatterboxRuntime {
    private var engine: ChatterboxEngine? = null
    private var tokenizer: JsonLlamaTokenizer? = null

    suspend fun getOrLoad(context: Context): ChatterboxEngine {
        val desiredNnapi = SettingsManager.isChatterboxNnapi(context)
        engine?.let { existing ->
            if (existing.useNnapi == desiredNnapi) {
                existing.load()
                return existing
            } else {
                existing.close()
                engine = null
                tokenizer = null
            }
        }

        val preparedEngine = synchronized(this) {
            engine?.let { return@synchronized it }

            require(ChatterboxInstaller.isInstalled(context)) {
                "Chatterbox assets missing. Please download the Chatterbox pack first."
            }
            val baseDir = NabuPaths.chatterboxModelDir(context)
            val tokenizerFile = File(baseDir, "tokenizer.json")
            val tokenizerInstance = JsonLlamaTokenizer(tokenizerFile)
            val env = OrtEnvironment.getEnvironment()
            val created = ChatterboxEngine(env, tokenizerInstance, baseDir, desiredNnapi)
            tokenizer = tokenizerInstance
            engine = created
            created
        }

        preparedEngine.load()
        return preparedEngine
    }

    fun shutdown() {
        synchronized(this) {
            engine?.close()
            engine = null
            tokenizer = null
        }
    }
}

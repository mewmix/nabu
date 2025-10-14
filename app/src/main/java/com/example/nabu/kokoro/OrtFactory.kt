package com.example.nabu.kokoro

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession.SessionOptions
import android.util.Log

object OrtFactory {
    private const val TAG = "KokoroOrtFactory"

    val env: OrtEnvironment by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OrtEnvironment.getEnvironment()
    }

    fun sessionOptions(ep: RunEp, cachePath: String?): SessionOptions {
        val options = SessionOptions()
        try {
            val level = when (ep) {
                RunEp.NNAPI -> SessionOptions.OptLevel.NO_OPT
                else -> SessionOptions.OptLevel.ALL_OPT
            }
            options.setOptimizationLevel(level)
        } catch (err: OrtException) {
            Log.w(TAG, "Failed to set opt level", err)
        }
        if (ep != RunEp.NNAPI && !cachePath.isNullOrBlank()) {
            try {
                options.setOptimizedModelFilePath(cachePath)
            } catch (err: OrtException) {
                Log.w(TAG, "Unable to set optimized model cache: ${err.message}")
            }
        }
        try {
            options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors())
        } catch (err: OrtException) {
            Log.w(TAG, "Failed to set intra-op threads", err)
        }

        when (ep) {
            RunEp.NNAPI -> {
                runCatching { options.addNnapi() }
                    .onFailure { Log.w(TAG, "NNAPI unavailable, will fall back to CPU", it) }
            }
            RunEp.CPU, RunEp.AUTO -> {
                // CPU is the default, nothing extra to do.
            }
        }
        return options
    }
}

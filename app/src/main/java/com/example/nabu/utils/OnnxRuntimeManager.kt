package com.example.nabu.utils

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nabu.R
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(private val context: Context) : ViewModel() {
    init {
        viewModelScope.launch {
            if (SettingsManager.getTtsEngine(context.applicationContext) != TtsEngine.CHATTERBOX) {
                OnnxRuntimeManager.initialize(context.applicationContext)
            }
        }
    }

    fun getSession(): OrtSession? = OnnxRuntimeManager.getSessionOrNull()

    fun reinitializeSession(modelPath: String) {
        viewModelScope.launch {
            OnnxRuntimeManager.reinitialize(context, modelPath)
        }
    }

    override fun onCleared() {
        super.onCleared()
        OnnxRuntimeManager.close()
    }
}

object OnnxRuntimeManager {
    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null

    @Synchronized
    fun initialize(context: Context, modelPath: String? = null) {
        if (environment == null) {
            environment = OrtEnvironment.getEnvironment()
        }
        session = if (modelPath != null) {
            createSession(modelPath)
        } else {
            when (SettingsManager.getTtsEngine(context)) {
                TtsEngine.CHATTERBOX -> null
                else -> createSession(context)
            }
        }
    }

    @Synchronized
    fun reinitialize(context: Context, modelPath: String) {
        if (modelPath.endsWith(".onnx")) {
            session?.close()
            initialize(context, modelPath)
        }
    }

    private fun createSession(context: Context): OrtSession {
        val options = SessionOptions().apply {
            addConfigEntry("nnapi.flags", "USE_FP16")
            addConfigEntry("nnapi.use_gpu", "true")
            addConfigEntry("nnapi.gpu_precision_loss_allowed", "true")
        }

        return when (SettingsManager.getTtsEngine(context)) {
            TtsEngine.KOKORO -> context.resources.openRawResource(R.raw.kokoro).use { stream ->
                environment!!.createSession(stream.readBytes(), options)
            }
            TtsEngine.KITTEN -> context.assets.open("kitten_tts/kitten_tts_nano_v0_1.onnx").use { stream ->
                environment!!.createSession(stream.readBytes(), options)
            }
            TtsEngine.CHATTERBOX -> throw UnsupportedOperationException("Chatterbox uses dedicated runtime initialization")
        }
    }

    private fun createSession(modelPath: String): OrtSession {
        val options = SessionOptions().apply {
            addConfigEntry("nnapi.flags", "USE_FP16")
            addConfigEntry("nnapi.use_gpu", "true")
            addConfigEntry("nnapi.gpu_precision_loss_allowed", "true")
        }
        val modelBytes = File(modelPath).readBytes()
        return environment!!.createSession(modelBytes, options)
    }


    fun getSession(): OrtSession = requireNotNull(session) { "ONNX Session not initialized" }

    fun getSessionOrNull(): OrtSession? = session

    fun close() {
        session?.close()
        environment?.close()
    }
}

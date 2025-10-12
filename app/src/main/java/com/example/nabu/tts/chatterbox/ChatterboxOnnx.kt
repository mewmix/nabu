package com.example.nabu.tts.chatterbox

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.nio.FloatBuffer
import java.nio.LongBuffer

internal object ChatterboxOnnx {
    fun floatTensor(env: OrtEnvironment, data: FloatArray, shape: LongArray): OnnxTensor {
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
    }

    fun longTensor(env: OrtEnvironment, data: LongArray, shape: LongArray): OnnxTensor {
        return OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)
    }

    fun floatZeros(env: OrtEnvironment, shape: LongArray): OnnxTensor {
        val size = shape.fold(1L) { acc, dim -> acc * dim }.toInt()
        val buffer = FloatArray(size.coerceAtLeast(0))
        return floatTensor(env, buffer, shape)
    }
}

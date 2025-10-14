package com.example.nabu.kokoro

/**
 * Declarative description of Kokoro model assets that should be available on device.
 */
data class Manifest(
    val name: String,
    val version: String,
    val files: List<ManifestFile>,
    val io: Io,
    val sampleRate: Int
) {
    data class Io(
        val encoderInput: String? = null,
        val decoderInput: String? = null,
        val vocoderInput: String? = null,
        val singleGraphInput: String? = null
    )
}

data class ManifestFile(
    val id: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val dest: String,
    val notes: String? = null
)

object ManifestProvider {
    fun kokoroV1(): Manifest = Manifest(
        name = "Kokoro-82M",
        version = "1.0.0",
        files = listOf(
            ManifestFile(
                id = "kokoro_fp16",
                url = "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/onnx/model.onnx?download=true",
                sha256 = "8fbea51ea711f2af382e88c833d9e288c6dc82ce5e98421ea61c058ce21a34cb",
                sizeBytes = 341_139_456,
                dest = "models/kokoro/model_fp16.onnx",
                notes = "FP16-enabled graph; NNAPI preferred"
            ),
            ManifestFile(
                id = "kokoro_int8",
                url = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.int8.onnx",
                sha256 = "6e742170d309016e5891a994e1ce1559c702a2ccd0075e67ef7157974f6406cb",
                sizeBytes = 92_361_271,
                dest = "models/kokoro/model_int8.onnx",
                notes = "CPU fallback; INT8 graph from kokoro-onnx v1.0"
            )
        ),
        io = Manifest.Io(
            encoderInput = "phones",
            decoderInput = "enc_seq",
            vocoderInput = "acoustic_feats",
            singleGraphInput = "input_ids"
        ),
        sampleRate = 24_000
    )
}

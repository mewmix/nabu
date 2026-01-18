package com.mewmix.nabu.kokoro

/**
 * Execution provider preference for Kokoro ONNX sessions.
 *
 * `AUTO` tries NNAPI first then falls back to CPU. `NNAPI` forces the accelerator.
 * `CPU` keeps the legacy behaviour.
 */
enum class RunEp {
    AUTO,
    NNAPI,
    CPU
}

package com.example.nabu.utils

import kotlin.math.min

/**
 * Lock-free ring buffer for PCM snapshots (power-of-two capacity).
 * Push from the audio thread; draw from the UI thread.
 */
object PcmTap {
    private const val CAP = 8192 // Power of two for fast masking
    private const val MASK = CAP - 1
    private val buf = FloatArray(CAP)
    @Volatile private var w = 0 // Write position
    @Volatile var enabled: Boolean = true // Toggle to pause capturing

    /** Push floats in [-1, 1]. Zero-alloc, branch-light. */
    fun pushFloats(src: FloatArray, off: Int = 0, len: Int = src.size) {
        if (!enabled) return
        var i = 0
        val L = min(len, src.size - off)
        var wi = w
        while (i < L) {
            buf[wi and MASK] = src[off + i].coerceIn(-1f, 1f)
            wi = (wi + 1) and MASK
            i++
        }
        w = wi
    }

    /** Snapshot the last [n] samples for drawing (UI thread). */
    fun getLastSamples(n: Int): FloatArray {
        val out = FloatArray(n)
        val wi = w
        var ri = (wi - n) and MASK
        var i = 0
        while (i < n) {
            out[i] = buf[ri]
            ri = (ri + 1) and MASK
            i++
        }
        return out
    }
}

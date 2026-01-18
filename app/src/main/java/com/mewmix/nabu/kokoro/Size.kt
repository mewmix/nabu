package com.mewmix.nabu.kokoro

import kotlin.math.abs

object Size {
    fun check(actual: Long, expected: Long, tolerance: Double): Boolean {
        if (expected <= 0) return actual == expected
        val delta = abs(actual - expected).toDouble()
        return delta <= expected * tolerance
    }
}

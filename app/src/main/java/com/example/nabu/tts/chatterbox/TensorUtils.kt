package com.example.nabu.tts.chatterbox

internal object TensorUtils {
    fun flatten3d(data: Array<Array<FloatArray>>): FloatArray {
        if (data.isEmpty()) return FloatArray(0)
        val rows = data[0].size
        val cols = data[0][0].size
        val total = data.size * rows * cols
        val flat = FloatArray(total)
        var index = 0
        for (batch in data) {
            for (row in batch) {
                for (value in row) {
                    flat[index++] = value
                }
            }
        }
        return flat
    }

    fun flatten2d(data: Array<FloatArray>): FloatArray {
        if (data.isEmpty()) return FloatArray(0)
        val cols = data[0].size
        val flat = FloatArray(data.size * cols)
        var index = 0
        for (row in data) {
            for (value in row) {
                flat[index++] = value
            }
        }
        return flat
    }

    fun flatten4d(data: Array<Array<Array<FloatArray>>>): FloatArray {
        if (data.isEmpty()) return FloatArray(0)
        val dim1 = data[0].size
        val dim2 = data[0][0].size
        val dim3 = data[0][0][0].size
        val total = data.size * dim1 * dim2 * dim3
        val flat = FloatArray(total)
        var index = 0
        for (batch in data) {
            for (layer in batch) {
                for (row in layer) {
                    for (value in row) {
                        flat[index++] = value
                    }
                }
            }
        }
        return flat
    }

    fun flattenLong2d(data: Array<LongArray>): LongArray {
        if (data.isEmpty()) return LongArray(0)
        val cols = data[0].size
        val flat = LongArray(data.size * cols)
        var index = 0
        for (row in data) {
            for (value in row) {
                flat[index++] = value
            }
        }
        return flat
    }
}

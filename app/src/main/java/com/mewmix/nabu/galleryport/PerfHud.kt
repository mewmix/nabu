package com.mewmix.nabu.galleryport

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.system.measureNanoTime

object PerfHud {
    private val stats = mutableStateMapOf<String, Float>()

    fun <T> record(label: String, block: () -> T): T {
        var result: T? = null
        val ms = measureNanoTime { result = block() } / 1e6f
        stats[label] = ms
        return result!!
    }

    @Composable
    fun Overlay() {
        Column(Modifier.padding(6.dp)) {
            stats.forEach { (k, v) ->
                Text("$k: ${"%.1f".format(v)} ms", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

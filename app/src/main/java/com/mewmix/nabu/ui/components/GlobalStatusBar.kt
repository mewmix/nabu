package com.mewmix.nabu.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewmix.nabu.data.ModelState
import com.mewmix.nabu.kokoro.Downloader
import com.mewmix.nabu.ui.brutalist.Brutal
import com.mewmix.nabu.utils.SettingsManager
import com.mewmix.nabu.utils.formatBytes

@Composable
fun GlobalStatusBar(
    modelState: ModelState,
    downloadProgress: Downloader.DownloadProgress?,
    benchmarkStats: Map<String, Float>
) {
    val context = LocalContext.current
    val isBenchmarking = SettingsManager.isBenchmark(context)
    val isLoading = modelState is ModelState.Loading
    val isError = modelState is ModelState.Error

    AnimatedVisibility(
        visible = isLoading || isError || (isBenchmarking && benchmarkStats.isNotEmpty()),
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = Brutal.panelHl)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Loading / Error State
            if (isLoading || isError) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Brutal.textBright
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (downloadProgress != null) "Downloading models..." else "Loading runtime...",
                            color = Brutal.textBright,
                            fontSize = 12.sp
                        )
                    } else if (isError) {
                        val msg = (modelState as ModelState.Error).message
                        Text(
                            text = "⚠️ $msg",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }
                }

                if (isLoading && downloadProgress != null && downloadProgress.totalBytes > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val ratio = downloadProgress.downloadedBytes.toFloat() / downloadProgress.totalBytes.toFloat()
                    LinearProgressIndicator(
                        progress = { ratio.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = Brutal.textBright,
                        trackColor = Brutal.panelBg
                    )
                    Text(
                        "${formatBytes(downloadProgress.downloadedBytes)} / ${formatBytes(downloadProgress.totalBytes)}",
                        color = Brutal.textDim,
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }

            // Benchmark Stats
            if (isBenchmarking && benchmarkStats.isNotEmpty()) {
                if (isLoading || isError) Spacer(modifier = Modifier.height(8.dp))
                
                Text("BENCHMARK", color = Brutal.textDim, fontSize = 10.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    benchmarkStats.forEach { (label, value) ->
                        Text(
                            "$label: ${"%.1f".format(value)}ms",
                            color = Brutal.textBright,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

package com.mewmix.nabu.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.ModelType
import com.mewmix.nabu.data.TtsModelValidator
import com.mewmix.nabu.ui.brutalist.Brutal
import com.mewmix.nabu.utils.OnnxRuntimeManager
import com.mewmix.nabu.utils.SettingsManager
import java.io.File

@Composable
fun RuntimeStatusLine(
    modifier: Modifier = Modifier,
    ttsEnabled: Boolean = true,
    llmRuntimeDescription: String = "NOT LOADED"
) {
    val context = LocalContext.current
    val ttsEngine = SettingsManager.getTtsEngine(context)
    val runtimeStatus = OnnxRuntimeManager.runtimeStatus()
    val modelManager = remember { ModelManager(context) }
    val supertonicModelId = SettingsManager.getSupertonicModelId(context)
    val supertonicModelName = supertonicModelId?.let { id ->
        modelManager.models.firstOrNull { it.type == ModelType.TTS && it.id == id }?.name
    }
    val supertonicModelLabel = supertonicModelName ?: supertonicModelId
    val sopranoModelId = "soprano-80m-onnx"
    val sopranoDir = File(context.filesDir, "models/$sopranoModelId")
    val sopranoPartialDir = File(context.filesDir, "models/${sopranoModelId}_partial")
    val sopranoMissing = TtsModelValidator.missingFiles(sopranoModelId, sopranoDir, sopranoPartialDir)

    val runtimeLabel = if (!ttsEnabled) {
        "TTS OFF"
    } else if (ttsEngine == "supertonic") {
        buildString {
            append("SUPERTONIC / CPU")
            supertonicModelLabel?.let { append(" / $it") }
        }
    } else if (ttsEngine == "soprano") {
        if (sopranoMissing.isEmpty()) {
            "SOPRANO / CPU / soprano-80m-onnx"
        } else {
            "SOPRANO / CPU / incomplete download (${sopranoMissing.size} missing)"
        }
    } else {
        if (runtimeStatus == null) {
            "KOKORO / LOADING..."
        } else {
            "KOKORO / ${runtimeStatus.ep.name} / ${runtimeStatus.graphId}"
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "TTS: $runtimeLabel",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "LLM: $llmRuntimeDescription",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

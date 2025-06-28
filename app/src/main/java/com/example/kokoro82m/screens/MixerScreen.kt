package com.example.kokoro82m.screens

import ai.onnxruntime.OrtSession
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.kokoro82m.utils.InterpolationMode
import com.example.kokoro82m.utils.PhonemeConverter
import com.example.kokoro82m.utils.StyleLoader
import com.example.kokoro82m.utils.createAudioFromStyleVector
import com.example.kokoro82m.utils.mixStyles
import com.example.kokoro82m.utils.playAudio
import com.example.kokoro82m.utils.saveAudio
import com.example.kokoro82m.utils.SettingsManager
import com.example.kokoro82m.utils.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Simplified mixer screen showing a static style configuration. */
@Composable
fun MixerScreen(
    session: OrtSession,
    phonemeConverter: PhonemeConverter,
    styleLoader: StyleLoader,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var text by remember { mutableStateOf("This is her warm heart, her warmest kokoro, unwavering love and comfort.") }
    var speed by remember { mutableFloatStateOf(SettingsManager.getSpeed(context)) }
    var isProcessing by remember { mutableStateOf(false) }
    var shouldSaveFile by remember { mutableStateOf(false) }

    val config = remember {
        loadStyleConfig(context) ?: Triple(
            listOf("af_sarah"),
            mapOf("af_sarah" to 1f),
            InterpolationMode.LINEAR
        )
    }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            minLines = 3,
            maxLines = 12,
            label = { Text("Text to speak") },
            modifier = Modifier.fillMaxWidth()
        )

        Text("Speed: $speed")
        Slider(
            value = speed,
            onValueChange = {
                speed = it
                SettingsManager.setSpeed(context, it)
            },
            valueRange = 0.5f..2.0f,
            steps = 5,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Styles: " + config.first.joinToString { s -> "$s(${config.second[s] ?: 1f})" })

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    shouldSaveFile = false
                    isProcessing = true
                    scope.launch {
                        val mixed = mixStyles(styleLoader, config.first, config.second, config.third)
                        generateAudio(text, mixed, speed, shouldSaveFile, session, phonemeConverter, scope, context) {
                            isProcessing = false
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isProcessing
            ) { Text(if (isProcessing) "Mixing..." else "Play") }

            Button(
                onClick = {
                    shouldSaveFile = true
                    isProcessing = true
                    scope.launch {
                        val mixed = mixStyles(styleLoader, config.first, config.second, config.third)
                        generateAudio(text, mixed, speed, shouldSaveFile, session, phonemeConverter, scope, context) {
                            isProcessing = false
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isProcessing
            ) { Text(if (isProcessing) "Mixing..." else "Play & Save") }
        }

        if (SettingsManager.isDebug(context)) {
            val logs = DebugLogger.getLogs().joinToString("\n")
            Text(logs, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun loadStyleConfig(context: android.content.Context): Triple<List<String>, Map<String, Float>, InterpolationMode>? {
    val prefs = context.getSharedPreferences("mixer_config", android.content.Context.MODE_PRIVATE)
    val saved = prefs.getString("styles", null) ?: return null
    val mode = prefs.getString("mode", InterpolationMode.LINEAR.name) ?: InterpolationMode.LINEAR.name
    val styles = mutableListOf<String>()
    val weights = mutableMapOf<String, Float>()
    saved.split(',').forEach { entry ->
        val parts = entry.split('|')
        if (parts.size == 2) {
            styles.add(parts[0])
            weights[parts[0]] = parts[1].toFloatOrNull() ?: 1f
        }
    }
    return Triple(styles, weights, InterpolationMode.valueOf(mode))
}

private fun generateAudio(
    text: String,
    style: Array<FloatArray>,
    speed: Float,
    shouldSaveFile: Boolean,
    session: OrtSession,
    phonemeConverter: PhonemeConverter,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    onComplete: () -> Unit
) {
    scope.launch(Dispatchers.IO) {
        try {
            val phonemes = phonemeConverter.phonemize(text)
            val (audio, _) = createAudioFromStyleVector(
                phonemes = phonemes,
                voice = style,
                speed = speed,
                session = session
            )
            if (shouldSaveFile) {
                saveAudio(audio, context)
            }
            playAudio(audio, scope) {}
        } catch (e: Exception) {
            DebugLogger.log("Mixer error: ${e.message}")
        } finally {
            withContext(Dispatchers.Main) { onComplete() }
        }
    }
}

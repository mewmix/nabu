package com.example.kokoro82m.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.kokoro82m.utils.SettingsManager
import com.example.kokoro82m.utils.TtsEngine
import com.example.kokoro82m.utils.OnnxRuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var debug by remember { mutableStateOf(SettingsManager.isDebug(context)) }
    var benchmark by remember { mutableStateOf(SettingsManager.isBenchmark(context)) }
    var engine by remember { mutableStateOf(SettingsManager.getTtsEngine(context)) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(engine) {
        withContext(Dispatchers.IO) {
            OnnxRuntimeManager.initialize(context.applicationContext)
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = debug,
                onCheckedChange = {
                    debug = it
                    SettingsManager.setDebug(context, it)
                }
            )
            Text(text = "Debug Mode", modifier = Modifier.padding(start = 8.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = benchmark,
                onCheckedChange = {
                    benchmark = it
                    SettingsManager.setBenchmark(context, it)
                }
            )
            Text(text = "Benchmark Mode", modifier = Modifier.padding(start = 8.dp))
        }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            TextField(
                value = engine.name,
                onValueChange = {},
                readOnly = true,
                label = { Text("TTS Engine") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                TtsEngine.values().forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.name) },
                        onClick = {
                            engine = option
                            SettingsManager.setTtsEngine(context, option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

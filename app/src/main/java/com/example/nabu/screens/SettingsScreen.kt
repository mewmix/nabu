package com.example.nabu.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.nabu.kokoro.RunEp
import com.example.nabu.components.VersionPlate
import com.example.nabu.utils.SettingsManager
import com.example.nabu.utils.OnnxRuntimeManager
import com.example.nabu.utils.getAppVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mewmix.nabu.ui.brutalist.PanelBox
import com.mewmix.nabu.ui.brutalist.SwitchToggle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val versionName = remember { getAppVersion(context) }
    var debug by remember { mutableStateOf(SettingsManager.isDebug(context)) }
    var benchmark by remember { mutableStateOf(SettingsManager.isBenchmark(context)) }
    var runtime by remember { mutableStateOf(SettingsManager.getRuntimePreference(context)) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(runtime) {
        withContext(Dispatchers.IO) {
            OnnxRuntimeManager.initialize(context.applicationContext, runtime)
        }
    }

    PanelBox(
        title = "Settings",
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SwitchToggle(
                checked = debug,
                onToggle = {
                    debug = it
                    SettingsManager.setDebug(context, it)
                },
                label = "Debug Mode"
            )

            SwitchToggle(
                checked = benchmark,
                onToggle = {
                    benchmark = it
                    SettingsManager.setBenchmark(context, it)
                },
                label = "Benchmark Mode"
            )

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                TextField(
                    value = runtime.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Execution Provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    RunEp.values().forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.name) },
                            onClick = {
                                runtime = option
                                SettingsManager.setRuntimePreference(context, option)
                                expanded = false
                            }
                        )
                    }
                }
            }

            VersionPlate(version = versionName)
        }
    }
}

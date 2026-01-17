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
import com.example.nabu.utils.ThemeManager
import com.example.nabu.utils.getAppVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mewmix.nabu.ui.brutalist.PanelBox
import com.mewmix.nabu.ui.brutalist.SwitchToggle
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.BrutalButtonText
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.example.nabu.utils.UpdateChecker
import com.example.nabu.BuildConfig
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.HorizontalDivider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val versionName = remember { getAppVersion(context) }
    var debug by remember { mutableStateOf(SettingsManager.isDebug(context)) }
    var benchmark by remember { mutableStateOf(SettingsManager.isBenchmark(context)) }
    var runtime by remember { mutableStateOf(SettingsManager.getRuntimePreference(context)) }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(runtime) {
        withContext(Dispatchers.IO) {
            OnnxRuntimeManager.initialize(
                context.applicationContext,
                runtime,
                allowDownload = SettingsManager.isKokoroAutoDownloadEnabled(context)
            )
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

            HorizontalDivider()

            Text("Theme Customization", style = MaterialTheme.typography.titleMedium)

            BrutalButton(onClick = {
                // For now, just a button to export/import to prove persistence mechanism.
                // A full color picker UI would be quite large, assuming manual edit for now or just proof of concept.
                // But let's add a "Rotate Theme" button to show it works?
                // Or "Export Current"
                scope.launch(Dispatchers.IO) {
                   val theme = ThemeManager.getTheme(context)
                   val success = ThemeManager.exportTheme(context, theme, "exported_theme.json")
                   withContext(Dispatchers.Main) {
                       Toast.makeText(context, if(success) "Theme Exported to Documents/Nabu" else "Export Failed", Toast.LENGTH_SHORT).show()
                   }
                }
            }) {
                BrutalButtonText("Export Theme JSON")
            }

             BrutalButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    // Try to import "current_theme.json" from export dir if user edited it
                    // Or specific path
                     val publicDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
                     val file = java.io.File(publicDir, "Nabu/Themes/current_theme.json")
                     val theme = ThemeManager.importTheme(context, file.absolutePath)
                     withContext(Dispatchers.Main) {
                         if (theme != null) {
                             Toast.makeText(context, "Theme Imported! Restart to apply.", Toast.LENGTH_LONG).show()
                         } else {
                             Toast.makeText(context, "Import failed or file not found.", Toast.LENGTH_SHORT).show()
                         }
                     }
                }
            }) {
                BrutalButtonText("Import 'current_theme.json'")
            }

            HorizontalDivider()

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                TextField(
                    value = "${SettingsManager.getTtsEngine(context).replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }} / ${runtime.name}",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Active Engine / Provider") },
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

            // TTS Engine Selection
            var ttsEngineExpanded by remember { mutableStateOf(false) }
            var ttsEngine by remember { mutableStateOf(SettingsManager.getTtsEngine(context)) }
            val ttsEngineOptions = listOf("kokoro", "supertonic")

            ExposedDropdownMenuBox(
                expanded = ttsEngineExpanded,
                onExpandedChange = { ttsEngineExpanded = it }
            ) {
                TextField(
                    value = ttsEngine.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("TTS Engine") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ttsEngineExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                DropdownMenu(
                    expanded = ttsEngineExpanded,
                    onDismissRequest = { ttsEngineExpanded = false }
                ) {
                    ttsEngineOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }) },
                            onClick = {
                                ttsEngine = option
                                SettingsManager.setTtsEngine(context, option)
                                ttsEngineExpanded = false
                            }
                        )
                    }
                }
            }

            val commitHash = BuildConfig.GIT_COMMIT_HASH
            val versionText = "v$versionName ($commitHash)"
            VersionPlate(version = versionText, onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mewmix/nabu/commit/$commitHash"))
                context.startActivity(intent)
            })
        }
    }
}

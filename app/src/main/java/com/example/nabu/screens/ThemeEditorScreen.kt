package com.example.nabu.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.nabu.ui.theme.AppTheme
import com.example.nabu.utils.ThemeManager
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.PanelBox
import com.mewmix.nabu.ui.brutalist.PanelRow
import java.util.Locale

@Composable
fun ThemeEditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var currentTheme by remember { mutableStateOf(ThemeManager.getTheme(context)) }
    var showSaveConfirm by remember { mutableStateOf(false) }

    PanelBox(
        title = "Theme Editor",
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BrutalButton(onClick = onBack) {
                    Text("BACK")
                }
                BrutalButton(onClick = {
                    ThemeManager.saveTheme(context, currentTheme)
                    showSaveConfirm = true
                }) {
                    Text("APPLY & SAVE")
                }
            }

            if (showSaveConfirm) {
                Text(
                    "Theme saved!",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(2000)
                    showSaveConfirm = false
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    BrutalButton(onClick = {
                        currentTheme = ThemeManager.DEFAULT_LIGHT
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("RESET TO LIGHT DEFAULT")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    BrutalButton(onClick = {
                        currentTheme = ThemeManager.DEFAULT_DARK
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("RESET TO DARK DEFAULT")
                    }
                }

                // Map all properties for editing
                // We use reflection or hardcode the list. Hardcoding is safer for now.
                val colors = listOf(
                    "Primary" to { t: AppTheme, v: Long -> t.copy(primary = v) } to { t: AppTheme -> t.primary },
                    "On Primary" to { t: AppTheme, v: Long -> t.copy(onPrimary = v) } to { t: AppTheme -> t.onPrimary },
                    "Primary Container" to { t: AppTheme, v: Long -> t.copy(primaryContainer = v) } to { t: AppTheme -> t.primaryContainer },
                    "On Primary Container" to { t: AppTheme, v: Long -> t.copy(onPrimaryContainer = v) } to { t: AppTheme -> t.onPrimaryContainer },

                    "Secondary" to { t: AppTheme, v: Long -> t.copy(secondary = v) } to { t: AppTheme -> t.secondary },
                    "On Secondary" to { t: AppTheme, v: Long -> t.copy(onSecondary = v) } to { t: AppTheme -> t.onSecondary },
                    "Secondary Container" to { t: AppTheme, v: Long -> t.copy(secondaryContainer = v) } to { t: AppTheme -> t.secondaryContainer },
                    "On Secondary Container" to { t: AppTheme, v: Long -> t.copy(onSecondaryContainer = v) } to { t: AppTheme -> t.onSecondaryContainer },

                    "Tertiary" to { t: AppTheme, v: Long -> t.copy(tertiary = v) } to { t: AppTheme -> t.tertiary },
                    "On Tertiary" to { t: AppTheme, v: Long -> t.copy(onTertiary = v) } to { t: AppTheme -> t.onTertiary },
                    "Tertiary Container" to { t: AppTheme, v: Long -> t.copy(tertiaryContainer = v) } to { t: AppTheme -> t.tertiaryContainer },
                    "On Tertiary Container" to { t: AppTheme, v: Long -> t.copy(onTertiaryContainer = v) } to { t: AppTheme -> t.onTertiaryContainer },

                    "Error" to { t: AppTheme, v: Long -> t.copy(error = v) } to { t: AppTheme -> t.error },
                    "On Error" to { t: AppTheme, v: Long -> t.copy(onError = v) } to { t: AppTheme -> t.onError },
                    "Error Container" to { t: AppTheme, v: Long -> t.copy(errorContainer = v) } to { t: AppTheme -> t.errorContainer },
                    "On Error Container" to { t: AppTheme, v: Long -> t.copy(onErrorContainer = v) } to { t: AppTheme -> t.onErrorContainer },

                    "Background" to { t: AppTheme, v: Long -> t.copy(background = v) } to { t: AppTheme -> t.background },
                    "On Background" to { t: AppTheme, v: Long -> t.copy(onBackground = v) } to { t: AppTheme -> t.onBackground },
                    "Surface" to { t: AppTheme, v: Long -> t.copy(surface = v) } to { t: AppTheme -> t.surface },
                    "On Surface" to { t: AppTheme, v: Long -> t.copy(onSurface = v) } to { t: AppTheme -> t.onSurface },

                    "Surface Variant" to { t: AppTheme, v: Long -> t.copy(surfaceVariant = v) } to { t: AppTheme -> t.surfaceVariant },
                    "On Surface Variant" to { t: AppTheme, v: Long -> t.copy(onSurfaceVariant = v) } to { t: AppTheme -> t.onSurfaceVariant },
                    "Outline" to { t: AppTheme, v: Long -> t.copy(outline = v) } to { t: AppTheme -> t.outline }
                )

                items(colors.size) { index ->
                    val pair = colors[index]
                    val getter = pair.second
                    val setter = pair.first.second
                    val label = pair.first.first

                    ColorRow(
                        label = label,
                        colorValue = getter(currentTheme),
                        onColorChange = { newColor ->
                            currentTheme = setter(currentTheme, newColor)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorRow(
    label: String,
    colorValue: Long,
    onColorChange: (Long) -> Unit
) {
    var textValue by remember(colorValue) { mutableStateOf(String.format("#%08X", colorValue)) }

    PanelRow(name = label.uppercase()) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Color(colorValue), RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
        )
        Spacer(modifier = Modifier.width(8.dp))
        TextField(
            value = textValue,
            onValueChange = {
                textValue = it
                if (it.length == 9 && it.startsWith("#")) {
                    try {
                        val parsed = java.lang.Long.parseLong(it.substring(1), 16)
                        onColorChange(parsed)
                    } catch (e: Exception) {
                        // Ignore parse error
                    }
                }
            },
            modifier = Modifier.width(150.dp),
            singleLine = true
        )
    }
}

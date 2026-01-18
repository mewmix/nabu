package com.mewmix.nabu.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mewmix.nabu.utils.SettingsManager
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.BrutalButtonText
import com.mewmix.nabu.ui.brutalist.PanelBox

@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    PanelBox(
        title = "MORE",
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BrutalButton(
                onClick = { onNavigate("Creations") },
                modifier = Modifier.fillMaxWidth()
            ) {
                BrutalButtonText("CREATIONS")
            }
            BrutalButton(
                onClick = { onNavigate("Settings") },
                modifier = Modifier.fillMaxWidth()
            ) {
                BrutalButtonText("SETTINGS")
            }
            BrutalButton(
                onClick = { onNavigate("Models") },
                modifier = Modifier.fillMaxWidth()
            ) {
                BrutalButtonText("MODELS")
            }
            BrutalButton(
                onClick = { onNavigate("Credits") },
                modifier = Modifier.fillMaxWidth()
            ) {
                BrutalButtonText("CREDITS")
            }
            if (SettingsManager.isDebug(context)) {
                BrutalButton(
                    onClick = { onNavigate("DebugLog") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BrutalButtonText("DEBUG LOG")
                }
            }
        }
    }
}

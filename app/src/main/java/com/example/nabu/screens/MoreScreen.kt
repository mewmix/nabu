package com.example.nabu.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.nabu.utils.SettingsManager
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.PanelBox

@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    PanelBox(
        title = "More",
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BrutalButton(
                onClick = { onNavigate("Creations") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Creations")
            }
            BrutalButton(
                onClick = { onNavigate("Settings") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Settings")
            }
            BrutalButton(
                onClick = { onNavigate("Models") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Models")
            }
            BrutalButton(
                onClick = { onNavigate("Credits") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Credits")
            }
            if (SettingsManager.isDebug(context)) {
                BrutalButton(
                    onClick = { onNavigate("DebugLog") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Debug Log")
                }
            }
        }
    }
}

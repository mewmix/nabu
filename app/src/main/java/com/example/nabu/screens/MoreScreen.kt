package com.example.nabu.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.nabu.utils.SettingsManager

@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Button(
            onClick = { onNavigate("Creations") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Creations")
        }
        Button(
            onClick = { onNavigate("Settings") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Settings")
        }
        Button(
            onClick = { onNavigate("Models") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Models")
        }
        Button(
            onClick = { onNavigate("Credits") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Credits")
        }
        if (SettingsManager.isDebug(context)) {
            Button(
                onClick = { onNavigate("DebugLog") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Debug Log")
            }
        }
    }
}

package com.example.kokoro82m.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.kokoro82m.utils.SettingsManager

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var debug by remember { mutableStateOf(SettingsManager.isDebug(context)) }

    Column(modifier = Modifier.padding(16.dp)) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = debug,
                onCheckedChange = {
                    debug = it
                    SettingsManager.setDebug(context, it)
                }
            )
            Text(text = "Debug Mode", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

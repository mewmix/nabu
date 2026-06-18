package com.mewmix.nabu.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.mewmix.nabu.R
import com.mewmix.nabu.utils.SettingsManager
import com.mewmix.nabu.ui.brutalist.BrutalButton
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
            MoreDestination(
                label = "Creations",
                description = "Saved voice renders and generated audio",
                icon = ImageVector.vectorResource(R.drawable.ic_audio_24),
                onClick = { onNavigate("Creations") }
            )
            MoreDestination(
                label = "Settings",
                description = "Theme, runtime, models, API, and debug preferences",
                icon = ImageVector.vectorResource(R.drawable.ic_settings_24),
                onClick = { onNavigate("Settings") }
            )
            MoreDestination(
                label = "Models",
                description = "Download, import, and manage local models",
                icon = ImageVector.vectorResource(R.drawable.instant_mix_24),
                onClick = { onNavigate("Models") }
            )
            MoreDestination(
                label = "Credits",
                description = "Project credits and licenses",
                icon = ImageVector.vectorResource(R.drawable.books_movies_and_music_24),
                onClick = { onNavigate("Credits") }
            )
            if (SettingsManager.isDebug(context)) {
                MoreDestination(
                    label = "Debug Log",
                    description = "Runtime logs and diagnostics",
                    icon = ImageVector.vectorResource(R.drawable.ic_more_24),
                    onClick = { onNavigate("DebugLog") }
                )
            }
        }
    }
}

@Composable
private fun MoreDestination(
    label: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    BrutalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Icon(icon, contentDescription = null)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

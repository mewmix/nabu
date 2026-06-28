package com.mewmix.nabu.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mewmix.nabu.R
import com.mewmix.nabu.utils.SettingsManager
import com.mewmix.nabu.ui.brutalist.BrutalIconButton
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.Top),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                MoreDestination(
                    label = "Creations",
                    icon = ImageVector.vectorResource(R.drawable.ic_music_note_24),
                    onClick = { onNavigate("Creations") }
                )
                MoreDestination(
                    label = "Settings",
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_24),
                    onClick = { onNavigate("Settings") }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                MoreDestination(
                    label = "Models",
                    icon = ImageVector.vectorResource(R.drawable.ic_model_24),
                    onClick = { onNavigate("Models") }
                )
                MoreDestination(
                    label = "Credits",
                    icon = ImageVector.vectorResource(R.drawable.ic_credits_24),
                    onClick = { onNavigate("Credits") }
                )
            }
            if (SettingsManager.isDebug(context)) {
                MoreDestination(
                    label = "Debug Log",
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
    icon: ImageVector,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BrutalIconButton(
            imageVector = icon,
            contentDescription = label,
            onClick = onClick,
            size = 88.dp
        )
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

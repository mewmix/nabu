package com.mewmix.nabu.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mewmix.nabu.utils.DebugLogger
import com.mewmix.nabu.ui.brutalist.PanelBox

@Composable
fun DebugLogScreen() {
    val logs = DebugLogger.getLogs().joinToString("\n")
    PanelBox(
        title = "Debug Log",
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Text(text = logs)
        }
    }
}


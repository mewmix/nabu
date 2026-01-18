package com.mewmix.nabu.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ProgressDialog(message: String, progress: Float?, onDismiss: (() -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        confirmButton = {},
        text = {
            Column {
                Text(message)
                Spacer(modifier = Modifier.height(8.dp))
                if (progress != null) {
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    )
}

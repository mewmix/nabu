package com.mewmix.nabu.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.mewmix.nabu.ui.brutalist.Brutal
import com.mewmix.nabu.ui.brutalist.LabelPlate

private val VersionPlateShape: Shape = RoundedCornerShape(6.dp)

@Composable
fun VersionPlate(
    version: String,
    modifier: Modifier = Modifier,
    label: String = "Version",
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, VersionPlateShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, VersionPlateShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LabelPlate(text = label)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = version,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

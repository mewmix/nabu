package com.mewmix.nabu.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mewmix.nabu.R
import com.mewmix.nabu.ui.design.LocalNabuChrome
import com.mewmix.nabu.ui.design.LocalNabuUiMode
import com.mewmix.nabu.ui.design.NabuUiMode

@Composable
fun ModernBottomBar(
    currentScreen: com.mewmix.nabu.Screen,
    onNavigate: (com.mewmix.nabu.Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val screens = listOf(
        BottomNavItem(
            screen = com.mewmix.nabu.Screen.Basic,
            iconRes = R.drawable.ic_audio_24,
            label = "Audio"
        ),
        BottomNavItem(
            screen = com.mewmix.nabu.Screen.Mixer,
            iconRes = R.drawable.instant_mix_24,
            label = "Mixer"
        ),
        BottomNavItem(
            screen = com.mewmix.nabu.Screen.Book,
            iconRes = R.drawable.ic_book_24,
            label = "Read"
        ),
        BottomNavItem(
            screen = com.mewmix.nabu.Screen.Chat,
            iconRes = R.drawable.ic_chat_24,
            label = "Chat"
        ),
        BottomNavItem(
            screen = com.mewmix.nabu.Screen.More,
            iconRes = R.drawable.ic_settings_24,
            label = "More"
        )
    )

    val currentFeature = currentScreen.asFeature()
    val mode = LocalNabuUiMode.current
    val chrome = LocalNabuChrome.current
    val isModern = mode == NabuUiMode.Modern

    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .then(
                if (isModern) {
                    Modifier
                        .clip(RoundedCornerShape(32.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            RoundedCornerShape(32.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                } else {
                    Modifier
                }
            ),
        horizontalArrangement = Arrangement.spacedBy(if (isModern) 6.dp else 8.dp)
    ) {
        screens.forEach { item ->
            val isActive = (item.screen == currentScreen) || (item.screen == currentFeature)
            BottomNavItem(
                item = item,
                isActive = isActive,
                onClick = { onNavigate(item.screen) },
                modifier = Modifier.weight(1f),
                iconOnly = chrome.navIconOnly
            )
        }
    }
}

@Composable
private fun BottomNavItem(
    item: BottomNavItem,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconOnly: Boolean = false
) {
    val context = LocalContext.current
    val mode = LocalNabuUiMode.current
    val chrome = LocalNabuChrome.current
    val isModern = mode == NabuUiMode.Modern
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    LaunchedEffect(isPressed) {
        if (isPressed &&
            com.mewmix.nabu.utils.SettingsManager.isVibrationsEnabled(context) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED
        ) {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(10)
            }
        }
    }
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(stiffness = 600f),
        label = "scale"
    )

    val iconColor = when {
        isModern && isActive -> MaterialTheme.colorScheme.onPrimary
        isModern -> MaterialTheme.colorScheme.onSurfaceVariant
        isActive -> Color.White
        else -> Color(0xFF888888)
    }
    val textColor = when {
        isModern && isActive -> MaterialTheme.colorScheme.primary
        isModern -> MaterialTheme.colorScheme.onSurfaceVariant
        isActive -> Color.White
        else -> Color(0xFF777777)
    }
    val shape = RoundedCornerShape(chrome.navItemRadius)

    Box(
        modifier = modifier
            .scale(scale)
            .height(if (isModern) 58.dp else 78.dp)
            .clip(shape)
            .then(
                if (isActive) {
                    if (isModern) {
                        Modifier.background(MaterialTheme.colorScheme.primary)
                    } else {
                        Modifier.background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF1A1A1A),
                                    Color(0xFF141414)
                                )
                            )
                        )
                    }
                } else {
                    Modifier.background(color = if (isModern) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f) else Color.Transparent)
                }
            )
            .border(
                width = 1.dp,
                color = if (isModern) {
                    if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)
                } else {
                    if (isActive) Color(0xFF3A3A3A) else Color(0xFF2A2A2A)
                },
                shape = shape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = if (isModern) 8.dp else 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isActive && !isModern) {
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(3.dp)
                        .background(
                            color = Color(0xFF00FF99),
                            shape = RoundedCornerShape(2.dp)
                        )
                )
                Spacer(modifier = Modifier.height(6.dp))
            } else {
                Spacer(modifier = Modifier.height(9.dp))
            }

            Icon(
                imageVector = ImageVector.vectorResource(item.iconRes),
                contentDescription = item.label,
                modifier = Modifier.size(if (isModern) 26.dp else 24.dp),
                tint = iconColor
            )

            if (!iconOnly) {
                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = item.label,
                    color = textColor,
                    fontSize = 11.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}

private data class BottomNavItem(
    val screen: com.mewmix.nabu.Screen,
    val iconRes: Int,
    val label: String
)

private fun com.mewmix.nabu.Screen.asFeature(): com.mewmix.nabu.Screen? = when (this) {
    com.mewmix.nabu.Screen.Basic -> com.mewmix.nabu.Screen.Basic
    com.mewmix.nabu.Screen.Mixer -> com.mewmix.nabu.Screen.Mixer
    com.mewmix.nabu.Screen.Book -> com.mewmix.nabu.Screen.Book
    com.mewmix.nabu.Screen.More,
    com.mewmix.nabu.Screen.Creations,
    com.mewmix.nabu.Screen.Settings,
    com.mewmix.nabu.Screen.Models,
    com.mewmix.nabu.Screen.Credits,
    com.mewmix.nabu.Screen.DebugLog -> com.mewmix.nabu.Screen.More
    com.mewmix.nabu.Screen.Chat -> null
}

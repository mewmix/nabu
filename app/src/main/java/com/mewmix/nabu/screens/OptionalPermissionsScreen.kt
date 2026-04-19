package com.mewmix.nabu.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mewmix.nabu.ui.brutalist.Brutal
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.BrutalButtonText
import com.mewmix.nabu.ui.brutalist.PanelBox

@Composable
fun OptionalPermissionsScreen(
    onContinue: () -> Unit
) {
    PanelBox(
        title = "Optional Permissions",
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OptionalPermissionsSection(
            showContinue = true,
            onContinue = onContinue
        )
    }
}

@Composable
fun OptionalPermissionsSection(
    showContinue: Boolean,
    onContinue: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val notificationPermission = optionalNotificationPermission()
    val mediaPermission = optionalMediaPermission()

    var refreshToken by remember { mutableStateOf(0) }
    val status = remember(refreshToken) { PermissionReviewStatus.from(context, notificationPermission, mediaPermission) }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        refreshToken++
    }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        refreshToken++
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshToken++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "These are optional. Nabu works without them, but some actions and notifications work better if you review them now.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        PermissionRow(
            title = "Notifications",
            description = "Needed for scheduled-action, playback, and reminder notifications.",
            statusLabel = status.notificationStatus,
            actionLabel = if (status.notificationsGranted) "Granted" else "Grant",
            enabled = notificationPermission != null && !status.notificationsGranted,
            onClick = {
                notificationPermission?.let(notificationLauncher::launch)
            }
        )

        PermissionRow(
            title = "Write System Settings",
            description = "Allows direct brightness changes. Without this, Nabu can only open the settings screen.",
            statusLabel = status.writeSettingsStatus,
            actionLabel = if (status.writeSettingsGranted) "Granted" else "Open Settings",
            enabled = !status.writeSettingsGranted,
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        )

        PermissionRow(
            title = "Media Access",
            description = "Lets Nabu read local audio files and generated media more reliably.",
            statusLabel = status.mediaStatus,
            actionLabel = if (status.mediaGranted || mediaPermission == null) "Granted" else "Grant",
            enabled = mediaPermission != null && !status.mediaGranted,
            onClick = {
                mediaPermission?.let(mediaPermissionLauncher::launch)
            }
        )

        Text(
            text = "Missing permissions do not block startup. They only limit the related features.",
            style = MaterialTheme.typography.bodySmall,
            color = Brutal.textDim
        )

        if (showContinue && onContinue != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BrutalButton(
                    onClick = onContinue,
                    modifier = Modifier.weight(1f)
                ) {
                    BrutalButtonText("Skip For Now")
                }
                BrutalButton(
                    onClick = onContinue,
                    modifier = Modifier.weight(1f)
                ) {
                    BrutalButtonText("Continue")
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    statusLabel: String,
    actionLabel: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = statusLabel,
            style = MaterialTheme.typography.bodySmall,
            color = Brutal.textBright
        )
        BrutalButton(
            onClick = onClick,
            enabled = enabled
        ) {
            BrutalButtonText(actionLabel)
        }
    }
}

private data class PermissionReviewStatus(
    val notificationsGranted: Boolean,
    val writeSettingsGranted: Boolean,
    val mediaGranted: Boolean,
    val notificationStatus: String,
    val writeSettingsStatus: String,
    val mediaStatus: String
) {
    companion object {
        fun from(
            context: Context,
            notificationPermission: String?,
            mediaPermission: String?
        ): PermissionReviewStatus {
            val notificationsGranted = notificationPermission == null ||
                ContextCompat.checkSelfPermission(context, notificationPermission) == PackageManager.PERMISSION_GRANTED
            val writeSettingsGranted = Settings.System.canWrite(context)
            val mediaGranted = mediaPermission == null ||
                ContextCompat.checkSelfPermission(context, mediaPermission) == PackageManager.PERMISSION_GRANTED

            return PermissionReviewStatus(
                notificationsGranted = notificationsGranted,
                writeSettingsGranted = writeSettingsGranted,
                mediaGranted = mediaGranted,
                notificationStatus = if (notificationPermission == null) {
                    "Not required on this Android version."
                } else if (notificationsGranted) {
                    "Granted."
                } else {
                    "Not granted."
                },
                writeSettingsStatus = if (writeSettingsGranted) {
                    "Granted."
                } else {
                    "Not granted."
                },
                mediaStatus = if (mediaPermission == null) {
                    "Not required on this Android version."
                } else if (mediaGranted) {
                    "Granted."
                } else {
                    "Not granted."
                }
            )
        }
    }
}

private fun optionalNotificationPermission(): String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        null
    }

private fun optionalMediaPermission(): String? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_AUDIO
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> Manifest.permission.READ_EXTERNAL_STORAGE
    else -> null
}

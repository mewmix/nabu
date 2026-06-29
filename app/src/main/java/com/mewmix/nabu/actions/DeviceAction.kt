package com.mewmix.nabu.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

object DeviceAction {
    data class AppCandidate(
        val label: String,
        val packageName: String
    )

    private data class ResolvedRecipient(
        val phoneNumber: String,
        val displayName: String? = null
    )

    data class ActionResult(
        val message: String,
        val isError: Boolean = false
    )

    internal var canResolveIntent: (Context, Intent) -> Boolean = { context, intent ->
        intent.resolveActivity(context.packageManager) != null
    }

    internal var launchIntentForPackageResolver: (Context, String) -> Intent? = { context, packageName ->
        context.packageManager.getLaunchIntentForPackage(packageName)
    }

    internal var appNameResolver: (Context, String) -> String? = { context, appName ->
        findLaunchableApps(context, appName).firstOrNull()?.packageName
    }

    internal var appCandidateResolver: (Context, String) -> List<AppCandidate> =
        { context, appName -> findLaunchableApps(context, appName) }

    internal var appLabelLoader: (Context, String) -> String? = { context, packageName ->
        loadAppLabel(context, packageName)
    }

    internal var activityLauncher: (Context, Intent) -> Unit = { context, intent ->
        context.startActivity(intent)
    }

    internal var mediaKeyDispatcher: (Context, Int) -> Unit = { context, keyCode ->
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    internal var torchController: (Context, Boolean) -> Unit = { context, enabled ->
        val cameraManager = context.getSystemService(CameraManager::class.java)
            ?: error("Camera service unavailable.")
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: error("No flashlight is available on this device.")
        cameraManager.setTorchMode(cameraId, enabled)
    }

    internal var brightnessSetter: (Context, Int) -> Boolean = { context, level ->
        if (!Settings.System.canWrite(context)) {
            false
        } else {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                (level.coerceIn(0, 100) * 255f / 100f).roundToInt()
            )
        }
    }

    internal fun resetForTesting() {
        canResolveIntent = { context, intent -> intent.resolveActivity(context.packageManager) != null }
        launchIntentForPackageResolver = { context, packageName ->
            context.packageManager.getLaunchIntentForPackage(packageName)
        }
        appNameResolver = { context, appName -> findLaunchableApps(context, appName).firstOrNull()?.packageName }
        appCandidateResolver = { context, appName -> findLaunchableApps(context, appName) }
        appLabelLoader = { context, packageName -> loadAppLabel(context, packageName) }
        activityLauncher = { context, intent -> context.startActivity(intent) }
        mediaKeyDispatcher = { context, keyCode ->
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        torchController = { context, enabled ->
            val cameraManager = context.getSystemService(CameraManager::class.java)
                ?: error("Camera service unavailable.")
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: error("No flashlight is available on this device.")
            cameraManager.setTorchMode(cameraId, enabled)
        }
        brightnessSetter = { context, level ->
            if (!Settings.System.canWrite(context)) {
                false
            } else {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    (level.coerceIn(0, 100) * 255f / 100f).roundToInt()
                )
            }
        }
    }

    fun openApp(context: Context, packageName: String, appName: String): ActionResult {
        val resolvedPackage = when {
            packageName.isNotBlank() -> packageName
            appName.isNotBlank() -> appNameResolver(context, appName)
                ?: return ActionResult("No launchable app found for '$appName'.", true)
            else -> return ActionResult("Missing required parameter: package_name or app_name", true)
        }

        val launchIntent = launchIntentForPackageResolver(context, resolvedPackage)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return ActionResult("No launchable app found for '$resolvedPackage'.", true)

        if (!canResolveIntent(context, launchIntent)) {
            return ActionResult("No launchable app found for '$resolvedPackage'.", true)
        }

        return launchIntent(context, launchIntent) {
            val label = appLabelLoader(context, resolvedPackage) ?: resolvedPackage
            "Opened $label."
        }
    }

    fun findAppCandidates(context: Context, appName: String): List<AppCandidate> =
        appCandidateResolver(context, appName)

    fun openUrl(context: Context, url: String): ActionResult {
        if (url.isBlank()) return ActionResult("Missing required parameter: url", true)
        val normalizedUrl = normalizeUrl(url)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!canResolveIntent(context, intent)) {
            return ActionResult("No app is available to open URLs on this device.", true)
        }
        return launchIntent(context, intent) { "Opened $normalizedUrl." }
    }

    fun sendSms(context: Context, phoneNumber: String, recipient: String, message: String): ActionResult {
        val resolvedRecipient = runCatching {
            resolveRecipient(context, phoneNumber, recipient)
        }.getOrElse {
            return ActionResult(it.message ?: "Failed to resolve SMS recipient.", true)
        } ?: return ActionResult(missingRecipientMessage(), true)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(resolvedRecipient.phoneNumber)}")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!canResolveIntent(context, intent)) {
            return ActionResult("No SMS app is available on this device.", true)
        }
        return launchIntent(context, intent) {
            val recipientLabel = resolvedRecipient.displayName ?: resolvedRecipient.phoneNumber
            if (message.isBlank()) {
                "Opened SMS composer for $recipientLabel."
            } else {
                "Opened SMS composer for $recipientLabel with draft message."
            }
        }
    }

    fun placeCall(context: Context, phoneNumber: String, recipient: String): ActionResult {
        val resolvedRecipient = runCatching {
            resolveRecipient(context, phoneNumber, recipient)
        }.getOrElse {
            return ActionResult(it.message ?: "Failed to resolve call recipient.", true)
        } ?: return ActionResult(missingRecipientMessage(), true)
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(resolvedRecipient.phoneNumber)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!canResolveIntent(context, intent)) {
            return ActionResult("No dialer app is available on this device.", true)
        }
        return launchIntent(context, intent) {
            "Opened dialer for ${resolvedRecipient.displayName ?: resolvedRecipient.phoneNumber}."
        }
    }

    fun setBrightness(context: Context, level: Int): ActionResult {
        if (level !in 0..100) {
            return ActionResult("Invalid brightness level. Use 0-100.", true)
        }
        if (brightnessSetter(context, level)) {
            return ActionResult("Brightness set to $level%.")
        }
        return ActionResult(
            "Nabu cannot change system brightness unless write-settings access is already granted.",
            true
        )
    }

    fun toggleFlashlight(context: Context, enabled: Boolean): ActionResult {
        return runCatching {
            torchController(context, enabled)
            ActionResult(if (enabled) "Flashlight turned on." else "Flashlight turned off.")
        }.getOrElse {
            ActionResult("Failed to control flashlight: ${it.message}", true)
        }
    }

    fun setVolume(context: Context, level: Int, stream: String): ActionResult {
        if (level !in 0..100) {
            return ActionResult("Invalid volume level. Use 0-100.", true)
        }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streamType = streamTypeForName(stream)
            ?: return ActionResult("Invalid stream. Use music, ring, alarm, or notification.", true)
        val maxVolume = audioManager.getStreamMaxVolume(streamType)
        val targetVolume = (maxVolume * (level / 100f)).roundToInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(streamType, targetVolume, 0)
        return ActionResult("Set ${streamName(streamType)} volume to $level%.")
    }

    fun mute(context: Context, muted: Boolean): ActionResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val direction = if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        return ActionResult(if (muted) "Muted media volume." else "Unmuted media volume.")
    }

    fun playMedia(context: Context): ActionResult = sendMediaCommand(context, KeyEvent.KEYCODE_MEDIA_PLAY, "Sent play command.")

    fun pauseMedia(context: Context): ActionResult = sendMediaCommand(context, KeyEvent.KEYCODE_MEDIA_PAUSE, "Sent pause command.")

    fun nextTrack(context: Context): ActionResult = sendMediaCommand(context, KeyEvent.KEYCODE_MEDIA_NEXT, "Sent next-track command.")

    fun createCalendarEvent(
        context: Context,
        title: String,
        startLocal: String,
        endLocal: String,
        location: String,
        description: String
    ): ActionResult {
        if (title.isBlank()) return ActionResult("Missing required parameter: title", true)

        val startEpochMs = parseLocalDateTime(startLocal)
        val endEpochMs = parseLocalDateTime(endLocal)
        if (startLocal.isNotBlank() && startEpochMs == null) {
            return ActionResult("Invalid start_local format. Use yyyy-MM-dd HH:mm", true)
        }
        if (endLocal.isNotBlank() && endEpochMs == null) {
            return ActionResult("Invalid end_local format. Use yyyy-MM-dd HH:mm", true)
        }
        if (startEpochMs != null && endEpochMs != null && endEpochMs < startEpochMs) {
            return ActionResult("end_local must be at or after start_local.", true)
        }

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            if (description.isNotBlank()) putExtra(CalendarContract.Events.DESCRIPTION, description)
            if (location.isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, location)
            if (startEpochMs != null) putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startEpochMs)
            if (endEpochMs != null) putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endEpochMs)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!canResolveIntent(context, intent)) {
            return ActionResult("No calendar app is available on this device.", true)
        }
        return launchIntent(context, intent) { "Opened calendar event composer for '$title'." }
    }

    fun navigateTo(context: Context, destination: String): ActionResult {
        if (destination.isBlank()) return ActionResult("Missing required parameter: destination", true)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!canResolveIntent(context, intent)) {
            return ActionResult("No navigation app is available on this device.", true)
        }
        return launchIntent(context, intent) { "Opened navigation for $destination." }
    }

    fun takePhoto(context: Context): ActionResult {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!canResolveIntent(context, intent)) {
            return ActionResult("No camera app is available for photos.", true)
        }
        return launchIntent(context, intent) { "Opened camera for photo capture." }
    }

    fun recordVideo(context: Context): ActionResult {
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!canResolveIntent(context, intent)) {
            return ActionResult("No camera app is available for video capture.", true)
        }
        return launchIntent(context, intent) { "Opened camera for video recording." }
    }

    fun toggleWifi(context: Context, enabled: Boolean?): ActionResult {
        val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!canResolveIntent(context, intent)) {
            return ActionResult("No Wi-Fi settings panel is available on this device.", true)
        }
        val requestedState = enabled?.let { if (it) "on" else "off" } ?: "toggled"
        return launchIntent(context, intent) {
            "Opened Wi-Fi controls. Android restricts direct Wi-Fi toggles here; requested state: $requestedState."
        }
    }

    fun toggleBluetooth(context: Context, enabled: Boolean?): ActionResult {
        val panelIntent = Intent("android.settings.panel.action.BLUETOOTH").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val settingsIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canResolveIntent(context, panelIntent)) {
            panelIntent
        } else {
            settingsIntent
        }
        if (!canResolveIntent(context, intent)) {
            return ActionResult("No Bluetooth settings panel is available on this device.", true)
        }
        val requestedState = enabled?.let { if (it) "on" else "off" } ?: "toggled"
        return launchIntent(context, intent) {
            "Opened Bluetooth controls. Android restricts direct Bluetooth toggles here; requested state: $requestedState."
        }
    }

    fun shareText(context: Context, text: String, subject: String): ActionResult {
        if (text.isBlank()) return ActionResult("Missing required parameter: text", true)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (subject.isNotBlank()) {
                putExtra(Intent.EXTRA_SUBJECT, subject)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!canResolveIntent(context, intent)) {
            return ActionResult("No share targets are available on this device.", true)
        }
        val chooser = Intent.createChooser(intent, "Share with").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntent(context, chooser) { "Opened share sheet." }
    }

    private fun sendMediaCommand(context: Context, keyCode: Int, successMessage: String): ActionResult {
        return runCatching {
            mediaKeyDispatcher(context, keyCode)
            ActionResult(successMessage)
        }.getOrElse {
            ActionResult("Failed to send media command: ${it.message}", true)
        }
    }

    private fun resolveRecipient(context: Context, phoneNumber: String, recipient: String): ResolvedRecipient? {
        if (phoneNumber.isNotBlank()) {
            return ResolvedRecipient(phoneNumber = phoneNumber)
        }
        if (recipient.isBlank()) {
            return null
        }
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException(
                "Contacts permission is required to resolve '$recipient'."
            )
        }

        findPhoneNumberForRecipient(context, recipient)?.let { return it }
        throw IllegalStateException("No contact with a phone number matched '$recipient'.")
    }

    private fun missingRecipientMessage(): String =
        "Provide either phone_number or a resolvable recipient contact."

    private fun findPhoneNumberForRecipient(context: Context, recipient: String): ResolvedRecipient? {
        val resolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        fun query(selection: String, args: Array<String>): ResolvedRecipient? {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                args,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (nameIndex == -1 || numberIndex == -1) return null
                if (!cursor.moveToFirst()) return null
                val displayName = cursor.getString(nameIndex).orEmpty().trim()
                val phoneNumber = cursor.getString(numberIndex).orEmpty().trim()
                if (phoneNumber.isBlank()) return null
                return ResolvedRecipient(phoneNumber = phoneNumber, displayName = displayName.ifBlank { null })
            }
            return null
        }

        val normalizedRecipient = recipient.trim()
        return query(
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ? COLLATE NOCASE",
            arrayOf(normalizedRecipient)
        ) ?: query(
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? COLLATE NOCASE",
            arrayOf("$normalizedRecipient%")
        ) ?: query(
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? COLLATE NOCASE",
            arrayOf("%$normalizedRecipient%")
        )
    }

    private fun launchIntent(context: Context, intent: Intent, successMessage: () -> String): ActionResult {
        return runCatching {
            activityLauncher(context, intent)
            ActionResult(successMessage())
        }.getOrElse {
            ActionResult("Failed to launch device action: ${it.message}", true)
        }
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.contains("://")) trimmed else "https://$trimmed"
    }

    private fun parseLocalDateTime(value: String): Long? {
        if (value.isBlank()) return null
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        return runCatching {
            LocalDateTime.parse(value, formatter)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    private fun streamTypeForName(name: String): Int? = when (name.trim().lowercase()) {
        "", "music", "media" -> AudioManager.STREAM_MUSIC
        "ring", "ringer" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification", "notifications" -> AudioManager.STREAM_NOTIFICATION
        else -> null
    }

    private fun streamName(streamType: Int): String = when (streamType) {
        AudioManager.STREAM_RING -> "ring"
        AudioManager.STREAM_ALARM -> "alarm"
        AudioManager.STREAM_NOTIFICATION -> "notification"
        else -> "music"
    }

    private fun findLaunchableApps(context: Context, appName: String): List<AppCandidate> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val query = normalizeAppName(appName)
        if (query.isBlank()) return emptyList()

        val installedApps = packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                val label = resolveInfo.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                if (label.isBlank()) return@mapNotNull null
                AppCandidate(label, packageName)
            }
        return rankAppCandidates(query, installedApps)
    }

    internal fun rankAppCandidates(appName: String, candidates: List<AppCandidate>): List<AppCandidate> {
        val query = normalizeAppName(appName)
        if (query.isBlank()) return emptyList()
        return candidates
            .mapNotNull { candidate ->
                val score = appMatchScore(
                    query,
                    normalizeAppName(candidate.label),
                    candidate.packageName.lowercase()
                )
                if (score <= 0) null else score to candidate
            }
            .distinctBy { it.second.packageName }
            .sortedWith(compareByDescending<Pair<Int, AppCandidate>> { it.first }
                .thenBy { it.second.label.lowercase() })
            .take(8)
            .map { it.second }
    }

    private fun normalizeAppName(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun appMatchScore(query: String, label: String, packageName: String): Int {
        if (label == query) return 1_000
        if (label.startsWith("$query ")) return 900 - (label.length - query.length)
        if (label.split(' ').any { it == query }) return 850 - (label.length - query.length).coerceAtLeast(0)
        if (label.contains(query)) return 750 - (label.length - query.length).coerceAtLeast(0)
        if (query.contains(label) && label.length >= 3) return 650 - (query.length - label.length)
        if (packageName.contains(query.replace(" ", ""))) return 500

        val distance = levenshteinDistance(query, label)
        val tolerance = (query.length / 3).coerceIn(1, 3)
        return if (distance <= tolerance) 600 - distance * 25 else 0
    }

    private fun levenshteinDistance(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        var previous = IntArray(right.length + 1) { it }
        for (leftIndex in left.indices) {
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            for (rightIndex in right.indices) {
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + if (left[leftIndex] == right[rightIndex]) 0 else 1
                )
            }
            previous = current
        }
        return previous[right.length]
    }

    private fun loadAppLabel(context: Context, packageName: String): String? {
        return runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
    }
}

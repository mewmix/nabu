package com.example.nabu

import NabuTheme
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.nabu.screens.BookScreen
import com.example.nabu.screens.CreationsScreen
import com.example.nabu.screens.SettingsScreen
import com.example.nabu.screens.MixerScreen
import com.example.nabu.screens.MoreScreen
import com.example.nabu.screens.ModelsScreen
import com.example.nabu.screens.DebugLogScreen
import com.example.nabu.screens.CreditsConstellationScreen
import com.example.kokoro.galleryport.PerfHud
import ai.onnxruntime.OrtSession
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import com.mewmix.nabu.ui.brutalist.PanelBox
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.BrutalSlider
import com.mewmix.nabu.ui.brutalist.PanelRow
import com.mewmix.nabu.ui.brutalist.Brutal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nabu.data.UserPreferencesRepository
import com.example.nabu.utils.MainViewModel
import com.example.nabu.utils.PhonemeConverter
import com.example.nabu.utils.StyleLoader
import com.example.nabu.utils.createAudio
import com.example.nabu.utils.createKittenAudioFromStyleVector
import com.example.nabu.utils.BenchmarkManager
import com.example.nabu.utils.KittenPhonemizer
import com.example.nabu.utils.playAudio
import com.example.nabu.utils.saveAudio
import com.example.nabu.utils.SettingsManager
import com.example.nabu.utils.TtsEngine
import com.example.nabu.utils.DebugLogger
import com.example.nabu.utils.OnnxRuntimeManager
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val EXTRA_START_SCREEN = "start_screen"
private const val PLAYBACK_CHANNEL_ID = "playback_channel"
private const val PLAYBACK_NOTIFICATION_ID = 1

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}

private fun showPlaybackNotification(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }

    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            PLAYBACK_CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    val notification = NotificationCompat.Builder(context, PLAYBACK_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("Playback")
        .setContentText("Audio playback in progress")
        .setOngoing(true)
        .build()

    manager.notify(PLAYBACK_NOTIFICATION_ID, notification)
}

class MainActivity : ComponentActivity() {
    private lateinit var phonemeConverter: PhonemeConverter
    private val scope = MainScope()
    private lateinit var userPreferencesRepository: UserPreferencesRepository
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showPlaybackNotification(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        DebugLogger.initialize(this)
        enableEdgeToEdge()
        userPreferencesRepository = UserPreferencesRepository(this)

        val startScreen = screenFromString(intent.getStringExtra(EXTRA_START_SCREEN))

        setContent {
            NabuTheme {
                LaunchedEffect(Unit) {
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                }

                val viewModel: MainViewModel = viewModel { MainViewModel(this@MainActivity) }

                MainScreen(
                    session = viewModel.getSession(),
                    phonemeConverter = phonemeConverter,
                    onGenerateAudio = { text, style, speed, shouldSave, useRaw, onComplete ->
                        maybeRequestNotificationPermission()
                        generateAudio(
                            phonemeConverter,
                            text,
                            style,
                            speed,
                            this@MainActivity,
                            scope,
                            shouldSave,
                            useRaw,
                            onComplete
                        )
                    },
                    userPreferencesRepository = userPreferencesRepository,
                    initialScreen = startScreen
                )

                if (SettingsManager.isBenchmark(this@MainActivity)) {
                    PerfHud.Overlay()
                }
            }
        }

        phonemeConverter = PhonemeConverter(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private fun generateAudio(
    phonemeConverter: PhonemeConverter,
    text: String,
    style: String,
    speed: Float,
    context: Context,
    scope: CoroutineScope,
    shouldSave: Boolean,
    useRaw: Boolean,
    onComplete: () -> Unit
) {
    val session = OnnxRuntimeManager.getSession()
    scope.launch(Dispatchers.IO) {
        try {
            val engine = SettingsManager.getTtsEngine(context)
            val ttsStart = SystemClock.elapsedRealtime()
            val (audioData, sampleRate) = if (engine == TtsEngine.KITTEN) {
                val (displayStr, tokens) = if (useRaw) {
                    KittenPhonemizer.encodeText(text)
                } else {
                    KittenPhonemizer.phonemize(text)
                }
                val logLabel = if (useRaw) "Raw text" else "Phonemes"
                DebugLogger.log("$logLabel: $displayStr")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "$logLabel: $displayStr", Toast.LENGTH_LONG).show()
                }
                val loader = StyleLoader(context)
                val voiceArray = loader.getStyleArray(style)
                PerfHud.record("ONNX synth") {
                    createKittenAudioFromStyleVector(
                        tokens = tokens,
                        voice = voiceArray,
                        speed = speed,
                        session = session
                    )
                }
            } else {
                val phonemes = phonemeConverter.phonemize(text)
                DebugLogger.log("Phonemes: $phonemes")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Phonemes: $phonemes", Toast.LENGTH_LONG).show()
                }
                PerfHud.record("ONNX synth") {
                    createAudio(
                        voice = style,
                        phonemes = phonemes,
                        speed = speed,
                        context = context,
                        session = session
                    )
                }
            }
            val genMs = SystemClock.elapsedRealtime() - ttsStart
            if (SettingsManager.isBenchmark(context)) {
                val audioMs = audioData.size * 1000L / sampleRate
                BenchmarkManager.recordTts(engine, genMs, audioMs)
                BenchmarkManager.profileSystem(context)
            }

            playAudio(
                audioData,
                sampleRate,
                scope,
                onComplete = onComplete
            )

            showPlaybackNotification(context)

            if (shouldSave) {
                saveAudio(audioData, context, style, sampleRate)
            }
        } catch (e: Exception) {
            DebugLogger.log("Error: ${e.message}")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }
}

private fun screenFromString(name: String?): Screen = when (name) {
    "Basic" -> Screen.Basic
    "Mixer" -> Screen.Mixer
    "Book" -> Screen.Book
    "Chat" -> Screen.Chat
    "More" -> Screen.More
    "Creations" -> Screen.Creations
    "Settings" -> Screen.Settings
    "Models" -> Screen.Models
    "DebugLog" -> Screen.DebugLog
    else -> Screen.Basic
}

sealed class Screen {
    object Basic : Screen()
    object Mixer : Screen()
    object Book : Screen()
    object Chat : Screen() // New screen state for ChatActivity if needed for selection
    object More : Screen()
    object Creations : Screen()
    object Settings : Screen()
    object Models : Screen()
    object DebugLog : Screen()
    object Credits : Screen()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    session: OrtSession,
    phonemeConverter: PhonemeConverter,
    onGenerateAudio: (String, String, Float, Boolean, Boolean, () -> Unit) -> Unit,
    userPreferencesRepository: UserPreferencesRepository,
    initialScreen: Screen = Screen.Basic
) {
    var currentScreen by remember { mutableStateOf(initialScreen) }
    val context = LocalContext.current
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BrutalButton(
                    onClick = { currentScreen = Screen.Basic },
                    modifier = Modifier.weight(1f),
                    enabled = currentScreen != Screen.Basic
                ) { Text("BASIC") }
                BrutalButton(
                    onClick = { currentScreen = Screen.Mixer },
                    modifier = Modifier.weight(1f),
                    enabled = currentScreen != Screen.Mixer
                ) { Text("MIXER") }
                BrutalButton(
                    onClick = { currentScreen = Screen.Book },
                    modifier = Modifier.weight(1f),
                    enabled = currentScreen != Screen.Book
                ) { Text("BOOK") }
                BrutalButton(
                    onClick = {
                        context.startActivity(Intent(context, ChatActivity::class.java))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("CHAT") }
                BrutalButton(
                    onClick = { currentScreen = Screen.More },
                    modifier = Modifier.weight(1f),
                    enabled = currentScreen != Screen.More
                ) { Text("MORE") }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (currentScreen) {
                Screen.Basic -> BasicScreen(onGenerateAudio = onGenerateAudio)
                Screen.Mixer -> MixerScreen(
                    phonemeConverter = phonemeConverter,
                    styleLoader = StyleLoader(context)
                )
                Screen.Book -> BookScreen(
                    session = session,
                    phonemeConverter = phonemeConverter
                )
                Screen.Chat -> {
                    // No-op, handled by onClick which starts ChatActivity
                    // This case is primarily for the 'selected' state of the NavigationBarItem
                }
                Screen.More -> MoreScreen { screen ->
                    currentScreen = when (screen) {
                        "Creations" -> Screen.Creations
                        "Settings" -> Screen.Settings
                        "Models" -> Screen.Models
                        "Credits" -> Screen.Credits
                        "DebugLog" -> Screen.DebugLog
                        else -> currentScreen
                    }
                }
                Screen.Creations -> CreationsScreen()
                Screen.Settings -> SettingsScreen()
                Screen.Models -> ModelsScreen(userPreferencesRepository)
                Screen.Credits -> CreditsConstellationScreen()
                Screen.DebugLog -> DebugLogScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BasicScreen(
    onGenerateAudio: (String, String, Float, Boolean, Boolean, () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var engine by remember { mutableStateOf(SettingsManager.getTtsEngine(context)) }
    val styleLoader = remember(engine) { StyleLoader(context) }
    val names = styleLoader.names.sorted()

    var text by remember { mutableStateOf("Made with love and brought to you from outer space.") }
    var style by remember(engine) {
        mutableStateOf(
            SettingsManager.getStyle(context).takeIf { it in names }
                ?: names.firstOrNull().orEmpty()
        )
    }
    var speed by remember { mutableFloatStateOf(SettingsManager.getSpeed(context)) }
    var isProcessing by remember { mutableStateOf(false) }
    var shouldSaveFile by remember { mutableStateOf(false) }
    var useRawText by remember { mutableStateOf(false) }

    LaunchedEffect(engine) {
        withContext(Dispatchers.IO) {
            OnnxRuntimeManager.initialize(context.applicationContext)
        }
        SettingsManager.setStyle(context, style)
    }

    var expanded by remember { mutableStateOf(false) }
    var engineExpanded by remember { mutableStateOf(false) }

    PanelBox(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextField(
            value = text,
            minLines = 3,
            maxLines = 12,
            onValueChange = { text = it },
            label = { Text("TEXT TO SPEAK") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions.Default.copy(
                keyboardType = KeyboardType.Text
            )
        )

        ExposedDropdownMenuBox(
            expanded = engineExpanded,
            onExpandedChange = { engineExpanded = !engineExpanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            TextField(
                value = engine.name,
                onValueChange = {},
                label = { Text("TTS ENGINE") },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                readOnly = true,
                trailingIcon = {
                    Text(if (engineExpanded) "▲" else "▼", color = Brutal.textBright)
                }
            )

            DropdownMenu(
                expanded = engineExpanded,
                onDismissRequest = { engineExpanded = false }
            ) {
                TtsEngine.values().forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.name.uppercase()) },
                        onClick = {
                            engine = option
                            SettingsManager.setTtsEngine(context, option)
                            engineExpanded = false
                        }
                    )
                }
            }
        }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            TextField(
                value = style,
                onValueChange = {
                    style = it
                    SettingsManager.setStyle(context, it)
                },
                label = { Text("STYLE") },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                readOnly = true,
                trailingIcon = {
                    Text(if (expanded) "▲" else "▼", color = Brutal.textBright)
                }
            )

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                names.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name.uppercase()) },
                        onClick = {
                            style = name
                            SettingsManager.setStyle(context, name)
                            expanded = false
                        }
                    )
                }
            }
        }

        if (engine == TtsEngine.KITTEN) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("RAW TEXT INPUT")
                Spacer(modifier = Modifier.width(8.dp))
                Switch(checked = useRawText, onCheckedChange = { useRawText = it })
            }
        }

        PanelRow(name = "SPEED") {
            BrutalSlider(
                value = speed,
                onValueChange = {
                    speed = it
                    SettingsManager.setSpeed(context, it)
                },
                range = 0.5f..2.0f,
                modifier = Modifier.weight(1f)
            )
            Text("%.2f".format(speed))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            BrutalButton(
                onClick = {
                    shouldSaveFile = false
                    isProcessing = true
                    onGenerateAudio(text, style, speed, shouldSaveFile, useRawText) {
                        isProcessing = false
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                enabled = !isProcessing
            ) {
                Text(if (isProcessing) "GPU PROCESSING..." else "PLAY")
            }

            Spacer(modifier = Modifier.width(12.dp))

            BrutalButton(
                onClick = {
                    shouldSaveFile = true
                    isProcessing = true
                    onGenerateAudio(text, style, speed, shouldSaveFile, useRawText) {
                        isProcessing = false
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                enabled = !isProcessing
            ) {
                Text(if (isProcessing) "GPU PROCESSING..." else "PLAY & SAVE")
            }
        }
    }
}
}

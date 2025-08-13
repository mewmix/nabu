package com.example.kokoro82m

import NabuTheme
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.kokoro82m.screens.BookScreen
import com.example.kokoro82m.screens.CreationsScreen
import com.example.kokoro82m.screens.SettingsScreen
import com.example.kokoro82m.screens.MixerScreen
import com.example.kokoro82m.screens.MoreScreen
import com.example.kokoro82m.screens.ModelsScreen
import com.example.kokoro82m.screens.DebugLogScreen
import com.example.kokoro82m.screens.CreditsConstellationScreen
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kokoro82m.data.UserPreferencesRepository
import com.example.kokoro82m.utils.MainViewModel
import com.example.kokoro82m.utils.PhonemeConverter
import com.example.kokoro82m.utils.StyleLoader
import com.example.kokoro82m.utils.createAudio
import com.example.kokoro82m.utils.createKittenAudioFromStyleVector
import com.example.kokoro82m.utils.KittenPhonemizer
import com.example.kokoro82m.utils.playAudio
import com.example.kokoro82m.utils.saveAudio
import com.example.kokoro82m.utils.SettingsManager
import com.example.kokoro82m.utils.TtsEngine
import com.example.kokoro82m.utils.DebugLogger
import com.example.kokoro82m.utils.OnnxRuntimeManager
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
                    onGenerateAudio = { text, style, speed, shouldSave, onComplete ->
                        maybeRequestNotificationPermission()
                        generateAudio(
                            phonemeConverter,
                            text,
                            style,
                            speed,
                            this@MainActivity,
                            scope,
                            shouldSave,
                            onComplete
                        )
                    },
                    userPreferencesRepository = userPreferencesRepository,
                    initialScreen = startScreen
                )
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
    onComplete: () -> Unit
) {
    val session = OnnxRuntimeManager.getSession()
    scope.launch(Dispatchers.IO) {
        try {
            val engine = SettingsManager.getTtsEngine(context)
            val (audioData, sampleRate) = if (engine == TtsEngine.KITTEN) {
                val (phonemeStr, tokens) = KittenPhonemizer.phonemize(text)
                DebugLogger.log("Phonemes: $phonemeStr")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Phonemes: $phonemeStr", Toast.LENGTH_LONG).show()
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
    "ChatTts" -> Screen.ChatTts
    "More" -> Screen.More
    "Creations" -> Screen.Creations
    "Settings" -> Screen.Settings
    "Models" -> Screen.Models
    "DebugLog" -> Screen.DebugLog
    else -> Screen.Basic
}

sealed class Screen(val title: String) {
    object Basic : Screen("Basic TTS")
    object Mixer : Screen("Mixer")
    object Book : Screen("Audio Book")
    object ChatTts : Screen("Chat TTS") // New screen state for ChatTtsActivity if needed for selection
    object More : Screen("More")
    object Creations : Screen("Creations")
    object Settings : Screen("Settings")
    object Models : Screen("Models")
    object DebugLog : Screen("Debug Log")
    object Credits : Screen("Credits")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    session: OrtSession,
    phonemeConverter: PhonemeConverter,
    onGenerateAudio: (String, String, Float, Boolean, () -> Unit) -> Unit,
    userPreferencesRepository: UserPreferencesRepository,
    initialScreen: Screen = Screen.Basic
) {
    var currentScreen by remember { mutableStateOf(initialScreen) }
    val context = LocalContext.current
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(currentScreen.title) }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Basic") },
                    label = { Text("Basic") },
                    selected = currentScreen == Screen.Basic,
                    onClick = { currentScreen = Screen.Basic }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.VolumeUp, contentDescription = "Mixer") },
                    label = { Text("Mixer") },
                    selected = currentScreen == Screen.Mixer,
                    onClick = { currentScreen = Screen.Mixer }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.MenuBook, contentDescription = "Book") },
                    label = { Text("Book") },
                    selected = currentScreen == Screen.Book,
                    onClick = { currentScreen = Screen.Book }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.RecordVoiceOver, contentDescription = "Chat TTS") }, // Example new icon
                    label = { Text("Chat TTS") },
                    selected = currentScreen == Screen.ChatTts, // For selection state
                    onClick = {
                        context.startActivity(Intent(context, ChatTtsActivity::class.java))
                        // currentScreen = Screen.ChatTts // If you want to try and reflect selection
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.MoreHoriz, contentDescription = "More") },
                    label = { Text("More") },
                    selected = currentScreen == Screen.More,
                    onClick = { currentScreen = Screen.More }
                )
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
                Screen.ChatTts -> {
                    // No-op, handled by onClick which starts ChatTtsActivity
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
    onGenerateAudio: (String, String, Float, Boolean, () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var engine by remember { mutableStateOf(SettingsManager.getTtsEngine(context)) }
    val styleLoader = remember(engine) { StyleLoader(context) }
    val names = styleLoader.names.sorted()

    var text by remember { mutableStateOf("This is her warm heart, her warmest kokoro, unwavering love and comfort.") }
    var style by remember(engine) {
        mutableStateOf(
            SettingsManager.getStyle(context).takeIf { it in names }
                ?: names.firstOrNull().orEmpty()
        )
    }
    var speed by remember { mutableFloatStateOf(SettingsManager.getSpeed(context)) }
    var isProcessing by remember { mutableStateOf(false) }
    var shouldSaveFile by remember { mutableStateOf(false) }

    LaunchedEffect(engine) {
        withContext(Dispatchers.IO) {
            OnnxRuntimeManager.initialize(context.applicationContext)
        }
        SettingsManager.setStyle(context, style)
    }

    var expanded by remember { mutableStateOf(false) }
    var engineExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextField(
            value = text,
            minLines = 3,
            maxLines = 12,
            onValueChange = { text = it },
            label = { Text("Text to speak") },
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
                label = { Text("TTS Engine") },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                readOnly = true,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = engineExpanded)
                }
            )

            DropdownMenu(
                expanded = engineExpanded,
                onDismissRequest = { engineExpanded = false }
            ) {
                TtsEngine.values().forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.name) },
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
                label = { Text("Style") },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                readOnly = true,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            )

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                names.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            style = name
                            SettingsManager.setStyle(context, name)
                            expanded = false
                        }
                    )
                }
            }
        }

        Text("Speed: $speed")
        Slider(
            value = speed,
            onValueChange = {
                speed = it
                SettingsManager.setSpeed(context, it)
            },
            valueRange = 0.5f..2.0f,
            steps = 5,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Button(
                onClick = {
                    shouldSaveFile = false
                    isProcessing = true
                    onGenerateAudio(text, style, speed, shouldSaveFile) {
                        isProcessing = false
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                enabled = !isProcessing
            ) {
                Text(if (isProcessing) "GPU Processing..." else "Play")
            }

            Spacer(modifier = Modifier.width(12.dp))

            Button(
                onClick = {
                    shouldSaveFile = true
                    isProcessing = true
                    onGenerateAudio(text, style, speed, shouldSaveFile) {
                        isProcessing = false
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                enabled = !isProcessing
            ) {
                Text(if (isProcessing) "GPU Processing..." else "Play & Save")
            }
        }

        
    }
}


//@Preview(showBackground = true)
//@Composable
//fun ScreenPreview() {
//    MainScreen(
//        session = TODO(),
//        onGenerateAudio = { _, _, _, _, _ -> }
//    )
//}

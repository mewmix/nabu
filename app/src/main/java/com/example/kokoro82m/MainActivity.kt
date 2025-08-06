package com.example.kokoro82m

import NabuTheme
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.kokoro82m.screens.BookScreen
import com.example.kokoro82m.screens.CreationsScreen
import com.example.kokoro82m.screens.SettingsScreen
import com.example.kokoro82m.screens.MixerScreen
import com.example.kokoro82m.screens.MoreScreen
import com.example.kokoro82m.screens.ModelsScreen
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
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
import androidx.compose.material3.Switch
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
import com.example.kokoro82m.screens.Acknowledgements
import com.example.kokoro82m.utils.MainViewModel
import com.example.kokoro82m.utils.PhonemeConverter
import com.example.kokoro82m.utils.StyleLoader
import com.example.kokoro82m.utils.createAudio
import com.example.kokoro82m.utils.createKittenAudioFromStyleVector
import com.example.kokoro82m.utils.playAudio
import com.example.kokoro82m.utils.saveAudio
import com.example.kokoro82m.utils.SettingsManager
import com.example.kokoro82m.utils.TtsEngine
import com.example.kokoro82m.utils.DebugLogger
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
                val session = remember { viewModel.getSession() }

                MainScreen(
                    session = session,
                    phonemeConverter = phonemeConverter,
                    onGenerateAudio = { text, style, speed, shouldSave, onComplete ->
                        maybeRequestNotificationPermission()
                        generateAudio(
                            session,
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
    session: OrtSession,
    phonemeConverter: PhonemeConverter,
    text: String,
    style: String,
    speed: Float,
    context: Context,
    scope: CoroutineScope,
    shouldSave: Boolean,
    onComplete: () -> Unit
) {
    scope.launch(Dispatchers.IO) {
        try {
            val phonemes = phonemeConverter.phonemize(text)
            DebugLogger.log("Phonemes: $phonemes")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Phonemes: $phonemes", Toast.LENGTH_LONG).show()
            }


            val engine = SettingsManager.getTtsEngine(context)
            val (audioData, sampleRate) = PerfHud.record("ONNX synth") {
                if (engine == TtsEngine.KITTEN) {
                    val loader = StyleLoader(context)
                    val voiceArray = loader.getStyleArray(style)
                    createKittenAudioFromStyleVector(
                        phonemes = phonemes,
                        voice = voiceArray,
                        speed = speed,
                        session = session
                    )
                } else {
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
                audioData, scope,
                onComplete = onComplete
            )

            showPlaybackNotification(context)

            if (shouldSave) {
                saveAudio(audioData, context, style)
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
    "ChatTts" -> Screen.ChatTts
    "More" -> Screen.More
    "Creations" -> Screen.Creations
    "Settings" -> Screen.Settings
    "About" -> Screen.About
    "Models" -> Screen.Models
    else -> Screen.Basic
}

sealed class Screen(val title: String) {
    object Basic : Screen("Basic TTS")
    object Mixer : Screen("Mixer")
    object Book : Screen("Audio Book")
    object Chat : Screen("Chat") // Existing Chat (for ChatActivity)
    object ChatTts : Screen("Chat TTS") // New screen state for ChatTtsActivity if needed for selection
    object More : Screen("More")
    object Creations : Screen("Creations")
    object Settings : Screen("Settings")
    object About : Screen("About this app")
    object Models : Screen("Models")
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
    var hudEnabled by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel { MainViewModel(context) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(currentScreen.title) },
                actions = {
                    Switch(checked = hudEnabled, onCheckedChange = { checked -> hudEnabled = checked })
                }
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
                    icon = { Icon(Icons.Default.Info, contentDescription = "Mixer") },
                    label = { Text("Mixer") },
                    selected = currentScreen == Screen.Mixer,
                    onClick = { currentScreen = Screen.Mixer }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Info, contentDescription = "Book") },
                    label = { Text("Book") },
                    selected = currentScreen == Screen.Book,
                    onClick = { currentScreen = Screen.Book }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Info, contentDescription = "Chat") }, // Existing Chat
                    label = { Text("Chat") },
                    selected = currentScreen == Screen.Chat, // This might not highlight correctly if always launching an activity
                    onClick = {
                        context.startActivity(Intent(context, ChatActivity::class.java))
                        // Optionally set currentScreen if you want to try and reflect selection,
                        // but launching a new activity makes this tricky.
                        // currentScreen = Screen.Chat
                    }
                )
                // START --- New NavigationBarItem for ChatTtsActivity ---
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.RecordVoiceOver, contentDescription = "Chat TTS") }, // Example new icon
                    label = { Text("Chat TTS") },
                    selected = currentScreen == Screen.ChatTts, // For selection state
                    onClick = {
                        context.startActivity(Intent(context, ChatTtsActivity::class.java))
                        // currentScreen = Screen.ChatTts // If you want to try and reflect selection
                    }
                )
                // END --- New NavigationBarItem for ChatTtsActivity ---
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Info, contentDescription = "About") },
                    label = { Text("About") },
                    selected = currentScreen == Screen.About,
                    onClick = { currentScreen = Screen.About }
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
                Screen.Basic -> BasicScreen(session = session, onGenerateAudio = onGenerateAudio, viewModel = viewModel)
                Screen.Mixer -> MixerScreen(
                    session = session,
                    phonemeConverter = phonemeConverter,
                    styleLoader = StyleLoader(context)
                )
                Screen.Book -> BookScreen(
                    session = session,
                    phonemeConverter = phonemeConverter
                )
                Screen.Chat -> {
                    // No-op, handled by onClick which starts ChatActivity
                }
                Screen.ChatTts -> {
                    // No-op, handled by onClick which starts ChatTtsActivity
                    // This case is primarily for the 'selected' state of the NavigationBarItem
                }
                Screen.More -> MoreScreen { screen ->
                    currentScreen = when (screen) {
                        "Creations" -> Screen.Creations
                        "Settings" -> Screen.Settings
                        "Models" -> Screen.Models
                        else -> currentScreen
                    }
                }
                Screen.Creations -> CreationsScreen()
                Screen.Settings -> SettingsScreen()
                Screen.About -> AboutScreen()
                Screen.Models -> ModelsScreen(userPreferencesRepository)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BasicScreen(
    session: OrtSession,
    onGenerateAudio: (String, String, Float, Boolean, () -> Unit) -> Unit,
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val styleLoader = remember { StyleLoader(context) }
    val names = styleLoader.names.sorted()

    var text by remember { mutableStateOf("This is her warm heart, her warmest kokoro, unwavering love and comfort.") }
    var style by remember {
        mutableStateOf(
            SettingsManager.getStyle(context).takeIf { it in names }
                ?: names.firstOrNull().orEmpty()
        )
    }
    var speed by remember { mutableFloatStateOf(SettingsManager.getSpeed(context)) }
    var isProcessing by remember { mutableStateOf(false) }
    var shouldSaveFile by remember { mutableStateOf(false) }

    val modelManager = remember { com.example.kokoro82m.data.ModelManager(context) }
    val models = remember { modelManager.models.filter { it.isDownloaded } }
    var selectedModel by remember { mutableStateOf(models.firstOrNull()) }

    LaunchedEffect(selectedModel) {
        selectedModel?.let {
            val modelFile = java.io.File(context.filesDir, "models/${it.id}.task")
            viewModel.reinitializeSession(modelFile.absolutePath)
        }
    }

    var expanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

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
            expanded = modelExpanded,
            onExpandedChange = { modelExpanded = !modelExpanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            TextField(
                value = selectedModel?.name ?: "Select a model",
                onValueChange = { },
                label = { Text("Model") },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                readOnly = true,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded)
                }
            )

            ExposedDropdownMenu(
                expanded = modelExpanded,
                onDismissRequest = { modelExpanded = false }
            ) {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.name) },
                        onClick = {
                            selectedModel = model
                            modelExpanded = false
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

            ExposedDropdownMenu(
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

@Composable
fun AboutScreen() {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
    ) {
        Acknowledgements()
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

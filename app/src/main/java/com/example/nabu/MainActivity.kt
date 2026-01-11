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
import com.example.nabu.speech.SpeechForegroundService
import com.example.nabu.speech.SpeechState
import com.example.kokoro.galleryport.PerfHud
import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.nabu.data.UserPreferencesRepository
import com.example.nabu.utils.PhonemeConverter
import com.example.nabu.utils.StyleLoader
import com.example.nabu.utils.createAudio
import com.example.nabu.utils.BenchmarkManager
import com.example.nabu.utils.playAudio
import com.example.nabu.utils.saveAudio
import com.example.nabu.utils.SettingsManager
import com.example.nabu.utils.DebugLogger
import com.example.nabu.utils.OnnxRuntimeManager
import com.example.nabu.kokoro.Downloader
import com.example.nabu.kokoro.RunEp
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import java.util.Locale

const val EXTRA_START_SCREEN = "start_screen"
private const val PLAYBACK_CHANNEL_ID = "playback_channel"
private const val PLAYBACK_NOTIFICATION_ID = 1

class MyApplication : Application() {
    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        preloadScope.launch {
            OnnxRuntimeManager.initialize(applicationContext)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        preloadScope.cancel()
    }
}

private sealed interface ModelState {
    data object Loading : ModelState
    data object Ready : ModelState
    data class Error(val message: String) : ModelState
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

    // Service binding - using mutableStateOf for Compose reactivity
    private var speechService by mutableStateOf<SpeechForegroundService?>(null)
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            DebugLogger.log("MainActivity: Service connected")
            val binder = service as SpeechForegroundService.LocalBinder
            speechService = binder.getService()
            isBound = true
            DebugLogger.log("MainActivity: Service is now available for UI")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            DebugLogger.log("MainActivity: Service disconnected")
            speechService = null
            isBound = false
        }
    }

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
        phonemeConverter = PhonemeConverter(this)

        // Start SpeechForegroundService properly (not just bind)
        val serviceIntent = Intent(this, SpeechForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, 0)

        val startScreen = screenFromString(intent.getStringExtra(EXTRA_START_SCREEN))

        setContent {
            NabuTheme {
                LaunchedEffect(Unit) {
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                }

                val service by remember { derivedStateOf { speechService } }

                MainScreen(
                    phonemeConverter = phonemeConverter,
                    speechService = service,
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

                if (SettingsManager.isBenchmark(this@MainActivity)) {
                    PerfHud.Overlay()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
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
    val modelManager = com.example.nabu.data.ModelManager(context)
    scope.launch(Dispatchers.IO) {
        val engine = com.example.nabu.tts.TTSManager.getEngine(context, modelManager)
        if (engine == null) {
             withContext(Dispatchers.Main) {
                Toast.makeText(context, "No TTS engine available", Toast.LENGTH_LONG).show()
                onComplete()
            }
            return@launch
        }

        val styleLoader = StyleLoader(context)
        try {
            val ttsStart = SystemClock.elapsedRealtime()
            val phonemes = phonemeConverter.phonemize(text)
            DebugLogger.log("Phonemes: $phonemes")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Phonemes: $phonemes", Toast.LENGTH_LONG).show()
            }

            val rawEngine = if (engine is com.example.nabu.tts.BenchmarkingTTSEngine) engine.delegate else engine

            val (audioData, sampleRate) = if (rawEngine is com.example.nabu.kokoro.KokoroEngine) {
                PerfHud.record("TTS synth") {
                     createAudio(
                        phonemes = phonemes,
                        voice = style,
                        speed = speed,
                        engine = rawEngine,
                        styleLoader = styleLoader
                    )
                }
            } else {
                // Supertonic and others are suspend functions, so we can't use PerfHud.record (which expects non-suspend)
                // We can manually measure if needed, but for now just call directly.
                if (rawEngine is com.example.nabu.supertonic.DebugSupertonicEngine) {
                    rawEngine.setStyle(style)
                    val result = rawEngine.synthesize(text, speed)
                    result.wav to result.sampleRate
                } else {
                    val result = engine.synthesize(text, speed)
                    result.wav to result.sampleRate
                }
            }

            val genMs = SystemClock.elapsedRealtime() - ttsStart
            if (SettingsManager.isBenchmark(context)) {
                val audioMs = audioData.size * 1000L / sampleRate
                BenchmarkManager.recordTts(OnnxRuntimeManager.currentBundle(), genMs, audioMs)
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

private fun screenToString(screen: Screen): String = when (screen) {
    Screen.Basic -> "Basic"
    Screen.Mixer -> "Mixer"
    Screen.Book -> "Book"
    Screen.Chat -> "Chat"
    Screen.More -> "More"
    Screen.Creations -> "Creations"
    Screen.Settings -> "Settings"
    Screen.Models -> "Models"
    Screen.DebugLog -> "DebugLog"
    Screen.Credits -> "Credits"
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

private val featureScreens = listOf(Screen.Basic, Screen.Mixer, Screen.Book, Screen.More)

private fun Screen.asFeature(): Screen? = when (this) {
    Screen.Basic -> Screen.Basic
    Screen.Mixer -> Screen.Mixer
    Screen.Book -> Screen.Book
    Screen.More, Screen.Creations, Screen.Settings, Screen.Models, Screen.Credits, Screen.DebugLog -> Screen.More
    Screen.Chat -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    phonemeConverter: PhonemeConverter,
    speechService: SpeechForegroundService?,
    onGenerateAudio: (String, String, Float, Boolean, () -> Unit) -> Unit,
    userPreferencesRepository: UserPreferencesRepository,
    initialScreen: Screen = Screen.Basic
) {
    val screenStack = rememberSaveable(
        saver = listSaver(
            save = { stateList -> stateList.map(::screenToString) },
            restore = { saved ->
                mutableStateListOf<Screen>().apply {
                    if (saved.isEmpty()) {
                        add(Screen.Basic)
                    } else {
                        saved.map(::screenFromString).forEach { add(it) }
                    }
                }
            }
        )
    ) {
        mutableStateListOf(initialScreen)
    }

    if (screenStack.isEmpty()) {
        screenStack.add(Screen.Basic)
    }

    val currentScreen = screenStack.last()
    val currentFeature = currentScreen.asFeature()
    val navigateTo: (Screen) -> Unit = { screen ->
        if (screenStack.lastOrNull() != screen) {
            screenStack.add(screen)
        }
    }

    BackHandler(enabled = screenStack.size > 1) {
        if (screenStack.size > 1) {
            screenStack.removeAt(screenStack.lastIndex)
        }
    }
    val context = LocalContext.current

    // Collect speech state for global status line
    val speechState by speechService?.state?.collectAsState() ?: remember {
        mutableStateOf(SpeechState.Idle)
    }
    val showGlobalStatus = speechState !is SpeechState.Idle

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (showGlobalStatus) {
                PanelBox(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isBusy = speechState is SpeechState.PreparingModels ||
                                     speechState is SpeechState.Chunking ||
                                     speechState is SpeechState.Synthesizing ||
                                     speechState is SpeechState.Buffering

                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 4.dp),
                                color = Brutal.textBright
                            )
                        }

                        Text(
                            text = "⚡ ${speechState.toStatusString()}",
                            color = Brutal.textBright,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f)
                        )

                        // Quick stop button
                        if (speechState !is SpeechState.Idle) {
                            BrutalButton(
                                onClick = { speechService?.stop() },
                                modifier = Modifier
                            ) {
                                Text("✕", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BrutalButton(
                    onClick = { navigateTo(Screen.Basic) },
                    modifier = Modifier.weight(1f),
                    enabled = currentFeature != Screen.Basic
                ) { Text("BASIC") }
                BrutalButton(
                    onClick = { navigateTo(Screen.Mixer) },
                    modifier = Modifier.weight(1f),
                    enabled = currentFeature != Screen.Mixer
                ) { Text("MIXER") }
                BrutalButton(
                    onClick = { navigateTo(Screen.Book) },
                    modifier = Modifier.weight(1f),
                    enabled = currentFeature != Screen.Book
                ) { Text("BOOK") }
                BrutalButton(
                    onClick = {
                        context.startActivity(Intent(context, ChatActivity::class.java))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("CHAT") }
                BrutalButton(
                    onClick = { navigateTo(Screen.More) },
                    modifier = Modifier.weight(1f),
                    enabled = currentFeature != Screen.More
                ) { Text("MORE") }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .pointerInput(currentScreen, screenStack.size) {
                    var totalDrag = 0f
                    detectDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onDragCancel = { totalDrag = 0f },
                        onDragEnd = {
                            val feature = currentFeature ?: return@detectDragGestures
                            val index = featureScreens.indexOf(feature)
                            if (abs(totalDrag) > 96f && index != -1) {
                                when {
                                    totalDrag > 0f && index > 0 -> navigateTo(featureScreens[index - 1])
                                    totalDrag < 0f && index < featureScreens.lastIndex -> navigateTo(featureScreens[index + 1])
                                }
                            }
                            totalDrag = 0f
                        }
                    ) { _, dragAmount ->
                        totalDrag += dragAmount.x
                    }
                }
        ) {
            when (currentScreen) {
                Screen.Basic -> BasicScreen(speechService = speechService)
                Screen.Mixer -> MixerScreen(
                    phonemeConverter = phonemeConverter,
                    styleLoader = StyleLoader(context)
                )
                Screen.Book -> BookScreen(
                    phonemeConverter = phonemeConverter
                )
                Screen.Chat -> {
                    // No-op, handled by onClick which starts ChatActivity
                    // This case is primarily for the 'selected' state of the NavigationBarItem
                }
                Screen.More -> MoreScreen { screen ->
                    val destination = when (screen) {
                        "Creations" -> Screen.Creations
                        "Settings" -> Screen.Settings
                        "Models" -> Screen.Models
                        "Credits" -> Screen.Credits
                        "DebugLog" -> Screen.DebugLog
                        else -> null
                    }
                    destination?.let { navigateTo(it) }
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
    speechService: SpeechForegroundService?
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    // Scope ViewModel to Activity so it persists across navigation
    val viewModel: com.example.nabu.viewmodel.BasicViewModel = viewModel(
        viewModelStoreOwner = activity ?: error("Context must be a ComponentActivity"),
        factory = com.example.nabu.viewmodel.BasicViewModel.Factory(context)
    )

    // Collect ViewModel state
    val text by viewModel.text.collectAsState()
    val style by viewModel.style.collectAsState()
    val speed by viewModel.speed.collectAsState()
    val modelState by viewModel.modelState.collectAsState()
    val hasLocalModels by viewModel.hasLocalModels.collectAsState()

    // Collect service state
    val speechState by speechService?.state?.collectAsState() ?: remember {
        mutableStateOf(SpeechState.Idle)
    }

    // UI state
    var expanded by remember { mutableStateOf(false) }
    var saveMode by remember { mutableStateOf(false) }

    // Determine if we can interact with UI
    val isIdle = speechState is SpeechState.Idle || speechState is SpeechState.Error
    val isPlaying = speechState is SpeechState.Playing
    val isPaused = speechState is SpeechState.Paused
    val isBusy = !isIdle && !isPaused

    PanelBox(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Model state indicator
            when (val state = modelState) {
                is com.example.nabu.viewmodel.BasicViewModel.ModelState.Loading -> {
                    val runtimeLabel = viewModel.getRuntimeDisplayName()
                    val loadingMessage = if (hasLocalModels) {
                        "Loading $runtimeLabel runtime…"
                    } else {
                        "Downloading voice models…"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        Text(loadingMessage, color = Brutal.textBright)
                    }
                }
                is com.example.nabu.viewmodel.BasicViewModel.ModelState.Error -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error
                        )
                        BrutalButton(onClick = { viewModel.retryModelInitialization() }) {
                            Text("Retry download")
                        }
                    }
                }
                is com.example.nabu.viewmodel.BasicViewModel.ModelState.Ready -> {
                    // Show speech state if active
                    if (speechState !is SpeechState.Idle) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                            Text(
                                text = speechState.toStatusString(),
                                color = Brutal.textBright
                            )
                        }
                    }
                }
            }

            // Text input
            TextField(
                value = text,
                minLines = 3,
                maxLines = 12,
                onValueChange = { viewModel.updateText(it) },
                label = { Text("TEXT TO SPEAK") },
                modifier = Modifier.fillMaxWidth(),
                enabled = isIdle,
                keyboardOptions = KeyboardOptions.Default.copy(
                    keyboardType = KeyboardType.Text
                )
            )

            // Style dropdown
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { if (isIdle) expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                TextField(
                    value = style,
                    onValueChange = {},
                    label = { Text("STYLE") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    readOnly = true,
                    enabled = isIdle,
                    trailingIcon = {
                        Text(if (expanded) "▲" else "▼", color = Brutal.textBright)
                    }
                )

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    viewModel.availableStyles.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name.uppercase()) },
                            onClick = {
                                viewModel.updateStyle(name)
                                expanded = false
                            }
                        )
                    }
                }
            }

            // Speed slider
            PanelRow(name = "SPEED") {
                BrutalSlider(
                    value = speed,
                    onValueChange = { viewModel.updateSpeed(it) },
                    range = 0.5f..2.0f,
                    modifier = Modifier.weight(1f)
                )
                Text("%.2f".format(speed))
            }

            // Player controls
            val modelReady = modelState is com.example.nabu.viewmodel.BasicViewModel.ModelState.Ready
            val serviceReady = speechService != null
            val canPlay = isIdle && style.isNotEmpty() && modelReady && serviceReady

            when {
                // Idle state - show PLAY and PLAY & SAVE buttons
                isIdle -> {
                    // Show service status if not ready
                    if (!serviceReady && modelReady) {
                        Text(
                            text = "Connecting to speech service...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Brutal.textDim,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        BrutalButton(
                            onClick = {
                                saveMode = false
                                viewModel.updateShouldSaveFile(false)
                                val request = viewModel.createSpeechRequest()
                                speechService?.speak(request) ?: run {
                                    DebugLogger.log("BasicScreen: Service not ready, cannot start TTS")
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = canPlay
                        ) {
                            Text("PLAY")
                        }

                        BrutalButton(
                            onClick = {
                                saveMode = true
                                viewModel.updateShouldSaveFile(true)
                                val request = viewModel.createSpeechRequest()
                                speechService?.speak(request) ?: run {
                                    DebugLogger.log("BasicScreen: Service not ready, cannot start TTS")
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = canPlay
                        ) {
                            Text("PLAY & SAVE")
                        }
                    }
                }

                // Playing state - show PAUSE and STOP buttons
                isPlaying -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        BrutalButton(
                            onClick = { speechService?.pause() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("PAUSE")
                        }

                        BrutalButton(
                            onClick = { speechService?.stop() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("STOP")
                        }
                    }
                }

                // Paused state - show RESUME and STOP buttons
                isPaused -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        BrutalButton(
                            onClick = { speechService?.resume() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("RESUME")
                        }

                        BrutalButton(
                            onClick = { speechService?.stop() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("STOP")
                        }
                    }
                }

                // Busy state (synthesizing, chunking, etc.) - show STOP button
                isBusy -> {
                    BrutalButton(
                        onClick = { speechService?.stop() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("STOP")
                    }
                }
            }

            // Status info box at bottom
            PanelBox(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Text(
                        text = "STATUS",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brutal.textDim
                    )
                    Text(
                        text = when {
                            speechState is SpeechState.Idle -> "Ready to generate speech"
                            speechState is SpeechState.PreparingModels -> "Initializing TTS models..."
                            speechState is SpeechState.Chunking -> "Splitting text into ${(speechState as SpeechState.Chunking).totalChunks} chunks..."
                            speechState is SpeechState.Synthesizing -> {
                                val s = speechState as SpeechState.Synthesizing
                                "Generating audio chunk ${s.currentChunk}/${s.totalChunks}..."
                            }
                            speechState is SpeechState.Buffering -> "Buffering audio..."
                            speechState is SpeechState.Playing -> {
                                val s = speechState as SpeechState.Playing
                                "Playing chunk ${s.currentChunk}/${s.totalChunks}"
                            }
                            speechState is SpeechState.Paused -> {
                                val s = speechState as SpeechState.Paused
                                "Paused at chunk ${s.currentChunk}/${s.totalChunks}"
                            }
                            speechState is SpeechState.Error -> "Error: ${(speechState as SpeechState.Error).message}"
                            else -> "Unknown state"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (speechState is SpeechState.Error) MaterialTheme.colorScheme.error else Brutal.textBright
                    )
                }
            }
        }
    }
}

private fun RunEp.displayName(): String =
    name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

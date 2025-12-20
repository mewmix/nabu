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
import com.example.nabu.screens.ThemeEditorScreen
import com.example.kokoro.galleryport.PerfHud
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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

        // Background initialization without blocking
        preloadScope.launch {
            if (SettingsManager.getTtsEngine(applicationContext) == "kokoro") {
                 OnnxRuntimeManager.initialize(applicationContext)
            } else {
                 // Even if not selected, if we want to preload in background as requested:
                 // We can fire it off but ensure it doesn't block UI.
                 // OnnxRuntimeManager.initialize uses a mutex so it's safe to call.
                 // We just won't wait for it in the UI unless needed.
                 OnnxRuntimeManager.initialize(applicationContext)
            }
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
    data object Skipped : ModelState // For when we don't need Kokoro loaded
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

        // Removed automatic permission request on start

        val startScreen = screenFromString(intent.getStringExtra(EXTRA_START_SCREEN))

        setContent {
            NabuTheme {
                LaunchedEffect(Unit) {
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                }

                MainScreen(
                    phonemeConverter = phonemeConverter,
                    onGenerateAudio = { text, style, speed, shouldSave, onComplete ->
                        // Removed permission request from basic generation
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
    val modelManager = com.example.nabu.data.ModelManager(context)
    scope.launch(Dispatchers.IO) {
        val engine = com.example.nabu.tts.TTSManager.getEngine(context, modelManager)
        if (engine == null) {
             withContext(Dispatchers.Main) {
                Toast.makeText(context, "No TTS engine available or ready", Toast.LENGTH_LONG).show()
                onComplete()
            }
            return@launch
        }

        val styleLoader = StyleLoader(context)
        try {
            val ttsStart = SystemClock.elapsedRealtime()
            val phonemes = phonemeConverter.phonemize(text)
            DebugLogger.log("Phonemes: $phonemes")

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

            // Only show notification if permission granted, do not request it here.
            // showPlaybackNotification(context)
            // Actually, for Basic screen (short utterances), we might not want notification spam at all.
            // The request was "don't request it from the basic screen".
            // If the permission is already granted, showing it is fine, but if not, don't ask.
            // showPlaybackNotification checks permission before showing.

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
    "ThemeEditor" -> Screen.ThemeEditor
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
    Screen.ThemeEditor -> "ThemeEditor"
}

sealed class Screen {
    object Basic : Screen()
    object Mixer : Screen()
    object Book : Screen()
    object Chat : Screen()
    object More : Screen()
    object Creations : Screen()
    object Settings : Screen()
    object Models : Screen()
    object DebugLog : Screen()
    object Credits : Screen()
    object ThemeEditor : Screen()
}

private val featureScreens = listOf(Screen.Basic, Screen.Mixer, Screen.Book, Screen.More)

private fun Screen.asFeature(): Screen? = when (this) {
    Screen.Basic -> Screen.Basic
    Screen.Mixer -> Screen.Mixer
    Screen.Book -> Screen.Book
    Screen.More, Screen.Creations, Screen.Settings, Screen.Models, Screen.Credits, Screen.DebugLog, Screen.ThemeEditor -> Screen.More
    Screen.Chat -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    phonemeConverter: PhonemeConverter,
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
                Screen.Basic -> BasicScreen(onGenerateAudio = onGenerateAudio)
                Screen.Mixer -> MixerScreen(
                    phonemeConverter = phonemeConverter,
                    styleLoader = StyleLoader(context)
                )
                Screen.Book -> BookScreen(
                    phonemeConverter = phonemeConverter
                )
                Screen.Chat -> {
                    // No-op
                }
                Screen.More -> MoreScreen { screen ->
                    val destination = when (screen) {
                        "Creations" -> Screen.Creations
                        "Settings" -> Screen.Settings
                        "Models" -> Screen.Models
                        "Credits" -> Screen.Credits
                        "DebugLog" -> Screen.DebugLog
                        "ThemeEditor" -> Screen.ThemeEditor
                        else -> null
                    }
                    destination?.let { navigateTo(it) }
                }
                Screen.Creations -> CreationsScreen()
                Screen.Settings -> SettingsScreen(onNavigate = { dest ->
                    if (dest == "ThemeEditor") navigateTo(Screen.ThemeEditor)
                })
                Screen.Models -> ModelsScreen(userPreferencesRepository)
                Screen.Credits -> CreditsConstellationScreen()
                Screen.DebugLog -> DebugLogScreen()
                Screen.ThemeEditor -> ThemeEditorScreen(onBack = {
                     if (screenStack.size > 1) screenStack.removeAt(screenStack.lastIndex)
                })
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
    val styleLoader = remember { StyleLoader(context) }
    val names = styleLoader.names.sorted()

    var text by remember { mutableStateOf("Made with love and brought to you from outer space.") }
    var style by remember {
        mutableStateOf(
            SettingsManager.getStyle(context).takeIf { it in names }
                ?: names.firstOrNull().orEmpty()
        )
    }
    var speed by remember { mutableFloatStateOf(SettingsManager.getSpeed(context)) }
    var isProcessing by remember { mutableStateOf(false) }
    var shouldSaveFile by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var initTrigger by remember { mutableStateOf(0) }
    var modelState by remember { mutableStateOf<ModelState>(ModelState.Loading) }

    val ttsEngine = remember { SettingsManager.getTtsEngine(context) }
    val isKokoro = ttsEngine == "kokoro"

    var hasLocalModels by remember {
        mutableStateOf(
            if (isKokoro) {
                Downloader.modelsAvailable(
                    context.applicationContext,
                    OnnxRuntimeManager.currentManifest()
                )
            } else true
        )
    }

    LaunchedEffect(initTrigger, ttsEngine) {
        modelState = ModelState.Loading
        if (isKokoro) {
            hasLocalModels = Downloader.modelsAvailable(
                context.applicationContext,
                OnnxRuntimeManager.currentManifest()
            )
            val result = withContext(Dispatchers.IO) {
                OnnxRuntimeManager.initialize(context.applicationContext)
            }
            modelState = result.fold(
                onSuccess = { ModelState.Ready },
                onFailure = { ModelState.Error(it?.message ?: "Unable to prepare Kokoro models") }
            )
        } else {
            // Supertonic or other engine.
            // We assume it's ready or handled by TTSManager lazily.
            // But we still want to ensure Kokoro loads in background if requested by user logic (bug fix)
            // The Application class already kicks off background load.
            modelState = ModelState.Skipped
        }

        if (style.isEmpty() && names.isNotEmpty()) {
            style = names.first()
            SettingsManager.setStyle(context, style)
        }
    }

    LaunchedEffect(style) {
        if (style.isNotEmpty()) {
            SettingsManager.setStyle(context, style)
        }
    }

    PanelBox(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (modelState !is ModelState.Skipped) {
                when (val state = modelState) {
                    ModelState.Loading -> {
                        val runtimePreference = SettingsManager.getRuntimePreference(context)
                        val runtimeLabel = runtimePreference.displayName()
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
                    is ModelState.Error -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = state.message,
                                color = MaterialTheme.colorScheme.error
                            )
                            BrutalButton(onClick = { initTrigger++ }) {
                                Text("Retry download")
                            }
                        }
                    }
                    ModelState.Ready -> Unit
                    ModelState.Skipped -> Unit
                }
            }

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
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
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
                                expanded = false
                            }
                        )
                    }
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
                val playEnabled = !isProcessing && style.isNotEmpty() && (modelState is ModelState.Ready || modelState is ModelState.Skipped)

                BrutalButton(
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
                    enabled = playEnabled
                ) {
                    Text(if (isProcessing) "PROCESSING..." else "PLAY")
                }

                Spacer(modifier = Modifier.width(12.dp))

                BrutalButton(
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
                    enabled = playEnabled
                ) {
                    Text(if (isProcessing) "PROCESSING..." else "PLAY & SAVE")
                }
            }
        }
    }
}

private fun RunEp.displayName(): String =
    name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

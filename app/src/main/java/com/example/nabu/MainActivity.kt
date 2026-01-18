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
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import com.mewmix.nabu.ui.brutalist.PanelBox
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.BrutalButtonText
import com.mewmix.nabu.ui.brutalist.BrutalSlider
import com.mewmix.nabu.ui.brutalist.PanelRow
import com.mewmix.nabu.ui.brutalist.Brutal
import com.example.nabu.ui.components.ModernBottomBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.nabu.utils.formatBytes
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
import com.example.nabu.data.ModelManager
import com.example.nabu.data.ModelType
import com.example.nabu.screens.InitScreen

const val EXTRA_START_SCREEN = "start_screen"
const val EXTRA_BOOK_URI = "book_uri"
class MyApplication : Application() {
    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        preloadScope.launch {
            if (SettingsManager.isInitComplete(this@MyApplication) &&
                SettingsManager.getTtsEngine(this@MyApplication) == "kokoro"
            ) {
                OnnxRuntimeManager.initialize(
                    applicationContext,
                    allowDownload = SettingsManager.isKokoroAutoDownloadEnabled(this@MyApplication)
                )
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
}

class MainActivity : ComponentActivity() {
    private lateinit var phonemeConverter: PhonemeConverter
    private val scope = MainScope()
    private lateinit var userPreferencesRepository: UserPreferencesRepository
    private val requestedScreen = mutableStateOf<Screen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        DebugLogger.initialize(this)
        enableEdgeToEdge()
        userPreferencesRepository = UserPreferencesRepository(this)

        val startScreen = handleStartIntent(intent)
        requestedScreen.value = startScreen

        setContent {
            NabuTheme {
                LaunchedEffect(Unit) {
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                }
                val context = LocalContext.current
                var needsInit by remember { mutableStateOf(!SettingsManager.isInitComplete(context)) }

                if (needsInit) {
                    InitScreen(
                        userPreferencesRepository = userPreferencesRepository,
                        onComplete = { needsInit = false }
                    )
                } else {
                    MainScreen(
                        phonemeConverter = phonemeConverter,
                        onGenerateAudio = { text, style, speed, shouldSave, onComplete ->
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
                        initialScreen = startScreen,
                        requestedScreen = requestedScreen.value,
                        onRequestedScreenHandled = { requestedScreen.value = null }
                    )

                    if (SettingsManager.isBenchmark(this@MainActivity)) {
                        PerfHud.Overlay()
                    }
                }
            }
        }

        phonemeConverter = PhonemeConverter(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val screen = handleStartIntent(intent)
        requestedScreen.value = screen
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun handleStartIntent(intent: Intent?): Screen {
        val screen = screenFromString(intent?.getStringExtra(EXTRA_START_SCREEN))
        val bookUri = intent?.getStringExtra(EXTRA_BOOK_URI)
        if (!bookUri.isNullOrBlank()) {
            SettingsManager.setLastBookUri(this, bookUri)
        }
        return screen
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
    onGenerateAudio: (String, String, Float, Boolean, () -> Unit) -> Unit,
    userPreferencesRepository: UserPreferencesRepository,
    initialScreen: Screen = Screen.Basic,
    requestedScreen: Screen? = null,
    onRequestedScreenHandled: () -> Unit = {}
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

    LaunchedEffect(requestedScreen) {
        requestedScreen?.let {
            navigateTo(it)
            onRequestedScreenHandled()
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
            ModernBottomBar(
                currentScreen = currentScreen,
                onNavigate = { screen ->
                    if (screen == Screen.Chat) {
                        context.startActivity(Intent(context, ChatActivity::class.java))
                    } else {
                        navigateTo(screen)
                    }
                }
            )
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
    var downloadProgress by remember { mutableStateOf<Downloader.DownloadProgress?>(null) }
    var isSupertonic by remember { mutableStateOf(false) }
    var hasSupertonicModels by remember { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()
    var hasLocalModels by remember {
        mutableStateOf(
            Downloader.modelsAvailable(
                context.applicationContext,
                OnnxRuntimeManager.currentManifest()
            )
        )
    }

    LaunchedEffect(initTrigger) {
        modelState = ModelState.Loading
        downloadProgress = null
        val preferredEngine = SettingsManager.getTtsEngine(context)
        isSupertonic = preferredEngine == "supertonic"
        if (isSupertonic) {
            val modelManager = ModelManager(context)
            val selectedId = SettingsManager.getSupertonicModelId(context)
            val downloadedModels = modelManager.models.filter { model ->
                model.type == ModelType.TTS && model.isDownloaded
            }
            val selectedModel = selectedId?.let { id -> downloadedModels.firstOrNull { it.id == id } }
            hasSupertonicModels = if (selectedId != null) selectedModel != null else downloadedModels.isNotEmpty()
            modelState = ModelState.Ready
        } else {
            hasLocalModels = Downloader.modelsAvailable(
                context.applicationContext,
                OnnxRuntimeManager.currentManifest()
            )
            val result = withContext(Dispatchers.IO) {
                OnnxRuntimeManager.initialize(
                    context.applicationContext,
                    allowDownload = SettingsManager.isKokoroAutoDownloadEnabled(context),
                    onProgress = { progress -> uiScope.launch { downloadProgress = progress } }
                )
            }
            modelState = result.fold(
                onSuccess = { ModelState.Ready },
                onFailure = { ModelState.Error(it?.message ?: "Unable to prepare Kokoro models") }
            )
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
            when (val state = modelState) {
                ModelState.Loading -> {
                    val runtimePreference = SettingsManager.getRuntimePreference(context)
                    val runtimeLabel = runtimePreference.displayName()
                    val loadingMessage = if (hasLocalModels) {
                        "Loading $runtimeLabel runtime…"
                    } else {
                        "Downloading voice models…"
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                            Text(loadingMessage, color = Brutal.textBright)
                        }
                        downloadProgress?.let { progress ->
                            if (progress.totalBytes > 0L) {
                                val ratio = progress.downloadedBytes.toFloat() / progress.totalBytes.toFloat()
                                LinearProgressIndicator(
                                    progress = { ratio.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    "${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)}",
                                    color = Brutal.textDim
                                )
                            }
                        }
                    }
                }
                is ModelState.Error -> {
                    val downloadEnabled = SettingsManager.isKokoroAutoDownloadEnabled(context)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error
                        )
                        BrutalButton(onClick = {
                            if (!downloadEnabled) {
                                SettingsManager.setKokoroAutoDownload(context, true)
                            }
                            initTrigger++
                        }) {
                            BrutalButtonText(if (downloadEnabled) "Retry download" else "Download voice models")
                        }
                    }
                }
                ModelState.Ready -> Unit
            }

            if (isSupertonic && !hasSupertonicModels) {
                Text(
                    text = if (SettingsManager.getSupertonicModelId(context) != null) {
                        "Selected Supertonic model is not downloaded yet. Open Models to download it."
                    } else {
                        "No Supertonic voice models found. Open Models to download one."
                    },
                    color = MaterialTheme.colorScheme.error
                )
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
                val playEnabled = !isProcessing &&
                    style.isNotEmpty() &&
                    modelState is ModelState.Ready &&
                    (!isSupertonic || hasSupertonicModels)

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
                    BrutalButtonText(if (isProcessing) "PROCESSING..." else "PLAY")
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
                    BrutalButtonText(if (isProcessing) "PROCESSING..." else "PLAY & SAVE")
                }
            }
        }
    }
}

private fun RunEp.displayName(): String =
    name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

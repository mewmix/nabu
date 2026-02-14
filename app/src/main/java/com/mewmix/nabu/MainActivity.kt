package com.mewmix.nabu

import NabuTheme
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.mewmix.nabu.screens.BookScreen
import com.mewmix.nabu.screens.CreationsScreen
import com.mewmix.nabu.screens.SettingsScreen
import com.mewmix.nabu.screens.MixerScreen
import com.mewmix.nabu.screens.MoreScreen
import com.mewmix.nabu.screens.ModelsScreen
import com.mewmix.nabu.screens.DebugLogScreen
import com.mewmix.nabu.screens.CreditsConstellationScreen
import com.mewmix.nabu.galleryport.PerfHud
import com.mewmix.nabu.api.ApiServerManager
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
import com.mewmix.nabu.ui.components.ModernBottomBar
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
import com.mewmix.nabu.data.UserPreferencesRepository
import com.mewmix.nabu.utils.PhonemeConverter
import com.mewmix.nabu.utils.StyleLoader
import com.mewmix.nabu.utils.createAudio
import com.mewmix.nabu.utils.BenchmarkManager
import com.mewmix.nabu.utils.playAudio
import com.mewmix.nabu.utils.saveAudio
import com.mewmix.nabu.utils.SettingsManager
import com.mewmix.nabu.utils.DebugLogger
import com.mewmix.nabu.utils.OnnxRuntimeManager
import com.mewmix.nabu.utils.formatBytes
import com.mewmix.nabu.kokoro.Downloader
import com.mewmix.nabu.kokoro.RunEp
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
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.ModelType
import com.mewmix.nabu.screens.InitScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mewmix.nabu.viewmodel.GlobalRuntimeViewModel
import com.mewmix.nabu.ui.components.GlobalStatusBar
import com.mewmix.nabu.ui.components.RuntimeStatusLine
import com.mewmix.nabu.data.ModelState
import androidx.compose.runtime.collectAsState

const val EXTRA_START_SCREEN = "start_screen"
const val EXTRA_BOOK_URI = "book_uri"
class MyApplication : Application() {
    // Initialization moved to GlobalRuntimeViewModel
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        // Ensure early initialization of file logger and optional method tracing
        com.mewmix.nabu.utils.DebugLogger.initialize(this)

        // Global uncaught exception handler to capture stack traces in our log
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                com.mewmix.nabu.utils.DebugLogger.logErr("Uncaught exception in thread ${t.name}", e)
            } catch (_: Throwable) {
                // No-op; avoid recursive crashes
            }
        }

        // Optionally start method tracing based on persisted setting
        if (com.mewmix.nabu.utils.SettingsManager.isMethodTracingEnabled(this)) {
            com.mewmix.nabu.utils.MethodTraceManager.start(this)
        }

        ApiServerManager.syncWithSettings(this)
    }

    override fun onTerminate() {
        ApiServerManager.stop()
        super.onTerminate()
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var phonemeConverter: PhonemeConverter
    private val scope = MainScope()
    private lateinit var userPreferencesRepository: UserPreferencesRepository
    private val requestedScreen = mutableStateOf<Screen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Logger initialized in Application
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
                    val viewModel: GlobalRuntimeViewModel = viewModel()
                    MainScreen(
                        viewModel = viewModel,
                        phonemeConverter = phonemeConverter,
                        onGenerateAudio = { text, style, speed, shouldSave, onComplete ->
                            generateAudio(
                                viewModel,
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
        // Stop method tracing if running
        com.mewmix.nabu.utils.MethodTraceManager.stop()
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
    viewModel: GlobalRuntimeViewModel,
    phonemeConverter: PhonemeConverter,
    text: String,
    style: String,
    speed: Float,
    context: Context,
    scope: CoroutineScope,
    shouldSave: Boolean,
    onComplete: () -> Unit
) {
    val modelManager = com.mewmix.nabu.data.ModelManager(context)
    scope.launch(Dispatchers.IO) {
        com.mewmix.nabu.utils.DebugLogger.log("Main.generateAudio: requesting engine")
        val engine = com.mewmix.nabu.tts.TTSManager.getEngine(context, modelManager)
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

            val rawEngine = if (engine is com.mewmix.nabu.tts.BenchmarkingTTSEngine) engine.delegate else engine
            com.mewmix.nabu.utils.DebugLogger.log("Main.generateAudio: using engine='${rawEngine.name}' provider='${rawEngine.provider}'")

            val (audioData, sampleRate) = if (rawEngine is com.mewmix.nabu.kokoro.KokoroEngine) {
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
                if (rawEngine is com.mewmix.nabu.supertonic.DebugSupertonicEngine) {
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
                
                val rtf = if (audioMs > 0) genMs.toFloat() / audioMs.toFloat() else 0f
                viewModel.updateBenchmarkStat("RTF", rtf)
                viewModel.updateBenchmarkStat("Latency", genMs.toFloat())
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
    viewModel: GlobalRuntimeViewModel,
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
    
    // Collect Global State
    val modelState by viewModel.modelState.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val benchmarkStats by viewModel.benchmarkStats.collectAsState()

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
        Column(modifier = Modifier.padding(innerPadding)) {
            // Global Status Bar (Shows Loading / Errors / Benchmarks)
            GlobalStatusBar(
                modelState = modelState,
                downloadProgress = downloadProgress,
                benchmarkStats = benchmarkStats
            )
            
            Box(
                modifier = Modifier
                    .weight(1f)
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
                    Screen.Basic -> BasicScreen(
                        onGenerateAudio = onGenerateAudio,
                        modelState = modelState
                    )
                    Screen.Mixer -> MixerScreen(
                        phonemeConverter = phonemeConverter,
                        styleLoader = StyleLoader(context)
                    )
                    Screen.Book -> BookScreen(
                        phonemeConverter = phonemeConverter
                    )
                    Screen.Chat -> {
                        // No-op, handled by onClick which starts ChatActivity
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BasicScreen(
    onGenerateAudio: (String, String, Float, Boolean, () -> Unit) -> Unit,
    modelState: ModelState
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
    
    var isSupertonic by remember { mutableStateOf(SettingsManager.getTtsEngine(context) == "supertonic") }
    var isSoprano by remember { mutableStateOf(SettingsManager.getTtsEngine(context) == "soprano") }
    var hasSupertonicModels by remember { mutableStateOf(false) }
    val styleRequired = !isSoprano

    LaunchedEffect(Unit) {
        val preferredEngine = SettingsManager.getTtsEngine(context)
        isSupertonic = preferredEngine == "supertonic"
        isSoprano = preferredEngine == "soprano"
        if (isSupertonic) {
            val modelManager = ModelManager(context)
            val selectedId = SettingsManager.getSupertonicModelId(context)
            val downloadedModels = modelManager.models.filter { model ->
                model.type == ModelType.TTS && model.isDownloaded
            }
            val selectedModel = selectedId?.let { id -> downloadedModels.firstOrNull { it.id == id } }
            hasSupertonicModels = if (selectedId != null) selectedModel != null else downloadedModels.isNotEmpty()
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
            RuntimeStatusLine()

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

            if (styleRequired) {
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
                    (!styleRequired || style.isNotEmpty()) &&
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

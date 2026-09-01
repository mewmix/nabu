package com.mewmix.nabu

import NabuTheme
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.material.color.DynamicColors
import com.mewmix.nabu.api.ApiServerBackgroundService
import com.mewmix.nabu.api.ApiServerManager
import com.mewmix.nabu.api.ApiServerRuntime
import com.mewmix.nabu.data.UserPreferencesRepository
import com.mewmix.nabu.screens.AudioScreen
import com.mewmix.nabu.screens.BookScreen
import com.mewmix.nabu.screens.CreationsScreen
import com.mewmix.nabu.screens.CreditsConstellationScreen
import com.mewmix.nabu.screens.DebugLogScreen
import com.mewmix.nabu.screens.InitScreen
import com.mewmix.nabu.screens.MixerScreen
import com.mewmix.nabu.screens.ModelsScreen
import com.mewmix.nabu.screens.MoreScreen
import com.mewmix.nabu.screens.OptionalPermissionsScreen
import com.mewmix.nabu.screens.SettingsScreen
import com.mewmix.nabu.ui.components.GlobalStatusBar
import com.mewmix.nabu.utils.PhonemeConverter
import com.mewmix.nabu.utils.SettingsManager
import com.mewmix.nabu.utils.StyleLoader
import com.mewmix.nabu.utils.UpdateChecker
import com.mewmix.nabu.viewmodel.GlobalRuntimeViewModel
import com.mewmix.nabu.viewmodel.TtsWorkbenchViewModel

const val EXTRA_START_SCREEN = "start_screen"
const val EXTRA_BOOK_URI = "book_uri"
class MyApplication : Application() {
    // Initialization moved to GlobalRuntimeViewModel
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        // Ensure early initialization of file logger and optional method tracing
        com.mewmix.nabu.utils.DebugLogger.initialize(this)
        com.mewmix.nabu.uiagent.AutomationSessionManager.initialize(this)
        com.mewmix.nabu.uiagent.AutomationMediaManager.purgeAllCaptures(this)
        com.mewmix.nabu.actions.DeviceAction.initializeInstalledAppIndex(this)

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

        UpdateChecker.schedulePeriodicChecks(this)
        UpdateChecker.enqueueStartupCheck(this)

        ApiServerRuntime.syncWithSettings(this)
    }

    override fun onTerminate() {
        ApiServerBackgroundService.stop(this)
        ApiServerManager.stop()
        super.onTerminate()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.mewmix.nabu.uiagent.ActionRequestDispatcher.onTrimMemory(level)
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var phonemeConverter: PhonemeConverter
    private lateinit var userPreferencesRepository: UserPreferencesRepository
    private val requestedScreen = mutableStateOf<Screen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Logger initialized in Application
        enableEdgeToEdge()
        userPreferencesRepository = UserPreferencesRepository(this)
        com.mewmix.nabu.utils.MigrationUtils.migrateLegacyKokoro(this)

        val startScreen = handleStartIntent(intent)
        if (savedInstanceState == null) {
            requestedScreen.value = startScreen
        }

        setContent {
            NabuTheme {
                LaunchedEffect(Unit) {
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                }
                val context = LocalContext.current
                var needsInit by remember { mutableStateOf(!SettingsManager.isInitComplete(context)) }
                var needsPermissionReview by remember {
                    mutableStateOf(
                        SettingsManager.isInitComplete(context) &&
                            !SettingsManager.isOptionalPermissionsReviewed(context)
                    )
                }

                if (needsInit) {
                    InitScreen(
                        userPreferencesRepository = userPreferencesRepository,
                        onComplete = {
                            needsInit = false
                            needsPermissionReview = !SettingsManager.isOptionalPermissionsReviewed(context)
                        }
                    )
                } else if (needsPermissionReview) {
                    OptionalPermissionsScreen(
                        onContinue = {
                            SettingsManager.setOptionalPermissionsReviewed(context, true)
                            needsPermissionReview = false
                        }
                    )
                } else {
                    val viewModel: GlobalRuntimeViewModel = viewModel()
                    val ttsWorkbenchViewModel: TtsWorkbenchViewModel = viewModel()
                    MainScreen(
                        viewModel = viewModel,
                        ttsWorkbenchViewModel = ttsWorkbenchViewModel,
                        phonemeConverter = phonemeConverter,
                        userPreferencesRepository = userPreferencesRepository,
                        initialScreen = startScreen,
                        requestedScreen = requestedScreen.value,
                        onRequestedScreenHandled = { requestedScreen.value = null },
                        onThemeChanged = { recreate() }
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
        // Stop method tracing if running
        com.mewmix.nabu.utils.MethodTraceManager.stop()
    }

    private fun handleStartIntent(intent: Intent?): Screen {
        val requested = intent?.getStringExtra(EXTRA_START_SCREEN)
        val screen = screenFromString(requested ?: SettingsManager.getLastMainScreen(this))
        val bookUri = intent?.getStringExtra(EXTRA_BOOK_URI)
        if (!bookUri.isNullOrBlank()) {
            SettingsManager.setLastBookUri(this, bookUri)
        }
        return screen
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
    "VoiceLab" -> Screen.Basic
    "DebugLog" -> Screen.DebugLog
    "Credits" -> Screen.Credits
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

@Composable
fun MainScreen(
    viewModel: GlobalRuntimeViewModel,
    ttsWorkbenchViewModel: TtsWorkbenchViewModel,
    phonemeConverter: PhonemeConverter,
    userPreferencesRepository: UserPreferencesRepository,
    initialScreen: Screen = Screen.Basic,
    requestedScreen: Screen? = null,
    onRequestedScreenHandled: () -> Unit = {},
    onThemeChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val styleLoader = remember(context) { StyleLoader(context) }
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
    val navigateTo: (Screen) -> Unit = { screen ->
        if (screenStack.lastOrNull() != screen) {
            screenStack.add(screen)
            SettingsManager.setLastMainScreen(context, screenToString(screen))
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
            SettingsManager.setLastMainScreen(context, screenToString(screenStack.last()))
        }
    }
    
    // Collect Global State
    val modelState by viewModel.modelState.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val benchmarkStats by viewModel.benchmarkStats.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
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
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Global Status Bar (Shows Loading / Errors / Benchmarks)
            GlobalStatusBar(
                modelState = modelState,
                downloadProgress = downloadProgress,
                benchmarkStats = benchmarkStats
            )
            
            Box(
                modifier = Modifier
                    .weight(1f)
            ) {
                when (currentScreen) {
                    Screen.Basic -> AudioScreen(ttsWorkbenchViewModel)
                    Screen.Mixer -> MixerScreen(
                        styleLoader = styleLoader,
                        workbench = ttsWorkbenchViewModel,
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
                    Screen.Settings -> SettingsScreen(
                        onRuntimeSettingsChanged = {
                            viewModel.retryInitialization()
                            ttsWorkbenchViewModel.refreshCatalog()
                        },
                        onThemeChanged = onThemeChanged
                    )
                    Screen.Models -> ModelsScreen(
                        userPreferencesRepository = userPreferencesRepository,
                        onModelArtifactsChanged = {
                            viewModel.retryInitialization()
                            ttsWorkbenchViewModel.refreshCatalog()
                        }
                    )
                    Screen.Credits -> CreditsConstellationScreen()
                    Screen.DebugLog -> DebugLogScreen()
                }
            }
        }
    }
}

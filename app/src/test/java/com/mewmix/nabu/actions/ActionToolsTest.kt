package com.mewmix.nabu.actions

import android.content.Intent
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.mewmix.nabu.tools.ToolCall
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActionToolsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AlarmTimerAction.resetForTesting()
        DeviceAction.resetForTesting()
        WeatherAction.resetForTesting()
        WebActionReasoner.resetForTesting()
        ScheduledActionScheduler.workEnqueuer = { _, _, _ -> }
        ScheduledActionScheduler.alarmScheduler = { _, _ -> }
        ScheduledActionScheduler.inProcessScheduler = { _, _, _ -> }
    }

    @After
    fun tearDown() {
        AlarmTimerAction.resetForTesting()
        DeviceAction.resetForTesting()
        WeatherAction.resetForTesting()
        WebActionReasoner.resetForTesting()
        ScheduledActionStore.list(context).forEach { ScheduledActionStore.remove(context, it.id) }
        ScheduledActionScheduler.workEnqueuer = { testContext, uniqueName, request ->
            androidx.work.WorkManager.getInstance(testContext.applicationContext)
                .enqueueUniqueWork(uniqueName, androidx.work.ExistingWorkPolicy.REPLACE, request)
        }
        ScheduledActionScheduler.alarmScheduler = { _, _ -> }
        ScheduledActionScheduler.inProcessScheduler = { _, _, _ -> }
    }

    @Test
    fun execute_setTimerRejectsFractionalSeconds() {
        val result = ActionTools.execute(
            context,
            ToolCall("set_timer", mapOf("seconds" to 1.5, "message" to "Tea"))
        )

        assertTrue(result?.isError == true)
        assertEquals("Invalid seconds format.", result?.output)
    }

    @Test
    fun tools_containsExpectedToolSurface() {
        val toolNames = ActionTools.tools.map { it.name }.toSet()

        assertTrue(toolNames.containsAll(setOf(
            "schedule_action",
            "schedule_fuzzy_action",
            "list_scheduled_actions",
            "search_web_context",
            "get_current_time",
            "get_weather",
            "save_memory",
            "retrieve_memory",
            "set_alarm",
            "set_timer",
            "open_app",
            "launch_package",
            "open_url",
            "send_sms",
            "place_call",
            "set_brightness",
            "toggle_flashlight",
            "set_volume",
            "mute",
            "play_media",
            "pause_media",
            "next_track",
            "create_calendar_event",
            "navigate_to",
            "take_photo",
            "record_video",
            "toggle_wifi",
            "toggle_bluetooth",
            "share_text"
        )))
    }

    @Test
    fun execute_setAlarmPropagatesRangeErrors() {
        val result = ActionTools.execute(
            context,
            ToolCall("set_alarm", mapOf("hour" to 25, "minute" to 0, "message" to "Nope"))
        )

        assertTrue(result?.isError == true)
        assertEquals("Invalid alarm time. Hour must be 0-23 and minute 0-59.", result?.output)
    }

    @Test
    fun execute_getCurrentTimeReturnsTimestamp() {
        val result = ActionTools.execute(context, ToolCall("get_current_time", emptyMap()))

        assertFalse(result?.isError ?: true)
        assertTrue(result?.output?.startsWith("Current local time is: ") == true)
    }

    @Test
    fun execute_saveAndRetrieveMemoryRoundTrip() {
        val saveResult = ActionTools.execute(
            context,
            ToolCall("save_memory", mapOf("fact" to "prefers concise responses"))
        )
        val retrieveResult = ActionTools.execute(
            context,
            ToolCall("retrieve_memory", emptyMap())
        )

        assertFalse(saveResult?.isError ?: true)
        assertFalse(retrieveResult?.isError ?: true)
        assertTrue(retrieveResult?.output?.contains("prefers concise responses") == true)
    }

    @Test
    fun execute_getWeatherMarksFetcherFailureAsError() {
        WeatherAction.jsonFetcher = { null }

        val result = ActionTools.execute(
            context,
            ToolCall("get_weather", mapOf("location" to "Seattle"))
        )

        assertTrue(result?.isError == true)
        assertEquals("Failed to geocode location.", result?.output)
    }

    @Test
    fun execute_searchWebContextMarksFetcherFailureAsError() {
        WebActionReasoner.searchPageFetcher = {
            WebActionReasoner.SearchPageResult(errorMessage = "Web search failed: timeout")
        }

        val result = ActionTools.execute(
            context,
            ToolCall("search_web_context", mapOf("query" to "latest weather radar"))
        )

        assertTrue(result?.isError == true)
        assertEquals("Web search failed: timeout", result?.output)
    }

    @Test
    fun execute_searchWebContextReturnsSummarizedHits() {
        WebActionReasoner.searchPageFetcher = {
            WebActionReasoner.SearchPageResult(
                html = """
                    <html><body>
                      <div class="result">
                        <a class="result__a" href="https://example.com/forecast">Forecast</a>
                        <a class="result__snippet">Sunny all week</a>
                      </div>
                    </body></html>
                """.trimIndent()
            )
        }

        val result = ActionTools.execute(
            context,
            ToolCall("search_web_context", mapOf("query" to "forecast"))
        )

        assertFalse(result?.isError ?: true)
        assertEquals("- Forecast: Sunny all week (https://example.com/forecast)", result?.output)
    }

    @Test
    fun execute_scheduleActionRejectsUnsafeTool() {
        val result = ActionTools.execute(
            context,
            ToolCall(
                "schedule_action",
                mapOf(
                    "run_at_local" to "2030-01-01 12:00",
                    "tool_name" to "schedule_action",
                    "tool_arguments" to mapOf("instruction" to "loop")
                )
            )
        )

        assertTrue(result?.isError == true)
        assertTrue(result?.output?.contains("cannot be scheduled from background execution") == true)
    }

    @Test
    fun execute_scheduleActionAcceptsDeferredToolCall() {
        val result = ActionTools.execute(
            context,
            ToolCall(
                "schedule_action",
                mapOf(
                    "title" to "Weather later",
                    "run_at_local" to "2030-01-01 12:00",
                    "tool_name" to "get_weather",
                    "tool_arguments" to mapOf("location" to "Seattle")
                )
            )
        )

        assertFalse(result?.isError ?: true)
        assertTrue(result?.output?.contains("tool=get_weather") == true)
    }

    @Test
    fun execute_scheduleActionAcceptsDeferredSmsComposer() {
        val result = ActionTools.execute(
            context,
            ToolCall(
                "schedule_action",
                mapOf(
                    "title" to "Text later",
                    "delay_seconds" to 300,
                    "tool_name" to "send_sms",
                    "tool_arguments" to mapOf(
                        "phone_number" to "1234567890",
                        "message" to "five minutes is up"
                    )
                )
            )
        )

        assertFalse(result?.isError ?: true)
        assertTrue(result?.output?.contains("tool=send_sms") == true)
    }

    @Test
    fun execute_scheduleActionAcceptsRelativeDelaySeconds() {
        val before = System.currentTimeMillis()
        var inProcessDelayMs: Long? = null
        ScheduledActionScheduler.inProcessScheduler = { _, action, delayMs ->
            if (action.title == "Flashlight off") {
                inProcessDelayMs = delayMs
            }
        }
        val result = ActionTools.execute(
            context,
            ToolCall(
                "schedule_action",
                mapOf(
                    "title" to "Flashlight off",
                    "delay_seconds" to 10,
                    "tool_name" to "toggle_flashlight",
                    "tool_arguments" to mapOf("enabled" to false)
                )
            )
        )

        assertFalse(result?.isError ?: true)
        val stored = ScheduledActionStore.list(context).firstOrNull { it.title == "Flashlight off" }
        assertNotNull(stored)
        assertTrue((stored?.triggerAtEpochMs ?: 0L) >= before + 10_000L)
        assertEquals("toggle_flashlight", stored?.effectiveSteps()?.firstOrNull()?.toolName)
        assertNotNull(inProcessDelayMs)
        assertTrue((inProcessDelayMs ?: 0L) in 1L..10_000L)
    }

    @Test
    fun execute_scheduleActionAcceptsSecondPrecisionLocalTime() {
        val result = ActionTools.execute(
            context,
            ToolCall(
                "schedule_action",
                mapOf(
                    "title" to "Weather later",
                    "run_at_local" to "2030-01-01 12:00:15",
                    "tool_name" to "get_weather",
                    "tool_arguments" to mapOf("location" to "Seattle")
                )
            )
        )

        assertFalse(result?.isError ?: true)
        assertTrue(result?.output?.contains("2030-01-01T12:00:15") == true)
    }

    @Test
    fun execute_scheduleActionAcceptsBackgroundSafeDeviceTool() {
        val result = ActionTools.execute(
            context,
            ToolCall(
                "schedule_action",
                mapOf(
                    "title" to "Mute later",
                    "run_at_local" to "2030-01-01 12:00",
                    "tool_name" to "mute",
                    "tool_arguments" to mapOf("enabled" to true)
                )
            )
        )

        assertFalse(result?.isError ?: true)
        assertTrue(result?.output?.contains("tool=mute") == true)
    }

    @Test
    fun execute_scheduleActionAcceptsStepSeries() {
        val result = ActionTools.execute(
            context,
            ToolCall(
                "schedule_action",
                mapOf(
                    "title" to "Morning context",
                    "instruction" to "Prepare morning context.",
                    "run_at_local" to "2030-01-01 12:00",
                    "steps" to listOf(
                        mapOf(
                            "title" to "Check weather",
                            "tool_name" to "get_weather",
                            "tool_arguments" to mapOf("location" to "Seattle")
                        ),
                        mapOf(
                            "title" to "Remember run",
                            "tool_name" to "save_memory",
                            "tool_arguments" to mapOf("fact" to "morning context ran"),
                            "continue_on_error" to true
                        )
                    )
                )
            )
        )

        assertFalse(result?.isError ?: true)
        assertTrue(result?.output?.contains("steps=2") == true)
        val stored = ScheduledActionStore.list(context).firstOrNull { it.title == "Morning context" }
        assertNotNull(stored)
        assertEquals(2, stored?.effectiveSteps()?.size)
    }

    @Test
    fun execute_scheduleActionAcceptsAgentStep() {
        val result = ActionTools.execute(
            context,
            ToolCall(
                "schedule_action",
                mapOf(
                    "title" to "Agent check",
                    "run_at_local" to "2030-01-01 12:00",
                    "steps" to listOf(
                        mapOf(
                            "title" to "Reason with tools",
                            "tool_name" to ActionTools.SCHEDULED_AGENT_STEP_TOOL,
                            "tool_arguments" to mapOf(
                                "instruction" to "Check the weather and summarize it.",
                                "model_id" to "local-test-model",
                                "max_tool_calls" to 2
                            )
                        )
                    )
                )
            )
        )

        assertFalse(result?.isError ?: true)
        assertTrue(result?.output?.contains("tool=${ActionTools.SCHEDULED_AGENT_STEP_TOOL}") == true)
    }

    @Test
    fun execute_scheduleActionRejectsInteractiveIntentTool() {
        val result = ActionTools.execute(
            context,
            ToolCall(
                "schedule_action",
                mapOf(
                    "title" to "Open site later",
                    "run_at_local" to "2030-01-01 12:00",
                    "tool_name" to "open_url",
                    "tool_arguments" to mapOf("url" to "https://example.com")
                )
            )
        )

        assertTrue(result?.isError == true)
        assertTrue(result?.output?.contains("cannot be scheduled from background execution") == true)
    }

    @Test
    fun execute_scheduleFuzzyActionRequiresRequest() {
        val result = ActionTools.execute(
            context,
            ToolCall("schedule_fuzzy_action", emptyMap())
        )

        assertTrue(result?.isError == true)
        assertEquals("Missing required parameter: request", result?.output)
    }

    @Test
    fun execute_listScheduledActionsHandlesEmptyState() {
        val result = ActionTools.execute(
            context,
            ToolCall("list_scheduled_actions", emptyMap())
        )

        assertFalse(result?.isError ?: true)
        assertEquals("No scheduled actions.", result?.output)
    }

    @Test
    fun execute_openUrlLaunchesNormalizedUrl() {
        DeviceAction.canResolveIntent = { _, _ -> true }
        var launchedIntent: Intent? = null
        DeviceAction.activityLauncher = { _, intent ->
            launchedIntent = intent
        }

        val result = ActionTools.execute(
            context,
            ToolCall("open_url", mapOf("url" to "example.com"))
        )

        assertFalse(result?.isError ?: true)
        assertEquals("Opened https://example.com.", result?.output)
        assertEquals("https://example.com", launchedIntent?.dataString)
    }

    @Test
    fun execute_openAppLaunchesResolvedPackage() {
        DeviceAction.launchIntentForPackageResolver = { _, packageName ->
            Intent(Intent.ACTION_MAIN).setPackage(packageName)
        }
        DeviceAction.canResolveIntent = { _, _ -> true }
        DeviceAction.appLabelLoader = { _, _ -> "Spotify" }
        var launchedIntent: Intent? = null
        DeviceAction.activityLauncher = { _, intent -> launchedIntent = intent }

        val result = ActionTools.execute(
            context,
            ToolCall("open_app", mapOf("package_name" to "com.spotify.music"))
        )

        assertFalse(result?.isError ?: true)
        assertEquals("Opened Spotify.", result?.output)
        assertEquals("com.spotify.music", launchedIntent?.`package`)
    }

    @Test
    fun execute_launchPackageCanResolveByAppName() {
        DeviceAction.appNameResolver = { _, _ -> "com.google.android.youtube" }
        DeviceAction.launchIntentForPackageResolver = { _, packageName ->
            Intent(Intent.ACTION_MAIN).setPackage(packageName)
        }
        DeviceAction.canResolveIntent = { _, _ -> true }
        var launchedIntent: Intent? = null
        DeviceAction.activityLauncher = { _, intent -> launchedIntent = intent }

        val result = ActionTools.execute(
            context,
            ToolCall("launch_package", mapOf("app_name" to "YouTube"))
        )

        assertFalse(result?.isError ?: true)
        assertEquals("com.google.android.youtube", launchedIntent?.`package`)
    }

    @Test
    fun execute_sendSmsOpensComposer() {
        DeviceAction.canResolveIntent = { _, _ -> true }
        var launchedIntent: Intent? = null
        DeviceAction.activityLauncher = { _, intent -> launchedIntent = intent }

        val result = ActionTools.execute(
            context,
            ToolCall("send_sms", mapOf("phone_number" to "1234567890", "message" to "hello"))
        )

        assertFalse(result?.isError ?: true)
        assertEquals(Intent.ACTION_SENDTO, launchedIntent?.action)
        assertTrue(launchedIntent?.dataString?.startsWith("smsto:") == true)
    }

    @Test
    fun execute_placeCallOpensDialer() {
        DeviceAction.canResolveIntent = { _, _ -> true }
        var launchedIntent: Intent? = null
        DeviceAction.activityLauncher = { _, intent -> launchedIntent = intent }

        val result = ActionTools.execute(
            context,
            ToolCall("place_call", mapOf("phone_number" to "1234567890"))
        )

        assertFalse(result?.isError ?: true)
        assertEquals(Intent.ACTION_DIAL, launchedIntent?.action)
    }

    @Test
    fun execute_setBrightnessReturnsErrorWhenPermissionMissing() {
        DeviceAction.brightnessSetter = { _, _ -> false }

        val result = ActionTools.execute(
            context,
            ToolCall("set_brightness", mapOf("level" to 40))
        )

        assertTrue(result?.isError == true)
        assertEquals(
            "Nabu cannot change system brightness unless write-settings access is already granted.",
            result?.output
        )
    }

    @Test
    fun execute_toggleFlashlightUsesTorchController() {
        var requestedState: Boolean? = null
        DeviceAction.torchController = { _, enabled -> requestedState = enabled }

        val result = ActionTools.execute(
            context,
            ToolCall("toggle_flashlight", mapOf("enabled" to false))
        )

        assertFalse(result?.isError ?: true)
        assertEquals(false, requestedState)
        assertEquals("Flashlight turned off.", result?.output)
    }

    @Test
    fun execute_setVolumeRejectsOutOfRangeLevel() {
        val result = ActionTools.execute(
            context,
            ToolCall("set_volume", mapOf("level" to 101))
        )

        assertTrue(result?.isError == true)
        assertEquals("Invalid volume level. Use 0-100.", result?.output)
    }

    @Test
    fun execute_muteAcceptsBooleanArgument() {
        val result = ActionTools.execute(
            context,
            ToolCall("mute", mapOf("enabled" to true))
        )

        assertNotNull(result)
        assertFalse(result?.isError ?: true)
    }

    @Test
    fun execute_mediaCommandsDispatchKeyEvents() {
        val dispatched = mutableListOf<Int>()
        DeviceAction.mediaKeyDispatcher = { _, keyCode -> dispatched += keyCode }

        val playResult = ActionTools.execute(context, ToolCall("play_media", emptyMap()))
        val pauseResult = ActionTools.execute(context, ToolCall("pause_media", emptyMap()))
        val nextResult = ActionTools.execute(context, ToolCall("next_track", emptyMap()))

        assertFalse(playResult?.isError ?: true)
        assertFalse(pauseResult?.isError ?: true)
        assertFalse(nextResult?.isError ?: true)
        assertEquals(listOf(
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT
        ), dispatched)
    }

    @Test
    fun execute_createCalendarEventRejectsBadDates() {
        val result = ActionTools.execute(
            context,
            ToolCall(
                "create_calendar_event",
                mapOf("title" to "Meet", "start_local" to "bad-date")
            )
        )

        assertTrue(result?.isError == true)
        assertEquals("Invalid start_local format. Use yyyy-MM-dd HH:mm", result?.output)
    }

    @Test
    fun execute_navigateToOpensGeoIntent() {
        DeviceAction.canResolveIntent = { _, _ -> true }
        var launchedIntent: Intent? = null
        DeviceAction.activityLauncher = { _, intent -> launchedIntent = intent }

        val result = ActionTools.execute(
            context,
            ToolCall("navigate_to", mapOf("destination" to "1600 Amphitheatre Parkway"))
        )

        assertFalse(result?.isError ?: true)
        assertEquals(Intent.ACTION_VIEW, launchedIntent?.action)
        assertTrue(launchedIntent?.dataString?.startsWith("geo:0,0?q=") == true)
    }

    @Test
    fun execute_takePhotoAndRecordVideoLaunchCameraIntents() {
        DeviceAction.canResolveIntent = { _, _ -> true }
        val actions = mutableListOf<String?>()
        DeviceAction.activityLauncher = { _, intent -> actions += intent.action }

        val photoResult = ActionTools.execute(context, ToolCall("take_photo", emptyMap()))
        val videoResult = ActionTools.execute(context, ToolCall("record_video", emptyMap()))

        assertFalse(photoResult?.isError ?: true)
        assertFalse(videoResult?.isError ?: true)
        assertEquals(listOf("android.media.action.IMAGE_CAPTURE", "android.media.action.VIDEO_CAPTURE"), actions)
    }

    @Test
    fun execute_toggleWifiExplainsSettingsFallback() {
        DeviceAction.canResolveIntent = { _, _ -> true }
        var launchedIntent: Intent? = null
        DeviceAction.activityLauncher = { _, intent ->
            launchedIntent = intent
        }

        val result = ActionTools.execute(
            context,
            ToolCall("toggle_wifi", mapOf("enabled" to true))
        )

        assertFalse(result?.isError ?: true)
        assertTrue(result?.output?.contains("restricts direct Wi-Fi toggles") == true)
        assertEquals("android.settings.panel.action.WIFI", launchedIntent?.action)
    }

    @Test
    fun execute_toggleBluetoothExplainsSettingsFallback() {
        DeviceAction.canResolveIntent = { _, _ -> true }
        var launchedIntent: Intent? = null
        DeviceAction.activityLauncher = { _, intent -> launchedIntent = intent }

        val result = ActionTools.execute(
            context,
            ToolCall("toggle_bluetooth", mapOf("enabled" to false))
        )

        assertFalse(result?.isError ?: true)
        assertTrue(result?.output?.contains("restricts direct Bluetooth toggles") == true)
        assertNotNull(launchedIntent?.action)
    }

    @Test
    fun execute_toggleBluetoothFallsBackToSettingsWhenPanelUnavailable() {
        DeviceAction.canResolveIntent = { _, intent ->
            intent.action == Settings.ACTION_BLUETOOTH_SETTINGS
        }
        var launchedIntent: Intent? = null
        DeviceAction.activityLauncher = { _, intent -> launchedIntent = intent }

        val result = ActionTools.execute(
            context,
            ToolCall("toggle_bluetooth", mapOf("enabled" to false))
        )

        assertFalse(result?.isError ?: true)
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, launchedIntent?.action)
    }

    @Test
    fun execute_shareTextRequiresText() {
        val result = ActionTools.execute(
            context,
            ToolCall("share_text", emptyMap())
        )

        assertTrue(result?.isError == true)
        assertEquals("Missing required parameter: text", result?.output)
    }

    @Test
    fun execute_shareTextLaunchesChooser() {
        DeviceAction.canResolveIntent = { _, intent ->
            intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_CHOOSER
        }
        var launchedIntent: Intent? = null
        DeviceAction.activityLauncher = { _, intent -> launchedIntent = intent }

        val result = ActionTools.execute(
            context,
            ToolCall("share_text", mapOf("text" to "hello world", "subject" to "greeting"))
        )

        assertFalse(result?.isError ?: true)
        assertEquals(Intent.ACTION_CHOOSER, launchedIntent?.action)
    }
}

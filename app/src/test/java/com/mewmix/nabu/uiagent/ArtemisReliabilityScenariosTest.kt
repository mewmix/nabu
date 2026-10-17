package com.mewmix.nabu.uiagent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mewmix.nabu.accessibility.ActionLeaseValidation
import com.mewmix.nabu.accessibility.ActionObservationAuthority
import com.mewmix.nabu.accessibility.ActionObservationLease
import com.mewmix.nabu.actions.DeviceAction
import com.mewmix.nabu.utils.DatabaseHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Phase 5 repeatable end-to-end reliability scenarios covering verification correctness,
 * observation lease authority, durable SQLite trace persistence, structured recovery,
 * and bounded history compaction using observed Android UI hierarchy fixtures.
 *
 * Note: Live external ARTEMIS MCP host-to-device execution requires an attached emulator/test
 * device and cloud/host MCP credentials, which are not present in the local unit-test environment
 * and are therefore documented as unverified on live hardware.
 */
@RunWith(RobolectricTestRunner::class)
class ArtemisReliabilityScenariosTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var traceStore: AutomationTraceStore

    private val settingsApp = listOf(DeviceAction.AppCandidate("Settings", "com.android.settings"))
    private val clockApp = listOf(DeviceAction.AppCandidate("Clock", "com.google.android.deskclock"))

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.cacheDir, "artemis_scenarios_${System.nanoTime()}.db")
        dbHelper = DatabaseHelper(context, databaseName = dbFile.absolutePath)
        traceStore = AutomationTraceStore(dbHelper)
    }

    @After
    fun tearDown() {
        dbHelper.close()
        dbFile.delete()
    }

    private fun node(
        id: String,
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        pkg: String = "com.android.settings",
        cls: String = "android.widget.TextView",
        clickable: Boolean = true,
        editable: Boolean = false,
        checkable: Boolean = false,
        checked: Boolean = false,
        bounds: UiBounds = UiBounds(0, 100, 1080, 220)
    ): UiElement = UiElement(
        id = id,
        text = text,
        contentDescription = desc,
        resourceId = resId,
        className = cls,
        packageName = pkg,
        bounds = bounds,
        clickable = clickable,
        enabled = true,
        visible = true,
        editable = editable,
        scrollable = false,
        longClickable = false,
        checkable = checkable,
        checked = checked,
        password = false,
        parentId = null,
        treePath = "0/$id",
        focusable = clickable || editable,
        standardActions = buildSet {
            if (clickable) add("click")
            if (editable) add("set_text")
        }
    )

    @Test
    fun `scenario 1 - navigation to the correct destination verifies only at target screen`() {
        val sessionId = "scenario_1_correct_dest"
        val goal = "Open Wi-Fi in Settings"
        val recorder = AutomationTraceRecorder(sessionId = sessionId, logger = {}, traceStore = traceStore)
        traceStore.startOrUpdateSession(
            sessionId = sessionId,
            rawGoal = goal,
            status = "running",
            verificationStatus = UiVerificationStatus.UNVERIFIABLE
        )

        val launcherScreen = UiScreenState(
            screenId = "fp_launcher",
            packageName = "com.google.android.apps.nexuslauncher",
            activityName = ".NexusLauncherActivity",
            elements = listOf(node("n1", text = "Settings", pkg = "com.google.android.apps.nexuslauncher"))
        )
        val settingsRootScreen = UiScreenState(
            screenId = "fp_settings_root",
            packageName = "com.android.settings",
            activityName = ".Settings",
            elements = listOf(
                node("n2", text = "Connections", resId = "android:id/title"),
                node("n3", text = "Apps", resId = "android:id/title")
            )
        )
        val wifiScreen = UiScreenState(
            screenId = "fp_wifi_settings",
            packageName = "com.android.settings",
            activityName = ".wifi.WifiSettingsActivity",
            elements = listOf(
                node("n10", text = "Wi-Fi", resId = "com.android.settings:id/collapsing_toolbar"),
                node("n11", text = "Use Wi-Fi", resId = "android:id/switch_widget", checkable = true, checked = true)
            )
        )

        // Step 1: Open Settings root -> STATE_CHANGED (not yet at Wi-Fi sub-screen)
        val step1Action = UiActionStep.OpenApp("com.android.settings")
        val step1Verify = UiGoalVerifier.verify(
            goal = goal,
            action = step1Action,
            before = launcherScreen,
            after = settingsRootScreen,
            transportStatus = UiTransportStatus.ACCEPTED,
            goalAppCandidates = settingsApp
        )
        assertEquals(UiVerificationStatus.STATE_CHANGED, step1Verify.status)
        assertFalse(step1Verify.isGoalVerified)
        recorder.recordStep(
            ActionStepRecord(
                sequence = 1,
                observation = launcherScreen.screenId,
                reasoningSummary = "Open Settings app",
                action = "open_app",
                target = "com.android.settings",
                result = "succeeded",
                postActionObservation = settingsRootScreen.screenId,
                verification = step1Verify.summary,
                latencyMs = 120L,
                sourcePackage = launcherScreen.packageName,
                resultPackage = settingsRootScreen.packageName,
                verificationStatus = step1Verify.status.name.lowercase(),
                goalVerificationStatus = step1Verify.status.name.lowercase(),
                evidenceCategory = step1Verify.evidenceCategory,
                checkpointId = step1Verify.checkpointId,
                sessionId = sessionId,
                observationId = "obs_1"
            ),
            rawGoalForInit = goal
        )

        // Step 2: Tap Connections -> reaches Wi-Fi screen -> GOAL_VERIFIED
        val step2Action = UiActionStep.Tap(UiTarget("n2", null))
        val step2Post = UiActionPostconditionVerifier.verify(step2Action, settingsRootScreen, wifiScreen)
        val step2Verify = UiGoalVerifier.verify(
            goal = goal,
            action = step2Action,
            before = settingsRootScreen,
            after = wifiScreen,
            transportStatus = UiTransportStatus.ACCEPTED,
            postcondition = step2Post,
            goalAppCandidates = settingsApp
        )
        assertEquals(UiVerificationStatus.GOAL_VERIFIED, step2Verify.status)
        assertTrue(step2Verify.isGoalVerified)
        recorder.recordStep(
            ActionStepRecord(
                sequence = 2,
                observation = settingsRootScreen.screenId,
                reasoningSummary = "Tap Connections",
                action = "tap",
                target = "n2",
                result = "succeeded",
                postActionObservation = wifiScreen.screenId,
                verification = step2Verify.summary,
                latencyMs = 95L,
                sourcePackage = settingsRootScreen.packageName,
                resultPackage = wifiScreen.packageName,
                verificationStatus = step2Verify.status.name.lowercase(),
                goalVerificationStatus = step2Verify.status.name.lowercase(),
                evidenceCategory = step2Verify.evidenceCategory,
                checkpointId = step2Verify.checkpointId,
                sessionId = sessionId,
                observationId = "obs_2"
            ),
            rawGoalForInit = goal
        )
        traceStore.finishSession(sessionId, "completed", UiVerificationStatus.GOAL_VERIFIED, step2Verify.summary)

        val replay = traceStore.inspectSession(sessionId)!!.replayReport
        assertEquals(0, replay.liveUiActionsExecuted)
        assertTrue(replay.integrityVerified)
        assertEquals(UiVerificationStatus.GOAL_VERIFIED, replay.reconstructedVerificationStatus)
    }

    @Test
    fun `scenario 2 - incorrect screen transition never produces GOAL_VERIFIED`() {
        val goal = "Open Bluetooth in Settings"
        val settingsRoot = UiScreenState(
            screenId = "fp_settings_root",
            packageName = "com.android.settings",
            activityName = ".Settings",
            elements = listOf(node("n1", text = "Display", resId = "android:id/title"))
        )
        val displayScreen = UiScreenState(
            screenId = "fp_display_settings",
            packageName = "com.android.settings",
            activityName = ".DisplaySettings",
            elements = listOf(
                node("n2", text = "Brightness level", resId = "android:id/title"),
                node("n3", text = "Dark theme", resId = "android:id/title")
            )
        )
        val wrongPackageScreen = UiScreenState(
            screenId = "fp_calculator",
            packageName = "com.google.android.calculator",
            activityName = ".Calculator",
            elements = listOf(node("n_eq", text = "=", pkg = "com.google.android.calculator"))
        )

        val tapAction = UiActionStep.Tap(UiTarget("n1", null))
        val post = UiActionPostconditionVerifier.verify(tapAction, settingsRoot, displayScreen)
        assertEquals(UiVerificationStatus.STATE_CHANGED, post.verificationStatus)

        val goalVerify = UiGoalVerifier.verify(
            goal = goal,
            action = tapAction,
            before = settingsRoot,
            after = displayScreen,
            transportStatus = UiTransportStatus.ACCEPTED,
            postcondition = post,
            goalAppCandidates = settingsApp
        )
        assertFalse(goalVerify.isGoalVerified)
        assertEquals(UiVerificationStatus.STATE_CHANGED, goalVerify.status)

        // Even if the model claims Done on the wrong sub-screen, verifyTerminalDone rejects completion
        val terminalDoneWrongSubScreen = UiGoalVerifier.verifyTerminalDone(
            goal = goal,
            currentScreen = displayScreen,
            actionHistory = emptyList(),
            goalAppCandidates = settingsApp
        )
        assertFalse(terminalDoneWrongSubScreen.isGoalVerified)
        assertEquals(UiVerificationStatus.STATE_CHANGED, terminalDoneWrongSubScreen.status)

        // And if the model claims Done on a wrong package altogether, verifyTerminalDone returns FAILED
        val terminalDoneWrongPackage = UiGoalVerifier.verifyTerminalDone(
            goal = goal,
            currentScreen = wrongPackageScreen,
            actionHistory = emptyList(),
            goalAppCandidates = emptyList()
        )
        assertFalse(terminalDoneWrongPackage.isGoalVerified)
        assertEquals(UiVerificationStatus.FAILED, terminalDoneWrongPackage.status)
    }

    @Test
    fun `scenario 3 - notification shade versus Quick Settings requires specific surface evidence`() {
        val notificationShadeOnly = UiScreenState(
            screenId = "fp_notif_shade",
            packageName = "com.android.systemui",
            activityName = "com.android.systemui.shade.NotificationShadeWindowView",
            elements = listOf(
                node(
                    id = "n_stack",
                    text = "Notifications",
                    resId = "com.android.systemui:id/notification_stack_scroller",
                    pkg = "com.android.systemui"
                ),
                node(
                    id = "n_clear",
                    text = "Clear all",
                    resId = "com.android.systemui:id/dismiss_text",
                    pkg = "com.android.systemui"
                )
            )
        )
        val quickSettingsPanel = UiScreenState(
            screenId = "fp_qs_panel",
            packageName = "com.android.systemui",
            activityName = "com.android.systemui.qs.QSFragment",
            elements = listOf(
                node(
                    id = "n_qs_container",
                    desc = "Quick settings",
                    resId = "com.android.systemui:id/quick_settings_panel",
                    pkg = "com.android.systemui"
                ),
                node(id = "n_tile_wifi", text = "Internet", desc = "Wi-Fi tile", pkg = "com.android.systemui"),
                node(id = "n_tile_bt", text = "Bluetooth", desc = "Bluetooth tile", pkg = "com.android.systemui"),
                node(id = "n_tile_flash", text = "Flashlight", desc = "Flashlight tile", pkg = "com.android.systemui"),
                node(
                    id = "n_brightness",
                    desc = "Brightness",
                    resId = "com.android.systemui:id/brightness_slider",
                    pkg = "com.android.systemui"
                )
            )
        )

        // Notification shade does NOT satisfy OpenQuickSettings
        val qsOnShade = UiGoalVerifier.verify(
            goal = "Open Quick Settings",
            action = UiActionStep.OpenQuickSettings,
            before = null,
            after = notificationShadeOnly,
            transportStatus = UiTransportStatus.ACCEPTED
        )
        assertFalse(qsOnShade.isGoalVerified)
        assertEquals(UiVerificationStatus.FAILED, qsOnShade.status)

        // Notification shade DOES satisfy OpenNotifications
        val notifOnShade = UiGoalVerifier.verify(
            goal = "Open Notification Shade",
            action = UiActionStep.OpenNotifications,
            before = null,
            after = notificationShadeOnly,
            transportStatus = UiTransportStatus.ACCEPTED
        )
        assertTrue(notifOnShade.isGoalVerified)
        assertEquals(UiVerificationStatus.GOAL_VERIFIED, notifOnShade.status)

        // Quick Settings panel DOES satisfy OpenQuickSettings
        val qsOnQs = UiGoalVerifier.verify(
            goal = "Open Quick Settings",
            action = UiActionStep.OpenQuickSettings,
            before = notificationShadeOnly,
            after = quickSettingsPanel,
            transportStatus = UiTransportStatus.ACCEPTED
        )
        assertTrue(qsOnQs.isGoalVerified)
        assertEquals(UiVerificationStatus.GOAL_VERIFIED, qsOnQs.status)
    }

    @Test
    fun `scenario 4 - target disappearance after observation rejects stale lease and recovers deterministically`() {
        val observedAuthority = ActionObservationAuthority(
            observationId = "obs_10",
            packageName = "com.android.settings",
            windowId = 4,
            stateFingerprint = "fp_before_disappear",
            rotation = 0,
            displayWidth = 1080,
            displayHeight = 2400
        )
        val lease = ActionObservationLease().apply { bind(observedAuthority) }

        // Screen drifts before action dispatch: target element e_hotspot disappears
        lease.invalidateIfDrifted(
            observedAuthority.copy(observationId = "obs_event_11", stateFingerprint = "fp_after_disappear")
        )
        val leaseOutcome = lease.consume(observedAuthority)
        assertTrue(leaseOutcome is ActionLeaseValidation.Rejected)

        val beforeScreen = UiScreenState(
            screenId = "fp_before_disappear",
            packageName = "com.android.settings",
            activityName = ".Settings",
            elements = listOf(node("e_hotspot", text = "Hotspot & tethering", resId = "android:id/title"))
        )
        val driftedScreenWithoutTarget = UiScreenState(
            screenId = "fp_after_disappear",
            packageName = "com.android.settings",
            activityName = ".Settings",
            elements = listOf(node("e_battery", text = "Battery", resId = "android:id/title"))
        )

        val tracker = UiRecoveryTracker()
        val decision = tracker.recordAndDecide(
            type = UiExecutionIncidentType.TARGET_NOT_FOUND,
            stepSequence = 1,
            action = UiActionStep.Tap(UiTarget("e_hotspot", null)),
            goal = "Open Hotspot & tethering",
            detail = "Target e_hotspot disappeared after observation.",
            beforeScreen = beforeScreen,
            currentScreen = driftedScreenWithoutTarget
        )

        assertTrue(decision is UiRecoveryDecision.ReplanWithEvidence)
        assertEquals(1, tracker.activeIncidents.size)
        assertEquals(UiExecutionIncidentType.TARGET_NOT_FOUND, tracker.activeIncidents.single().type)
    }

    @Test
    fun `scenario 5 - interrupted session and process restart recovers consistent trace history`() {
        val sessionId = "scenario_5_restart"
        val goal = "Open Sound settings"
        traceStore.startOrUpdateSession(
            sessionId = sessionId,
            rawGoal = goal,
            status = "running",
            verificationStatus = UiVerificationStatus.STATE_CHANGED
        )
        val recorder = AutomationTraceRecorder(sessionId = sessionId, logger = {}, traceStore = traceStore)

        recorder.recordStep(
            ActionStepRecord(
                sequence = 1,
                observation = "fp_home",
                reasoningSummary = "Launch Settings",
                action = "open_app",
                target = "com.android.settings",
                result = "succeeded",
                postActionObservation = "fp_settings_main",
                verification = "Opened Settings",
                latencyMs = 110L,
                sourcePackage = "com.google.android.apps.nexuslauncher",
                resultPackage = "com.android.settings",
                verificationStatus = UiVerificationStatus.STATE_CHANGED.name.lowercase(),
                goalVerificationStatus = UiVerificationStatus.STATE_CHANGED.name.lowercase(),
                sessionId = sessionId,
                observationId = "obs_s5_1"
            ),
            rawGoalForInit = goal
        )

        // Simulate process restart with a fresh DatabaseHelper and AutomationTraceStore on the same file
        val restartedDb = DatabaseHelper(context, databaseName = dbFile.absolutePath)
        try {
            val restartedStore = AutomationTraceStore(restartedDb)
            val recoveredSessions = restartedStore.recoverAfterProcessRestart()
            assertEquals(1, recoveredSessions.size)

            val inspected = restartedStore.inspectSession(sessionId)
            assertNotNull(inspected)
            assertEquals("interrupted", inspected!!.session.status)
            assertEquals("process_restart", inspected.session.terminationReason)
            assertEquals(1, inspected.steps.size)
            assertEquals("fp_home", inspected.steps.single().beforeStateFingerprint)
            assertEquals("fp_settings_main", inspected.steps.single().afterStateFingerprint)

            val replay = inspected.replayReport
            assertTrue(replay.integrityVerified)
            assertEquals(0, replay.liveUiActionsExecuted)
            assertEquals(UiVerificationStatus.UNVERIFIABLE, replay.reconstructedVerificationStatus)
        } finally {
            restartedDb.close()
        }
    }

    @Test
    fun `scenario 6 - multiple actions that change UI but do not fulfill goal remain unverified and compact safely`() {
        val sessionId = "scenario_6_multi_unfulfilled"
        val goal = "Turn on Airplane mode in Settings"
        val steps = (1..10).map { seq ->
            val beforeScreen = UiScreenState(
                screenId = "fp_scroll_$seq",
                packageName = "com.android.settings",
                activityName = ".Settings",
                elements = listOf(node("n_$seq", text = "Menu item $seq"))
            )
            val afterScreen = UiScreenState(
                screenId = "fp_scroll_${seq + 1}",
                packageName = "com.android.settings",
                activityName = ".Settings",
                elements = listOf(node("n_${seq + 1}", text = "Menu item ${seq + 1}"))
            )
            val action = UiActionStep.Scroll(ScrollDirection.DOWN, null)
            val post = UiActionPostconditionVerifier.verify(action, beforeScreen, afterScreen)
            val verify = UiGoalVerifier.verify(
                goal = goal,
                action = action,
                before = beforeScreen,
                after = afterScreen,
                transportStatus = UiTransportStatus.ACCEPTED,
                postcondition = post,
                goalAppCandidates = settingsApp
            )
            assertFalse("Step $seq must not be GOAL_VERIFIED", verify.isGoalVerified)
            assertEquals(UiVerificationStatus.STATE_CHANGED, verify.status)

            ActionStepRecord(
                sequence = seq,
                observation = beforeScreen.screenId,
                reasoningSummary = "Scroll down $seq",
                action = "scroll_down",
                target = null,
                result = "succeeded",
                postActionObservation = afterScreen.screenId,
                verification = verify.summary,
                latencyMs = 50L,
                sourcePackage = "com.android.settings",
                resultPackage = "com.android.settings",
                verificationStatus = verify.status.name.lowercase(),
                goalVerificationStatus = verify.status.name.lowercase(),
                sessionId = sessionId,
                observationId = "obs_scroll_$seq"
            )
        }

        val compacted = ActionSessionWorkingMemory.compactStepRecords(
            sessionId = sessionId,
            goal = goal,
            steps = steps,
            rawWindowSize = 6
        )
        assertEquals(6, compacted.recentRawSteps.size)
        assertEquals(1, compacted.compactedSummaries.size)
        val chunkSummary = compacted.compactedSummaries.single()
        assertEquals(UiVerificationStatus.STATE_CHANGED, chunkSummary.highestVerificationStatus)
        assertFalse(chunkSummary.highestVerificationStatus.isGoalVerified)
        assertEquals(4, chunkSummary.unresolvedUncertainties.size)
    }

    @Test
    fun `scenario 7 - cancellation during execution aborts recovery and records cancelled status`() {
        val sessionId = "scenario_7_cancelled"
        val goal = "Open Calculator"
        traceStore.startOrUpdateSession(
            sessionId = sessionId,
            rawGoal = goal,
            status = "running",
            verificationStatus = UiVerificationStatus.UNVERIFIABLE
        )
        val tracker = UiRecoveryTracker()

        val decision = tracker.recordAndDecide(
            type = UiExecutionIncidentType.EXECUTION_TIMEOUT,
            stepSequence = 2,
            action = UiActionStep.Tap(UiTarget("e_calc", null)),
            goal = goal,
            detail = "Cancelled while waiting for action completion",
            isCancelled = true
        )

        assertTrue(decision is UiRecoveryDecision.Abort)
        assertEquals("cancelled", (decision as UiRecoveryDecision.Abort).terminationCode)

        traceStore.finishSession(sessionId, "cancelled", UiVerificationStatus.UNVERIFIABLE, decision.summary)
        val persisted = traceStore.inspectSession(sessionId)!!
        assertEquals("cancelled", persisted.session.status)
        assertEquals(UiVerificationStatus.UNVERIFIABLE, persisted.session.verificationStatus)
    }

    @Test
    fun `scenario 8 - failure recovery followed by genuine success resolves incidents and verifies goal`() {
        val sessionId = "scenario_8_recovery_success"
        val goal = "Open Clock"
        traceStore.startOrUpdateSession(
            sessionId = sessionId,
            rawGoal = goal,
            status = "running",
            verificationStatus = UiVerificationStatus.UNVERIFIABLE
        )
        val recorder = AutomationTraceRecorder(sessionId = sessionId, logger = {}, traceStore = traceStore)
        val tracker = UiRecoveryTracker()

        val launcherV1 = UiScreenState(
            screenId = "fp_launcher_v1",
            packageName = "com.google.android.apps.nexuslauncher",
            activityName = ".NexusLauncherActivity",
            elements = listOf(
                node("e_old_clock", text = "Clock", resId = "com.google.android.apps.nexuslauncher:id/icon_clock")
            )
        )
        val launcherV2 = UiScreenState(
            screenId = "fp_launcher_v2",
            packageName = "com.google.android.apps.nexuslauncher",
            activityName = ".NexusLauncherActivity",
            elements = listOf(
                node(
                    "e_reindexed_clock",
                    text = "Clock",
                    resId = "com.google.android.apps.nexuslauncher:id/icon_clock",
                    bounds = UiBounds(200, 500, 400, 700)
                )
            )
        )
        val clockScreen = UiScreenState(
            screenId = "fp_clock_main",
            packageName = "com.google.android.deskclock",
            activityName = "com.android.deskclock.DeskClock",
            elements = listOf(
                node("e_alarm_tab", text = "Alarm", pkg = "com.google.android.deskclock")
            )
        )

        // Step 1 fails with STALE_OBSERVATION on launcherV1 -> recovered by re-grounding on launcherV2
        val initialAction = UiActionStep.Tap(UiTarget("e_old_clock", null))
        val recoveryDecision = tracker.recordAndDecide(
            type = UiExecutionIncidentType.STALE_OBSERVATION,
            stepSequence = 1,
            action = initialAction,
            goal = goal,
            detail = "Lease rejected due to launcher layout shift",
            beforeScreen = launcherV1,
            currentScreen = launcherV2
        )
        assertTrue(recoveryDecision is UiRecoveryDecision.RebindAction)
        val reboundAction = (recoveryDecision as UiRecoveryDecision.RebindAction).replacementAction as UiActionStep.Tap
        assertEquals("e_reindexed_clock", reboundAction.target.elementId)

        // Execute rebound action -> transitions to Clock -> GOAL_VERIFIED
        val post = UiActionPostconditionVerifier.verify(reboundAction, launcherV2, clockScreen)
        val verify = UiGoalVerifier.verify(
            goal = goal,
            action = reboundAction,
            before = launcherV2,
            after = clockScreen,
            transportStatus = UiTransportStatus.ACCEPTED,
            postcondition = post,
            goalAppCandidates = clockApp
        )
        assertTrue(verify.isGoalVerified)
        assertEquals(UiVerificationStatus.GOAL_VERIFIED, verify.status)

        tracker.resolveAllActive("Goal verified after re-grounding Clock icon")
        assertTrue(tracker.activeIncidents.isEmpty())
        assertEquals(1, tracker.resolvedIncidents.size)

        recorder.recordStep(
            ActionStepRecord(
                sequence = 1,
                observation = launcherV2.screenId,
                reasoningSummary = "Tap Clock icon after stale-observation re-grounding",
                action = "tap",
                target = reboundAction.target.elementId,
                result = "succeeded",
                postActionObservation = clockScreen.screenId,
                verification = verify.summary,
                latencyMs = 140L,
                retryCount = 1,
                sourcePackage = launcherV2.packageName,
                resultPackage = clockScreen.packageName,
                verificationStatus = verify.status.name.lowercase(),
                goalVerificationStatus = verify.status.name.lowercase(),
                evidenceCategory = verify.evidenceCategory,
                checkpointId = verify.checkpointId,
                sessionId = sessionId,
                observationId = "obs_s8_2",
                recoverySummary = recoveryDecision.summary
            ),
            rawGoalForInit = goal
        )
        traceStore.finishSession(sessionId, "completed", UiVerificationStatus.GOAL_VERIFIED, verify.summary)

        val persisted = traceStore.inspectSession(sessionId)!!
        assertEquals("completed", persisted.session.status)
        assertEquals(UiVerificationStatus.GOAL_VERIFIED, persisted.session.verificationStatus)
        assertEquals(1, persisted.steps.single().retryCount)
        assertTrue(persisted.steps.single().recoverySummary!!.contains("re-grounded"))
    }
}

package com.mewmix.nabu.uiagent

import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import com.mewmix.nabu.utils.DatabaseHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class AutomationTraceTest {
    @Test
    fun traceRedactsGoalsMessagesDestinationsAndPlannerOutput() {
        val secretGoal = "Send hello to Saved Messages"
        val secretOutput = "{\"action\":\"type_text\",\"text\":\"hello\"}"
        val json = AutomationTraceEvent(
            sessionId = "session-1",
            sequence = 3,
            elapsedMs = 42,
            name = "planner_output_received",
            fields = mapOf(
                "goal" to secretGoal,
                "destination" to "Saved Messages",
                "planner_output" to secretOutput,
                "action_count" to 3
            )
        ).toJson()

        assertFalse(json.contains(secretGoal))
        assertFalse(json.contains("Saved Messages"))
        assertFalse(json.contains(secretOutput))
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals(1, root.get("schema_version").asInt)
        assertEquals("planner_output_received", root.get("event").asString)
        assertEquals(3, root.getAsJsonObject("fields").get("action_count").asInt)
        assertTrue(root.getAsJsonObject("fields").getAsJsonObject("goal").get("redacted").asBoolean)
        assertEquals(64, root.getAsJsonObject("fields").getAsJsonObject("planner_output").get("sha256").asString.length)
    }

    @Test
    fun recorderEmitsOrderedStructuredEvents() {
        val lines = mutableListOf<String>()
        val events = mutableListOf<AutomationTraceEvent>()
        var now = 100L
        val recorder = AutomationTraceRecorder(
            "session-1",
            lines::add,
            onEvent = events::add,
            nowMs = { now }
        )

        recorder.emit("session_started")
        now = 125L
        recorder.emit("action_selected", mapOf("action" to "tap"))

        assertEquals(2, lines.size)
        assertEquals(listOf("session_started", "action_selected"), events.map { it.name })
        val first = JsonParser.parseString(lines[0].removePrefix("UiAutomationTrace ")).asJsonObject
        val second = JsonParser.parseString(lines[1].removePrefix("UiAutomationTrace ")).asJsonObject
        assertEquals(0, first.get("sequence").asInt)
        assertEquals(1, second.get("sequence").asInt)
        assertEquals(25L, second.get("elapsed_ms").asLong)
    }

    @Test
    fun sqliteStorePersistsStepMetadataAndRedactsSensitiveInformation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbHelper = DatabaseHelper(context, databaseName = null)
        val store = AutomationTraceStore(dbHelper = dbHelper, nowMs = { 1_000L })

        val secretGoal = "Login with password=SuperSecret123! and PIN 482910"
        store.startOrUpdateSession(
            sessionId = "sess-redact",
            rawGoal = secretGoal,
            checkpointId = "chk_login",
            status = "running"
        )

        store.recordStep(
            sessionId = "sess-redact",
            step = ActionStepRecord(
                sequence = 1,
                observation = "fp_before_1",
                reasoningSummary = "Type password",
                action = "type_text",
                target = "n4 (com.example:id/password_input) 'SuperSecret123!'",
                result = "succeeded",
                postActionObservation = "fp_after_1",
                verification = "Entered password=SuperSecret123! with Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abc12345",
                latencyMs = 180L,
                retryCount = 1,
                sourcePackage = "com.example",
                resultPackage = "com.example",
                verificationStatus = UiVerificationStatus.STATE_CHANGED.name.lowercase(),
                transportStatus = UiTransportStatus.ACCEPTED.name.lowercase(),
                postconditionStatus = PostconditionStatus.STATE_CHANGED.name.lowercase(),
                goalVerificationStatus = UiVerificationStatus.STATE_CHANGED.name.lowercase(),
                evidenceCategory = "state_transition",
                checkpointId = "chk_login",
                observationId = "obs-101",
                beforeStateFingerprint = "fp_before_1",
                afterStateFingerprint = "fp_after_1",
                leaseValidationOutcome = "authorized",
                failureClassification = "GOAL_NOT_VERIFIED",
                observationLatencyMs = 20L,
                modelDecisionLatencyMs = 90L,
                executionLatencyMs = 40L,
                verificationLatencyMs = 30L,
                recoverySummary = "Retried with PIN 482910 and token=my_secret_token_999",
                terminationReason = "Failed auth with password=SuperSecret123!",
                screenshotArtifactRef = "/tmp/untrusted_screenshot.png"
            )
        )

        val session = store.getSession("sess-redact")
        assertNotNull(session)
        assertFalse(session!!.goalDigest.contains("SuperSecret123!"))
        assertFalse(session.goalDigest.contains("482910"))
        assertFalse(session.terminationReason.orEmpty().contains("SuperSecret123!"))
        assertEquals(1, session.stepCount)
        assertEquals(1, session.recoveryAttemptCount)

        val steps = store.getSteps("sess-redact")
        assertEquals(1, steps.size)
        val persisted = steps.single()
        assertEquals("obs-101", persisted.observationId)
        assertEquals("fp_before_1", persisted.beforeStateFingerprint)
        assertEquals("fp_after_1", persisted.afterStateFingerprint)
        assertEquals("authorized", persisted.leaseValidationOutcome)
        assertEquals("GOAL_NOT_VERIFIED", persisted.failureClassification)
        assertEquals(20L, persisted.observationLatencyMs)
        assertEquals(90L, persisted.modelDecisionLatencyMs)
        assertEquals(40L, persisted.executionLatencyMs)
        assertEquals(30L, persisted.verificationLatencyMs)
        assertEquals(180L, persisted.latencyMs)
        // Screenshot must be stripped when screenshotOptIn is false (default)
        assertNull(persisted.screenshotArtifactRef)
        // Target must retain structural IDs (n4, com.example:id/password_input) but redact freeform text
        assertTrue(persisted.target.orEmpty().contains("n4"))
        assertTrue(persisted.target.orEmpty().contains("com.example:id/password_input"))
        assertFalse(persisted.target.orEmpty().contains("SuperSecret123!"))
        assertFalse(persisted.recoverySummary.orEmpty().contains("482910"))
        assertFalse(persisted.recoverySummary.orEmpty().contains("my_secret_token_999"))
        assertFalse(persisted.terminationReason.orEmpty().contains("SuperSecret123!"))
    }

    @Test
    fun recoversCompletedAndInterruptedSessionsAfterProcessRestart() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = Files.createTempFile("nabu_trace_restart", ".db").toFile()
        try {
            val dbHelper1 = DatabaseHelper(context, databaseName = dbFile.absolutePath)
            val storeBeforeRestart = AutomationTraceStore(dbHelper = dbHelper1, nowMs = { 5_000L })

            storeBeforeRestart.startOrUpdateSession(
                sessionId = "sess-completed",
                rawGoal = "Open Settings",
                status = "completed",
                verificationStatus = UiVerificationStatus.GOAL_VERIFIED
            )
            storeBeforeRestart.recordStep(
                sessionId = "sess-completed",
                step = ActionStepRecord(
                    sequence = 1,
                    observation = "fp_home",
                    reasoningSummary = "Open Settings",
                    action = "open_app",
                    target = "com.android.settings",
                    result = "succeeded",
                    postActionObservation = "fp_settings",
                    verification = "Settings foregrounded",
                    latencyMs = 50L,
                    verificationStatus = UiVerificationStatus.GOAL_VERIFIED.name.lowercase(),
                    goalVerificationStatus = UiVerificationStatus.GOAL_VERIFIED.name.lowercase(),
                    evidenceCategory = "package_foreground",
                    checkpointId = "app_foreground_com.android.settings"
                )
            )
            storeBeforeRestart.finishSession(
                sessionId = "sess-completed",
                status = "completed",
                verificationStatus = UiVerificationStatus.GOAL_VERIFIED,
                terminationReason = "Goal verified"
            )

            // Simulate a session that was mid-flight when process died
            storeBeforeRestart.startOrUpdateSession(
                sessionId = "sess-in-flight",
                rawGoal = "Open Wi-Fi Settings",
                status = "running",
                verificationStatus = UiVerificationStatus.STATE_CHANGED
            )
            storeBeforeRestart.recordStep(
                sessionId = "sess-in-flight",
                step = ActionStepRecord(
                    sequence = 1,
                    observation = "fp_home",
                    reasoningSummary = "Tap settings icon",
                    action = "tap",
                    target = "n2",
                    result = "succeeded",
                    postActionObservation = "fp_settings_root",
                    verification = "Screen changed",
                    latencyMs = 75L,
                    verificationStatus = UiVerificationStatus.STATE_CHANGED.name.lowercase(),
                    goalVerificationStatus = UiVerificationStatus.STATE_CHANGED.name.lowercase(),
                    evidenceCategory = "screen_transition"
                )
            )
            dbHelper1.close()

            // Reopen database as a new process and run restart recovery
            val dbHelper2 = DatabaseHelper(context, databaseName = dbFile.absolutePath)
            val storeAfterRestart = AutomationTraceStore(dbHelper = dbHelper2, nowMs = { 9_000L })
            val recovered = storeAfterRestart.recoverAfterProcessRestart(activeRunnerSessionIds = emptySet())

            assertEquals(2, recovered.size)
            val completed = storeAfterRestart.getSession("sess-completed")!!
            assertEquals("completed", completed.status)
            assertEquals(UiVerificationStatus.GOAL_VERIFIED, completed.verificationStatus)
            assertEquals(1, storeAfterRestart.getSteps("sess-completed").size)

            val interrupted = storeAfterRestart.getSession("sess-in-flight")!!
            assertEquals("interrupted", interrupted.status)
            assertEquals(UiVerificationStatus.UNVERIFIABLE, interrupted.verificationStatus)
            assertEquals("process_restart", interrupted.terminationReason)
            assertEquals(1, storeAfterRestart.getSteps("sess-in-flight").size)
            dbHelper2.close()
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun enforcesBoundedStorageAndScreenshotOptInRetention() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbHelper = DatabaseHelper(context, databaseName = null)
        val screenshotRoot = Files.createTempDirectory("nabu_trace_screenshots").toFile()
        try {
            var clock = 1_000L
            val policy = TraceRetentionPolicy(
                maxSessions = 2,
                maxStepsPerSession = 2,
                maxEventsPerSession = 3,
                maxAgeMs = 10_000L,
                screenshotOptIn = false
            )
            val store = AutomationTraceStore(
                dbHelper = dbHelper,
                screenshotDir = screenshotRoot,
                retentionPolicy = policy,
                nowMs = { clock }
            )

            // Screenshots disabled by default
            assertNull(store.saveScreenshotIfOptedIn("sess-1", 1, byteArrayOf(1, 2, 3)))

            // Opt in to screenshots and record 3 sessions (maxSessions = 2)
            store.retentionPolicy = policy.copy(screenshotOptIn = true)
            val s1Screenshot = store.saveScreenshotIfOptedIn("sess-1", 1, byteArrayOf(1, 2, 3))
            assertNotNull(s1Screenshot)
            assertTrue(File(s1Screenshot!!).exists())

            clock = 1_000L
            store.startOrUpdateSession("sess-1", "Goal 1")
            clock = 2_000L
            store.startOrUpdateSession("sess-2", "Goal 2")
            clock = 3_000L
            store.startOrUpdateSession("sess-3", "Goal 3")

            val remainingIds = store.listSessions().map { it.sessionId }
            assertEquals(listOf("sess-3", "sess-2"), remainingIds)
            // Pruned session's screenshot directory must also be deleted
            assertFalse(File(s1Screenshot).exists())

            // Record 3 steps in sess-3 when maxStepsPerSession = 2
            for (seq in 1..3) {
                store.recordStep(
                    sessionId = "sess-3",
                    step = ActionStepRecord(
                        sequence = seq,
                        observation = "fp_$seq",
                        reasoningSummary = "Step $seq",
                        action = "tap",
                        target = "n$seq",
                        result = "succeeded",
                        postActionObservation = "fp_${seq + 1}",
                        verification = "Changed",
                        latencyMs = 10L,
                        verificationStatus = UiVerificationStatus.STATE_CHANGED.name.lowercase()
                    )
                )
            }
            val stepsForS3 = store.getSteps("sess-3")
            assertEquals(2, stepsForS3.size)
            assertEquals(listOf(2, 3), stepsForS3.map { it.sequence })
        } finally {
            screenshotRoot.deleteRecursively()
        }
    }

    @Test
    fun deterministicReplayReconstructsEvidenceAndDetectsFalseVerificationClaimsWithoutLiveExecution() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbHelper = DatabaseHelper(context, databaseName = null)
        val store = AutomationTraceStore(dbHelper = dbHelper, nowMs = { 2_000L })

        // Session with a step that falsely claimed GOAL_VERIFIED on a rejected lease
        store.startOrUpdateSession(
            sessionId = "sess-tampered",
            rawGoal = "Open Quick Settings",
            status = "completed",
            verificationStatus = UiVerificationStatus.GOAL_VERIFIED
        )
        store.recordStep(
            sessionId = "sess-tampered",
            step = ActionStepRecord(
                sequence = 1,
                observation = "fp_1",
                reasoningSummary = "Open QS",
                action = "open_quick_settings",
                target = "quick_settings",
                result = "failed",
                postActionObservation = "fp_1",
                verification = "Rejected lease",
                latencyMs = 30L,
                verificationStatus = UiVerificationStatus.GOAL_VERIFIED.name.lowercase(),
                goalVerificationStatus = UiVerificationStatus.GOAL_VERIFIED.name.lowercase(),
                evidenceCategory = "system_surface_quick_settings",
                leaseValidationOutcome = "rejected:stale_fingerprint"
            )
        )
        store.finishSession(
            sessionId = "sess-tampered",
            status = "completed",
            verificationStatus = UiVerificationStatus.GOAL_VERIFIED
        )

        val inspection = store.inspectSession("sess-tampered")
        assertNotNull(inspection)
        val replay = inspection!!.replayReport
        assertTrue(replay.deterministicReconstructionOnly)
        assertEquals(0, replay.liveUiActionsExecuted)
        assertFalse(replay.integrityVerified)
        assertEquals(UiVerificationStatus.UNVERIFIABLE, replay.reconstructedVerificationStatus)
        assertTrue(replay.anomalies.any { it.contains("rejected lease") })
    }
}

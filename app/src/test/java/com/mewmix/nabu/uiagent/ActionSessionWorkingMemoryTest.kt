package com.mewmix.nabu.uiagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSessionWorkingMemoryTest {

    private fun makeStep(
        seq: Int,
        action: String = "tap",
        target: String? = "btn_$seq",
        result: String = "succeeded",
        status: UiVerificationStatus = UiVerificationStatus.STATE_CHANGED,
        failureClassification: String? = null,
        sourcePkg: String = "com.android.settings",
        resultPkg: String = "com.android.settings"
    ): ActionStepRecord = ActionStepRecord(
        sequence = seq,
        observation = "screen_$seq",
        reasoningSummary = "Step $seq",
        action = action,
        target = target,
        result = result,
        postActionObservation = "screen_${seq + 1}",
        verification = "Step $seq detail",
        latencyMs = 45L,
        sourcePackage = sourcePkg,
        resultPackage = resultPkg,
        verificationStatus = status.name.lowercase(),
        goalVerificationStatus = status.name.lowercase(),
        checkpointId = "cp_$seq",
        sessionId = "sess_mem_1",
        observationId = "obs_$seq",
        beforeStateFingerprint = "screen_$seq",
        afterStateFingerprint = "screen_${seq + 1}",
        failureClassification = failureClassification
    )

    @Test
    fun `compacts older steps while keeping recent raw window and persistent trace references`() {
        val goal = "Open Wi-Fi settings and do not enable hotspot"
        val steps = (1..14).map { seq ->
            when (seq) {
                2 -> makeStep(
                    seq = seq,
                    action = "tap",
                    target = "missing_row",
                    result = "failed",
                    status = UiVerificationStatus.FAILED,
                    failureClassification = "TARGET_NOT_FOUND"
                )
                4 -> makeStep(
                    seq = seq,
                    action = "scroll_down",
                    target = "list",
                    result = "unverifiable",
                    status = UiVerificationStatus.UNVERIFIABLE,
                    failureClassification = "ACCESSIBILITY_UNAVAILABLE"
                )
                else -> makeStep(
                    seq = seq,
                    status = UiVerificationStatus.STATE_CHANGED
                )
            }
        }
        val activeIncident = UiExecutionIncident(
            type = UiExecutionIncidentType.GOAL_NOT_VERIFIED,
            stepSequence = 14,
            actionLabel = "tap",
            detail = "Still on intermediate network sub-screen",
            resolved = false
        )
        val resolvedIncident = UiExecutionIncident(
            type = UiExecutionIncidentType.TARGET_NOT_FOUND,
            stepSequence = 2,
            actionLabel = "tap",
            detail = "Recovered via scroll",
            resolved = true
        )

        val result = ActionSessionWorkingMemory.compactStepRecords(
            sessionId = "sess_mem_1",
            goal = goal,
            steps = steps,
            existingSummaries = emptyList(),
            activeIncidents = listOf(activeIncident, resolvedIncident),
            rawWindowSize = 8
        )

        // Recent raw window has exactly the last 8 steps (7..14)
        assertEquals(8, result.recentRawSteps.size)
        assertEquals((7..14).toList(), result.recentRawSteps.map { it.sequence })

        // Older 6 steps (1..6) are compacted into 2 chunks (1..4 and 5..6)
        assertEquals(2, result.compactedSummaries.size)
        val firstChunk = result.compactedSummaries[0]
        assertEquals("sess_mem_1", firstChunk.traceReference.sessionId)
        assertEquals(1, firstChunk.traceReference.startSequence)
        assertEquals(4, firstChunk.traceReference.endSequence)
        assertEquals(listOf("obs_1", "obs_2", "obs_3", "obs_4"), firstChunk.traceReference.observationIds)
        assertEquals(listOf("cp_1", "cp_2", "cp_3", "cp_4"), firstChunk.traceReference.checkpointIds)

        // Preserves verified transitions, failed attempts, task constraints, and unresolved uncertainty
        assertTrue(firstChunk.verifiedTransitions.any { it.contains("#1 tap") })
        assertTrue(firstChunk.failedAttempts.any { it.contains("#2 tap") && it.contains("TARGET_NOT_FOUND") })
        assertTrue(firstChunk.unresolvedUncertainties.any { it.contains("#4 scroll_down") && it.contains("ACCESSIBILITY_UNAVAILABLE") })
        assertTrue(firstChunk.taskConstraints.any { it.contains("do not enable hotspot", ignoreCase = true) })

        // Active unresolved incidents are preserved; resolved incidents are filtered out
        assertEquals(1, result.activeIncidents.size)
        assertEquals(UiExecutionIncidentType.GOAL_NOT_VERIFIED, result.activeIncidents.single().type)
    }

    @Test
    fun `model summarization never upgrades failed or unverified outcome into success`() {
        val steps = listOf(
            makeStep(
                seq = 1,
                action = "tap",
                target = "send_button",
                result = "failed",
                status = UiVerificationStatus.FAILED,
                failureClassification = "POSTCONDITION_FAILED"
            ),
            makeStep(
                seq = 2,
                action = "tap",
                target = "send_button",
                result = "unverifiable",
                status = UiVerificationStatus.UNVERIFIABLE,
                failureClassification = "ACCESSIBILITY_UNAVAILABLE"
            )
        )
        val baseSummary = ActionSessionWorkingMemory.buildDeterministicSummary(
            sessionId = "sess_safe_1",
            chunk = steps,
            taskConstraints = listOf("goal: Send message")
        )
        assertEquals(UiVerificationStatus.FAILED, baseSummary.highestVerificationStatus)

        // Misleading model summary claiming goal verification and completion
        val misleadingModelOutput = "Goal verified! The task is completed and all steps succeeded with verified success."
        val applied = ActionSessionWorkingMemory.applyModelSummarySafely(
            deterministicSummary = baseSummary,
            candidateModelSummary = misleadingModelOutput
        )

        // Status MUST remain FAILED, never GOAL_VERIFIED
        assertEquals(UiVerificationStatus.FAILED, applied.highestVerificationStatus)
        assertFalse(applied.highestVerificationStatus.isGoalVerified)
        // False success claims must be scrubbed from the summary text
        assertFalse(applied.summaryText.contains("Goal verified", ignoreCase = true))
        assertFalse(applied.summaryText.contains("all steps succeeded", ignoreCase = true))
        assertTrue(applied.summaryText.contains("[unverified_in_trace]"))
        // Failures and unresolved items remain intact
        assertEquals(baseSummary.failedAttempts, applied.failedAttempts)
        assertEquals(baseSummary.unresolvedUncertainties, applied.unresolvedUncertainties)
        assertTrue(applied.summarizedByLocalModel)
    }

    @Test
    fun `off-critical-path local summarization does not block caller and preserves safety invariants`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val baseSummary = ActionSessionWorkingMemory.buildDeterministicSummary(
            sessionId = "sess_async_1",
            chunk = listOf(makeStep(seq = 1, status = UiVerificationStatus.EXECUTED)),
            taskConstraints = listOf("goal: Open Bluetooth")
        )
        val refinedDeferred = CompletableDeferred<CompactedHistorySummary>()

        val job = ActionSessionWorkingMemory.summarizeOffCriticalPath(
            scope = scope,
            deterministicSummary = baseSummary,
            localSummarizer = {
                "Tapped Bluetooth row; task completed."
            },
            onRefined = { refined ->
                refinedDeferred.complete(refined)
            }
        )
        job.join()
        val refined = refinedDeferred.await()
        assertEquals(UiVerificationStatus.EXECUTED, refined.highestVerificationStatus)
        assertFalse(refined.summaryText.contains("task completed", ignoreCase = true))
        assertTrue(refined.summaryText.contains("trace:sess_async_1#1..1"))
    }
}

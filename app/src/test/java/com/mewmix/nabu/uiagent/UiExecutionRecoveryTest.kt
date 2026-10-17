package com.mewmix.nabu.uiagent

import com.mewmix.nabu.actions.DeviceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiExecutionRecoveryTest {

    private fun element(
        id: String,
        text: String? = null,
        contentDescription: String? = null,
        resourceId: String? = null,
        className: String? = "android.widget.Button",
        clickable: Boolean = true,
        editable: Boolean = false,
        focusable: Boolean = true,
        bounds: UiBounds = UiBounds(10, 10, 200, 80)
    ): UiElement = UiElement(
        id = id,
        text = text,
        contentDescription = contentDescription,
        resourceId = resourceId,
        className = className,
        packageName = "com.example.app",
        bounds = bounds,
        clickable = clickable,
        enabled = true,
        visible = true,
        editable = editable,
        scrollable = false,
        longClickable = false,
        checkable = false,
        checked = false,
        password = false,
        parentId = null,
        treePath = "0/$id",
        focusable = focusable,
        standardActions = buildSet {
            if (clickable) add("click")
            if (editable) add("set_text")
        }
    )

    private fun screen(
        screenId: String,
        packageName: String = "com.example.app",
        activityName: String = ".MainActivity",
        elements: List<UiElement>
    ): UiScreenState = UiScreenState(
        screenId = screenId,
        packageName = packageName,
        activityName = activityName,
        elements = elements
    )

    @Test
    fun `stale observation re-grounds target on fresh screen when unique match exists`() {
        val tracker = UiRecoveryTracker()
        val before = screen(
            screenId = "screen_v1",
            elements = listOf(
                element(id = "e_1", text = "Continue", resourceId = "com.example.app:id/btn_continue")
            )
        )
        val fresh = screen(
            screenId = "screen_v2",
            elements = listOf(
                element(id = "e_9", text = "Header", clickable = false),
                element(
                    id = "e_14",
                    text = "Continue",
                    resourceId = "com.example.app:id/btn_continue",
                    bounds = UiBounds(20, 400, 300, 480)
                )
            )
        )
        val originalAction = UiActionStep.Tap(UiTarget(elementId = "e_1", fallbackBounds = UiBounds(10, 10, 200, 80)))

        val decision = tracker.recordAndDecide(
            type = UiExecutionIncidentType.STALE_OBSERVATION,
            stepSequence = 1,
            action = originalAction,
            goal = "Tap Continue",
            detail = "Observation lease expired because screen state drifted.",
            beforeScreen = before,
            currentScreen = fresh
        )

        assertTrue(decision is UiRecoveryDecision.RebindAction)
        val rebind = decision as UiRecoveryDecision.RebindAction
        val reboundTap = rebind.replacementAction as UiActionStep.Tap
        assertEquals("e_14", reboundTap.target.elementId)
        assertEquals(UiBounds(20, 400, 300, 480), reboundTap.target.fallbackBounds)
        assertEquals(1, tracker.activeIncidents.size)

        tracker.resolveIncident(UiExecutionIncidentType.STALE_OBSERVATION, rebind.summary)
        assertTrue(tracker.activeIncidents.isEmpty())
        assertEquals(1, tracker.resolvedIncidents.size)
        assertTrue(tracker.resolvedIncidents.single().resolved)
    }

    @Test
    fun `missing target locates alternative semantic locator on current UI`() {
        val tracker = UiRecoveryTracker()
        val before = screen(
            screenId = "screen_before",
            elements = listOf(
                element(id = "e_old_search", text = "Search settings", resourceId = "com.android.settings:id/search_bar")
            )
        )
        val current = screen(
            screenId = "screen_current",
            elements = listOf(
                element(
                    id = "e_new_search",
                    text = null,
                    contentDescription = "Search settings",
                    resourceId = "com.android.settings:id/action_search"
                )
            )
        )
        val action = UiActionStep.Tap(UiTarget(elementId = "e_old_search", fallbackBounds = null))

        val decision = tracker.recordAndDecide(
            type = UiExecutionIncidentType.TARGET_NOT_FOUND,
            stepSequence = 2,
            action = action,
            goal = "Search settings for Wi-Fi",
            detail = "Element e_old_search not found on current hierarchy.",
            beforeScreen = before,
            currentScreen = current
        )

        assertTrue(decision is UiRecoveryDecision.RebindAction)
        val rebound = (decision as UiRecoveryDecision.RebindAction).replacementAction as UiActionStep.Tap
        assertEquals("e_new_search", rebound.target.elementId)
    }

    @Test
    fun `ambiguous target resolves deterministically by exact resourceId or replans with disambiguation hint`() {
        val tracker = UiRecoveryTracker()
        val before = screen(
            screenId = "s1",
            elements = listOf(
                element(id = "e_save_primary", text = "Save", resourceId = "com.example.app:id/toolbar_save")
            )
        )
        val currentWithUniqueRes = screen(
            screenId = "s2",
            elements = listOf(
                element(id = "e_10", text = "Save", resourceId = "com.example.app:id/toolbar_save"),
                element(id = "e_11", text = "Save", resourceId = "com.example.app:id/footer_save_draft")
            )
        )
        val action = UiActionStep.Tap(UiTarget(elementId = "e_save_primary", fallbackBounds = null))

        val resolvedDecision = tracker.recordAndDecide(
            type = UiExecutionIncidentType.AMBIGUOUS_TARGET,
            stepSequence = 1,
            action = action,
            goal = "Save note",
            detail = "Multiple Save buttons visible.",
            beforeScreen = before,
            currentScreen = currentWithUniqueRes
        )
        assertTrue(resolvedDecision is UiRecoveryDecision.RebindAction)
        assertEquals("e_10", ((resolvedDecision as UiRecoveryDecision.RebindAction).replacementAction as UiActionStep.Tap).target.elementId)

        // Second tracker with no disambiguating resourceId must replan rather than guessing
        val tracker2 = UiRecoveryTracker()
        val ambiguousScreen = screen(
            screenId = "s3",
            elements = listOf(
                element(id = "e_20", text = "Save", resourceId = "com.example.app:id/btn_save"),
                element(id = "e_21", text = "Save", resourceId = "com.example.app:id/btn_save")
            )
        )
        val replanDecision = tracker2.recordAndDecide(
            type = UiExecutionIncidentType.AMBIGUOUS_TARGET,
            stepSequence = 1,
            action = UiActionStep.Tap(UiTarget(elementId = "unknown", fallbackBounds = null, textContains = "Save")),
            goal = "Save note",
            detail = "2 identical Save buttons",
            beforeScreen = null,
            currentScreen = ambiguousScreen
        )
        assertTrue(replanDecision is UiRecoveryDecision.ReplanWithEvidence)
    }

    @Test
    fun `unexpected navigation detects when intended destination was actually reached`() {
        val tracker = UiRecoveryTracker()
        val before = screen(
            screenId = "launcher",
            packageName = "com.google.android.apps.nexuslauncher",
            elements = emptyList()
        )
        val after = screen(
            screenId = "settings_home",
            packageName = "com.android.settings",
            activityName = ".Settings",
            elements = listOf(element(id = "e_wifi", text = "Network & internet"))
        )
        val candidates = listOf(DeviceAction.AppCandidate("Settings", "com.android.settings"))

        val decision = tracker.recordAndDecide(
            type = UiExecutionIncidentType.UNEXPECTED_NAVIGATION,
            stepSequence = 1,
            action = UiActionStep.Tap(UiTarget("e_icon", null)),
            goal = "Open Settings",
            detail = "Package changed unexpectedly.",
            beforeScreen = before,
            currentScreen = after,
            actionAlreadyDispatched = true,
            goalAppCandidates = candidates
        )

        assertTrue(decision is UiRecoveryDecision.DestinationAlreadyReached)
        val reached = decision as UiRecoveryDecision.DestinationAlreadyReached
        assertTrue(reached.verification.isGoalVerified)
    }

    @Test
    fun `unexpected navigation to unrelated package navigates back and replans`() {
        val tracker = UiRecoveryTracker()
        val before = screen(
            screenId = "settings_main",
            packageName = "com.android.settings",
            elements = emptyList()
        )
        val wrongPackageScreen = screen(
            screenId = "browser_ad",
            packageName = "com.android.chrome",
            elements = listOf(
                element(id = "e_back", contentDescription = "Navigate up", resourceId = "com.android.chrome:id/back_button")
            )
        )
        val candidates = listOf(DeviceAction.AppCandidate("Settings", "com.android.settings"))

        val decision = tracker.recordAndDecide(
            type = UiExecutionIncidentType.UNEXPECTED_NAVIGATION,
            stepSequence = 2,
            action = UiActionStep.Tap(UiTarget("e_link", null)),
            goal = "Open Wi-Fi in Settings",
            detail = "Drifted from com.android.settings to com.android.chrome.",
            beforeScreen = before,
            currentScreen = wrongPackageScreen,
            actionAlreadyDispatched = true,
            goalAppCandidates = candidates
        )

        assertTrue(decision is UiRecoveryDecision.NavigateBackAndReplan)
    }

    @Test
    fun `accessibility unavailable retries with bounded exponential backoff then aborts`() {
        val budget = UiRecoveryBudget(
            maxRetriesByType = mapOf(UiExecutionIncidentType.ACCESSIBILITY_UNAVAILABLE to 3),
            maxTotalRecoveryAttempts = 6,
            baseBackoffMs = 100L,
            maxBackoffMs = 800L
        )
        val tracker = UiRecoveryTracker(budget = budget)

        val d1 = tracker.recordAndDecide(
            type = UiExecutionIncidentType.ACCESSIBILITY_UNAVAILABLE,
            stepSequence = 1,
            action = null,
            goal = "Open Settings",
            detail = "Window root null"
        )
        assertTrue(d1 is UiRecoveryDecision.ReobserveWithBackoff)
        assertEquals(100L, (d1 as UiRecoveryDecision.ReobserveWithBackoff).delayMs)

        val d2 = tracker.recordAndDecide(
            type = UiExecutionIncidentType.ACCESSIBILITY_UNAVAILABLE,
            stepSequence = 1,
            action = null,
            goal = "Open Settings",
            detail = "Window root null"
        )
        assertTrue(d2 is UiRecoveryDecision.ReobserveWithBackoff)
        assertEquals(200L, (d2 as UiRecoveryDecision.ReobserveWithBackoff).delayMs)

        val d3 = tracker.recordAndDecide(
            type = UiExecutionIncidentType.ACCESSIBILITY_UNAVAILABLE,
            stepSequence = 1,
            action = null,
            goal = "Open Settings",
            detail = "Window root null"
        )
        assertTrue(d3 is UiRecoveryDecision.ReobserveWithBackoff)
        assertEquals(400L, (d3 as UiRecoveryDecision.ReobserveWithBackoff).delayMs)

        val d4 = tracker.recordAndDecide(
            type = UiExecutionIncidentType.ACCESSIBILITY_UNAVAILABLE,
            stepSequence = 1,
            action = null,
            goal = "Open Settings",
            detail = "Window root still null"
        )
        assertTrue(d4 is UiRecoveryDecision.Abort)
        assertEquals("retry_budget_exhausted", (d4 as UiRecoveryDecision.Abort).terminationCode)
    }

    @Test
    fun `non-idempotent action is never repeated when confirmation or post-action state is lost`() {
        val tracker = UiRecoveryTracker()
        val chatScreen = screen(
            screenId = "chat_compose",
            packageName = "org.telegram.messenger",
            elements = listOf(
                element(
                    id = "e_send",
                    text = "Send",
                    contentDescription = "Send message",
                    resourceId = "org.telegram.messenger:id/send_button"
                )
            )
        )
        val sendTap = UiActionStep.Tap(UiTarget(elementId = "e_send", fallbackBounds = null))

        assertTrue(UiRecoveryPolicy.isPotentiallyNonIdempotent(sendTap, chatScreen, "Send hello to Alice"))
        tracker.markNonIdempotentDispatched(sendTap, chatScreen, "Send hello to Alice")
        assertTrue(tracker.wasNonIdempotentAlreadyDispatched(sendTap, chatScreen))

        // Postcondition or timeout after dispatch must HALT rather than re-executing the send button
        val timeoutDecision = tracker.recordAndDecide(
            type = UiExecutionIncidentType.EXECUTION_TIMEOUT,
            stepSequence = 3,
            action = sendTap,
            goal = "Send hello to Alice",
            detail = "Timed out waiting for message receipt.",
            beforeScreen = chatScreen,
            currentScreen = chatScreen,
            actionAlreadyDispatched = true
        )
        assertTrue(timeoutDecision is UiRecoveryDecision.HaltNonIdempotentAction)

        // Accessibility unavailable after dispatch may re-observe state ONLY with allowActionRedispatch = false
        val a11yDecision = tracker.recordAndDecide(
            type = UiExecutionIncidentType.ACCESSIBILITY_UNAVAILABLE,
            stepSequence = 3,
            action = sendTap,
            goal = "Send hello to Alice",
            detail = "Window root unavailable after tap.",
            beforeScreen = chatScreen,
            currentScreen = null,
            actionAlreadyDispatched = true
        )
        assertTrue(a11yDecision is UiRecoveryDecision.ReobserveWithBackoff)
        assertFalse((a11yDecision as UiRecoveryDecision.ReobserveWithBackoff).allowActionRedispatch)
    }

    @Test
    fun `enforces cancellation, deadline, and session ownership during recovery`() {
        var clockMs = 1_000L
        val tracker = UiRecoveryTracker(
            budget = UiRecoveryBudget(sessionDeadlineMs = 5_000L),
            startedAtMs = 1_000L,
            nowMs = { clockMs }
        )

        val ownerLost = tracker.recordAndDecide(
            type = UiExecutionIncidentType.STALE_OBSERVATION,
            stepSequence = 1,
            action = null,
            goal = "Open Settings",
            detail = "Lease stale",
            isSessionOwnerValid = false
        )
        assertTrue(ownerLost is UiRecoveryDecision.Abort)
        assertEquals("session_owner_superseded", (ownerLost as UiRecoveryDecision.Abort).terminationCode)

        val cancelled = tracker.recordAndDecide(
            type = UiExecutionIncidentType.STALE_OBSERVATION,
            stepSequence = 1,
            action = null,
            goal = "Open Settings",
            detail = "Lease stale",
            isCancelled = true
        )
        assertTrue(cancelled is UiRecoveryDecision.Abort)
        assertEquals("cancelled", (cancelled as UiRecoveryDecision.Abort).terminationCode)

        clockMs = 7_500L
        val expired = tracker.recordAndDecide(
            type = UiExecutionIncidentType.STALE_OBSERVATION,
            stepSequence = 1,
            action = null,
            goal = "Open Settings",
            detail = "Lease stale"
        )
        assertTrue(expired is UiRecoveryDecision.Abort)
        assertEquals("deadline_exceeded", (expired as UiRecoveryDecision.Abort).terminationCode)
    }
}

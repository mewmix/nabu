package com.mewmix.nabu.uiagent

import com.mewmix.nabu.actions.DeviceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossAppAutomationTest {
    @Test
    fun `explicit installed app resolution is generic and prefers longest label`() {
        val candidates = listOf(
            DeviceAction.AppCandidate("Telegram", "org.telegram"),
            DeviceAction.AppCandidate("Telegram X", "org.telegram.x"),
            DeviceAction.AppCandidate("Settings", "com.android.settings")
        )

        assertEquals(
            "org.telegram.x",
            DeviceAction.explicitGoalAppCandidate(
                "open Telegram X and search for High Council",
                candidates
            )?.packageName
        )
        assertEquals(
            "com.android.settings",
            DeviceAction.explicitGoalAppCandidate(
                "use Settings to inspect accessibility",
                candidates
            )?.packageName
        )
    }

    @Test
    fun `explicit installed app resolution rejects equal ambiguity`() {
        val candidates = listOf(
            DeviceAction.AppCandidate("Transit", "one.transit"),
            DeviceAction.AppCandidate("Transit", "two.transit")
        )

        assertEquals(
            null,
            DeviceAction.explicitGoalAppCandidate("open Transit", candidates)
        )
    }

    private val candidates = listOf(
        DeviceAction.AppCandidate("YouTube", "com.google.android.youtube"),
        DeviceAction.AppCandidate("Messages", "com.google.android.apps.messaging"),
        DeviceAction.AppCandidate("Calculator", "com.google.android.calculator")
    )

    @Test
    fun ranksEveryApplicationNamedInAMultiAppGoal() {
        val ranked = DeviceAction.rankGoalAppCandidates(
            "Open YouTube, then switch to Messages and finally Calculator",
            candidates
        )

        assertEquals(
            setOf(
                "com.google.android.youtube",
                "com.google.android.apps.messaging",
                "com.google.android.calculator"
            ),
            ranked.map(DeviceAction.AppCandidate::packageName).toSet()
        )
    }

    @Test
    fun appScopeAllowsResolvedOrExplicitPackagesOnly() {
        assertTrue(
            AutomationAppScope.allows(
                "com.google.android.youtube",
                "Open YouTube",
                candidates.take(1)
            )
        )
        assertTrue(
            AutomationAppScope.allows(
                "com.example.explicit",
                "Open com.example.explicit",
                emptyList()
            )
        )
        assertFalse(
            AutomationAppScope.allows(
                "com.example.unrelated",
                "Open YouTube",
                candidates.take(1)
            )
        )
    }

    @Test
    fun completedApplicationsAreRemovedUntilRequestedAgain() {
        val youtube = candidates.first()
        val completedYoutube = UiActionHistoryEntry(
            index = 0,
            action = "open app ${youtube.packageName}",
            targetElementId = null,
            targetLabel = null,
            sourceScreenId = "nabu",
            resultScreenId = "youtube",
            outcome = Outcome.SUCCEEDED,
            changedScreen = true,
            detail = null,
            sourcePackage = "com.mewmix.nabu",
            resultPackage = youtube.packageName
        )

        val remaining = AutomationAppScope.remainingCandidates(
            "Open YouTube, then switch to Calculator",
            candidates,
            listOf(completedYoutube)
        )
        val repeatedRemaining = AutomationAppScope.remainingCandidates(
            "Open YouTube, then Calculator, then YouTube again",
            candidates,
            listOf(completedYoutube)
        )

        assertFalse(remaining.any { it.packageName == youtube.packageName })
        assertTrue(repeatedRemaining.any { it.packageName == youtube.packageName })
    }

    @Test
    fun `openApp settles on expected package`() {
        val oldScreen = screen("old", "com.mewmix.nabu")
        val staleScreen = screen("old", "com.mewmix.nabu")
        val targetScreen = screen("youtube", "com.google.android.youtube")
        val action = UiActionStep.OpenApp("com.google.android.youtube")

        assertFalse(UiTransitionPolicy.isSettled(oldScreen, staleScreen, action))
        assertTrue(UiTransitionPolicy.isSettled(oldScreen, targetScreen, action))
    }

    @Test
    fun `openApp does not settle on intermediate package`() {
        val oldScreen = screen("old", "com.mewmix.nabu")
        val launcherScreen = screen("launcher", "com.android.launcher")
        val action = UiActionStep.OpenApp("com.google.android.youtube")

        assertFalse(UiTransitionPolicy.isSettled(oldScreen, launcherScreen, action))
    }

    private fun screen(id: String, packageName: String) = UiScreenState(
        screenId = id,
        packageName = packageName,
        activityName = null,
        elements = emptyList()
    )
}

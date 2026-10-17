package com.mewmix.nabu.uiagent

import com.mewmix.nabu.actions.DeviceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiGoalVerifierTest {

    @Test
    fun `wrong destination package never produces GOAL_VERIFIED`() {
        val before = simpleScreen(packageName = "com.mewmix.nabu", text = "Home")
        val wrongAfter = simpleScreen(packageName = "com.android.settings", text = "Settings")
        val action = UiActionStep.OpenApp("com.android.chrome")
        val post = UiActionPostconditionVerifier.verify(action, before, wrongAfter)
        val candidates = listOf(DeviceAction.AppCandidate("Chrome", "com.android.chrome"))

        val result = UiGoalVerifier.verify(
            goal = "open chrome",
            action = action,
            before = before,
            after = wrongAfter,
            transportStatus = UiTransportStatus.ACCEPTED,
            postcondition = post,
            goalAppCandidates = candidates
        )

        assertEquals(UiVerificationStatus.FAILED, result.status)
        assertFalse(result.isGoalVerified)
    }

    @Test
    fun `unrelated state fingerprint change returns STATE_CHANGED and never proves goal completion`() {
        val before = simpleScreen(packageName = "com.android.settings", text = "Settings", resourceId = "com.android.settings:id/main")
        val unrelatedAfter = simpleScreen(packageName = "com.android.settings", text = "Battery 85%", resourceId = "com.android.settings:id/battery")
        val target = before.elements.single()
        val action = UiActionStep.Tap(UiTarget(target.id, target.bounds))
        val post = UiActionPostconditionVerifier.verify(action, before, unrelatedAfter)

        val result = UiGoalVerifier.verify(
            goal = "open wifi settings",
            action = action,
            before = before,
            after = unrelatedAfter,
            transportStatus = UiTransportStatus.ACCEPTED,
            postcondition = post
        )

        assertEquals(UiVerificationStatus.STATE_CHANGED, result.status)
        assertFalse(result.isGoalVerified)
    }

    @Test
    fun `generic systemui visibility does not prove Quick Settings opened`() {
        val before = simpleScreen(packageName = "com.android.settings", text = "Settings")
        val genericSystemUi = UiTreeIndexer.parse(
            """
            <hierarchy>
              <node package="com.android.systemui"
                    resource-id="com.android.systemui:id/navigation_bar_frame"
                    class="android.widget.FrameLayout"
                    text=""
                    visible="true"
                    bounds="[0,2200][1080,2400]" />
            </hierarchy>
            """.trimIndent()
        )
        val action = UiActionStep.OpenQuickSettings
        val post = UiActionPostconditionVerifier.verify(action, before, genericSystemUi)

        val result = UiGoalVerifier.verify(
            goal = "open quick settings",
            action = action,
            before = before,
            after = genericSystemUi,
            transportStatus = UiTransportStatus.ACCEPTED,
            postcondition = post
        )

        assertEquals(UiVerificationStatus.STATE_CHANGED, result.status)
        assertFalse(result.isGoalVerified)
    }

    @Test
    fun `executed intermediate action with unmet goal remains EXECUTED or STATE_CHANGED`() {
        val before = simpleScreen(packageName = "com.mewmix.nabu", text = "Launcher")
        val after = simpleScreen(packageName = "com.android.chrome", text = "New tab")
        val action = UiActionStep.OpenApp("com.android.chrome")
        val post = UiActionPostconditionVerifier.verify(action, before, after)
        val candidates = listOf(DeviceAction.AppCandidate("Chrome", "com.android.chrome"))

        val result = UiGoalVerifier.verify(
            goal = "open chrome and search for weather",
            action = action,
            before = before,
            after = after,
            transportStatus = UiTransportStatus.ACCEPTED,
            postcondition = post,
            goalAppCandidates = candidates
        )

        assertTrue(
            "Expected STATE_CHANGED or EXECUTED, got ${result.status}",
            result.status == UiVerificationStatus.STATE_CHANGED || result.status == UiVerificationStatus.EXECUTED
        )
        assertFalse(result.isGoalVerified)
    }

    @Test
    fun `action with unavailable post-action observation returns UNVERIFIABLE`() {
        val before = simpleScreen(packageName = "com.android.settings", text = "Settings")
        val action = UiActionStep.OpenQuickSettings
        val post = PostconditionResult(
            status = PostconditionStatus.UNVERIFIABLE,
            detail = "No post-action screen",
            verificationStatus = UiVerificationStatus.UNVERIFIABLE,
            evidenceCategory = "post_observation_missing"
        )

        val result = UiGoalVerifier.verify(
            goal = "open quick settings",
            action = action,
            before = before,
            after = null,
            transportStatus = UiTransportStatus.ACCEPTED,
            postcondition = post
        )

        assertEquals(UiVerificationStatus.UNVERIFIABLE, result.status)
        assertFalse(result.isGoalVerified)
    }

    @Test
    fun `model Done assertion alone does not produce GOAL_VERIFIED without independent evidence`() {
        val screen = simpleScreen(packageName = "com.example.app", text = "Dashboard")

        val terminalResult = UiGoalVerifier.verifyTerminalDone(
            goal = "book a flight to Tokyo",
            currentScreen = screen,
            actionHistory = emptyList()
        )

        assertEquals(UiVerificationStatus.UNVERIFIABLE, terminalResult.status)
        assertFalse(terminalResult.isGoalVerified)
    }

    @Test
    fun `consequential send action without confirmation or composer clearance is not GOAL_VERIFIED`() {
        val before = UiTreeIndexer.parse(
            """
            <hierarchy>
              <node package="org.telegram.messenger.web"
                    resource-id="org.telegram.messenger.web:id/chat_message_edit_text"
                    class="android.widget.EditText"
                    text="Hello Alice"
                    editable="true"
                    visible="true"
                    bounds="[20,2000][800,2150]" />
              <node package="org.telegram.messenger.web"
                    resource-id="org.telegram.messenger.web:id/send_button"
                    class="android.widget.ImageButton"
                    content-desc="Send"
                    clickable="true"
                    visible="true"
                    bounds="[820,2000][1040,2150]" />
            </hierarchy>
            """.trimIndent()
        )
        // Suppose screen ID changed slightly, but composer still holds "Hello Alice" and confirmation wasn't granted
        val after = before.copy(screenId = "different_fingerprint")
        val sendBtn = before.elements.last()
        val action = UiActionStep.Tap(UiTarget(sendBtn.id, sendBtn.bounds))
        val post = UiActionPostconditionVerifier.verify(action, before, after)

        val result = UiGoalVerifier.verify(
            goal = "send message Hello Alice",
            action = action,
            before = before,
            after = after,
            transportStatus = UiTransportStatus.ACCEPTED,
            postcondition = post,
            confirmationGranted = false
        )

        assertFalse(result.isGoalVerified)
        assertTrue(result.status != UiVerificationStatus.GOAL_VERIFIED)
    }

    @Test
    fun `settings surface verification requires specific section evidence rather than bare settings package`() {
        val rootSettings = simpleScreen(
            packageName = "com.android.settings",
            text = "Settings",
            resourceId = "com.android.settings:id/homepage_container"
        )
        val wifiSettings = simpleScreen(
            packageName = "com.android.settings",
            text = "Wi-Fi",
            resourceId = "com.android.settings:id/wifi_settings"
        )

        val wrongResult = UiGoalVerifier.verifySettingsSurface(rootSettings, SettingsPage.WIFI)
        assertEquals(UiVerificationStatus.STATE_CHANGED, wrongResult.status)
        assertFalse(wrongResult.isGoalVerified)

        val rightResult = UiGoalVerifier.verifySettingsSurface(wifiSettings, SettingsPage.WIFI)
        assertEquals(UiVerificationStatus.GOAL_VERIFIED, rightResult.status)
        assertTrue(rightResult.isGoalVerified)
    }

    private fun simpleScreen(
        packageName: String,
        text: String,
        resourceId: String = "$packageName:id/content"
    ): UiScreenState = UiTreeIndexer.parse(
        """
        <hierarchy>
          <node package="$packageName"
                resource-id="$resourceId"
                class="android.widget.TextView"
                text="$text"
                clickable="true"
                visible="true"
                bounds="[0,100][1080,400]" />
        </hierarchy>
        """.trimIndent()
    )
}

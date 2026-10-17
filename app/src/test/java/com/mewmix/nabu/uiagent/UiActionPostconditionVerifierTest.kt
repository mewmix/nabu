package com.mewmix.nabu.uiagent

import org.junit.Assert.assertEquals
import org.junit.Test

class UiActionPostconditionVerifierTest {
    @Test
    fun verifiesTextAfterElementIdChangesWithItsContent() {
        val before = screenWithInput("")
        val after = screenWithInput("hello from Nabu")
        val source = before.elements.single()

        val result = UiActionPostconditionVerifier.verify(
            UiActionStep.TypeText(
                text = "hello from Nabu",
                target = UiTarget(source.id, source.bounds)
            ),
            before,
            after
        )

        assertEquals(PostconditionStatus.VERIFIED, result.status)
    }

    @Test
    fun failsWhenSetTextReportsSuccessButComposerDidNotChange() {
        val before = screenWithInput("")
        val after = screenWithInput("")
        val source = before.elements.single()

        val result = UiActionPostconditionVerifier.verify(
            UiActionStep.TypeText("expected", UiTarget(source.id, source.bounds)),
            before,
            after
        )

        assertEquals(PostconditionStatus.FAILED, result.status)
    }

    @Test
    fun normalizesWhitespaceWhenVerifyingComposerText() {
        val before = screenWithInput("")
        val after = screenWithInput("hello\u00a0  from\nNabu")

        val result = UiActionPostconditionVerifier.verify(
            UiActionStep.TypeText("hello from Nabu", UiTarget(before.elements.single().id, null)),
            before,
            after
        )

        assertEquals(PostconditionStatus.VERIFIED, result.status)
    }

    @Test
    fun verifiesSemanticFocusState() {
        val before = screenWithInput("")
        val source = before.elements.single().copy(focused = false)
        val afterElement = source.copy(focused = true)

        val result = UiActionPostconditionVerifier.verify(
            UiActionStep.NodeAction("focus", UiTarget(source.id, source.bounds)),
            before.copy(elements = listOf(source), screenId = "before"),
            before.copy(elements = listOf(afterElement), screenId = "after")
        )

        assertEquals(PostconditionStatus.VERIFIED, result.status)
    }

    @Test
    fun verifiesSetProgressAgainstRangeInfo() {
        val base = screenWithInput("")
        val source = base.elements.single().copy(range = UiRange(0, 0f, 100f, 10f))
        val destination = source.copy(range = source.range?.copy(current = 42f))

        val result = UiActionPostconditionVerifier.verify(
            UiActionStep.NodeAction(
                "set_progress",
                UiTarget(source.id, source.bounds),
                mapOf("value" to "42")
            ),
            base.copy(elements = listOf(source), screenId = "before"),
            base.copy(elements = listOf(destination), screenId = "after")
        )

        assertEquals(PostconditionStatus.VERIFIED, result.status)
    }

    @Test
    fun `openApp postcondition accepts matching package`() {
        val before = screenWithInput("").copy(packageName = "com.mewmix.nabu")
        val after = screenWithInput("").copy(packageName = "com.android.chrome")

        val result = UiActionPostconditionVerifier.verify(
            UiActionStep.OpenApp("com.android.chrome"),
            before,
            after
        )

        assertEquals(PostconditionStatus.VERIFIED, result.status)
    }

    @Test
    fun `openApp postcondition rejects wrong package`() {
        val before = screenWithInput("").copy(packageName = "com.mewmix.nabu")
        val intermediate = screenWithInput("").copy(packageName = "com.android.launcher")

        val result = UiActionPostconditionVerifier.verify(
            UiActionStep.OpenApp("com.android.chrome"),
            before,
            intermediate
        )

        assertEquals(PostconditionStatus.FAILED, result.status)
    }

    @Test
    fun `generic tap screen mutation returns STATE_CHANGED instead of VERIFIED`() {
        val before = screenWithInput("").copy(packageName = "com.android.settings", screenId = "screen_1")
        val after = screenWithInput("").copy(packageName = "com.android.settings", screenId = "screen_2")
        val target = before.elements.single()

        val result = UiActionPostconditionVerifier.verify(
            UiActionStep.Tap(UiTarget(target.id, target.bounds)),
            before,
            after
        )

        assertEquals(PostconditionStatus.STATE_CHANGED, result.status)
        assertEquals(UiVerificationStatus.STATE_CHANGED, result.verificationStatus)
    }

    @Test
    fun `generic systemui package visibility does not verify OpenQuickSettings`() {
        val before = screenWithInput("").copy(packageName = "com.android.settings", screenId = "settings")
        val genericSystemUi = UiTreeIndexer.parse(
            """
            <hierarchy>
              <node package="com.android.systemui"
                    resource-id="com.android.systemui:id/status_bar_container"
                    class="android.widget.FrameLayout"
                    text="12:00"
                    visible="true"
                    bounds="[0,0][1080,100]" />
            </hierarchy>
            """.trimIndent()
        )

        val result = UiActionPostconditionVerifier.verify(
            UiActionStep.OpenQuickSettings,
            before,
            genericSystemUi
        )

        assertEquals(PostconditionStatus.FAILED, result.status)
        assertEquals(UiVerificationStatus.STATE_CHANGED, result.verificationStatus)
    }

    @Test
    fun `notification shade visibility fails OpenQuickSettings verification`() {
        val before = screenWithInput("").copy(packageName = "com.android.settings", screenId = "settings")
        val notificationShade = UiTreeIndexer.parse(
            """
            <hierarchy>
              <node package="com.android.systemui"
                    resource-id="com.android.systemui:id/notification_stack_scroller"
                    class="android.view.ViewGroup"
                    text="Clear all notifications"
                    visible="true"
                    bounds="[0,0][1080,2400]" />
            </hierarchy>
            """.trimIndent()
        )

        val result = UiActionPostconditionVerifier.verify(
            UiActionStep.OpenQuickSettings,
            before,
            notificationShade
        )

        assertEquals(PostconditionStatus.FAILED, result.status)
        assertEquals(UiVerificationStatus.FAILED, result.verificationStatus)
    }

    @Test
    fun `quick settings tiles verify OpenQuickSettings`() {
        val before = screenWithInput("").copy(packageName = "com.android.settings", screenId = "settings")
        val quickSettings = UiTreeIndexer.parse(
            """
            <hierarchy>
              <node package="com.android.systemui"
                    resource-id="com.android.systemui:id/qs_frame"
                    class="android.widget.FrameLayout"
                    content-desc="Quick settings"
                    visible="true"
                    bounds="[0,0][1080,2400]">
                <node package="com.android.systemui"
                      resource-id="com.android.systemui:id/tile_label"
                      class="android.widget.Switch"
                      text="Wi-Fi"
                      checkable="true"
                      checked="true"
                      clickable="true"
                      visible="true"
                      bounds="[50,200][500,380]" />
                <node package="com.android.systemui"
                      resource-id="com.android.systemui:id/tile_label"
                      class="android.widget.Switch"
                      text="Bluetooth"
                      checkable="true"
                      checked="false"
                      clickable="true"
                      visible="true"
                      bounds="[550,200][1000,380]" />
              </node>
            </hierarchy>
            """.trimIndent()
        )

        val result = UiActionPostconditionVerifier.verify(
            UiActionStep.OpenQuickSettings,
            before,
            quickSettings
        )

        assertEquals(PostconditionStatus.VERIFIED, result.status)
        assertEquals(UiVerificationStatus.GOAL_VERIFIED, result.verificationStatus)
    }

    private fun screenWithInput(text: String): UiScreenState = UiTreeIndexer.parse(
        """
        <hierarchy>
          <node package="org.telegram.messenger.web"
                resource-id="org.telegram.messenger.web:id/chat_message_edit_text"
                class="android.widget.EditText"
                text="$text"
                hint="Message"
                editable="true"
                focusable="true"
                focused="true"
                visible="true"
                bounds="[20,2000][900,2150]" />
        </hierarchy>
        """.trimIndent()
    )
}

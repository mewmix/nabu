package com.mewmix.nabu.uiagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiActionValidatorTest {
    private val screen = UiTreeIndexer.parse(
        """
        <hierarchy>
          <node package="com.android.settings" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" enabled="true">
            <node text="Dark mode" resource-id="android:id/title" class="android.widget.TextView" bounds="[48,220][420,280]" enabled="true"/>
            <node content-desc="Dark mode" resource-id="android:id/switch_widget" class="android.widget.Switch" bounds="[920,215][1010,285]" clickable="true" enabled="true" checkable="true" checked="false"/>
            <node text="Send" class="android.widget.Button" bounds="[800,1900][1020,2050]" clickable="true" enabled="true"/>
            <node class="android.widget.EditText" bounds="[60,1500][1020,1650]" clickable="true" enabled="true" password="true" editable="true"/>
          </node>
        </hierarchy>
        """.trimIndent()
    )

    @Test
    fun parserAllowsOneActionAndTrailingAssertion() {
        val toggle = screen.elements.first { it.resourceId == "android:id/switch_widget" }
        val plan = UiActionPlanParser.parse(
            """{
              "goal":"Turn on dark mode",
              "screen_id":"${screen.screenId}",
              "steps":[
                {"action":"tap","target":{"element_id":"${toggle.id}"}},
                {"action":"assert","condition":{"element_id":"${toggle.id}","checked":true}}
              ]
            }"""
        )

        assertEquals(2, plan.steps.size)
        assertEquals(UiPlanDecision.Allow, UiActionValidator.validate(plan, screen))
    }

    @Test
    fun parserRejectsLongPlans() {
        val result = runCatching {
            UiActionPlanParser.parse(
                """{"goal":"navigate","screen_id":"${screen.screenId}","steps":[
                  {"action":"press_back"},{"action":"press_home"},{"action":"wait","ms":100}
                ]}"""
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun validatorRejectsStaleScreenAndPasswordTargets() {
        val password = screen.elements.first { it.password }
        val stale = UiActionPlan("goal", "stale", listOf(UiActionStep.PressBack))
        assertTrue(UiActionValidator.validate(stale, screen) is UiPlanDecision.Invalid)

        val passwordPlan = UiActionPlan(
            "Enter password",
            screen.screenId,
            listOf(UiActionStep.TypeText("secret", UiTarget(password.id, null)))
        )
        assertTrue(UiActionValidator.validate(passwordPlan, screen) is UiPlanDecision.Block)
    }

    @Test
    fun validatorRequiresConfirmationForSending() {
        val send = screen.elements.first { it.text == "Send" }
        val plan = UiActionPlan(
            "Send this message",
            screen.screenId,
            listOf(UiActionStep.Tap(UiTarget(send.id, null)))
        )

        assertTrue(UiActionValidator.validate(plan, screen) is UiPlanDecision.RequireConfirmation)
    }

    @Test
    fun parserAllowsDoneWhenGoalIsAlreadySatisfied() {
        val plan = UiActionPlanParser.parse(
            """{"goal":"Turn on dark mode","screen_id":"${screen.screenId}","steps":[
              {"action":"done","summary":"Dark mode is already enabled."}
            ]}"""
        )

        assertTrue(plan.steps.single() is UiActionStep.Done)
        assertEquals(UiPlanDecision.Allow, UiActionValidator.validate(plan, screen))
    }

    @Test
    fun plannerParserNormalizesCompactActionEnvelope() {
        val target = screen.elements.first { it.resourceId == "android:id/switch_widget" }
        val plan = UiActionPlanParser.parsePlannerOutput(
            rawJson = """{"action":"tap","element_id":"${target.id}"}""",
            knownGoal = "Turn on dark mode",
            knownScreenId = screen.screenId
        )

        assertEquals("Turn on dark mode", plan.goal)
        assertEquals(screen.screenId, plan.screenId)
        assertEquals(target.id, (plan.steps.single() as UiActionStep.Tap).target.elementId)
        assertEquals(UiPlanDecision.Allow, UiActionValidator.validate(plan, screen))
    }

    @Test
    fun screenFingerprintChangesWhenCheckedStateChangesButElementIdDoesNot() {
        val uncheckedXml = """<hierarchy><node package="p" class="android.widget.Switch" bounds="[0,0][10,10]" checked="false"/></hierarchy>"""
        val checkedXml = uncheckedXml.replace("checked=\"false\"", "checked=\"true\"")
        val unchecked = UiTreeIndexer.parse(uncheckedXml)
        val checked = UiTreeIndexer.parse(checkedXml)

        assertEquals(unchecked.elements.single().id, checked.elements.single().id)
        assertTrue(unchecked.screenId != checked.screenId)
    }
}

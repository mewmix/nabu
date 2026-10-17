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
            <node resource-id="android:id/search_src_text" class="android.widget.EditText" bounds="[60,350][1020,470]" clickable="true" enabled="true" editable="true"/>
            <node resource-id="android:id/list" class="android.widget.ScrollView" bounds="[0,500][1080,1800]" enabled="true" scrollable="true"/>
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
    fun validatorChecksOnlyCurrentExecutionSliceFromMultipleActions() {
        val horizon = UiActionPlanParser.parse(
            """{"goal":"navigate","screen_id":"${screen.screenId}","steps":[
              {"action":"press_back"},{"action":"press_home"},{"action":"wait","ms":100}
            ]}"""
        )

        assertEquals(3, horizon.executableSteps.size)
        val current = horizon.firstExecutionSlice()
        assertEquals(listOf(UiActionStep.PressBack), current.steps)
        assertEquals(UiPlanDecision.Allow, UiActionValidator.validate(current, screen))
    }

    @Test
    fun validatorAllowsOnScreenBoundsWhenElementIdIsStale() {
        val plan = UiActionPlan(
            "Tap dark mode",
            screen.screenId,
            listOf(UiActionStep.Tap(UiTarget("e_stale", UiBounds(920, 215, 1010, 285))))
        )

        assertEquals(UiPlanDecision.Allow, UiActionValidator.validate(plan, screen))
    }

    @Test
    fun validatorDoesNotBlockActionForStaleOptionalAssertionId() {
        val toggle = screen.elements.first { it.resourceId == "android:id/switch_widget" }
        val plan = UiActionPlan(
            "Turn on dark mode",
            screen.screenId,
            listOf(
                UiActionStep.Tap(UiTarget(toggle.id, null)),
                UiActionStep.Assert(UiAssertion("e_stale", null, true))
            )
        )

        assertEquals(UiPlanDecision.Allow, UiActionValidator.validate(plan, screen))
    }

    @Test
    fun plannerAliasesResolveToIndexedElements() {
        val plannerElements = screen.plannerElements()

        assertTrue(plannerElements.isNotEmpty())
        assertEquals(plannerElements.first(), screen.element("p0"))
        assertEquals(plannerElements.last(), screen.element("p${plannerElements.lastIndex}"))
    }

    @Test
    fun plannerAliasesOnlyExposeActionableElementsAndIncludeChildLabels() {
        val plannerElements = screen.plannerElements()
        val send = plannerElements.first { it.text == "Send" }

        assertTrue(plannerElements.none { it.text == "Dark mode" })
        assertEquals("Send", screen.plannerLabel(send))
    }

    @Test
    fun validatorRejectsTapOnNonClickableText() {
        val label = screen.elements.first { it.text == "Dark mode" }
        val plan = UiActionPlan(
            "Tap dark mode",
            screen.screenId,
            listOf(UiActionStep.Tap(UiTarget(label.id, null)))
        )

        assertTrue(UiActionValidator.validate(plan, screen) is UiPlanDecision.Invalid)
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
    fun sendGoalDoesNotConfirmBeforeCommitBoundary() {
        val ordinary = screen.elements.first { it.resourceId == "android:id/switch_widget" }
        val plan = UiActionPlan(
            "Send this message after navigating to Saved Messages",
            screen.screenId,
            listOf(UiActionStep.Tap(UiTarget(ordinary.id, null)))
        )

        assertEquals(UiPlanDecision.Allow, UiActionValidator.validate(plan, screen))
    }

    @Test
    fun validatorRequiresConfirmationForCameraShutter() {
        val shutter = screen.elements.first { it.text == "Send" }.copy(
            text = "Take photo",
            contentDescription = "Shutter",
            resourceId = "com.camera:id/shutter_button"
        )
        val cameraScreen = screen.copy(elements = screen.elements + shutter.copy(id = "camera_shutter"))
        val plan = UiActionPlan(
            "Take a selfie",
            cameraScreen.screenId,
            listOf(UiActionStep.Tap(UiTarget("camera_shutter", null)))
        )

        assertTrue(UiActionValidator.validate(plan, cameraScreen) is UiPlanDecision.RequireConfirmation)
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
    fun plannerParserNormalizesTapTextInCompactAndStepEnvelopes() {
        val target = screen.elements.first { it.resourceId == "android:id/switch_widget" }
        val compactPlan = UiActionPlanParser.parsePlannerOutput(
            rawJson = """{"action":"tap_text","element_id":"${target.id}"}""",
            knownGoal = "Turn on dark mode",
            knownScreenId = screen.screenId
        )
        val stepPlan = UiActionPlanParser.parse(
            """{"goal":"Turn on dark mode","screen_id":"${screen.screenId}","steps":[
              {"action":"tap_text","element_id":"${target.id}"}
            ]}"""
        )

        listOf(compactPlan, stepPlan).forEach { plan ->
            assertEquals(target.id, (plan.steps.single() as UiActionStep.Tap).target.elementId)
            assertEquals(UiPlanDecision.Allow, UiActionValidator.validate(plan, screen))
        }
    }

    @Test
    fun plannerParserInterpretsCompositeActionOnlyWhenPayloadShapeIsUnambiguous() {
        val target = screen.elements.first { it.resourceId == "android:id/switch_widget" }
        val targeted = UiActionPlanParser.parsePlannerOutput(
            rawJson = """{"action":"tap|press_back","element_id":"${target.id}"}""",
            knownGoal = "Turn on dark mode",
            knownScreenId = screen.screenId
        )
        val targetless = UiActionPlanParser.parsePlannerOutput(
            rawJson = """{"action":"tap|press_back"}""",
            knownGoal = "Go back",
            knownScreenId = screen.screenId
        )

        assertTrue(targeted.steps.single() is UiActionStep.Tap)
        assertEquals(UiActionStep.PressBack, targetless.steps.single())
        assertEquals(UiPlanDecision.Allow, UiActionValidator.validate(targeted, screen))
        assertEquals(UiPlanDecision.Allow, UiActionValidator.validate(targetless, screen))
    }

    @Test
    fun pressBackIgnoresSpuriousPlannerTarget() {
        val plan = UiActionPlanParser.parsePlannerOutput(
            rawJson = """{"action":"press_back","element_id":"p0"}""",
            knownGoal = "Go back",
            knownScreenId = screen.screenId
        )

        assertEquals(UiActionStep.PressBack, plan.steps.single())
        assertEquals(UiPlanDecision.Allow, UiActionValidator.validate(plan, screen))
    }

    @Test
    fun plannerResolvesSelectorIdAndUniqueVisibleLabelAliases() {
        val toggle = screen.elements.first { it.resourceId == "android:id/switch_widget" }
        val selectorPlan = UiActionPlanParser.parsePlannerOutput(
            rawJson = """{"action":"tap","selector_id":"${toggle.id}"}""",
            knownGoal = "Turn on dark mode",
            knownScreenId = screen.screenId
        ).resolveElementReferences(screen)
        val labelPlan = UiActionPlanParser.parsePlannerOutput(
            rawJson = """{"action":"tap_text","text_contains":"Dark mode"}""",
            knownGoal = "Turn on dark mode",
            knownScreenId = screen.screenId
        ).resolveElementReferences(screen)

        assertEquals(toggle.id, (selectorPlan.steps.single() as UiActionStep.Tap).target.elementId)
        assertEquals(toggle.id, (labelPlan.steps.single() as UiActionStep.Tap).target.elementId)
        assertEquals(UiPlanDecision.Allow, UiActionValidator.validate(selectorPlan, screen))
        assertEquals(UiPlanDecision.Allow, UiActionValidator.validate(labelPlan, screen))
    }

    @Test
    fun validatesControlUiActionSeries() {
        val toggle = screen.elements.first { it.resourceId == "android:id/switch_widget" }
        val input = screen.elements.first { it.resourceId == "android:id/search_src_text" }
        val list = screen.elements.first { it.resourceId == "android:id/list" }
        val outputs = listOf(
            """{"action":"tap_text","element_id":"${toggle.id}"}""",
            """{"action":"type_text","text":"hello","element_id":"${input.id}"}""",
            """{"action":"scroll","direction":"down","element_id":"${list.id}"}""",
            """{"action":"press_back"}""",
            """{"action":"wait","ms":250}""",
            """{"action":"done","summary":"Finished the requested UI flow."}"""
        )

        val plans = outputs.map { output ->
            UiActionPlanParser.parsePlannerOutput(output, "Complete UI flow", screen.screenId)
        }

        assertTrue(plans[0].steps.single() is UiActionStep.Tap)
        assertTrue(plans[1].steps.single() is UiActionStep.TypeText)
        assertTrue(plans[2].steps.single() is UiActionStep.Scroll)
        assertEquals(UiActionStep.PressBack, plans[3].steps.single())
        assertEquals(UiActionStep.Wait(250), plans[4].steps.single())
        assertTrue(plans[5].steps.single() is UiActionStep.Done)
        plans.forEach { plan ->
            assertEquals(UiPlanDecision.Allow, UiActionValidator.validate(plan, screen))
        }
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

    @Test
    fun consequentialGestureCannotBypassTargetConfirmation() {
        val send = screen.elements.first { it.text == "Send" }
        val plan = UiActionPlan(
            "Send the message",
            screen.screenId,
            listOf(UiActionStep.Gesture("tap_point", UiTarget(send.id, send.bounds)))
        )

        assertTrue(UiActionValidator.validate(plan, screen) is UiPlanDecision.RequireConfirmation)
    }

    @Test
    fun nodeActionMustBeAdvertisedAndProgressMustBeInRange() {
        val base = screen.elements.first { it.resourceId == "android:id/switch_widget" }
        val slider = base.copy(
            standardActions = setOf("set_progress"),
            range = UiRange(0, 0f, 100f, 25f)
        )
        val capabilityScreen = screen.copy(elements = screen.elements.map { if (it.id == base.id) slider else it })
        val allowed = UiActionPlan(
            "Set slider",
            capabilityScreen.screenId,
            listOf(
                UiActionStep.NodeAction(
                    "set_progress",
                    UiTarget(slider.id, slider.bounds),
                    mapOf("value" to "42")
                )
            )
        )
        val outOfRange = allowed.copy(
            steps = listOf(
                UiActionStep.NodeAction(
                    "set_progress",
                    UiTarget(slider.id, slider.bounds),
                    mapOf("value" to "142")
                )
            )
        )

        assertEquals(UiPlanDecision.Allow, UiActionValidator.validate(allowed, capabilityScreen))
        assertTrue(UiActionValidator.validate(outOfRange, capabilityScreen) is UiPlanDecision.Invalid)
    }

    @Test
    fun dangerousCustomActionLabelRequiresConfirmation() {
        val base = screen.elements.first { it.resourceId == "android:id/switch_widget" }
        val target = base.copy(
            text = null,
            contentDescription = null,
            resourceId = "fixture:id/item",
            customActions = listOf(UiCustomActionRef("ca0", "Delete", 0x01000001))
        )
        val customScreen = screen.copy(elements = screen.elements.map { if (it.id == base.id) target else it })
        val plan = UiActionPlan(
            "Use the available action",
            customScreen.screenId,
            listOf(UiActionStep.CustomAction("ca0", UiTarget(target.id, target.bounds)))
        )

        assertTrue(UiActionValidator.validate(plan, customScreen) is UiPlanDecision.RequireConfirmation)
    }

    @Test
    fun `planner excludes accessibility focus only node`() {
        val nonInteractive = screen.elements.first { it.text == "Dark mode" }.copy(
            id = "non_interactive",
            clickable = false,
            editable = false,
            scrollable = false,
            checkable = false,
            longClickable = false,
            standardActions = setOf("accessibility_focus", "clear_accessibility_focus")
        )
        val customScreen = screen.copy(elements = listOf(nonInteractive))
        assertTrue(customScreen.plannerElements().isEmpty())
    }

    @Test
    fun `planner excludes show on screen only node`() {
        val nonInteractive = screen.elements.first { it.text == "Dark mode" }.copy(
            id = "non_interactive",
            clickable = false,
            editable = false,
            scrollable = false,
            checkable = false,
            longClickable = false,
            standardActions = setOf("show_on_screen")
        )
        val customScreen = screen.copy(elements = listOf(nonInteractive))
        assertTrue(customScreen.plannerElements().isEmpty())
    }

    @Test
    fun `typeText rejects non-editable target`() {
        val sendBtn = screen.elements.first { it.text == "Send" }
        val plan = UiActionPlan(
            "type text",
            screen.screenId,
            listOf(UiActionStep.TypeText("hello", UiTarget(sendBtn.id, null)))
        )

        val decision = UiActionValidator.validate(plan, screen)
        assertTrue(decision is UiPlanDecision.Invalid)
        assertTrue((decision as UiPlanDecision.Invalid).reason.contains("not editable"))
    }

    @Test
    fun `explicitTapLabel rejects semantically different target`() {
        val sendBtn = screen.elements.first { it.text == "Send" }
        val plan = UiActionPlan(
            "tap Message",
            screen.screenId,
            listOf(UiActionStep.Tap(UiTarget(sendBtn.id, null)))
        )

        val decision = UiActionValidator.validate(plan, screen)
        assertTrue(decision is UiPlanDecision.Invalid)
        assertTrue((decision as UiPlanDecision.Invalid).reason.contains("does not match the requested target 'message'"))
    }
}

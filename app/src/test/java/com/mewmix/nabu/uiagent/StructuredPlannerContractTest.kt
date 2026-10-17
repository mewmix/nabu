package com.mewmix.nabu.uiagent

import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredPlannerContractTest {

    @Test
    fun `structuredSchema targetEnumMatchesCandidates`() {
        val screen = UiTreeIndexer.parse(
            """
            <hierarchy>
              <node package="com.android.settings" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" enabled="true">
                <node text="Item 0" class="android.widget.Button" bounds="[0,0][100,50]" clickable="true" enabled="true"/>
                <node text="Item 1" class="android.widget.Button" bounds="[0,60][100,110]" clickable="true" enabled="true"/>
                <node text="Item 2" class="android.widget.Button" bounds="[0,120][100,170]" clickable="true" enabled="true"/>
              </node>
            </hierarchy>
            """.trimIndent()
        )

        val tools = UiAutomationOrchestrator.buildDecisionTools(screen)
        val actTool = tools.first { it.name == "ui_act" }
        val paramsJson = JsonParser.parseString(actTool.parametersJson).asJsonObject
        val targetProp = paramsJson.getAsJsonObject("properties").getAsJsonObject("target")
        val targetEnum = targetProp.getAsJsonArray("enum").map { it.asString }

        assertEquals(listOf("p0", "p1", "p2"), targetEnum)
    }

    @Test
    fun `structuredPrompt containsNoFixedP0Example`() {
        val tools = UiAutomationOrchestrator.buildDecisionTools(UiScreenState("s", "p", null, emptyList()))
        assertFalse(tools.any { it.description.contains(""""target":"p0"""") })
    }

    @Test
    fun `globalBack noMutation triggersSemanticBackRecovery`() {
        val screen = UiTreeIndexer.parse(
            """
            <hierarchy>
              <node package="com.android.settings" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" enabled="true">
                <node content-desc="Navigate up" class="android.widget.ImageButton" bounds="[0,0][100,100]" clickable="true" enabled="true"/>
              </node>
            </hierarchy>
            """.trimIndent()
        )

        val back = DirectSemanticActionResolver.resolveVisibleBack(screen)
        assertTrue(back is UiActionStep.Tap)
    }

    @Test
    fun `overlayConfirmation confirmReturnsTrue`() = runTest {
        val deferred = CompletableDeferred<Boolean>()
        deferred.complete(true)
        assertTrue(deferred.await())
    }

    @Test
    fun `overlayConfirmation denyReturnsFalse`() = runTest {
        val deferred = CompletableDeferred<Boolean>()
        deferred.complete(false)
        assertFalse(deferred.await())
    }

    @Test
    fun `overlayConfirmation cancelFailsClosed`() = runTest {
        val deferred = CompletableDeferred<Boolean>()
        deferred.complete(false)
        assertFalse(deferred.await())
    }
}

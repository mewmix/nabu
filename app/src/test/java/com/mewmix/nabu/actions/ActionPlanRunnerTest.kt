package com.mewmix.nabu.actions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mewmix.nabu.tools.ToolCall
import com.mewmix.nabu.tools.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActionPlanRunnerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun run_executesStepsInOrder() {
        val calls = mutableListOf<ToolCall>()
        val action = ScheduledAction(
            id = "action-1",
            title = "Morning setup",
            instruction = "Run setup",
            triggerAtEpochMs = System.currentTimeMillis(),
            steps = listOf(
                ActionStep("step-1", "Save memory", "save_memory", mapOf("fact" to "one")),
                ActionStep("step-2", "Read memory", "retrieve_memory")
            )
        )

        val run = ActionPlanRunner.run(context, action) { _, call ->
            calls += call
            ToolResult(call.toolName, "ok:${call.toolName}")
        }

        assertEquals(ActionRun.STATUS_SUCCEEDED, run.status)
        assertEquals(listOf("save_memory", "retrieve_memory"), calls.map { it.toolName })
        assertEquals(2, run.stepResults.size)
    }

    @Test
    fun run_stopsAfterErrorByDefault() {
        val calls = mutableListOf<ToolCall>()
        val action = ScheduledAction(
            id = "action-1",
            title = "Stop on failure",
            instruction = "Run setup",
            triggerAtEpochMs = System.currentTimeMillis(),
            steps = listOf(
                ActionStep("step-1", "Weather", "get_weather"),
                ActionStep("step-2", "Memory", "retrieve_memory")
            )
        )

        val run = ActionPlanRunner.run(context, action) { _, call ->
            calls += call
            ToolResult(call.toolName, "failed", isError = true)
        }

        assertEquals(ActionRun.STATUS_FAILED, run.status)
        assertEquals(listOf("get_weather"), calls.map { it.toolName })
        assertTrue(run.summary.contains("skipped 1"))
    }

    @Test
    fun effectiveSteps_mapsLegacyToolCallToSingleStep() {
        val action = ScheduledAction(
            id = "legacy",
            title = "Weather",
            instruction = "Check weather",
            triggerAtEpochMs = System.currentTimeMillis(),
            toolName = "get_weather",
            toolArguments = mapOf("location" to "Seattle")
        )

        val steps = action.effectiveSteps()

        assertEquals(1, steps.size)
        assertEquals("get_weather", steps.first().toolName)
        assertEquals("Seattle", steps.first().toolArguments["location"])
    }
}

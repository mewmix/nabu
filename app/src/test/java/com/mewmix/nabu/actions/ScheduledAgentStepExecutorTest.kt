package com.mewmix.nabu.actions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScheduledAgentStepExecutorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun execute_requiresInstruction() {
        val result = ScheduledAgentStepExecutor.execute(
            context,
            ScheduledAction(
                id = "action",
                title = "Agent",
                instruction = "Run agent",
                triggerAtEpochMs = System.currentTimeMillis()
            ),
            ActionStep(
                id = "step",
                title = "Agent",
                toolName = ActionTools.SCHEDULED_AGENT_STEP_TOOL,
                toolArguments = mapOf("model_id" to "model")
            )
        )

        assertTrue(result.isError)
        assertEquals("Missing required parameter: instruction", result.output)
    }

    @Test
    fun execute_requiresModelId() {
        val result = ScheduledAgentStepExecutor.execute(
            context,
            ScheduledAction(
                id = "action",
                title = "Agent",
                instruction = "Run agent",
                triggerAtEpochMs = System.currentTimeMillis()
            ),
            ActionStep(
                id = "step",
                title = "Agent",
                toolName = ActionTools.SCHEDULED_AGENT_STEP_TOOL,
                toolArguments = mapOf("instruction" to "Summarize context.")
            )
        )

        assertTrue(result.isError)
        assertEquals("Missing required parameter: model_id", result.output)
    }
}

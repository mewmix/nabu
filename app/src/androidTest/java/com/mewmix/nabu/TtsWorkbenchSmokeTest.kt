package com.mewmix.nabu

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mewmix.nabu.ui.components.TtsWorkbenchTestTags
import org.junit.Rule
import org.junit.Test

class TtsWorkbenchSmokeTest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    @Test
    fun audioWorkbenchIsReachableAndInspectable() {
        launchMainActivityForSmokeTest("Basic").use {
            composeTestRule.waitForNodeWithTag(TtsWorkbenchTestTags.AudioScreen)
            composeTestRule.onNodeWithTag(TtsWorkbenchTestTags.AudioScreen).assertIsDisplayed()
            composeTestRule.onNodeWithTag(TtsWorkbenchTestTags.ScriptInput).assertIsDisplayed()
            composeTestRule.onNodeWithTag(TtsWorkbenchTestTags.EngineSelector).assertIsDisplayed()
            composeTestRule.onNodeWithTag(TtsWorkbenchTestTags.VoiceSelector).assertIsDisplayed()
            composeTestRule.onNodeWithTag(TtsWorkbenchTestTags.PreviewButton).assertIsDisplayed()
            composeTestRule.onNodeWithTag(TtsWorkbenchTestTags.RenderFullButton).assertIsDisplayed()
            composeTestRule.onNodeWithTag(TtsWorkbenchTestTags.PlaybackControls).assertIsDisplayed()

            composeTestRule.onNodeWithText("Advanced engine controls").performClick()
            composeTestRule.onNodeWithTag(TtsWorkbenchTestTags.ParameterControls).assertIsDisplayed()
        }
    }
}

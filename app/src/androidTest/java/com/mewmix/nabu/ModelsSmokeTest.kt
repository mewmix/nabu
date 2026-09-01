package com.mewmix.nabu

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import com.mewmix.nabu.data.ModelTestTags
import org.junit.Rule
import org.junit.Test

class ModelsSmokeTest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    @Test
    fun ttsModelRowsAreReachableAndHaveSafeAutomationHooks() {
        launchMainActivityForSmokeTest("Models").use {
            composeTestRule.waitForNodeWithTag(ModelTestTags.Screen)
            composeTestRule.onNodeWithTag(ModelTestTags.Screen).assertIsDisplayed()
            composeTestRule.onNodeWithTag(ModelTestTags.ImportLocalModelButton).assertIsDisplayed()

            listOf(
                "kokoro-fp16",
                "kokoro-int8",
                "supertonic-2-onnx",
                "supertonic-3-onnx",
                "soprano-80m-onnx"
            ).forEach { modelId ->
                val rowTag = ModelTestTags.row(modelId)
                composeTestRule.onNodeWithTag(ModelTestTags.List)
                    .performScrollToNode(hasTestTag(rowTag))
                composeTestRule.onNodeWithTag(rowTag)
                    .assertIsDisplayed()
                    .assert(
                        hasAnyDescendant(hasTestTag(ModelTestTags.downloadButton(modelId))) or
                            hasAnyDescendant(hasTestTag(ModelTestTags.deleteButton(modelId)))
                    )
            }
        }
    }
}

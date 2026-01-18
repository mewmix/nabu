package com.mewmix.nabu

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreensTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun testNavigationToAllScreens() {
        // Basic Screen (Default)
        // Check for specific UI elements unique to Basic Screen
        composeTestRule.onNodeWithText("TEXT TO SPEAK").assertIsDisplayed()
        composeTestRule.onNodeWithText("STYLE").assertIsDisplayed()

        // Navigate to Mixer
        composeTestRule.onNodeWithText("MIXER").performClick()
        // Check for Mixer elements
        composeTestRule.onNodeWithText("VOICE A").assertIsDisplayed()

        // Navigate to Book
        composeTestRule.onNodeWithText("BOOK").performClick()
        // Check for Book elements
        composeTestRule.onNodeWithText("OPEN EBOOK").assertIsDisplayed()

        // Navigate to More menu
        composeTestRule.onNodeWithText("MORE").performClick()
        
        // Navigate to Settings
        composeTestRule.onNodeWithText("SETTINGS").performClick()
        // Check for Settings elements
        composeTestRule.onNodeWithText("Debug Mode").assertIsDisplayed()
        
        // Go back (More menu is not persistent in stack usually, but let's check hierarchy)
        // Note: Back handling in test might require explicit Espresso calls or ensuring BackHandler logic.
        // For now, let's just verify we got to Settings.
    }
}

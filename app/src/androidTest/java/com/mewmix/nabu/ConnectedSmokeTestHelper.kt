package com.mewmix.nabu

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.mewmix.nabu.utils.SettingsManager

fun launchMainActivityForSmokeTest(startScreen: String): ActivityScenario<MainActivity> {
    val context = ApplicationProvider.getApplicationContext<Context>()
    SettingsManager.setInitComplete(context, true)
    SettingsManager.setOptionalPermissionsReviewed(context, true)
    SettingsManager.setLastMainScreen(context, startScreen)
    val intent = Intent(context, MainActivity::class.java)
        .putExtra(EXTRA_START_SCREEN, startScreen)
    return ActivityScenario.launch(intent)
}

fun ComposeTestRule.waitForNodeWithTag(tag: String) {
    waitUntil(timeoutMillis = 10_000) {
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }
}

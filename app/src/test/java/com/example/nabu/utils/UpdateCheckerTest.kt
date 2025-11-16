package com.example.nabu.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `isNewerVersion returns true for higher major version`() {
        val latestVersion = "2.0.0"
        val currentVersion = "1.0.0"
        val result = UpdateChecker.isNewerVersion(latestVersion, currentVersion)
        assertTrue(result)
    }

    @Test
    fun `isNewerVersion returns true for higher minor version`() {
        val latestVersion = "1.1.0"
        val currentVersion = "1.0.0"
        val result = UpdateChecker.isNewerVersion(latestVersion, currentVersion)
        assertTrue(result)
    }

    @Test
    fun `isNewerVersion returns true for higher patch version`() {
        val latestVersion = "1.0.1"
        val currentVersion = "1.0.0"
        val result = UpdateChecker.isNewerVersion(latestVersion, currentVersion)
        assertTrue(result)
    }

    @Test
    fun `isNewerVersion returns false for same version`() {
        val latestVersion = "1.0.0"
        val currentVersion = "1.0.0"
        val result = UpdateChecker.isNewerVersion(latestVersion, currentVersion)
        assertEquals(false, result)
    }

    @Test
    fun `isNewerVersion returns false for older version`() {
        val latestVersion = "1.0.0"
        val currentVersion = "2.0.0"
        val result = UpdateChecker.isNewerVersion(latestVersion, currentVersion)
        assertEquals(false, result)
    }
}

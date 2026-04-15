package com.mewmix.nabu.actions

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AlarmTimerActionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AlarmTimerAction.resetForTesting()
    }

    @After
    fun tearDown() {
        AlarmTimerAction.resetForTesting()
    }

    @Test
    fun setAlarm_launchesAlarmIntentWithExpectedExtras() {
        AlarmTimerAction.canResolveIntent = { _, _ -> true }

        val result = AlarmTimerAction.setAlarm(context, 7, 5, "Wake up")
        val launchedIntent = shadowOf(context as Application).nextStartedActivity

        assertEquals("Alarm set for 07:05 with message: 'Wake up'.", result.message)
        assertEquals(false, result.isError)
        assertNotNull(launchedIntent)
        assertEquals(AlarmClock.ACTION_SET_ALARM, launchedIntent.action)
        assertEquals(7, launchedIntent.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(5, launchedIntent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertEquals("Wake up", launchedIntent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertEquals(true, launchedIntent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
        assertEquals(true, launchedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun setAlarm_rejectsOutOfRangeValues() {
        val result = AlarmTimerAction.setAlarm(context, 24, 0, "Too late")

        assertEquals("Invalid alarm time. Hour must be 0-23 and minute 0-59.", result.message)
        assertEquals(true, result.isError)
        assertNull(shadowOf(context as Application).nextStartedActivity)
    }

    @Test
    fun setTimer_launchesTimerIntentWithExpectedExtras() {
        AlarmTimerAction.canResolveIntent = { _, _ -> true }

        val result = AlarmTimerAction.setTimer(context, 90, "Tea")
        val launchedIntent = shadowOf(context as Application).nextStartedActivity

        assertEquals("Timer set for 90 seconds with message: 'Tea'.", result.message)
        assertEquals(false, result.isError)
        assertNotNull(launchedIntent)
        assertEquals(AlarmClock.ACTION_SET_TIMER, launchedIntent.action)
        assertEquals(90, launchedIntent.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
        assertEquals("Tea", launchedIntent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertEquals(true, launchedIntent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
    }

    @Test
    fun setTimer_returnsHelpfulErrorWhenNoHandlerExists() {
        AlarmTimerAction.canResolveIntent = { _, _ -> false }

        val result = AlarmTimerAction.setTimer(context, 30, "Break")

        assertEquals("No timer app is available on this device.", result.message)
        assertEquals(true, result.isError)
        assertNull(shadowOf(context as Application).nextStartedActivity)
    }
}

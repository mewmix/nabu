package com.mewmix.nabu.uiagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTreeIndexerTest {
    private val xml = """
        <hierarchy>
          <node package="com.android.settings" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" enabled="true">
            <node text="Wi-Fi" resource-id="android:id/title" class="android.widget.TextView" bounds="[48,220][220,280]" enabled="true"/>
            <node text="" content-desc="Wi-Fi" resource-id="android:id/switch_widget" class="android.widget.Switch" bounds="[920,215][1010,285]" clickable="true" enabled="true" checkable="true" checked="false"/>
          </node>
        </hierarchy>
    """.trimIndent()

    @Test
    fun parseBuildsStableIndexedTree() {
        val first = UiTreeIndexer.parse(xml, activityName = "WifiSettingsActivity")
        val second = UiTreeIndexer.parse(xml, activityName = "WifiSettingsActivity")

        assertEquals("com.android.settings", first.packageName)
        assertEquals(3, first.elements.size)
        assertEquals(first.screenId, second.screenId)
        assertEquals(first.elements.map { it.id }, second.elements.map { it.id })
        val toggle = first.elements.first { it.resourceId == "android:id/switch_widget" }
        assertTrue(toggle.clickable)
        assertEquals(UiBounds(920, 215, 1010, 285), toggle.bounds)
        assertNotNull(toggle.parentId)
    }

    @Test
    fun parseRejectsDoctype() {
        val unsafe = """<!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><hierarchy/>"""

        val result = runCatching { UiTreeIndexer.parse(unsafe) }

        assertTrue(result.isFailure)
    }
}

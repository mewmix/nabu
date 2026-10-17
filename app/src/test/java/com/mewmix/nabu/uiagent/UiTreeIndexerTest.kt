package com.mewmix.nabu.uiagent

import android.graphics.Rect
import com.mewmix.nabu.accessibility.SnapshotCustomAction
import com.mewmix.nabu.accessibility.SnapshotNodeAction
import com.mewmix.nabu.accessibility.UiNode
import com.mewmix.nabu.accessibility.UiRangeInfo
import com.mewmix.nabu.accessibility.UiSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTreeIndexerTest {

    @Test
    fun `captures editable hint and focus state`() {
        val screen = UiTreeIndexer.parse(
            """<hierarchy><node package="p" class="android.widget.EditText" hint="Message" editable="true" focusable="true" focused="true" selected="true" bounds="[0,0][100,50]"/></hierarchy>"""
        )

        val input = screen.elements.single()
        assertEquals("Message", input.hintText)
        assertTrue(input.focusable)
        assertTrue(input.focused)
        assertTrue(input.selected)
        assertEquals("Message", screen.plannerLabel(input))
    }

    @Test
    fun `liveSnapshot preserves hintText`() {
        val node = UiNode(
            treePath = "0",
            packageName = "org.telegram.messenger",
            resourceId = "message_input",
            className = "android.widget.EditText",
            text = "",
            contentDescription = "",
            isCheckable = false,
            isChecked = false,
            isClickable = true,
            isEnabled = true,
            isEditable = true,
            isFocusable = true,
            isFocused = false,
            isVisibleToUser = true,
            isScrollable = false,
            isLongClickable = true,
            isPassword = false,
            isSelected = false,
            boundsInScreen = Rect(0, 1000, 1080, 1100),
            children = emptyList(),
            hintText = "Message",
            standardActions = listOf(SnapshotNodeAction(16, "click", null), SnapshotNodeAction(2097152, "set_text", null))
        )
        val snapshot = UiSnapshot(
            id = "telegram_chat",
            capturedAtMs = 1L,
            packageName = "org.telegram.messenger",
            windowTitle = "Chat",
            rotation = 0,
            displayBounds = Rect(0, 0, 1080, 2400),
            rootNode = node
        )

        val element = UiTreeIndexer.build(snapshot).elements.single()
        assertEquals("Message", element.hintText)
        assertEquals("Message", UiTreeIndexer.build(snapshot).plannerLabel(element))
    }
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
        assertEquals(
            com.mewmix.nabu.accessibility.ObservedNodeIdentity.compute(
                packageName = toggle.packageName,
                resourceId = toggle.resourceId,
                text = toggle.text,
                contentDescription = toggle.contentDescription,
                className = toggle.className,
                left = toggle.bounds?.left,
                top = toggle.bounds?.top,
                right = toggle.bounds?.right,
                bottom = toggle.bounds?.bottom,
                treePath = toggle.treePath
            ),
            toggle.id
        )
    }

    @Test
    fun parseRejectsDoctype() {
        val unsafe = """<!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><hierarchy/>"""

        val result = runCatching { UiTreeIndexer.parse(unsafe) }

        assertTrue(result.isFailure)
    }

    @Test
    fun `snapshot projection preserves standard custom and range capabilities`() {
        val node = UiNode(
            treePath = "0",
            packageName = "p",
            resourceId = "slider",
            className = "android.widget.SeekBar",
            text = "Brightness",
            contentDescription = "",
            isCheckable = false,
            isChecked = false,
            isClickable = false,
            isEnabled = true,
            isEditable = false,
            isFocusable = true,
            isFocused = false,
            isVisibleToUser = true,
            isScrollable = false,
            isLongClickable = false,
            isPassword = false,
            isSelected = false,
            boundsInScreen = Rect(0, 0, 100, 20),
            children = emptyList(),
            standardActions = listOf(SnapshotNodeAction(1, "set_progress", null)),
            customActions = listOf(SnapshotCustomAction(0x01000001, "Reset")),
            rangeInfo = UiRangeInfo(0, 0f, 100f, 42f)
        )
        val snapshot = UiSnapshot(
            id = "observation",
            capturedAtMs = 1L,
            packageName = "p",
            windowTitle = "fixture",
            rotation = 0,
            displayBounds = Rect(0, 0, 100, 200),
            rootNode = node
        )

        val element = UiTreeIndexer.build(snapshot).elements.single()

        assertEquals(setOf("set_progress"), element.standardActions)
        assertEquals("ca0", element.customActions.single().ref)
        assertEquals("Reset", element.customActions.single().label)
        assertEquals(42f, element.range?.current)
        assertEquals(snapshot.stateFingerprint, UiTreeIndexer.build(snapshot).screenId)
    }

    @Test
    fun `snapshot screen identity changes with capability-sensitive fingerprint`() {
        val node = UiNode(
            treePath = "0",
            packageName = "p",
            resourceId = "action",
            className = "android.widget.Button",
            text = "Run",
            contentDescription = "",
            isCheckable = false,
            isChecked = false,
            isClickable = true,
            isEnabled = true,
            isEditable = false,
            isFocusable = true,
            isFocused = false,
            isVisibleToUser = true,
            isScrollable = false,
            isLongClickable = false,
            isPassword = false,
            isSelected = false,
            boundsInScreen = Rect(0, 0, 100, 40),
            children = emptyList(),
            standardActions = listOf(SnapshotNodeAction(16, "click", null))
        )
        val actionable = UiSnapshot("a", 1L, 1L, "p", "fixture", 0, Rect(0, 0, 100, 100), node)
        val unavailable = actionable.copy(
            id = "b",
            rootNode = node.copy(standardActions = emptyList()),
            stateFingerprint = com.mewmix.nabu.accessibility.UiSnapshotFingerprint.compute(
                "p", "fixture", 0, node.copy(standardActions = emptyList())
            )
        )

        assertEquals(actionable.stateFingerprint, UiTreeIndexer.build(actionable).screenId)
        assertNotEquals(UiTreeIndexer.build(actionable).screenId, UiTreeIndexer.build(unavailable).screenId)
    }
}

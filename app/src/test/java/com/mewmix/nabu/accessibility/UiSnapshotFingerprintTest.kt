package com.mewmix.nabu.accessibility

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSnapshotFingerprintTest {
    @Test
    fun `duplicate captures have the same state fingerprint`() {
        val first = snapshot(text = "Settings")
        val second = snapshot(text = "Settings")

        assertEquals(first.stateFingerprint, second.stateFingerprint)
    }

    @Test
    fun `semantic state change has a different fingerprint`() {
        val before = snapshot(text = "Wi-Fi")
        val after = snapshot(text = "Wi-Fi off")

        assertNotEquals(before.stateFingerprint, after.stateFingerprint)
    }

    @Test
    fun `action capability change has a different fingerprint`() {
        val before = snapshot(
            text = "Send",
            actions = listOf(SnapshotNodeAction(16, "click", "Send"))
        )
        val after = snapshot(text = "Send", actions = emptyList())

        assertNotEquals(before.stateFingerprint, after.stateFingerprint)
    }

    @Test
    fun `runtime system action change has a different fingerprint`() {
        val before = snapshot(text = "Home", systemActions = setOf("back", "home"))
        val after = snapshot(text = "Home", systemActions = setOf("back"))

        assertNotEquals(before.stateFingerprint, after.stateFingerprint)
    }

    @Test
    fun `fingerprint changes when hint changes`() {
        val before = snapshot(text = "").let {
            it.copy(
                rootNode = it.rootNode?.copy(hintText = "Message"),
                stateFingerprint = UiSnapshotFingerprint.compute(it.packageName, it.windowTitle, it.rotation, it.rootNode?.copy(hintText = "Message"))
            )
        }
        val after = snapshot(text = "").let {
            it.copy(
                rootNode = it.rootNode?.copy(hintText = "Search"),
                stateFingerprint = UiSnapshotFingerprint.compute(it.packageName, it.windowTitle, it.rotation, it.rootNode?.copy(hintText = "Search"))
            )
        }
        assertNotEquals(before.stateFingerprint, after.stateFingerprint)
    }

    @Test
    fun `promotion requires the exact latest immutable observation`() {
        val expected = snapshot(text = "Settings").copy(id = "obs", sequence = 7L, windowId = 4)

        assertTrue(expected.copy().isExactPromotionOf(expected))
        assertFalse(expected.copy(sequence = 8L).isExactPromotionOf(expected))
        assertFalse(expected.copy(id = "new-observation").isExactPromotionOf(expected))
        assertFalse(expected.copy(windowId = 5).isExactPromotionOf(expected))
        assertFalse(expected.copy(rotation = 1).isExactPromotionOf(expected))
        val changedBounds = Rect().apply {
            left = expected.displayBounds.left
            top = expected.displayBounds.top
            right = expected.displayBounds.right + 1
            bottom = expected.displayBounds.bottom
        }
        assertFalse(expected.copy(displayBounds = changedBounds).isExactPromotionOf(expected))
        assertFalse(expected.copy(stateFingerprint = "changed").isExactPromotionOf(expected))
    }

    private fun snapshot(
        text: String,
        actions: List<SnapshotNodeAction> = emptyList(),
        systemActions: Set<String> = emptySet()
    ) = UiSnapshot(
        id = java.util.UUID.randomUUID().toString(),
        capturedAtMs = 1L,
        packageName = "com.android.settings",
        windowTitle = "Settings",
        rotation = 0,
        displayBounds = Rect(0, 0, 1080, 2400),
        rootNode = UiNode(
            treePath = "0",
            packageName = "com.android.settings",
            resourceId = "android:id/content",
            className = "android.widget.FrameLayout",
            text = text,
            contentDescription = "",
            isCheckable = false,
            isChecked = false,
            isClickable = false,
            isEnabled = true,
            isEditable = false,
            isFocusable = false,
            isFocused = false,
            isVisibleToUser = true,
            isScrollable = false,
            isLongClickable = false,
            isPassword = false,
            isSelected = false,
            boundsInScreen = Rect(0, 0, 1080, 2400),
            children = emptyList(),
            standardActions = actions
        ),
        systemActions = systemActions
    )
}

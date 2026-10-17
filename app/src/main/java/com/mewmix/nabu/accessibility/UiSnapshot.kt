package com.mewmix.nabu.accessibility

import android.graphics.Rect

/**
 * A safe, lightweight representation of an AccessibilityNodeInfo that avoids
 * holding onto the original node references (which causes memory leaks).
 */
data class UiNode(
    val treePath: String,
    val packageName: String,
    val resourceId: String,
    val className: String,
    val text: String,
    val contentDescription: String,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val isClickable: Boolean,
    val isEnabled: Boolean,
    val isEditable: Boolean,
    val isFocusable: Boolean,
    val isFocused: Boolean,
    val isVisibleToUser: Boolean,
    val isScrollable: Boolean,
    val isLongClickable: Boolean,
    val isPassword: Boolean,
    val isSelected: Boolean,
    val boundsInScreen: Rect,
    val children: List<UiNode>,
    val hintText: String = "",
    val standardActions: List<SnapshotNodeAction> = emptyList(),
    val customActions: List<SnapshotCustomAction> = emptyList(),
    val movementGranularities: Int = 0,
    val rangeInfo: UiRangeInfo? = null,
    val isAccessibilityFocused: Boolean = false,
    val isContextClickable: Boolean = false,
    val textSelectionStart: Int = -1,
    val textSelectionEnd: Int = -1,
    val collectionInfo: UiCollectionInfo? = null,
    val collectionItemInfo: UiCollectionItemInfo? = null
)

data class UiRangeInfo(
    val type: Int,
    val min: Float,
    val max: Float,
    val current: Float
)

data class UiCollectionInfo(
    val rowCount: Int,
    val columnCount: Int,
    val hierarchical: Boolean,
    val selectionMode: Int
)

data class UiCollectionItemInfo(
    val rowIndex: Int,
    val rowSpan: Int,
    val columnIndex: Int,
    val columnSpan: Int,
    val heading: Boolean,
    val selected: Boolean
)

/**
 * A complete point-in-time capture of the UI state.
 */
data class UiSnapshot(
    val id: String,
    val sequence: Long = 0L,
    val capturedAtMs: Long,
    val packageName: String,
    val windowTitle: String,
    val rotation: Int,
    val displayBounds: Rect,
    val rootNode: UiNode?,
    val windowId: Int = -1,
    val systemActions: Set<String> = emptySet(),
    val stateFingerprint: String = UiSnapshotFingerprint.compute(
        packageName = packageName,
        windowTitle = windowTitle,
        rotation = rotation,
        rootNode = rootNode,
        systemActions = systemActions
    )
)

internal fun UiSnapshot.isExactPromotionOf(expected: UiSnapshot): Boolean =
    sequence == expected.sequence &&
        id == expected.id &&
        packageName == expected.packageName &&
        windowId == expected.windowId &&
        stateFingerprint == expected.stateFingerprint &&
        rotation == expected.rotation &&
        displayBounds.left == expected.displayBounds.left &&
        displayBounds.top == expected.displayBounds.top &&
        displayBounds.right == expected.displayBounds.right &&
        displayBounds.bottom == expected.displayBounds.bottom

/**
 * Stable state identity used to distinguish a real UI mutation from duplicate
 * accessibility events. This is intentionally cheaper than allocating JSON or
 * hashing a serialized hierarchy on the hot path.
 */
object UiSnapshotFingerprint {
    fun compute(
        packageName: String,
        windowTitle: String,
        rotation: Int,
        rootNode: UiNode?,
        systemActions: Set<String> = emptySet()
    ): String {
        var hash = FNV_OFFSET_BASIS

        fun add(value: String) {
            value.forEach { character ->
                hash = hash xor character.code.toLong()
                hash *= FNV_PRIME
            }
            hash = hash xor FIELD_SEPARATOR
            hash *= FNV_PRIME
        }

        fun add(value: Boolean) = add(if (value) "1" else "0")

        fun visit(node: UiNode) {
            add(node.treePath)
            add(node.packageName)
            add(node.resourceId)
            add(node.className)
            add(node.text)
            add(node.contentDescription)
            add(node.hintText)
            add(node.isCheckable)
            add(node.isChecked)
            add(node.isClickable)
            add(node.isEnabled)
            add(node.isEditable)
            add(node.isFocusable)
            add(node.isFocused)
            add(node.isVisibleToUser)
            add(node.isScrollable)
            add(node.isLongClickable)
            add(node.isPassword)
            add(node.isSelected)
            node.standardActions.sortedBy { it.actionId }.forEach { action ->
                add(action.actionId.toString())
                add(action.token)
                add(action.label.orEmpty())
            }
            node.customActions.sortedBy { it.actionId }.forEach { action ->
                add(action.actionId.toString())
                add(action.label)
            }
            add(node.movementGranularities.toString())
            add(node.isAccessibilityFocused)
            add(node.isContextClickable)
            add(node.textSelectionStart.toString())
            add(node.textSelectionEnd.toString())
            node.rangeInfo?.let { range ->
                add(range.type.toString())
                add(range.min.toString())
                add(range.max.toString())
                add(range.current.toString())
            }
            node.collectionInfo?.let { collection ->
                add(collection.rowCount.toString())
                add(collection.columnCount.toString())
                add(collection.hierarchical)
                add(collection.selectionMode.toString())
            }
            node.collectionItemInfo?.let { item ->
                add(item.rowIndex.toString())
                add(item.rowSpan.toString())
                add(item.columnIndex.toString())
                add(item.columnSpan.toString())
                add(item.heading)
                add(item.selected)
            }
            add(node.boundsInScreen.left.toString())
            add(node.boundsInScreen.top.toString())
            add(node.boundsInScreen.right.toString())
            add(node.boundsInScreen.bottom.toString())
            node.children.forEach(::visit)
        }

        add(packageName)
        add(windowTitle)
        add(rotation.toString())
        systemActions.sorted().forEach(::add)
        rootNode?.let(::visit)
        return java.lang.Long.toUnsignedString(hash, 16)
    }

    private const val FNV_OFFSET_BASIS = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L
    private const val FIELD_SEPARATOR = 0xffL
}

package com.mewmix.nabu.uiagent

data class UiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val isValid: Boolean get() = right > left && bottom > top
    fun toList(): List<Int> = listOf(left, top, right, bottom)
}

data class UiElement(
    val id: String,
    val text: String?,
    val contentDescription: String?,
    val resourceId: String?,
    val className: String?,
    val packageName: String?,
    val bounds: UiBounds?,
    val clickable: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val longClickable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val password: Boolean,
    val parentId: String?,
    val treePath: String,
    val hintText: String? = null,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val standardActions: Set<String> = emptySet(),
    val customActions: List<UiCustomActionRef> = emptyList(),
    val movementGranularities: Int = 0,
    val range: UiRange? = null,
    val accessibilityFocused: Boolean = false,
    val contextClickable: Boolean = false,
    val selectionStart: Int = -1,
    val selectionEnd: Int = -1,
    val collection: UiCollection? = null,
    val collectionItem: UiCollectionItem? = null
)

data class UiCustomActionRef(
    val ref: String,
    val label: String,
    internal val trustedActionId: Int
)

data class UiRange(
    val type: Int,
    val min: Float,
    val max: Float,
    val current: Float
)

data class UiCollection(
    val rowCount: Int,
    val columnCount: Int,
    val hierarchical: Boolean,
    val selectionMode: Int
)

data class UiCollectionItem(
    val rowIndex: Int,
    val rowSpan: Int,
    val columnIndex: Int,
    val columnSpan: Int,
    val heading: Boolean,
    val selected: Boolean
)

data class UiScreenState(
    val screenId: String,
    val packageName: String?,
    val activityName: String?,
    val elements: List<UiElement>,
    val systemActions: Set<String> = emptySet()
) {
    fun element(id: String): UiElement? {
        val plannerIndex = id.trim().lowercase().removePrefix("p").toIntOrNull()
        if (id.trim().startsWith("p", ignoreCase = true) && plannerIndex != null) {
            return plannerElements().getOrNull(plannerIndex)
        }
        val normalized = id.trim().lowercase().removePrefix("e_")
        return elements.firstOrNull { candidate ->
            candidate.id == id || candidate.id.lowercase().removePrefix("e_") == normalized
        }
    }

    fun plannerElements(limit: Int = 100): List<UiElement> = elements.asSequence()
        .filter { it.visible }
        .filter { it.isInteractive() }
        .take(limit)
        .toList()

    fun plannerLabel(element: UiElement): String? {
        val labels = linkedSetOf<String>()
        listOfNotNull(element.text, element.contentDescription, element.hintText)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach(labels::add)
        elements.asSequence()
            .filter { it.visible && it.id != element.id }
            .filter { candidate -> isDescendantOf(candidate, element.id, maxDepth = 3) }
            .flatMap { candidate -> sequenceOf(candidate.text, candidate.contentDescription) }
            .filterNotNull()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .take(4)
            .forEach(labels::add)
        if (!element.scrollable && (labels.isEmpty() || element.editable)) {
            nearestAncestor(element, maxDepth = 2)?.let { ancestor ->
                elements.asSequence()
                    .filter { candidate ->
                        candidate.visible &&
                            candidate.id != element.id &&
                            isDescendantOf(candidate, ancestor.id, maxDepth = 3)
                    }
                    .flatMap { candidate ->
                        sequenceOf(candidate.text, candidate.contentDescription, candidate.hintText)
                    }
                    .filterNotNull()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .take(2)
                    .forEach(labels::add)
            }
        }
        return labels.takeIf { it.isNotEmpty() }?.joinToString(" | ")
    }

    private fun nearestAncestor(element: UiElement, maxDepth: Int): UiElement? {
        var parentId = element.parentId
        repeat(maxDepth) {
            val parent = parentId?.let { id -> elements.firstOrNull { it.id == id } } ?: return null
            if (isCompactContextContainer(parent, element)) return parent
            parentId = parent.parentId
        }
        return null
    }

    private fun isCompactContextContainer(parent: UiElement, child: UiElement): Boolean {
        val parentBounds = parent.bounds ?: return false
        val childBounds = child.bounds ?: return false
        val parentHeight = parentBounds.bottom - parentBounds.top
        val childHeight = childBounds.bottom - childBounds.top
        return parentHeight <= maxOf(600, childHeight * 6)
    }

    private fun isDescendantOf(candidate: UiElement, ancestorId: String, maxDepth: Int): Boolean {
        var parentId = candidate.parentId
        repeat(maxDepth) {
            if (parentId == ancestorId) return true
            parentId = parentId?.let { id -> elements.firstOrNull { it.id == id }?.parentId }
                ?: return false
        }
        return false
    }
}

private val SCROLL_ACTIONS = setOf(
    "scroll_forward", "scroll_backward", "scroll_up", "scroll_down",
    "scroll_left", "scroll_right", "page_up", "page_down",
    "page_left", "page_right", "scroll_to_position", "scroll_in_direction"
)

private val INTERACTIVE_STANDARD_ACTIONS = setOf(
    "click", "long_click", "context_click", "press_and_hold", "ime_enter",
    "set_text", "copy", "cut", "paste", "set_progress", "set_selection",
    "scroll_forward", "scroll_backward", "scroll_up", "scroll_down",
    "scroll_left", "scroll_right", "page_up", "page_down", "page_left",
    "page_right", "scroll_to_position", "scroll_in_direction",
    "expand", "collapse", "dismiss"
)

fun UiElement.supports(operation: Operation): Boolean = when (operation) {
    Operation.TAP ->
        enabled && visible && !password &&
            (clickable || checkable || "click" in standardActions)
    Operation.TYPE_TEXT ->
        enabled && visible && !password &&
            (editable || "set_text" in standardActions)
    Operation.SCROLL ->
        enabled && visible &&
            (scrollable || standardActions.any { it in SCROLL_ACTIONS })
    Operation.LONG_PRESS ->
        enabled && visible && !password &&
            (longClickable || "long_click" in standardActions)
    Operation.FOCUS ->
        enabled && visible && !password &&
            (focusable || clickable || checkable || "focus" in standardActions)
    Operation.NODE_ACTION ->
        enabled && visible && standardActions.isNotEmpty()
    Operation.CUSTOM_ACTION ->
        enabled && visible && customActions.isNotEmpty()
    Operation.GESTURE ->
        enabled && visible && !password
    else -> false
}

fun UiElement.isInteractive(): Boolean =
    supports(Operation.TAP) ||
        supports(Operation.TYPE_TEXT) ||
        supports(Operation.SCROLL) ||
        supports(Operation.LONG_PRESS) ||
        customActions.isNotEmpty() ||
        standardActions.any { it in INTERACTIVE_STANDARD_ACTIONS }

fun UiElement.capabilityTokens(): List<String> = buildList {
    if (supports(Operation.TAP)) add("tap")
    if (supports(Operation.TYPE_TEXT)) add("type")
    if (supports(Operation.SCROLL)) add("scroll")
    if (supports(Operation.LONG_PRESS)) add("long_press")
    if (customActions.isNotEmpty()) add("custom")
    if ("set_progress" in standardActions || range != null) add("range")
}


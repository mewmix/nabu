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
    val treePath: String
)

data class UiScreenState(
    val screenId: String,
    val packageName: String?,
    val activityName: String?,
    val elements: List<UiElement>
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
        .filter { it.clickable || it.editable || it.scrollable || it.checkable || it.longClickable }
        .take(limit)
        .toList()

    fun plannerLabel(element: UiElement): String? {
        val labels = linkedSetOf<String>()
        listOfNotNull(element.text, element.contentDescription)
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
        return labels.takeIf { it.isNotEmpty() }?.joinToString(" | ")
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

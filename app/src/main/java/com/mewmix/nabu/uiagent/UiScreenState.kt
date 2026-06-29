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
    fun element(id: String): UiElement? = elements.firstOrNull { it.id == id }
}

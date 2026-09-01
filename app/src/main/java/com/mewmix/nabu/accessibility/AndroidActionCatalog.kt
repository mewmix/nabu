package com.mewmix.nabu.accessibility

import android.annotation.SuppressLint
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityService

enum class ActionEffectClass {
    ACTIVATION, FOCUS, TEXT, SELECTION, SCROLL, RANGE, VISIBILITY, DRAG, WINDOW
}

enum class ActionRiskClass { LOW, CONTEXTUAL, HIGH }

enum class ExpectedPostcondition {
    FOCUS_CHANGED,
    SELECTION_CHANGED,
    TEXT_CHANGED,
    SURFACE_CHANGED,
    SCROLL_CHANGED,
    RANGE_CHANGED,
    VISIBILITY_CHANGED,
    DRAG_RESULT,
    WINDOW_CHANGED
}

/** Canonical metadata for model-selectable standard AccessibilityNodeInfo actions. */
enum class StandardNodeAction(
    val token: String,
    val minimumApi: Int,
    val effectClass: ActionEffectClass,
    val riskClass: ActionRiskClass,
    val postcondition: ExpectedPostcondition,
    val scheduledAllowed: Boolean = true,
    val requiredArguments: Set<String> = emptySet()
) {
    ACCESSIBILITY_FOCUS("accessibility_focus", 16, ActionEffectClass.FOCUS, ActionRiskClass.LOW, ExpectedPostcondition.FOCUS_CHANGED),
    CLEAR_ACCESSIBILITY_FOCUS("clear_accessibility_focus", 16, ActionEffectClass.FOCUS, ActionRiskClass.LOW, ExpectedPostcondition.FOCUS_CHANGED),
    FOCUS("focus", 14, ActionEffectClass.FOCUS, ActionRiskClass.LOW, ExpectedPostcondition.FOCUS_CHANGED),
    CLEAR_FOCUS("clear_focus", 14, ActionEffectClass.FOCUS, ActionRiskClass.LOW, ExpectedPostcondition.FOCUS_CHANGED),
    SELECT("select", 14, ActionEffectClass.SELECTION, ActionRiskClass.LOW, ExpectedPostcondition.SELECTION_CHANGED),
    CLEAR_SELECTION("clear_selection", 14, ActionEffectClass.SELECTION, ActionRiskClass.LOW, ExpectedPostcondition.SELECTION_CHANGED),
    SET_SELECTION("set_selection", 18, ActionEffectClass.SELECTION, ActionRiskClass.LOW, ExpectedPostcondition.SELECTION_CHANGED, requiredArguments = setOf("selection_start", "selection_end")),
    SET_TEXT("set_text", 21, ActionEffectClass.TEXT, ActionRiskClass.CONTEXTUAL, ExpectedPostcondition.TEXT_CHANGED, requiredArguments = setOf("text")),
    COPY("copy", 18, ActionEffectClass.TEXT, ActionRiskClass.CONTEXTUAL, ExpectedPostcondition.SELECTION_CHANGED),
    CUT("cut", 18, ActionEffectClass.TEXT, ActionRiskClass.CONTEXTUAL, ExpectedPostcondition.TEXT_CHANGED),
    PASTE("paste", 18, ActionEffectClass.TEXT, ActionRiskClass.CONTEXTUAL, ExpectedPostcondition.TEXT_CHANGED),
    NEXT_AT_MOVEMENT_GRANULARITY("next_at_movement_granularity", 16, ActionEffectClass.SELECTION, ActionRiskClass.LOW, ExpectedPostcondition.SELECTION_CHANGED, requiredArguments = setOf("granularity")),
    PREVIOUS_AT_MOVEMENT_GRANULARITY("previous_at_movement_granularity", 16, ActionEffectClass.SELECTION, ActionRiskClass.LOW, ExpectedPostcondition.SELECTION_CHANGED, requiredArguments = setOf("granularity")),
    NEXT_HTML_ELEMENT("next_html_element", 16, ActionEffectClass.SELECTION, ActionRiskClass.LOW, ExpectedPostcondition.SELECTION_CHANGED, requiredArguments = setOf("html_element")),
    PREVIOUS_HTML_ELEMENT("previous_html_element", 16, ActionEffectClass.SELECTION, ActionRiskClass.LOW, ExpectedPostcondition.SELECTION_CHANGED, requiredArguments = setOf("html_element")),
    CLICK("click", 14, ActionEffectClass.ACTIVATION, ActionRiskClass.CONTEXTUAL, ExpectedPostcondition.SURFACE_CHANGED),
    LONG_CLICK("long_click", 14, ActionEffectClass.ACTIVATION, ActionRiskClass.CONTEXTUAL, ExpectedPostcondition.SURFACE_CHANGED),
    CONTEXT_CLICK("context_click", 23, ActionEffectClass.ACTIVATION, ActionRiskClass.CONTEXTUAL, ExpectedPostcondition.SURFACE_CHANGED),
    PRESS_AND_HOLD("press_and_hold", 30, ActionEffectClass.ACTIVATION, ActionRiskClass.CONTEXTUAL, ExpectedPostcondition.SURFACE_CHANGED, requiredArguments = setOf("duration_ms")),
    IME_ENTER("ime_enter", 30, ActionEffectClass.TEXT, ActionRiskClass.CONTEXTUAL, ExpectedPostcondition.SURFACE_CHANGED),
    EXPAND("expand", 19, ActionEffectClass.VISIBILITY, ActionRiskClass.LOW, ExpectedPostcondition.VISIBILITY_CHANGED),
    COLLAPSE("collapse", 19, ActionEffectClass.VISIBILITY, ActionRiskClass.LOW, ExpectedPostcondition.VISIBILITY_CHANGED),
    SHOW_ON_SCREEN("show_on_screen", 23, ActionEffectClass.VISIBILITY, ActionRiskClass.LOW, ExpectedPostcondition.VISIBILITY_CHANGED),
    SHOW_TOOLTIP("show_tooltip", 28, ActionEffectClass.VISIBILITY, ActionRiskClass.LOW, ExpectedPostcondition.VISIBILITY_CHANGED),
    HIDE_TOOLTIP("hide_tooltip", 28, ActionEffectClass.VISIBILITY, ActionRiskClass.LOW, ExpectedPostcondition.VISIBILITY_CHANGED),
    DISMISS("dismiss", 19, ActionEffectClass.VISIBILITY, ActionRiskClass.CONTEXTUAL, ExpectedPostcondition.VISIBILITY_CHANGED),
    SCROLL_FORWARD("scroll_forward", 14, ActionEffectClass.SCROLL, ActionRiskClass.LOW, ExpectedPostcondition.SCROLL_CHANGED),
    SCROLL_BACKWARD("scroll_backward", 14, ActionEffectClass.SCROLL, ActionRiskClass.LOW, ExpectedPostcondition.SCROLL_CHANGED),
    SCROLL_UP("scroll_up", 23, ActionEffectClass.SCROLL, ActionRiskClass.LOW, ExpectedPostcondition.SCROLL_CHANGED),
    SCROLL_DOWN("scroll_down", 23, ActionEffectClass.SCROLL, ActionRiskClass.LOW, ExpectedPostcondition.SCROLL_CHANGED),
    SCROLL_LEFT("scroll_left", 23, ActionEffectClass.SCROLL, ActionRiskClass.LOW, ExpectedPostcondition.SCROLL_CHANGED),
    SCROLL_RIGHT("scroll_right", 23, ActionEffectClass.SCROLL, ActionRiskClass.LOW, ExpectedPostcondition.SCROLL_CHANGED),
    PAGE_UP("page_up", 29, ActionEffectClass.SCROLL, ActionRiskClass.LOW, ExpectedPostcondition.SCROLL_CHANGED),
    PAGE_DOWN("page_down", 29, ActionEffectClass.SCROLL, ActionRiskClass.LOW, ExpectedPostcondition.SCROLL_CHANGED),
    PAGE_LEFT("page_left", 29, ActionEffectClass.SCROLL, ActionRiskClass.LOW, ExpectedPostcondition.SCROLL_CHANGED),
    PAGE_RIGHT("page_right", 29, ActionEffectClass.SCROLL, ActionRiskClass.LOW, ExpectedPostcondition.SCROLL_CHANGED),
    SCROLL_TO_POSITION("scroll_to_position", 23, ActionEffectClass.SCROLL, ActionRiskClass.LOW, ExpectedPostcondition.SCROLL_CHANGED),
    SCROLL_IN_DIRECTION("scroll_in_direction", 34, ActionEffectClass.SCROLL, ActionRiskClass.LOW, ExpectedPostcondition.SCROLL_CHANGED, requiredArguments = setOf("direction")),
    SET_PROGRESS("set_progress", 24, ActionEffectClass.RANGE, ActionRiskClass.LOW, ExpectedPostcondition.RANGE_CHANGED, requiredArguments = setOf("value")),
    MOVE_WINDOW("move_window", 26, ActionEffectClass.WINDOW, ActionRiskClass.CONTEXTUAL, ExpectedPostcondition.WINDOW_CHANGED, scheduledAllowed = false, requiredArguments = setOf("x", "y")),
    SHOW_TEXT_SUGGESTIONS("show_text_suggestions", 33, ActionEffectClass.TEXT, ActionRiskClass.LOW, ExpectedPostcondition.VISIBILITY_CHANGED),
    DRAG_START("drag_start", 32, ActionEffectClass.DRAG, ActionRiskClass.CONTEXTUAL, ExpectedPostcondition.DRAG_RESULT, scheduledAllowed = false),
    DRAG_DROP("drag_drop", 32, ActionEffectClass.DRAG, ActionRiskClass.CONTEXTUAL, ExpectedPostcondition.DRAG_RESULT, scheduledAllowed = false),
    DRAG_CANCEL("drag_cancel", 32, ActionEffectClass.DRAG, ActionRiskClass.LOW, ExpectedPostcondition.DRAG_RESULT, scheduledAllowed = false)
}

data class SnapshotNodeAction(
    val actionId: Int,
    val token: String,
    val label: String?
)

data class SnapshotCustomAction(
    val actionId: Int,
    val label: String
)

object AndroidActionCatalog {
    private val standardById: Map<Int, StandardNodeAction> by lazy {
        buildMap {
            fun add(action: AccessibilityNodeInfo.AccessibilityAction, standard: StandardNodeAction) {
                put(action.id, standard)
            }
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS, StandardNodeAction.ACCESSIBILITY_FOCUS)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS, StandardNodeAction.CLEAR_ACCESSIBILITY_FOCUS)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS, StandardNodeAction.FOCUS)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_FOCUS, StandardNodeAction.CLEAR_FOCUS)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SELECT, StandardNodeAction.SELECT)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_SELECTION, StandardNodeAction.CLEAR_SELECTION)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION, StandardNodeAction.SET_SELECTION)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT, StandardNodeAction.SET_TEXT)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_COPY, StandardNodeAction.COPY)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_CUT, StandardNodeAction.CUT)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE, StandardNodeAction.PASTE)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, StandardNodeAction.NEXT_AT_MOVEMENT_GRANULARITY)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, StandardNodeAction.PREVIOUS_AT_MOVEMENT_GRANULARITY)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_HTML_ELEMENT, StandardNodeAction.NEXT_HTML_ELEMENT)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_PREVIOUS_HTML_ELEMENT, StandardNodeAction.PREVIOUS_HTML_ELEMENT)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK, StandardNodeAction.CLICK)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK, StandardNodeAction.LONG_CLICK)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_CONTEXT_CLICK, StandardNodeAction.CONTEXT_CLICK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(AccessibilityNodeInfo.AccessibilityAction.ACTION_PRESS_AND_HOLD, StandardNodeAction.PRESS_AND_HOLD)
                add(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER, StandardNodeAction.IME_ENTER)
            }
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND, StandardNodeAction.EXPAND)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE, StandardNodeAction.COLLAPSE)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN, StandardNodeAction.SHOW_ON_SCREEN)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_TOOLTIP, StandardNodeAction.SHOW_TOOLTIP)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_HIDE_TOOLTIP, StandardNodeAction.HIDE_TOOLTIP)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS, StandardNodeAction.DISMISS)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD, StandardNodeAction.SCROLL_FORWARD)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD, StandardNodeAction.SCROLL_BACKWARD)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP, StandardNodeAction.SCROLL_UP)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN, StandardNodeAction.SCROLL_DOWN)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT, StandardNodeAction.SCROLL_LEFT)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT, StandardNodeAction.SCROLL_RIGHT)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP, StandardNodeAction.PAGE_UP)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN, StandardNodeAction.PAGE_DOWN)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT, StandardNodeAction.PAGE_LEFT)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT, StandardNodeAction.PAGE_RIGHT)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION, StandardNodeAction.SCROLL_TO_POSITION)
            if (Build.VERSION.SDK_INT >= 34) {
                add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_IN_DIRECTION, StandardNodeAction.SCROLL_IN_DIRECTION)
            }
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS, StandardNodeAction.SET_PROGRESS)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_MOVE_WINDOW, StandardNodeAction.MOVE_WINDOW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_TEXT_SUGGESTIONS, StandardNodeAction.SHOW_TEXT_SUGGESTIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
                add(AccessibilityNodeInfo.AccessibilityAction.ACTION_DRAG_START, StandardNodeAction.DRAG_START)
                add(AccessibilityNodeInfo.AccessibilityAction.ACTION_DRAG_DROP, StandardNodeAction.DRAG_DROP)
                add(AccessibilityNodeInfo.AccessibilityAction.ACTION_DRAG_CANCEL, StandardNodeAction.DRAG_CANCEL)
            }
        }
    }

    fun standardForId(actionId: Int): StandardNodeAction? = standardById[actionId]

    fun actionIdForToken(token: String): Int? {
        val canonical = token.trim().lowercase()
        return standardById.entries.firstOrNull { it.value.token == canonical }?.key
    }

    fun capture(actions: List<AccessibilityNodeInfo.AccessibilityAction>): Pair<List<SnapshotNodeAction>, List<SnapshotCustomAction>> {
        val standard = mutableListOf<SnapshotNodeAction>()
        val custom = mutableListOf<SnapshotCustomAction>()
        actions.forEach { action ->
            val known = standardForId(action.id)
            val label = action.label?.toString()?.trim()?.takeIf(String::isNotEmpty)
            if (known != null && Build.VERSION.SDK_INT >= known.minimumApi) {
                standard += SnapshotNodeAction(action.id, known.token, label)
            } else if (label != null) {
                custom += SnapshotCustomAction(action.id, label)
            }
        }
        return standard.distinctBy { it.actionId } to custom.distinctBy { it.actionId }
    }
}

@SuppressLint("InlinedApi")
enum class GlobalSystemAction(
    val token: String,
    val actionId: Int,
    val plannerAllowed: Boolean,
    val scheduledAllowed: Boolean = true
) {
    BACK("back", AccessibilityService.GLOBAL_ACTION_BACK, true),
    HOME("home", AccessibilityService.GLOBAL_ACTION_HOME, true),
    RECENTS("recents", AccessibilityService.GLOBAL_ACTION_RECENTS, true),
    NOTIFICATIONS("notifications", AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, true),
    QUICK_SETTINGS("quick_settings", AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, true),
    DISMISS_NOTIFICATION_SHADE("dismiss_notification_shade", AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE, true),
    DPAD_UP("dpad_up", AccessibilityService.GLOBAL_ACTION_DPAD_UP, true),
    DPAD_DOWN("dpad_down", AccessibilityService.GLOBAL_ACTION_DPAD_DOWN, true),
    DPAD_LEFT("dpad_left", AccessibilityService.GLOBAL_ACTION_DPAD_LEFT, true),
    DPAD_RIGHT("dpad_right", AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT, true),
    DPAD_CENTER("dpad_center", AccessibilityService.GLOBAL_ACTION_DPAD_CENTER, true),
    POWER_DIALOG("power_dialog", AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, true, scheduledAllowed = false),
    LOCK_SCREEN("lock_screen", AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, true, scheduledAllowed = false),
    TOGGLE_SPLIT_SCREEN("toggle_split_screen", AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN, true, scheduledAllowed = false),
    ACCESSIBILITY_BUTTON("accessibility_button", AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_BUTTON, false, scheduledAllowed = false),
    ACCESSIBILITY_BUTTON_CHOOSER("accessibility_button_chooser", AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_BUTTON_CHOOSER, false, scheduledAllowed = false),
    ACCESSIBILITY_SHORTCUT("accessibility_shortcut", AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_SHORTCUT, false, scheduledAllowed = false),
    ACCESSIBILITY_ALL_APPS("accessibility_all_apps", AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS, false, scheduledAllowed = false),
    TAKE_SCREENSHOT("take_screenshot", AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, false, scheduledAllowed = false),
    KEYCODE_HEADSETOOK("headset_hook", AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK, false, scheduledAllowed = false);

    companion object {
        fun fromId(id: Int): GlobalSystemAction? = entries.firstOrNull { it.actionId == id }
        fun fromToken(token: String): GlobalSystemAction? = entries.firstOrNull { it.token == token.trim().lowercase() }
    }
}

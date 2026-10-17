package com.mewmix.nabu.uiagent

internal enum class PostconditionStatus {
    EXECUTED,
    STATE_CHANGED,
    GOAL_VERIFIED,
    VERIFIED,
    FAILED,
    UNVERIFIABLE,
    NOT_APPLICABLE;

    val isTargetVerified: Boolean
        get() = this == VERIFIED || this == GOAL_VERIFIED

    val isMechanicalProgress: Boolean
        get() = this == VERIFIED || this == GOAL_VERIFIED || this == STATE_CHANGED || this == EXECUTED

    fun toVerificationStatus(): UiVerificationStatus = when (this) {
        VERIFIED, GOAL_VERIFIED -> UiVerificationStatus.GOAL_VERIFIED
        STATE_CHANGED -> UiVerificationStatus.STATE_CHANGED
        EXECUTED -> UiVerificationStatus.EXECUTED
        FAILED -> UiVerificationStatus.FAILED
        UNVERIFIABLE, NOT_APPLICABLE -> UiVerificationStatus.UNVERIFIABLE
    }
}

internal data class PostconditionResult(
    val status: PostconditionStatus,
    val detail: String,
    val verificationStatus: UiVerificationStatus = status.toVerificationStatus(),
    val evidenceCategory: String = defaultEvidenceCategory(status)
) {
    companion object {
        private fun defaultEvidenceCategory(status: PostconditionStatus): String = when (status) {
            PostconditionStatus.VERIFIED, PostconditionStatus.GOAL_VERIFIED -> "target_property_verified"
            PostconditionStatus.STATE_CHANGED -> "state_transition"
            PostconditionStatus.EXECUTED -> "transport_accepted"
            PostconditionStatus.FAILED -> "postcondition_failed"
            PostconditionStatus.UNVERIFIABLE, PostconditionStatus.NOT_APPLICABLE -> "insufficient_evidence"
        }
    }
}

internal object UiActionPostconditionVerifier {
    fun verify(
        action: UiActionStep,
        before: UiScreenState,
        after: UiScreenState
    ): PostconditionResult = when (action) {
        is UiActionStep.TypeText -> verifyTypedText(action, before, after)
        is UiActionStep.Focus -> verifyBooleanTarget(action.target, before, after, "focused", true) { it.focused }
        is UiActionStep.NodeAction -> verifyNodeAction(action, before, after)
        is UiActionStep.CustomAction -> verifyMutation(action.target, before, after, "custom action")
        is UiActionStep.Gesture -> verifyMutation(action.target, before, after, action.gesture)
        is UiActionStep.Tap -> verifyMutation(action.target, before, after, "activation")
        is UiActionStep.LongPress -> verifyMutation(action.target, before, after, "long press")
        is UiActionStep.Scroll -> verifyScreenMutation(before, after, "Visible scroll state")
        UiActionStep.PressBack,
        UiActionStep.PressHome,
        UiActionStep.PressRecents -> verifyScreenMutation(before, after, "Global navigation")
        UiActionStep.OpenNotifications -> verifySystemSurface(before, after, SystemSurfaceKind.NOTIFICATION_SHADE)
        UiActionStep.OpenQuickSettings -> verifySystemSurface(before, after, SystemSurfaceKind.QUICK_SETTINGS)
        is UiActionStep.GlobalAction -> when (action.action) {
            "notifications" -> verifySystemSurface(before, after, SystemSurfaceKind.NOTIFICATION_SHADE)
            "quick_settings" -> verifySystemSurface(before, after, SystemSurfaceKind.QUICK_SETTINGS)
            "dismiss_notification_shade" -> verifyScreenMutation(before, after, "Notification shade")
            else -> verifyScreenMutation(before, after, "Global action ${action.action}")
        }
        is UiActionStep.OpenApp -> if (after.packageName.equals(action.packageName, ignoreCase = true)) {
            PostconditionResult(
                status = PostconditionStatus.VERIFIED,
                detail = "Opened ${after.packageName} and verified it in the foreground.",
                verificationStatus = UiVerificationStatus.GOAL_VERIFIED,
                evidenceCategory = "package_foreground"
            )
        } else {
            PostconditionResult(
                status = PostconditionStatus.FAILED,
                detail = "Expected ${action.packageName} to foreground, but observed ${after.packageName}.",
                verificationStatus = UiVerificationStatus.FAILED,
                evidenceCategory = "package_mismatch"
            )
        }
        is UiActionStep.OpenSettingsPage -> verifyOpenSettingsPage(action, before, after)
        is UiActionStep.OpenUrl -> verifyScreenMutation(before, after, "URL target screen")
        is UiActionStep.ShareText,
        is UiActionStep.OpenCamera,
        is UiActionStep.ShareCapturedMedia -> verifyScreenMutation(before, after, "External intent handoff")
        else -> PostconditionResult(
            status = PostconditionStatus.NOT_APPLICABLE,
            detail = "No built-in postcondition is defined for this action.",
            verificationStatus = UiVerificationStatus.UNVERIFIABLE,
            evidenceCategory = "not_applicable"
        )
    }

    private fun verifyNodeAction(
        action: UiActionStep.NodeAction,
        before: UiScreenState,
        after: UiScreenState
    ): PostconditionResult = when (action.action) {
        "focus" -> verifyBooleanTarget(action.target, before, after, "focused", true) { it.focused }
        "clear_focus" -> verifyBooleanTarget(action.target, before, after, "focused", false) { it.focused }
        "accessibility_focus" -> verifyBooleanTarget(
            action.target,
            before,
            after,
            "accessibilityFocused",
            true
        ) { it.accessibilityFocused }
        "clear_accessibility_focus" -> verifyBooleanTarget(
            action.target,
            before,
            after,
            "accessibilityFocused",
            false
        ) { it.accessibilityFocused }
        "select" -> verifyBooleanTarget(action.target, before, after, "selected", true) { it.selected }
        "clear_selection" -> verifyBooleanTarget(action.target, before, after, "selected", false) { it.selected }
        "set_text" -> {
            val expectedText = action.arguments["text"]
            if (!expectedText.isNullOrBlank()) {
                verifyTypedText(UiActionStep.TypeText(expectedText, action.target), before, after)
            } else {
                verifyTargetTextChanged(action.target, before, after)
            }
        }
        "cut", "paste" -> verifyTargetTextChanged(action.target, before, after)
        "set_progress" -> verifyProgress(action, before, after)
        "scroll_forward", "scroll_backward", "scroll_up", "scroll_down", "scroll_left", "scroll_right",
        "page_up", "page_down", "page_left", "page_right", "scroll_to_position", "scroll_in_direction" ->
            verifyScreenMutation(before, after, "Visible scroll state")
        "click", "long_click", "context_click", "press_and_hold", "ime_enter", "expand", "collapse",
        "show_on_screen", "show_tooltip", "hide_tooltip", "dismiss", "drag_start", "drag_drop",
        "drag_cancel", "move_window", "show_text_suggestions",
        "next_at_movement_granularity", "previous_at_movement_granularity", "next_html_element",
        "previous_html_element" -> verifyMutation(action.target, before, after, action.action)
        "set_selection" -> verifyTextSelection(action, before, after)
        "copy" -> PostconditionResult(
            status = PostconditionStatus.EXECUTED,
            detail = "Copy was accepted by Android, but clipboard contents remain opaque and were not verified.",
            verificationStatus = UiVerificationStatus.EXECUTED,
            evidenceCategory = "opaque_clipboard"
        )
        else -> PostconditionResult(
            status = PostconditionStatus.FAILED,
            detail = "No verifier exists for '${action.action}'.",
            verificationStatus = UiVerificationStatus.FAILED,
            evidenceCategory = "unsupported_action"
        )
    }

    private fun verifyTextSelection(
        action: UiActionStep.NodeAction,
        before: UiScreenState,
        after: UiScreenState
    ): PostconditionResult {
        val start = action.arguments["selection_start"]?.toIntOrNull()
        val end = action.arguments["selection_end"]?.toIntOrNull()
        val source = action.target.elementId?.let(before::element)
        val destination = source?.let { findSameControl(it, after) }
        return if (start != null && end != null &&
            destination?.selectionStart == start && destination.selectionEnd == end
        ) {
            PostconditionResult(
                status = PostconditionStatus.VERIFIED,
                detail = "The requested text selection is active.",
                verificationStatus = UiVerificationStatus.GOAL_VERIFIED,
                evidenceCategory = "selection_state"
            )
        } else {
            PostconditionResult(
                status = PostconditionStatus.FAILED,
                detail = "The requested text selection was not observed.",
                verificationStatus = UiVerificationStatus.FAILED,
                evidenceCategory = "selection_mismatch"
            )
        }
    }

    private fun verifyProgress(
        action: UiActionStep.NodeAction,
        before: UiScreenState,
        after: UiScreenState
    ): PostconditionResult {
        val expected = action.arguments["value"]?.toFloatOrNull()
            ?: return PostconditionResult(
                status = PostconditionStatus.FAILED,
                detail = "Requested progress value is missing.",
                verificationStatus = UiVerificationStatus.FAILED,
                evidenceCategory = "missing_argument"
            )
        val source = action.target.elementId?.let(before::element)
            ?: return PostconditionResult(
                status = PostconditionStatus.FAILED,
                detail = "Progress target is missing.",
                verificationStatus = UiVerificationStatus.FAILED,
                evidenceCategory = "target_missing"
            )
        val observed = findSameControl(source, after)?.range?.current
            ?: return PostconditionResult(
                status = PostconditionStatus.FAILED,
                detail = "Progress target RangeInfo is unavailable after execution.",
                verificationStatus = UiVerificationStatus.FAILED,
                evidenceCategory = "range_unavailable"
            )
        val range = source.range
        val tolerance = ((range?.max ?: expected) - (range?.min ?: expected))
            .let { kotlin.math.abs(it) * 0.01f }
            .coerceAtLeast(0.001f)
        return if (kotlin.math.abs(observed - expected) <= tolerance) {
            PostconditionResult(
                status = PostconditionStatus.VERIFIED,
                detail = "The range control reached the requested value.",
                verificationStatus = UiVerificationStatus.GOAL_VERIFIED,
                evidenceCategory = "range_value"
            )
        } else {
            PostconditionResult(
                status = PostconditionStatus.FAILED,
                detail = "The range control did not reach the requested value.",
                verificationStatus = UiVerificationStatus.FAILED,
                evidenceCategory = "range_mismatch"
            )
        }
    }

    private fun verifyTargetTextChanged(
        target: UiTarget,
        before: UiScreenState,
        after: UiScreenState
    ): PostconditionResult {
        val source = target.elementId?.let(before::element)
            ?: return PostconditionResult(
                status = PostconditionStatus.FAILED,
                detail = "Text target is missing.",
                verificationStatus = UiVerificationStatus.FAILED,
                evidenceCategory = "target_missing"
            )
        val destination = findSameControl(source, after)
            ?: return PostconditionResult(
                status = PostconditionStatus.FAILED,
                detail = "Text target disappeared after execution.",
                verificationStatus = UiVerificationStatus.FAILED,
                evidenceCategory = "target_disappeared"
            )
        return if (destination.text != source.text) {
            PostconditionResult(
                status = PostconditionStatus.VERIFIED,
                detail = "The target text changed.",
                verificationStatus = UiVerificationStatus.GOAL_VERIFIED,
                evidenceCategory = "text_mutation"
            )
        } else {
            PostconditionResult(
                status = PostconditionStatus.FAILED,
                detail = "The target text did not change.",
                verificationStatus = UiVerificationStatus.FAILED,
                evidenceCategory = "text_unchanged"
            )
        }
    }

    private fun verifyBooleanTarget(
        target: UiTarget,
        before: UiScreenState,
        after: UiScreenState,
        property: String,
        expected: Boolean,
        read: (UiElement) -> Boolean
    ): PostconditionResult {
        val source = target.elementId?.let(before::element)
            ?: return PostconditionResult(
                status = PostconditionStatus.FAILED,
                detail = "Target is missing before execution.",
                verificationStatus = UiVerificationStatus.FAILED,
                evidenceCategory = "target_missing"
            )
        val destination = findSameControl(source, after)
            ?: return PostconditionResult(
                status = PostconditionStatus.FAILED,
                detail = "Target disappeared before $property could be verified.",
                verificationStatus = UiVerificationStatus.FAILED,
                evidenceCategory = "target_disappeared"
            )
        return if (read(destination) == expected) {
            PostconditionResult(
                status = PostconditionStatus.VERIFIED,
                detail = "Target $property is $expected.",
                verificationStatus = UiVerificationStatus.GOAL_VERIFIED,
                evidenceCategory = "target_property_$property"
            )
        } else {
            PostconditionResult(
                status = PostconditionStatus.FAILED,
                detail = "Target $property did not become $expected.",
                verificationStatus = UiVerificationStatus.FAILED,
                evidenceCategory = "target_property_mismatch"
            )
        }
    }

    private fun verifyMutation(
        target: UiTarget,
        before: UiScreenState,
        after: UiScreenState,
        label: String
    ): PostconditionResult {
        if (before.screenId != after.screenId || !before.packageName.equals(after.packageName, ignoreCase = true)) {
            return PostconditionResult(
                status = PostconditionStatus.STATE_CHANGED,
                detail = "$label produced a UI state transition (${before.packageName}/${before.screenId} -> ${after.packageName}/${after.screenId}), but goal completion requires explicit outcome verification.",
                verificationStatus = UiVerificationStatus.STATE_CHANGED,
                evidenceCategory = "screen_transition"
            )
        }
        val source = target.elementId?.let(before::element)
        val destination = source?.let { findSameControl(it, after) }
        val stateChanged = source != null && destination != null &&
            (source.checked != destination.checked || source.selected != destination.selected ||
                source.focused != destination.focused || source.text != destination.text || source.range != destination.range)
        return if (stateChanged) {
            PostconditionResult(
                status = PostconditionStatus.STATE_CHANGED,
                detail = "$label changed the target control state; awaiting goal verification.",
                verificationStatus = UiVerificationStatus.STATE_CHANGED,
                evidenceCategory = "target_state_mutation"
            )
        } else {
            PostconditionResult(
                status = PostconditionStatus.FAILED,
                detail = "$label produced no observable target or screen change.",
                verificationStatus = UiVerificationStatus.FAILED,
                evidenceCategory = "no_mutation"
            )
        }
    }

    private fun verifyScreenMutation(
        before: UiScreenState,
        after: UiScreenState,
        label: String
    ): PostconditionResult = if (before.screenId != after.screenId || !before.packageName.equals(after.packageName, ignoreCase = true)) {
        PostconditionResult(
            status = PostconditionStatus.STATE_CHANGED,
            detail = "$label changed (${before.packageName}/${before.screenId} -> ${after.packageName}/${after.screenId}).",
            verificationStatus = UiVerificationStatus.STATE_CHANGED,
            evidenceCategory = "screen_transition"
        )
    } else {
        PostconditionResult(
            status = PostconditionStatus.FAILED,
            detail = "$label did not observably change.",
            verificationStatus = UiVerificationStatus.FAILED,
            evidenceCategory = "no_mutation"
        )
    }

    private fun verifySystemSurface(
        before: UiScreenState,
        after: UiScreenState,
        expected: SystemSurfaceKind
    ): PostconditionResult {
        val evaluation = UiSystemSurfaceDetector.evaluate(after, expected)
        val transitionOccurred = before.screenId != after.screenId ||
            !before.packageName.equals(after.packageName, ignoreCase = true)
        return if (evaluation.matched) {
            PostconditionResult(
                status = PostconditionStatus.VERIFIED,
                detail = evaluation.detail,
                verificationStatus = UiVerificationStatus.GOAL_VERIFIED,
                evidenceCategory = "system_surface_${expected.name.lowercase()}"
            )
        } else {
            PostconditionResult(
                status = PostconditionStatus.FAILED,
                detail = evaluation.detail,
                verificationStatus = if (transitionOccurred && evaluation.contradictedByOtherSurface == null) {
                    UiVerificationStatus.STATE_CHANGED
                } else {
                    UiVerificationStatus.FAILED
                },
                evidenceCategory = if (evaluation.contradictedByOtherSurface != null) {
                    "wrong_system_surface"
                } else {
                    "system_surface_unverified"
                }
            )
        }
    }

    private fun verifyOpenSettingsPage(
        action: UiActionStep.OpenSettingsPage,
        before: UiScreenState,
        after: UiScreenState
    ): PostconditionResult {
        val transitioned = before.screenId != after.screenId ||
            !before.packageName.equals(after.packageName, ignoreCase = true)
        if (!transitioned) {
            return PostconditionResult(
                status = PostconditionStatus.FAILED,
                detail = "Settings screen (${action.page.name.lowercase()}) did not observably change.",
                verificationStatus = UiVerificationStatus.FAILED,
                evidenceCategory = "no_mutation"
            )
        }
        val settingsEvaluation = UiGoalVerifier.verifySettingsSurface(after, action.page, action.packageName)
        return when (settingsEvaluation.status) {
            UiVerificationStatus.GOAL_VERIFIED -> PostconditionResult(
                status = PostconditionStatus.VERIFIED,
                detail = settingsEvaluation.summary,
                verificationStatus = UiVerificationStatus.GOAL_VERIFIED,
                evidenceCategory = "settings_surface"
            )
            UiVerificationStatus.FAILED -> PostconditionResult(
                status = PostconditionStatus.FAILED,
                detail = settingsEvaluation.summary,
                verificationStatus = UiVerificationStatus.FAILED,
                evidenceCategory = "wrong_settings_surface"
            )
            else -> PostconditionResult(
                status = PostconditionStatus.STATE_CHANGED,
                detail = settingsEvaluation.summary,
                verificationStatus = UiVerificationStatus.STATE_CHANGED,
                evidenceCategory = "screen_transition"
            )
        }
    }

    private fun verifyTypedText(
        action: UiActionStep.TypeText,
        before: UiScreenState,
        after: UiScreenState
    ): PostconditionResult {
        val source = action.target?.elementId?.let(before::element)
            ?: return PostconditionResult(
                status = PostconditionStatus.FAILED,
                detail = "The editable source element could not be identified before typing.",
                verificationStatus = UiVerificationStatus.FAILED,
                evidenceCategory = "target_missing"
            )
        if (source.password) {
            return PostconditionResult(
                status = PostconditionStatus.FAILED,
                detail = "Password text cannot be verified.",
                verificationStatus = UiVerificationStatus.UNVERIFIABLE,
                evidenceCategory = "password_redacted"
            )
        }
        val destination = findSameControl(source, after)
            ?: return PostconditionResult(
                status = PostconditionStatus.FAILED,
                detail = "The editable control could not be re-identified after typing.",
                verificationStatus = UiVerificationStatus.FAILED,
                evidenceCategory = "target_disappeared"
            )
        val expected = normalize(action.text)
        val observed = normalize(destination.text.orEmpty())
        return if (observed == expected) {
            PostconditionResult(
                status = PostconditionStatus.VERIFIED,
                detail = "The editable control contains the requested text.",
                verificationStatus = UiVerificationStatus.GOAL_VERIFIED,
                evidenceCategory = "typed_text"
            )
        } else {
            PostconditionResult(
                status = PostconditionStatus.FAILED,
                detail = "The editable control text did not match the requested text.",
                verificationStatus = UiVerificationStatus.FAILED,
                evidenceCategory = "typed_text_mismatch"
            )
        }
    }

    internal fun findSameControl(source: UiElement, screen: UiScreenState): UiElement? {
        val candidates = screen.elements.filter { it.visible && !it.password }
        source.resourceId?.takeIf(String::isNotBlank)?.let { resourceId ->
            candidates.singleOrNull { it.resourceId == resourceId }?.let { return it }
        }
        candidates.singleOrNull {
            it.treePath == source.treePath && it.className == source.className
        }?.let { return it }
        return candidates.singleOrNull { candidate ->
            candidate.className == source.className && candidate.bounds == source.bounds
        }
    }

    internal fun normalize(value: String): String = value
        .replace('\u00a0', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
}


package com.mewmix.nabu.uiagent

sealed interface UiPlanDecision {
    data object Allow : UiPlanDecision
    data class RequireConfirmation(val reason: String) : UiPlanDecision
    data class Block(val reason: String) : UiPlanDecision
    data class Invalid(val reason: String) : UiPlanDecision
}

object UiActionValidator {
    private val blockedTerms = listOf(
        "payment", "purchase", "buy now", "checkout", "bank transfer", "wire transfer",
        "password", "passcode", "two factor", "2fa", "authentication approval",
        "delete account", "factory reset", "install unknown", "unknown apk"
    )
    private val confirmationTerms = listOf(
        "send", "post", "publish", "call", "delete", "remove file", "security setting",
        "grant permission", "allow permission", "permission"
    )

    fun validate(plan: UiActionPlan, screen: UiScreenState): UiPlanDecision {
        if (plan.screenId != screen.screenId) {
            return UiPlanDecision.Invalid("Plan screen_id does not match the current observation.")
        }
        val context = buildSafetyContext(plan, screen).lowercase()
        blockedTerms.firstOrNull(context::contains)?.let {
            return UiPlanDecision.Block("Blocked sensitive UI action involving '$it'.")
        }

        for (step in plan.steps) {
            validateStep(step, screen)?.let { return it }
        }

        confirmationTerms.firstOrNull(context::contains)?.let {
            return UiPlanDecision.RequireConfirmation("Confirmation required for action involving '$it'.")
        }
        return UiPlanDecision.Allow
    }

    private fun validateStep(step: UiActionStep, screen: UiScreenState): UiPlanDecision? = when (step) {
        is UiActionStep.Tap -> validateTarget(step.target, screen, requireEnabled = true)?.let { it }
            ?: validateCapability(step.target, screen, "clickable") { it.clickable || it.checkable }
        is UiActionStep.LongPress -> validateTarget(step.target, screen, requireEnabled = true)?.let { it }
            ?: validateCapability(step.target, screen, "long-clickable") { it.longClickable }
        is UiActionStep.TypeText -> {
            if (step.text.isBlank()) {
                UiPlanDecision.Invalid("type_text requires non-blank text.")
            } else if (step.target == null) {
                UiPlanDecision.Invalid("type_text requires an editable target element.")
            } else {
                val targetDecision = step.target?.let { validateTarget(it, screen, requireEnabled = true) }
                if (targetDecision != null) {
                    targetDecision
                } else {
                    val targetElement = step.target?.elementId?.let(screen::element)
                    if (targetElement?.password == true) {
                        UiPlanDecision.Block("Typing into password fields is blocked.")
                    } else if (targetElement != null && !targetElement.editable) {
                        UiPlanDecision.Invalid("type_text target is not editable.")
                    } else {
                        null
                    }
                }
            }
        }
        is UiActionStep.Scroll -> step.target?.let { target ->
            validateTarget(target, screen, requireEnabled = true)
                ?: validateCapability(target, screen, "scrollable") { it.scrollable }
        }
        is UiActionStep.Wait -> if (step.milliseconds !in 0..5_000) {
            UiPlanDecision.Invalid("wait must be between 0 and 5000 ms.")
        } else null
        is UiActionStep.Assert -> validateAssertion(step.condition, screen)
        is UiActionStep.AskUser -> if (step.reason.isBlank()) UiPlanDecision.Invalid("ask_user requires a reason.") else null
        is UiActionStep.Done -> if (step.summary.isBlank()) UiPlanDecision.Invalid("done requires a summary.") else null
        UiActionStep.PressBack, UiActionStep.PressHome -> null
    }

    private fun validateTarget(
        target: UiTarget,
        screen: UiScreenState,
        requireEnabled: Boolean
    ): UiPlanDecision? {
        val elementId = target.elementId ?: return if (target.fallbackBounds?.isValid == true) null else {
            UiPlanDecision.Invalid("Target has no valid element or bounds.")
        }
        val element = screen.element(elementId) ?: return if (isOnScreen(target.fallbackBounds, screen)) {
            null
        } else {
            UiPlanDecision.Invalid("Target element '$elementId' is not present and has no on-screen fallback bounds.")
        }
        if (!element.visible) return UiPlanDecision.Invalid("Target element '$elementId' is not visible.")
        if (requireEnabled && !element.enabled) return UiPlanDecision.Invalid("Target element '$elementId' is disabled.")
        if (element.password) return UiPlanDecision.Block("Password fields cannot be targeted.")
        return null
    }

    private fun validateCapability(
        target: UiTarget,
        screen: UiScreenState,
        capability: String,
        predicate: (UiElement) -> Boolean
    ): UiPlanDecision? {
        val element = target.elementId?.let(screen::element) ?: return null
        return if (predicate(element)) null else {
            UiPlanDecision.Invalid("Target element '${element.id}' is not $capability.")
        }
    }

    private fun validateAssertion(assertion: UiAssertion, screen: UiScreenState): UiPlanDecision? {
        // A stale optional assertion should fail post-action verification and trigger
        // replanning, not block an otherwise valid primary action.
        return null
    }

    private fun isOnScreen(bounds: UiBounds?, screen: UiScreenState): Boolean {
        if (bounds?.isValid != true) return false
        val knownBounds = screen.elements.mapNotNull { it.bounds }
        if (knownBounds.isEmpty()) return false
        val left = knownBounds.minOf { it.left }
        val top = knownBounds.minOf { it.top }
        val right = knownBounds.maxOf { it.right }
        val bottom = knownBounds.maxOf { it.bottom }
        return bounds.left >= left && bounds.top >= top && bounds.right <= right && bounds.bottom <= bottom
    }

    private fun buildSafetyContext(plan: UiActionPlan, screen: UiScreenState): String {
        val targetText = plan.steps.mapNotNull { step ->
            val id = when (step) {
                is UiActionStep.Tap -> step.target.elementId
                is UiActionStep.LongPress -> step.target.elementId
                is UiActionStep.TypeText -> step.target?.elementId
                is UiActionStep.Scroll -> step.target?.elementId
                is UiActionStep.Assert -> step.condition.elementId
                else -> null
            }
            id?.let(screen::element)?.let { element ->
                listOfNotNull(element.text, element.contentDescription, element.resourceId).joinToString(" ")
            }
        }
        return (listOf(plan.goal) + targetText).joinToString(" ")
    }
}

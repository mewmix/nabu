package com.mewmix.nabu.uiagent

import com.mewmix.nabu.actions.DeviceAction
import java.util.UUID

/**
 * Typed execution incidents for structured recovery (Phase 3).
 */
enum class UiExecutionIncidentType {
    STALE_OBSERVATION,
    TARGET_NOT_FOUND,
    AMBIGUOUS_TARGET,
    UNEXPECTED_NAVIGATION,
    POSTCONDITION_FAILED,
    GOAL_NOT_VERIFIED,
    ACCESSIBILITY_UNAVAILABLE,
    EXECUTION_TIMEOUT
}

data class UiExecutionIncident(
    val id: String = UUID.randomUUID().toString(),
    val type: UiExecutionIncidentType,
    val stepSequence: Int,
    val actionLabel: String,
    val detail: String,
    val sourceScreenId: String? = null,
    val observedScreenId: String? = null,
    val sourcePackage: String? = null,
    val observedPackage: String? = null,
    val isNonIdempotentAction: Boolean = false,
    val attemptCount: Int = 1,
    val resolved: Boolean = false,
    val resolutionSummary: String? = null,
    val occurredAtMs: Long = System.currentTimeMillis()
)

data class UiRecoveryBudget(
    val maxRetriesByType: Map<UiExecutionIncidentType, Int> = DEFAULT_RETRIES_BY_TYPE,
    val maxTotalRecoveryAttempts: Int = 6,
    val sessionDeadlineMs: Long = 90_000L,
    val baseBackoffMs: Long = 120L,
    val maxBackoffMs: Long = 960L
) {
    fun maxRetriesFor(type: UiExecutionIncidentType): Int =
        maxRetriesByType[type] ?: 1

    fun backoffMsForAttempt(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 4)
        return (baseBackoffMs * (1L shl shift)).coerceAtMost(maxBackoffMs)
    }

    companion object {
        val DEFAULT_RETRIES_BY_TYPE = mapOf(
            UiExecutionIncidentType.STALE_OBSERVATION to 2,
            UiExecutionIncidentType.TARGET_NOT_FOUND to 2,
            UiExecutionIncidentType.AMBIGUOUS_TARGET to 1,
            UiExecutionIncidentType.UNEXPECTED_NAVIGATION to 2,
            UiExecutionIncidentType.POSTCONDITION_FAILED to 2,
            UiExecutionIncidentType.GOAL_NOT_VERIFIED to 2,
            UiExecutionIncidentType.ACCESSIBILITY_UNAVAILABLE to 3,
            UiExecutionIncidentType.EXECUTION_TIMEOUT to 1
        )
    }
}

internal sealed interface UiRecoveryDecision {
    val incident: UiExecutionIncident
    val summary: String

    /** Reacquire observation with bounded backoff (e.g. when accessibility root is transiently unavailable). */
    data class ReobserveWithBackoff(
        override val incident: UiExecutionIncident,
        val delayMs: Long,
        val allowActionRedispatch: Boolean,
        override val summary: String
    ) : UiRecoveryDecision

    /** Rebind the intended action to a re-grounded or alternative semantic target on the current screen. */
    data class RebindAction(
        override val incident: UiExecutionIncident,
        val replacementAction: UiActionStep,
        val delayMs: Long = 0L,
        override val summary: String
    ) : UiRecoveryDecision

    /** An unexpected navigation actually reached the intended goal destination. */
    data class DestinationAlreadyReached(
        override val incident: UiExecutionIncident,
        val verification: GoalVerificationResult,
        override val summary: String
    ) : UiRecoveryDecision

    /** Navigate back once from an unexpected screen transition and replan from the resulting state. */
    data class NavigateBackAndReplan(
        override val incident: UiExecutionIncident,
        val backAction: UiActionStep,
        override val summary: String
    ) : UiRecoveryDecision

    /** Stop current plan, collect evidence, and invoke the planner with structured incident context. */
    data class ReplanWithEvidence(
        override val incident: UiExecutionIncident,
        val plannerHint: String,
        override val summary: String
    ) : UiRecoveryDecision

    /**
     * Halt without repeating because a potentially non-idempotent action was already dispatched
     * and its confirmation/outcome was lost or unverified.
     */
    data class HaltNonIdempotentAction(
        override val incident: UiExecutionIncident,
        override val summary: String
    ) : UiRecoveryDecision

    /** Abort recovery due to exhausted retry budget, cancellation, deadline expiry, or lost session ownership. */
    data class Abort(
        override val incident: UiExecutionIncident,
        val terminationCode: String,
        override val summary: String
    ) : UiRecoveryDecision
}

/**
 * Stateful, per-session tracker and deterministic policy engine for structured incident recovery.
 */
internal class UiRecoveryTracker(
    val budget: UiRecoveryBudget = UiRecoveryBudget(),
    private val startedAtMs: Long = System.currentTimeMillis(),
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val countsByType = mutableMapOf<UiExecutionIncidentType, Int>()
    private val activeIncidentsById = linkedMapOf<String, UiExecutionIncident>()
    private val resolvedIncidentsList = mutableListOf<UiExecutionIncident>()
    private val dispatchedNonIdempotentSignatures = mutableSetOf<String>()

    var totalRecoveryAttempts: Int = 0
        private set

    val activeIncidents: List<UiExecutionIncident>
        get() = activeIncidentsById.values.toList()

    val resolvedIncidents: List<UiExecutionIncident>
        get() = resolvedIncidentsList.toList()

    fun markNonIdempotentDispatched(action: UiActionStep, screen: UiScreenState?, goal: String) {
        if (UiRecoveryPolicy.isPotentiallyNonIdempotent(action, screen, goal)) {
            dispatchedNonIdempotentSignatures += actionSignature(action, screen)
        }
    }

    fun wasNonIdempotentAlreadyDispatched(action: UiActionStep, screen: UiScreenState?): Boolean =
        actionSignature(action, screen) in dispatchedNonIdempotentSignatures

    fun resolveIncident(type: UiExecutionIncidentType, resolutionSummary: String) {
        val matchingIds = activeIncidentsById.values
            .filter { it.type == type && !it.resolved }
            .map { it.id }
        for (id in matchingIds) {
            val existing = activeIncidentsById.remove(id) ?: continue
            resolvedIncidentsList += existing.copy(
                resolved = true,
                resolutionSummary = resolutionSummary
            )
        }
    }

    fun resolveAllActive(resolutionSummary: String) {
        val ids = activeIncidentsById.keys.toList()
        for (id in ids) {
            val existing = activeIncidentsById.remove(id) ?: continue
            resolvedIncidentsList += existing.copy(
                resolved = true,
                resolutionSummary = resolutionSummary
            )
        }
    }

    fun recordAndDecide(
        type: UiExecutionIncidentType,
        stepSequence: Int,
        action: UiActionStep?,
        goal: String,
        detail: String,
        beforeScreen: UiScreenState? = null,
        currentScreen: UiScreenState? = null,
        actionAlreadyDispatched: Boolean = false,
        isCancelled: Boolean = false,
        isSessionOwnerValid: Boolean = true,
        goalAppCandidates: List<DeviceAction.AppCandidate> = emptyList()
    ): UiRecoveryDecision {
        val now = nowMs()
        val nonIdempotent = action != null &&
            (UiRecoveryPolicy.isPotentiallyNonIdempotent(action, beforeScreen ?: currentScreen, goal) ||
                wasNonIdempotentAlreadyDispatched(action, beforeScreen ?: currentScreen))

        val nextAttemptForType = (countsByType[type] ?: 0) + 1
        countsByType[type] = nextAttemptForType
        totalRecoveryAttempts += 1

        val incident = UiExecutionIncident(
            type = type,
            stepSequence = stepSequence,
            actionLabel = action?.let(UiRecoveryPolicy::actionName) ?: "observe",
            detail = detail,
            sourceScreenId = beforeScreen?.screenId,
            observedScreenId = currentScreen?.screenId,
            sourcePackage = beforeScreen?.packageName,
            observedPackage = currentScreen?.packageName,
            isNonIdempotentAction = nonIdempotent,
            attemptCount = nextAttemptForType,
            occurredAtMs = now
        )

        // Replace prior unresolved incident of the same type with the updated attempt
        activeIncidentsById.values
            .filter { it.type == type && !it.resolved }
            .map { it.id }
            .forEach(activeIncidentsById::remove)
        activeIncidentsById[incident.id] = incident

        // 1. Check ownership, cancellation, and deadline first
        if (!isSessionOwnerValid) {
            return UiRecoveryDecision.Abort(
                incident = incident,
                terminationCode = "session_owner_superseded",
                summary = "Aborted recovery for ${type.name}: session ownership was superseded."
            )
        }
        if (isCancelled) {
            return UiRecoveryDecision.Abort(
                incident = incident,
                terminationCode = "cancelled",
                summary = "Aborted recovery for ${type.name}: session was cancelled."
            )
        }
        if (now - startedAtMs >= budget.sessionDeadlineMs) {
            return UiRecoveryDecision.Abort(
                incident = incident,
                terminationCode = "deadline_exceeded",
                summary = "Aborted recovery for ${type.name}: session deadline (${budget.sessionDeadlineMs}ms) exceeded."
            )
        }

        // 2. Never re-execute a potentially non-idempotent action after it was already dispatched
        if (nonIdempotent && actionAlreadyDispatched) {
            if (type == UiExecutionIncidentType.ACCESSIBILITY_UNAVAILABLE &&
                nextAttemptForType <= budget.maxRetriesFor(type) &&
                totalRecoveryAttempts <= budget.maxTotalRecoveryAttempts
            ) {
                return UiRecoveryDecision.ReobserveWithBackoff(
                    incident = incident,
                    delayMs = budget.backoffMsForAttempt(nextAttemptForType),
                    allowActionRedispatch = false,
                    summary = "Accessibility unavailable after non-idempotent action '${incident.actionLabel}'; re-observing state without re-executing the action."
                )
            }
            return UiRecoveryDecision.HaltNonIdempotentAction(
                incident = incident,
                summary = "Halted recovery for '${incident.actionLabel}' (${type.name}): potentially non-idempotent action was already dispatched and cannot be safely repeated without confirmation."
            )
        }

        // 3. Enforce per-incident-type and total session retry limits
        val maxForType = budget.maxRetriesFor(type)
        if (nextAttemptForType > maxForType || totalRecoveryAttempts > budget.maxTotalRecoveryAttempts) {
            return UiRecoveryDecision.Abort(
                incident = incident,
                terminationCode = "retry_budget_exhausted",
                summary = "Recovery budget exhausted for ${type.name} (attempt $nextAttemptForType/$maxForType, total $totalRecoveryAttempts/${budget.maxTotalRecoveryAttempts}): $detail"
            )
        }

        return UiRecoveryPolicy.decideWithinBudget(
            incident = incident,
            budget = budget,
            action = action,
            goal = goal,
            beforeScreen = beforeScreen,
            currentScreen = currentScreen,
            actionAlreadyDispatched = actionAlreadyDispatched,
            goalAppCandidates = goalAppCandidates
        )
    }

    private fun actionSignature(action: UiActionStep, screen: UiScreenState?): String {
        val targetId = UiRecoveryPolicy.extractTarget(action)?.elementId.orEmpty()
        return "${action::class.simpleName}:${targetId}:${screen?.packageName.orEmpty()}"
    }
}

internal object UiRecoveryPolicy {
    private val NON_IDEMPOTENT_CONTROL_KEYWORDS = setOf(
        "send", "submit", "post", "publish", "delete", "remove", "erase",
        "pay", "purchase", "buy", "checkout", "confirm", "transfer", "call", "dial"
    )

    fun isPotentiallyNonIdempotent(
        action: UiActionStep,
        screen: UiScreenState?,
        goal: String
    ): Boolean {
        when (action) {
            is UiActionStep.ShareText,
            is UiActionStep.ShareCapturedMedia -> return true
            else -> Unit
        }
        val target = extractTarget(action) ?: return false
        val element = target.elementId?.let { id -> screen?.element(id) }
        val descriptor = listOfNotNull(
            target.textContains,
            element?.text,
            element?.contentDescription,
            element?.resourceId
        ).joinToString(" ").lowercase()

        if (NON_IDEMPOTENT_CONTROL_KEYWORDS.any { kw ->
                Regex("\\b${Regex.escape(kw)}\\b").containsMatchIn(descriptor) ||
                    element?.resourceId?.lowercase()?.endsWith(":id/$kw") == true
            }
        ) {
            return true
        }
        val goalLower = goal.lowercase()
        return (action is UiActionStep.Tap || action is UiActionStep.NodeAction) &&
            NON_IDEMPOTENT_CONTROL_KEYWORDS.any { kw ->
                Regex("\\b${Regex.escape(kw)}\\b").containsMatchIn(goalLower) &&
                    descriptor.contains(kw)
            }
    }

    fun classifyExecutionFailure(
        action: UiActionStep,
        errorMessage: String,
        beforeScreen: UiScreenState?,
        currentScreen: UiScreenState?
    ): UiExecutionIncidentType {
        val lower = errorMessage.lowercase()
        return when {
            lower.contains("timeout") || lower.contains("timed out") ->
                UiExecutionIncidentType.EXECUTION_TIMEOUT
            lower.contains("lease") || lower.contains("stale") || lower.contains("drifted") ||
                lower.contains("observation authority") || lower.contains("screen changed") ->
                UiExecutionIncidentType.STALE_OBSERVATION
            lower.contains("ambiguous") || lower.contains("multiple matching") ->
                UiExecutionIncidentType.AMBIGUOUS_TARGET
            lower.contains("not found") || lower.contains("missing") || lower.contains("no longer exists") ||
                lower.contains("disappeared") ->
                UiExecutionIncidentType.TARGET_NOT_FOUND
            lower.contains("accessibility") || lower.contains("control plane") || lower.contains("window root") ->
                UiExecutionIncidentType.ACCESSIBILITY_UNAVAILABLE
            beforeScreen != null && currentScreen != null &&
                !beforeScreen.packageName.equals(currentScreen.packageName, ignoreCase = true) ->
                UiExecutionIncidentType.UNEXPECTED_NAVIGATION
            else -> UiExecutionIncidentType.POSTCONDITION_FAILED
        }
    }

    fun decideWithinBudget(
        incident: UiExecutionIncident,
        budget: UiRecoveryBudget,
        action: UiActionStep?,
        goal: String,
        beforeScreen: UiScreenState?,
        currentScreen: UiScreenState?,
        actionAlreadyDispatched: Boolean,
        goalAppCandidates: List<DeviceAction.AppCandidate>
    ): UiRecoveryDecision {
        return when (incident.type) {
            UiExecutionIncidentType.ACCESSIBILITY_UNAVAILABLE -> {
                val delayMs = budget.backoffMsForAttempt(incident.attemptCount)
                UiRecoveryDecision.ReobserveWithBackoff(
                    incident = incident,
                    delayMs = delayMs,
                    allowActionRedispatch = !actionAlreadyDispatched && !incident.isNonIdempotentAction,
                    summary = "Accessibility control plane unavailable (attempt ${incident.attemptCount}); retrying observation after ${delayMs}ms backoff."
                )
            }

            UiExecutionIncidentType.STALE_OBSERVATION -> {
                if (action != null && beforeScreen != null && currentScreen != null) {
                    when (val reground = regroundActionOnFreshScreen(action, beforeScreen, currentScreen)) {
                        is RegroundOutcome.UniqueMatch -> UiRecoveryDecision.RebindAction(
                            incident = incident,
                            replacementAction = reground.action,
                            summary = "Observation was stale; reacquired screen '${currentScreen.screenId}' and re-grounded target to '${reground.matchedElement.id}'."
                        )
                        is RegroundOutcome.Ambiguous -> UiRecoveryDecision.ReplanWithEvidence(
                            incident = incident.copy(type = UiExecutionIncidentType.AMBIGUOUS_TARGET),
                            plannerHint = "Target re-grounding on fresh screen '${currentScreen.screenId}' matched ${reground.candidates.size} ambiguous candidates (${reground.candidates.joinToString { it.id }}). Select a disambiguated element.",
                            summary = "Stale observation re-grounding found ${reground.candidates.size} ambiguous targets; requesting planner disambiguation."
                        )
                        RegroundOutcome.NotFound -> UiRecoveryDecision.ReplanWithEvidence(
                            incident = incident,
                            plannerHint = "Observation '${beforeScreen.screenId}' became stale and target is no longer present on '${currentScreen.screenId}'. Replan from the fresh UI state.",
                            summary = "Observation was stale and target moved off-screen; replanning from fresh observation '${currentScreen.screenId}'."
                        )
                    }
                } else {
                    UiRecoveryDecision.ReobserveWithBackoff(
                        incident = incident,
                        delayMs = budget.backoffMsForAttempt(incident.attemptCount),
                        allowActionRedispatch = false,
                        summary = "Observation lease expired; reacquiring fresh screen state before replanning."
                    )
                }
            }

            UiExecutionIncidentType.TARGET_NOT_FOUND -> {
                if (action != null && currentScreen != null) {
                    when (val relocation = locateAlternativeSemanticTarget(action, beforeScreen, currentScreen)) {
                        is RegroundOutcome.UniqueMatch -> UiRecoveryDecision.RebindAction(
                            incident = incident,
                            replacementAction = relocation.action,
                            summary = "Primary target was missing; bound to alternative semantic locator '${relocation.matchedElement.id}'."
                        )
                        is RegroundOutcome.Ambiguous -> UiRecoveryDecision.ReplanWithEvidence(
                            incident = incident.copy(type = UiExecutionIncidentType.AMBIGUOUS_TARGET),
                            plannerHint = "Primary target was not found and semantic fallback matched ${relocation.candidates.size} candidates (${relocation.candidates.joinToString { it.id }}). Disambiguate using surrounding labels.",
                            summary = "Semantic target fallback matched multiple candidates (${relocation.candidates.joinToString { it.id }}); replanning with disambiguation hint."
                        )
                        RegroundOutcome.NotFound -> UiRecoveryDecision.ReplanWithEvidence(
                            incident = incident,
                            plannerHint = "Target for '${actionName(action)}' is not visible on screen '${currentScreen.screenId}'. Scroll or navigate to reveal it.",
                            summary = "Target not found on '${currentScreen.screenId}'; replanning to reveal or navigate to target."
                        )
                    }
                } else {
                    UiRecoveryDecision.ReplanWithEvidence(
                        incident = incident,
                        plannerHint = "Target not found on current screen. Inspect visible elements and choose an alternative path.",
                        summary = "Target not found; replanning from current UI."
                    )
                }
            }

            UiExecutionIncidentType.AMBIGUOUS_TARGET -> {
                if (action != null && currentScreen != null) {
                    val disambiguated = tryDeterministicDisambiguation(action, beforeScreen, currentScreen)
                    if (disambiguated != null) {
                        return UiRecoveryDecision.RebindAction(
                            incident = incident,
                            replacementAction = disambiguated.first,
                            summary = "Resolved ambiguous target deterministically using unique resource/focus attribute ('${disambiguated.second.id}')."
                        )
                    }
                }
                UiRecoveryDecision.ReplanWithEvidence(
                    incident = incident,
                    plannerHint = "Ambiguous target for '${incident.actionLabel}': ${incident.detail}. Specify an exact elementId with unique context.",
                    summary = "Ambiguous target detected; requesting explicit disambiguation from planner."
                )
            }

            UiExecutionIncidentType.UNEXPECTED_NAVIGATION -> {
                if (currentScreen != null) {
                    val verification = UiGoalVerifier.verify(
                        goal = goal,
                        action = action,
                        before = beforeScreen,
                        after = currentScreen,
                        transportStatus = UiTransportStatus.ACCEPTED,
                        goalAppCandidates = goalAppCandidates
                    )
                    if (verification.isGoalVerified) {
                        return UiRecoveryDecision.DestinationAlreadyReached(
                            incident = incident,
                            verification = verification,
                            summary = "Screen navigated unexpectedly, but destination verification confirmed the goal was reached: ${verification.summary}"
                        )
                    }
                    // If navigated to an unexpected package away from the goal app or source package, navigate back once
                    val expectedPackages = goalAppCandidates.map { it.packageName.lowercase() }.toSet()
                    val driftedToUnrelatedPackage = beforeScreen != null &&
                        !beforeScreen.packageName.equals(currentScreen.packageName, ignoreCase = true) &&
                        (expectedPackages.isNotEmpty() && currentScreen.packageName.orEmpty().lowercase() !in expectedPackages)
                    if (driftedToUnrelatedPackage && incident.attemptCount == 1) {
                        val backStep = DirectSemanticActionResolver.resolveVisibleBack(currentScreen)
                            ?: UiActionStep.PressBack
                        return UiRecoveryDecision.NavigateBackAndReplan(
                            incident = incident,
                            backAction = backStep,
                            summary = "Unexpected navigation to '${currentScreen.packageName}'; navigating back to recover expected context."
                        )
                    }
                }
                UiRecoveryDecision.ReplanWithEvidence(
                    incident = incident,
                    plannerHint = "Unexpected navigation to '${currentScreen?.packageName}/${currentScreen?.screenId}'. Re-evaluate whether this screen leads to '$goal' or navigate back.",
                    summary = "Unexpected navigation to '${currentScreen?.packageName}'; replanning from observed screen."
                )
            }

            UiExecutionIncidentType.POSTCONDITION_FAILED -> {
                if (action != null && currentScreen != null) {
                    if (action is UiActionStep.PressBack || (action is UiActionStep.GlobalAction && action.action == "back")) {
                        val visibleBack = DirectSemanticActionResolver.resolveVisibleBack(currentScreen)
                        if (visibleBack != null) {
                            return UiRecoveryDecision.RebindAction(
                                incident = incident,
                                replacementAction = visibleBack,
                                summary = "Global back postcondition failed; recovering via visible toolbar navigation button."
                            )
                        }
                    }
                }
                UiRecoveryDecision.ReplanWithEvidence(
                    incident = incident,
                    plannerHint = "Action '${incident.actionLabel}' failed its postcondition (${incident.detail}). Do not repeat the identical action on '${currentScreen?.screenId}'; try an alternative control or navigation step.",
                    summary = "Postcondition failed for '${incident.actionLabel}'; collecting evidence and replanning."
                )
            }

            UiExecutionIncidentType.GOAL_NOT_VERIFIED -> {
                UiRecoveryDecision.ReplanWithEvidence(
                    incident = incident,
                    plannerHint = "UI state changed, but goal '$goal' is not yet verified (${incident.detail}). Continue toward the required terminal state.",
                    summary = "Goal remains unverified after '${incident.actionLabel}'; replanning with missing-evidence diagnostics."
                )
            }

            UiExecutionIncidentType.EXECUTION_TIMEOUT -> {
                val delayMs = budget.backoffMsForAttempt(incident.attemptCount)
                UiRecoveryDecision.ReobserveWithBackoff(
                    incident = incident,
                    delayMs = delayMs,
                    allowActionRedispatch = !incident.isNonIdempotentAction,
                    summary = "Action '${incident.actionLabel}' timed out; re-observing screen state after ${delayMs}ms before retrying."
                )
            }
        }
    }

    private sealed interface RegroundOutcome {
        data class UniqueMatch(val action: UiActionStep, val matchedElement: UiElement) : RegroundOutcome
        data class Ambiguous(val candidates: List<UiElement>) : RegroundOutcome
        data object NotFound : RegroundOutcome
    }

    private fun regroundActionOnFreshScreen(
        action: UiActionStep,
        beforeScreen: UiScreenState,
        freshScreen: UiScreenState
    ): RegroundOutcome {
        if (!beforeScreen.packageName.equals(freshScreen.packageName, ignoreCase = true)) {
            return RegroundOutcome.NotFound
        }
        val target = extractTarget(action) ?: return RegroundOutcome.NotFound
        val originalElement = target.elementId?.let(beforeScreen::element)

        // Match by resourceId + label/role on fresh screen
        val candidates = freshScreen.elements.filter { el ->
            el.visible && isActionCompatibleWithElement(action, el) && matchesElementIdentity(originalElement, target, el)
        }
        return when {
            candidates.size == 1 -> {
                val match = candidates.single()
                val newTarget = target.copy(elementId = match.id, fallbackBounds = match.bounds)
                RegroundOutcome.UniqueMatch(replaceTarget(action, newTarget), match)
            }
            candidates.size > 1 -> RegroundOutcome.Ambiguous(candidates)
            else -> locateAlternativeSemanticTarget(action, beforeScreen, freshScreen)
        }
    }

    private fun locateAlternativeSemanticTarget(
        action: UiActionStep,
        beforeScreen: UiScreenState?,
        freshScreen: UiScreenState
    ): RegroundOutcome {
        val target = extractTarget(action) ?: return RegroundOutcome.NotFound
        val originalElement = target.elementId?.let { id -> beforeScreen?.element(id) }
        val searchLabels = listOfNotNull(
            target.textContains,
            originalElement?.text,
            originalElement?.contentDescription,
            originalElement?.hintText,
            originalElement?.resourceId?.substringAfterLast('/')?.replace('_', ' ')
        ).map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()

        if (searchLabels.isEmpty()) {
            // For TypeText/Focus when only a single editable element exists on screen
            if (action is UiActionStep.TypeText || action is UiActionStep.Focus) {
                val editables = freshScreen.elements.filter { it.visible && (it.editable || "set_text" in it.standardActions) }
                if (editables.size == 1) {
                    val only = editables.single()
                    return RegroundOutcome.UniqueMatch(
                        replaceTarget(action, target.copy(elementId = only.id, fallbackBounds = only.bounds)),
                        only
                    )
                }
            }
            return RegroundOutcome.NotFound
        }

        val candidates = freshScreen.elements.filter { el ->
            if (!el.visible || !isActionCompatibleWithElement(action, el)) return@filter false
            val elTerms = listOfNotNull(
                el.text,
                el.contentDescription,
                el.hintText,
                el.resourceId?.substringAfterLast('/')?.replace('_', ' ')
            ).map { it.trim().lowercase() }
            searchLabels.any { wanted ->
                elTerms.any { candidate -> candidate == wanted || candidate.contains(wanted) }
            }
        }

        return when {
            candidates.size == 1 -> {
                val match = candidates.single()
                RegroundOutcome.UniqueMatch(
                    replaceTarget(action, target.copy(elementId = match.id, fallbackBounds = match.bounds)),
                    match
                )
            }
            candidates.size > 1 -> RegroundOutcome.Ambiguous(candidates)
            else -> RegroundOutcome.NotFound
        }
    }

    private fun tryDeterministicDisambiguation(
        action: UiActionStep,
        beforeScreen: UiScreenState?,
        currentScreen: UiScreenState
    ): Pair<UiActionStep, UiElement>? {
        val target = extractTarget(action) ?: return null
        val original = target.elementId?.let { id -> beforeScreen?.element(id) } ?: return null
        if (!original.resourceId.isNullOrBlank()) {
            val exactResMatches = currentScreen.elements.filter { el ->
                el.visible && el.resourceId == original.resourceId && el.text == original.text
            }
            if (exactResMatches.size == 1) {
                val match = exactResMatches.single()
                return replaceTarget(action, target.copy(elementId = match.id, fallbackBounds = match.bounds)) to match
            }
        }
        return null
    }

    private fun matchesElementIdentity(
        original: UiElement?,
        target: UiTarget,
        candidate: UiElement
    ): Boolean {
        if (original != null) {
            val sameResource = !original.resourceId.isNullOrBlank() && original.resourceId == candidate.resourceId
            val sameText = !original.text.isNullOrBlank() && original.text.equals(candidate.text, ignoreCase = true)
            val sameDesc = !original.contentDescription.isNullOrBlank() &&
                original.contentDescription.equals(candidate.contentDescription, ignoreCase = true)
            if (sameResource && (sameText || sameDesc || (original.text.isNullOrBlank() && original.contentDescription.isNullOrBlank()))) {
                return true
            }
            if (original.className == candidate.className && (sameText || sameDesc)) {
                return true
            }
        }
        val role = target.textContains?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return candidate.text.equals(role, ignoreCase = true) ||
            candidate.contentDescription.equals(role, ignoreCase = true) ||
            candidate.hintText.equals(role, ignoreCase = true)
    }

    private fun isActionCompatibleWithElement(action: UiActionStep, element: UiElement): Boolean = when (action) {
        is UiActionStep.TypeText -> element.editable || "set_text" in element.standardActions
        is UiActionStep.Focus -> element.focusable || element.editable
        is UiActionStep.Tap -> element.clickable || element.checkable || element.editable || "click" in element.standardActions
        is UiActionStep.LongPress -> element.longClickable || element.clickable
        else -> true
    }

    fun extractTarget(action: UiActionStep): UiTarget? = when (action) {
        is UiActionStep.Tap -> action.target
        is UiActionStep.LongPress -> action.target
        is UiActionStep.TypeText -> action.target
        is UiActionStep.Focus -> action.target
        is UiActionStep.Scroll -> action.target
        is UiActionStep.NodeAction -> action.target
        is UiActionStep.CustomAction -> action.target
        is UiActionStep.Gesture -> action.target
        else -> null
    }

    private fun replaceTarget(action: UiActionStep, newTarget: UiTarget): UiActionStep = when (action) {
        is UiActionStep.Tap -> action.copy(target = newTarget)
        is UiActionStep.LongPress -> action.copy(target = newTarget)
        is UiActionStep.TypeText -> action.copy(target = newTarget)
        is UiActionStep.Focus -> action.copy(target = newTarget)
        is UiActionStep.Scroll -> action.copy(target = newTarget)
        is UiActionStep.NodeAction -> action.copy(target = newTarget)
        is UiActionStep.CustomAction -> action.copy(target = newTarget)
        is UiActionStep.Gesture -> action.copy(target = newTarget)
        else -> action
    }

    fun actionName(action: UiActionStep): String = when (action) {
        is UiActionStep.Tap -> "tap"
        is UiActionStep.LongPress -> "long_press"
        is UiActionStep.TypeText -> "type_text"
        is UiActionStep.Focus -> "focus"
        is UiActionStep.Scroll -> "scroll_${action.direction.name.lowercase()}"
        is UiActionStep.NodeAction -> "node_action:${action.action}"
        is UiActionStep.CustomAction -> "custom_action:${action.actionRef}"
        is UiActionStep.Gesture -> "gesture:${action.gesture}"
        is UiActionStep.OpenApp -> "open_app:${action.packageName}"
        is UiActionStep.OpenSettingsPage -> "open_settings:${action.page.name.lowercase()}"
        UiActionStep.PressBack -> "press_back"
        UiActionStep.PressHome -> "press_home"
        UiActionStep.PressRecents -> "press_recents"
        UiActionStep.OpenNotifications -> "open_notifications"
        UiActionStep.OpenQuickSettings -> "open_quick_settings"
        is UiActionStep.GlobalAction -> "global_action:${action.action}"
        is UiActionStep.ShareText -> "share_text"
        is UiActionStep.ShareCapturedMedia -> "share_captured_media"
        is UiActionStep.OpenCamera -> "open_camera"
        is UiActionStep.OpenUrl -> "open_url"
        is UiActionStep.Wait -> "wait"
        is UiActionStep.Done -> "done"
        is UiActionStep.AskUser -> "ask_user"
        is UiActionStep.Assert -> "assert"
    }
}

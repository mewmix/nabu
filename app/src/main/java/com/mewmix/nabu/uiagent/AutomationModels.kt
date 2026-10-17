package com.mewmix.nabu.uiagent

enum class Outcome {
    SUCCEEDED,
    FAILED,
    BLOCKED,
    DENIED,
    TIMED_OUT
}

data class UiActionHistoryEntry(
    val index: Int,
    val action: String,
    val targetElementId: String?,
    val targetLabel: String?,
    val sourceScreenId: String,
    val resultScreenId: String?,
    val outcome: Outcome,
    val changedScreen: Boolean,
    val detail: String?,
    val sourcePackage: String? = null,
    val resultPackage: String? = null
)

data class AutomationBudget(
    val maxExecutedActions: Int = 12,
    val maxWallClockDurationMs: Long = 180_000,
    val maxPlannerRetriesPerObservation: Int = 1,
    val maxIdenticalActionUnchangedScreen: Int = 1,
    val maxUnchangedObservations: Int = 3,
    val maxSingleWaitMs: Long = 5_000,
    val maxCumulativeWaitMs: Long = 20_000,
    val maxUiTransitionWaitMs: Long = 5_000,
    val maxInAppTransitionWaitMs: Long = 1_500,
    val transitionPollIntervalMs: Long = 50,
    val postActionSettleDelayMs: Long = 40
)

internal object AutomationAppScope {
    fun allows(
        packageName: String,
        goal: String,
        candidates: Collection<com.mewmix.nabu.actions.DeviceAction.AppCandidate>
    ): Boolean {
        val normalizedPackage = packageName.trim().lowercase()
        if (normalizedPackage.isBlank()) return false
        if (candidates.any { it.packageName.equals(normalizedPackage, ignoreCase = true) }) return true
        return goal.lowercase().contains(normalizedPackage)
    }

    fun remainingCandidates(
        goal: String,
        candidates: List<com.mewmix.nabu.actions.DeviceAction.AppCandidate>,
        history: List<UiActionHistoryEntry>
    ): List<com.mewmix.nabu.actions.DeviceAction.AppCandidate> = candidates.filter { candidate ->
        val completedCount = history.count { entry ->
            entry.outcome == Outcome.SUCCEEDED &&
                entry.action.equals("open app ${candidate.packageName}", ignoreCase = true)
        }
        completedCount < requestedLaunchCount(goal, candidate)
    }

    private fun requestedLaunchCount(
        goal: String,
        candidate: com.mewmix.nabu.actions.DeviceAction.AppCandidate
    ): Int {
        val normalizedGoal = normalize(goal)
        val labelCount = countPhrase(normalizedGoal, normalize(candidate.label))
        val packageCount = countPhrase(goal.lowercase(), candidate.packageName.lowercase())
        return maxOf(1, labelCount, packageCount)
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun countPhrase(text: String, phrase: String): Int {
        if (phrase.isBlank()) return 0
        var count = 0
        var start = 0
        while (start <= text.length - phrase.length) {
            val index = text.indexOf(phrase, start)
            if (index < 0) break
            val leftBoundary = index == 0 || text[index - 1] == ' '
            val end = index + phrase.length
            val rightBoundary = end == text.length || text[end] == ' '
            if (leftBoundary && rightBoundary) count++
            start = index + phrase.length
        }
        return count
    }
}

internal object UiTransitionPolicy {
    fun maxWaitMs(action: UiActionStep, budget: AutomationBudget): Long = when (action) {
        is UiActionStep.OpenApp,
        is UiActionStep.OpenSettingsPage,
        is UiActionStep.OpenUrl,
        is UiActionStep.ShareText,
        is UiActionStep.OpenCamera,
        is UiActionStep.ShareCapturedMedia,
        UiActionStep.PressBack,
        UiActionStep.PressHome,
        UiActionStep.PressRecents,
        UiActionStep.OpenNotifications,
        UiActionStep.OpenQuickSettings -> budget.maxUiTransitionWaitMs
        is UiActionStep.GlobalAction -> budget.maxUiTransitionWaitMs
        is UiActionStep.Tap,
        is UiActionStep.Focus,
        is UiActionStep.NodeAction,
        is UiActionStep.CustomAction,
        is UiActionStep.Gesture,
        is UiActionStep.LongPress,
        is UiActionStep.TypeText,
        is UiActionStep.Scroll -> budget.maxInAppTransitionWaitMs
        is UiActionStep.Wait,
        is UiActionStep.Assert,
        is UiActionStep.AskUser,
        is UiActionStep.Done -> 0
    }

    fun isSettled(
        previous: UiScreenState,
        next: UiScreenState,
        action: UiActionStep
    ): Boolean {
        if (action is UiActionStep.OpenApp) {
            return next.packageName.equals(action.packageName, ignoreCase = true)
        }
        if (action is UiActionStep.ShareText && !action.targetPackage.isNullOrBlank()) {
            return next.packageName.equals(action.targetPackage, ignoreCase = true)
        }
        if (action is UiActionStep.ShareCapturedMedia && action.targetPackage.isNotBlank()) {
            return next.packageName.equals(action.targetPackage, ignoreCase = true)
        }
        return next.screenId != previous.screenId ||
            !next.packageName.equals(previous.packageName, ignoreCase = true)
    }
}

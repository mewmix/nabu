package com.mewmix.nabu.uiagent

import com.mewmix.nabu.actions.DeviceAction

/**
 * Typed outcome verification status inspired by ARTEMIS checker semantics.
 *
 * Transport acceptance ([UiTransportStatus]) and immediate step postconditions
 * ([PostconditionStatus]) are kept strictly separate from goal verification.
 */
enum class UiVerificationStatus {
    /** Android accepted the action; intended result is not yet established. */
    EXECUTED,

    /** A UI transition or control mutation occurred, but the goal remains unverified. */
    STATE_CHANGED,

    /** Explicit goal-specific evidence matches the desired outcome. */
    GOAL_VERIFIED,

    /** Evidence contradicts the required outcome or execution failed. */
    FAILED,

    /** Insufficient or unavailable evidence to establish the outcome. */
    UNVERIFIABLE;

    val isGoalVerified: Boolean
        get() = this == GOAL_VERIFIED

    val isFailure: Boolean
        get() = this == FAILED

    val isMechanicalProgress: Boolean
        get() = this == GOAL_VERIFIED || this == STATE_CHANGED || this == EXECUTED
}

/**
 * Transport-layer dispatch status, separate from postcondition and goal verification.
 */
enum class UiTransportStatus {
    NOT_DISPATCHED,
    ACCEPTED,
    REJECTED,
    TIMED_OUT;

    val isAccepted: Boolean
        get() = this == ACCEPTED
}

internal enum class SystemSurfaceKind {
    NOTIFICATION_SHADE,
    QUICK_SETTINGS,
    HOME_SCREEN,
    RECENTS
}

internal data class SystemSurfaceEvaluation(
    val expected: SystemSurfaceKind,
    val matched: Boolean,
    val detail: String,
    val matchedEvidence: List<String> = emptyList(),
    val contradictedByOtherSurface: SystemSurfaceKind? = null
)

internal object UiSystemSurfaceDetector {
    private val NOTIFICATION_RESOURCE_MARKERS = setOf(
        "notification_stack_scroller",
        "notification_stack",
        "notification_panel",
        "scrim_notifications",
        "shelf_menu_anchor",
        "clear_all",
        "dismiss_text",
        "expandablenotificationrow",
        "status_bar_latest_event"
    )

    private val NOTIFICATION_TEXT_MARKERS = setOf(
        "notifications",
        "notification shade",
        "clear all",
        "no notifications",
        "manage notifications",
        "manage",
        "silent notifications",
        "alerting notifications"
    )

    private val QUICK_SETTINGS_RESOURCE_MARKERS = setOf(
        "quick_settings_panel",
        "quick_settings_container",
        "qs_panel",
        "qs_frame",
        "qs_pager",
        "qs_footer",
        "qs_customize",
        "brightness_slider",
        "brightness_mirror",
        "tile_label"
    )

    private val QUICK_SETTINGS_TEXT_MARKERS = setOf(
        "quick settings",
        "edit tiles",
        "display brightness",
        "brightness slider",
        "brightness"
    )

    private val QUICK_SETTINGS_TILE_LABELS = setOf(
        "internet",
        "wi-fi",
        "wifi",
        "bluetooth",
        "do not disturb",
        "flashlight",
        "auto-rotate",
        "battery saver",
        "airplane mode",
        "hotspot",
        "location",
        "screen record",
        "dark theme",
        "nearby share",
        "quick share"
    )

    fun evaluate(
        screen: UiScreenState,
        expected: SystemSurfaceKind,
        before: UiScreenState? = null
    ): SystemSurfaceEvaluation = when (expected) {
        SystemSurfaceKind.NOTIFICATION_SHADE -> evaluateNotificationShade(screen)
        SystemSurfaceKind.QUICK_SETTINGS -> evaluateQuickSettings(screen)
        SystemSurfaceKind.HOME_SCREEN -> evaluateHomeScreen(screen, before)
        SystemSurfaceKind.RECENTS -> evaluateRecents(screen, before)
    }

    fun isNotificationShadeVisible(screen: UiScreenState): Boolean =
        evaluateNotificationShade(screen).matched

    fun isQuickSettingsVisible(screen: UiScreenState): Boolean =
        evaluateQuickSettings(screen).matched

    private fun evaluateNotificationShade(screen: UiScreenState): SystemSurfaceEvaluation {
        val notifEvidence = collectNotificationEvidence(screen)
        val qsSpecificEvidence = collectQuickSettingsExclusiveEvidence(screen)

        if (notifEvidence.isNotEmpty() && (qsSpecificEvidence.isEmpty() || hasExplicitNotificationListEvidence(screen))) {
            return SystemSurfaceEvaluation(
                expected = SystemSurfaceKind.NOTIFICATION_SHADE,
                matched = true,
                detail = "The notification shade is visible (${notifEvidence.first()}).",
                matchedEvidence = notifEvidence
            )
        }

        if (qsSpecificEvidence.isNotEmpty()) {
            return SystemSurfaceEvaluation(
                expected = SystemSurfaceKind.NOTIFICATION_SHADE,
                matched = false,
                detail = "Expected Notification Shade, but observed Quick Settings surface (${qsSpecificEvidence.first()}).",
                contradictedByOtherSurface = SystemSurfaceKind.QUICK_SETTINGS
            )
        }

        return SystemSurfaceEvaluation(
            expected = SystemSurfaceKind.NOTIFICATION_SHADE,
            matched = false,
            detail = "The notification system surface was not observed; generic SystemUI or app content is insufficient."
        )
    }

    private fun evaluateQuickSettings(screen: UiScreenState): SystemSurfaceEvaluation {
        val qsEvidence = collectQuickSettingsEvidence(screen)
        val notifEvidence = collectNotificationEvidence(screen)
        val hasExplicitNotificationList = hasExplicitNotificationListEvidence(screen)
        val hasStrongQsPanel = hasStrongQuickSettingsPanelEvidence(screen)

        // If the screen shows the notification list ("Clear all", "No notifications", notification_stack_scroller)
        // without an expanded Quick Settings panel (brightness slider, qs_panel, edit tiles, or >= 3 QS tiles),
        // it is the Notification Shade, not Quick Settings.
        if (qsEvidence.isNotEmpty() && (!hasExplicitNotificationList || hasStrongQsPanel)) {
            return SystemSurfaceEvaluation(
                expected = SystemSurfaceKind.QUICK_SETTINGS,
                matched = true,
                detail = "The quick settings system surface is visible (${qsEvidence.first()}).",
                matchedEvidence = qsEvidence
            )
        }

        if (notifEvidence.isNotEmpty()) {
            return SystemSurfaceEvaluation(
                expected = SystemSurfaceKind.QUICK_SETTINGS,
                matched = false,
                detail = "Expected Quick Settings, but observed Notification Shade (${notifEvidence.first()}).",
                contradictedByOtherSurface = SystemSurfaceKind.NOTIFICATION_SHADE
            )
        }

        return SystemSurfaceEvaluation(
            expected = SystemSurfaceKind.QUICK_SETTINGS,
            matched = false,
            detail = "The quick settings system surface was not observed; generic SystemUI visibility is insufficient."
        )
    }

    private fun evaluateHomeScreen(
        screen: UiScreenState,
        before: UiScreenState?
    ): SystemSurfaceEvaluation {
        val pkg = screen.packageName.orEmpty().lowercase()
        val activity = screen.activityName.orEmpty().lowercase()
        val isLauncherPackage = pkg.contains("launcher") ||
            pkg.contains("trebuchet") ||
            pkg.contains("nexuslauncher") ||
            pkg.contains("pixel") && activity.contains("launcher") ||
            activity.contains("launcher") ||
            screen.elements.any { el ->
                val res = el.resourceId.orEmpty().lowercase()
                res.contains("workspace") || res.contains("hotseat") || res.contains("apps_list_view")
            }
        val transitionedFromNonLauncher = before != null &&
            (before.screenId != screen.screenId || !before.packageName.equals(screen.packageName, ignoreCase = true)) &&
            !pkg.contains("systemui") &&
            !before.packageName.equals(screen.packageName, ignoreCase = true)

        return if (isLauncherPackage || transitionedFromNonLauncher) {
            SystemSurfaceEvaluation(
                expected = SystemSurfaceKind.HOME_SCREEN,
                matched = true,
                detail = "Home screen is visible (${screen.packageName}).",
                matchedEvidence = listOf("package:${screen.packageName}")
            )
        } else {
            SystemSurfaceEvaluation(
                expected = SystemSurfaceKind.HOME_SCREEN,
                matched = false,
                detail = "Home screen was not observed."
            )
        }
    }

    private fun evaluateRecents(
        screen: UiScreenState,
        before: UiScreenState?
    ): SystemSurfaceEvaluation {
        val pkg = screen.packageName.orEmpty().lowercase()
        val activity = screen.activityName.orEmpty().lowercase()
        val hasRecentsEvidence = activity.contains("recents") ||
            activity.contains("overview") ||
            screen.elements.any { el ->
                val text = "${el.text.orEmpty()} ${el.contentDescription.orEmpty()} ${el.resourceId.orEmpty()}".lowercase()
                text.contains("clear all") && (pkg.contains("launcher") || pkg.contains("systemui")) ||
                    text.contains("overview_panel") ||
                    text.contains("recents_view") ||
                    text.contains("recent apps") ||
                    text.contains("screenshot") && text.contains("select") && pkg.contains("launcher")
            } ||
            (before != null && before.screenId != screen.screenId && (pkg.contains("launcher") || pkg.contains("systemui")))

        return if (hasRecentsEvidence) {
            SystemSurfaceEvaluation(
                expected = SystemSurfaceKind.RECENTS,
                matched = true,
                detail = "Recents overview is visible.",
                matchedEvidence = listOf("recents:${screen.packageName}")
            )
        } else {
            SystemSurfaceEvaluation(
                expected = SystemSurfaceKind.RECENTS,
                matched = false,
                detail = "Recents overview was not observed."
            )
        }
    }

    private fun collectNotificationEvidence(screen: UiScreenState): List<String> {
        val matched = mutableListOf<String>()
        val activity = screen.activityName.orEmpty().lowercase()
        if (activity.contains("notification")) {
            matched += "window:$activity"
        }
        screen.elements.filter { it.visible }.forEach { element ->
            val res = element.resourceId.orEmpty().lowercase()
            val label = "${element.text.orEmpty()} ${element.contentDescription.orEmpty()}".trim().lowercase()
            NOTIFICATION_RESOURCE_MARKERS.firstOrNull { res.contains(it) }?.let { marker ->
                matched += "resource:$marker"
            }
            NOTIFICATION_TEXT_MARKERS.firstOrNull { marker ->
                label == marker || label.contains("notification shade") || label.contains("clear all") ||
                    label.contains("no notifications") || label.contains("manage notifications") ||
                    label.contains("silent notifications") || label.contains("alerting notifications") ||
                    (marker == "notifications" && Regex("\\bnotifications\\b").containsMatchIn(label))
            }?.let { marker ->
                matched += "label:$marker"
            }
        }
        return matched.distinct()
    }

    private fun hasExplicitNotificationListEvidence(screen: UiScreenState): Boolean =
        screen.elements.filter { it.visible }.any { element ->
            val res = element.resourceId.orEmpty().lowercase()
            val label = "${element.text.orEmpty()} ${element.contentDescription.orEmpty()}".trim().lowercase()
            res.contains("notification_stack") ||
                res.contains("clear_all") ||
                res.contains("expandablenotificationrow") ||
                label.contains("clear all") ||
                label.contains("no notifications") ||
                label.contains("manage notifications") ||
                label.contains("notification shade") ||
                Regex("\\bnotifications\\b").containsMatchIn(label)
        }

    private fun collectQuickSettingsEvidence(screen: UiScreenState): List<String> {
        val matched = mutableListOf<String>()
        val activity = screen.activityName.orEmpty().lowercase()
        if (activity.contains("quicksettings") || activity.contains("qs")) {
            matched += "window:$activity"
        }
        val matchedTiles = mutableSetOf<String>()
        screen.elements.filter { it.visible }.forEach { element ->
            val res = element.resourceId.orEmpty().lowercase()
            val label = "${element.text.orEmpty()} ${element.contentDescription.orEmpty()}".trim().lowercase()
            QUICK_SETTINGS_RESOURCE_MARKERS.firstOrNull { res.contains(it) }?.let { marker ->
                matched += "resource:$marker"
            }
            QUICK_SETTINGS_TEXT_MARKERS.firstOrNull { marker ->
                label.contains(marker)
            }?.let { marker ->
                matched += "label:$marker"
            }
            QUICK_SETTINGS_TILE_LABELS.firstOrNull { tile ->
                label == tile || label.startsWith("$tile ") || label.startsWith("$tile,") || label.endsWith(" $tile")
            }?.let { tile ->
                matchedTiles += tile
            }
        }
        if (matchedTiles.size >= 2 || (matchedTiles.size >= 1 && matched.isNotEmpty())) {
            matched += matchedTiles.map { "tile:$it" }
        }
        return matched.distinct()
    }

    private fun collectQuickSettingsExclusiveEvidence(screen: UiScreenState): List<String> =
        collectQuickSettingsEvidence(screen).filter {
            it.startsWith("resource:quick_settings") ||
                it.startsWith("resource:qs_") ||
                it.startsWith("resource:brightness") ||
                it.startsWith("label:quick settings") ||
                it.startsWith("label:edit tiles") ||
                it.startsWith("label:brightness") ||
                it.startsWith("label:display brightness")
        }

    private fun hasStrongQuickSettingsPanelEvidence(screen: UiScreenState): Boolean {
        val evidence = collectQuickSettingsEvidence(screen)
        val hasPanelOrBrightness = evidence.any {
            it.contains("quick_settings_panel") ||
                it.contains("qs_panel") ||
                it.contains("qs_pager") ||
                it.contains("qs_customize") ||
                it.contains("brightness") ||
                it.contains("label:quick settings") ||
                it.contains("label:edit tiles")
        }
        val tileCount = evidence.count { it.startsWith("tile:") }
        return hasPanelOrBrightness || tileCount >= 3
    }
}

internal sealed interface UiExpectedOutcome {
    val description: String

    data class AppForegrounded(
        val packageName: String,
        val appLabel: String? = null
    ) : UiExpectedOutcome {
        override val description: String =
            "Foreground package must be '${packageName}'" + (appLabel?.let { " ($it)" } ?: "")
    }

    data class SystemSurfaceVisible(
        val surface: SystemSurfaceKind
    ) : UiExpectedOutcome {
        override val description: String =
            "System surface '${surface.name.lowercase()}' must be visible with surface-specific evidence"
    }

    data class SettingsSurfaceVisible(
        val page: SettingsPage?,
        val expectedKeywords: Set<String>,
        val targetPackage: String? = null
    ) : UiExpectedOutcome {
        override val description: String =
            "Settings surface '${page?.name?.lowercase() ?: "settings"}' must be visible with matching controls"
    }

    data class SearchResultsVisible(
        val query: String,
        val requiredPackages: Set<String> = emptySet()
    ) : UiExpectedOutcome {
        override val description: String =
            "Committed search results for '$query' must be visible outside the active search input"
    }

    data class TypedTextPresent(
        val expectedText: String,
        val targetLabel: String? = null,
        val requiredPackages: Set<String> = emptySet()
    ) : UiExpectedOutcome {
        override val description: String =
            "Editable input${targetLabel?.let { " '$it'" } ?: ""} must contain '$expectedText'"
    }

    data class TargetPropertyMatched(
        val targetLabel: String?,
        val elementId: String? = null,
        val expectedChecked: Boolean? = null,
        val expectedFocused: Boolean? = null,
        val expectedSelected: Boolean? = null,
        val expectedAccessibilityFocused: Boolean? = null,
        val expectedSelectionRange: Pair<Int, Int>? = null,
        val requiredPackages: Set<String> = emptySet()
    ) : UiExpectedOutcome {
        override val description: String = buildString {
            append("Target control")
            targetLabel?.let { append(" '$it'") }
            elementId?.let { append(" ($it)") }
            expectedChecked?.let { append(" checked=$it") }
            expectedFocused?.let { append(" focused=$it") }
            expectedSelected?.let { append(" selected=$it") }
            expectedAccessibilityFocused?.let { append(" accessibilityFocused=$it") }
            expectedSelectionRange?.let { append(" selection=${it.first}..${it.second}") }
        }
    }

    data class RangeValueMatched(
        val targetLabel: String?,
        val elementId: String? = null,
        val expectedValue: Float,
        val isPercent: Boolean = false,
        val requiredPackages: Set<String> = emptySet()
    ) : UiExpectedOutcome {
        override val description: String =
            "Range control${targetLabel?.let { " '$it'" } ?: ""} must reach ${expectedValue}${if (isPercent) "%" else ""}"
    }

    data class DestinationVisible(
        val destinationName: String,
        val requiredPackages: Set<String> = emptySet()
    ) : UiExpectedOutcome {
        override val description: String =
            "Destination '$destinationName' must be visible on the resulting screen"
    }

    data object BackNavigationCompleted : UiExpectedOutcome {
        override val description: String = "Back navigation must transition away from the prior screen"
    }

    data class ScrollStateChanged(
        val direction: ScrollDirection? = null
    ) : UiExpectedOutcome {
        override val description: String =
            "Scroll action must advance the visible viewport"
    }

    data class ConsequentialActionConfirmed(
        val actionKind: String,
        val expectedDestination: String? = null,
        val expectedContent: String? = null
    ) : UiExpectedOutcome {
        override val description: String =
            "Consequential action '$actionKind' requires user confirmation and post-action confirmation evidence"
    }

    data class ExplicitAssertionMatched(
        val assertion: UiAssertion
    ) : UiExpectedOutcome {
        override val description: String =
            "Explicit assertion (element=${assertion.elementId}, textContains=${assertion.textContains}, checked=${assertion.checked}) must match"
    }

    data class UnverifiedFreeformGoal(
        val rawGoal: String,
        val requiredKeywords: Set<String>,
        val requiredPackages: Set<String> = emptySet()
    ) : UiExpectedOutcome {
        override val description: String =
            "Goal '$rawGoal' requires explicit on-screen evidence (${requiredKeywords.joinToString(", ")})"
    }
}

internal data class UiGoalCheckpoint(
    val id: String,
    val expectedOutcome: UiExpectedOutcome,
    val isTerminal: Boolean = true
)

internal data class GoalVerificationResult(
    val status: UiVerificationStatus,
    val transportStatus: UiTransportStatus,
    val postconditionStatus: PostconditionStatus,
    val checkpointId: String,
    val evidenceCategory: String,
    val summary: String,
    val matchedEvidence: List<String> = emptyList(),
    val missingOrContradictingEvidence: List<String> = emptyList()
) {
    val isGoalVerified: Boolean
        get() = status == UiVerificationStatus.GOAL_VERIFIED
}

/**
 * Narrowly-scoped deterministic goal and checkpoint verifier.
 *
 * Ensures that:
 * 1. Every goal/checkpoint declares a machine-evaluable expected outcome.
 * 2. Fresh post-action state is required before claiming completion.
 * 3. Generic screen or package mutation is never promoted to [UiVerificationStatus.GOAL_VERIFIED].
 * 4. The acting model's `Done` assertion is never accepted as independent proof.
 * 5. Notification Shade and Quick Settings require surface-specific evidence.
 * 6. Irreversible / externally consequential actions require confirmation + post-action evidence.
 * 7. Ambiguous or unavailable evidence fails closed.
 */
object UiGoalVerifier {
    private val SEARCH_VERBS = Regex(
        pattern = "\\b(?:search(?:\\s+for)?|find|look\\s+up)\\s+(.+)",
        option = RegexOption.IGNORE_CASE
    )
    private val TYPE_TEXT_PATTERN = Regex(
        pattern = "^(?:please\\s+)?(?:type|enter|input|write)\\s+[\"']?(.+?)[\"']?(?:\\s+(?:in|into|on)\\s+(.+))?$",
        option = RegexOption.IGNORE_CASE
    )
    private val FOCUS_PATTERN = Regex(
        pattern = "^(?:please\\s+)?(?:focus(?:\\s+on)?|tap\\s+composer|tap\\s+message|focus\\s+message|focus\\s+composer)\\s*(.*)$",
        option = RegexOption.IGNORE_CASE
    )
    private val TOGGLE_ON_PATTERN = Regex(
        pattern = "^(?:please\\s+)?(?:turn\\s+on|enable|switch\\s+on|check|activate\\s+toggle)\\s+(.+)$",
        option = RegexOption.IGNORE_CASE
    )
    private val TOGGLE_OFF_PATTERN = Regex(
        pattern = "^(?:please\\s+)?(?:turn\\s+off|disable|switch\\s+off|uncheck)\\s+(.+)$",
        option = RegexOption.IGNORE_CASE
    )
    private val SET_PROGRESS_PATTERN = Regex(
        pattern = "^(?:please\\s+)?set\\s+(.+?)\\s+to\\s+(-?[0-9]+(?:\\.[0-9]+)?)\\s*(%|percent)?$",
        option = RegexOption.IGNORE_CASE
    )
    private val SEND_MESSAGE_PATTERN = Regex(
        pattern = "\\b(?:send|message|post|publish|reply)\\b",
        option = RegexOption.IGNORE_CASE
    )
    private val CONSEQUENTIAL_VERB_PATTERN = Regex(
        pattern = "\\b(?:send|post|publish|delete|remove|call|dial|purchase|buy|pay|transfer)\\b",
        option = RegexOption.IGNORE_CASE
    )
    private val NAVIGATE_DESTINATION_PATTERN = Regex(
        pattern = "(?:\\b(?:go\\s+to|navigate\\s+to|open|view|show|select|tap|click)\\s+)(?:the\\s+)?([a-z0-9][a-z0-9 _'-]{1,40})$",
        option = RegexOption.IGNORE_CASE
    )
    private val STOP_WORDS = setOf(
        "please", "the", "a", "an", "in", "on", "to", "for", "and", "then", "with", "from",
        "into", "app", "application", "screen", "page", "button", "item", "tab", "menu",
        "open", "launch", "start", "use", "switch", "go", "navigate", "tap", "click", "press"
    )

    internal fun compileCheckpoints(
        goal: String,
        goalAppCandidates: List<DeviceAction.AppCandidate> = emptyList(),
        explicitAssertion: UiAssertion? = null
    ): List<UiGoalCheckpoint> {
        val trimmed = goal.trim()
        if (trimmed.isBlank()) return emptyList()
        val normalized = trimmed.lowercase().replace(Regex("\\s+"), " ").removePrefix("please ").trim()
        val requiredPackages = goalAppCandidates
            .map { it.packageName.lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

        val checkpoints = mutableListOf<UiGoalCheckpoint>()

        // 1. System surface goals
        when {
            normalized in setOf("open quick settings", "show quick settings", "expand quick settings") ||
                normalized.contains("quick settings") && !normalized.contains("notification") -> {
                checkpoints += UiGoalCheckpoint(
                    id = "system_surface_quick_settings",
                    expectedOutcome = UiExpectedOutcome.SystemSurfaceVisible(SystemSurfaceKind.QUICK_SETTINGS),
                    isTerminal = true
                )
                return withExplicitAssertion(checkpoints, explicitAssertion)
            }
            normalized in setOf(
                "open notifications",
                "show notifications",
                "open notification shade",
                "show notification shade",
                "read notifications"
            ) || normalized.contains("notification shade") -> {
                checkpoints += UiGoalCheckpoint(
                    id = "system_surface_notifications",
                    expectedOutcome = UiExpectedOutcome.SystemSurfaceVisible(SystemSurfaceKind.NOTIFICATION_SHADE),
                    isTerminal = true
                )
                return withExplicitAssertion(checkpoints, explicitAssertion)
            }
            normalized in setOf("press home", "go home", "go to home", "open home screen", "show home screen") -> {
                checkpoints += UiGoalCheckpoint(
                    id = "system_surface_home",
                    expectedOutcome = UiExpectedOutcome.SystemSurfaceVisible(SystemSurfaceKind.HOME_SCREEN),
                    isTerminal = true
                )
                return withExplicitAssertion(checkpoints, explicitAssertion)
            }
            normalized in setOf("open recents", "show recents", "press recents", "recent apps") -> {
                checkpoints += UiGoalCheckpoint(
                    id = "system_surface_recents",
                    expectedOutcome = UiExpectedOutcome.SystemSurfaceVisible(SystemSurfaceKind.RECENTS),
                    isTerminal = true
                )
                return withExplicitAssertion(checkpoints, explicitAssertion)
            }
            normalized in setOf("press back", "go back", "navigate back") -> {
                checkpoints += UiGoalCheckpoint(
                    id = "navigation_back",
                    expectedOutcome = UiExpectedOutcome.BackNavigationCompleted,
                    isTerminal = true
                )
                return withExplicitAssertion(checkpoints, explicitAssertion)
            }
            normalized in setOf("scroll down", "scroll up", "scroll left", "scroll right") -> {
                val direction = when (normalized.substringAfter("scroll ").trim()) {
                    "up" -> ScrollDirection.UP
                    "left" -> ScrollDirection.LEFT
                    "right" -> ScrollDirection.RIGHT
                    else -> ScrollDirection.DOWN
                }
                checkpoints += UiGoalCheckpoint(
                    id = "scroll_${direction.name.lowercase()}",
                    expectedOutcome = UiExpectedOutcome.ScrollStateChanged(direction),
                    isTerminal = true
                )
                return withExplicitAssertion(checkpoints, explicitAssertion)
            }
        }

        // 1b. Settings page goals
        detectSettingsPageFromGoal(normalized)?.let { settingsPage ->
            checkpoints += UiGoalCheckpoint(
                id = "settings_${settingsPage.name.lowercase()}",
                expectedOutcome = UiExpectedOutcome.SettingsSurfaceVisible(
                    page = settingsPage,
                    expectedKeywords = settingsKeywordsFor(settingsPage, null)
                ),
                isTerminal = true
            )
            return withExplicitAssertion(checkpoints, explicitAssertion)
        }

        // 2. Add app-launch checkpoints when app candidates are resolved
        val hasDownstreamTask = hasSubGoalBeyondAppLaunch(normalized, goalAppCandidates)
        goalAppCandidates.forEachIndexed { index, candidate ->
            val isLastApp = index == goalAppCandidates.lastIndex
            checkpoints += UiGoalCheckpoint(
                id = "app_foreground_${candidate.packageName}",
                expectedOutcome = UiExpectedOutcome.AppForegrounded(
                    packageName = candidate.packageName,
                    appLabel = candidate.label
                ),
                isTerminal = isLastApp && !hasDownstreamTask
            )
        }

        if (!hasDownstreamTask && checkpoints.isNotEmpty()) {
            return withExplicitAssertion(checkpoints, explicitAssertion)
        }

        // 3. Consequential actions (send message, delete, call, etc.)
        if (CONSEQUENTIAL_VERB_PATTERN.containsMatchIn(normalized)) {
            val destination = extractExpectedDestination(trimmed, goalAppCandidates)
            val content = extractQuotedOrMessageBody(trimmed)
            checkpoints += UiGoalCheckpoint(
                id = "consequential_action_confirmed",
                expectedOutcome = UiExpectedOutcome.ConsequentialActionConfirmed(
                    actionKind = CONSEQUENTIAL_VERB_PATTERN.find(normalized)?.value?.lowercase() ?: "commit",
                    expectedDestination = destination,
                    expectedContent = content
                ),
                isTerminal = true
            )
            return withExplicitAssertion(checkpoints, explicitAssertion)
        }

        // 4. Search goals
        extractSearchQuery(trimmed, goalAppCandidates)?.let { query ->
            checkpoints += UiGoalCheckpoint(
                id = "search_results_${query.lowercase().replace(Regex("[^a-z0-9]+"), "_")}",
                expectedOutcome = UiExpectedOutcome.SearchResultsVisible(
                    query = query,
                    requiredPackages = requiredPackages
                ),
                isTerminal = true
            )
            return withExplicitAssertion(checkpoints, explicitAssertion)
        }

        // 5. Range / progress goals
        SET_PROGRESS_PATTERN.matchEntire(normalized)?.let { match ->
            val label = match.groupValues[1].removePrefix("the ").trim()
            val value = match.groupValues[2].toFloatOrNull()
            val isPercent = match.groupValues[3].isNotBlank()
            if (value != null) {
                checkpoints += UiGoalCheckpoint(
                    id = "range_value_${label.replace(' ', '_')}",
                    expectedOutcome = UiExpectedOutcome.RangeValueMatched(
                        targetLabel = label,
                        expectedValue = value,
                        isPercent = isPercent,
                        requiredPackages = requiredPackages
                    ),
                    isTerminal = true
                )
                return withExplicitAssertion(checkpoints, explicitAssertion)
            }
        }

        // 6. Typed text goals
        TYPE_TEXT_PATTERN.matchEntire(trimmed)?.let { match ->
            val text = match.groupValues[1].trim()
            val targetLabel = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.removePrefix("the ")?.trim()
            if (text.isNotBlank()) {
                checkpoints += UiGoalCheckpoint(
                    id = "typed_text",
                    expectedOutcome = UiExpectedOutcome.TypedTextPresent(
                        expectedText = text,
                        targetLabel = targetLabel,
                        requiredPackages = requiredPackages
                    ),
                    isTerminal = true
                )
                return withExplicitAssertion(checkpoints, explicitAssertion)
            }
        }

        // 7. Focus / Toggle / Control state goals
        TOGGLE_ON_PATTERN.matchEntire(normalized)?.let { match ->
            val label = match.groupValues[1].removePrefix("the ").trim()
            checkpoints += UiGoalCheckpoint(
                id = "toggle_on_${label.replace(' ', '_')}",
                expectedOutcome = UiExpectedOutcome.TargetPropertyMatched(
                    targetLabel = label,
                    expectedChecked = true,
                    requiredPackages = requiredPackages
                ),
                isTerminal = true
            )
            return withExplicitAssertion(checkpoints, explicitAssertion)
        }
        TOGGLE_OFF_PATTERN.matchEntire(normalized)?.let { match ->
            val label = match.groupValues[1].removePrefix("the ").trim()
            checkpoints += UiGoalCheckpoint(
                id = "toggle_off_${label.replace(' ', '_')}",
                expectedOutcome = UiExpectedOutcome.TargetPropertyMatched(
                    targetLabel = label,
                    expectedChecked = false,
                    requiredPackages = requiredPackages
                ),
                isTerminal = true
            )
            return withExplicitAssertion(checkpoints, explicitAssertion)
        }
        if (normalized.startsWith("focus ") || normalized in setOf("tap message", "tap composer", "focus message", "focus composer")) {
            val rawLabel = FOCUS_PATTERN.matchEntire(normalized)?.groupValues?.getOrNull(1)?.removePrefix("the ")?.trim()
            val label = rawLabel?.takeIf { it.isNotBlank() } ?: "message"
            checkpoints += UiGoalCheckpoint(
                id = "focus_${label.replace(' ', '_')}",
                expectedOutcome = UiExpectedOutcome.TargetPropertyMatched(
                    targetLabel = label,
                    expectedFocused = true,
                    requiredPackages = requiredPackages
                ),
                isTerminal = true
            )
            return withExplicitAssertion(checkpoints, explicitAssertion)
        }

        // 8. Explicit package name in goal when no AppCandidate matched
        Regex("\\b([a-z][a-z0-9_]*(?:\\.[a-z0-9_]+){2,})\\b", RegexOption.IGNORE_CASE)
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { goalAppCandidates.isEmpty() && normalized.startsWith("open ") || normalized.startsWith("launch ") }
            ?.let { explicitPkg ->
                checkpoints += UiGoalCheckpoint(
                    id = "app_foreground_$explicitPkg",
                    expectedOutcome = UiExpectedOutcome.AppForegrounded(packageName = explicitPkg),
                    isTerminal = true
                )
                return withExplicitAssertion(checkpoints, explicitAssertion)
            }

        // 9. Destination / screen navigation goals (e.g. "open Saved Messages", "navigate to Security", "tap Profile")
        extractNavigationDestination(normalized, goalAppCandidates)?.let { dest ->
            checkpoints += UiGoalCheckpoint(
                id = "destination_${dest.replace(Regex("[^a-z0-9]+"), "_")}",
                expectedOutcome = UiExpectedOutcome.DestinationVisible(
                    destinationName = dest,
                    requiredPackages = requiredPackages
                ),
                isTerminal = true
            )
            return withExplicitAssertion(checkpoints, explicitAssertion)
        }

        // 10. Fallback: freeform goal requiring explicit keyword + package evidence
        val keywords = extractSignificantKeywords(normalized, goalAppCandidates)
        checkpoints += UiGoalCheckpoint(
            id = "goal_evidence",
            expectedOutcome = UiExpectedOutcome.UnverifiedFreeformGoal(
                rawGoal = trimmed,
                requiredKeywords = keywords,
                requiredPackages = requiredPackages
            ),
            isTerminal = true
        )
        return withExplicitAssertion(checkpoints, explicitAssertion)
    }

    private fun withExplicitAssertion(
        checkpoints: List<UiGoalCheckpoint>,
        assertion: UiAssertion?
    ): List<UiGoalCheckpoint> {
        if (assertion == null) return checkpoints
        return checkpoints + UiGoalCheckpoint(
            id = "explicit_assertion",
            expectedOutcome = UiExpectedOutcome.ExplicitAssertionMatched(assertion),
            isTerminal = true
        )
    }

    internal fun verify(
        goal: String,
        action: UiActionStep?,
        before: UiScreenState?,
        after: UiScreenState?,
        transportStatus: UiTransportStatus = UiTransportStatus.ACCEPTED,
        postcondition: PostconditionResult? = null,
        goalAppCandidates: List<DeviceAction.AppCandidate> = emptyList(),
        explicitAssertion: UiAssertion? = null,
        actionHistory: List<UiActionHistoryEntry> = emptyList(),
        confirmationGranted: Boolean = false,
        expectedDestination: String? = null,
        hasPendingUnconfirmedEffect: Boolean = false
    ): GoalVerificationResult {
        // 1. Check transport status first; never collapse transport failure with postconditions
        if (!transportStatus.isAccepted) {
            return GoalVerificationResult(
                status = UiVerificationStatus.FAILED,
                transportStatus = transportStatus,
                postconditionStatus = PostconditionStatus.FAILED,
                checkpointId = "transport",
                evidenceCategory = "transport_${transportStatus.name.lowercase()}",
                summary = "Action execution was not accepted by the Android control plane (${transportStatus.name.lowercase()}).",
                missingOrContradictingEvidence = listOf("transport_status:${transportStatus.name.lowercase()}")
            )
        }

        // 2. Fresh post-action state is mandatory
        if (after == null) {
            return GoalVerificationResult(
                status = UiVerificationStatus.UNVERIFIABLE,
                transportStatus = transportStatus,
                postconditionStatus = PostconditionStatus.UNVERIFIABLE,
                checkpointId = "post_observation",
                evidenceCategory = "observation_unavailable",
                summary = "Action was accepted by Android (executed), but the resulting screen could not be observed to verify the outcome.",
                missingOrContradictingEvidence = listOf("after_observation:missing")
            )
        }

        val effectivePostcondition = postcondition ?: if (action != null && before != null) {
            UiActionPostconditionVerifier.verify(action, before, after)
        } else {
            PostconditionResult(
                status = PostconditionStatus.NOT_APPLICABLE,
                detail = "No pre-action baseline screen available.",
                verificationStatus = UiVerificationStatus.UNVERIFIABLE,
                evidenceCategory = "missing_before_state"
            )
        }

        // 3. Opaque clipboard or password redaction cannot be reported as goal verified
        if (effectivePostcondition.evidenceCategory == "password_redacted") {
            return GoalVerificationResult(
                status = UiVerificationStatus.UNVERIFIABLE,
                transportStatus = transportStatus,
                postconditionStatus = effectivePostcondition.status,
                checkpointId = "password_redacted",
                evidenceCategory = "password_redacted",
                summary = "Password field contents are redacted; outcome cannot be verified.",
                missingOrContradictingEvidence = listOf("password_field_redacted")
            )
        }
        if (effectivePostcondition.status == PostconditionStatus.EXECUTED) {
            return GoalVerificationResult(
                status = UiVerificationStatus.EXECUTED,
                transportStatus = transportStatus,
                postconditionStatus = PostconditionStatus.EXECUTED,
                checkpointId = "opaque_execution",
                evidenceCategory = effectivePostcondition.evidenceCategory,
                summary = effectivePostcondition.detail,
                missingOrContradictingEvidence = listOf("observable_outcome_unavailable")
            )
        }

        // 4. Consequential / irreversible action check (Requirement 9)
        if (action != null && isConsequentialStep(action, before, goal)) {
            return verifyConsequentialStep(
                goal = goal,
                action = action,
                before = before,
                after = after,
                transportStatus = transportStatus,
                effectivePostcondition = effectivePostcondition,
                confirmationGranted = confirmationGranted,
                expectedDestination = expectedDestination ?: extractExpectedDestination(goal, goalAppCandidates)
            )
        }

        // 5. Action-level postcondition failure
        if (effectivePostcondition.status == PostconditionStatus.FAILED) {
            val stateChangedAnyway = before != null &&
                (before.screenId != after.screenId || !before.packageName.equals(after.packageName, ignoreCase = true))
            val status = if (stateChangedAnyway && effectivePostcondition.evidenceCategory == "system_surface_unverified") {
                UiVerificationStatus.STATE_CHANGED
            } else {
                UiVerificationStatus.FAILED
            }
            return GoalVerificationResult(
                status = status,
                transportStatus = transportStatus,
                postconditionStatus = effectivePostcondition.status,
                checkpointId = "action_postcondition",
                evidenceCategory = effectivePostcondition.evidenceCategory,
                summary = effectivePostcondition.detail,
                missingOrContradictingEvidence = listOf(effectivePostcondition.detail)
            )
        }

        // 6. Evaluate explicit planner assertion if present
        if (explicitAssertion != null && !evaluateAssertion(explicitAssertion, after)) {
            return GoalVerificationResult(
                status = UiVerificationStatus.FAILED,
                transportStatus = transportStatus,
                postconditionStatus = effectivePostcondition.status,
                checkpointId = "explicit_assertion",
                evidenceCategory = "assertion_failed",
                summary = "Planner postcondition assertion did not match the observed screen.",
                missingOrContradictingEvidence = listOf("assertion_mismatch")
            )
        }

        // 7. Compile goal checkpoints and verify against observed evidence
        val checkpoints = compileCheckpoints(goal, goalAppCandidates, explicitAssertion)
        val terminalCheckpoint = checkpoints.lastOrNull { it.isTerminal } ?: checkpoints.lastOrNull()
            ?: return fallbackStatusFromPostcondition(before, after, transportStatus, effectivePostcondition)

        // Check intermediate app-scope checkpoints (e.g., multi-app goals)
        val unmetAppCandidates = AutomationAppScope.remainingCandidates(goal, goalAppCandidates, actionHistory)
            .filterNot { candidate -> after.packageName.equals(candidate.packageName, ignoreCase = true) }

        val outcomeResult = evaluateExpectedOutcome(
            checkpoint = terminalCheckpoint,
            action = action,
            before = before,
            after = after,
            transportStatus = transportStatus,
            effectivePostcondition = effectivePostcondition,
            confirmationGranted = confirmationGranted,
            hasPendingUnconfirmedEffect = hasPendingUnconfirmedEffect
        )

        if (outcomeResult.status == UiVerificationStatus.GOAL_VERIFIED && unmetAppCandidates.isNotEmpty()) {
            return outcomeResult.copy(
                status = UiVerificationStatus.STATE_CHANGED,
                checkpointId = "intermediate_app_checkpoint",
                evidenceCategory = "intermediate_checkpoint_only",
                summary = "Current step changed state in ${after.packageName}, but remaining goal apps (${unmetAppCandidates.joinToString { it.label }}) are not yet verified.",
                missingOrContradictingEvidence = unmetAppCandidates.map { "remaining_app:${it.packageName}" }
            )
        }

        return outcomeResult
    }

    internal fun verifyTerminalDone(
        goal: String,
        currentScreen: UiScreenState?,
        actionHistory: List<UiActionHistoryEntry> = emptyList(),
        goalAppCandidates: List<DeviceAction.AppCandidate> = emptyList(),
        confirmationGranted: Boolean = false,
        expectedDestination: String? = null,
        hasPendingUnconfirmedEffect: Boolean = false
    ): GoalVerificationResult {
        if (currentScreen == null) {
            return GoalVerificationResult(
                status = UiVerificationStatus.UNVERIFIABLE,
                transportStatus = UiTransportStatus.NOT_DISPATCHED,
                postconditionStatus = PostconditionStatus.UNVERIFIABLE,
                checkpointId = "terminal_done",
                evidenceCategory = "observation_unavailable",
                summary = "Model claimed completion, but no current screen observation is available.",
                missingOrContradictingEvidence = listOf("current_screen:missing")
            )
        }
        if (hasPendingUnconfirmedEffect) {
            return GoalVerificationResult(
                status = UiVerificationStatus.STATE_CHANGED,
                transportStatus = UiTransportStatus.ACCEPTED,
                postconditionStatus = PostconditionStatus.STATE_CHANGED,
                checkpointId = "pending_external_effect",
                evidenceCategory = "unconfirmed_external_effect",
                summary = "External share/send handoff was initiated, but final commit confirmation evidence has not been observed.",
                missingOrContradictingEvidence = listOf("pending_external_effect")
            )
        }

        val checkpoints = compileCheckpoints(goal, goalAppCandidates)
        val unmetAppCandidates = AutomationAppScope.remainingCandidates(goal, goalAppCandidates, actionHistory)
            .filterNot { candidate -> currentScreen.packageName.equals(candidate.packageName, ignoreCase = true) }
        if (unmetAppCandidates.isNotEmpty()) {
            return GoalVerificationResult(
                status = UiVerificationStatus.STATE_CHANGED,
                transportStatus = UiTransportStatus.NOT_DISPATCHED,
                postconditionStatus = PostconditionStatus.STATE_CHANGED,
                checkpointId = "multi_app_incomplete",
                evidenceCategory = "incomplete_app_sequence",
                summary = "Completion rejected: required app(s) ${unmetAppCandidates.joinToString { it.label }} have not been visited.",
                missingOrContradictingEvidence = unmetAppCandidates.map { "missing_app:${it.packageName}" }
            )
        }

        val terminalCheckpoint = checkpoints.lastOrNull { it.isTerminal } ?: checkpoints.lastOrNull()
            ?: return GoalVerificationResult(
                status = UiVerificationStatus.UNVERIFIABLE,
                transportStatus = UiTransportStatus.NOT_DISPATCHED,
                postconditionStatus = PostconditionStatus.UNVERIFIABLE,
                checkpointId = "uncompiled_goal",
                evidenceCategory = "model_assertion_rejected",
                summary = "Completion rejected: model self-assertion cannot be used as independent proof without verifiable goal criteria.",
                missingOrContradictingEvidence = listOf("no_verifiable_checkpoint")
            )

        val anyScreenChangedInSession = actionHistory.any { it.changedScreen }
        val syntheticPostcondition = PostconditionResult(
            status = if (anyScreenChangedInSession) PostconditionStatus.STATE_CHANGED else PostconditionStatus.NOT_APPLICABLE,
            detail = "Evaluating terminal done claim against observed screen state."
        )

        return evaluateExpectedOutcome(
            checkpoint = terminalCheckpoint,
            action = UiActionStep.Done("model_claim"),
            before = null,
            after = currentScreen,
            transportStatus = UiTransportStatus.ACCEPTED,
            effectivePostcondition = syntheticPostcondition,
            confirmationGranted = confirmationGranted,
            hasPendingUnconfirmedEffect = hasPendingUnconfirmedEffect,
            expectedDestinationOverride = expectedDestination,
            actionHistory = actionHistory
        )
    }

    internal fun verifySettingsSurface(
        screen: UiScreenState,
        page: SettingsPage?,
        targetPackage: String? = null
    ): GoalVerificationResult {
        val pkg = screen.packageName.orEmpty().lowercase()
        val isSettingsPkg = pkg.contains("settings") ||
            pkg.contains("permissioncontroller") ||
            pkg.contains("packageinstaller") ||
            pkg.contains("wifi") ||
            pkg.contains("bluetooth")
        if (!isSettingsPkg) {
            return GoalVerificationResult(
                status = UiVerificationStatus.FAILED,
                transportStatus = UiTransportStatus.ACCEPTED,
                postconditionStatus = PostconditionStatus.FAILED,
                checkpointId = "settings_${page?.name?.lowercase() ?: "main"}",
                evidenceCategory = "wrong_package",
                summary = "Expected Android Settings surface, but observed package '${screen.packageName}'.",
                missingOrContradictingEvidence = listOf("observed_package:${screen.packageName}")
            )
        }

        val keywords = settingsKeywordsFor(page, targetPackage)
        if (keywords.isEmpty()) {
            return GoalVerificationResult(
                status = UiVerificationStatus.GOAL_VERIFIED,
                transportStatus = UiTransportStatus.ACCEPTED,
                postconditionStatus = PostconditionStatus.VERIFIED,
                checkpointId = "settings_root",
                evidenceCategory = "settings_surface",
                summary = "Verified Android Settings surface in '${screen.packageName}'.",
                matchedEvidence = listOf("package:${screen.packageName}")
            )
        }

        val screenText = collectVisibleTextAndIds(screen)
        val matched = keywords.filter { kw -> screenText.contains(kw) }
        return if (matched.isNotEmpty()) {
            GoalVerificationResult(
                status = UiVerificationStatus.GOAL_VERIFIED,
                transportStatus = UiTransportStatus.ACCEPTED,
                postconditionStatus = PostconditionStatus.VERIFIED,
                checkpointId = "settings_${page?.name?.lowercase() ?: "page"}",
                evidenceCategory = "settings_surface",
                summary = "Verified Settings surface (${matched.first()}) in '${screen.packageName}'.",
                matchedEvidence = matched.map { "keyword:$it" }
            )
        } else {
            GoalVerificationResult(
                status = UiVerificationStatus.STATE_CHANGED,
                transportStatus = UiTransportStatus.ACCEPTED,
                postconditionStatus = PostconditionStatus.STATE_CHANGED,
                checkpointId = "settings_${page?.name?.lowercase() ?: "page"}",
                evidenceCategory = "settings_page_unverified",
                summary = "Settings package '${screen.packageName}' is foreground, but expected ${page?.name?.lowercase()} controls (${keywords.joinToString("/")}) were not observed.",
                missingOrContradictingEvidence = keywords.map { "missing_keyword:$it" }
            )
        }
    }

    private fun evaluateExpectedOutcome(
        checkpoint: UiGoalCheckpoint,
        action: UiActionStep?,
        before: UiScreenState?,
        after: UiScreenState,
        transportStatus: UiTransportStatus,
        effectivePostcondition: PostconditionResult,
        confirmationGranted: Boolean,
        hasPendingUnconfirmedEffect: Boolean,
        expectedDestinationOverride: String? = null,
        actionHistory: List<UiActionHistoryEntry> = emptyList()
    ): GoalVerificationResult {
        val stateChanged = before != null &&
            (before.screenId != after.screenId || !before.packageName.equals(after.packageName, ignoreCase = true)) ||
            effectivePostcondition.status == PostconditionStatus.STATE_CHANGED ||
            actionHistory.any { it.changedScreen }

        return when (val outcome = checkpoint.expectedOutcome) {
            is UiExpectedOutcome.AppForegrounded -> {
                if (after.packageName.equals(outcome.packageName, ignoreCase = true)) {
                    GoalVerificationResult(
                        status = UiVerificationStatus.GOAL_VERIFIED,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.GOAL_VERIFIED,
                        checkpointId = checkpoint.id,
                        evidenceCategory = "package_foreground",
                        summary = "Opened ${outcome.appLabel ?: outcome.packageName} and verified '${after.packageName}' in the foreground.",
                        matchedEvidence = listOf("package:${after.packageName}")
                    )
                } else {
                    GoalVerificationResult(
                        status = UiVerificationStatus.FAILED,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.FAILED,
                        checkpointId = checkpoint.id,
                        evidenceCategory = "wrong_destination_package",
                        summary = "Expected ${outcome.packageName} in the foreground, but observed ${after.packageName}.",
                        missingOrContradictingEvidence = listOf(
                            "expected_package:${outcome.packageName}",
                            "observed_package:${after.packageName}"
                        )
                    )
                }
            }

            is UiExpectedOutcome.SystemSurfaceVisible -> {
                val eval = UiSystemSurfaceDetector.evaluate(after, outcome.surface, before)
                if (eval.matched) {
                    GoalVerificationResult(
                        status = UiVerificationStatus.GOAL_VERIFIED,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.GOAL_VERIFIED,
                        checkpointId = checkpoint.id,
                        evidenceCategory = "system_surface_${outcome.surface.name.lowercase()}",
                        summary = eval.detail,
                        matchedEvidence = eval.matchedEvidence
                    )
                } else {
                    val status = if (eval.contradictedByOtherSurface != null || !stateChanged) {
                        UiVerificationStatus.FAILED
                    } else {
                        UiVerificationStatus.STATE_CHANGED
                    }
                    GoalVerificationResult(
                        status = status,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.FAILED,
                        checkpointId = checkpoint.id,
                        evidenceCategory = if (eval.contradictedByOtherSurface != null) {
                            "wrong_system_surface"
                        } else {
                            "system_surface_unverified"
                        },
                        summary = eval.detail,
                        missingOrContradictingEvidence = listOf(eval.detail)
                    )
                }
            }

            is UiExpectedOutcome.SettingsSurfaceVisible ->
                verifySettingsSurface(after, outcome.page, outcome.targetPackage)

            is UiExpectedOutcome.SearchResultsVisible -> {
                if (outcome.requiredPackages.isNotEmpty() &&
                    outcome.requiredPackages.none { after.packageName.equals(it, ignoreCase = true) }
                ) {
                    return GoalVerificationResult(
                        status = if (stateChanged) UiVerificationStatus.STATE_CHANGED else UiVerificationStatus.FAILED,
                        transportStatus = transportStatus,
                        postconditionStatus = effectivePostcondition.status,
                        checkpointId = checkpoint.id,
                        evidenceCategory = "wrong_package",
                        summary = "Search results require package ${outcome.requiredPackages}, but observed '${after.packageName}'.",
                        missingOrContradictingEvidence = listOf("observed_package:${after.packageName}")
                    )
                }
                val completion = UiGoalCompletionEvaluator.evaluateCondition(
                    UiSuccessCondition.SearchResultsVisible(
                        query = outcome.query,
                        requiredPackages = outcome.requiredPackages
                    ),
                    after
                )
                if (completion != null) {
                    GoalVerificationResult(
                        status = UiVerificationStatus.GOAL_VERIFIED,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.GOAL_VERIFIED,
                        checkpointId = checkpoint.id,
                        evidenceCategory = "search_results",
                        summary = completion.summary,
                        matchedEvidence = listOf("query:${outcome.query}", "package:${after.packageName}")
                    )
                } else {
                    GoalVerificationResult(
                        status = if (stateChanged) UiVerificationStatus.STATE_CHANGED else UiVerificationStatus.EXECUTED,
                        transportStatus = transportStatus,
                        postconditionStatus = effectivePostcondition.status,
                        checkpointId = checkpoint.id,
                        evidenceCategory = "search_results_pending",
                        summary = "Search results for '${outcome.query}' are not yet visible outside the active search input.",
                        missingOrContradictingEvidence = listOf("search_results_missing:${outcome.query}")
                    )
                }
            }

            is UiExpectedOutcome.TypedTextPresent -> {
                if (outcome.requiredPackages.isNotEmpty() &&
                    outcome.requiredPackages.none { after.packageName.equals(it, ignoreCase = true) }
                ) {
                    return GoalVerificationResult(
                        status = UiVerificationStatus.FAILED,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.FAILED,
                        checkpointId = checkpoint.id,
                        evidenceCategory = "wrong_package",
                        summary = "Expected package ${outcome.requiredPackages} for text entry, but observed '${after.packageName}'."
                    )
                }
                val normalizedExpected = UiActionPostconditionVerifier.normalize(outcome.expectedText)
                val matchingEditable = after.elements.firstOrNull { el ->
                    el.visible && !el.password && (el.editable || "set_text" in el.standardActions) &&
                        UiActionPostconditionVerifier.normalize(el.text.orEmpty()) == normalizedExpected &&
                        (outcome.targetLabel == null || elementMatchesLabel(el, outcome.targetLabel))
                }
                if (matchingEditable != null) {
                    GoalVerificationResult(
                        status = UiVerificationStatus.GOAL_VERIFIED,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.GOAL_VERIFIED,
                        checkpointId = checkpoint.id,
                        evidenceCategory = "typed_text",
                        summary = "The editable control contains the requested text.",
                        matchedEvidence = listOf("element:${matchingEditable.id}")
                    )
                } else {
                    GoalVerificationResult(
                        status = UiVerificationStatus.FAILED,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.FAILED,
                        checkpointId = checkpoint.id,
                        evidenceCategory = "typed_text_mismatch",
                        summary = "The editable control text did not match the requested text.",
                        missingOrContradictingEvidence = listOf("expected_text_not_observed")
                    )
                }
            }

            is UiExpectedOutcome.TargetPropertyMatched -> {
                val candidate = findElementForOutcome(after, outcome.elementId, outcome.targetLabel)
                if (candidate == null) {
                    return GoalVerificationResult(
                        status = if (stateChanged) UiVerificationStatus.STATE_CHANGED else UiVerificationStatus.FAILED,
                        transportStatus = transportStatus,
                        postconditionStatus = effectivePostcondition.status,
                        checkpointId = checkpoint.id,
                        evidenceCategory = "target_missing",
                        summary = "Target control '${outcome.targetLabel ?: outcome.elementId ?: "unknown"}' is not visible on the resulting screen.",
                        missingOrContradictingEvidence = listOf("target_not_visible")
                    )
                }
                val checkedOk = outcome.expectedChecked?.let { candidate.checked == it } ?: true
                val focusedOk = outcome.expectedFocused?.let { candidate.focused == it } ?: true
                val selectedOk = outcome.expectedSelected?.let { candidate.selected == it } ?: true
                val a11yFocusedOk = outcome.expectedAccessibilityFocused?.let { candidate.accessibilityFocused == it } ?: true
                val selectionOk = outcome.expectedSelectionRange?.let {
                    candidate.selectionStart == it.first && candidate.selectionEnd == it.second
                } ?: true

                if (checkedOk && focusedOk && selectedOk && a11yFocusedOk && selectionOk) {
                    GoalVerificationResult(
                        status = UiVerificationStatus.GOAL_VERIFIED,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.GOAL_VERIFIED,
                        checkpointId = checkpoint.id,
                        evidenceCategory = "target_property",
                        summary = "Verified target property on '${outcome.targetLabel ?: candidate.id}'.",
                        matchedEvidence = listOf("element:${candidate.id}")
                    )
                } else {
                    GoalVerificationResult(
                        status = UiVerificationStatus.FAILED,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.FAILED,
                        checkpointId = checkpoint.id,
                        evidenceCategory = "target_property_mismatch",
                        summary = "Target control '${outcome.targetLabel ?: candidate.id}' did not reach the required state.",
                        missingOrContradictingEvidence = listOf("property_mismatch:${candidate.id}")
                    )
                }
            }

            is UiExpectedOutcome.RangeValueMatched -> {
                val candidate = findElementForOutcome(after, outcome.elementId, outcome.targetLabel)
                    ?: after.elements.singleOrNull { it.visible && it.range != null }
                val range = candidate?.range
                if (range == null) {
                    return GoalVerificationResult(
                        status = UiVerificationStatus.FAILED,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.FAILED,
                        checkpointId = checkpoint.id,
                        evidenceCategory = "range_unavailable",
                        summary = "Range control '${outcome.targetLabel ?: "target"}' RangeInfo is unavailable after execution.",
                        missingOrContradictingEvidence = listOf("range_info_missing")
                    )
                }
                val targetVal = if (outcome.isPercent) {
                    range.min + ((range.max - range.min) * outcome.expectedValue / 100f)
                } else {
                    outcome.expectedValue
                }
                val tolerance = (kotlin.math.abs(range.max - range.min) * 0.01f).coerceAtLeast(0.001f)
                if (kotlin.math.abs(range.current - targetVal) <= tolerance) {
                    GoalVerificationResult(
                        status = UiVerificationStatus.GOAL_VERIFIED,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.GOAL_VERIFIED,
                        checkpointId = checkpoint.id,
                        evidenceCategory = "range_value",
                        summary = "The range control reached the requested value (${range.current}).",
                        matchedEvidence = listOf("range_current:${range.current}")
                    )
                } else {
                    GoalVerificationResult(
                        status = UiVerificationStatus.FAILED,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.FAILED,
                        checkpointId = checkpoint.id,
                        evidenceCategory = "range_mismatch",
                        summary = "The range control is at ${range.current} instead of the requested $targetVal.",
                        missingOrContradictingEvidence = listOf("observed_range:${range.current}", "expected_range:$targetVal")
                    )
                }
            }

            is UiExpectedOutcome.BackNavigationCompleted -> {
                if (stateChanged) {
                    GoalVerificationResult(
                        status = UiVerificationStatus.GOAL_VERIFIED,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.GOAL_VERIFIED,
                        checkpointId = checkpoint.id,
                        evidenceCategory = "back_navigation",
                        summary = "Navigated back to '${after.packageName}' (${after.screenId}).",
                        matchedEvidence = listOf("screen:${after.screenId}")
                    )
                } else {
                    GoalVerificationResult(
                        status = UiVerificationStatus.FAILED,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.FAILED,
                        checkpointId = checkpoint.id,
                        evidenceCategory = "no_mutation",
                        summary = "Back navigation did not change the active screen.",
                        missingOrContradictingEvidence = listOf("screen_unchanged:${after.screenId}")
                    )
                }
            }

            is UiExpectedOutcome.ScrollStateChanged -> {
                if (stateChanged) {
                    GoalVerificationResult(
                        status = UiVerificationStatus.GOAL_VERIFIED,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.GOAL_VERIFIED,
                        checkpointId = checkpoint.id,
                        evidenceCategory = "scroll_mutation",
                        summary = "Scrolled ${outcome.direction?.name?.lowercase() ?: "screen"} and verified updated viewport.",
                        matchedEvidence = listOf("screen:${after.screenId}")
                    )
                } else {
                    GoalVerificationResult(
                        status = UiVerificationStatus.FAILED,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.FAILED,
                        checkpointId = checkpoint.id,
                        evidenceCategory = "scroll_unchanged",
                        summary = "Visible scroll state did not observably change.",
                        missingOrContradictingEvidence = listOf("screen_unchanged:${after.screenId}")
                    )
                }
            }

            is UiExpectedOutcome.DestinationVisible -> {
                verifyDestinationOutcome(
                    checkpointId = checkpoint.id,
                    destinationName = expectedDestinationOverride ?: outcome.destinationName,
                    requiredPackages = outcome.requiredPackages,
                    action = action,
                    before = before,
                    after = after,
                    transportStatus = transportStatus,
                    effectivePostcondition = effectivePostcondition,
                    stateChanged = stateChanged
                )
            }

            is UiExpectedOutcome.ConsequentialActionConfirmed -> {
                verifyConsequentialStep(
                    goal = outcome.description,
                    action = action,
                    before = before,
                    after = after,
                    transportStatus = transportStatus,
                    effectivePostcondition = effectivePostcondition,
                    confirmationGranted = confirmationGranted,
                    expectedDestination = expectedDestinationOverride ?: outcome.expectedDestination,
                    expectedContent = outcome.expectedContent,
                    hasPendingUnconfirmedEffect = hasPendingUnconfirmedEffect
                )
            }

            is UiExpectedOutcome.ExplicitAssertionMatched -> {
                if (evaluateAssertion(outcome.assertion, after)) {
                    GoalVerificationResult(
                        status = UiVerificationStatus.GOAL_VERIFIED,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.GOAL_VERIFIED,
                        checkpointId = checkpoint.id,
                        evidenceCategory = "explicit_assertion",
                        summary = "Verified explicit postcondition assertion.",
                        matchedEvidence = listOf("assertion_matched")
                    )
                } else {
                    GoalVerificationResult(
                        status = UiVerificationStatus.FAILED,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.FAILED,
                        checkpointId = checkpoint.id,
                        evidenceCategory = "assertion_failed",
                        summary = "Explicit postcondition assertion failed.",
                        missingOrContradictingEvidence = listOf("assertion_mismatch")
                    )
                }
            }

            is UiExpectedOutcome.UnverifiedFreeformGoal -> {
                verifyFreeformGoal(
                    checkpointId = checkpoint.id,
                    outcome = outcome,
                    action = action,
                    before = before,
                    after = after,
                    transportStatus = transportStatus,
                    effectivePostcondition = effectivePostcondition,
                    stateChanged = stateChanged
                )
            }
        }
    }

    private fun verifyDestinationOutcome(
        checkpointId: String,
        destinationName: String,
        requiredPackages: Set<String>,
        action: UiActionStep?,
        before: UiScreenState?,
        after: UiScreenState,
        transportStatus: UiTransportStatus,
        effectivePostcondition: PostconditionResult,
        stateChanged: Boolean
    ): GoalVerificationResult {
        if (requiredPackages.isNotEmpty() &&
            requiredPackages.none { after.packageName.equals(it, ignoreCase = true) }
        ) {
            return GoalVerificationResult(
                status = if (stateChanged) UiVerificationStatus.FAILED else UiVerificationStatus.STATE_CHANGED,
                transportStatus = transportStatus,
                postconditionStatus = effectivePostcondition.status,
                checkpointId = checkpointId,
                evidenceCategory = "wrong_destination_package",
                summary = "Expected package ${requiredPackages.joinToString("/")} for destination '$destinationName', but observed '${after.packageName}'.",
                missingOrContradictingEvidence = listOf("observed_package:${after.packageName}")
            )
        }

        // Check if target was an in-place focusable/checkable control on the same screen
        if (before != null && !stateChanged && action != null) {
            val targetId = when (action) {
                is UiActionStep.Tap -> action.target.elementId
                is UiActionStep.NodeAction -> action.target.elementId
                else -> null
            }
            val beforeEl = targetId?.let(before::element)
            val afterEl = beforeEl?.let { UiActionPostconditionVerifier.findSameControl(it, after) }
            if (beforeEl != null && afterEl != null) {
                if ((beforeEl.checkable && beforeEl.checked != afterEl.checked) ||
                    (!beforeEl.focused && afterEl.focused) ||
                    (!beforeEl.selected && afterEl.selected)
                ) {
                    return GoalVerificationResult(
                        status = UiVerificationStatus.GOAL_VERIFIED,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.GOAL_VERIFIED,
                        checkpointId = checkpointId,
                        evidenceCategory = "control_activated",
                        summary = "Activated '$destinationName' and verified its state change on screen.",
                        matchedEvidence = listOf("element:${afterEl.id}")
                    )
                }
            }
        }

        // Check DestinationResolver first when header elements exist
        val resolvedDestination = DestinationResolver.resolve(after, destinationName)
        if (resolvedDestination is DestinationResolver.DestinationResult.Verified && stateChanged) {
            return GoalVerificationResult(
                status = UiVerificationStatus.GOAL_VERIFIED,
                transportStatus = transportStatus,
                postconditionStatus = PostconditionStatus.GOAL_VERIFIED,
                checkpointId = checkpointId,
                evidenceCategory = "destination_header_verified",
                summary = "Navigated to '$destinationName' and verified destination header '${resolvedDestination.observed}'.",
                matchedEvidence = listOf("destination:${resolvedDestination.observed}")
            )
        }
        if (resolvedDestination is DestinationResolver.DestinationResult.Mismatch) {
            return GoalVerificationResult(
                status = UiVerificationStatus.FAILED,
                transportStatus = transportStatus,
                postconditionStatus = PostconditionStatus.FAILED,
                checkpointId = checkpointId,
                evidenceCategory = "wrong_destination",
                summary = "Wrong destination: expected '$destinationName', but observed '${resolvedDestination.observed}'.",
                missingOrContradictingEvidence = listOf(
                    "expected_destination:$destinationName",
                    "observed_destination:${resolvedDestination.observed}"
                )
            )
        }

        // Otherwise check if the resulting screen after transition displays destination evidence
        val normalizedDest = normalizeText(destinationName)
        val hasDestinationEvidenceOnAfter = after.elements.any { el ->
            el.visible && !el.password &&
                listOfNotNull(el.text, el.contentDescription, el.hintText).any { text ->
                    normalizeText(text).contains(normalizedDest)
                }
        } || normalizeText(after.activityName.orEmpty()).contains(normalizedDest)

        if (stateChanged && hasDestinationEvidenceOnAfter) {
            return GoalVerificationResult(
                status = UiVerificationStatus.GOAL_VERIFIED,
                transportStatus = transportStatus,
                postconditionStatus = PostconditionStatus.GOAL_VERIFIED,
                checkpointId = checkpointId,
                evidenceCategory = "destination_evidence_verified",
                summary = "Navigated to '$destinationName' and verified destination evidence on screen.",
                matchedEvidence = listOf("destination:$destinationName")
            )
        }

        return if (stateChanged) {
            GoalVerificationResult(
                status = UiVerificationStatus.STATE_CHANGED,
                transportStatus = transportStatus,
                postconditionStatus = PostconditionStatus.STATE_CHANGED,
                checkpointId = checkpointId,
                evidenceCategory = "destination_unverified",
                summary = "Screen transitioned to '${after.packageName}' (${after.screenId}), but expected destination '$destinationName' was not observed.",
                missingOrContradictingEvidence = listOf("missing_destination:$destinationName")
            )
        } else {
            GoalVerificationResult(
                status = UiVerificationStatus.FAILED,
                transportStatus = transportStatus,
                postconditionStatus = PostconditionStatus.FAILED,
                checkpointId = checkpointId,
                evidenceCategory = "no_navigation",
                summary = "Action did not navigate to '$destinationName'.",
                missingOrContradictingEvidence = listOf("screen_unchanged:${after.screenId}")
            )
        }
    }

    private fun verifyConsequentialStep(
        goal: String,
        action: UiActionStep?,
        before: UiScreenState?,
        after: UiScreenState,
        transportStatus: UiTransportStatus,
        effectivePostcondition: PostconditionResult,
        confirmationGranted: Boolean,
        expectedDestination: String?,
        expectedContent: String? = null,
        hasPendingUnconfirmedEffect: Boolean = false
    ): GoalVerificationResult {
        if (!confirmationGranted) {
            return GoalVerificationResult(
                status = UiVerificationStatus.FAILED,
                transportStatus = transportStatus,
                postconditionStatus = PostconditionStatus.FAILED,
                checkpointId = "consequential_confirmation",
                evidenceCategory = "missing_user_confirmation",
                summary = "Irreversible or externally consequential action requires explicit confirmation evidence before completion can be claimed.",
                missingOrContradictingEvidence = listOf("user_confirmation:false")
            )
        }

        if (hasPendingUnconfirmedEffect || action is UiActionStep.ShareText || action is UiActionStep.ShareCapturedMedia) {
            return GoalVerificationResult(
                status = UiVerificationStatus.STATE_CHANGED,
                transportStatus = transportStatus,
                postconditionStatus = PostconditionStatus.STATE_CHANGED,
                checkpointId = "consequential_handoff",
                evidenceCategory = "external_handoff_only",
                summary = "External share handoff opened target UI, but final commit confirmation evidence has not been observed.",
                missingOrContradictingEvidence = listOf("commit_confirmation_pending")
            )
        }

        if (expectedDestination != null) {
            when (val destResult = DestinationResolver.resolve(after, expectedDestination)) {
                is DestinationResolver.DestinationResult.Mismatch ->
                    return GoalVerificationResult(
                        status = UiVerificationStatus.FAILED,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.FAILED,
                        checkpointId = "consequential_destination",
                        evidenceCategory = "wrong_destination",
                        summary = "Consequential action destination mismatch: expected '$expectedDestination', observed '${destResult.observed}'.",
                        missingOrContradictingEvidence = listOf(
                            "expected_destination:$expectedDestination",
                            "observed_destination:${destResult.observed}"
                        )
                    )
                is DestinationResolver.DestinationResult.Unresolvable ->
                    return GoalVerificationResult(
                        status = UiVerificationStatus.UNVERIFIABLE,
                        transportStatus = transportStatus,
                        postconditionStatus = PostconditionStatus.UNVERIFIABLE,
                        checkpointId = "consequential_destination",
                        evidenceCategory = "destination_unresolvable",
                        summary = "Consequential action destination '$expectedDestination' could not be verified on the resulting screen.",
                        missingOrContradictingEvidence = listOf("unresolvable_destination:$expectedDestination")
                    )
                is DestinationResolver.DestinationResult.Verified -> Unit
            }
        }

        // Check message-send confirmation: composer cleared and/or sent message visible in transcript
        val isSendGoal = SEND_MESSAGE_PATTERN.containsMatchIn(goal) ||
            (action != null && before != null && isSendControlStep(action, before))
        if (isSendGoal) {
            val beforeComposerText = before?.elements
                ?.firstOrNull { it.visible && !it.password && (it.editable || "set_text" in it.standardActions) }
                ?.text
                ?.trim()
                .orEmpty()
            val afterComposer = after.elements
                .firstOrNull { it.visible && !it.password && (it.editable || "set_text" in it.standardActions) }
            val afterComposerText = afterComposer?.text?.trim().orEmpty()
            val messageToVerify = expectedContent?.takeIf { it.isNotBlank() }
                ?: beforeComposerText.takeIf { it.isNotBlank() }

            if (!messageToVerify.isNullOrBlank() &&
                afterComposerText.equals(messageToVerify, ignoreCase = true)
            ) {
                return GoalVerificationResult(
                    status = UiVerificationStatus.FAILED,
                    transportStatus = transportStatus,
                    postconditionStatus = PostconditionStatus.FAILED,
                    checkpointId = "consequential_send_verification",
                    evidenceCategory = "composer_not_cleared",
                    summary = "Send action was dispatched, but the message composer still contains the unsent text.",
                    missingOrContradictingEvidence = listOf("composer_still_contains_message")
                )
            }

            val composerCleared = beforeComposerText.isNotBlank() && afterComposerText.isBlank()
            val transcriptContainsMessage = !messageToVerify.isNullOrBlank() &&
                after.elements.any { el ->
                    el.visible && !el.editable && "set_text" !in el.standardActions &&
                        listOfNotNull(el.text, el.contentDescription).any {
                            UiActionPostconditionVerifier.normalize(it)
                                .contains(UiActionPostconditionVerifier.normalize(messageToVerify), ignoreCase = true)
                        }
                }

            if (composerCleared || transcriptContainsMessage) {
                return GoalVerificationResult(
                    status = UiVerificationStatus.GOAL_VERIFIED,
                    transportStatus = transportStatus,
                    postconditionStatus = PostconditionStatus.GOAL_VERIFIED,
                    checkpointId = "consequential_send_verification",
                    evidenceCategory = "consequential_send_confirmed",
                    summary = "Confirmed message commit with post-action UI evidence.",
                    matchedEvidence = buildList {
                        if (composerCleared) add("composer_cleared")
                        if (transcriptContainsMessage) add("transcript_message_visible")
                    }
                )
            }

            return GoalVerificationResult(
                status = UiVerificationStatus.UNVERIFIABLE,
                transportStatus = transportStatus,
                postconditionStatus = PostconditionStatus.UNVERIFIABLE,
                checkpointId = "consequential_send_verification",
                evidenceCategory = "consequential_evidence_missing",
                summary = "Consequential send action ran, but post-action confirmation evidence (cleared composer or visible sent item) is unavailable.",
                missingOrContradictingEvidence = listOf("send_confirmation_evidence_missing")
            )
        }

        // Other consequential actions (e.g. delete): require concrete state change + disappearance of target
        val stateChanged = before != null &&
            (before.screenId != after.screenId || !before.packageName.equals(after.packageName, ignoreCase = true))
        return if (stateChanged && after.elements.isNotEmpty()) {
            GoalVerificationResult(
                status = UiVerificationStatus.GOAL_VERIFIED,
                transportStatus = transportStatus,
                postconditionStatus = PostconditionStatus.GOAL_VERIFIED,
                checkpointId = "consequential_confirmed",
                evidenceCategory = "consequential_state_confirmed",
                summary = "Confirmed consequential action with user approval and post-action state transition.",
                matchedEvidence = listOf("confirmed_transition:${after.screenId}")
            )
        } else {
            GoalVerificationResult(
                status = UiVerificationStatus.UNVERIFIABLE,
                transportStatus = transportStatus,
                postconditionStatus = PostconditionStatus.UNVERIFIABLE,
                checkpointId = "consequential_confirmed",
                evidenceCategory = "consequential_evidence_missing",
                summary = "Consequential action executed, but post-action confirmation evidence is insufficient.",
                missingOrContradictingEvidence = listOf("post_action_confirmation_missing")
            )
        }
    }

    private fun verifyFreeformGoal(
        checkpointId: String,
        outcome: UiExpectedOutcome.UnverifiedFreeformGoal,
        action: UiActionStep?,
        before: UiScreenState?,
        after: UiScreenState,
        transportStatus: UiTransportStatus,
        effectivePostcondition: PostconditionResult,
        stateChanged: Boolean
    ): GoalVerificationResult {
        if (outcome.requiredPackages.isNotEmpty() &&
            outcome.requiredPackages.none { after.packageName.equals(it, ignoreCase = true) }
        ) {
            return GoalVerificationResult(
                status = if (stateChanged) UiVerificationStatus.STATE_CHANGED else UiVerificationStatus.FAILED,
                transportStatus = transportStatus,
                postconditionStatus = effectivePostcondition.status,
                checkpointId = checkpointId,
                evidenceCategory = "wrong_package",
                summary = "Expected package ${outcome.requiredPackages.joinToString("/")}, but observed '${after.packageName}'.",
                missingOrContradictingEvidence = listOf("observed_package:${after.packageName}")
            )
        }

        // If the step had a specific target postcondition (e.g. TypeText, Focus, SetProgress) and no extra keywords remain unmet
        if (effectivePostcondition.status == PostconditionStatus.VERIFIED && action !is UiActionStep.Done) {
            val visibleText = collectVisibleTextAndIds(after)
            val allKeywordsPresent = outcome.requiredKeywords.isEmpty() ||
                outcome.requiredKeywords.all { kw -> visibleText.contains(kw) }
            if (allKeywordsPresent) {
                return GoalVerificationResult(
                    status = UiVerificationStatus.GOAL_VERIFIED,
                    transportStatus = transportStatus,
                    postconditionStatus = PostconditionStatus.GOAL_VERIFIED,
                    checkpointId = checkpointId,
                    evidenceCategory = effectivePostcondition.evidenceCategory,
                    summary = effectivePostcondition.detail,
                    matchedEvidence = listOf("postcondition:${effectivePostcondition.evidenceCategory}")
                )
            }
        }

        // Never promote a generic screen mutation or bare model Done assertion without keyword/control evidence
        if (outcome.requiredKeywords.isEmpty() || after.elements.isEmpty()) {
            return GoalVerificationResult(
                status = if (stateChanged) UiVerificationStatus.STATE_CHANGED else UiVerificationStatus.UNVERIFIABLE,
                transportStatus = transportStatus,
                postconditionStatus = effectivePostcondition.status,
                checkpointId = checkpointId,
                evidenceCategory = "insufficient_goal_evidence",
                summary = "UI state changed or action executed, but insufficient machine-evaluable evidence is available to verify '${outcome.rawGoal}'.",
                missingOrContradictingEvidence = listOf("verifiable_evidence_unavailable")
            )
        }

        val visibleText = collectVisibleTextAndIds(after)
        val matchedKeywords = outcome.requiredKeywords.filter { kw -> visibleText.contains(kw) }
        val allMatched = matchedKeywords.size == outcome.requiredKeywords.size

        // Even if keywords appear on a menu item BEFORE tapping it, require that the screen actually transitioned
        // to the destination screen (and the keyword is not merely the un-tapped button on an unchanged screen)
        return if (stateChanged && allMatched && action is UiActionStep.Done) {
            GoalVerificationResult(
                status = UiVerificationStatus.GOAL_VERIFIED,
                transportStatus = transportStatus,
                postconditionStatus = PostconditionStatus.GOAL_VERIFIED,
                checkpointId = checkpointId,
                evidenceCategory = "corroborated_goal_keywords",
                summary = "Verified goal outcome on '${after.packageName}' with corroborated screen evidence (${matchedKeywords.joinToString(", ")}).",
                matchedEvidence = matchedKeywords.map { "keyword:$it" }
            )
        } else if (stateChanged) {
            GoalVerificationResult(
                status = UiVerificationStatus.STATE_CHANGED,
                transportStatus = transportStatus,
                postconditionStatus = PostconditionStatus.STATE_CHANGED,
                checkpointId = checkpointId,
                evidenceCategory = "state_changed_goal_unverified",
                summary = "UI state changed in '${after.packageName}', but the goal '${outcome.rawGoal}' is not yet verified.",
                missingOrContradictingEvidence = (outcome.requiredKeywords - matchedKeywords.toSet()).map { "missing:$it" }
            )
        } else {
            GoalVerificationResult(
                status = UiVerificationStatus.UNVERIFIABLE,
                transportStatus = transportStatus,
                postconditionStatus = PostconditionStatus.UNVERIFIABLE,
                checkpointId = checkpointId,
                evidenceCategory = "unverified_claim",
                summary = "Goal '${outcome.rawGoal}' could not be verified from the current screen evidence.",
                missingOrContradictingEvidence = (outcome.requiredKeywords - matchedKeywords.toSet()).map { "missing:$it" }
            )
        }
    }

    private fun fallbackStatusFromPostcondition(
        before: UiScreenState?,
        after: UiScreenState,
        transportStatus: UiTransportStatus,
        postcondition: PostconditionResult
    ): GoalVerificationResult {
        val changed = before != null &&
            (before.screenId != after.screenId || !before.packageName.equals(after.packageName, ignoreCase = true))
        val status = when {
            postcondition.status == PostconditionStatus.FAILED -> UiVerificationStatus.FAILED
            changed || postcondition.status == PostconditionStatus.STATE_CHANGED -> UiVerificationStatus.STATE_CHANGED
            postcondition.status == PostconditionStatus.EXECUTED -> UiVerificationStatus.EXECUTED
            else -> UiVerificationStatus.UNVERIFIABLE
        }
        return GoalVerificationResult(
            status = status,
            transportStatus = transportStatus,
            postconditionStatus = postcondition.status,
            checkpointId = "fallback",
            evidenceCategory = postcondition.evidenceCategory,
            summary = postcondition.detail
        )
    }

    internal fun evaluateAssertion(assertion: UiAssertion, screen: UiScreenState): Boolean {
        val element = assertion.elementId?.let(screen::element)
        if (assertion.elementId != null && element == null) return false
        assertion.checked?.let { expected -> if (element?.checked != expected) return false }
        assertion.textContains?.let { expected ->
            val matches = if (element != null) {
                listOfNotNull(element.text, element.contentDescription).any { it.contains(expected, ignoreCase = true) }
            } else {
                screen.elements.any { candidate ->
                    listOfNotNull(candidate.text, candidate.contentDescription).any { it.contains(expected, ignoreCase = true) }
                }
            }
            if (!matches) return false
        }
        return true
    }

    private fun isConsequentialStep(
        action: UiActionStep,
        before: UiScreenState?,
        goal: String
    ): Boolean {
        if (action is UiActionStep.ShareText || action is UiActionStep.ShareCapturedMedia) return true
        if (before != null && isSendControlStep(action, before)) return true
        val targetId = when (action) {
            is UiActionStep.Tap -> action.target.elementId
            is UiActionStep.LongPress -> action.target.elementId
            is UiActionStep.NodeAction -> action.target.elementId
            is UiActionStep.CustomAction -> action.target.elementId
            else -> null
        }
        val targetEl = if (before != null && targetId != null) before.element(targetId) else null
        val targetText = listOfNotNull(
            targetEl?.text,
            targetEl?.contentDescription,
            targetEl?.resourceId
        ).joinToString(" ").lowercase()
        val consequentialTargetTerms = listOf("send", "post", "publish", "delete", "remove", "call", "pay", "purchase", "checkout")
        return consequentialTargetTerms.any { term ->
            Regex("\\b${Regex.escape(term)}\\b").containsMatchIn(targetText) ||
                targetEl?.resourceId?.lowercase()?.endsWith(":id/$term") == true
        } && CONSEQUENTIAL_VERB_PATTERN.containsMatchIn("${goal.lowercase()} $targetText")
    }

    private fun isSendControlStep(action: UiActionStep, screen: UiScreenState): Boolean {
        val targetId = when (action) {
            is UiActionStep.Tap -> action.target.elementId
            is UiActionStep.NodeAction -> action.target.elementId
            is UiActionStep.CustomAction -> action.target.elementId
            else -> null
        } ?: return false
        val el = screen.element(targetId) ?: return false
        val combined = listOfNotNull(el.text, el.contentDescription, el.resourceId).joinToString(" ").lowercase()
        return combined.contains("send")
    }

    private fun hasSubGoalBeyondAppLaunch(
        normalizedGoal: String,
        candidates: List<DeviceAction.AppCandidate>
    ): Boolean {
        if (candidates.isEmpty()) return true
        var residual = normalizedGoal
        candidates.forEach { candidate ->
            residual = residual.replace(candidate.label.lowercase(), " ")
            residual = residual.replace(candidate.packageName.lowercase(), " ")
        }
        residual = residual
            .replace(Regex("\\b(?:open|launch|start|switch\\s+to|go\\s+to|use|and|then|finally|again|app|application)\\b"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        return residual.isNotBlank()
    }

    private fun extractSearchQuery(
        goal: String,
        candidates: List<DeviceAction.AppCandidate>
    ): String? {
        val match = SEARCH_VERBS.find(goal) ?: return null
        var query = match.groupValues[1].trim()
        candidates.forEach { candidate ->
            val labelPattern = Regex("\\s+(?:in|on|using|inside)\\s+${Regex.escape(candidate.label)}$", RegexOption.IGNORE_CASE)
            query = query.replace(labelPattern, "").trim()
        }
        query = query
            .replace(Regex("\\s+(?:and\\s+then|then)\\s+.*$", RegexOption.IGNORE_CASE), "")
            .trim()
            .trim('"', '\'', '.', '!', '?')
        return query.takeIf { it.isNotBlank() }
    }

    private fun extractExpectedDestination(
        goal: String,
        candidates: List<DeviceAction.AppCandidate>
    ): String? {
        val match = Regex("\\b(?:to|in)\\s+([A-Z][A-Za-z0-9 _'-]{1,35}|Saved Messages)", RegexOption.IGNORE_CASE)
            .findAll(goal)
            .map { it.groupValues[1].trim().trim('.', ',', '!', '?') }
            .filterNot { candidateDest ->
                candidates.any { it.label.equals(candidateDest, ignoreCase = true) }
            }
            .lastOrNull()
        return match?.takeIf { it.isNotBlank() }
    }

    private fun extractQuotedOrMessageBody(goal: String): String? {
        Regex("[\"']([^\"']+)[\"']").find(goal)?.groupValues?.getOrNull(1)?.let { return it.trim() }
        Regex("\\bsend\\s+(.+?)\\s+to\\s+", RegexOption.IGNORE_CASE)
            .find(goal)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { !it.equals("a message", ignoreCase = true) && !it.equals("message", ignoreCase = true) }
            ?.let { return it.trim() }
        return null
    }

    private fun extractNavigationDestination(
        normalizedGoal: String,
        candidates: List<DeviceAction.AppCandidate>
    ): String? {
        var stripped = normalizedGoal
        candidates.forEach { candidate ->
            stripped = stripped
                .replace(Regex("\\b(?:open|launch|use|in|on)\\s+${Regex.escape(candidate.label.lowercase())}\\b"), " ")
                .replace(Regex("\\b${Regex.escape(candidate.packageName.lowercase())}\\b"), " ")
        }
        stripped = stripped.replace(Regex("^\\s*(?:and|then)\\s+"), "").trim()
        val match = NAVIGATE_DESTINATION_PATTERN.find(stripped) ?: return null
        val destination = match.groupValues[1]
            .replace(Regex("\\s+(?:page|screen|tab|section|chat|channel|settings)$"), "")
            .trim()
        if (destination.isBlank() || destination in STOP_WORDS) return null
        if (candidates.any { it.label.equals(destination, ignoreCase = true) }) return null
        return destination
    }

    private fun extractSignificantKeywords(
        normalizedGoal: String,
        candidates: List<DeviceAction.AppCandidate>
    ): Set<String> {
        var stripped = normalizedGoal
        candidates.forEach { candidate ->
            stripped = stripped.replace(candidate.label.lowercase(), " ")
            stripped = stripped.replace(candidate.packageName.lowercase(), " ")
        }
        return stripped
            .split(Regex("[^a-z0-9]+"))
            .map { it.trim() }
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()
    }

    private fun detectSettingsPageFromGoal(normalizedGoal: String): SettingsPage? {
        if (!normalizedGoal.contains("settings") && !normalizedGoal.contains("options") && !normalizedGoal.contains("debugging")) {
            return null
        }
        return when {
            normalizedGoal.contains("wireless debugging") -> SettingsPage.WIRELESS_DEBUGGING
            normalizedGoal.contains("developer options") -> SettingsPage.DEVELOPER_OPTIONS
            normalizedGoal.contains("wi-fi") || normalizedGoal.contains("wifi") || normalizedGoal.contains("internet") -> SettingsPage.WIFI
            normalizedGoal.contains("bluetooth") -> SettingsPage.BLUETOOTH
            normalizedGoal.contains("display") || normalizedGoal.contains("brightness") -> SettingsPage.DISPLAY
            normalizedGoal.contains("sound") || normalizedGoal.contains("volume") -> SettingsPage.SOUND
            normalizedGoal.contains("accessibility") -> SettingsPage.ACCESSIBILITY
            normalizedGoal.contains("notification settings") || normalizedGoal.contains("app notifications") -> SettingsPage.NOTIFICATION_SETTINGS
            normalizedGoal.contains("app info") || normalizedGoal.contains("app details") -> SettingsPage.APP_DETAILS
            else -> null
        }
    }

    private fun settingsKeywordsFor(page: SettingsPage?, targetPackage: String?): Set<String> = when (page) {
        SettingsPage.WIFI -> setOf("wi-fi", "wifi", "internet", "network")
        SettingsPage.BLUETOOTH -> setOf("bluetooth", "connected devices", "pair new device")
        SettingsPage.DISPLAY -> setOf("display", "brightness", "dark theme", "screen timeout")
        SettingsPage.SOUND -> setOf("sound", "vibration", "media volume", "ring volume")
        SettingsPage.ACCESSIBILITY -> setOf("accessibility", "downloaded apps", "screen reader", "nabu")
        SettingsPage.NOTIFICATION_SETTINGS -> setOf("notifications", "app notifications", "notification history")
        SettingsPage.APP_DETAILS -> setOfNotNull("app info", "uninstall", "force stop", "permissions", targetPackage?.lowercase())
        SettingsPage.DEVELOPER_OPTIONS -> setOf("developer options", "usb debugging", "wireless debugging")
        SettingsPage.WIRELESS_DEBUGGING -> setOf("wireless debugging", "pair device", "ip address")
        null -> emptySet()
    }

    private fun findElementForOutcome(
        screen: UiScreenState,
        elementId: String?,
        targetLabel: String?
    ): UiElement? {
        if (elementId != null) {
            screen.element(elementId)?.let { return it }
        }
        if (!targetLabel.isNullOrBlank()) {
            val normalizedTarget = normalizeText(targetLabel)
            screen.elements.firstOrNull { el ->
                el.visible && elementMatchesLabel(el, normalizedTarget)
            }?.let { return it }
        }
        return null
    }

    private fun elementMatchesLabel(element: UiElement, targetLabel: String): Boolean {
        val norm = normalizeText(targetLabel)
        return listOfNotNull(element.text, element.contentDescription, element.hintText, element.resourceId)
            .any { candidate ->
                val normCandidate = normalizeText(candidate)
                normCandidate == norm || normCandidate.contains(norm)
            }
    }

    private fun collectVisibleTextAndIds(screen: UiScreenState): String = buildString {
        append(screen.packageName.orEmpty().lowercase()).append(' ')
        append(screen.activityName.orEmpty().lowercase()).append(' ')
        screen.elements.filter { it.visible && !it.password }.forEach { el ->
            append(el.text.orEmpty().lowercase()).append(' ')
            append(el.contentDescription.orEmpty().lowercase()).append(' ')
            append(el.hintText.orEmpty().lowercase()).append(' ')
            append(el.resourceId.orEmpty().lowercase()).append(' ')
        }
    }

    private fun normalizeText(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9%]+"), " ")
        .trim()
}

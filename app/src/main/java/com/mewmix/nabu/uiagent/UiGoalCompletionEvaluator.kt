package com.mewmix.nabu.uiagent

import com.mewmix.nabu.actions.DeviceAction

internal sealed interface UiSuccessCondition {
    data class SearchResultsVisible(
        val query: String,
        val requiredPackages: Set<String> = emptySet()
    ) : UiSuccessCondition
}

internal data class UiGoalCompletion(
    val summary: String,
    val condition: UiSuccessCondition
)

/**
 * Compiles deterministic, observable completion contracts from the user's goal.
 *
 * These contracts are deliberately narrow. They never infer that an intermediate search
 * completes a larger goal such as "search for X and open the first result".
 */
internal object UiGoalCompletionEvaluator {
    fun compile(
        goal: String,
        appCandidates: List<DeviceAction.AppCandidate> = emptyList()
    ): List<UiSuccessCondition> {
        val match = TERMINAL_SEARCH.find(goal.trim()) ?: return emptyList()
        var query = match.groupValues[1].trim().trimEnd('.', '!', '?')
        appCandidates.forEach { app ->
            query = query.replace(
                Regex("""(?i)\s+(?:in|on|using)\s+${Regex.escape(app.label)}\s*$"""),
                ""
            ).trim()
        }
        query = query
            .replace(Regex("""(?i)^(?:the|a|an)\s+"""), "")
            .trim()
        val requiredPackages = appCandidates.map { it.packageName.lowercase() }.filter { it.isNotBlank() }.toSet()
        return query.takeIf(String::isNotBlank)
            ?.let { listOf(UiSuccessCondition.SearchResultsVisible(it, requiredPackages)) }
            .orEmpty()
    }

    fun evaluate(
        goal: String,
        screen: UiScreenState,
        appCandidates: List<DeviceAction.AppCandidate> = emptyList()
    ): UiGoalCompletion? {
        return compile(goal, appCandidates).firstNotNullOfOrNull { condition ->
            evaluateCondition(condition, screen)
        }
    }

    fun plannerDescriptions(
        goal: String,
        appCandidates: List<DeviceAction.AppCandidate> = emptyList()
    ): List<String> {
        val searchDescriptions = compile(goal, appCandidates).map { condition ->
            when (condition) {
                is UiSuccessCondition.SearchResultsVisible ->
                    "search_results_visible: query field equals '${condition.query}' and results or an empty-results state are visible"
            }
        }
        val checkpointDescriptions = UiGoalVerifier.compileCheckpoints(goal, appCandidates)
            .map { "${it.id}: ${it.expectedOutcome.description}" }
        return (searchDescriptions + checkpointDescriptions).distinct()
    }

    internal fun evaluateCondition(
        condition: UiSuccessCondition,
        screen: UiScreenState
    ): UiGoalCompletion? = when (condition) {
        is UiSuccessCondition.SearchResultsVisible -> evaluateSearch(condition, screen)
    }

    private fun evaluateSearch(
        condition: UiSuccessCondition.SearchResultsVisible,
        screen: UiScreenState
    ): UiGoalCompletion? {
        if (condition.requiredPackages.isNotEmpty() &&
            condition.requiredPackages.none { screen.packageName.equals(it, ignoreCase = true) }
        ) {
            return null
        }
        val expected = normalize(condition.query)
        if (expected.isBlank()) return null
        val queryField = screen.elements.firstOrNull { element ->
            element.visible &&
                element.enabled &&
                element.editable &&
                !element.password &&
                normalize(element.text.orEmpty()) == expected
        } ?: return null
        val resultVisible = screen.elements.any { element ->
            if (!element.visible || element.id == queryField.id || element.editable) return@any false
            val values = listOfNotNull(
                element.text,
                element.contentDescription
            ).map(::normalize)
            values.any { value ->
                value.contains(expected) || EMPTY_RESULT_PHRASES.any(value::contains)
            }
        }
        if (!resultVisible) return null
        return UiGoalCompletion(
            summary = "Search results for “${condition.query}” are visible.",
            condition = condition
        )
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()

    private val TERMINAL_SEARCH =
        Regex("""(?is)\bsearch(?:\s+for)?\s+(.+?)\s*$""")

    private val EMPTY_RESULT_PHRASES = listOf(
        "no results",
        "no matches",
        "nothing found",
        "no results found"
    )
}

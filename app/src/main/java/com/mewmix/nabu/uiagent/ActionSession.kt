package com.mewmix.nabu.uiagent

import kotlinx.coroutines.launch
import java.util.UUID

enum class ActionSessionMode {
    SINGLE_TURN,
    TEMPORARY_CONVERSATION,
    HANDOFF_TO_CHAT
}

enum class ActionSessionStatus {
    IDLE,
    ROUTING,
    OBSERVING,
    PLANNING,
    EXECUTING,
    VERIFYING,
    WAITING_FOR_USER,
    COMPLETED,
    FAILED,
    CANCELLED
}

internal fun actionStatusForProgress(
    phase: String,
    current: ActionSessionStatus
): ActionSessionStatus {
    val normalized = phase.trim().lowercase()
    return when {
        normalized.startsWith("rout") || normalized.startsWith("start") ->
            ActionSessionStatus.ROUTING
        normalized.startsWith("observe") -> ActionSessionStatus.OBSERVING
        normalized.startsWith("plan") -> ActionSessionStatus.PLANNING
        normalized.startsWith("execute") || normalized.startsWith("navigate") ->
            ActionSessionStatus.EXECUTING
        normalized.startsWith("verify") || normalized.startsWith("transition") ->
            ActionSessionStatus.VERIFYING
        normalized.startsWith("confirm") || normalized.startsWith("needs input") ->
            ActionSessionStatus.WAITING_FOR_USER
        else -> current
    }
}

data class ActionSessionMetrics(
    val requestReceivedMs: Long = 0L,
    val sessionCreatedMs: Long = 0L,
    val routingStartedMs: Long = 0L,
    val routingCompletedMs: Long = 0L,
    val observationStartedMs: Long = 0L,
    val observationReadyMs: Long = 0L,
    val modelRequestStartedMs: Long = 0L,
    val modelFirstResponseMs: Long = 0L,
    val modelResponseCompleteMs: Long = 0L,
    val actionDispatchMs: Long = 0L,
    val actionCompleteMs: Long = 0L,
    val verificationStartedMs: Long = 0L,
    val verificationCompleteMs: Long = 0L,
    val nextModelRequestMs: Long = 0L,
    val totalStepLatencyMs: Long = 0L,
    val totalSessionLatencyMs: Long = 0L,
    val plannerRequestCount: Long = 0L,
    val appResolutionMs: Long = 0L,
    val modelRequired: Long = 0L,
    val modelInitializationOnCriticalPath: Long = 0L
) {
    fun toMap(): Map<String, Long> = mapOf(
        "request_received_ms" to requestReceivedMs,
        "session_created_ms" to sessionCreatedMs,
        "routing_started_ms" to routingStartedMs,
        "routing_completed_ms" to routingCompletedMs,
        "observation_started_ms" to observationStartedMs,
        "observation_ready_ms" to observationReadyMs,
        "model_request_started_ms" to modelRequestStartedMs,
        "model_first_response_ms" to modelFirstResponseMs,
        "model_response_complete_ms" to modelResponseCompleteMs,
        "action_dispatch_ms" to actionDispatchMs,
        "action_complete_ms" to actionCompleteMs,
        "verification_started_ms" to verificationStartedMs,
        "verification_complete_ms" to verificationCompleteMs,
        "next_model_request_ms" to nextModelRequestMs,
        "total_step_latency_ms" to totalStepLatencyMs,
        "total_session_latency_ms" to totalSessionLatencyMs,
        "planner_request_count" to plannerRequestCount,
        "app_resolution_ms" to appResolutionMs,
        "model_required" to modelRequired,
        "model_initialization_on_critical_path" to modelInitializationOnCriticalPath,
        "invocation_to_first_action_ms" to duration(requestReceivedMs, actionDispatchMs),
        "model_first_response_latency_ms" to duration(modelRequestStartedMs, modelFirstResponseMs),
        "model_latency_ms" to duration(modelRequestStartedMs, modelResponseCompleteMs),
        "action_latency_ms" to duration(actionDispatchMs, actionCompleteMs),
        "verification_latency_ms" to duration(verificationStartedMs, verificationCompleteMs),
        "initial_observation_ms" to duration(routingStartedMs, observationReadyMs)
    )

    fun recordRuntimeEvent(
        name: String,
        occurredAtMs: Long,
        fields: Map<String, Any?> = emptyMap()
    ): ActionSessionMetrics = when (name) {
        "routing_started" -> copy(
            routingStartedMs = routingStartedMs.takeIf { it > 0L } ?: occurredAtMs
        )
        "routing_completed" -> copy(
            routingCompletedMs = occurredAtMs,
            appResolutionMs = fields.long("app_resolution_ms"),
            modelRequired = if (fields["model_required"] == true) 1L else 0L,
            modelInitializationOnCriticalPath =
                if (fields["model_initialization_on_critical_path"] == true) 1L else 0L
        )
        "observation_captured" -> {
            val workMs = fields.long("capture_ms") + fields.long("index_ms")
            copy(
                observationStartedMs = observationStartedMs.takeIf { it > 0L }
                    ?: (occurredAtMs - workMs).coerceAtLeast(0L),
                observationReadyMs = occurredAtMs
            )
        }
        "planner_request_started" -> if (modelRequestStartedMs == 0L) {
            copy(modelRequestStartedMs = occurredAtMs, plannerRequestCount = plannerRequestCount + 1L)
        } else {
            copy(nextModelRequestMs = occurredAtMs, plannerRequestCount = plannerRequestCount + 1L)
        }
        "planner_first_response" -> copy(
            modelFirstResponseMs = modelFirstResponseMs.takeIf { it > 0L } ?: occurredAtMs
        )
        "planner_output_received" -> copy(modelResponseCompleteMs = occurredAtMs)
        "action_dispatch_started" -> copy(
            actionDispatchMs = actionDispatchMs.takeIf { it > 0L } ?: occurredAtMs
        )
        "action_dispatch_completed" -> copy(actionCompleteMs = occurredAtMs)
        "verification_started" -> copy(
            verificationStartedMs = verificationStartedMs.takeIf { it > 0L } ?: occurredAtMs
        )
        "verification_completed" -> copy(verificationCompleteMs = occurredAtMs)
        else -> this
    }

    private fun Map<String, Any?>.long(key: String): Long =
        (get(key) as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L

    private fun duration(startMs: Long, endMs: Long): Long =
        if (startMs > 0L && endMs >= startMs) endMs - startMs else 0L
}

data class ActionStepRecord(
    val sequence: Int,
    val observation: String,
    val reasoningSummary: String,
    val action: String,
    val target: String?,
    val result: String,
    val postActionObservation: String?,
    val verification: String?,
    val latencyMs: Long,
    val retryCount: Int = 0,
    val sourcePackage: String? = null,
    val resultPackage: String? = null,
    val actionFamily: String? = null,
    val semanticAction: String? = null,
    val executionMechanism: String? = null,
    val verificationStatus: String? = null,
    val sourceWindow: String? = null,
    val resultWindow: String? = null,
    val transportStatus: String = UiTransportStatus.ACCEPTED.name.lowercase(),
    val postconditionStatus: String? = null,
    val goalVerificationStatus: String? = verificationStatus,
    val evidenceCategory: String? = null,
    val checkpointId: String? = null,
    val sessionId: String? = null,
    val observationId: String? = null,
    val beforeStateFingerprint: String? = observation,
    val afterStateFingerprint: String? = postActionObservation,
    val leaseValidationOutcome: String = "authorized",
    val failureClassification: String? = null,
    val observationLatencyMs: Long = 0L,
    val modelDecisionLatencyMs: Long = 0L,
    val executionLatencyMs: Long = 0L,
    val verificationLatencyMs: Long = 0L,
    val recoverySummary: String? = null,
    val terminationReason: String? = null,
    val screenshotArtifactRef: String? = null,
    val recordedAtMs: Long = System.currentTimeMillis()
)

data class ActionConversationTurn(
    val id: String = UUID.randomUUID().toString(),
    val userRequest: String,
    val assistantResponse: String? = null,
    val stepRecords: List<ActionStepRecord> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis(),
    val isComplete: Boolean = false,
    val isError: Boolean = false,
    val verificationStatus: UiVerificationStatus = UiVerificationStatus.UNVERIFIABLE
)

data class TraceRecordReference(
    val sessionId: String,
    val startSequence: Int,
    val endSequence: Int,
    val observationIds: List<String> = emptyList(),
    val checkpointIds: List<String> = emptyList()
) {
    fun toCompactTag(): String = "trace:$sessionId#$startSequence..$endSequence"
}

data class CompactedHistorySummary(
    val id: String = UUID.randomUUID().toString(),
    val traceReference: TraceRecordReference,
    val summaryText: String,
    val verifiedTransitions: List<String>,
    val failedAttempts: List<String>,
    val taskConstraints: List<String>,
    val unresolvedUncertainties: List<String>,
    val highestVerificationStatus: UiVerificationStatus,
    val summarizedByLocalModel: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class CompactionResult(
    val recentRawSteps: List<ActionStepRecord>,
    val allRetainedSteps: List<ActionStepRecord>,
    val compactedSummaries: List<CompactedHistorySummary>,
    val activeIncidents: List<UiExecutionIncident>,
    val taskConstraints: List<String>
)

data class ActionSession(
    val id: String = UUID.randomUUID().toString(),
    val mode: ActionSessionMode = ActionSessionMode.SINGLE_TURN,
    val status: ActionSessionStatus = ActionSessionStatus.IDLE,
    val originalGoal: String,
    val currentGoal: String = originalGoal,
    val currentStep: Int = 0,
    val turns: List<ActionConversationTurn> = emptyList(),
    val stepHistory: List<ActionStepRecord> = emptyList(),
    val recentRawSteps: List<ActionStepRecord> = emptyList(),
    val compactedHistory: List<CompactedHistorySummary> = emptyList(),
    val taskConstraints: List<String> = ActionSessionWorkingMemory.extractTaskConstraints(originalGoal),
    val activeIncidents: List<UiExecutionIncident> = emptyList(),
    val resolvedIncidents: List<UiExecutionIncident> = emptyList(),
    val pendingObjective: String? = null,
    val lastObservedPackage: String? = null,
    val lastObservedWindow: String? = null,
    val lastVerificationResult: String? = null,
    val lastVerificationStatus: UiVerificationStatus = UiVerificationStatus.UNVERIFIABLE,
    val metrics: ActionSessionMetrics = ActionSessionMetrics(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    fun toHandoffMap(): Map<String, String> = mapOf(
        "session_id" to id,
        "mode" to mode.name,
        "original_goal" to originalGoal,
        "current_goal" to currentGoal,
        "pending_objective" to (pendingObjective ?: ""),
        "last_package" to (lastObservedPackage ?: ""),
        "last_window" to (lastObservedWindow ?: ""),
        "last_verification" to (lastVerificationResult ?: ""),
        "last_verification_status" to lastVerificationStatus.name.lowercase(),
        "step_count" to stepHistory.size.toString(),
        "compacted_summary_count" to compactedHistory.size.toString(),
        "turn_count" to turns.size.toString(),
        "active_incident_count" to activeIncidents.size.toString()
    )

    fun toHandoffBundle(): android.os.Bundle = android.os.Bundle().apply {
        putString("session_id", id)
        putString("mode", mode.name)
        putString("original_goal", originalGoal)
        putString("current_goal", currentGoal)
        putString("pending_objective", pendingObjective)
        putString("last_package", lastObservedPackage)
        putString("last_window", lastObservedWindow)
        putString("last_verification", lastVerificationResult)
        putString("last_verification_status", lastVerificationStatus.name.lowercase())
        putInt("step_count", stepHistory.size)
        putInt("compacted_summary_count", compactedHistory.size)
        putInt("turn_count", turns.size)
        putInt("active_incident_count", activeIncidents.size)
    }
}

/**
 * Bounded working memory and history compaction for UI automation sessions (Phase 4).
 *
 * Preserves:
 * - Recent raw observations and actions (`MAX_RAW_RECENT_STEPS`).
 * - Older verified transitions as compact summaries (`CompactedHistorySummary`).
 * - Persistent references to detailed trace records (`TraceRecordReference`).
 * - Active unresolved incidents until resolved or terminated.
 * - Failed attempts, task constraints, and unresolved uncertainty.
 *
 * Enforces that summarization never upgrades an unknown, executed, state-changed, or failed
 * outcome into a verified goal completion.
 */
object ActionSessionWorkingMemory {
    const val MAX_RECENT_TURNS = 8
    const val MAX_RECENT_STEPS = 64
    const val MAX_RAW_RECENT_STEPS = 8
    const val COMPACTION_CHUNK_SIZE = 4
    const val MAX_COMPACTED_SUMMARIES = 16

    private val FALSE_SUCCESS_CLAIMS = listOf(
        Regex("\\bgoal\\s+verified\\b", RegexOption.IGNORE_CASE),
        Regex("\\bgoal_verified\\b", RegexOption.IGNORE_CASE),
        Regex("\\btask\\s+(?:is\\s+)?complete(?:d)?\\b", RegexOption.IGNORE_CASE),
        Regex("\\bsuccessfully\\s+completed\\b", RegexOption.IGNORE_CASE),
        Regex("\\bverified\\s+success\\b", RegexOption.IGNORE_CASE),
        Regex("\\ball\\s+steps\\s+succeeded\\b", RegexOption.IGNORE_CASE)
    )

    fun appendStep(
        history: List<ActionStepRecord>,
        step: ActionStepRecord
    ): List<ActionStepRecord> = (history + step).takeLast(MAX_RECENT_STEPS)

    fun appendTurn(
        history: List<ActionConversationTurn>,
        turn: ActionConversationTurn
    ): List<ActionConversationTurn> = (history + turn).takeLast(MAX_RECENT_TURNS)

    fun extractTaskConstraints(goal: String): List<String> {
        if (goal.isBlank()) return emptyList()
        val constraints = mutableListOf<String>()
        val normalized = AutomationTraceRedactor.scrubDiagnosticText(goal.trim()).orEmpty()
        constraints += "goal: $normalized"

        val negationPattern = Regex(
            "\\b(do not|don't|never|without|only|must not|avoid)\\s+([^.;,]+)",
            RegexOption.IGNORE_CASE
        )
        negationPattern.findAll(normalized).forEach { match ->
            constraints += "constraint: ${match.value.trim()}"
        }
        return constraints.distinct()
    }

    fun compactStepRecords(
        sessionId: String,
        goal: String,
        steps: List<ActionStepRecord>,
        existingSummaries: List<CompactedHistorySummary> = emptyList(),
        activeIncidents: List<UiExecutionIncident> = emptyList(),
        rawWindowSize: Int = MAX_RAW_RECENT_STEPS
    ): CompactionResult {
        val retainedSteps = steps.takeLast(MAX_RECENT_STEPS)
        val taskConstraints = extractTaskConstraints(goal)
        // Active unresolved incidents are always retained verbatim until resolved or terminated
        val unresolvedIncidents = activeIncidents.filter { !it.resolved }

        if (retainedSteps.size <= rawWindowSize) {
            return CompactionResult(
                recentRawSteps = retainedSteps,
                allRetainedSteps = retainedSteps,
                compactedSummaries = existingSummaries.takeLast(MAX_COMPACTED_SUMMARIES),
                activeIncidents = unresolvedIncidents,
                taskConstraints = taskConstraints
            )
        }

        val olderSteps = retainedSteps.dropLast(rawWindowSize)
        val recentRaw = retainedSteps.takeLast(rawWindowSize)
        val alreadyCoveredMaxSeq = existingSummaries.maxOfOrNull { it.traceReference.endSequence } ?: 0
        val uncompactedOlder = olderSteps.filter { it.sequence > alreadyCoveredMaxSeq }

        val newSummaries = uncompactedOlder
            .chunked(COMPACTION_CHUNK_SIZE)
            .map { chunk -> buildDeterministicSummary(sessionId, chunk, taskConstraints) }

        val mergedSummaries = (existingSummaries + newSummaries).takeLast(MAX_COMPACTED_SUMMARIES)
        return CompactionResult(
            recentRawSteps = recentRaw,
            allRetainedSteps = retainedSteps,
            compactedSummaries = mergedSummaries,
            activeIncidents = unresolvedIncidents,
            taskConstraints = taskConstraints
        )
    }

    fun buildDeterministicSummary(
        sessionId: String,
        chunk: List<ActionStepRecord>,
        taskConstraints: List<String>
    ): CompactedHistorySummary {
        require(chunk.isNotEmpty()) { "Cannot summarize an empty step chunk." }
        val startSeq = chunk.first().sequence
        val endSeq = chunk.last().sequence
        val observationIds = chunk.mapNotNull { it.observationId }.distinct()
        val checkpointIds = chunk.mapNotNull { it.checkpointId }.distinct()
        val traceRef = TraceRecordReference(
            sessionId = sessionId,
            startSequence = startSeq,
            endSequence = endSeq,
            observationIds = observationIds,
            checkpointIds = checkpointIds
        )

        val verifiedTransitions = mutableListOf<String>()
        val failedAttempts = mutableListOf<String>()
        val unresolvedUncertainties = mutableListOf<String>()
        var highestStatus = UiVerificationStatus.UNVERIFIABLE

        for (step in chunk) {
            val status = parseStatus(step.goalVerificationStatus ?: step.verificationStatus)
            highestStatus = maxVerificationStatus(highestStatus, status)
            val actionDesc = buildString {
                append("#${step.sequence} ${step.action}")
                step.target?.takeIf { it.isNotBlank() }?.let {
                    append("(${AutomationTraceRedactor.sanitizeTarget(it)})")
                }
            }
            val transitionDesc = "${step.sourcePackage ?: "?"}/${step.beforeStateFingerprint ?: step.observation} -> " +
                "${step.resultPackage ?: "?"}/${step.afterStateFingerprint ?: step.postActionObservation ?: "?"}"

            when (status) {
                UiVerificationStatus.GOAL_VERIFIED -> {
                    verifiedTransitions += "$actionDesc [GOAL_VERIFIED]: $transitionDesc"
                }
                UiVerificationStatus.STATE_CHANGED -> {
                    if (step.result == "failed" || step.failureClassification != null) {
                        failedAttempts += "$actionDesc [FAILED:${step.failureClassification ?: "postcondition"}]: $transitionDesc"
                    } else {
                        verifiedTransitions += "$actionDesc [STATE_CHANGED]: $transitionDesc"
                        unresolvedUncertainties += "$actionDesc changed UI state ($transitionDesc) but goal outcome remained unverified"
                    }
                }
                UiVerificationStatus.EXECUTED -> {
                    if (step.result == "failed" || step.failureClassification != null) {
                        failedAttempts += "$actionDesc [FAILED:${step.failureClassification ?: "executed_unverified"}]"
                    } else {
                        unresolvedUncertainties += "$actionDesc [EXECUTED]: accepted by Android on ${step.beforeStateFingerprint ?: step.observation} without verified state change"
                    }
                }
                UiVerificationStatus.FAILED -> {
                    val reason = step.failureClassification ?: step.evidenceCategory ?: "failed"
                    val detail = AutomationTraceRedactor.scrubDiagnosticText(step.verification).orEmpty()
                    failedAttempts += "$actionDesc [FAILED:$reason]: $detail".trim()
                }
                UiVerificationStatus.UNVERIFIABLE -> {
                    val reason = step.failureClassification ?: step.evidenceCategory ?: "unverifiable"
                    unresolvedUncertainties += "$actionDesc [UNVERIFIABLE:$reason]: post-action outcome could not be verified"
                }
            }
        }

        val summaryParts = mutableListOf<String>()
        summaryParts += "[${traceRef.toCompactTag()}]"
        if (verifiedTransitions.isNotEmpty()) {
            summaryParts += "Verified transitions (${verifiedTransitions.size}): ${verifiedTransitions.joinToString("; ")}"
        }
        if (failedAttempts.isNotEmpty()) {
            summaryParts += "Failed attempts (${failedAttempts.size}): ${failedAttempts.joinToString("; ")}"
        }
        if (unresolvedUncertainties.isNotEmpty()) {
            summaryParts += "Unresolved (${unresolvedUncertainties.size}): ${unresolvedUncertainties.joinToString("; ")}"
        }

        return CompactedHistorySummary(
            traceReference = traceRef,
            summaryText = summaryParts.joinToString(" | "),
            verifiedTransitions = verifiedTransitions,
            failedAttempts = failedAttempts,
            taskConstraints = taskConstraints,
            unresolvedUncertainties = unresolvedUncertainties,
            highestVerificationStatus = highestStatus,
            summarizedByLocalModel = false
        )
    }

    /**
     * Applies an optional local-inference summary off the critical path while strictly enforcing
     * that a model summary can NEVER upgrade an unknown, executed, state-changed, or failed outcome
     * into a successful/verified one, nor drop failed attempts or unresolved uncertainties.
     */
    fun applyModelSummarySafely(
        deterministicSummary: CompactedHistorySummary,
        candidateModelSummary: String?
    ): CompactedHistorySummary {
        val raw = candidateModelSummary?.trim().orEmpty()
        if (raw.isBlank()) return deterministicSummary

        val redacted = AutomationTraceRedactor.scrubDiagnosticText(raw).orEmpty()
        val isActuallyGoalVerified =
            deterministicSummary.highestVerificationStatus == UiVerificationStatus.GOAL_VERIFIED &&
                deterministicSummary.failedAttempts.isEmpty() &&
                deterministicSummary.unresolvedUncertainties.isEmpty()

        val sanitizedModelText = if (!isActuallyGoalVerified) {
            var cleaned = redacted
            for (pattern in FALSE_SUCCESS_CLAIMS) {
                cleaned = pattern.replace(cleaned, "[unverified_in_trace]")
            }
            cleaned
        } else {
            redacted
        }

        val preservedFooter = buildList {
            add("[${deterministicSummary.traceReference.toCompactTag()}]")
            add("status=${deterministicSummary.highestVerificationStatus.name.lowercase()}")
            if (deterministicSummary.failedAttempts.isNotEmpty()) {
                add("failures=${deterministicSummary.failedAttempts.joinToString("; ")}")
            }
            if (deterministicSummary.unresolvedUncertainties.isNotEmpty()) {
                add("unresolved=${deterministicSummary.unresolvedUncertainties.joinToString("; ")}")
            }
        }.joinToString(" | ")

        return deterministicSummary.copy(
            summaryText = "$sanitizedModelText ($preservedFooter)",
            // Never allow model summarization to alter verification status or drop structured lists
            highestVerificationStatus = deterministicSummary.highestVerificationStatus,
            verifiedTransitions = deterministicSummary.verifiedTransitions,
            failedAttempts = deterministicSummary.failedAttempts,
            taskConstraints = deterministicSummary.taskConstraints,
            unresolvedUncertainties = deterministicSummary.unresolvedUncertainties,
            summarizedByLocalModel = true
        )
    }

    /**
     * Launches optional local-inference summarization off the critical execution path.
     * Execution never awaits this job, and any failure or timeout preserves the deterministic summary.
     */
    fun summarizeOffCriticalPath(
        scope: kotlinx.coroutines.CoroutineScope,
        deterministicSummary: CompactedHistorySummary,
        localSummarizer: suspend (String) -> String?,
        onRefined: (CompactedHistorySummary) -> Unit
    ): kotlinx.coroutines.Job = scope.launch {
        val candidate = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(1_500L) {
                localSummarizer(deterministicSummary.summaryText)
            }
        }.getOrNull()
        if (!candidate.isNullOrBlank()) {
            onRefined(applyModelSummarySafely(deterministicSummary, candidate))
        }
    }

    fun compactPlannerHistory(
        sessionId: String,
        goal: String,
        history: List<UiActionHistoryEntry>,
        rawWindowSize: Int = MAX_RAW_RECENT_STEPS
    ): Pair<List<UiActionHistoryEntry>, List<CompactedHistorySummary>> {
        if (history.size <= rawWindowSize) {
            return history to emptyList()
        }
        val older = history.dropLast(rawWindowSize)
        val recent = history.takeLast(rawWindowSize)
        val constraints = extractTaskConstraints(goal)
        val pseudoRecords = older.mapIndexed { idx, entry ->
            val seq = if (entry.index >= 0) entry.index + 1 else idx + 1
            val isFailed = entry.outcome != Outcome.SUCCEEDED
            val status = when {
                isFailed -> UiVerificationStatus.FAILED.name.lowercase()
                entry.changedScreen -> UiVerificationStatus.STATE_CHANGED.name.lowercase()
                else -> UiVerificationStatus.EXECUTED.name.lowercase()
            }
            ActionStepRecord(
                sequence = seq,
                observation = entry.sourceScreenId,
                reasoningSummary = entry.action,
                action = entry.action,
                target = entry.targetLabel ?: entry.targetElementId,
                result = if (isFailed) "failed" else "succeeded",
                postActionObservation = entry.resultScreenId,
                verification = entry.detail,
                latencyMs = 0L,
                sourcePackage = entry.sourcePackage,
                resultPackage = entry.resultPackage,
                verificationStatus = status,
                goalVerificationStatus = status,
                sessionId = sessionId,
                beforeStateFingerprint = entry.sourceScreenId,
                afterStateFingerprint = entry.resultScreenId,
                failureClassification = if (isFailed) entry.outcome.name else null
            )
        }
        val summaries = pseudoRecords
            .chunked(COMPACTION_CHUNK_SIZE)
            .map { chunk -> buildDeterministicSummary(sessionId, chunk, constraints) }
            .takeLast(MAX_COMPACTED_SUMMARIES)
        return recent to summaries
    }

    private fun parseStatus(raw: String?): UiVerificationStatus {
        if (raw.isNullOrBlank()) return UiVerificationStatus.UNVERIFIABLE
        return runCatching { UiVerificationStatus.valueOf(raw.trim().uppercase()) }
            .getOrDefault(UiVerificationStatus.UNVERIFIABLE)
    }

    private fun maxVerificationStatus(a: UiVerificationStatus, b: UiVerificationStatus): UiVerificationStatus {
        val rank = mapOf(
            UiVerificationStatus.UNVERIFIABLE to 0,
            UiVerificationStatus.FAILED to 1,
            UiVerificationStatus.EXECUTED to 2,
            UiVerificationStatus.STATE_CHANGED to 3,
            UiVerificationStatus.GOAL_VERIFIED to 4
        )
        return if ((rank[b] ?: 0) >= (rank[a] ?: 0)) b else a
    }
}


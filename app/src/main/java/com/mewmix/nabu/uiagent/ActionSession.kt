package com.mewmix.nabu.uiagent

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
    val checkpointId: String? = null
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

data class ActionSession(
    val id: String = UUID.randomUUID().toString(),
    val mode: ActionSessionMode = ActionSessionMode.SINGLE_TURN,
    val status: ActionSessionStatus = ActionSessionStatus.IDLE,
    val originalGoal: String,
    val currentGoal: String = originalGoal,
    val currentStep: Int = 0,
    val turns: List<ActionConversationTurn> = emptyList(),
    val stepHistory: List<ActionStepRecord> = emptyList(),
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
        "turn_count" to turns.size.toString()
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
        putInt("turn_count", turns.size)
    }
}

/** Keeps process-lifetime action memory useful without allowing a long conversation to grow forever. */
object ActionSessionWorkingMemory {
    const val MAX_RECENT_TURNS = 8
    const val MAX_RECENT_STEPS = 64

    fun appendStep(
        history: List<ActionStepRecord>,
        step: ActionStepRecord
    ): List<ActionStepRecord> = (history + step).takeLast(MAX_RECENT_STEPS)

    fun appendTurn(
        history: List<ActionConversationTurn>,
        turn: ActionConversationTurn
    ): List<ActionConversationTurn> = (history + turn).takeLast(MAX_RECENT_TURNS)
}

package com.mewmix.nabu.uiagent

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.mewmix.nabu.utils.DatabaseHelper
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Redaction utility for durable execution traces.
 *
 * Ensures no passwords, PINs, authentication tokens, bearer secrets, or unrestricted
 * freeform UI text are persisted in ordinary traces.
 */
object AutomationTraceRedactor {
    private val SENSITIVE_KEYS = setOf(
        "goal", "text", "message", "raw_output", "planner_output", "authorization",
        "token", "recipient", "destination", "expected_destination", "content",
        "pin", "passcode", "password", "secret", "bearer", "auth_token", "api_key",
        "otp", "verification_code", "cookie", "session_token", "clipboard"
    )

    private val SENSITIVE_FRAGMENTS = listOf(
        "password", "passcode", "secret", "bearer", "auth_token", "api_key",
        "access_token", "refresh_token", "private_key", "pin_code", "otp"
    )

    private val BEARER_OR_TOKEN_REGEX = Regex(
        pattern = "(?i)(?:bearer\\s+[a-z0-9._~+/=-]{8,}|eyJ[a-zA-Z0-9_-]{10,}\\.[a-zA-Z0-9_-]{10,}\\.[a-zA-Z0-9_-]{5,}|sk-[a-zA-Z0-9]{12,}|AIza[0-9A-Za-z_-]{20,})"
    )

    private val LABELED_SECRET_REGEX = Regex(
        pattern = "(?i)\\b(?:password|passcode|pin|otp|token|secret|api[_-]?key|code)\\s*[:=]\\s*([^\\s,;\"']+)"
    )

    private val STANDALONE_PIN_OR_CARD_REGEX = Regex(
        pattern = "\\b(?:\\d{4,8}|(?:\\d[ -]?){13,19})\\b"
    )

    private val STRUCTURAL_TARGET_REGEX = Regex(
        pattern = "^(?:n\\d+|element_\\d+|\\[\\d+,\\d+\\]\\[\\d+,\\d+\\]|[a-z0-9_.]+:id/[a-z0-9_.-]+|[a-z0-9_]+)$",
        option = RegexOption.IGNORE_CASE
    )

    fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase().trim()
        return normalized in SENSITIVE_KEYS || SENSITIVE_FRAGMENTS.any(normalized::contains)
    }

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun redactGoalDigest(goal: String): String {
        val trimmed = goal.trim()
        return "redacted_goal(len=${trimmed.length},sha256=${sha256(trimmed).take(16)})"
    }

    /**
     * Sanitizes a target descriptor for durable trace persistence.
     * Structural identifiers (element IDs like `n1`, resource-ids like `com.app:id/btn`,
     * or bounds) are preserved; any embedded freeform UI text, PIN, password, or token
     * is replaced with a one-way digest.
     */
    fun sanitizeTarget(target: String?): String? {
        if (target == null) return null
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return ""
        if (STRUCTURAL_TARGET_REGEX.matches(trimmed) && !STANDALONE_PIN_OR_CARD_REGEX.matches(trimmed)) {
            return trimmed
        }
        // If target has structural prefix like "n3 (com.app:id/input) 'secret text'", keep only structural tokens
        val structuralTokens = mutableListOf<String>()
        Regex("\\bn\\d+\\b").find(trimmed)?.value?.let(structuralTokens::add)
        Regex("[a-zA-Z0-9_.]+:id/[a-zA-Z0-9_.-]+").find(trimmed)?.value?.let(structuralTokens::add)
        Regex("\\[\\d+,\\d+\\]\\[\\d+,\\d+\\]").find(trimmed)?.value?.let(structuralTokens::add)

        val digest = sha256(trimmed).take(12)
        return if (structuralTokens.isNotEmpty()) {
            "${structuralTokens.joinToString("|")}[redacted_label:$digest,len=${trimmed.length}]"
        } else {
            "target[redacted:$digest,len=${trimmed.length}]"
        }
    }

    /**
     * Scrubs secrets, tokens, PINs, and unrestricted freeform text from diagnostic strings.
     */
    fun scrubDiagnosticText(input: String?): String? {
        if (input == null) return null
        var scrubbed = input
        scrubbed = BEARER_OR_TOKEN_REGEX.replace(scrubbed) { match ->
            "[REDACTED_TOKEN:${sha256(match.value).take(8)}]"
        }
        scrubbed = LABELED_SECRET_REGEX.replace(scrubbed) { match ->
            val prefix = match.value.substringBefore(match.groupValues[1])
            "$prefix[REDACTED:${sha256(match.groupValues[1]).take(8)}]"
        }
        scrubbed = STANDALONE_PIN_OR_CARD_REGEX.replace(scrubbed) { match ->
            "[REDACTED_DIGITS:len=${match.value.length}]"
        }
        // Redact quoted literals that could contain arbitrary UI text or user input
        scrubbed = Regex("[\"']([^\"']{1,200})[\"']").replace(scrubbed) { match ->
            val inner = match.groupValues[1]
            if (STRUCTURAL_TARGET_REGEX.matches(inner) && !STANDALONE_PIN_OR_CARD_REGEX.matches(inner)) {
                match.value
            } else {
                "'[redacted:${sha256(inner).take(8)},len=${inner.length}]'"
            }
        }
        return scrubbed.take(280)
    }
}

internal data class AutomationTraceEvent(
    val sessionId: String,
    val sequence: Int,
    val elapsedMs: Long,
    val name: String,
    val fields: Map<String, Any?> = emptyMap()
) {
    fun toJson(): String = JsonObject().apply {
        addProperty("schema_version", 1)
        addProperty("session_id", sessionId)
        addProperty("sequence", sequence)
        addProperty("elapsed_ms", elapsedMs)
        addProperty("event", name)
        add("fields", sanitizeMap(fields))
    }.toString()

    private fun sanitizeMap(values: Map<String, Any?>): JsonObject = JsonObject().apply {
        values.forEach { (key, value) -> add(key, sanitize(key, value)) }
    }

    private fun sanitize(key: String, value: Any?): JsonElement {
        if (value == null) return JsonNull.INSTANCE
        if (AutomationTraceRedactor.isSensitiveKey(key)) {
            val raw = value.toString()
            return JsonObject().apply {
                addProperty("redacted", true)
                addProperty("length", raw.length)
                addProperty("sha256", AutomationTraceRedactor.sha256(raw))
            }
        }
        return when (value) {
            is Map<*, *> -> JsonObject().apply {
                value.forEach { (nestedKey, nestedValue) ->
                    val name = nestedKey.toString()
                    add(name, sanitize(name, nestedValue))
                }
            }
            is Iterable<*> -> JsonArray().apply { value.forEach { add(sanitize(key, it)) } }
            is Array<*> -> JsonArray().apply { value.forEach { add(sanitize(key, it)) } }
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is String -> {
                val scrubbed = if (key.lowercase() in setOf("target", "label", "detail", "reason", "summary", "error")) {
                    AutomationTraceRedactor.scrubDiagnosticText(value) ?: value
                } else {
                    AutomationTraceRedactor.scrubDiagnosticText(value) ?: value
                }
                JsonPrimitive(scrubbed)
            }
            is JsonElement -> value
            else -> JsonPrimitive(AutomationTraceRedactor.scrubDiagnosticText(value.toString()) ?: "")
        }
    }
}

data class TraceRetentionPolicy(
    val maxSessions: Int = 50,
    val maxStepsPerSession: Int = 200,
    val maxEventsPerSession: Int = 500,
    val maxAgeMs: Long = 7L * 24L * 60L * 60L * 1000L,
    val screenshotOptIn: Boolean = false
)

data class PersistedTraceSession(
    val sessionId: String,
    val schemaVersion: Int,
    val goalDigest: String,
    val checkpointId: String?,
    val status: String,
    val verificationStatus: UiVerificationStatus,
    val terminationReason: String?,
    val stepCount: Int,
    val recoveryAttemptCount: Int,
    val lastStateFingerprint: String?,
    val lastPackage: String?,
    val lastWindow: String?,
    val screenshotOptIn: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

data class ReconstructedStepEvidence(
    val sequence: Int,
    val checkpointId: String?,
    val observationId: String?,
    val beforeStateFingerprint: String?,
    val afterStateFingerprint: String?,
    val intendedAction: String,
    val sanitizedTarget: String?,
    val leaseValidationOutcome: String,
    val transportStatus: UiTransportStatus,
    val postconditionStatus: String?,
    val verificationStatus: UiVerificationStatus,
    val evidenceCategory: String?,
    val failureClassification: String?,
    val stateTransitioned: Boolean,
    val evidenceConsistentWithClaim: Boolean,
    val anomaly: String? = null
)

data class TraceReplayReport(
    val sessionId: String,
    val schemaVersion: Int,
    val sessionStatus: String,
    val claimedVerificationStatus: UiVerificationStatus,
    val reconstructedVerificationStatus: UiVerificationStatus,
    val terminationReason: String?,
    val stepCount: Int,
    val totalRecoveryAttempts: Int,
    val reconstructedSteps: List<ReconstructedStepEvidence>,
    val integrityVerified: Boolean,
    val anomalies: List<String>,
    val deterministicReconstructionOnly: Boolean = true,
    val liveUiActionsExecuted: Int = 0
)

data class SessionInspectionReport(
    val session: PersistedTraceSession,
    val steps: List<ActionStepRecord>,
    val eventsCount: Int,
    val timingSummaryMs: Map<String, Long>,
    val failureBreakdown: Map<String, Int>,
    val replayReport: TraceReplayReport
)

/**
 * Deterministic evidence reconstruction engine.
 *
 * Replays recorded session traces strictly as offline evidence verification,
 * never re-executing live UI actions against the device.
 */
object AutomationTraceReplayEngine {
    fun reconstruct(
        session: PersistedTraceSession,
        steps: List<ActionStepRecord>
    ): TraceReplayReport {
        val anomalies = mutableListOf<String>()
        val orderedSteps = steps.sortedBy { it.sequence }
        val reconstructedSteps = mutableListOf<ReconstructedStepEvidence>()

        var expectedMinSequence = 1
        for (step in orderedSteps) {
            if (step.sequence < expectedMinSequence) {
                anomalies += "Non-monotonic step sequence at step ${step.sequence}"
            }
            expectedMinSequence = step.sequence + 1

            val transport = runCatching {
                UiTransportStatus.valueOf(step.transportStatus.uppercase())
            }.getOrDefault(UiTransportStatus.ACCEPTED)

            val verification = step.goalVerificationStatus?.let { raw ->
                runCatching { UiVerificationStatus.valueOf(raw.uppercase()) }.getOrNull()
            } ?: step.verificationStatus?.let { raw ->
                runCatching { UiVerificationStatus.valueOf(raw.uppercase()) }.getOrNull()
            } ?: UiVerificationStatus.UNVERIFIABLE

            val beforeFp = step.beforeStateFingerprint ?: step.observation
            val afterFp = step.afterStateFingerprint ?: step.postActionObservation
            val transitioned = beforeFp != null && afterFp != null &&
                afterFp != "unavailable" && beforeFp != afterFp

            val leaseAuthorized = step.leaseValidationOutcome.equals("authorized", ignoreCase = true) ||
                step.leaseValidationOutcome.equals("not_required", ignoreCase = true)

            var stepAnomaly: String? = null
            if (!leaseAuthorized && verification == UiVerificationStatus.GOAL_VERIFIED) {
                stepAnomaly = "Step ${step.sequence} claimed GOAL_VERIFIED despite rejected lease (${step.leaseValidationOutcome})"
                anomalies += stepAnomaly
            } else if (!transport.isAccepted && verification == UiVerificationStatus.GOAL_VERIFIED) {
                stepAnomaly = "Step ${step.sequence} claimed GOAL_VERIFIED despite unaccepted transport (${transport.name})"
                anomalies += stepAnomaly
            } else if (verification == UiVerificationStatus.GOAL_VERIFIED &&
                (step.evidenceCategory.isNullOrBlank() ||
                    step.evidenceCategory in setOf("screen_transition", "state_transition", "insufficient_evidence", "observation_unavailable"))
            ) {
                stepAnomaly = "Step ${step.sequence} claimed GOAL_VERIFIED with generic or missing evidence category '${step.evidenceCategory}'"
                anomalies += stepAnomaly
            } else if ((afterFp == null || afterFp == "unavailable") && verification == UiVerificationStatus.GOAL_VERIFIED) {
                stepAnomaly = "Step ${step.sequence} claimed GOAL_VERIFIED without post-action state fingerprint"
                anomalies += stepAnomaly
            }

            reconstructedSteps += ReconstructedStepEvidence(
                sequence = step.sequence,
                checkpointId = step.checkpointId,
                observationId = step.observationId,
                beforeStateFingerprint = beforeFp,
                afterStateFingerprint = afterFp,
                intendedAction = step.action,
                sanitizedTarget = step.target,
                leaseValidationOutcome = step.leaseValidationOutcome,
                transportStatus = transport,
                postconditionStatus = step.postconditionStatus,
                verificationStatus = if (stepAnomaly != null) UiVerificationStatus.UNVERIFIABLE else verification,
                evidenceCategory = step.evidenceCategory,
                failureClassification = step.failureClassification,
                stateTransitioned = transitioned,
                evidenceConsistentWithClaim = stepAnomaly == null,
                anomaly = stepAnomaly
            )
        }

        val reconstructedTerminalStatus = when {
            session.status.equals("interrupted", ignoreCase = true) -> {
                if (reconstructedSteps.lastOrNull()?.verificationStatus == UiVerificationStatus.GOAL_VERIFIED) {
                    UiVerificationStatus.GOAL_VERIFIED
                } else {
                    UiVerificationStatus.UNVERIFIABLE
                }
            }
            reconstructedSteps.isEmpty() -> UiVerificationStatus.UNVERIFIABLE
            reconstructedSteps.any { !it.evidenceConsistentWithClaim } -> UiVerificationStatus.UNVERIFIABLE
            else -> reconstructedSteps.last().verificationStatus
        }

        if (session.verificationStatus == UiVerificationStatus.GOAL_VERIFIED &&
            reconstructedTerminalStatus != UiVerificationStatus.GOAL_VERIFIED
        ) {
            anomalies += "Session claimed GOAL_VERIFIED, but reconstructed evidence only supports $reconstructedTerminalStatus"
        }

        return TraceReplayReport(
            sessionId = session.sessionId,
            schemaVersion = session.schemaVersion,
            sessionStatus = session.status,
            claimedVerificationStatus = session.verificationStatus,
            reconstructedVerificationStatus = reconstructedTerminalStatus,
            terminationReason = session.terminationReason,
            stepCount = reconstructedSteps.size,
            totalRecoveryAttempts = orderedSteps.sumOf { it.retryCount },
            reconstructedSteps = reconstructedSteps,
            integrityVerified = anomalies.isEmpty(),
            anomalies = anomalies,
            deterministicReconstructionOnly = true,
            liveUiActionsExecuted = 0
        )
    }
}

/**
 * Persistent, queryable SQLite trace store with atomic step recording, schema versioning,
 * bounded retention, automatic sensitive-data redaction, opt-in screenshot storage, and
 * process-restart recovery.
 */
class AutomationTraceStore(
    private val dbHelper: DatabaseHelper,
    private val screenshotDir: File? = null,
    @Volatile var retentionPolicy: TraceRetentionPolicy = TraceRetentionPolicy(),
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()

    companion object {
        const val TRACE_SCHEMA_VERSION = 2

        @Volatile
        private var defaultInstance: AutomationTraceStore? = null

        fun getInstance(
            context: Context,
            retentionPolicy: TraceRetentionPolicy = TraceRetentionPolicy()
        ): AutomationTraceStore {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: AutomationTraceStore(
                    dbHelper = DatabaseHelper(context.applicationContext),
                    screenshotDir = File(context.applicationContext.filesDir, "automation_traces/screenshots"),
                    retentionPolicy = retentionPolicy
                ).also { defaultInstance = it }
            }
        }
    }

    fun startOrUpdateSession(
        sessionId: String,
        rawGoal: String,
        checkpointId: String? = null,
        status: String = "running",
        verificationStatus: UiVerificationStatus = UiVerificationStatus.UNVERIFIABLE,
        terminationReason: String? = null
    ) {
        val timestamp = nowMs()
        val goalDigest = AutomationTraceRedactor.redactGoalDigest(rawGoal)
        synchronized(lock) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                val existing = querySessionLocked(db, sessionId)
                val values = ContentValues().apply {
                    put("session_id", sessionId)
                    put("schema_version", TRACE_SCHEMA_VERSION)
                    put("goal_digest", goalDigest)
                    put("checkpoint_id", checkpointId ?: existing?.checkpointId)
                    put("status", status)
                    put("verification_status", verificationStatus.name.lowercase())
                    put("termination_reason", AutomationTraceRedactor.scrubDiagnosticText(terminationReason ?: existing?.terminationReason))
                    put("step_count", existing?.stepCount ?: 0)
                    put("recovery_attempt_count", existing?.recoveryAttemptCount ?: 0)
                    put("last_state_fingerprint", existing?.lastStateFingerprint)
                    put("last_package", existing?.lastPackage)
                    put("last_window", existing?.lastWindow)
                    put("screenshot_opt_in", if (retentionPolicy.screenshotOptIn) 1 else 0)
                    put("created_at_ms", existing?.createdAtMs ?: timestamp)
                    put("updated_at_ms", timestamp)
                }
                db.insertWithOnConflict(
                    DatabaseHelper.TABLE_AUTOMATION_TRACE_SESSIONS,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                enforceRetentionLocked(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * Atomically records a step record and updates the owning session summary row.
     */
    fun recordStep(
        sessionId: String,
        step: ActionStepRecord,
        rawGoalForInit: String = ""
    ) {
        val timestamp = step.recordedAtMs.takeIf { it > 0L } ?: nowMs()
        val sanitizedTarget = AutomationTraceRedactor.sanitizeTarget(step.target)
        val sanitizedRecovery = AutomationTraceRedactor.scrubDiagnosticText(step.recoverySummary)
        val sanitizedTermination = AutomationTraceRedactor.scrubDiagnosticText(step.terminationReason)
        val effectiveScreenshotRef = if (retentionPolicy.screenshotOptIn) {
            step.screenshotArtifactRef
        } else {
            null
        }
        val effectiveVerification = (step.goalVerificationStatus ?: step.verificationStatus ?: "unverifiable").lowercase()

        synchronized(lock) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                val existingSession = querySessionLocked(db, sessionId)
                if (existingSession == null) {
                    val initValues = ContentValues().apply {
                        put("session_id", sessionId)
                        put("schema_version", TRACE_SCHEMA_VERSION)
                        put("goal_digest", AutomationTraceRedactor.redactGoalDigest(rawGoalForInit))
                        put("checkpoint_id", step.checkpointId)
                        put("status", "running")
                        put("verification_status", effectiveVerification)
                        put("step_count", 0)
                        put("recovery_attempt_count", 0)
                        put("screenshot_opt_in", if (retentionPolicy.screenshotOptIn) 1 else 0)
                        put("created_at_ms", timestamp)
                        put("updated_at_ms", timestamp)
                    }
                    db.insertWithOnConflict(
                        DatabaseHelper.TABLE_AUTOMATION_TRACE_SESSIONS,
                        null,
                        initValues,
                        SQLiteDatabase.CONFLICT_IGNORE
                    )
                }

                val stepValues = ContentValues().apply {
                    put("session_id", sessionId)
                    put("step_sequence", step.sequence)
                    put("checkpoint_id", step.checkpointId)
                    put("observation_id", step.observationId)
                    put("before_fingerprint", step.beforeStateFingerprint ?: step.observation)
                    put("after_fingerprint", step.afterStateFingerprint ?: step.postActionObservation)
                    put("intended_action", step.action)
                    put("sanitized_target", sanitizedTarget)
                    put("lease_validation_outcome", step.leaseValidationOutcome)
                    put("execution_result", step.result)
                    put("transport_status", step.transportStatus)
                    put("postcondition_status", step.postconditionStatus)
                    put("verification_status", effectiveVerification)
                    put("evidence_category", step.evidenceCategory)
                    put("failure_classification", step.failureClassification)
                    put("observation_ms", step.observationLatencyMs.coerceAtLeast(0L))
                    put("model_decision_ms", step.modelDecisionLatencyMs.coerceAtLeast(0L))
                    put("execution_ms", step.executionLatencyMs.coerceAtLeast(0L))
                    put("verification_ms", step.verificationLatencyMs.coerceAtLeast(0L))
                    put("total_latency_ms", step.latencyMs.coerceAtLeast(0L))
                    put("recovery_attempts", step.retryCount.coerceAtLeast(0))
                    put("recovery_summary", sanitizedRecovery)
                    put("termination_reason", sanitizedTermination)
                    put("source_package", step.sourcePackage)
                    put("result_package", step.resultPackage)
                    put("source_window", step.sourceWindow)
                    put("result_window", step.resultWindow)
                    put("screenshot_ref", effectiveScreenshotRef)
                    put("recorded_at_ms", timestamp)
                }
                db.insertWithOnConflict(
                    DatabaseHelper.TABLE_AUTOMATION_TRACE_STEPS,
                    null,
                    stepValues,
                    SQLiteDatabase.CONFLICT_REPLACE
                )

                // Enforce bounded steps per session
                db.execSQL(
                    """
                    DELETE FROM ${DatabaseHelper.TABLE_AUTOMATION_TRACE_STEPS}
                    WHERE session_id = ? AND _id NOT IN (
                        SELECT _id FROM ${DatabaseHelper.TABLE_AUTOMATION_TRACE_STEPS}
                        WHERE session_id = ?
                        ORDER BY step_sequence DESC
                        LIMIT ?
                    )
                    """.trimIndent(),
                    arrayOf<Any>(sessionId, sessionId, retentionPolicy.maxStepsPerSession)
                )

                // Recompute session aggregates transactionally
                val stepCount = countForSessionLocked(db, DatabaseHelper.TABLE_AUTOMATION_TRACE_STEPS, sessionId)
                val totalRetries = sumRetriesForSessionLocked(db, sessionId)
                val sessionUpdate = ContentValues().apply {
                    step.checkpointId?.let { put("checkpoint_id", it) }
                    put("verification_status", effectiveVerification)
                    put("step_count", stepCount)
                    put("recovery_attempt_count", totalRetries)
                    put("last_state_fingerprint", step.afterStateFingerprint ?: step.postActionObservation ?: step.beforeStateFingerprint)
                    put("last_package", step.resultPackage ?: step.sourcePackage)
                    put("last_window", step.resultWindow ?: step.sourceWindow)
                    sanitizedTermination?.let { put("termination_reason", it) }
                    put("updated_at_ms", timestamp)
                }
                db.update(
                    DatabaseHelper.TABLE_AUTOMATION_TRACE_SESSIONS,
                    sessionUpdate,
                    "session_id = ?",
                    arrayOf(sessionId)
                )

                enforceRetentionLocked(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    internal fun recordEvent(event: AutomationTraceEvent) {
        val timestamp = nowMs()
        val json = event.toJson()
        synchronized(lock) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                val values = ContentValues().apply {
                    put("session_id", event.sessionId)
                    put("event_sequence", event.sequence)
                    put("elapsed_ms", event.elapsedMs)
                    put("event_name", event.name)
                    put("payload_json", json)
                    put("recorded_at_ms", timestamp)
                }
                db.insertWithOnConflict(
                    DatabaseHelper.TABLE_AUTOMATION_TRACE_EVENTS,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                db.execSQL(
                    """
                    DELETE FROM ${DatabaseHelper.TABLE_AUTOMATION_TRACE_EVENTS}
                    WHERE session_id = ? AND _id NOT IN (
                        SELECT _id FROM ${DatabaseHelper.TABLE_AUTOMATION_TRACE_EVENTS}
                        WHERE session_id = ?
                        ORDER BY event_sequence DESC
                        LIMIT ?
                    )
                    """.trimIndent(),
                    arrayOf<Any>(event.sessionId, event.sessionId, retentionPolicy.maxEventsPerSession)
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun finishSession(
        sessionId: String,
        status: String,
        verificationStatus: UiVerificationStatus,
        terminationReason: String? = null
    ) {
        val timestamp = nowMs()
        val sanitizedReason = AutomationTraceRedactor.scrubDiagnosticText(terminationReason)
        synchronized(lock) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                val values = ContentValues().apply {
                    put("status", status)
                    put("verification_status", verificationStatus.name.lowercase())
                    if (sanitizedReason != null) {
                        put("termination_reason", sanitizedReason)
                    }
                    put("updated_at_ms", timestamp)
                }
                db.update(
                    DatabaseHelper.TABLE_AUTOMATION_TRACE_SESSIONS,
                    values,
                    "session_id = ?",
                    arrayOf(sessionId)
                )
                enforceRetentionLocked(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * Saves a screenshot artifact only if [TraceRetentionPolicy.screenshotOptIn] is explicitly enabled.
     * Returns the private artifact path or null when disabled.
     */
    fun saveScreenshotIfOptedIn(
        sessionId: String,
        stepSequence: Int,
        pngBytes: ByteArray
    ): String? {
        if (!retentionPolicy.screenshotOptIn) return null
        val rootDir = screenshotDir ?: return null
        synchronized(lock) {
            val sessionDir = File(rootDir, sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_"))
            if (!sessionDir.exists()) {
                sessionDir.mkdirs()
                sessionDir.setReadable(false, false)
                sessionDir.setReadable(true, true)
                sessionDir.setWritable(false, false)
                sessionDir.setWritable(true, true)
            }
            val targetFile = File(sessionDir, "step_${stepSequence}.png")
            targetFile.writeBytes(pngBytes)
            targetFile.setReadable(false, false)
            targetFile.setReadable(true, true)
            targetFile.setWritable(false, false)
            targetFile.setWritable(true, true)
            return targetFile.absolutePath
        }
    }

    /**
     * Marks any previously running sessions without an attached active runner as `interrupted`
     * after a process restart, preserving their recorded steps and returning all recovered sessions.
     */
    fun recoverAfterProcessRestart(
        activeRunnerSessionIds: Set<String> = emptySet()
    ): List<PersistedTraceSession> {
        val timestamp = nowMs()
        synchronized(lock) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                val runningSessions = querySessionsByStatusLocked(db, "running", limit = retentionPolicy.maxSessions)
                for (running in runningSessions) {
                    if (running.sessionId !in activeRunnerSessionIds) {
                        val nextVerification = if (running.verificationStatus == UiVerificationStatus.GOAL_VERIFIED) {
                            UiVerificationStatus.GOAL_VERIFIED
                        } else {
                            UiVerificationStatus.UNVERIFIABLE
                        }
                        val update = ContentValues().apply {
                            put("status", "interrupted")
                            put("verification_status", nextVerification.name.lowercase())
                            put("termination_reason", running.terminationReason ?: "process_restart")
                            put("updated_at_ms", timestamp)
                        }
                        db.update(
                            DatabaseHelper.TABLE_AUTOMATION_TRACE_SESSIONS,
                            update,
                            "session_id = ?",
                            arrayOf(running.sessionId)
                        )
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return listSessions(limit = retentionPolicy.maxSessions)
        }
    }

    fun getSession(sessionId: String): PersistedTraceSession? = synchronized(lock) {
        querySessionLocked(dbHelper.readableDatabase, sessionId)
    }

    fun listSessions(
        statusFilter: String? = null,
        limit: Int = retentionPolicy.maxSessions
    ): List<PersistedTraceSession> = synchronized(lock) {
        val db = dbHelper.readableDatabase
        if (statusFilter != null) {
            querySessionsByStatusLocked(db, statusFilter, limit)
        } else {
            val list = mutableListOf<PersistedTraceSession>()
            db.query(
                DatabaseHelper.TABLE_AUTOMATION_TRACE_SESSIONS,
                null,
                null,
                null,
                null,
                null,
                "updated_at_ms DESC",
                limit.toString()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    list += readSessionCursor(cursor)
                }
            }
            list
        }
    }

    fun getSteps(
        sessionId: String,
        verificationStatusFilter: UiVerificationStatus? = null,
        failureClassificationFilter: String? = null
    ): List<ActionStepRecord> = synchronized(lock) {
        val db = dbHelper.readableDatabase
        val selectionParts = mutableListOf("session_id = ?")
        val args = mutableListOf(sessionId)
        if (verificationStatusFilter != null) {
            selectionParts += "verification_status = ?"
            args += verificationStatusFilter.name.lowercase()
        }
        if (failureClassificationFilter != null) {
            selectionParts += "failure_classification = ?"
            args += failureClassificationFilter
        }
        val list = mutableListOf<ActionStepRecord>()
        db.query(
            DatabaseHelper.TABLE_AUTOMATION_TRACE_STEPS,
            null,
            selectionParts.joinToString(" AND "),
            args.toTypedArray(),
            null,
            null,
            "step_sequence ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list += readStepCursor(cursor)
            }
        }
        list
    }

    internal fun getEvents(sessionId: String): List<AutomationTraceEvent> = synchronized(lock) {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<AutomationTraceEvent>()
        db.query(
            DatabaseHelper.TABLE_AUTOMATION_TRACE_EVENTS,
            null,
            "session_id = ?",
            arrayOf(sessionId),
            null,
            null,
            "event_sequence ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val seq = cursor.getInt(cursor.getColumnIndexOrThrow("event_sequence"))
                val elapsed = cursor.getLong(cursor.getColumnIndexOrThrow("elapsed_ms"))
                val name = cursor.getString(cursor.getColumnIndexOrThrow("event_name"))
                val payload = cursor.getString(cursor.getColumnIndexOrThrow("payload_json"))
                val parsedFields = runCatching {
                    val obj = JsonParser.parseString(payload).asJsonObject.getAsJsonObject("fields")
                    obj?.entrySet()?.associate { it.key to it.value } ?: emptyMap()
                }.getOrDefault(emptyMap())
                list += AutomationTraceEvent(
                    sessionId = sessionId,
                    sequence = seq,
                    elapsedMs = elapsed,
                    name = name,
                    fields = parsedFields
                )
            }
        }
        list
    }

    /**
     * Local session-inspection interface modeled on ARTEMIS `inspect_trace`.
     */
    fun inspectSession(sessionId: String): SessionInspectionReport? = synchronized(lock) {
        val session = getSession(sessionId) ?: return null
        val steps = getSteps(sessionId)
        val eventsCount = countForSessionLocked(
            dbHelper.readableDatabase,
            DatabaseHelper.TABLE_AUTOMATION_TRACE_EVENTS,
            sessionId
        )
        val timingSummary = mapOf(
            "observation_ms" to steps.sumOf { it.observationLatencyMs },
            "model_decision_ms" to steps.sumOf { it.modelDecisionLatencyMs },
            "execution_ms" to steps.sumOf { it.executionLatencyMs },
            "verification_ms" to steps.sumOf { it.verificationLatencyMs },
            "total_step_latency_ms" to steps.sumOf { it.latencyMs }
        )
        val failureBreakdown = steps
            .mapNotNull { it.failureClassification }
            .groupingBy { it }
            .eachCount()
        val replay = AutomationTraceReplayEngine.reconstruct(session, steps)
        SessionInspectionReport(
            session = session,
            steps = steps,
            eventsCount = eventsCount,
            timingSummaryMs = timingSummary,
            failureBreakdown = failureBreakdown,
            replayReport = replay
        )
    }

    fun pruneNow() {
        synchronized(lock) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                enforceRetentionLocked(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun enforceRetentionLocked(db: SQLiteDatabase) {
        val cutoffMs = nowMs() - retentionPolicy.maxAgeMs
        val expiredSessionIds = mutableSetOf<String>()

        // 1. Collect sessions older than maxAgeMs
        db.query(
            DatabaseHelper.TABLE_AUTOMATION_TRACE_SESSIONS,
            arrayOf("session_id"),
            "updated_at_ms < ?",
            arrayOf(cutoffMs.toString()),
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                expiredSessionIds += cursor.getString(0)
            }
        }

        // 2. Collect sessions exceeding maxSessions (ordered newest first)
        db.query(
            DatabaseHelper.TABLE_AUTOMATION_TRACE_SESSIONS,
            arrayOf("session_id"),
            null,
            null,
            null,
            null,
            "updated_at_ms DESC"
        ).use { cursor ->
            var index = 0
            while (cursor.moveToNext()) {
                if (index >= retentionPolicy.maxSessions) {
                    expiredSessionIds += cursor.getString(0)
                }
                index++
            }
        }

        for (expiredId in expiredSessionIds) {
            deleteSessionLocked(db, expiredId)
        }
    }

    private fun deleteSessionLocked(db: SQLiteDatabase, sessionId: String) {
        db.delete(DatabaseHelper.TABLE_AUTOMATION_TRACE_STEPS, "session_id = ?", arrayOf(sessionId))
        db.delete(DatabaseHelper.TABLE_AUTOMATION_TRACE_EVENTS, "session_id = ?", arrayOf(sessionId))
        db.delete(DatabaseHelper.TABLE_AUTOMATION_TRACE_SESSIONS, "session_id = ?", arrayOf(sessionId))
        screenshotDir?.let { root ->
            val dir = File(root, sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_"))
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    private fun querySessionLocked(db: SQLiteDatabase, sessionId: String): PersistedTraceSession? {
        db.query(
            DatabaseHelper.TABLE_AUTOMATION_TRACE_SESSIONS,
            null,
            "session_id = ?",
            arrayOf(sessionId),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) readSessionCursor(cursor) else null
        }
    }

    private fun querySessionsByStatusLocked(
        db: SQLiteDatabase,
        status: String,
        limit: Int
    ): List<PersistedTraceSession> {
        val list = mutableListOf<PersistedTraceSession>()
        db.query(
            DatabaseHelper.TABLE_AUTOMATION_TRACE_SESSIONS,
            null,
            "status = ?",
            arrayOf(status),
            null,
            null,
            "updated_at_ms DESC",
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list += readSessionCursor(cursor)
            }
        }
        return list
    }

    private fun countForSessionLocked(db: SQLiteDatabase, table: String, sessionId: String): Int {
        db.rawQuery("SELECT COUNT(*) FROM $table WHERE session_id = ?", arrayOf(sessionId)).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun sumRetriesForSessionLocked(db: SQLiteDatabase, sessionId: String): Int {
        db.rawQuery(
            "SELECT COALESCE(SUM(recovery_attempts), 0) FROM ${DatabaseHelper.TABLE_AUTOMATION_TRACE_STEPS} WHERE session_id = ?",
            arrayOf(sessionId)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun readSessionCursor(cursor: Cursor): PersistedTraceSession {
        val rawVer = cursor.getString(cursor.getColumnIndexOrThrow("verification_status")).orEmpty()
        val verificationStatus = runCatching {
            UiVerificationStatus.valueOf(rawVer.uppercase())
        }.getOrDefault(UiVerificationStatus.UNVERIFIABLE)
        return PersistedTraceSession(
            sessionId = cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
            schemaVersion = cursor.getInt(cursor.getColumnIndexOrThrow("schema_version")),
            goalDigest = cursor.getString(cursor.getColumnIndexOrThrow("goal_digest")),
            checkpointId = cursor.getStringOrNull("checkpoint_id"),
            status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
            verificationStatus = verificationStatus,
            terminationReason = cursor.getStringOrNull("termination_reason"),
            stepCount = cursor.getInt(cursor.getColumnIndexOrThrow("step_count")),
            recoveryAttemptCount = cursor.getInt(cursor.getColumnIndexOrThrow("recovery_attempt_count")),
            lastStateFingerprint = cursor.getStringOrNull("last_state_fingerprint"),
            lastPackage = cursor.getStringOrNull("last_package"),
            lastWindow = cursor.getStringOrNull("last_window"),
            screenshotOptIn = cursor.getInt(cursor.getColumnIndexOrThrow("screenshot_opt_in")) != 0,
            createdAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_ms")),
            updatedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at_ms"))
        )
    }

    private fun readStepCursor(cursor: Cursor): ActionStepRecord {
        val sequence = cursor.getInt(cursor.getColumnIndexOrThrow("step_sequence"))
        val beforeFp = cursor.getStringOrNull("before_fingerprint").orEmpty()
        val afterFp = cursor.getStringOrNull("after_fingerprint")
        val verificationStatus = cursor.getStringOrNull("verification_status")
        val evidenceCategory = cursor.getStringOrNull("evidence_category")
        return ActionStepRecord(
            sequence = sequence,
            observation = beforeFp,
            reasoningSummary = evidenceCategory ?: "persisted_step",
            action = cursor.getString(cursor.getColumnIndexOrThrow("intended_action")),
            target = cursor.getStringOrNull("sanitized_target"),
            result = cursor.getString(cursor.getColumnIndexOrThrow("execution_result")),
            postActionObservation = afterFp,
            verification = evidenceCategory,
            latencyMs = cursor.getLong(cursor.getColumnIndexOrThrow("total_latency_ms")),
            retryCount = cursor.getInt(cursor.getColumnIndexOrThrow("recovery_attempts")),
            sourcePackage = cursor.getStringOrNull("source_package"),
            resultPackage = cursor.getStringOrNull("result_package"),
            actionFamily = null,
            semanticAction = cursor.getString(cursor.getColumnIndexOrThrow("intended_action")),
            executionMechanism = null,
            verificationStatus = verificationStatus,
            sourceWindow = cursor.getStringOrNull("source_window"),
            resultWindow = cursor.getStringOrNull("result_window"),
            transportStatus = cursor.getStringOrNull("transport_status") ?: UiTransportStatus.ACCEPTED.name.lowercase(),
            postconditionStatus = cursor.getStringOrNull("postcondition_status"),
            goalVerificationStatus = verificationStatus,
            evidenceCategory = evidenceCategory,
            checkpointId = cursor.getStringOrNull("checkpoint_id"),
            sessionId = cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
            observationId = cursor.getStringOrNull("observation_id"),
            beforeStateFingerprint = beforeFp,
            afterStateFingerprint = afterFp,
            leaseValidationOutcome = cursor.getStringOrNull("lease_validation_outcome") ?: "authorized",
            failureClassification = cursor.getStringOrNull("failure_classification"),
            observationLatencyMs = cursor.getLong(cursor.getColumnIndexOrThrow("observation_ms")),
            modelDecisionLatencyMs = cursor.getLong(cursor.getColumnIndexOrThrow("model_decision_ms")),
            executionLatencyMs = cursor.getLong(cursor.getColumnIndexOrThrow("execution_ms")),
            verificationLatencyMs = cursor.getLong(cursor.getColumnIndexOrThrow("verification_ms")),
            recoverySummary = cursor.getStringOrNull("recovery_summary"),
            terminationReason = cursor.getStringOrNull("termination_reason"),
            screenshotArtifactRef = cursor.getStringOrNull("screenshot_ref"),
            recordedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("recorded_at_ms"))
        )
    }

    private fun Cursor.getStringOrNull(columnName: String): String? {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }
}

internal class AutomationTraceRecorder(
    private val sessionId: String,
    private val logger: (String) -> Unit,
    private val onEvent: (AutomationTraceEvent) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val traceStore: AutomationTraceStore? = null
) {
    private val sequence = AtomicInteger(0)
    private val startedAtMs = nowMs()

    fun emit(name: String, fields: Map<String, Any?> = emptyMap()) {
        val event = AutomationTraceEvent(
            sessionId = sessionId,
            sequence = sequence.getAndIncrement(),
            elapsedMs = (nowMs() - startedAtMs).coerceAtLeast(0),
            name = name,
            fields = fields
        )
        logger("UiAutomationTrace ${event.toJson()}")
        runCatching { traceStore?.recordEvent(event) }
        onEvent(event)
    }

    fun recordStep(step: ActionStepRecord, rawGoalForInit: String = "") {
        runCatching {
            traceStore?.recordStep(
                sessionId = sessionId,
                step = step.copy(sessionId = sessionId),
                rawGoalForInit = rawGoalForInit
            )
        }
    }
}

package com.mewmix.nabu.uiagent

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmImageInput
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.chat.LiteRtLmBackend
import com.mewmix.nabu.chat.LlmToolDefinition
import com.mewmix.nabu.accessibility.AccessibilityToolHandler
import com.mewmix.nabu.accessibility.UiSnapshotStore
import com.mewmix.nabu.tools.ToolCall
import com.mewmix.nabu.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest
import com.mewmix.nabu.actions.DeviceAction
import java.util.UUID

class UiAutomationOrchestrator(
    private val context: Context,
    private val backend: LlmBackend? = null,
    private val backendProvider: suspend () -> LlmBackend? = { backend },
    private val backendReadyProvider: () -> Boolean = { backend != null },
    private val onBackendReleased: () -> Unit = {},
    private val requestConfirmation: suspend (String) -> Boolean,
    private val budget: AutomationBudget = AutomationBudget(),
    private val isScheduled: Boolean = false,
    private val priorTurns: List<ActionConversationTurn> = emptyList(),
    private val onProgress: (phase: String, detail: String) -> Unit = { _, _ -> },
    private val onModelOutput: (String) -> Unit = {},
    private val onStepRecord: ((ActionStepRecord) -> Unit)? = null,
    private val onUserQuestion: suspend (String) -> Unit = {},
    private val onUserQuestionCleared: suspend () -> Unit = {},
    private val externalSessionId: String? = null,
    private val onRuntimeEvent: (
        name: String,
        elapsedMs: Long,
        fields: Map<String, Any?>
    ) -> Unit = { _, _, _ -> },
    private val logger: (String) -> Unit = {}
) {
    private data class Observation(
        val bridgeObservationId: String,
        val snapshotSequence: Long,
        val screen: UiScreenState,
        val screenshotPath: String?,
        val artifactPaths: List<String>
    )

    private data class PendingCapture(val uri: Uri, val mimeType: String)
    private data class PendingExternalEffect(
        val targetPackage: String,
        val expectedDestination: String,
        val contentHash: String
    )

    private val pendingArtifactPaths = linkedSetOf<String>()
    private val pendingMediaUris = linkedSetOf<Uri>()
    private var pendingCapture: PendingCapture? = null
    private var pendingExternalEffect: PendingExternalEffect? = null
    private var activeTrace: AutomationTraceRecorder? = null
    private var activeSessionId: String? = null
    private var invocationStartedMonotonicMs: Long = 0L
    private var firstPhysicalActionRecorded: Boolean = false
    private var activeBackend: LlmBackend? = backend
    private var modelRoutePublished: Boolean = false
    private var actionModelUnavailable: Boolean = false
    private var appResolutionDurationMs: Long = 0L
    private var backendLeaseAcquired: Boolean = false

    suspend fun run(goal: String): ToolResult {
        invocationStartedMonotonicMs = monotonicNowMs()
        firstPhysicalActionRecorded = false
        val sessionId = externalSessionId ?: UUID.randomUUID().toString()
        val trace = AutomationTraceRecorder(
            sessionId = sessionId,
            logger = logger,
            onEvent = { event ->
                onRuntimeEvent(event.name, event.elapsedMs, event.fields)
            }
        )
        trace.emit("session_requested", mapOf("goal" to goal, "scheduled" to isScheduled))
        trace.emit("request_received")
        trace.emit("routing_started")
        if (!sessionMutex.tryLock()) {
            onProgress("Queue", "Waiting for the active device-control session to finish")
            trace.emit("session_queued", mapOf("reason_code" to "device_control_owned"))
            sessionMutex.lock()
            trace.emit("session_dequeued")
        }
        activeTrace = trace
        activeSessionId = sessionId
        var terminalResult: ToolResult? = null
        return try {
            if (goal.isBlank()) {
                trace.emit("session_preflight_failed", mapOf("reason_code" to "blank_goal"))
                return failure("UI automation goal is blank.").also { terminalResult = it }
            }
            if (isDeviceLocked()) {
                trace.emit("session_preflight_failed", mapOf("reason_code" to "device_locked"))
                return failure("UI automation requires the device to be awake and unlocked.").also {
                    terminalResult = it
                }
            }
            val availabilityProbe = AccessibilityToolHandler.probeControlPlane(
                context,
                captureSnapshot = false
            )
            availabilityProbe.failure?.let { detail ->
                logger("UiAutomation preflight failed before routing: $detail")
                trace.emit(
                    "session_preflight_failed",
                    mapOf("reason_code" to "accessibility_control_plane_unavailable")
                )
                return failure(detail).also { terminalResult = it }
            }
            val appResolutionStartedMs = monotonicNowMs()
            val service = com.mewmix.nabu.accessibility.NabuAccessibilityService.instance
            val trustedEventSnapshot = UiSnapshotStore.currentSnapshot.value
                ?.takeIf { System.currentTimeMillis() - it.capturedAtMs in 0..TRUSTED_EVENT_MAX_AGE_MS }
            val requestResolution = DirectRequestResolver.resolve(
                request = goal,
                apps = DeviceAction.launchableAppIndex(context),
                supportedSystemActions = if (trustedEventSnapshot != null) {
                    service?.availableGlobalActionTokens().orEmpty()
                } else {
                    emptySet()
                }
            )
            val appResolutionMs = monotonicNowMs() - appResolutionStartedMs
            appResolutionDurationMs = appResolutionMs
            val directResolution = requestResolution as? DirectRequestResolution.Resolved
            val controlPlaneProbe = AccessibilityToolHandler.probeControlPlane(
                context,
                captureSnapshot = directResolution == null
            )
            controlPlaneProbe.failure?.let { detail ->
                logger("UiAutomation preflight failed before takeover: $detail")
                trace.emit(
                    "session_preflight_failed",
                    mapOf("reason_code" to "accessibility_control_plane_unavailable")
                )
                return failure(detail).also { terminalResult = it }
            }

            AutomationSessionManager.begin(context, sessionId, goal)
            trace.emit("session_started")
            val mustParkChat = !isScheduled && ControlSurfaceCoordinator.isChatForegrounded
            if (mustParkChat) {
                progress("Take over", "Parking Chat after acquiring the device-control channel")
                val acknowledged = ControlSurfaceCoordinator.parkAndAwait(sessionId, timeoutMs = 800L)
                trace.emit(
                    "control_surface_parked",
                    mapOf("acknowledged" to acknowledged)
                )
            }
            runWithCleanup(
                initialGoal = goal,
                sessionId = sessionId,
                initialSnapshot = controlPlaneProbe.snapshot.takeUnless { mustParkChat },
                directResolution = directResolution,
                appResolutionMs = appResolutionMs
            ).also { result ->
                terminalResult = result
                trace.emit(
                    "session_finished",
                    mapOf(
                        "status" to if (result.isError) "failed" else "succeeded",
                        "result_length" to result.output.length,
                        "result_hash" to hashContent(result.output)
                    )
                )
            }
        } finally {
            withContext(NonCancellable) {
                cleanupArtifacts(pendingArtifactPaths.toList())
                AutomationMediaManager.cleanupAll(context, pendingMediaUris.toList())
                pendingMediaUris.clear()
                trace.emit(
                    "session_cleaned_up",
                    mapOf(
                        "remaining_artifacts" to pendingArtifactPaths.size,
                        "remaining_media" to pendingMediaUris.size
                    )
                )
                activeTrace = null
                activeSessionId = null
                if (backendLeaseAcquired) {
                    backendLeaseAcquired = false
                    onBackendReleased()
                }
                ControlSurfaceCoordinator.clear(sessionId)
                AutomationSessionManager.finish(
                    sessionId = sessionId,
                    succeeded = terminalResult?.isError == false,
                    detail = terminalResult?.output ?: "Device-control runner was cancelled."
                )
                sessionMutex.unlock()
            }
        }
    }

    private suspend fun runWithCleanup(
        initialGoal: String,
        sessionId: String,
        initialSnapshot: com.mewmix.nabu.accessibility.UiSnapshot?,
        directResolution: DirectRequestResolution.Resolved?,
        appResolutionMs: Long
    ): ToolResult {
        var goal = initialGoal
        if (goal.isBlank()) return failure("UI automation goal is blank.")
        when (val directive = AutomationSessionManager.awaitDirective(sessionId)) {
            AutomationSessionDirective.Cancel ->
                return failure("Device control was stopped by the user.")
            is AutomationSessionDirective.Run -> goal = directive.goal
        }
        if (isDeviceLocked()) {
            trace("session_blocked", mapOf("reason_code" to "device_locked"))
            return failure("UI automation requires the device to be awake and unlocked.")
        }
        var observation: Observation
        var deterministicPrefixCount = 0
        val currentEventSnapshot = UiSnapshotStore.currentSnapshot.value
            ?.takeIf { System.currentTimeMillis() - it.capturedAtMs in 0..TRUSTED_EVENT_MAX_AGE_MS }
        val executableDirectResolution = directResolution?.takeIf {
            it.action is UiActionStep.OpenApp || currentEventSnapshot != null
        }
        if (executableDirectResolution != null) {
            val fastPathStartedMs = monotonicNowMs()
            val beforeSnapshot = currentEventSnapshot
            val beforeScreen = beforeSnapshot?.let(UiTreeIndexer::build)
            trace(
                if (executableDirectResolution.completesGoalWhenVerified) {
                    "routing_completed"
                } else {
                    "deterministic_prefix_selected"
                },
                mapOf(
                    "route" to "request_fast_path",
                    "model_required" to false,
                    "model_initialization_on_critical_path" to false,
                    "app_resolution_ms" to appResolutionMs,
                    "planner_request_count" to 0
                )
            )
            progress("Execute", directRequestProgress(executableDirectResolution))
            val execution = executeRequestLevelAction(executableDirectResolution.action, goal)
            if (execution.isError) return execution
            progress("Verify", "Observing the deterministic action result")
            trace("verification_started", mapOf("route" to "request_fast_path"))
            val observedAfterDirect = observeAfterDirectAction(beforeSnapshot, executableDirectResolution.action)
            if (observedAfterDirect == null) {
                val unverifiable = GoalVerificationResult(
                    status = UiVerificationStatus.UNVERIFIABLE,
                    transportStatus = UiTransportStatus.ACCEPTED,
                    postconditionStatus = PostconditionStatus.UNVERIFIABLE,
                    checkpointId = "post_observation",
                    evidenceCategory = "observation_unavailable",
                    summary = "${actionLabel(executableDirectResolution.action)} ran (executed), but its resulting screen could not be observed (unverifiable)."
                )
                publishFastPathReceipt(
                    sequence = 1,
                    action = executableDirectResolution.action,
                    before = beforeScreen,
                    after = beforeScreen,
                    verificationResult = unverifiable,
                    mechanism = if (executableDirectResolution.action is UiActionStep.OpenApp) "trusted_intent" else "global_action",
                    startedMs = fastPathStartedMs
                )
                return failure(unverifiable.summary)
            }
            observation = observedAfterDirect
            val verification = verifyRequestLevelAction(
                goal = goal,
                action = executableDirectResolution.action,
                before = beforeScreen,
                after = observation.screen,
                completesGoalWhenVerified = executableDirectResolution.completesGoalWhenVerified
            )
            trace(
                "verification_completed",
                mapOf(
                    "route" to "request_fast_path",
                    "verified" to verification.isGoalVerified,
                    "verification_status" to verification.status.name.lowercase(),
                    "postcondition_status" to verification.postconditionStatus.name.lowercase(),
                    "evidence_category" to verification.evidenceCategory
                )
            )
            val mechanism = if (executableDirectResolution.action is UiActionStep.OpenApp) {
                "trusted_intent"
            } else {
                "global_action"
            }
            if (executableDirectResolution.completesGoalWhenVerified && !verification.isGoalVerified) {
                publishFastPathReceipt(
                    sequence = 1,
                    action = executableDirectResolution.action,
                    before = beforeScreen,
                    after = observation.screen,
                    verificationResult = verification,
                    mechanism = mechanism,
                    startedMs = fastPathStartedMs
                )
                return failure(verification.summary)
            }
            if (!executableDirectResolution.completesGoalWhenVerified &&
                (verification.status == UiVerificationStatus.FAILED || verification.status == UiVerificationStatus.UNVERIFIABLE)
            ) {
                publishFastPathReceipt(
                    sequence = 1,
                    action = executableDirectResolution.action,
                    before = beforeScreen,
                    after = observation.screen,
                    verificationResult = verification,
                    mechanism = mechanism,
                    startedMs = fastPathStartedMs
                )
                return failure(verification.summary)
            }
            publishFastPathReceipt(
                sequence = 1,
                action = executableDirectResolution.action,
                before = beforeScreen,
                after = observation.screen,
                verificationResult = verification,
                mechanism = mechanism,
                startedMs = fastPathStartedMs
            )
            deterministicPrefixCount = 1
            trace(
                "request_fast_path_verified",
                mapOf(
                    "reason" to executableDirectResolution.reason,
                    "action" to actionLabel(executableDirectResolution.action),
                    "result_package" to observation.screen.packageName,
                    "verification_status" to verification.status.name.lowercase()
                )
            )
            if (executableDirectResolution.completesGoalWhenVerified) {
                return success(verification.summary)
            }
        } else {
            progress("Observe", "Capturing the active window and accessibility tree")
            observation = observeInitial(initialSnapshot)
                ?: return failure(
                    "Nabu lost its accessibility control plane while Android was switching windows. " +
                        "Return to Nabu and invoke device control again."
                )
        }

        DirectSemanticActionResolver.resolve(goal, observation.screen)?.let { action ->
            val fastPathStartedMs = monotonicNowMs()
            trace(
                "routing_completed",
                mapOf(
                    "route" to "ui_semantic_fast_path",
                    "model_required" to false,
                    "model_initialization_on_critical_path" to false,
                    "app_resolution_ms" to appResolutionMs,
                    "planner_request_count" to 0
                )
            )
            progress("Execute", "Running ${actionLabel(action)} without model inference")
            val actionPlan = UiActionPlan(goal, observation.screen.screenId, listOf(action))
            var directObservation = observation
            var fastPathConfirmationGranted = false
            var fastPathVerifiedDestination: String? = null
            when (val decision = UiActionValidator.validate(actionPlan, observation.screen)) {
                UiPlanDecision.Allow -> Unit
                is UiPlanDecision.Invalid -> return failure(decision.reason)
                is UiPlanDecision.Block -> return failure(decision.reason)
                is UiPlanDecision.RequireConfirmation -> {
                    progress("Confirm", "Evaluating confirmation requirements")
                    val expectedDestination = expectedDestinationForCommit(action, observation.screen)
                    if (isMessagingCommitBoundary(action, observation.screen) && expectedDestination == null) {
                        return failure("Action blocked: no verified pending message is bound to this send action.")
                    }
                    val verifiedDestination = if (expectedDestination != null) {
                        when (val destination = DestinationResolver.resolve(observation.screen, expectedDestination)) {
                            is DestinationResolver.DestinationResult.Verified -> destination.observed
                            is DestinationResolver.DestinationResult.Mismatch ->
                                return failure("Destination mismatch: observed '${destination.observed}' but expected '${destination.expected}'.")
                            is DestinationResolver.DestinationResult.Unresolvable ->
                                return failure("Action blocked: ${destination.reason}")
                        }
                    } else {
                        null
                    }
                    val approved = withTimeoutOrNull(60_000L) {
                        requestConfirmation(
                            describeConfirmation(
                                action,
                                decision.reason,
                                observation.screen,
                                goal,
                                verifiedDestination
                            )
                        )
                    } ?: false
                    if (!approved) return failure("User denied UI action confirmation or timed out.")
                    val latest = observe(requestScreenshot = false)
                        ?: return failure("Failed to re-observe screen for confirmation.")
                    if (latest.screen.screenId != observation.screen.screenId) {
                        return failure("Screen changed while awaiting confirmation. Action aborted.")
                    }
                    if (verifiedDestination != null) {
                        when (val recheck = DestinationResolver.resolve(latest.screen, verifiedDestination)) {
                            is DestinationResolver.DestinationResult.Verified -> Unit
                            is DestinationResolver.DestinationResult.Mismatch ->
                                return failure("Destination changed after confirmation: observed '${recheck.observed}' but expected '${recheck.expected}'.")
                            is DestinationResolver.DestinationResult.Unresolvable ->
                                return failure("Destination unresolvable after confirmation: ${recheck.reason}")
                        }
                    }
                    directObservation = latest
                    val fingerprint = "${actionLabel(action)}|${hashContent(action.toJson().toString(), goal)}|${latest.screen.screenId}"
                    val contentHash = confirmationContentHash(action, latest.screen, goal)
                    val grantId = ConfirmationManager.requestConfirmation(
                        sessionId = sessionId,
                        screenId = latest.screen.screenId,
                        actionFingerprint = fingerprint,
                        destination = verifiedDestination,
                        contentHash = contentHash,
                        timeoutMs = 120_000L
                    )
                    if (!ConfirmationManager.consumeConfirmation(
                            grantId,
                            sessionId,
                            latest.screen.screenId,
                            fingerprint,
                            verifiedDestination,
                            contentHash
                        )
                    ) {
                        return failure("Confirmation expired or invalid.")
                    }
                    fastPathConfirmationGranted = true
                    fastPathVerifiedDestination = verifiedDestination
                }
            }
            val result = execute(action, directObservation, sessionId, goal)
            if (result.isError) return result
            val next = observeAfterAction(directObservation, action)
            if (next == null) {
                val unverifiable = GoalVerificationResult(
                    status = UiVerificationStatus.UNVERIFIABLE,
                    transportStatus = UiTransportStatus.ACCEPTED,
                    postconditionStatus = PostconditionStatus.UNVERIFIABLE,
                    checkpointId = "post_observation",
                    evidenceCategory = "observation_unavailable",
                    summary = "${actionLabel(action)} ran (executed), but the resulting screen could not be observed (unverifiable)."
                )
                publishFastPathReceipt(
                    sequence = deterministicPrefixCount + 1,
                    action = action,
                    before = directObservation.screen,
                    after = directObservation.screen,
                    verificationResult = unverifiable,
                    mechanism = "semantic_node",
                    startedMs = fastPathStartedMs
                )
                return failure(unverifiable.summary)
            }
            var finalNext = next
            var postcondition = UiActionPostconditionVerifier.verify(
                action,
                directObservation.screen,
                finalNext.screen
            )
            if (postcondition.status == PostconditionStatus.FAILED) {
                if (action is UiActionStep.PressBack || (action is UiActionStep.GlobalAction && action.action == "back")) {
                    val visibleBack = DirectSemanticActionResolver.resolveVisibleBack(directObservation.screen)
                    if (visibleBack != null) {
                        val recoveryResult = execute(visibleBack, directObservation, sessionId, goal)
                        if (!recoveryResult.isError) {
                            val nextRecovery = observeAfterAction(directObservation, visibleBack)
                            if (nextRecovery != null) {
                                val recoveryPost = UiActionPostconditionVerifier.verify(visibleBack, directObservation.screen, nextRecovery.screen)
                                if (recoveryPost.status.isMechanicalProgress) {
                                    finalNext = nextRecovery
                                    postcondition = recoveryPost
                                }
                            }
                        }
                    }
                }
            }
            val goalVerification = UiGoalVerifier.verify(
                goal = goal,
                action = action,
                before = directObservation.screen,
                after = finalNext.screen,
                transportStatus = UiTransportStatus.ACCEPTED,
                postcondition = postcondition,
                goalAppCandidates = DeviceAction.findGoalAppCandidates(context, goal),
                confirmationGranted = fastPathConfirmationGranted,
                expectedDestination = fastPathVerifiedDestination,
                hasPendingUnconfirmedEffect = pendingExternalEffect != null
            )
            publishFastPathReceipt(
                sequence = deterministicPrefixCount + 1,
                action = action,
                before = directObservation.screen,
                after = finalNext.screen,
                verificationResult = goalVerification,
                mechanism = "semantic_node",
                startedMs = fastPathStartedMs
            )
            if (!goalVerification.isGoalVerified) {
                return failure(goalVerification.summary)
            }
            trace(
                "deterministic_action_verified",
                mapOf(
                    "action" to actionLabel(action),
                    "source_screen_id" to directObservation.screen.screenId,
                    "result_screen_id" to finalNext.screen.screenId,
                    "source_package" to directObservation.screen.packageName,
                    "result_package" to finalNext.screen.packageName,
                    "verification_status" to goalVerification.status.name.lowercase(),
                    "evidence_category" to goalVerification.evidenceCategory
                )
            )
            return success(goalVerification.summary)
        }
        
        var activeDeadlineMs = monotonicNowMs() + budget.maxWallClockDurationMs
        var unchangedCount = 0
        var cumulativeWaitMs = 0L
        val actionHistory = mutableListOf<UiActionHistoryEntry>()
        val successfulFingerprints = mutableSetOf<String>()
        val actionRepetitions = mutableMapOf<String, Int>()
        var goalAppCandidates = DeviceAction.findGoalAppCandidates(context, goal)

        DeviceAction.findExplicitGoalAppCandidate(context, goal)?.let { app ->
            val launchAction = UiActionStep.OpenApp(app.packageName)
            if (!observation.screen.packageName.equals(app.packageName, ignoreCase = true)) {
                progress("Navigate", "Opening ${app.label} through the installed-app registry")
                val launchPlan = UiActionPlan(goal, observation.screen.screenId, listOf(launchAction))
                when (val decision = UiActionValidator.validate(launchPlan, observation.screen)) {
                    UiPlanDecision.Allow -> Unit
                    is UiPlanDecision.Invalid -> return failure(decision.reason)
                    is UiPlanDecision.Block -> return failure(decision.reason)
                    is UiPlanDecision.RequireConfirmation ->
                        return failure("Unexpected confirmation requirement while opening ${app.label}.")
                }
                val result = execute(launchAction, observation, sessionId, goal)
                if (result.isError) return result
                val next = observeAfterAction(observation, launchAction)
                    ?: return failure("${app.label} opened, but its screen could not be observed.")
                actionHistory.add(
                    UiActionHistoryEntry(
                        index = -1,
                        action = actionLabel(launchAction),
                        targetElementId = null,
                        targetLabel = app.label,
                        sourceScreenId = observation.screen.screenId,
                        resultScreenId = next.screen.screenId,
                        outcome = Outcome.SUCCEEDED,
                        changedScreen = next.screen.screenId != observation.screen.screenId,
                        detail = "Resolved from an exact installed-app label in the goal.",
                        sourcePackage = observation.screen.packageName,
                        resultPackage = next.screen.packageName
                    )
                )
                trace(
                    "installed_app_navigation",
                    mapOf(
                        "label" to app.label,
                        "package" to app.packageName,
                        "result_package" to next.screen.packageName
                    )
                )
                observation = next
            } else {
                actionHistory.add(
                    UiActionHistoryEntry(
                        index = -1,
                        action = actionLabel(launchAction),
                        targetElementId = null,
                        targetLabel = app.label,
                        sourceScreenId = observation.screen.screenId,
                        resultScreenId = observation.screen.screenId,
                        outcome = Outcome.SUCCEEDED,
                        changedScreen = false,
                        detail = "${app.label} was already foreground.",
                        sourcePackage = observation.screen.packageName,
                        resultPackage = observation.screen.packageName
                    )
                )
            }
        }

        val executionResult = run execution@ {
            repeat(budget.maxExecutedActions) { actionIndex ->
            val stepStartMs = monotonicNowMs()
            val directiveWaitStartedMs = monotonicNowMs()
            when (val directive = AutomationSessionManager.awaitDirective(sessionId)) {
                AutomationSessionDirective.Cancel ->
                    return@execution failure("Device control was stopped by the user.")
                is AutomationSessionDirective.Run -> {
                    if (directive.goal != goal) {
                        trace(
                            "session_steered",
                            mapOf(
                                "old_goal_hash" to hashContent(goal),
                                "new_goal_hash" to hashContent(directive.goal)
                            )
                        )
                        goal = directive.goal
                        goalAppCandidates = DeviceAction.findGoalAppCandidates(context, goal)
                        actionHistory.add(
                            UiActionHistoryEntry(
                                index = actionIndex,
                                action = "steer",
                                targetElementId = null,
                                targetLabel = null,
                                sourceScreenId = observation.screen.screenId,
                                resultScreenId = observation.screen.screenId,
                                outcome = Outcome.SUCCEEDED,
                                changedScreen = false,
                                detail = "Goal updated; all speculative downstream actions were discarded.",
                                sourcePackage = observation.screen.packageName,
                                resultPackage = observation.screen.packageName
                            )
                        )
                    }
                }
            }
            // A suspended session keeps its goal and checkpoint without consuming its active
            // execution budget. Planner/action time remains bounded by the ordinary deadline.
            activeDeadlineMs += monotonicNowMs() - directiveWaitStartedMs
            if (monotonicNowMs() >= activeDeadlineMs) {
                return@execution failure("UI automation reached its active wall-clock time limit.")
            }
            UiGoalCompletionEvaluator.evaluate(goal, observation.screen, goalAppCandidates)?.let { completion ->
                trace(
                    "goal_condition_satisfied",
                    mapOf(
                        "summary" to completion.summary,
                        "screen_id" to observation.screen.screenId,
                        "package" to observation.screen.packageName
                    )
                )
                return@execution success(completion.summary)
            }
            progress("Plan ${actionIndex + 1}", "Choosing the next UI action")
            
            var rawPlan = plan(goal, observation, actionHistory, goalAppCandidates)
                ?: return@execution failure(
                    if (actionModelUnavailable) {
                        "This request requires reasoning, but no eligible Action Model is available. " +
                            "Select or download a non-Codex Action Model in Settings."
                    } else {
                        "The UI planner returned no usable plan."
                    }
                )
            var parsedPlan = parsePlan(rawPlan, goal, observation)
            
            var retries = 0
            while (parsedPlan.isFailure && retries < budget.maxPlannerRetriesPerObservation) {
                val errorMsg = parsedPlan.exceptionOrNull()?.message
                logger(
                    "UiAutomation planner parse failed: $errorMsg; " +
                        "outputLength=${rawPlan.length} outputHash=${hashContent(rawPlan)}"
                )
                progress("Plan ${actionIndex + 1}", "Retrying with a strict JSON-only prompt")
                rawPlan = plan(goal, observation, actionHistory, goalAppCandidates, jsonRetry = true)
                    ?: return@execution failure("The UI planner returned no usable JSON after retry.")
                parsedPlan = parsePlan(rawPlan, goal, observation)
                retries++
            }
            
            val planHorizon = parsedPlan.getOrElse { error ->
                logger(
                    "UiAutomation planner retry parse failed: ${error.message}; " +
                        "outputLength=${rawPlan.length} outputHash=${hashContent(rawPlan)}"
                )
                trace(
                    "plan_parse_failed",
                    mapOf("error_type" to error::class.java.simpleName, "error_message" to error.message)
                )
                return@execution failure("The UI planner returned invalid action JSON after retry: ${error.message}")
            }
            val actionPlan = planHorizon.firstExecutionSlice()
            trace(
                "plan_parsed",
                mapOf(
                    "screen_id" to observation.screen.screenId,
                    "package" to observation.screen.packageName,
                    "action_count" to planHorizon.executableSteps.size,
                    "assertion_count" to planHorizon.steps.count { it is UiActionStep.Assert },
                    "selected_action" to actionLabel(actionPlan.executableSteps.single()),
                    "deferred_actions" to planHorizon.executableSteps.drop(1).map(::actionLabel)
                )
            )
            
            val action = actionPlan.executableSteps.single()
            val remainingAppCandidates = AutomationAppScope.remainingCandidates(
                goal = goal,
                candidates = goalAppCandidates,
                history = actionHistory
            )
            if (action is UiActionStep.OpenApp) {
                val wasResolvedForGoal = goalAppCandidates.any {
                    it.packageName.equals(action.packageName, ignoreCase = true)
                }
                val isStillRequired = remainingAppCandidates.any {
                    it.packageName.equals(action.packageName, ignoreCase = true)
                }
                val isExplicitUnresolvedPackage = !wasResolvedForGoal &&
                    goal.lowercase().contains(action.packageName.lowercase())
                if (!isStillRequired && !isExplicitUnresolvedPackage) {
                    return@execution failure(
                        "Planner requested app package '${action.packageName}' outside the remaining goal-relevant app set."
                    )
                }
            }
            val fingerprint = "${actionLabel(action)}|${hashContent(action.toJson().toString(), goal)}|${observation.screen.screenId}"
            val contentHash = confirmationContentHash(action, observation.screen, goal)
            
            var stepConfirmationGranted = false
            var stepVerifiedDestination: String? = null
            when (val decision = UiActionValidator.validate(actionPlan, observation.screen)) {
                UiPlanDecision.Allow -> Unit
                is UiPlanDecision.Invalid -> {
                    logger("UiAutomation plan rejected: ${decision.reason}")
                    return@execution failure(decision.reason)
                }
                is UiPlanDecision.Block -> {
                    logger("UiAutomation plan blocked: ${decision.reason}")
                    return@execution failure(decision.reason)
                }
                is UiPlanDecision.RequireConfirmation -> {
                    progress("Confirm", "Evaluating confirmation requirements")
                    val expectedDestination = expectedDestinationForCommit(action, observation.screen)
                    if (isMessagingCommitBoundary(action, observation.screen) && expectedDestination == null) {
                        return@execution failure("Action blocked: no verified pending message is bound to this send action.")
                    }
                    val needsDestination = expectedDestination != null
                    var verifiedDestination: String? = null
                    if (needsDestination) {
                        when (val result = DestinationResolver.resolve(observation.screen, expectedDestination)) {
                            is DestinationResolver.DestinationResult.Verified -> verifiedDestination = result.observed
                            is DestinationResolver.DestinationResult.Mismatch -> return@execution failure("Destination mismatch: observed '${result.observed}' but expected '${result.expected}'.")
                            is DestinationResolver.DestinationResult.Unresolvable -> return@execution failure("Action blocked: ${result.reason}")
                        }
                    }
                    val userApproved = kotlinx.coroutines.withTimeoutOrNull(60_000) {
                        requestConfirmation(describeConfirmation(action, decision.reason, observation.screen, goal, verifiedDestination))
                    } ?: false
                    
                    if (!userApproved) {
                        return@execution failure("User denied UI action confirmation or timed out.")
                    }
                    
                    val latestObservation = observe(requestScreenshot = false)
                        ?: return@execution failure("Failed to re-observe screen for confirmation.")
                    if (latestObservation.screen.screenId != observation.screen.screenId) {
                        return@execution failure("Screen changed while awaiting confirmation. Action aborted.")
                    }

                    // Re-verify destination after user confirmation
                    if (needsDestination && verifiedDestination != null) {
                        when (val recheck = DestinationResolver.resolve(latestObservation.screen, verifiedDestination)) {
                            is DestinationResolver.DestinationResult.Verified -> Unit
                            is DestinationResolver.DestinationResult.Mismatch -> return@execution failure("Destination changed after confirmation: observed '${recheck.observed}' but expected '${recheck.expected}'.")
                            is DestinationResolver.DestinationResult.Unresolvable -> return@execution failure("Destination unresolvable after confirmation: ${recheck.reason}")
                        }
                    }

                    val updatedFingerprint = "${actionLabel(action)}|${hashContent(action.toJson().toString(), goal)}|${latestObservation.screen.screenId}"
                    
                    // Assign latest observation before proceeding
                    observation = latestObservation
                    
                    val grantId = ConfirmationManager.requestConfirmation(
                        sessionId = sessionId,
                        screenId = latestObservation.screen.screenId,
                        actionFingerprint = updatedFingerprint,
                        destination = verifiedDestination,
                        contentHash = contentHash,
                        timeoutMs = 120_000
                    )
                    
                    if (!ConfirmationManager.consumeConfirmation(grantId, sessionId, latestObservation.screen.screenId, updatedFingerprint, verifiedDestination, contentHash)) {
                        return@execution failure("Confirmation expired or invalid.")
                    }
                    stepConfirmationGranted = true
                    stepVerifiedDestination = verifiedDestination
                }
            }
            
            val repCount = actionRepetitions.getOrDefault(fingerprint, 0)
            if (successfulFingerprints.contains(fingerprint) && unchangedCount > 0) {
                if (repCount >= budget.maxIdenticalActionUnchangedScreen) {
                    logger("UiAutomation repetion detected: fingerprint=$fingerprint")
                    return@execution failure("Planner proposed a repetitive action that already succeeded on this exact screen without changing it.")
                }
                
                logger("UiAutomation repetion warned: fingerprint=$fingerprint")
                actionHistory.add(
                    UiActionHistoryEntry(
                        index = actionIndex,
                        action = actionLabel(action),
                        targetElementId = getTargetElementId(action),
                        targetLabel = getTargetLabel(action, observation.screen),
                        sourceScreenId = observation.screen.screenId,
                        resultScreenId = observation.screen.screenId,
                        outcome = Outcome.FAILED,
                        changedScreen = false,
                        detail = "Action proposed again after succeeding without changing the screen.",
                        sourcePackage = observation.screen.packageName,
                        resultPackage = observation.screen.packageName
                    )
                )
                actionRepetitions[fingerprint] = repCount + 1
                observation = observe() ?: return@execution failure("Failed to re-observe after repetition.")
                return@repeat
            }

            progress("Decision ${actionIndex + 1}", "Planner selected ${actionLabel(action)}")
            trace(
                "action_selected",
                mapOf(
                    "index" to actionIndex,
                    "action" to actionLabel(action),
                    "screen_id" to observation.screen.screenId,
                    "package" to observation.screen.packageName
                )
            )
            var executedResult: ToolResult? = null
            when (action) {
                is UiActionStep.Done -> {
                    val terminalVerification = UiGoalVerifier.verifyTerminalDone(
                        goal = goal,
                        currentScreen = observation.screen,
                        actionHistory = actionHistory,
                        goalAppCandidates = goalAppCandidates,
                        confirmationGranted = stepConfirmationGranted,
                        expectedDestination = stepVerifiedDestination,
                        hasPendingUnconfirmedEffect = pendingExternalEffect != null
                    )
                    if (terminalVerification.isGoalVerified) {
                        trace(
                            "goal_condition_satisfied",
                            mapOf(
                                "summary" to terminalVerification.summary,
                                "screen_id" to observation.screen.screenId,
                                "package" to observation.screen.packageName,
                                "verification_status" to terminalVerification.status.name.lowercase(),
                                "evidence_category" to terminalVerification.evidenceCategory,
                                "checkpoint_id" to terminalVerification.checkpointId
                            )
                        )
                        return@execution success(terminalVerification.summary)
                    }
                    trace(
                        "done_rejected_unverified",
                        mapOf(
                            "index" to actionIndex,
                            "verification_status" to terminalVerification.status.name.lowercase(),
                            "evidence_category" to terminalVerification.evidenceCategory,
                            "checkpoint_id" to terminalVerification.checkpointId,
                            "reason" to terminalVerification.summary
                        )
                    )
                    val priorDoneRejectionsOnScreen = actionHistory.count {
                        it.action == "done" && it.sourceScreenId == observation.screen.screenId && it.outcome == Outcome.FAILED
                    }
                    actionHistory.add(
                        UiActionHistoryEntry(
                            index = actionIndex,
                            action = actionLabel(action),
                            targetElementId = null,
                            targetLabel = null,
                            sourceScreenId = observation.screen.screenId,
                            resultScreenId = observation.screen.screenId,
                            outcome = Outcome.FAILED,
                            changedScreen = false,
                            detail = "Completion rejected (${terminalVerification.status.name.lowercase()}): ${terminalVerification.summary}",
                            sourcePackage = observation.screen.packageName,
                            resultPackage = observation.screen.packageName
                        )
                    )
                    if (priorDoneRejectionsOnScreen >= 1) {
                        return@execution failure(terminalVerification.summary)
                    }
                    return@repeat
                }
                is UiActionStep.AskUser -> {
                    if (isScheduled) {
                        return@execution failure(
                            "Scheduled device control needs user input but has no interactive control surface."
                        )
                    }
                    if (!AutomationSessionManager.requestUserInput(sessionId, action.reason)) {
                        return@execution failure("Device control could not preserve its pending user question.")
                    }
                    onUserQuestion(action.reason)
                    progress("Needs input", action.reason)
                    val foregrounded = ControlSurfaceCoordinator.showAndAwait(sessionId)
                    trace(
                        "user_question_presented",
                        mapOf("foregrounded" to foregrounded, "message" to action.reason)
                    )
                    if (!foregrounded) {
                        return@execution failure(
                            "Nabu could not bring Chat forward for the required clarification."
                        )
                    }
                    val inputWaitStartedMs = monotonicNowMs()
                    val answer = AutomationSessionManager.awaitUserInput(sessionId)
                    activeDeadlineMs += monotonicNowMs() - inputWaitStartedMs
                    onUserQuestionCleared()
                    if (answer.isNullOrBlank()) {
                        return@execution failure("Device control was stopped while waiting for user input.")
                    }
                    val resumedObservation = observe()
                        ?: return@execution failure(
                            "Device control resumed, but the target screen could not be observed."
                        )
                    actionHistory.add(
                        UiActionHistoryEntry(
                            index = actionIndex,
                            action = "user input",
                            targetElementId = null,
                            targetLabel = null,
                            sourceScreenId = observation.screen.screenId,
                            resultScreenId = resumedObservation.screen.screenId,
                            outcome = Outcome.SUCCEEDED,
                            changedScreen = resumedObservation.screen.screenId != observation.screen.screenId,
                            detail = "Question: ${action.reason}\nAnswer: $answer",
                            sourcePackage = observation.screen.packageName,
                            resultPackage = resumedObservation.screen.packageName
                        )
                    )
                    trace(
                        "user_question_answered",
                        mapOf(
                            "message" to answer,
                            "result_screen_id" to resumedObservation.screen.screenId,
                            "result_package" to resumedObservation.screen.packageName
                        )
                    )
                    observation = resumedObservation
                    return@repeat
                }
                is UiActionStep.Wait -> {
                    val waitTime = action.milliseconds.coerceAtMost(budget.maxSingleWaitMs)
                    if (cumulativeWaitMs + waitTime > budget.maxCumulativeWaitMs) {
                        return@execution failure("Wait budget exceeded.")
                    }
                    cumulativeWaitMs += waitTime
                    delay(waitTime)
                    actionHistory.add(
                        UiActionHistoryEntry(
                            index = actionIndex,
                            action = actionLabel(action),
                            targetElementId = null,
                            targetLabel = null,
                            sourceScreenId = observation.screen.screenId,
                            resultScreenId = observation.screen.screenId,
                            outcome = Outcome.SUCCEEDED,
                            changedScreen = false,
                            detail = "waited ${waitTime}ms",
                            sourcePackage = observation.screen.packageName,
                            resultPackage = observation.screen.packageName
                        )
                    )
                }
                else -> {
                    progress("Execute ${actionIndex + 1}", "Running ${actionLabel(action)}")
                    val result = execute(action, observation, sessionId, goal)
                    executedResult = result
                    trace(
                        "action_executed",
                        mapOf(
                            "index" to actionIndex,
                            "action" to actionLabel(action),
                            "status" to if (result.isError) "failed" else "succeeded",
                            "transport_status" to if (result.isError) UiTransportStatus.REJECTED.name.lowercase() else UiTransportStatus.ACCEPTED.name.lowercase(),
                            "result_length" to result.output.length,
                            "result_hash" to hashContent(result.output)
                        )
                    )
                    if (result.isError) {
                        logger(
                            "UiAutomation execution failed action=${actionLabel(action)} " +
                                "resultLength=${result.output.length} resultHash=${hashContent(result.output)}"
                        )
                        actionHistory.add(
                            UiActionHistoryEntry(
                                index = actionIndex,
                                action = actionLabel(action),
                                targetElementId = getTargetElementId(action),
                                targetLabel = getTargetLabel(action, observation.screen),
                                sourceScreenId = observation.screen.screenId,
                                resultScreenId = observation.screen.screenId,
                                outcome = Outcome.FAILED,
                                changedScreen = false,
                                detail = result.output,
                                sourcePackage = observation.screen.packageName,
                                resultPackage = observation.screen.packageName
                            )
                        )
                        onStepRecord?.invoke(
                            ActionStepRecord(
                                sequence = actionIndex + 1 + deterministicPrefixCount,
                                observation = observation.screen.screenId,
                                reasoningSummary = actionLabel(action),
                                action = actionLabel(action),
                                target = getTargetLabel(action, observation.screen),
                                result = "failed",
                                postActionObservation = observation.screen.screenId,
                                verification = result.output,
                                latencyMs = monotonicNowMs() - stepStartMs,
                                sourcePackage = observation.screen.packageName,
                                resultPackage = observation.screen.packageName,
                                actionFamily = actionFamily(action),
                                semanticAction = actionLabel(action),
                                executionMechanism = executionMechanism(action, executedResult),
                                verificationStatus = UiVerificationStatus.FAILED.name.lowercase(),
                                sourceWindow = observation.screen.activityName,
                                resultWindow = observation.screen.activityName,
                                transportStatus = UiTransportStatus.REJECTED.name.lowercase(),
                                postconditionStatus = PostconditionStatus.FAILED.name.lowercase(),
                                goalVerificationStatus = UiVerificationStatus.FAILED.name.lowercase(),
                                evidenceCategory = "transport_rejected",
                                checkpointId = "transport"
                            )
                        )
                        observation = observe() ?: return@execution failure("UI action failed and screen could not be observed.")
                        return@repeat
                    }
                }
            }

            progress("Verify ${actionIndex + 1}", "Observing the resulting screen")
            trace("verification_started", mapOf("index" to actionIndex))
            val next = observeAfterAction(observation, action)
            if (next == null) {
                val unverifiableRecord = ActionStepRecord(
                    sequence = actionIndex + 1 + deterministicPrefixCount,
                    observation = observation.screen.screenId,
                    reasoningSummary = actionLabel(action),
                    action = actionLabel(action),
                    target = getTargetLabel(action, observation.screen),
                    result = "unverifiable",
                    postActionObservation = null,
                    verification = "UI action ran (executed), but the resulting screen could not be observed.",
                    latencyMs = monotonicNowMs() - stepStartMs,
                    sourcePackage = observation.screen.packageName,
                    resultPackage = null,
                    actionFamily = actionFamily(action),
                    semanticAction = actionLabel(action),
                    executionMechanism = executionMechanism(action, executedResult),
                    verificationStatus = UiVerificationStatus.UNVERIFIABLE.name.lowercase(),
                    sourceWindow = observation.screen.activityName,
                    resultWindow = null,
                    transportStatus = UiTransportStatus.ACCEPTED.name.lowercase(),
                    postconditionStatus = PostconditionStatus.UNVERIFIABLE.name.lowercase(),
                    goalVerificationStatus = UiVerificationStatus.UNVERIFIABLE.name.lowercase(),
                    evidenceCategory = "observation_unavailable",
                    checkpointId = "post_observation"
                )
                onStepRecord?.invoke(unverifiableRecord)
                return@execution failure("UI action ran (executed), but the resulting screen could not be observed (unverifiable).")
            }
            val assertion = actionPlan.steps.filterIsInstance<UiActionStep.Assert>().lastOrNull()?.condition
            val assertionMatched = assertion?.let { assertionMatches(it, next.screen) }
            val builtInPostcondition = UiActionPostconditionVerifier.verify(action, observation.screen, next.screen)
            if (assertion != null) {
                trace(
                    "postcondition_checked",
                    mapOf(
                        "index" to actionIndex,
                        "matched" to assertionMatched,
                        "screen_id" to next.screen.screenId,
                        "package" to next.screen.packageName
                    )
                )
            }
            if (builtInPostcondition.status != PostconditionStatus.NOT_APPLICABLE) {
                trace(
                    "built_in_postcondition_checked",
                    mapOf(
                        "index" to actionIndex,
                        "status" to builtInPostcondition.status.name.lowercase(),
                        "verification_status" to builtInPostcondition.verificationStatus.name.lowercase(),
                        "evidence_category" to builtInPostcondition.evidenceCategory,
                        "detail" to builtInPostcondition.detail,
                        "screen_id" to next.screen.screenId,
                        "package" to next.screen.packageName
                    )
                )
            }

            var finalNext = next
            var effectivePostcondition = builtInPostcondition
            if (builtInPostcondition.status == PostconditionStatus.FAILED) {
                if (action is UiActionStep.PressBack || (action is UiActionStep.GlobalAction && action.action == "back")) {
                    val visibleBack = DirectSemanticActionResolver.resolveVisibleBack(observation.screen)
                    if (visibleBack != null) {
                        val recoveryResult = execute(visibleBack, observation, sessionId, goal)
                        if (!recoveryResult.isError) {
                            val nextRecovery = observeAfterAction(observation, visibleBack)
                            if (nextRecovery != null) {
                                val recoveryPost = UiActionPostconditionVerifier.verify(visibleBack, observation.screen, nextRecovery.screen)
                                if (recoveryPost.status.isMechanicalProgress) {
                                    finalNext = nextRecovery
                                    effectivePostcondition = recoveryPost
                                }
                            }
                        }
                    }
                }
            }

            val changed = finalNext.screen.screenId != observation.screen.screenId
            unchangedCount = if (changed) 0 else unchangedCount + 1
            val postconditionFailed = assertionMatched == false ||
                effectivePostcondition.status == PostconditionStatus.FAILED ||
                effectivePostcondition.status == PostconditionStatus.UNVERIFIABLE
            val goalVerification = UiGoalVerifier.verify(
                goal = goal,
                action = action,
                before = observation.screen,
                after = finalNext.screen,
                transportStatus = UiTransportStatus.ACCEPTED,
                postcondition = effectivePostcondition,
                goalAppCandidates = goalAppCandidates,
                explicitAssertion = assertion,
                actionHistory = actionHistory,
                confirmationGranted = stepConfirmationGranted,
                expectedDestination = stepVerifiedDestination,
                hasPendingUnconfirmedEffect = pendingExternalEffect != null
            )
            trace(
                "verification_completed",
                mapOf(
                    "index" to actionIndex,
                    "success" to !postconditionFailed,
                    "status" to effectivePostcondition.status.name.lowercase(),
                    "goal_verification_status" to goalVerification.status.name.lowercase(),
                    "evidence_category" to goalVerification.evidenceCategory,
                    "checkpoint_id" to goalVerification.checkpointId
                )
            )
            val postconditionDetail = buildList {
                when (assertionMatched) {
                    true -> add("Planner postcondition matched; continue until goal outcome is verified.")
                    false -> add("Planner postcondition did not match; replan from the observed state.")
                    null -> Unit
                }
                if (effectivePostcondition.status != PostconditionStatus.NOT_APPLICABLE) {
                    add(effectivePostcondition.detail)
                }
            }.takeIf { it.isNotEmpty() }?.joinToString(" ")
            
            if (action !is UiActionStep.Wait && action !is UiActionStep.Done && action !is UiActionStep.AskUser) {
                if (!postconditionFailed) successfulFingerprints.add(fingerprint)
                actionHistory.add(
                    UiActionHistoryEntry(
                        index = actionIndex,
                        action = actionLabel(action),
                        targetElementId = getTargetElementId(action),
                        targetLabel = getTargetLabel(action, observation.screen),
                        sourceScreenId = observation.screen.screenId,
                        resultScreenId = finalNext.screen.screenId,
                        outcome = if (postconditionFailed) Outcome.FAILED else Outcome.SUCCEEDED,
                        changedScreen = changed,
                        detail = postconditionDetail,
                        sourcePackage = observation.screen.packageName,
                        resultPackage = finalNext.screen.packageName
                    )
                )
            }
            
            logger(
                "UiAutomation step=${actionIndex + 1} action=${action::class.simpleName} " +
                    "screen=${observation.screen.screenId}->${finalNext.screen.screenId} " +
                    "postcondition=${effectivePostcondition.status} goalStatus=${goalVerification.status} unchanged=$unchangedCount"
            )
            trace(
                "transition_observed",
                mapOf(
                    "index" to actionIndex,
                    "source_screen_id" to observation.screen.screenId,
                    "result_screen_id" to finalNext.screen.screenId,
                    "source_package" to observation.screen.packageName,
                    "result_package" to finalNext.screen.packageName,
                    "changed" to changed,
                    "unchanged_count" to unchangedCount
                )
            )
            AutomationSessionManager.actionCompleted(
                sessionId,
                "${actionLabel(action)}: ${observation.screen.packageName} → ${finalNext.screen.packageName} (${goalVerification.status.name.lowercase()})"
            )
            val stepRecord = ActionStepRecord(
                sequence = actionIndex + 1 + deterministicPrefixCount,
                observation = observation.screen.screenId,
                reasoningSummary = actionLabel(action),
                action = actionLabel(action),
                target = getTargetLabel(action, observation.screen),
                result = if (postconditionFailed) "failed" else "succeeded",
                postActionObservation = finalNext.screen.screenId,
                verification = goalVerification.summary.ifBlank { postconditionDetail ?: "" },
                latencyMs = monotonicNowMs() - stepStartMs,
                sourcePackage = observation.screen.packageName,
                resultPackage = finalNext.screen.packageName,
                actionFamily = actionFamily(action),
                semanticAction = actionLabel(action),
                executionMechanism = executionMechanism(action, executedResult),
                verificationStatus = goalVerification.status.name.lowercase(),
                sourceWindow = observation.screen.activityName,
                resultWindow = finalNext.screen.activityName,
                transportStatus = goalVerification.transportStatus.name.lowercase(),
                postconditionStatus = effectivePostcondition.status.name.lowercase(),
                goalVerificationStatus = goalVerification.status.name.lowercase(),
                evidenceCategory = goalVerification.evidenceCategory,
                checkpointId = goalVerification.checkpointId
            )
            onStepRecord?.invoke(stepRecord)
            if (goalVerification.isGoalVerified) {
                trace(
                    "goal_condition_satisfied",
                    mapOf(
                        "summary" to goalVerification.summary,
                        "screen_id" to finalNext.screen.screenId,
                        "package" to finalNext.screen.packageName,
                        "verification_status" to goalVerification.status.name.lowercase(),
                        "evidence_category" to goalVerification.evidenceCategory,
                        "checkpoint_id" to goalVerification.checkpointId
                    )
                )
                return@execution success(goalVerification.summary)
            }
            UiGoalCompletionEvaluator.evaluate(goal, finalNext.screen, goalAppCandidates)?.let { completion ->
                trace(
                    "goal_condition_satisfied",
                    mapOf(
                        "summary" to completion.summary,
                        "screen_id" to finalNext.screen.screenId,
                        "package" to finalNext.screen.packageName,
                        "verification_status" to UiVerificationStatus.GOAL_VERIFIED.name.lowercase(),
                        "evidence_category" to "search_results"
                    )
                )
                return@execution success(completion.summary)
            }
            if (unchangedCount >= budget.maxUnchangedObservations) {
                return@execution failure("UI did not change after repeated actions; stopping automation.")
            }
            observation = finalNext
            }
            failure("UI action limit reached before the goal was verified.")
        }
        return executionResult
    }

    private suspend fun observe(requestScreenshot: Boolean = false): Observation? {
        val service = com.mewmix.nabu.accessibility.NabuAccessibilityService.instance
        if (service == null) {
            logger("UiAutomation observe failed: AccessibilityService not connected")
            return null
        }

        val captureStartedMs = monotonicNowMs()
        val snapshot = service.forceCaptureSnapshot()
        if (snapshot == null) {
            logger("UiAutomation observe failed: No snapshot available")
            return null
        }

        val capturedMs = monotonicNowMs()
        return observationFromSnapshot(
            snapshot = snapshot,
            requestScreenshot = requestScreenshot,
            captureStartedMs = captureStartedMs,
            capturedMs = capturedMs,
            source = "forced_capture"
        )
    }

    private fun observationFromSnapshot(
        snapshot: com.mewmix.nabu.accessibility.UiSnapshot,
        requestScreenshot: Boolean,
        captureStartedMs: Long,
        capturedMs: Long,
        source: String
    ): Observation {
        val screenState = UiTreeIndexer.build(snapshot)
        val indexedMs = monotonicNowMs()
        var screenshotPath: String? = null
        val artifactPaths = mutableListOf<String>()
        val needsVisionFallback = screenState.plannerElements().isEmpty() &&
            activeBackend?.let { shouldAttachScreenshot(it, jsonRetry = false) } == true
        if ((requestScreenshot || needsVisionFallback) &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
        ) {
            val path = context.cacheDir.absolutePath + "/nabu_ui_${snapshot.id}.png"
            if (com.mewmix.nabu.accessibility.NabuAccessibilityService.instance
                    ?.takeScreenshotToPath(path) == true
            ) {
                screenshotPath = path
                artifactPaths.add(path)
                pendingArtifactPaths.add(path)
            }
        }

        return Observation(
            bridgeObservationId = snapshot.id,
            snapshotSequence = snapshot.sequence,
            screen = screenState,
            screenshotPath = screenshotPath,
            artifactPaths = artifactPaths
        ).also { observation ->
            trace(
                "observation_captured",
                mapOf(
                    "observation_id" to observation.bridgeObservationId,
                    "screen_id" to observation.screen.screenId,
                    "package" to observation.screen.packageName,
                    "window" to observation.screen.activityName,
                    "element_count" to observation.screen.elements.size,
                    "actionable_count" to observation.screen.plannerElements().size,
                    "screenshot" to (observation.screenshotPath != null),
                    "source" to source,
                    "capture_ms" to (capturedMs - captureStartedMs),
                    "index_ms" to (indexedMs - capturedMs)
                )
            )
        }
    }

    private suspend fun observeInitial(
        initialSnapshot: com.mewmix.nabu.accessibility.UiSnapshot?
    ): Observation? {
        val service = com.mewmix.nabu.accessibility.NabuAccessibilityService.instance
        if (initialSnapshot != null && service != null) {
            service.promoteSnapshotForAction(initialSnapshot)?.let { promoted ->
                val now = monotonicNowMs()
                return observationFromSnapshot(
                    snapshot = promoted,
                    requestScreenshot = false,
                    captureStartedMs = now,
                    capturedMs = now,
                    source = "preflight_reuse"
                )
            }
            trace("observation_reuse_rejected", mapOf("reason_code" to "preflight_snapshot_superseded"))
        }
        val deadline = monotonicNowMs() + INITIAL_OBSERVATION_TIMEOUT_MS
        var sequence = com.mewmix.nabu.accessibility.UiSnapshotStore.currentSnapshot.value?.sequence ?: 0L
        do {
            observe(requestScreenshot = false)?.let { return it }
            val remainingMs = deadline - monotonicNowMs()
            if (remainingMs <= 0L) break
            val eventSnapshot = com.mewmix.nabu.accessibility.UiSnapshotStore.awaitAfter(
                sequence = sequence,
                timeoutMs = remainingMs.coerceAtMost(INITIAL_EVENT_WAIT_SLICE_MS)
            )
            if (eventSnapshot != null) {
                sequence = eventSnapshot.sequence
            } else {
                delay(INITIAL_RETRY_DELAY_MS)
            }
        } while (monotonicNowMs() < deadline)
        return observe(requestScreenshot = false)
    }

    private suspend fun observeAfterDirectAction(
        beforeSnapshot: com.mewmix.nabu.accessibility.UiSnapshot?,
        action: UiActionStep
    ): Observation? {
        val maxWaitMs = UiTransitionPolicy.maxWaitMs(action, budget)
        if (maxWaitMs <= 0) return observe(requestScreenshot = false)

        progress("Transition", "Waiting for Android to settle after ${actionLabel(action)}")
        val deadline = monotonicNowMs() + maxWaitMs
        var latest: Observation? = null
        var observedSequence = beforeSnapshot?.sequence
            ?: com.mewmix.nabu.accessibility.UiSnapshotStore.currentSnapshot.value?.sequence
            ?: 0L
        val beforeScreen = beforeSnapshot?.let(UiTreeIndexer::build)

        do {
            val remainingMs = deadline - monotonicNowMs()
            if (remainingMs <= 0) break
            val eventSnapshot = com.mewmix.nabu.accessibility.UiSnapshotStore.awaitAfter(
                sequence = observedSequence,
                timeoutMs = remainingMs
            ) ?: break
            observedSequence = eventSnapshot.sequence
            val promoted = com.mewmix.nabu.accessibility.NabuAccessibilityService.instance
                ?.promoteSnapshotForAction(eventSnapshot)
                ?: continue
            val now = monotonicNowMs()
            val candidate = observationFromSnapshot(
                snapshot = promoted,
                requestScreenshot = false,
                captureStartedMs = now,
                capturedMs = now,
                source = "event_promotion"
            )
            latest?.let { cleanupArtifacts(it.artifactPaths) }
            latest = candidate
            if (beforeScreen != null && UiTransitionPolicy.isSettled(beforeScreen, candidate.screen, action)) break
            if (action is UiActionStep.OpenApp && candidate.screen.packageName.equals(action.packageName, ignoreCase = true)) break
        } while (true)

        return latest ?: observe(requestScreenshot = false)
    }

    private suspend fun observeAfterAction(
        previous: Observation,
        action: UiActionStep
    ): Observation? {
        val maxWaitMs = UiTransitionPolicy.maxWaitMs(action, budget)
        if (maxWaitMs <= 0) return observe()

        progress("Transition", "Waiting for Android to settle after ${actionLabel(action)}")
        val deadline = monotonicNowMs() + maxWaitMs
        var latest: Observation? = null
        var observedSequence = previous.snapshotSequence
        do {
            val remainingMs = deadline - monotonicNowMs()
            if (remainingMs <= 0) break
            val eventSnapshot = com.mewmix.nabu.accessibility.UiSnapshotStore.awaitAfter(
                sequence = observedSequence,
                timeoutMs = remainingMs
            ) ?: break
            observedSequence = eventSnapshot.sequence
            val promoted = com.mewmix.nabu.accessibility.NabuAccessibilityService.instance
                ?.promoteSnapshotForAction(eventSnapshot)
                ?: continue
            val now = monotonicNowMs()
            val candidate = observationFromSnapshot(
                snapshot = promoted,
                requestScreenshot = false,
                captureStartedMs = now,
                capturedMs = now,
                source = "event_promotion"
            )
            latest?.let { cleanupArtifacts(it.artifactPaths) }
            latest = candidate
            if (UiTransitionPolicy.isSettled(previous.screen, candidate.screen, action)) break
        } while (true)

        return latest ?: observe(requestScreenshot = false)
    }

    private suspend fun cleanupArtifacts(paths: Collection<String>) {
        paths.forEach { path ->
            if (!isGeneratedObservationPath(path)) {
                logger("UiAutomation refused to delete unexpected artifact path $path")
                return@forEach
            }
            val deletedLocally = runCatching { !File(path).exists() || File(path).delete() }.getOrDefault(false)
            val deleted = deletedLocally || (AccessibilityToolHandler.execute(
                context,
                ToolCall("delete_file", mapOf("path" to path))
            )?.isError == false)
            if (!deleted) logger("UiAutomation could not delete consumed artifact $path")
            if (deleted) pendingArtifactPaths.remove(path)
        }
    }

    private fun isGeneratedObservationPath(path: String): Boolean {
        val normalized = path.replace("/sdcard/", "/storage/emulated/0/")
        val file = File(normalized)
        return (file.parent == "/storage/emulated/0/Download" || file.parent == context.cacheDir.absolutePath) &&
            file.name.startsWith("nabu_ui_") &&
            (file.extension == "xml" || file.extension == "png")
    }

    private fun parsePlan(
        rawPlan: String,
        goal: String,
        observation: Observation
    ): Result<UiActionPlan> = runCatching {
        val rawJson = extractJson(rawPlan)
        try {
            val decision = ConstrainedDecisionDecoder.decode(rawJson)
            require(decision !is AgentDecision.Query && decision !is AgentDecision.Delegate) {
                "UI control does not accept query or delegation decisions."
            }
            logger("Successfully parsed V3 decision: $decision")
            decision.toUiActionPlan(goal, observation.screen.screenId)
        } catch (e: Exception) {
            val isV3Payload = runCatching {
                com.google.gson.JsonParser.parseString(rawJson)
                    .asJsonObject
                    .get("v")
                    ?.asInt == 3
            }.getOrDefault(false)
            logger("V3 decision rejected: ${e.message}")
            if (isV3Payload) throw e
            LegacyPlannerAdapter.parseAndAdapt(
                rawJson = rawJson,
                goal = goal,
                screenId = observation.screen.screenId,
                logger = { logger(it) }
            )
        }.resolveElementReferences(observation.screen)
    }

    private suspend fun plan(
        goal: String,
        observation: Observation,
        history: List<UiActionHistoryEntry>,
        goalAppCandidates: List<DeviceAction.AppCandidate>,
        jsonRetry: Boolean = false
    ): String? {
        val modelAcquireStartedMs = monotonicNowMs()
        val wasReady = activeBackend != null || backendReadyProvider()
        if (!modelRoutePublished) {
            trace(
                "routing_completed",
                mapOf(
                    "route" to "action_model",
                    "model_required" to true,
                    "model_initialization_on_critical_path" to !wasReady,
                    "app_resolution_ms" to appResolutionDurationMs
                )
            )
            modelRoutePublished = true
        }
        val plannerBackend = activeBackend ?: backendProvider()?.also {
            activeBackend = it
            backendLeaseAcquired = true
        }
        if (plannerBackend == null) {
            actionModelUnavailable = true
            trace("planner_unavailable", mapOf("reason_code" to "no_eligible_action_model"))
            return null
        }
        trace(
            "model_backend_acquired",
            mapOf(
                "was_ready" to wasReady,
                "acquisition_ms" to (monotonicNowMs() - modelAcquireStartedMs)
            )
        )
        trace(
            "planner_request_started",
            mapOf("screen_id" to observation.screen.screenId, "json_retry" to jsonRetry)
        )
        val userContent = buildPlannerInput(goal, observation.screen, history, goalAppCandidates)
        val attachScreenshot = observation.screenshotPath != null && shouldAttachScreenshot(plannerBackend, jsonRetry)
        logger(
            "UiAutomation planner input backend=${plannerBackend::class.java.simpleName} " +
                "runtime=${plannerBackend.runtimeDescription()} jsonRetry=$jsonRetry screenshot=$attachScreenshot"
        )
        val images = if (attachScreenshot) {
            observation.screenshotPath
                ?.let(BitmapFactory::decodeFile)
                ?.let(::LlmImageInput)
                ?.let(::listOf)
                .orEmpty()
        } else {
            emptyList()
        }
        val conversation = listOf(
            LlmMessage(
                role = "system",
                content = if (jsonRetry) JSON_RETRY_SYSTEM_PROMPT else PLANNER_SYSTEM_PROMPT
            ),
            LlmMessage(role = "user", content = userContent, images = images)
        )
        val structuredStartedMs = monotonicNowMs()
        val decisionTools = buildDecisionTools(observation.screen, plannerElementLimit())
        val structuredResult = try {
            plannerBackend.generateStructured(conversation, decisionTools)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger(
                "UiAutomation native structured generation failed; falling back to JSON: " +
                    "${error::class.java.simpleName}: ${error.message}"
            )
            null
        }
        structuredResult?.let { result ->
            val raw = try {
                result.toolCall?.let(ConstrainedDecisionDecoder::toCanonicalJson)
                    ?: result.text
            } catch (error: Exception) {
                logger(
                    "UiAutomation native structured decision was unusable; falling back to JSON: " +
                        "${error::class.java.simpleName}: ${error.message}"
                )
                ""
            }
            if (raw.isBlank()) {
                logger("UiAutomation native structured generation returned no decision; falling back to JSON")
            } else {
                trace("planner_first_response", mapOf("native_structured" to true))
                onModelOutput(raw)
                logger(
                    "UiAutomation native structured output screen=${observation.screen.screenId} " +
                        "tool=${result.toolCall?.name ?: "text_fallback"} outputLength=${raw.length} " +
                        "outputHash=${hashContent(raw)}"
                )
                trace(
                    "planner_output_received",
                    mapOf(
                        "screen_id" to observation.screen.screenId,
                        "package" to observation.screen.packageName,
                        "planner_output" to raw,
                        "output_length" to raw.length,
                        "json_retry" to jsonRetry,
                        "native_structured" to (result.toolCall != null),
                        "latency_ms" to (monotonicNowMs() - structuredStartedMs)
                    )
                )
                return raw
            }
        }
        val completion = CompletableDeferred<String?>()
        val completed = AtomicBoolean(false)
        val firstResponse = AtomicBoolean(false)
        val output = StringBuilder()
        val plannerStartedMs = monotonicNowMs()
        plannerBackend.sendMessage(conversation) { partial, done ->
            if (partial.isNotEmpty()) {
                if (firstResponse.compareAndSet(false, true)) {
                    trace("planner_first_response", mapOf("native_structured" to false))
                }
                output.append(partial)
                onModelOutput(partial)
            }
            if (done && completed.compareAndSet(false, true)) completion.complete(output.toString())
        }
        return withTimeoutOrNull(PLANNER_TIMEOUT_MS) { completion.await() }?.also { raw ->
            logger(
                "UiAutomation planner output screen=${observation.screen.screenId} " +
                    "elements=${observation.screen.plannerElements(plannerElementLimit()).size} " +
                    "outputLength=${raw.length} outputHash=${hashContent(raw)}"
            )
            trace(
                "planner_output_received",
                mapOf(
                    "screen_id" to observation.screen.screenId,
                    "package" to observation.screen.packageName,
                    "planner_output" to raw,
                    "output_length" to raw.length,
                    "json_retry" to jsonRetry,
                    "latency_ms" to (monotonicNowMs() - plannerStartedMs)
                )
            )
        }
    }

    private fun shouldAttachScreenshot(backend: LlmBackend, jsonRetry: Boolean): Boolean {
        if (jsonRetry || !backend.supportsImageInput()) return false
        return backend !is LiteRtLmBackend && !backend.runtimeDescription().startsWith("LITERT-LM")
    }

    private fun isDeviceLocked(): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        return keyguardManager?.isDeviceLocked == true
    }

    private suspend fun executeRequestLevelAction(action: UiActionStep, goal: String): ToolResult {
        val policy = AutomationIntentPolicy.evaluate(
            action,
            PolicyContext(
                isScheduled = isScheduled,
                isDeviceLocked = isDeviceLocked(),
                destinationProvenance = "deterministic_request",
                context = context
            )
        )
        when (policy) {
            is IntentPolicyDecision.Block -> return failure("Action blocked by policy: ${policy.reason}")
            is IntentPolicyDecision.RequireConfirmation -> {
                progress("Confirm", policy.reason)
                if (!requestConfirmation(policy.preview?.let { "${policy.reason}\n\n$it" } ?: policy.reason)) {
                    return failure("Action cancelled because confirmation was not granted.")
                }
            }
            IntentPolicyDecision.Allow -> Unit
        }

        val started = markPhysicalActionDispatch(action)
        val result = when (action) {
            is UiActionStep.OpenApp -> DeviceAction.openApp(context, action.packageName, "")
                .let { if (it.isError) failure(it.message) else success(it.message) }
            else -> {
                val token = globalToken(action)
                    ?: return completePhysicalAction(
                        action,
                        started,
                        failure("Unsupported deterministic request action for '$goal'."),
                        "request_fast_path"
                    )
                val succeeded = runCatching {
                    com.mewmix.nabu.accessibility.NabuAccessibilityService.instance
                        ?.performTrustedGlobalAction(token) == true
                }.getOrElse { error ->
                    return completePhysicalAction(
                        action,
                        started,
                        failure(error.message ?: "Global action '$token' failed."),
                        "global_action"
                    )
                }
                if (succeeded) success("${actionLabel(action)} completed.")
                else failure("Global action '$token' failed.")
            }
        }
        return completePhysicalAction(
            action,
            started,
            result,
            if (action is UiActionStep.OpenApp) "trusted_intent" else "global_action"
        )
    }

    private fun verifyRequestLevelAction(
        goal: String,
        action: UiActionStep,
        before: UiScreenState?,
        after: UiScreenState?,
        completesGoalWhenVerified: Boolean
    ): GoalVerificationResult {
        val postcondition = when {
            after == null -> PostconditionResult(
                status = PostconditionStatus.UNVERIFIABLE,
                detail = "No trusted post-action screen state was observed for ${actionLabel(action)}.",
                verificationStatus = UiVerificationStatus.UNVERIFIABLE,
                evidenceCategory = "post_observation_missing"
            )
            action is UiActionStep.OpenApp -> {
                if (after.packageName.equals(action.packageName, ignoreCase = true)) {
                    PostconditionResult(
                        status = PostconditionStatus.VERIFIED,
                        detail = "Opened ${after.packageName} and verified it in the foreground.",
                        verificationStatus = if (completesGoalWhenVerified) UiVerificationStatus.GOAL_VERIFIED else UiVerificationStatus.EXECUTED,
                        evidenceCategory = "app_launch_package"
                    )
                } else {
                    PostconditionResult(
                        status = PostconditionStatus.FAILED,
                        detail = "Expected ${action.packageName} to foreground, but observed ${after.packageName}.",
                        verificationStatus = UiVerificationStatus.FAILED,
                        evidenceCategory = "app_launch_mismatch"
                    )
                }
            }
            before != null -> UiActionPostconditionVerifier.verify(action, before, after)
            action == UiActionStep.OpenNotifications -> {
                val eval = UiSystemSurfaceDetector.evaluate(after, SystemSurfaceKind.NOTIFICATION_SHADE)
                PostconditionResult(
                    status = if (eval.matched) PostconditionStatus.VERIFIED else PostconditionStatus.FAILED,
                    detail = eval.detail,
                    verificationStatus = if (eval.matched) UiVerificationStatus.GOAL_VERIFIED else UiVerificationStatus.FAILED,
                    evidenceCategory = "system_surface_notification_shade"
                )
            }
            action == UiActionStep.OpenQuickSettings -> {
                val eval = UiSystemSurfaceDetector.evaluate(after, SystemSurfaceKind.QUICK_SETTINGS)
                PostconditionResult(
                    status = if (eval.matched) PostconditionStatus.VERIFIED else PostconditionStatus.FAILED,
                    detail = eval.detail,
                    verificationStatus = if (eval.matched) UiVerificationStatus.GOAL_VERIFIED else UiVerificationStatus.FAILED,
                    evidenceCategory = "system_surface_quick_settings"
                )
            }
            else -> PostconditionResult(
                status = PostconditionStatus.UNVERIFIABLE,
                detail = "No trusted pre-action screen was available to verify ${actionLabel(action)}.",
                verificationStatus = UiVerificationStatus.UNVERIFIABLE,
                evidenceCategory = "pre_observation_missing"
            )
        }
        val candidates = if (action is UiActionStep.OpenApp) {
            val resolved = DeviceAction.findGoalAppCandidates(context, goal)
            if (resolved.any { it.packageName.equals(action.packageName, ignoreCase = true) }) {
                resolved
            } else {
                listOf(DeviceAction.AppCandidate(action.packageName, action.packageName)) + resolved
            }
        } else {
            DeviceAction.findGoalAppCandidates(context, goal)
        }
        return UiGoalVerifier.verify(
            goal = goal,
            action = action,
            before = before,
            after = after,
            transportStatus = UiTransportStatus.ACCEPTED,
            postcondition = postcondition,
            goalAppCandidates = candidates
        )
    }

    private fun directRequestProgress(resolution: DirectRequestResolution.Resolved): String =
        when (val action = resolution.action) {
            is UiActionStep.OpenApp -> "Opening ${resolution.displayLabel ?: action.packageName}"
            else -> actionLabel(action).replaceFirstChar(Char::uppercase)
    }

    private fun globalToken(action: UiActionStep): String? = when (action) {
        UiActionStep.PressBack -> "back"
        UiActionStep.PressHome -> "home"
        UiActionStep.PressRecents -> "recents"
        UiActionStep.OpenNotifications -> "notifications"
        UiActionStep.OpenQuickSettings -> "quick_settings"
        is UiActionStep.GlobalAction -> action.action
        else -> null
    }

    private fun publishFastPathReceipt(
        sequence: Int,
        action: UiActionStep,
        before: UiScreenState?,
        after: UiScreenState?,
        verificationResult: GoalVerificationResult,
        mechanism: String,
        startedMs: Long
    ) {
        activeSessionId?.let { sessionId ->
            AutomationSessionManager.actionCompleted(
                sessionId,
                "${actionLabel(action)}: ${before?.packageName ?: "request"} → ${after?.packageName ?: "unknown"} (${verificationResult.status.name})"
            )
        }
        val targetContext = before ?: after
        onStepRecord?.invoke(
            ActionStepRecord(
                sequence = sequence,
                observation = before?.screenId ?: "request_level",
                reasoningSummary = "Deterministic ${actionLabel(action)}",
                action = actionLabel(action),
                target = if (targetContext != null) getTargetLabel(action, targetContext) else actionLabel(action),
                result = if (verificationResult.status.isFailure) "failed" else "succeeded",
                postActionObservation = after?.screenId ?: "unavailable",
                verification = verificationResult.summary,
                latencyMs = (monotonicNowMs() - startedMs).coerceAtLeast(0L),
                sourcePackage = before?.packageName,
                resultPackage = after?.packageName,
                actionFamily = actionFamily(action),
                semanticAction = actionLabel(action),
                executionMechanism = mechanism,
                verificationStatus = verificationResult.status.name.lowercase(),
                transportStatus = verificationResult.transportStatus.name.lowercase(),
                postconditionStatus = verificationResult.postconditionStatus.name.lowercase(),
                goalVerificationStatus = verificationResult.status.name.lowercase(),
                evidenceCategory = verificationResult.evidenceCategory,
                checkpointId = verificationResult.checkpointId,
                sourceWindow = before?.activityName,
                resultWindow = after?.activityName
            )
        )
    }

    private suspend fun execute(action: UiActionStep, observation: Observation, sessionId: String, goal: String): ToolResult {
        if (action is UiActionStep.ShareCapturedMedia) {
            val capture = pendingCapture
                ?: return failure("No captured media is available to share.")
            if (!AutomationMediaManager.validateCaptureOutput(context, capture.uri)) {
                return failure("Captured media is empty or unavailable.")
            }
            if (!goalExplicitlyNamesDestination(goal, action.expectedDestination)) {
                return failure("Expected destination must be explicitly present in the user's goal.")
            }
        }
        val policyContext = PolicyContext(
            isScheduled = isScheduled,
            isDeviceLocked = isDeviceLocked(),
            destinationProvenance = "planner",
            context = context
        )
        val policyDecision = AutomationIntentPolicy.evaluate(action, policyContext)
        var currentObservation = observation
        when (policyDecision) {
            is IntentPolicyDecision.Block -> return failure("Action blocked by policy: ${policyDecision.reason}")
            is IntentPolicyDecision.RequireConfirmation -> {
                progress("Confirm", "Evaluating confirmation requirements")
                val expectedDestination = expectedDestinationForCommit(action, currentObservation.screen)
                if (isMessagingCommitBoundary(action, currentObservation.screen) && expectedDestination == null) {
                    return failure("Action blocked: no verified pending message is bound to this send action.")
                }
                val needsDestination = expectedDestination != null
                var verifiedDestination: String? = null
                if (needsDestination) {
                    when (val result = DestinationResolver.resolve(currentObservation.screen, expectedDestination)) {
                        is DestinationResolver.DestinationResult.Verified -> verifiedDestination = result.observed
                        is DestinationResolver.DestinationResult.Mismatch -> return failure("Destination mismatch: observed '${result.observed}' but expected '${result.expected}'.")
                        is DestinationResolver.DestinationResult.Unresolvable -> return failure("Action blocked: ${result.reason}")
                    }
                }
                val userApproved = kotlinx.coroutines.withTimeoutOrNull(60_000) {
                    val description = describeConfirmation(action, policyDecision.reason, currentObservation.screen, goal, verifiedDestination)
                    requestConfirmation(description)
                } ?: false
                
                if (!userApproved) {
                    return failure("User denied UI action confirmation or timed out.")
                }
                
                val latestObservation = observe(requestScreenshot = false)
                    ?: return failure("Failed to re-observe screen for confirmation.")
                if (latestObservation.screen.screenId != currentObservation.screen.screenId) {
                    return failure("Screen changed while awaiting confirmation. Action aborted.")
                }
                
                currentObservation = latestObservation

                // Re-verify destination after user confirmation
                if (needsDestination && verifiedDestination != null) {
                    when (val recheck = DestinationResolver.resolve(currentObservation.screen, verifiedDestination)) {
                        is DestinationResolver.DestinationResult.Verified -> Unit
                        is DestinationResolver.DestinationResult.Mismatch -> return failure("Destination changed after confirmation: observed '${recheck.observed}' but expected '${recheck.expected}'.")
                        is DestinationResolver.DestinationResult.Unresolvable -> return failure("Destination unresolvable after confirmation: ${recheck.reason}")
                    }
                }
                
                val fingerprint = "${actionLabel(action)}|${hashContent(action.toJson().toString(), goal)}|${currentObservation.screen.screenId}"
                val contentHash = confirmationContentHash(action, currentObservation.screen, goal)
                val grantId = ConfirmationManager.requestConfirmation(
                    sessionId = sessionId,
                    screenId = currentObservation.screen.screenId,
                    actionFingerprint = fingerprint,
                    destination = verifiedDestination,
                    contentHash = contentHash,
                    timeoutMs = 120_000
                )
                
                if (!ConfirmationManager.consumeConfirmation(grantId, sessionId, currentObservation.screen.screenId, fingerprint, verifiedDestination, contentHash)) {
                    return failure("Confirmation expired or invalid.")
                }
            }
            is IntentPolicyDecision.Allow -> Unit
        }

        val arguments = linkedMapOf<String, Any>("observation_id" to currentObservation.bridgeObservationId)
        val toolName = when (action) {
            is UiActionStep.Tap -> {
                addTarget(arguments, action.target, currentObservation.screen)
                "ui_tap"
            }
            is UiActionStep.Focus -> {
                addTarget(arguments, action.target, currentObservation.screen)
                "ui_focus"
            }
            is UiActionStep.NodeAction -> {
                addTarget(arguments, action.target, currentObservation.screen)
                arguments["node_action"] = action.action
                arguments.putAll(action.arguments)
                "ui_node_action"
            }
            is UiActionStep.CustomAction -> {
                addTarget(arguments, action.target, currentObservation.screen)
                val element = action.target.elementId?.let(currentObservation.screen::element)
                    ?: return failure("Custom action target is unavailable.")
                val custom = element.customActions.singleOrNull { it.ref == action.actionRef }
                    ?: return failure("Custom action ref expired with its observation.")
                arguments["trusted_action_id"] = custom.trustedActionId
                arguments["custom_action_label"] = custom.label
                "ui_custom_action"
            }
            is UiActionStep.Gesture -> {
                addTarget(arguments, action.target, currentObservation.screen)
                arguments["gesture"] = action.gesture
                arguments.putAll(action.arguments)
                action.destination?.let { destination ->
                    val destinationElement = destination.elementId?.let(currentObservation.screen::element)
                        ?: return failure("Gesture destination is unavailable.")
                    arguments["destination_selector"] = selectorFor(destinationElement)
                    destinationElement.bounds?.let { arguments["destination_bounds"] = it.toList() }
                        ?: return failure("Gesture destination has no observed bounds.")
                }
                "ui_gesture"
            }
            is UiActionStep.LongPress -> {
                addTarget(arguments, action.target, currentObservation.screen)
                "ui_long_press"
            }
            is UiActionStep.TypeText -> {
                action.target?.let { addTarget(arguments, it, currentObservation.screen) }
                arguments["text"] = action.text
                "ui_set_text"
            }
            is UiActionStep.Scroll -> {
                action.target?.let { addTarget(arguments, it, currentObservation.screen) }
                arguments["direction"] = action.direction.name.lowercase()
                "ui_scroll"
            }
            UiActionStep.PressBack -> {
                arguments["global_action"] = "back"
                "ui_global_action"
            }
            UiActionStep.PressHome -> {
                arguments["global_action"] = "home"
                "ui_global_action"
            }
            UiActionStep.PressRecents -> {
                arguments["global_action"] = "recents"
                "ui_global_action"
            }
            UiActionStep.OpenNotifications -> {
                arguments["global_action"] = "notifications"
                "ui_global_action"
            }
            UiActionStep.OpenQuickSettings -> {
                arguments["global_action"] = "quick_settings"
                "ui_global_action"
            }
            is UiActionStep.GlobalAction -> {
                arguments["global_action"] = action.action
                "ui_global_action"
            }
            is UiActionStep.OpenApp -> {
                val started = markPhysicalActionDispatch(action)
                val result = DeviceAction.openApp(context, action.packageName, "")
                return completePhysicalAction(
                    action,
                    started,
                    if (result.isError) failure(result.message) else success(result.message),
                    "trusted_intent"
                )
            }
            is UiActionStep.OpenSettingsPage -> {
                val started = markPhysicalActionDispatch(action)
                val result = DeviceAction.openSettingsPage(context, action.page.name, action.packageName)
                val toolResult = if (result.isError) failure(result.message) else success(result.message)
                return completePhysicalAction(action, started, toolResult, "trusted_intent")
            }
            is UiActionStep.OpenUrl -> {
                val started = markPhysicalActionDispatch(action)
                val result = DeviceAction.openUrl(context, action.url)
                val toolResult = if (result.isError) failure(result.message) else success(result.message)
                return completePhysicalAction(action, started, toolResult, "trusted_intent")
            }
            is UiActionStep.ShareText -> {
                if (action.expectedDestination != null &&
                    !goalExplicitlyNamesDestination(goal, action.expectedDestination)
                ) {
                    return failure("Expected destination must be explicitly present in the user's goal.")
                }
                val started = markPhysicalActionDispatch(action)
                val result = DeviceAction.shareText(context, action.text, "", action.targetPackage)
                if (!result.isError && action.targetPackage != null && action.expectedDestination != null) {
                    pendingExternalEffect = PendingExternalEffect(
                        targetPackage = action.targetPackage,
                        expectedDestination = action.expectedDestination,
                        contentHash = hashContent(action.text)
                    )
                }
                val toolResult = if (result.isError) failure(result.message) else success(result.message)
                return completePhysicalAction(action, started, toolResult, "trusted_intent")
            }
            is UiActionStep.OpenCamera -> {
                val mimeType = if (action.mode == CameraMode.VIDEO) {
                    AutomationMediaManager.VIDEO_MIME_TYPE
                } else {
                    AutomationMediaManager.IMAGE_MIME_TYPE
                }
                val outputUri = runCatching {
                    AutomationMediaManager.createCaptureOutputUri(context, mimeType)
                }.getOrElse { return failure("Unable to create trusted capture output: ${it.message}") }
                pendingMediaUris += outputUri
                val started = markPhysicalActionDispatch(action)
                val result = if (action.mode == CameraMode.VIDEO) {
                    DeviceAction.recordVideo(context, action.facing.name, outputUri)
                } else {
                    DeviceAction.takePhoto(context, action.facing.name, outputUri)
                }
                if (result.isError) {
                    AutomationMediaManager.cleanupAll(context, listOf(outputUri))
                    pendingMediaUris -= outputUri
                } else {
                    pendingCapture = PendingCapture(outputUri, mimeType)
                }
                val toolResult = if (result.isError) failure(result.message) else success(result.message)
                return completePhysicalAction(action, started, toolResult, "trusted_intent")
            }
            is UiActionStep.ShareCapturedMedia -> {
                val capture = pendingCapture ?: return failure("No captured media is available to share.")
                val mediaHash = AutomationMediaManager.contentSha256(context, capture.uri)
                    ?: return failure("Unable to hash captured media.")
                AutomationMediaManager.grantUriReadPermission(context, capture.uri, action.targetPackage)
                val started = markPhysicalActionDispatch(action)
                val result = DeviceAction.shareMedia(
                    context = context,
                    uri = capture.uri,
                    mimeType = capture.mimeType,
                    targetPackage = action.targetPackage
                )
                if (!result.isError) {
                    pendingExternalEffect = PendingExternalEffect(
                        targetPackage = action.targetPackage,
                        expectedDestination = action.expectedDestination,
                        contentHash = mediaHash
                    )
                }
                val toolResult = if (result.isError) failure(result.message) else success(result.message)
                return completePhysicalAction(action, started, toolResult, "trusted_intent")
            }
            else -> return failure("Unsupported executable UI action.")
        }
        val dispatchStartedMs = markPhysicalActionDispatch(action)
        val result = AccessibilityToolHandler.execute(context, ToolCall(toolName, arguments))
            ?: failure("Unknown UI tool: $toolName")
        completePhysicalAction(action, dispatchStartedMs, result, executionMechanism(action, result))
        if (!result.isError && isMessagingCommitBoundary(action, currentObservation.screen)) {
            pendingExternalEffect = null
        }
        return result
    }

    private fun completePhysicalAction(
        action: UiActionStep,
        dispatchStartedMs: Long,
        result: ToolResult,
        mechanism: String
    ): ToolResult {
        trace(
            "action_dispatch_completed",
            mapOf(
                "action" to actionLabel(action),
                "mechanism" to mechanism,
                "success" to !result.isError,
                "latency_ms" to (monotonicNowMs() - dispatchStartedMs)
            )
        )
        return result
    }

    private fun addTarget(
        arguments: MutableMap<String, Any>,
        target: UiTarget,
        screen: UiScreenState
    ) {
        val element = target.elementId?.let(screen::element)
        if (element != null) {
            arguments["selector"] = selectorFor(element)
        }
        val bounds = element?.bounds ?: target.fallbackBounds
        if (bounds != null) arguments["fallback_bounds"] = bounds.toList()
    }

    private fun selectorFor(element: UiElement): Map<String, String> = mapOf(
        "element_id" to element.id,
        "tree_path" to element.treePath,
        "resource_id" to element.resourceId.orEmpty(),
        "text" to element.text.orEmpty(),
        "content_desc" to element.contentDescription.orEmpty(),
        "class" to element.className.orEmpty(),
        "bounds" to element.bounds?.toList()?.joinToString(",").orEmpty()
    )

    private fun assertionMatches(assertion: UiAssertion, screen: UiScreenState): Boolean {
        val element = assertion.elementId?.let(screen::element)
        if (assertion.elementId != null && element == null) return false
        assertion.checked?.let { expected -> if (element?.checked != expected) return false }
        assertion.textContains?.let { expected ->
            val matches = if (element != null) {
                listOfNotNull(element.text, element.contentDescription).any { it.contains(expected, true) }
            } else {
                screen.elements.any { candidate ->
                    listOfNotNull(candidate.text, candidate.contentDescription).any { it.contains(expected, true) }
                }
            }
            if (!matches) return false
        }
        return true
    }

    private fun buildPlannerInput(
        goal: String,
        screen: UiScreenState,
        history: List<UiActionHistoryEntry>,
        goalAppCandidates: List<DeviceAction.AppCandidate>
    ): String {
        val elements = JsonArray()
        val plannerIdByElementId = linkedMapOf<String, String>()
        val remainingAppCandidates = AutomationAppScope.remainingCandidates(
            goal = goal,
            candidates = goalAppCandidates,
            history = history
        )
        screen.plannerElements(plannerElementLimit())
            .forEachIndexed { index, element ->
                plannerIdByElementId[element.id] = "p$index"
                elements.add(JsonObject().apply {
                    addProperty("id", "p$index")
                    screen.plannerLabel(element)?.let { addProperty("label", it) }
                    if (isCompactLocalPlanner()) {
                        if (screen.plannerLabel(element) == null) {
                            element.resourceId?.let { addProperty("resource_id", it) }
                        }
                        add("actions", JsonArray().apply {
                            plannerActions(element).forEach(::add)
                            element.customActions.forEach { custom ->
                                add(JsonObject().apply {
                                    addProperty("ref", custom.ref)
                                    addProperty("label", custom.label)
                                })
                            }
                        })
                        if (element.checkable) addProperty("checked", element.checked)
                        if (element.password) addProperty("password", true)
                        element.range?.let { range -> add("range", plannerRange(range)) }
                    } else {
                        element.resourceId?.let { addProperty("resource_id", it) }
                        element.className?.let { addProperty("class", it) }
                        element.bounds?.let { bounds ->
                            add("bounds", JsonArray().apply { bounds.toList().forEach(::add) })
                        }
                        addProperty("clickable", element.clickable)
                        addProperty("enabled", element.enabled)
                        addProperty("editable", element.editable)
                        addProperty("scrollable", element.scrollable)
                        addProperty("checkable", element.checkable)
                        addProperty("checked", element.checked)
                        addProperty("password", element.password)
                        add("actions", JsonArray().apply {
                            plannerActions(element).forEach(::add)
                            element.customActions.forEach { custom ->
                                add(JsonObject().apply {
                                    addProperty("ref", custom.ref)
                                    addProperty("label", custom.label)
                                })
                            }
                        })
                        element.range?.let { range -> add("range", plannerRange(range)) }
                    }
                })
            }
        return JsonObject().apply {
            addProperty("goal", goal)
            addProperty("screen_id", screen.screenId)
            addProperty("package", screen.packageName)
            addProperty("activity", screen.activityName)
            add("system_actions", JsonArray().apply { screen.systemActions.sorted().forEach(::add) })
            val successConditions = UiGoalCompletionEvaluator.plannerDescriptions(
                goal,
                goalAppCandidates
            )
            if (successConditions.isNotEmpty()) {
                add("success_conditions", JsonArray().apply {
                    successConditions.forEach(::add)
                })
            }
            if (priorTurns.isNotEmpty()) {
                val turnsArray = JsonArray()
                priorTurns.takeLast(4).forEach { turn ->
                    turnsArray.add(JsonObject().apply {
                        addProperty("user_goal", turn.userRequest)
                        turn.assistantResponse?.let { addProperty("assistant_summary", it) }
                    })
                }
                add("prior_turns", turnsArray)
            }
            if (history.isNotEmpty()) {
                val recentEntries = history.takeLast(8)
                val olderEntries = history.dropLast(8)
                val olderSuccesses = olderEntries.count { it.outcome == Outcome.SUCCEEDED }
                
                if (olderSuccesses > 0) {
                    addProperty("older_successful_actions", olderSuccesses)
                }

                val historyArray = JsonArray()
                recentEntries.forEach { entry -> 
                    historyArray.add(JsonObject().apply {
                        addProperty("action", entry.action)
                        entry.targetLabel?.let { addProperty("target_label", it) }
                        entry.sourcePackage?.let { addProperty("source_package", it) }
                        entry.resultPackage?.let { addProperty("result_package", it) }
                        addProperty("outcome", entry.outcome.name.lowercase())
                        addProperty("screen_changed", entry.changedScreen)
                        entry.detail?.let { addProperty("detail", it) }
                    })
                }
                add("history", historyArray)
            }
            val completedAppPackages = goalAppCandidates
                .map(DeviceAction.AppCandidate::packageName)
                .filter { packageName ->
                    history.any { entry ->
                        entry.outcome == Outcome.SUCCEEDED &&
                            entry.action.equals("open app $packageName", ignoreCase = true)
                    }
                }
            if (completedAppPackages.isNotEmpty()) {
                add("completed_app_packages", JsonArray().apply {
                    completedAppPackages.forEach(::add)
                })
            }
            if (remainingAppCandidates.isNotEmpty()) {
                add("launchable_apps", JsonArray().apply {
                    remainingAppCandidates.forEach { app ->
                        add(JsonObject().apply {
                            addProperty("label", app.label)
                            addProperty("package_name", app.packageName)
                        })
                    }
                })
            }
            TelegramUiAdapter.inspect(screen)?.let { telegram ->
                add("application_context", JsonObject().apply {
                    addProperty("adapter", "telegram")
                    addProperty("surface", telegram.surface.name.lowercase())
                    if (telegram.targets.isNotEmpty()) {
                        add("semantic_targets", JsonArray().apply {
                            telegram.targets.forEach { target ->
                                plannerIdByElementId[target.elementId]?.let { plannerId ->
                                    add(JsonObject().apply {
                                        addProperty("kind", target.kind)
                                        addProperty("label", target.label)
                                        addProperty("element_id", plannerId)
                                        addProperty("confidence", "exact_unique")
                                    })
                                }
                            }
                        })
                    }
                    if (telegram.ambiguousKinds.isNotEmpty()) {
                        add("ambiguous_targets", JsonArray().apply {
                            telegram.ambiguousKinds.forEach { (kind, count) ->
                                add(JsonObject().apply {
                                    addProperty("kind", kind)
                                    addProperty("count", count)
                                })
                            }
                        })
                    }
                })
            }
            add("elements", elements)
        }.toString()
    }

    private fun plannerActions(element: UiElement): Set<String> =
        if (element.standardActions.isNotEmpty()) {
            element.standardActions
        } else {
            buildSet {
                if (element.clickable || element.checkable) add("click")
                if (element.longClickable) add("long_click")
                if (element.editable) add("set_text")
                if (element.scrollable) {
                    add("scroll_forward")
                    add("scroll_backward")
                }
                if (element.focusable) add("focus")
            }
        }

    private fun plannerRange(range: UiRange): JsonObject = JsonObject().apply {
        addProperty("type", range.type)
        addProperty("min", range.min)
        addProperty("max", range.max)
        addProperty("current", range.current)
    }

    private fun plannerElementLimit(): Int =
        if (isCompactLocalPlanner()) MAX_LOCAL_PROMPT_ELEMENTS else MAX_PROMPT_ELEMENTS

    private fun isCompactLocalPlanner(): Boolean =
        activeBackend is LiteRtLmBackend ||
            activeBackend?.runtimeDescription()?.startsWith("LITERT-LM") == true

    private fun describeConfirmation(action: UiActionStep, reason: String, screen: UiScreenState, goal: String, destination: String?): String {
        val contextLines = screen.elements
            .filter { !it.editable && it.text?.isNotBlank() == true }
            .sortedBy { it.bounds?.top ?: 0 }
            .take(3)
            .joinToString("\n") { "> ${it.text}" }

        val details = when(action) {
            is UiActionStep.Tap -> {
                val element = action.target.elementId?.let { screen.element(it) }
                val label = element?.text?.takeIf(String::isNotBlank) ?: element?.contentDescription?.takeIf(String::isNotBlank)
                val targetDesc = if (label != null) "Target: \"$label\"" else "Target: (unlabeled)"
                val inputs = screen.elements.filter { it.editable && !it.text.isNullOrBlank() }
                    .joinToString("\n") { "Input field contains: ${it.text}" }
                if (inputs.isNotEmpty()) "$targetDesc\n$inputs" else targetDesc
            }
            is UiActionStep.LongPress -> {
                val element = action.target.elementId?.let { screen.element(it) }
                val label = element?.text?.takeIf(String::isNotBlank) ?: element?.contentDescription?.takeIf(String::isNotBlank)
                val targetDesc = if (label != null) "Target: \"$label\"" else "Target: (unlabeled)"
                val inputs = screen.elements.filter { it.editable && !it.text.isNullOrBlank() }
                    .joinToString("\n") { "Input field contains: ${it.text}" }
                if (inputs.isNotEmpty()) "$targetDesc\n$inputs" else targetDesc
            }
            is UiActionStep.OpenApp -> "App: ${action.packageName}"
            is UiActionStep.OpenUrl -> "URL: ${action.url}"
            is UiActionStep.ShareText -> "Text: ${action.text}\nTarget Package: ${action.targetPackage ?: "Any"}"
            is UiActionStep.OpenSettingsPage -> "Page: ${action.page.name}\nTarget Package: ${action.packageName ?: "None"}"
            is UiActionStep.OpenCamera -> "Mode: ${action.mode.name}\nFacing: ${action.facing.name}"
            is UiActionStep.ShareCapturedMedia -> {
                val capture = pendingCapture
                val size = capture?.let { AutomationMediaManager.contentSize(context, it.uri) }
                val hash = capture?.let { AutomationMediaManager.contentSha256(context, it.uri) }
                "Captured media: ${capture?.mimeType ?: "unavailable"}\n" +
                    "Size: ${size?.let { "$it bytes" } ?: "unknown"}\n" +
                    "SHA-256: ${hash ?: "unavailable"}\n" +
                    "Target Package: ${action.targetPackage}\n" +
                    "Expected Destination: ${action.expectedDestination}"
            }
            else -> ""
        }
        val contextStr = if (contextLines.isNotEmpty()) "\n\nContext:\n$contextLines" else ""
        val destStr = if (!destination.isNullOrBlank() && destination != "UNKNOWN") "\n\nRecipient/Destination: $destination" else ""
        return if (details.isNotEmpty()) {
            "$reason\n\nGoal: $goal$destStr$contextStr\n\nAction: ${action::class.simpleName}\n$details"
        } else {
            "$reason\n\nGoal: $goal$destStr$contextStr\n\nAction: ${action::class.simpleName}"
        }
    }
    private fun expectedDestinationForCommit(action: UiActionStep, screen: UiScreenState): String? =
        pendingExternalEffect
            ?.takeIf { it.targetPackage == screen.packageName && isMessagingCommitBoundary(action, screen) }
            ?.expectedDestination

    private fun isMessagingCommitBoundary(action: UiActionStep, screen: UiScreenState): Boolean {
        if (!DestinationResolver.isSupported(screen.packageName)) return false
        val target = when (action) {
            is UiActionStep.Tap -> action.target
            is UiActionStep.LongPress -> action.target
            is UiActionStep.NodeAction -> action.target.takeIf {
                action.action in setOf("click", "long_click", "context_click", "press_and_hold", "ime_enter")
            } ?: return false
            is UiActionStep.CustomAction -> action.target
            is UiActionStep.Gesture -> action.target
            else -> return false
        }
        val element = target.elementId?.let(screen::element)
        val evidence = listOfNotNull(
            element?.text,
            element?.contentDescription,
            element?.resourceId,
            target.textContains
        ).joinToString(" ").lowercase()
        return SEND_TARGET_TERMS.any(evidence::contains)
    }

    private fun confirmationContentHash(action: UiActionStep, screen: UiScreenState, goal: String): String =
        pendingExternalEffect
            ?.takeIf { isMessagingCommitBoundary(action, screen) }
            ?.contentHash
            ?: if (action is UiActionStep.ShareCapturedMedia) {
                pendingCapture?.let { AutomationMediaManager.contentSha256(context, it.uri) }
                    ?: hashContent(action.toJson().toString(), goal)
            } else {
                hashContent(action.toJson().toString(), goal)
            }

    private fun goalExplicitlyNamesDestination(goal: String, destination: String): Boolean {
        val normalizedGoal = goal.lowercase().replace(Regex("\\s+"), " ").trim()
        val normalizedDestination = destination.lowercase().replace(Regex("\\s+"), " ").trim()
        if (normalizedDestination.isBlank()) return false
        if (normalizedGoal.contains(normalizedDestination)) return true
        val destinationDigits = destination.filter(Char::isDigit)
        return destinationDigits.length >= 7 && goal.filter(Char::isDigit).contains(destinationDigits)
    }

    private fun hashContent(vararg inputs: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (input in inputs) {
            val bytes = input.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractJson(raw: String): String {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (trimmed.equals("done", ignoreCase = true)) {
            return """{"action":"done","summary":"Completed the requested UI workflow."}"""
        }
        val arrayStart = trimmed.indexOf('[')
        val arrayEnd = trimmed.lastIndexOf(']')
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (arrayStart >= 0 && arrayEnd > arrayStart && (start < 0 || arrayStart < start)) {
            return trimmed.substring(arrayStart, arrayEnd + 1)
        }
        require(start >= 0 && end > start) { "No JSON object found." }
        return trimmed.substring(start, end + 1)
    }

    private fun success(output: String) = ToolResult(CONTROL_UI_TOOL, output)
    private fun failure(output: String) = ToolResult(CONTROL_UI_TOOL, output, true)
    private fun trace(name: String, fields: Map<String, Any?> = emptyMap()) {
        activeTrace?.emit(name, fields)
    }

    private fun progress(phase: String, detail: String) {
        onProgress(phase, detail)
        activeSessionId?.let { sessionId ->
            AutomationSessionManager.progress(sessionId, phase, detail)
        }
    }

    private fun actionLabel(action: UiActionStep): String = when (action) {
        is UiActionStep.Tap -> "tap"
        is UiActionStep.Focus -> "focus"
        is UiActionStep.NodeAction -> action.action.replace('_', ' ')
        is UiActionStep.CustomAction -> "custom action ${action.actionRef}"
        is UiActionStep.Gesture -> action.gesture.replace('_', ' ')
        is UiActionStep.LongPress -> "long press"
        is UiActionStep.TypeText -> "type text"
        UiActionStep.PressBack -> "press back"
        UiActionStep.PressHome -> "press home"
        is UiActionStep.Scroll -> "scroll ${action.direction.name.lowercase()}"
        UiActionStep.PressRecents -> "press recents"
        UiActionStep.OpenNotifications -> "open notifications"
        UiActionStep.OpenQuickSettings -> "open quick settings"
        is UiActionStep.GlobalAction -> action.action.replace('_', ' ')
        is UiActionStep.Wait -> "wait"
        is UiActionStep.Assert -> "assert"
        is UiActionStep.AskUser -> "ask user"
        is UiActionStep.Done -> "done"
        is UiActionStep.OpenApp -> "open app ${action.packageName}"
        is UiActionStep.OpenSettingsPage -> "open settings ${action.page.name.lowercase()}"
        is UiActionStep.OpenUrl -> "open url"
        is UiActionStep.ShareText -> "share text"
        is UiActionStep.OpenCamera -> "open camera"
        is UiActionStep.ShareCapturedMedia -> "share captured media"
    }

    private fun actionFamily(action: UiActionStep): String = when (action) {
        is UiActionStep.NodeAction, is UiActionStep.Focus -> "semantic_node"
        is UiActionStep.CustomAction -> "custom_accessibility_action"
        is UiActionStep.Gesture -> "gesture"
        is UiActionStep.Tap, is UiActionStep.LongPress, is UiActionStep.TypeText, is UiActionStep.Scroll -> "ui_action"
        is UiActionStep.GlobalAction,
        UiActionStep.PressBack,
        UiActionStep.PressHome,
        UiActionStep.PressRecents,
        UiActionStep.OpenNotifications,
        UiActionStep.OpenQuickSettings -> "global_action"
        is UiActionStep.OpenApp,
        is UiActionStep.OpenSettingsPage,
        is UiActionStep.OpenUrl,
        is UiActionStep.ShareText,
        is UiActionStep.OpenCamera,
        is UiActionStep.ShareCapturedMedia -> "typed_android"
        is UiActionStep.Wait, is UiActionStep.Assert, is UiActionStep.AskUser, is UiActionStep.Done -> "internal"
    }

    private fun executionMechanism(action: UiActionStep, result: ToolResult?): String {
        result?.let { toolResult ->
            runCatching { JSONObject(toolResult.output).optString("mechanism") }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?.let { return it }
        }
        return when (action) {
            is UiActionStep.Gesture -> "gesture"
            is UiActionStep.CustomAction -> "custom_accessibility_action"
            is UiActionStep.NodeAction, is UiActionStep.Focus -> "semantic_node"
            is UiActionStep.GlobalAction,
            UiActionStep.PressBack,
            UiActionStep.PressHome,
            UiActionStep.PressRecents,
            UiActionStep.OpenNotifications,
            UiActionStep.OpenQuickSettings -> "global_action"
            is UiActionStep.OpenApp,
            is UiActionStep.OpenSettingsPage,
            is UiActionStep.OpenUrl,
            is UiActionStep.ShareText,
            is UiActionStep.OpenCamera,
            is UiActionStep.ShareCapturedMedia -> "trusted_intent"
            else -> "accessibility"
        }
    }

    private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

    private fun markPhysicalActionDispatch(action: UiActionStep): Long {
        val now = monotonicNowMs()
        val fields = linkedMapOf<String, Any?>(
            "action" to actionLabel(action),
            "elapsed_ms" to (now - invocationStartedMonotonicMs).coerceAtLeast(0L)
        )
        if (!firstPhysicalActionRecorded) {
            firstPhysicalActionRecorded = true
            fields["invocation_to_first_action_ms"] =
                (now - invocationStartedMonotonicMs).coerceAtLeast(0L)
        }
        trace("action_dispatch_started", fields)
        return now
    }

    companion object {
        const val CONTROL_UI_TOOL = "control_ui"
        private const val TRUSTED_EVENT_MAX_AGE_MS = 2_000L
        val sessionMutex = Mutex()
        private const val MAX_PROMPT_ELEMENTS = 32
        private const val MAX_LOCAL_PROMPT_ELEMENTS = 12
        private const val PLANNER_TIMEOUT_MS = 45_000L
        private const val INITIAL_OBSERVATION_DELAY_MS = 75L
        private const val INITIAL_OBSERVATION_TIMEOUT_MS = 1_500L
        private const val INITIAL_EVENT_WAIT_SLICE_MS = 250L
        private const val INITIAL_RETRY_DELAY_MS = 40L
        private val PLANNER_OPERATION_TOKENS = Operation.entries.map { it.name.lowercase() }
        private val PLANNER_OPERATION_SCHEMA = PLANNER_OPERATION_TOKENS.joinToString(",", "[", "]") { "\"$it\"" }
        private val PLANNER_OPERATION_PROMPT = PLANNER_OPERATION_TOKENS.joinToString(", ")

        fun buildDecisionTools(screen: UiScreenState, limit: Int = MAX_PROMPT_ELEMENTS): List<LlmToolDefinition> {
            val candidates = screen.plannerElements(limit)
            val targetEnumJson = if (candidates.isNotEmpty()) {
                candidates.indices.joinToString(",", "[", "]") { "\"p$it\"" }
            } else {
                "[\"p0\"]"
            }
            return listOf(
                LlmToolDefinition(
                    name = "ui_act",
                    description = "Choose exactly one next Android UI action from the supplied screen state.",
                    parametersJson = """
                        {
                          "type":"object",
                          "properties":{
                            "op":{"type":"string","enum":$PLANNER_OPERATION_SCHEMA},
                            "target":{"type":"string","enum":$targetEnumJson,"description":"Supplied candidate ID."},
                            "expect":{"type":"string","enum":["surface_change","mutation","content_appear","no_change"]},
                            "text":{"type":"string"},
                            "direction":{"type":"string","enum":["up","down","left","right"]},
                            "package_name":{"type":"string"},
                            "page":{"type":"string"},
                            "url":{"type":"string"},
                            "mode":{"type":"string"},
                            "facing":{"type":"string"},
                            "ms":{"type":"integer"},
                            "target_package":{"type":"string"},
                            "expected_destination":{"type":"string"},
                            "action":{"type":"string"},
                            "action_ref":{"type":"string","pattern":"^ca[0-9]+$"},
                            "value":{"type":"number"},
                            "global_action":{"type":"string"},
                            "gesture":{"type":"string","enum":["tap_point","double_tap","long_press_point","press_and_hold","swipe_path","drag_drop","polyline_drag","pinch_in","pinch_out","two_finger_swipe"]},
                            "destination_target":{"type":"string","enum":$targetEnumJson}
                          },
                          "required":["op"],
                          "additionalProperties":false
                        }
                    """.trimIndent()
                ),
                LlmToolDefinition(
                    name = "ui_ask",
                    description = "Ask the user one question only when the target or required information is ambiguous.",
                    parametersJson = """{"type":"object","properties":{"question":{"type":"string"}},"required":["question"],"additionalProperties":false}"""
                ),
                LlmToolDefinition(
                    name = "ui_finish",
                    description = "Finish only when current screen state or successful action history proves the goal is complete.",
                    parametersJson = """{"type":"object","properties":{"outcome":{"type":"string"}},"required":["outcome"],"additionalProperties":false}"""
                )
            )
        }

        private val JSON_RETRY_SYSTEM_PROMPT = """
            Return exactly one V3 JSON object and no other text.
            Act: {"v":3,"kind":"act","op":"<operation>","target":"<target_id>","expect":"<expected_effect>"}
            Ask: {"v":3,"kind":"ask","question":"<clarification_question>"}
            Finish: {"v":3,"kind":"finish","outcome":"<completion_summary>"}
            Put action inputs in "args", for example:
            {"v":3,"kind":"act","op":"type_text","target":"<target_id>","args":{"text":"<text>"},"expect":"mutation"}
            {"v":3,"kind":"act","op":"scroll","target":"<target_id>","args":{"direction":"down"},"expect":"mutation"}
            {"v":3,"kind":"act","op":"open_app","args":{"package_name":"<package_name>"},"expect":"surface_change"}
            Allowed ops: $PLANNER_OPERATION_PROMPT.
            For node_action, supply args.action using only an action advertised on that target.
            For custom_action, supply args.action_ref using only a caN ref advertised on that target.
            For global_action, supply args.global_action using only a token in current system_actions.
            Gesture mechanics are one bounded operation owned by Kotlin; never split pointer down/move/up across turns.
            Required args: scroll direction; open_app package_name; open_settings_page page and optional package_name;
            open_url url; open_camera mode and facing; wait ms; share_text text and optional target_package/expected_destination;
            share_captured_media target_package and expected_destination.
            Use only supplied pN target IDs and exact supplied package names. Never use Markdown.
        """.trimIndent()

        private val PLANNER_SYSTEM_PROMPT = """
            Choose exactly one next Android UI decision. Return one V3 JSON object only, without Markdown.
            Act: {"v":3,"kind":"act","op":"<operation>","target":"<target_id>","expect":"<expected_effect>"}
            Ask: {"v":3,"kind":"ask","question":"<clarification_question>"}
            Finish: {"v":3,"kind":"finish","outcome":"<completion_summary>"}
            Put action inputs in "args", for example:
            {"v":3,"kind":"act","op":"type_text","target":"<target_id>","args":{"text":"<text>"},"expect":"mutation"}
            {"v":3,"kind":"act","op":"scroll","target":"<target_id>","args":{"direction":"down"},"expect":"mutation"}
            {"v":3,"kind":"act","op":"open_app","args":{"package_name":"<package_name>"},"expect":"surface_change"}
            Allowed ops: $PLANNER_OPERATION_PROMPT.
            Prefer node_action with an action advertised on the target; use custom_action only with that target's advertised caN ref.
            Use global_action only with a token in current system_actions.
            Use gesture only when semantic node/global actions are unavailable; gesture coordinates must be normalized.
            Required args: scroll direction; open_app package_name; open_settings_page page and optional package_name;
            open_url url; open_camera mode and facing; wait ms; share_text text and optional target_package/expected_destination;
            share_captured_media target_package and expected_destination.
            Use IDs and package names exactly as supplied. Never invent targets, destinations, or completion.
            Do not target passwords or enter secrets, OTPs, payment data, or authentication data.
            Ask when the target is ambiguous or information is missing. Finish only when current state or successful history proves completion.
            Never select a messaging destination listed in ambiguous_targets. Trusted code handles confirmation and verification.
            Never plan payments, purchases, account deletion, factory reset, permission escalation, or unknown APK installation.
        """.trimIndent()
    }

    private fun getTargetElementId(action: UiActionStep): String? = when(action) {
        is UiActionStep.Tap -> action.target.elementId
        is UiActionStep.Focus -> action.target.elementId
        is UiActionStep.NodeAction -> action.target.elementId
        is UiActionStep.CustomAction -> action.target.elementId
        is UiActionStep.Gesture -> action.target.elementId
        is UiActionStep.LongPress -> action.target.elementId
        is UiActionStep.TypeText -> action.target?.elementId
        is UiActionStep.Scroll -> action.target?.elementId
        is UiActionStep.OpenApp,
        is UiActionStep.OpenSettingsPage,
        is UiActionStep.OpenUrl,
        is UiActionStep.ShareText,
        is UiActionStep.OpenCamera,
        is UiActionStep.ShareCapturedMedia,
        is UiActionStep.PressBack,
        is UiActionStep.PressHome,
        is UiActionStep.PressRecents,
        is UiActionStep.OpenNotifications,
        is UiActionStep.OpenQuickSettings,
        is UiActionStep.GlobalAction,
        is UiActionStep.Wait,
        is UiActionStep.AskUser,
        is UiActionStep.Done,
        is UiActionStep.Assert -> null
    }

    private fun getTargetLabel(action: UiActionStep, screen: UiScreenState): String? {
        val id = getTargetElementId(action) ?: return null
        return screen.element(id)?.let { screen.plannerLabel(it) }
    }

    private val SEND_TARGET_TERMS = setOf("send", "send_message", "send button", "send_button")
}

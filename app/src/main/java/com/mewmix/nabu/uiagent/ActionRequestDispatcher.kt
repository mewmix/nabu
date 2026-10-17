package com.mewmix.nabu.uiagent

import android.content.Context
import android.content.Intent
import com.mewmix.nabu.ChatActivity
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmBackendFactory
import com.mewmix.nabu.data.Model
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.ModelType
import com.mewmix.nabu.data.OAuthRemoteModels
import com.mewmix.nabu.data.ConversationRepository
import com.mewmix.nabu.tools.ToolResult
import com.mewmix.nabu.utils.DebugLogger
import com.mewmix.nabu.utils.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.util.UUID

object ActionRequestDispatcher {
    private const val TAG = "ActionRequestDispatcher"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val dispatchMutex = Mutex()
    private val backendMutex = Mutex()
    private val lock = Any()

    private val _activeSession = MutableStateFlow<ActionSession?>(null)
    val activeSession = _activeSession.asStateFlow()

    private var activeJob: Job? = null
    private val ownership = ActionRequestOwnership()
    private var cachedBackend: Pair<String, LlmBackend>? = null
    private var backendUseCount = 0
    private var pendingBackendCloseReason: String? = null
    private var idleUnloadJob: Job? = null
    private var accessibilityConnected = false
    private val _actionModelLifecycle = MutableStateFlow(ActionModelLifecycleState())
    val actionModelLifecycle = _actionModelLifecycle.asStateFlow()
    private val metricRuntimeEvents = setOf(
        "observation_captured",
        "routing_started",
        "routing_completed",
        "planner_request_started",
        "planner_first_response",
        "planner_output_received",
        "action_dispatch_started",
        "action_dispatch_completed",
        "verification_started",
        "verification_completed"
    )

    fun submitRequest(
        context: Context,
        request: String,
        mode: ActionSessionMode = ActionSessionMode.SINGLE_TURN,
        preferredModelId: String? = null,
        requestConfirmation: (suspend (String) -> Boolean)? = null,
        onStep: ((ActionStepRecord) -> Unit)? = null,
        onComplete: ((ToolResult) -> Unit)? = null
    ): ActionSession {
        cancelIdleUnload()
        val requestReceivedMs = System.currentTimeMillis()
        val normalized = request.trim()
        val (session, epoch) = synchronized(lock) {
            activeJob?.cancel()
            val newSession = ActionSession(
                id = UUID.randomUUID().toString(),
                mode = mode,
                status = ActionSessionStatus.ROUTING,
                originalGoal = normalized,
                currentGoal = normalized,
                metrics = ActionSessionMetrics(
                    requestReceivedMs = requestReceivedMs,
                    sessionCreatedMs = System.currentTimeMillis()
                )
            )
            val epoch = ownership.begin(newSession.id)
            _activeSession.value = newSession
            newSession to epoch
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            dispatchMutex.withLock {
                if (!isOwner(session.id, epoch)) return@withLock
                val result = executeSessionTurn(
                    context = context.applicationContext,
                    session = session,
                    goal = normalized,
                    preferredModelId = preferredModelId,
                    requestConfirmation = requestConfirmation,
                    onStep = onStep,
                epoch = epoch
            )
                val published = updateOwned(session.id, epoch) { current ->
                    val endMs = System.currentTimeMillis()
                    val updatedMetrics = current.metrics.copy(
                        totalSessionLatencyMs = (endMs - current.metrics.requestReceivedMs).coerceAtLeast(0L)
                    )
                    current.copy(
                        status = if (result.isError) ActionSessionStatus.FAILED else ActionSessionStatus.COMPLETED,
                        lastVerificationResult = result.output,
                        metrics = updatedMetrics,
                        updatedAtMs = endMs
                    )
                }
                synchronized(lock) { if (isOwnerLocked(session.id, epoch)) activeJob = null }
                retainOrUnloadAfterSession(context.applicationContext)
                if (published) onComplete?.invoke(result)
            }
        }
        synchronized(lock) {
            if (isOwnerLocked(session.id, epoch)) activeJob = job else job.cancel()
        }
        job.start()

        return session
    }

    fun submitFollowUp(
        context: Context,
        sessionId: String,
        followUpRequest: String,
        preferredModelId: String? = null,
        requestConfirmation: (suspend (String) -> Boolean)? = null,
        onStep: ((ActionStepRecord) -> Unit)? = null,
        onComplete: ((ToolResult) -> Unit)? = null
    ): ActionSession? {
        cancelIdleUnload()
        val normalized = followUpRequest.trim()
        val (session, epoch) = synchronized(lock) {
            val current = _activeSession.value ?: return null
            if (current.id != sessionId) return null
            activeJob?.cancel()
            val updated = current.copy(
                currentGoal = normalized,
                status = ActionSessionStatus.ROUTING,
                updatedAtMs = System.currentTimeMillis()
            )
            val epoch = ownership.begin(updated.id)
            _activeSession.value = updated
            updated to epoch
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            dispatchMutex.withLock {
                if (!isOwner(session.id, epoch)) return@withLock
                val result = executeSessionTurn(
                    context = context.applicationContext,
                    session = session,
                    goal = normalized,
                    preferredModelId = preferredModelId,
                    requestConfirmation = requestConfirmation,
                    onStep = onStep,
                    epoch = epoch
                )
                val published = updateOwned(sessionId, epoch) { current ->
                    val endMs = System.currentTimeMillis()
                    current.copy(
                        status = if (result.isError) ActionSessionStatus.FAILED else ActionSessionStatus.COMPLETED,
                        lastVerificationResult = result.output,
                        metrics = current.metrics.copy(
                            totalSessionLatencyMs = (endMs - current.metrics.requestReceivedMs).coerceAtLeast(0L)
                        ),
                        updatedAtMs = endMs
                    )
                }
                synchronized(lock) { if (isOwnerLocked(session.id, epoch)) activeJob = null }
                retainOrUnloadAfterSession(context.applicationContext)
                if (published) onComplete?.invoke(result)
            }
        }
        synchronized(lock) {
            if (isOwnerLocked(session.id, epoch)) activeJob = job else job.cancel()
        }
        job.start()

        return session
    }

    fun cancelSession(sessionId: String) {
        synchronized(lock) {
            val current = _activeSession.value ?: return
            if (current.id == sessionId) {
                ownership.invalidate(sessionId)
                activeJob?.cancel()
                activeJob = null
                val updated = current.copy(
                    status = ActionSessionStatus.CANCELLED,
                    updatedAtMs = System.currentTimeMillis()
                )
                _activeSession.value = updated
                AutomationSessionManager.cancelActive()
            }
        }
    }

    fun clearSession(sessionId: String) {
        synchronized(lock) {
            if (_activeSession.value?.id == sessionId) {
                ownership.invalidate(sessionId)
                activeJob?.cancel()
                activeJob = null
                _activeSession.value = null
            }
        }
    }

    fun onAccessibilityConnectionChanged(context: Context, connected: Boolean) {
        synchronized(lock) { accessibilityConnected = connected }
        if (connected && SettingsManager.keepActionModelReady(context)) {
            cancelIdleUnload()
            warmActionModel(context, "accessibility_connected")
        } else {
            scheduleIdleUnload(context.applicationContext, ACTION_MODEL_IDLE_TIMEOUT_MS)
        }
    }

    fun onActionModelSettingsChanged(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            dispatchMutex.withLock {
                closeCachedBackend("settings_changed")
                if (SettingsManager.keepActionModelReady(appContext) &&
                    synchronized(lock) { accessibilityConnected }
                ) {
                    resolveOrInitBackend(appContext, preferredModelId = null)
                }
            }
        }
    }

    fun warmActionModel(context: Context, reason: String = "requested") {
        if (!SettingsManager.keepActionModelReady(context)) return
        val appContext = context.applicationContext
        scope.launch {
            DebugLogger.log("$TAG: warming Action Model reason=$reason")
            resolveOrInitBackend(appContext, preferredModelId = null)
        }
    }

    internal suspend fun acquireEligibleActionBackend(
        context: Context,
        preferredModelId: String? = null
    ): LlmBackend? = backendMutex.withLock {
        val backend = resolveOrInitBackendLocked(context.applicationContext, preferredModelId)
        if (backend != null && synchronized(lock) { cachedBackend?.second === backend }) {
            synchronized(lock) { backendUseCount += 1 }
        }
        backend
    }

    internal fun releaseEligibleActionBackend(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            var warmAfterClose = false
            backendMutex.withLock {
                val closeReason = synchronized(lock) {
                    backendUseCount = (backendUseCount - 1).coerceAtLeast(0)
                    pendingBackendCloseReason.takeIf { backendUseCount == 0 }
                        ?.also { pendingBackendCloseReason = null }
                }
                if (closeReason != null) {
                    closeCachedBackendLocked(closeReason)
                    warmAfterClose = SettingsManager.keepActionModelReady(appContext) &&
                        synchronized(lock) { accessibilityConnected }
                }
            }
            if (warmAfterClose) resolveOrInitBackend(appContext, preferredModelId = null)
        }
    }

    internal fun isEligibleActionBackendReady(
        context: Context,
        preferredModelId: String? = null
    ): Boolean = isConfiguredBackendReady(context.applicationContext, preferredModelId)

    fun onTrimMemory(level: Int) {
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        scope.launch {
            dispatchMutex.withLock {
                closeCachedBackend("memory_pressure_$level")
            }
        }
    }

    fun handoffToChat(context: Context, sessionId: String) {
        val session = synchronized(lock) {
            val current = _activeSession.value ?: return
            if (current.id != sessionId) return
            // If currently running, suspend execution safely
            if (current.status in listOf(ActionSessionStatus.ROUTING, ActionSessionStatus.PLANNING, ActionSessionStatus.OBSERVING, ActionSessionStatus.EXECUTING, ActionSessionStatus.VERIFYING)) {
                AutomationSessionManager.suspendActive("Handoff to Chat screen requested by user.")
            }
            activeJob?.cancel()
            ownership.invalidate(sessionId)
            activeJob = null
            val updated = current.copy(
                status = ActionSessionStatus.IDLE,
                mode = ActionSessionMode.HANDOFF_TO_CHAT,
                updatedAtMs = System.currentTimeMillis()
            )
            _activeSession.value = updated
            updated
        }

        val intent = Intent(context, ChatActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("extra_handoff_session_id", session.id)
        }
        context.startActivity(intent)
    }

    private suspend fun executeSessionTurn(
        context: Context,
        session: ActionSession,
        goal: String,
        preferredModelId: String?,
        requestConfirmation: (suspend (String) -> Boolean)?,
        onStep: ((ActionStepRecord) -> Unit)?,
        epoch: Long
    ): ToolResult {
        val turn = ActionConversationTurn(
            userRequest = goal,
            timestampMs = System.currentTimeMillis()
        )
        val turnSteps = mutableListOf<ActionStepRecord>()

        val orchestrator = UiAutomationOrchestrator(
            context = context,
            backendProvider = { acquireEligibleActionBackend(context, preferredModelId) },
            backendReadyProvider = { isConfiguredBackendReady(context, preferredModelId) },
            onBackendReleased = { releaseEligibleActionBackend(context) },
            requestConfirmation = { prompt ->
                updateOwned(session.id, epoch) { current ->
                    current.copy(
                        status = ActionSessionStatus.WAITING_FOR_USER,
                        pendingObjective = prompt,
                        updatedAtMs = System.currentTimeMillis()
                    )
                }
                (requestConfirmation ?: { p ->
                    DebugLogger.log("ActionRequestDispatcher: confirmation requested without interactive handler: $p")
                    false
                }).invoke(prompt)
            },
            budget = AutomationBudget(maxExecutedActions = 14),
            isScheduled = false,
            priorTurns = session.turns,
            onProgress = { phase, detail ->
                updateOwned(session.id, epoch) { current ->
                    current.copy(
                        status = actionStatusForProgress(phase, current.status),
                        pendingObjective = detail,
                        updatedAtMs = System.currentTimeMillis()
                    )
                }
            },
            onStepRecord = { stepRecord ->
                val published = updateOwned(session.id, epoch) { current ->
                    val updatedSteps = ActionSessionWorkingMemory.appendStep(
                        current.stepHistory,
                        stepRecord
                    )
                    val stepVerificationStatus = parseVerificationStatus(
                        stepRecord.goalVerificationStatus ?: stepRecord.verificationStatus
                    )
                    current.copy(
                        stepHistory = updatedSteps,
                        currentStep = stepRecord.sequence,
                        lastObservedPackage = stepRecord.resultPackage ?: stepRecord.sourcePackage,
                        lastObservedWindow = stepRecord.resultWindow ?: stepRecord.sourceWindow,
                        lastVerificationStatus = stepVerificationStatus ?: current.lastVerificationStatus,
                        metrics = current.metrics.copy(
                            totalStepLatencyMs = current.metrics.totalStepLatencyMs + stepRecord.latencyMs
                        ),
                        updatedAtMs = System.currentTimeMillis()
                    )
                }
                if (published) {
                    turnSteps += stepRecord
                    onStep?.invoke(stepRecord)
                }
            },
            externalSessionId = session.id,
            onRuntimeEvent = { name, _, fields ->
                if (name in metricRuntimeEvents) {
                    val occurredAtMs = System.currentTimeMillis()
                    updateOwned(session.id, epoch) { current ->
                        current.copy(
                            metrics = current.metrics.recordRuntimeEvent(
                                name = name,
                                occurredAtMs = occurredAtMs,
                                fields = fields
                            ),
                            updatedAtMs = occurredAtMs
                        )
                    }
                }
            },
            logger = { line ->
                DebugLogger.log("ActionSession[${session.id.take(8)}]: $line")
            }
        )

        val result = orchestrator.run(goal)
        updateOwned(session.id, epoch) { current ->
            val terminalStatus = turnSteps.lastOrNull()
                ?.let { parseVerificationStatus(it.goalVerificationStatus ?: it.verificationStatus) }
                ?: if (!result.isError) UiVerificationStatus.GOAL_VERIFIED else UiVerificationStatus.FAILED
            val finalTurn = turn.copy(
                assistantResponse = result.output,
                stepRecords = turnSteps.toList(),
                isComplete = true,
                isError = result.isError || !terminalStatus.isGoalVerified,
                verificationStatus = terminalStatus
            )
            val updatedTurns = ActionSessionWorkingMemory.appendTurn(current.turns, finalTurn)
            current.copy(
                turns = updatedTurns,
                lastVerificationResult = result.output,
                lastVerificationStatus = terminalStatus,
                updatedAtMs = System.currentTimeMillis()
            )
        }
        return result
    }

    private fun parseVerificationStatus(raw: String?): UiVerificationStatus? =
        raw?.uppercase()?.let { token ->
            UiVerificationStatus.entries.firstOrNull { it.name == token }
        }

    private fun isOwner(sessionId: String, epoch: Long): Boolean = synchronized(lock) {
        isOwnerLocked(sessionId, epoch)
    }

    private fun isOwnerLocked(sessionId: String, epoch: Long): Boolean =
        ownership.owns(sessionId, epoch) && _activeSession.value?.id == sessionId

    private fun updateOwned(
        sessionId: String,
        epoch: Long,
        transform: (ActionSession) -> ActionSession
    ): Boolean = synchronized(lock) {
        if (!isOwnerLocked(sessionId, epoch)) return@synchronized false
        val current = _activeSession.value ?: return@synchronized false
        _activeSession.value = transform(current)
        true
    }

    private suspend fun resolveOrInitBackend(context: Context, preferredModelId: String?): LlmBackend? =
        backendMutex.withLock { resolveOrInitBackendLocked(context, preferredModelId) }

    private fun isConfiguredBackendReady(context: Context, preferredModelId: String?): Boolean {
        val cachedId = synchronized(lock) { cachedBackend?.first } ?: return false
        val configuredId = preferredModelId?.takeIf(String::isNotBlank)
            ?: SettingsManager.getActionModelId(context)
        return configuredId.isNullOrBlank() ||
            cachedId == configuredId ||
            cachedId.contains(configuredId, ignoreCase = true)
    }

    private suspend fun resolveOrInitBackendLocked(
        context: Context,
        preferredModelId: String?
    ): LlmBackend? = withContext(Dispatchers.IO) {
        val modelManager = ModelManager(context)
        val downloaded = modelManager.models.filter {
            it.isDownloaded && ActionModelEligibility.isEligible(it)
        }
        val remote = OAuthRemoteModels.connectedModels(context)
        val available = (downloaded + remote)
            .filter(ActionModelEligibility::isEligible)
            .distinctBy { it.id }
        if (available.isEmpty()) return@withContext null

        val configuredModelId = preferredModelId?.takeIf(String::isNotBlank)
            ?: SettingsManager.getActionModelId(context)
        val selectedModel = if (!configuredModelId.isNullOrBlank()) {
            available.firstOrNull {
                it.id == configuredModelId || it.id.contains(configuredModelId, ignoreCase = true)
            }
        } else {
            // Prioritize fast on-device action models first
            available.firstOrNull { it.isDownloaded && it.type == ModelType.LLM && (
                it.id.contains("gemma-4-E2B", ignoreCase = true) ||
                it.id.contains("qwen", ignoreCase = true) ||
                it.id.contains("2b", ignoreCase = true) ||
                it.id.contains("3b", ignoreCase = true)
            ) } ?: preferredRecentModel(context, available) ?: available.first()
        } ?: run {
            DebugLogger.log("ActionRequestDispatcher: configured action model '$configuredModelId' is unavailable")
            return@withContext null
        }

        _actionModelLifecycle.value = ActionModelLifecycleState(
            phase = ActionModelLifecyclePhase.SELECTED,
            modelId = selectedModel.id
        )

        synchronized(lock) {
            cachedBackend?.let { (id, backend) ->
                if (id == selectedModel.id) {
                    _actionModelLifecycle.value = ActionModelLifecycleState(
                        phase = ActionModelLifecyclePhase.READY,
                        modelId = id,
                        initializationMs = 0L
                    )
                    return@withContext backend
                } else {
                    if (backendUseCount > 0) {
                        pendingBackendCloseReason = "model_selection_changed"
                        return@withContext null
                    }
                    runCatching { backend.close() }
                    cachedBackend = null
                }
            }
        }

        DebugLogger.log("ActionRequestDispatcher: initializing action model ${selectedModel.id}")
        _actionModelLifecycle.value = ActionModelLifecycleState(
            phase = ActionModelLifecyclePhase.WARMING,
            modelId = selectedModel.id
        )
        val initializationStartedMs = System.nanoTime() / 1_000_000L
        val created = LlmBackendFactory.create(
            context = context,
            modelId = selectedModel.id,
            initializeSynchronously = true
        ) ?: run {
            _actionModelLifecycle.value = ActionModelLifecycleState(
                phase = ActionModelLifecyclePhase.FAILED,
                modelId = selectedModel.id,
                reason = "backend_initialization_failed"
            )
            return@withContext null
        }

        val initializationMs = (System.nanoTime() / 1_000_000L - initializationStartedMs)
            .coerceAtLeast(0L)

        synchronized(lock) {
            cachedBackend = selectedModel.id to created.backend
        }
        _actionModelLifecycle.value = ActionModelLifecycleState(
            phase = ActionModelLifecyclePhase.READY,
            modelId = selectedModel.id,
            initializationMs = initializationMs
        )
        DebugLogger.log("$TAG: Action Model ready model=${selectedModel.id} initializationMs=$initializationMs")
        created.backend
    }

    private fun retainOrUnloadAfterSession(context: Context) {
        if (synchronized(lock) { accessibilityConnected } &&
            SettingsManager.keepActionModelReady(context)
        ) {
            cancelIdleUnload()
        } else {
            scheduleIdleUnload(context, ACTION_MODEL_IDLE_TIMEOUT_MS)
        }
    }

    private fun scheduleIdleUnload(context: Context, delayMs: Long) {
        synchronized(lock) {
            idleUnloadJob?.cancel()
            idleUnloadJob = scope.launch {
                delay(delayMs)
                dispatchMutex.withLock {
                    val shouldRemainWarm = synchronized(lock) { accessibilityConnected } &&
                        SettingsManager.keepActionModelReady(context)
                    if (!shouldRemainWarm) closeCachedBackend("idle_timeout")
                }
            }
        }
    }

    private fun cancelIdleUnload() {
        synchronized(lock) {
            idleUnloadJob?.cancel()
            idleUnloadJob = null
        }
    }

    private suspend fun closeCachedBackend(reason: String) = backendMutex.withLock {
        if (synchronized(lock) { backendUseCount > 0 }) {
            synchronized(lock) { pendingBackendCloseReason = reason }
            return@withLock
        }
        closeCachedBackendLocked(reason)
    }

    private fun closeCachedBackendLocked(reason: String) {
        val cached = synchronized(lock) {
            cachedBackend.also { cachedBackend = null }
        }
        cached?.let { (modelId, backend) ->
            runCatching { backend.close() }
            DebugLogger.log("$TAG: unloaded Action Model model=$modelId reason=$reason")
        }
        _actionModelLifecycle.value = ActionModelLifecycleState(
            phase = ActionModelLifecyclePhase.UNLOADED,
            reason = reason
        )
    }

    private fun preferredRecentModel(context: Context, available: List<Model>): Model? {
        val modelsById = available.associateBy { it.id }
        val normalizedModelsById = available.associateBy { OAuthRemoteModels.normalizeModelId(it.id) }
        ConversationRepository.getConversationSummaries(context)
            .sortedByDescending { it.updatedAt }
            .forEach { summary ->
                val modelId = summary.modelId ?: return@forEach
                modelsById[modelId]?.let { return it }
                normalizedModelsById[OAuthRemoteModels.normalizeModelId(modelId)]?.let { return it }
            }
        return null
    }

    private const val ACTION_MODEL_IDLE_TIMEOUT_MS = 10 * 60 * 1_000L
}

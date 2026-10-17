package com.mewmix.nabu.uiagent

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AutomationSessionState {
    QUEUED,
    STARTING,
    RUNNING,
    SUSPENDED,
    WAITING_FOR_USER,
    CANCELLING,
    COMPLETED,
    FAILED
}

data class AutomationSessionSnapshot(
    val sessionId: String,
    val originalGoal: String,
    val currentGoal: String,
    val state: AutomationSessionState,
    val phase: String,
    val detail: String,
    val actionCount: Int,
    val updatedAtMs: Long,
    val runnerAttached: Boolean,
    val pendingQuestion: String? = null
)

sealed interface AutomationSessionDirective {
    data class Run(val goal: String) : AutomationSessionDirective
    data object Cancel : AutomationSessionDirective
}

data class AutomationCommandResult(
    val accepted: Boolean,
    val message: String,
    val restartGoal: String? = null
)

/**
 * Process-wide owner for an interactive device-control run.
 *
 * The owner deliberately lives outside Activity and ViewModel lifecycle. A task can move to
 * the background while an accessibility run continues, and reopening Chat becomes a steering
 * console for the same session. The last non-terminal snapshot is persisted as suspended so a
 * process restart can offer an explicit resume instead of silently losing the user's goal.
 */
object AutomationSessionManager {
    private const val PREFS = "automation_session"
    private const val KEY_ID = "id"
    private const val KEY_ORIGINAL_GOAL = "original_goal"
    private const val KEY_CURRENT_GOAL = "current_goal"
    private const val KEY_PHASE = "phase"
    private const val KEY_DETAIL = "detail"
    private const val KEY_ACTION_COUNT = "action_count"
    private const val KEY_PENDING_QUESTION = "pending_question"

    private val lock = Any()
    private var appContext: Context? = null
    private val _session = MutableStateFlow<AutomationSessionSnapshot?>(null)
    val session = _session.asStateFlow()
    private val userInputWaiters = mutableMapOf<String, CompletableDeferred<String?>>()
    private val pendingUserAnswers = mutableMapOf<String, String>()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (appContext != null) return
            appContext = context.applicationContext
            runCatching {
                AutomationTraceStore.getInstance(context.applicationContext).recoverAfterProcessRestart()
            }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val id = prefs.getString(KEY_ID, null) ?: return
            val originalGoal = prefs.getString(KEY_ORIGINAL_GOAL, null).orEmpty()
            val currentGoal = prefs.getString(KEY_CURRENT_GOAL, null).orEmpty()
            if (originalGoal.isBlank() || currentGoal.isBlank()) {
                prefs.edit().clear().apply()
                return
            }
            _session.value = AutomationSessionSnapshot(
                sessionId = id,
                originalGoal = originalGoal,
                currentGoal = currentGoal,
                state = AutomationSessionState.SUSPENDED,
                phase = prefs.getString(KEY_PHASE, "Suspended").orEmpty(),
                detail = "Nabu restarted. Resume to continue this goal from a fresh observation.",
                actionCount = prefs.getInt(KEY_ACTION_COUNT, 0),
                updatedAtMs = System.currentTimeMillis(),
                runnerAttached = false,
                pendingQuestion = prefs.getString(KEY_PENDING_QUESTION, null)
            )
        }
    }

    fun queued(context: Context, sessionId: String, goal: String) {
        initialize(context)
        update(
            AutomationSessionSnapshot(
                sessionId = sessionId,
                originalGoal = goal,
                currentGoal = goal,
                state = AutomationSessionState.QUEUED,
                phase = "Queue",
                detail = "Waiting for the active device-control session",
                actionCount = 0,
                updatedAtMs = System.currentTimeMillis(),
                runnerAttached = false
            )
        )
    }

    fun begin(context: Context, sessionId: String, goal: String) {
        initialize(context)
        update(
            AutomationSessionSnapshot(
                sessionId = sessionId,
                originalGoal = goal,
                currentGoal = goal,
                state = AutomationSessionState.STARTING,
                phase = "Take over",
                detail = "Acquired the device-control channel",
                actionCount = 0,
                updatedAtMs = System.currentTimeMillis(),
                runnerAttached = true
            )
        )
    }

    fun progress(sessionId: String, phase: String, detail: String) {
        mutate(sessionId) { current ->
            current.copy(
                state = if (current.state == AutomationSessionState.STARTING) {
                    AutomationSessionState.RUNNING
                } else {
                    current.state
                },
                phase = phase,
                detail = detail,
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun actionCompleted(sessionId: String, detail: String) {
        mutate(sessionId) { current ->
            current.copy(
                state = when (current.state) {
                    AutomationSessionState.SUSPENDED,
                    AutomationSessionState.WAITING_FOR_USER,
                    AutomationSessionState.CANCELLING -> current.state
                    else -> AutomationSessionState.RUNNING
                },
                phase = "Verify",
                detail = detail,
                actionCount = current.actionCount + 1,
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    suspend fun awaitDirective(sessionId: String): AutomationSessionDirective {
        while (true) {
            val current = _session.value
            if (current == null || current.sessionId != sessionId) {
                return AutomationSessionDirective.Cancel
            }
            when (current.state) {
                AutomationSessionState.CANCELLING,
                AutomationSessionState.COMPLETED,
                AutomationSessionState.FAILED -> return AutomationSessionDirective.Cancel
                AutomationSessionState.SUSPENDED,
                AutomationSessionState.WAITING_FOR_USER -> delay(100L)
                else -> return AutomationSessionDirective.Run(current.currentGoal)
            }
        }
    }

    fun suspendActive(reason: String = "Suspended by user"): AutomationCommandResult {
        val current = _session.value
            ?: return AutomationCommandResult(false, "There is no device-control session to suspend.")
        if (current.state in TERMINAL_STATES) {
            return AutomationCommandResult(false, "The last device-control session has already ended.")
        }
        if (current.state == AutomationSessionState.CANCELLING) {
            return AutomationCommandResult(false, "Device control is already stopping.")
        }
        if (current.state == AutomationSessionState.WAITING_FOR_USER) {
            return AutomationCommandResult(
                true,
                "Device control is waiting for your answer. Its goal and progress are preserved."
            )
        }
        update(
            current.copy(
                state = AutomationSessionState.SUSPENDED,
                phase = "Suspended",
                detail = reason,
                updatedAtMs = System.currentTimeMillis()
            )
        )
        return AutomationCommandResult(true, "Device control suspended. Its goal and progress are preserved.")
    }

    fun resumeActive(): AutomationCommandResult {
        val current = _session.value
            ?: return AutomationCommandResult(false, "There is no device-control session to resume.")
        if (current.state in TERMINAL_STATES) {
            return AutomationCommandResult(false, "The last device-control session has already ended.")
        }
        if (current.state == AutomationSessionState.CANCELLING) {
            return AutomationCommandResult(false, "Device control is already stopping.")
        }
        if (current.state == AutomationSessionState.WAITING_FOR_USER) {
            return AutomationCommandResult(false, "Answer Nabu's pending question to continue device control.")
        }
        if (!current.runnerAttached) {
            clearPersisted()
            _session.value = null
            return AutomationCommandResult(
                accepted = true,
                message = "Restarting the preserved goal from a fresh screen observation.",
                restartGoal = current.currentGoal
            )
        }
        update(
            current.copy(
                state = AutomationSessionState.SUSPENDED,
                phase = "Resume",
                detail = "Waiting for the Nabu control surface to park",
                updatedAtMs = System.currentTimeMillis()
            )
        )
        return AutomationCommandResult(true, "Resuming device control.")
    }

    fun steerActive(goal: String): AutomationCommandResult {
        val normalized = goal.trim()
        if (normalized.isBlank()) {
            return AutomationCommandResult(false, "Tell me the new outcome for the active device-control session.")
        }
        val current = _session.value
            ?: return AutomationCommandResult(false, "There is no device-control session to steer.")
        if (current.state in TERMINAL_STATES) {
            return AutomationCommandResult(false, "The last device-control session has already ended.")
        }
        if (current.state == AutomationSessionState.CANCELLING) {
            return AutomationCommandResult(false, "Device control is already stopping.")
        }
        if (!current.runnerAttached) {
            clearPersisted()
            _session.value = null
            return AutomationCommandResult(
                accepted = true,
                message = "Restarting the preserved session with the new goal.",
                restartGoal = normalized
            )
        }
        synchronized(lock) {
            if (current.state == AutomationSessionState.WAITING_FOR_USER) {
                pendingUserAnswers[current.sessionId] = normalized
            }
            updateLocked(
                current.copy(
                    currentGoal = normalized,
                    state = AutomationSessionState.SUSPENDED,
                    phase = "Steer",
                    detail = "Goal updated; waiting for the Nabu control surface to park",
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        }
        return AutomationCommandResult(true, "Steering the active session to: $normalized")
    }

    fun continueAfterPark(sessionId: String): Boolean {
        var resumed = false
        var waiter: CompletableDeferred<String?>? = null
        var answer: String? = null
        mutate(sessionId) { current ->
            if (!current.runnerAttached ||
                current.state in TERMINAL_STATES ||
                current.state == AutomationSessionState.CANCELLING
            ) {
                current
            } else {
                resumed = true
                answer = synchronized(lock) { pendingUserAnswers.remove(sessionId) }
                waiter = synchronized(lock) { userInputWaiters.remove(sessionId) }
                current.copy(
                    state = AutomationSessionState.RUNNING,
                    phase = "Resume",
                    detail = "Control surface parked; replanning from a fresh observation",
                    updatedAtMs = System.currentTimeMillis(),
                    pendingQuestion = null
                )
            }
        }
        if (resumed && answer != null) waiter?.complete(answer)
        return resumed
    }

    fun requestUserInput(sessionId: String, question: String): Boolean {
        val normalized = question.trim()
        if (normalized.isBlank()) return false
        synchronized(lock) {
            val current = _session.value ?: return false
            if (current.sessionId != sessionId ||
                !current.runnerAttached ||
                current.state in TERMINAL_STATES ||
                current.state == AutomationSessionState.CANCELLING
            ) {
                return false
            }
            userInputWaiters.remove(sessionId)?.cancel()
            userInputWaiters[sessionId] = CompletableDeferred()
            pendingUserAnswers.remove(sessionId)
            updateLocked(
                current.copy(
                    state = AutomationSessionState.WAITING_FOR_USER,
                    phase = "Needs input",
                    detail = normalized,
                    updatedAtMs = System.currentTimeMillis(),
                    pendingQuestion = normalized
                )
            )
            return true
        }
    }

    suspend fun awaitUserInput(sessionId: String): String? {
        val waiter = synchronized(lock) { userInputWaiters[sessionId] } ?: return null
        return waiter.await()
    }

    fun answerActive(answer: String): AutomationCommandResult {
        val normalized = answer.trim()
        if (normalized.isBlank()) {
            return AutomationCommandResult(false, "Type an answer before resuming device control.")
        }
        synchronized(lock) {
            val current = _session.value
                ?: return AutomationCommandResult(false, "There is no device-control question to answer.")
            if (current.state != AutomationSessionState.WAITING_FOR_USER ||
                !current.runnerAttached ||
                !userInputWaiters.containsKey(current.sessionId)
            ) {
                return AutomationCommandResult(false, "Device control is not waiting for an answer.")
            }
            pendingUserAnswers[current.sessionId] = normalized
            updateLocked(
                current.copy(
                    state = AutomationSessionState.SUSPENDED,
                    phase = "Answer received",
                    detail = "Waiting for the Nabu control surface to park",
                    updatedAtMs = System.currentTimeMillis()
                )
            )
            return AutomationCommandResult(true, "Answer received. Resuming from the same checkpoint.")
        }
    }

    fun cancelActive(): AutomationCommandResult {
        val current = _session.value
            ?: return AutomationCommandResult(false, "There is no device-control session to stop.")
        if (current.state in TERMINAL_STATES) {
            return AutomationCommandResult(false, "The last device-control session has already ended.")
        }
        update(
            current.copy(
                state = AutomationSessionState.CANCELLING,
                phase = "Stop",
                detail = "Stopping at the next safe action boundary",
                updatedAtMs = System.currentTimeMillis()
            )
        )
        synchronized(lock) {
            pendingUserAnswers.remove(current.sessionId)
            userInputWaiters.remove(current.sessionId)?.complete(null)
        }
        return AutomationCommandResult(true, "Stopping device control at the next safe boundary.")
    }

    fun status(): AutomationCommandResult {
        val current = _session.value
            ?: return AutomationCommandResult(false, "No device-control session is active or preserved.")
        return AutomationCommandResult(
            true,
            "${current.state.name.lowercase()}: ${current.currentGoal} " +
                "(${current.phase}, ${current.actionCount} actions)"
        )
    }

    fun finish(sessionId: String, succeeded: Boolean, detail: String) {
        val current = _session.value ?: return
        if (current.sessionId != sessionId) return
        update(
            current.copy(
                state = if (succeeded) AutomationSessionState.COMPLETED else AutomationSessionState.FAILED,
                phase = if (succeeded) "Complete" else "Stopped",
                detail = detail,
                updatedAtMs = System.currentTimeMillis(),
                runnerAttached = false
            ),
            persist = false
        )
        clearPersisted()
        synchronized(lock) {
            pendingUserAnswers.remove(sessionId)
            userInputWaiters.remove(sessionId)?.complete(null)
        }
    }

    fun hasRunningSession(): Boolean {
        val current = _session.value ?: return false
        return current.runnerAttached && current.state !in TERMINAL_STATES
    }

    private fun mutate(
        sessionId: String,
        transform: (AutomationSessionSnapshot) -> AutomationSessionSnapshot
    ) {
        synchronized(lock) {
            val current = _session.value ?: return
            if (current.sessionId != sessionId) return
            updateLocked(transform(current))
        }
    }

    private fun update(snapshot: AutomationSessionSnapshot, persist: Boolean = true) {
        synchronized(lock) {
            updateLocked(snapshot, persist)
        }
    }

    private fun updateLocked(snapshot: AutomationSessionSnapshot, persist: Boolean = true) {
        _session.value = snapshot
        if (!persist) return
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(KEY_ID, snapshot.sessionId)
            ?.putString(KEY_ORIGINAL_GOAL, snapshot.originalGoal)
            ?.putString(KEY_CURRENT_GOAL, snapshot.currentGoal)
            ?.putString(KEY_PHASE, snapshot.phase)
            ?.putString(KEY_DETAIL, snapshot.detail)
            ?.putInt(KEY_ACTION_COUNT, snapshot.actionCount)
            ?.putString(KEY_PENDING_QUESTION, snapshot.pendingQuestion)
            ?.apply()
    }

    private fun clearPersisted() {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
    }

    private val TERMINAL_STATES = setOf(
        AutomationSessionState.COMPLETED,
        AutomationSessionState.FAILED
    )
}

/**
 * Handshake between a lifecycle-independent automation owner and the Activity task.
 * The Activity acknowledges only after it has actually moved behind the target app.
 */
object ControlSurfaceCoordinator {
    private val lock = Any()
    @Volatile
    var isChatForegrounded: Boolean = false
        private set

    private val _parkRequest = MutableStateFlow<String?>(null)
    val parkRequest = _parkRequest.asStateFlow()
    private val acknowledgements = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private val _foregroundRequest = MutableStateFlow<String?>(null)
    val foregroundRequest = _foregroundRequest.asStateFlow()
    private val foregroundAcknowledgements = mutableMapOf<String, CompletableDeferred<Boolean>>()

    fun setChatForegroundState(foreground: Boolean) {
        synchronized(lock) {
            isChatForegrounded = foreground
        }
    }

    suspend fun parkAndAwait(sessionId: String, timeoutMs: Long = 1_000L): Boolean {
        synchronized(lock) {
            if (!isChatForegrounded) {
                return true
            }
        }
        val acknowledgement = synchronized(lock) {
            acknowledgements.remove(sessionId)?.cancel()
            CompletableDeferred<Boolean>().also {
                acknowledgements[sessionId] = it
                _parkRequest.value = sessionId
            }
        }
        val acknowledged = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            acknowledgement.await()
        } ?: false
        if (!acknowledged) {
            synchronized(lock) {
                if (acknowledgements[sessionId] === acknowledgement) {
                    acknowledgements.remove(sessionId)
                    if (_parkRequest.value == sessionId) _parkRequest.value = null
                }
            }
        }
        return acknowledged
    }

    fun acknowledgeParked(sessionId: String, parked: Boolean) {
        synchronized(lock) {
            isChatForegrounded = false
            acknowledgements.remove(sessionId)?.complete(parked)
            if (_parkRequest.value == sessionId) _parkRequest.value = null
        }
    }

    suspend fun showAndAwait(sessionId: String, timeoutMs: Long = 2_000L): Boolean {
        val acknowledgement = synchronized(lock) {
            foregroundAcknowledgements.remove(sessionId)?.cancel()
            CompletableDeferred<Boolean>().also {
                foregroundAcknowledgements[sessionId] = it
                _foregroundRequest.value = sessionId
            }
        }
        val acknowledged = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            acknowledgement.await()
        } ?: false
        if (!acknowledged) {
            synchronized(lock) {
                if (foregroundAcknowledgements[sessionId] === acknowledgement) {
                    foregroundAcknowledgements.remove(sessionId)
                    if (_foregroundRequest.value == sessionId) _foregroundRequest.value = null
                }
            }
        }
        return acknowledged
    }

    fun acknowledgeForegrounded() {
        synchronized(lock) {
            val sessionId = _foregroundRequest.value ?: return
            foregroundAcknowledgements.remove(sessionId)?.complete(true)
            _foregroundRequest.value = null
        }
    }

    fun clear(sessionId: String) {
        synchronized(lock) {
            acknowledgements.remove(sessionId)?.cancel()
            if (_parkRequest.value == sessionId) _parkRequest.value = null
            foregroundAcknowledgements.remove(sessionId)?.cancel()
            if (_foregroundRequest.value == sessionId) _foregroundRequest.value = null
        }
    }
}

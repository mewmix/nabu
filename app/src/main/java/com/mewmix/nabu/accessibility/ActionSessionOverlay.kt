package com.mewmix.nabu.accessibility

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.mewmix.nabu.R
import com.mewmix.nabu.ui.theme.AppTheme
import com.mewmix.nabu.uiagent.ActionRequestDispatcher
import com.mewmix.nabu.uiagent.ActionSessionMode
import com.mewmix.nabu.uiagent.ActionSessionStatus
import com.mewmix.nabu.utils.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Visual tokens resolved from the active [ThemeManager] theme (Bubble Pop, Brutal, or custom)
 * so the overlay always matches the in-app chat chrome.
 */
private data class OverlayPalette(
    val surface: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val surfaceVariant: Int,
    val outline: Int,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val error: Int,
    val panelRadiusDp: Float,
    val controlRadiusDp: Float,
    val borderWidthDp: Float,
    val titleTypeface: Typeface?,
    val bodyTypeface: Typeface?
) {
    companion object {
        fun resolve(context: Context): OverlayPalette {
            val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            val theme: AppTheme = ThemeManager.getTheme(context, nightMode == Configuration.UI_MODE_NIGHT_YES)
            return OverlayPalette(
                surface = theme.surface.toInt(),
                onSurface = theme.onSurface.toInt(),
                onSurfaceVariant = theme.onSurfaceVariant.toInt(),
                surfaceVariant = theme.surfaceVariant.toInt(),
                outline = theme.outline.toInt(),
                primary = theme.primary.toInt(),
                onPrimary = theme.onPrimary.toInt(),
                primaryContainer = theme.primaryContainer.toInt(),
                onPrimaryContainer = theme.onPrimaryContainer.toInt(),
                secondary = theme.secondary.toInt(),
                error = 0xFFD0563F.toInt(),
                panelRadiusDp = theme.panelRadiusDp ?: 24f,
                controlRadiusDp = theme.controlRadiusDp ?: 18f,
                borderWidthDp = theme.borderWidthDp ?: 1f,
                titleTypeface = ResourcesCompat.getFont(context, R.font.quicksand_bold),
                bodyTypeface = ResourcesCompat.getFont(context, R.font.quicksand_regular)
            )
        }
    }
}

class ActionSessionOverlay(private val service: NabuAccessibilityService) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var overlayView: View? = null
    private var observerJob: Job? = null
    private var currentMode: ActionSessionMode = ActionSessionMode.SINGLE_TURN
    private var palette: OverlayPalette = OverlayPalette.resolve(service)
    private var pendingConfirmation: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    suspend fun handleConfirmation(prompt: String): Boolean {
        pendingConfirmation?.complete(false)
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        pendingConfirmation = deferred
        val result = try {
            kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                deferred.await()
            } ?: false
        } catch (e: Exception) {
            false
        } finally {
            if (pendingConfirmation == deferred) {
                pendingConfirmation = null
            }
        }
        return result
    }

    private fun dp(value: Float): Int = (value * service.resources.displayMetrics.density).toInt()

    private fun chipBackground(fill: Int, radiusDp: Float, strokeColor: Int? = null, strokeWidthDp: Float = 0f) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * service.resources.displayMetrics.density
            if (strokeColor != null && strokeWidthDp > 0f) {
                setStroke(dp(strokeWidthDp), strokeColor)
            }
        }

    fun show() {
        if (overlayView != null) {
            overlayView?.visibility = View.VISIBLE
            return
        }

        palette = OverlayPalette.resolve(service)
        val p = palette

        val root = FrameLayout(service).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(16f), dp(12f), dp(16f), dp(24f))
        }

        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            // Mostly-opaque surface so underlying app content stays legible at the edges.
            val cardColor = (p.surface and 0x00FFFFFF) or (0xF6 shl 24)
            background = chipBackground(cardColor, p.panelRadiusDp, p.outline, p.borderWidthDp)
            setPadding(dp(20f), dp(18f), dp(20f), dp(18f))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        // Header
        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12f)
            }
        }

        val title = TextView(service).apply {
            text = "Nabu Assistant"
            setTextColor(p.onSurface)
            textSize = 16f
            typeface = p.titleTypeface ?: Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val modeToggle = TextView(service).apply {
            text = if (currentMode == ActionSessionMode.SINGLE_TURN) "Single-Turn" else "Conversation"
            textSize = 12f
            setTextColor(p.onPrimaryContainer)
            typeface = p.bodyTypeface ?: Typeface.DEFAULT
            background = chipBackground(p.primaryContainer, 999f)
            setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(8f)
            }
            setOnClickListener {
                currentMode = if (currentMode == ActionSessionMode.SINGLE_TURN) {
                    ActionSessionMode.TEMPORARY_CONVERSATION
                } else {
                    ActionSessionMode.SINGLE_TURN
                }
                text = if (currentMode == ActionSessionMode.SINGLE_TURN) "Single-Turn" else "Conversation"
            }
        }

        val closeBtn = TextView(service).apply {
            text = "✕"
            textSize = 16f
            setTextColor(p.onSurfaceVariant)
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            setOnClickListener { hide() }
        }

        header.addView(title)
        header.addView(modeToggle)
        header.addView(closeBtn)
        card.addView(header)

        // Status / progress container
        val statusContainer = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12f)
            }
        }

        val statusText = TextView(service).apply {
            text = "Ready"
            setTextColor(p.primary)
            textSize = 13f
            typeface = p.bodyTypeface ?: Typeface.DEFAULT
        }

        val progressBar = ProgressBar(service, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(p.primary)
            progressTintList = ColorStateList.valueOf(p.primary)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(6f)
            ).apply {
                topMargin = dp(8f)
                bottomMargin = dp(8f)
            }
        }

        val actionButtonsRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val handoffBtn = TextView(service).apply {
            text = "Open in Nabu ↗"
            textSize = 12f
            setTextColor(p.onPrimaryContainer)
            typeface = p.bodyTypeface ?: Typeface.DEFAULT
            background = chipBackground(p.primaryContainer, p.controlRadiusDp, p.primary, p.borderWidthDp)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(8f)
            }
            setOnClickListener {
                ActionRequestDispatcher.activeSession.value?.let { session ->
                    ActionRequestDispatcher.handoffToChat(service, session.id)
                    hide()
                }
            }
        }

        val cancelBtn = TextView(service).apply {
            text = "Stop"
            textSize = 12f
            setTextColor(p.error)
            typeface = p.bodyTypeface ?: Typeface.DEFAULT
            background = chipBackground(0x00000000, p.controlRadiusDp, p.error, p.borderWidthDp)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            setOnClickListener {
                pendingConfirmation?.complete(false)
                pendingConfirmation = null
                ActionRequestDispatcher.activeSession.value?.let { session ->
                    ActionRequestDispatcher.cancelSession(session.id)
                }
            }
        }

        actionButtonsRow.addView(handoffBtn)
        actionButtonsRow.addView(cancelBtn)

        val confirmationRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8f)
            }
        }

        val denyBtn = TextView(service).apply {
            text = "Deny"
            textSize = 13f
            setTextColor(p.onSurface)
            typeface = p.titleTypeface ?: Typeface.DEFAULT_BOLD
            background = chipBackground(p.surfaceVariant, p.controlRadiusDp, p.outline, p.borderWidthDp)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            setOnClickListener {
                pendingConfirmation?.complete(false)
                pendingConfirmation = null
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(8f)
            }
        }

        val confirmBtn = TextView(service).apply {
            text = "Confirm"
            textSize = 13f
            setTextColor(p.onPrimary)
            typeface = p.titleTypeface ?: Typeface.DEFAULT_BOLD
            background = chipBackground(p.primary, p.controlRadiusDp)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            setOnClickListener {
                pendingConfirmation?.complete(true)
                pendingConfirmation = null
            }
        }

        confirmationRow.addView(denyBtn)
        confirmationRow.addView(confirmBtn)

        statusContainer.addView(statusText)
        statusContainer.addView(progressBar)
        statusContainer.addView(actionButtonsRow)
        statusContainer.addView(confirmationRow)
        card.addView(statusContainer)

        // Input row
        val inputRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val inputField = EditText(service).apply {
            hint = "Ask Nabu to do something..."
            setHintTextColor(p.onSurfaceVariant)
            setTextColor(p.onSurface)
            textSize = 15f
            maxLines = 3
            typeface = p.bodyTypeface ?: Typeface.DEFAULT
            background = chipBackground(p.surfaceVariant, p.controlRadiusDp, p.outline, p.borderWidthDp)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10f)
            }
        }

        val submitBtn = TextView(service).apply {
            text = "Run"
            textSize = 14f
            setTextColor(p.onPrimary)
            typeface = p.titleTypeface ?: Typeface.DEFAULT_BOLD
            background = chipBackground(p.primary, p.controlRadiusDp)
            setPadding(dp(18f), dp(12f), dp(18f), dp(12f))
        }

        fun doSubmit() {
            pendingConfirmation?.complete(false)
            pendingConfirmation = null
            val text = inputField.text.toString().trim()
            if (text.isBlank()) return
            inputField.text.clear()
            val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(inputField.windowToken, 0)

            val currentSession = ActionRequestDispatcher.activeSession.value
            if (currentMode == ActionSessionMode.TEMPORARY_CONVERSATION && currentSession != null && currentSession.status in listOf(ActionSessionStatus.COMPLETED, ActionSessionStatus.IDLE)) {
                ActionRequestDispatcher.submitFollowUp(
                    context = service,
                    sessionId = currentSession.id,
                    followUpRequest = text,
                    requestConfirmation = ::handleConfirmation
                )
            } else {
                ActionRequestDispatcher.submitRequest(
                    context = service,
                    request = text,
                    mode = currentMode,
                    requestConfirmation = ::handleConfirmation
                )
            }
        }

        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                doSubmit()
                true
            } else false
        }
        submitBtn.setOnClickListener { doSubmit() }

        inputRow.addView(inputField)
        inputRow.addView(submitBtn)
        card.addView(inputRow)
        root.addView(card)

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            format = android.graphics.PixelFormat.TRANSLUCENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        windowManager.addView(root, params)
        overlayView = root

        // Gentle rise-in so the panel feels like it belongs to the same surface language as the app.
        card.translationY = dp(20f).toFloat()
        card.alpha = 0f
        card.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(180L)
            .start()

        fun statusColor(status: ActionSessionStatus): Int = when (status) {
            ActionSessionStatus.COMPLETED -> p.secondary
            ActionSessionStatus.FAILED -> p.error
            ActionSessionStatus.CANCELLED, ActionSessionStatus.IDLE -> p.onSurfaceVariant
            ActionSessionStatus.WAITING_FOR_USER -> p.error
            else -> p.primary
        }

        observerJob?.cancel()
        observerJob = scope.launch {
            ActionRequestDispatcher.activeSession.collectLatest { session ->
                if (session == null) {
                    statusContainer.visibility = View.GONE
                    statusText.text = ""
                    inputRow.visibility = View.VISIBLE
                    header.visibility = View.VISIBLE
                    confirmationRow.visibility = View.GONE
                    return@collectLatest
                }

                val isRunning = session.status in listOf(
                    ActionSessionStatus.ROUTING,
                    ActionSessionStatus.PLANNING,
                    ActionSessionStatus.OBSERVING,
                    ActionSessionStatus.EXECUTING,
                    ActionSessionStatus.VERIFYING
                )

                if (session.status == ActionSessionStatus.WAITING_FOR_USER) {
                    inputRow.visibility = View.GONE
                    header.visibility = View.VISIBLE
                    handoffBtn.visibility = View.GONE
                    cancelBtn.visibility = View.VISIBLE
                    confirmationRow.visibility = View.VISIBLE
                    params.width = WindowManager.LayoutParams.MATCH_PARENT
                    root.setPadding(dp(16f), dp(12f), dp(16f), dp(24f))
                    card.setPadding(dp(20f), dp(18f), dp(20f), dp(18f))
                } else {
                    confirmationRow.visibility = View.GONE
                    // Collapse input row during active execution to prevent obstruction
                    if (isRunning) {
                        inputRow.visibility = View.GONE
                        header.visibility = View.GONE
                        handoffBtn.visibility = View.GONE
                        cancelBtn.visibility = View.VISIBLE
                        params.width = WindowManager.LayoutParams.WRAP_CONTENT
                        root.setPadding(dp(8f), dp(6f), dp(8f), dp(10f))
                        card.setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                    } else {
                        inputRow.visibility = View.VISIBLE
                        header.visibility = View.VISIBLE
                        handoffBtn.visibility = View.VISIBLE
                        params.width = WindowManager.LayoutParams.MATCH_PARENT
                        root.setPadding(dp(16f), dp(12f), dp(16f), dp(24f))
                        card.setPadding(dp(20f), dp(18f), dp(20f), dp(18f))
                    }
                }
                runCatching { windowManager.updateViewLayout(root, params) }

                statusContainer.visibility = View.VISIBLE
                val phaseText = when (session.status) {
                    ActionSessionStatus.ROUTING -> "Routing: ${session.currentGoal}"
                    ActionSessionStatus.PLANNING -> "Planning: ${session.currentGoal}"
                    ActionSessionStatus.OBSERVING -> "Observing screen..."
                    ActionSessionStatus.EXECUTING -> "Acting: ${session.pendingObjective ?: session.currentGoal}"
                    ActionSessionStatus.VERIFYING -> "Verifying: ${session.pendingObjective ?: ""}"
                    ActionSessionStatus.WAITING_FOR_USER -> session.pendingObjective ?: "Confirmation required"
                    ActionSessionStatus.COMPLETED -> "Done: ${session.lastVerificationResult ?: "Complete"}"
                    ActionSessionStatus.FAILED -> "Failed: ${session.lastVerificationResult ?: "Action could not complete"}"
                    ActionSessionStatus.CANCELLED -> "Stopped"
                    else -> session.status.name
                }
                statusText.text = phaseText
                statusText.setTextColor(statusColor(session.status))
                progressBar.visibility = if (isRunning) View.VISIBLE else View.GONE

                if (session.status == ActionSessionStatus.COMPLETED && session.mode == ActionSessionMode.SINGLE_TURN) {
                    // In single-turn mode, hide overlay shortly after verified success
                    kotlinx.coroutines.delay(1800L)
                    if (ActionRequestDispatcher.activeSession.value?.status == ActionSessionStatus.COMPLETED) {
                        hide()
                    }
                }
            }
        }
    }

    fun hide() {
        pendingConfirmation?.complete(false)
        pendingConfirmation = null
        observerJob?.cancel()
        observerJob = null
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
            overlayView = null
        }
    }
}

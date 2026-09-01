package com.mewmix.nabu.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import android.widget.Toast
import kotlinx.coroutines.flow.asStateFlow
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class NabuAccessibilityService : AccessibilityService() {
    private val observationLock = Any()
    private val actionObservationLease = ActionObservationLease()

    private var actionOverlay: ActionSessionOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Service connected")
        com.mewmix.nabu.uiagent.ActionRequestDispatcher.onAccessibilityConnectionChanged(
            applicationContext,
            connected = true
        )
        actionOverlay = ActionSessionOverlay(this)

        accessibilityButtonController.registerAccessibilityButtonCallback(object : android.accessibilityservice.AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: android.accessibilityservice.AccessibilityButtonController) {
                super.onClicked(controller)
                actionOverlay?.show()
            }
        })
    }

    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
    private val eventCaptureCoalescer = AccessibilityEventCaptureCoalescer()
    private var leadingSnapshotJob: kotlinx.coroutines.Job? = null
    private var snapshotJob: kotlinx.coroutines.Job? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType in SNAPSHOT_EVENT_TYPES) {
            val plan = eventCaptureCoalescer.onMeaningfulEvent()
            if (plan.captureLeading) {
                leadingSnapshotJob = serviceScope.launch {
                    captureEventSnapshot()
                }
            }
            snapshotJob?.cancel()
            snapshotJob = serviceScope.launch {
                kotlinx.coroutines.delay(EVENT_DEBOUNCE_MS)
                if (eventCaptureCoalescer.isCurrent(plan.generation)) {
                    captureEventSnapshot()
                    eventCaptureCoalescer.finishTrailing(plan.generation)
                }
            }
        }
    }

    /** Captures a snapshot and atomically leases it for one UI action. */
    fun forceCaptureSnapshot(): UiSnapshot? {
        leadingSnapshotJob?.cancel()
        leadingSnapshotJob = null
        snapshotJob?.cancel()
        snapshotJob = null
        eventCaptureCoalescer.reset()
        return synchronized(observationLock) {
            captureSnapshotLocked(bindForAction = true)
        }
    }

    /**
     * Promotes an already-published immutable snapshot into fresh single-use action authority.
     *
     * Promotion is deliberately exact: a newer capture, even one with an equivalent fingerprint,
     * wins and the caller must use that newer observation. Physical dispatch still reconstructs
     * and compares the live hierarchy before consuming this lease.
     */
    fun promoteSnapshotForAction(expected: UiSnapshot): UiSnapshot? = synchronized(observationLock) {
        val latest = UiSnapshotStore.currentSnapshot.value ?: return@synchronized null
        if (!latest.isExactPromotionOf(expected)) {
            return@synchronized null
        }
        actionObservationLease.bind(latest.toActionAuthority())
        latest
    }

    private fun captureEventSnapshot() {
        synchronized(observationLock) {
            captureSnapshotLocked(bindForAction = false)
        }
    }

    private fun captureSnapshotLocked(bindForAction: Boolean): UiSnapshot? {
        return try {
            val window = targetWindow()
            val root = window?.root ?: rootInActiveWindow ?: run {
                clearActionLeaseLocked()
                return null
            }
            val packageName = root.packageName?.toString().orEmpty()
            val windowTitle = window?.title?.toString().orEmpty()
            val rotation = currentDisplayRotation()

            val uiNodeRoot = buildUiNodeTree(root, "0")

            val snapshot = UiSnapshotStore.updateSnapshot(UiSnapshot(
                id = UUID.randomUUID().toString(),
                capturedAtMs = System.currentTimeMillis(),
                packageName = packageName,
                windowTitle = windowTitle,
                rotation = rotation,
                displayBounds = Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels),
                rootNode = uiNodeRoot,
                windowId = window?.id ?: -1,
                systemActions = currentSystemActionTokens()
            ))
            if (bindForAction) {
                actionObservationLease.bind(snapshot.toActionAuthority())
            } else {
                actionObservationLease.invalidateIfDrifted(snapshot.toActionAuthority())
            }
            snapshot
        } catch (e: Exception) {
            clearActionLeaseLocked()
            Log.e(TAG, "Failed to capture in-memory snapshot", e)
            null
        }
    }

    private fun buildUiNodeTree(node: AccessibilityNodeInfo, path: String): UiNode {
        val bounds = Rect().also(node::getBoundsInScreen)
        val (standardActions, customActions) = AndroidActionCatalog.capture(node.actionList)
        val range = node.rangeInfo?.let {
            UiRangeInfo(type = it.type, min = it.min, max = it.max, current = it.current)
        }
        val collection = node.collectionInfo?.let {
            UiCollectionInfo(it.rowCount, it.columnCount, it.isHierarchical, it.selectionMode)
        }
        val collectionItem = node.collectionItemInfo?.let {
            UiCollectionItemInfo(
                it.rowIndex,
                it.rowSpan,
                it.columnIndex,
                it.columnSpan,
                it.isHeading,
                it.isSelected
            )
        }
        val children = mutableListOf<UiNode>()
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                children.add(buildUiNodeTree(child, "$path/$index"))
            }
        }

        return UiNode(
            treePath = path,
            packageName = node.packageName?.toString().orEmpty(),
            resourceId = node.viewIdResourceName.orEmpty(),
            className = node.className?.toString().orEmpty(),
            text = if (node.isPassword) "•".repeat(node.text?.length ?: 8) else node.text?.toString().orEmpty(),
            contentDescription = if (node.isPassword) "•".repeat(node.contentDescription?.length ?: 8) else node.contentDescription?.toString().orEmpty(),
            isCheckable = node.isCheckable,
            isChecked = node.isChecked,
            isClickable = node.isClickable,
            isEnabled = node.isEnabled,
            isEditable = node.isEditable,
            isFocusable = node.isFocusable,
            isFocused = node.isFocused,
            isVisibleToUser = node.isVisibleToUser,
            isScrollable = node.isScrollable,
            isLongClickable = node.isLongClickable,
            isPassword = node.isPassword,
            isSelected = node.isSelected,
            boundsInScreen = bounds,
            children = children,
            standardActions = standardActions,
            customActions = customActions,
            movementGranularities = node.movementGranularities,
            rangeInfo = range,
            isAccessibilityFocused = node.isAccessibilityFocused,
            isContextClickable = node.isContextClickable,
            textSelectionStart = node.textSelectionStart,
            textSelectionEnd = node.textSelectionEnd,
            collectionInfo = collection,
            collectionItemInfo = collectionItem
        )
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    fun showActionSurface() {
        mainExecutor.execute { actionOverlay?.show() }
    }

    override fun onDestroy() {
        actionOverlay?.hide()
        actionOverlay = null
        if (instance == this) instance = null
        com.mewmix.nabu.uiagent.ActionRequestDispatcher.onAccessibilityConnectionChanged(
            applicationContext,
            connected = false
        )
        serviceScope.cancel()
        eventCaptureCoalescer.reset()
        UiSnapshotStore.clear()
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun takeScreenshotToPath(destPath: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        File(destPath).parentFile?.mkdirs()
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(
                        screenshotResult.hardwareBuffer,
                        screenshotResult.colorSpace
                    )
                    try {
                        val copy = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        if (copy == null) {
                            deferred.complete(false)
                            return
                        }
                        FileOutputStream(File(destPath)).use { out ->
                            copy.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        copy.recycle()
                        deferred.complete(true)
                    } catch (error: Exception) {
                        Log.e(TAG, "Failed to save screenshot", error)
                        deferred.complete(false)
                    } finally {
                        bitmap?.recycle()
                        screenshotResult.hardwareBuffer.close()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Failed to take screenshot: $errorCode")
                    deferred.complete(false)
                }
            }
        )
        return kotlinx.coroutines.runBlocking { deferred.await() }
    }

    fun observeUi(xmlPath: String, screenshotPath: String?): JSONObject = synchronized(observationLock) {
        val window = targetWindow()
        val root = window?.root ?: rootInActiveWindow ?: throw IllegalStateException("No active application window is available.")
        val observationId = UUID.randomUUID().toString()
        val packageName = root.packageName?.toString().orEmpty()
        val capturedAt = System.currentTimeMillis()
        val rotation = currentDisplayRotation()
        val snapshot = UiSnapshotStore.updateSnapshot(
            UiSnapshot(
                id = observationId,
                capturedAtMs = capturedAt,
                packageName = packageName,
                windowTitle = window?.title?.toString().orEmpty(),
                rotation = rotation,
                displayBounds = Rect(
                    0,
                    0,
                    resources.displayMetrics.widthPixels,
                    resources.displayMetrics.heightPixels
                ),
                rootNode = buildUiNodeTree(root, "0"),
                windowId = window?.id ?: -1,
                systemActions = currentSystemActionTokens()
            )
        )
        if (!writeHierarchy(root, window, observationId, xmlPath)) {
            throw IllegalStateException("Failed to capture UI hierarchy.")
        }
        var actualScreenshotPath: String? = null
        if (!screenshotPath.isNullOrBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (takeScreenshotToPath(screenshotPath)) {
                actualScreenshotPath = screenshotPath
            }
        }
        actionObservationLease.bind(snapshot.toActionAuthority())
        val result = JSONObject()
            .put("schema_version", 2)
            .put("observation_id", observationId)
            .put("captured_at_ms", capturedAt)
            .put("package", packageName)
            .put("window_title", window?.title?.toString().orEmpty())
            .put("rotation", rotation)
            .put("display_bounds", JSONArray(listOf(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)))
            .put("xml_path", xmlPath)
        if (actualScreenshotPath != null) {
            result.put("screenshot_path", actualScreenshotPath)
        }
        result
    }

    fun dumpScreenToXml(destPath: String): Boolean {
        val window = targetWindow()
        val root = window?.root ?: rootInActiveWindow ?: return false
        return writeHierarchy(root, window, UUID.randomUUID().toString(), destPath)
    }

    fun performUiAction(action: String, params: JSONObject): JSONObject = synchronized(observationLock) {
        val observationId = params.optString("observation_id").trim()
        require(observationId.isNotEmpty()) { "observation_id is required." }
        val window = targetWindow()
        val root = window?.root ?: rootInActiveWindow ?: throw IllegalStateException("No active application window is available.")
        val currentPackage = root.packageName?.toString().orEmpty()
        val rotation = currentDisplayRotation()
        val currentFingerprint = UiSnapshotFingerprint.compute(
            packageName = currentPackage,
            windowTitle = window?.title?.toString().orEmpty(),
            rotation = rotation,
            rootNode = buildUiNodeTree(root, "0"),
            systemActions = currentSystemActionTokens()
        )
        val currentAuthority = ActionObservationAuthority(
            observationId = observationId,
            packageName = currentPackage,
            windowId = window?.id ?: -1,
            stateFingerprint = currentFingerprint,
            rotation = rotation,
            displayWidth = resources.displayMetrics.widthPixels,
            displayHeight = resources.displayMetrics.heightPixels
        )
        when (val validation = actionObservationLease.consume(currentAuthority)) {
            ActionLeaseValidation.Authorized -> Unit
            is ActionLeaseValidation.Rejected -> throw IllegalStateException(validation.reason)
        }

        val selector = params.optJSONObject("selector") ?: JSONObject()
        val target = findNode(root, selector)
        val success = when (action) {
            "ui_tap" -> {
                val nodeSuccess = target?.let { performNodeAction(it, AccessibilityNodeInfo.ACTION_CLICK) } == true
                if (nodeSuccess) {
                    params.put("mechanism", "semantic_node")
                    true
                } else {
                    val gestureSuccess = runCatching { dispatchPointGesture(params, longPress = false) }.getOrDefault(false)
                    if (gestureSuccess) params.put("mechanism", "gesture")
                    gestureSuccess
                }
            }
            "ui_long_press" -> {
                val nodeSuccess = target?.let { performNodeAction(it, AccessibilityNodeInfo.ACTION_LONG_CLICK) } == true
                if (nodeSuccess) {
                    params.put("mechanism", "semantic_node")
                    true
                } else {
                    val gestureSuccess = runCatching { dispatchPointGesture(params, longPress = true) }.getOrDefault(false)
                    if (gestureSuccess) params.put("mechanism", "gesture")
                    gestureSuccess
                }
            }
            "ui_set_text" -> {
                val text = params.optString("text")
                require(text.isNotEmpty()) { "text is required." }
                val node = target ?: throw IllegalArgumentException("Target node was not found.")
                require(!node.isPassword) { "Password fields cannot be targeted." }
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                var textSuccess = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                if (!textSuccess) {
                    val editableTarget = findEditableNode(node)
                    if (editableTarget != null && editableTarget != node) {
                        textSuccess = editableTarget.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    }
                }
                if (!textSuccess) {
                    performNodeAction(node, AccessibilityNodeInfo.ACTION_CLICK)
                    textSuccess = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                }
                if (!textSuccess) {
                    runCatching { dispatchPointGesture(params, longPress = false) }
                    textSuccess = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                }
                textSuccess
            }
            "ui_scroll" -> {
                val direction = params.optString("direction").lowercase()
                val scrollAction = when (direction) {
                    "down", "right" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    "up", "left" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else -> throw IllegalArgumentException("Unsupported scroll direction '${direction}'.")
                }
                val node = target ?: findScrollableAncestor(root)
                val nodeSuccess = node?.let { runCatching { performNodeAction(it, scrollAction) }.getOrDefault(false) } ?: false
                if (nodeSuccess) {
                    true
                } else {
                    dispatchScrollGesture(direction, params)
                }
            }
            "ui_focus" -> {
                val node = target ?: throw IllegalArgumentException("Target node was not found.")
                performNodeAction(node, android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            }
            "ui_node_action" -> {
                val node = target ?: throw IllegalArgumentException("Target node was not found.")
                val token = params.optString("node_action").trim().lowercase()
                val standard = StandardNodeAction.entries.firstOrNull { it.token == token }
                    ?: throw IllegalArgumentException("Unknown standard node action '$token'.")
                val actionId = AndroidActionCatalog.actionIdForToken(token)
                    ?: throw IllegalArgumentException("Node action '$token' is unavailable on this Android version.")
                require(node.actionList.any { it.id == actionId }) {
                    "Target node does not currently advertise '$token'."
                }
                require(!(node.isPassword && standard in PASSWORD_BLOCKED_NODE_ACTIONS)) {
                    "Clipboard and text actions cannot target password fields."
                }
                val arguments = buildNodeActionArguments(standard, node, params)
                val nodeSuccess = if (arguments == null) {
                    node.performAction(actionId)
                } else {
                    node.performAction(actionId, arguments)
                }
                if (nodeSuccess) params.put("mechanism", "semantic_node")
                nodeSuccess
            }
            "ui_custom_action" -> {
                val node = target ?: throw IllegalArgumentException("Target node was not found.")
                val actionId = params.optInt("trusted_action_id", Int.MIN_VALUE)
                require(actionId != Int.MIN_VALUE) { "A trusted observation-scoped action mapping is required." }
                require(AndroidActionCatalog.standardForId(actionId) == null) {
                    "Standard actions must use their canonical token."
                }
                val advertised = node.actionList.singleOrNull { it.id == actionId }
                    ?: throw IllegalArgumentException("Custom action is no longer advertised by the target.")
                val expectedLabel = params.optString("custom_action_label").trim()
                require(expectedLabel.isNotEmpty() && advertised.label?.toString()?.trim() == expectedLabel) {
                    "Custom action label changed since observation."
                }
                val customSuccess = node.performAction(actionId)
                if (customSuccess) params.put("mechanism", "custom_accessibility_action")
                customSuccess
            }
            "ui_gesture" -> {
                val token = params.optString("gesture").trim().lowercase()
                val destination = params.optJSONObject("destination_selector")?.let { findNode(root, it) }
                val gestureSuccess = if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2 &&
                    token == "drag_drop" &&
                    target != null &&
                    destination != null
                ) {
                    performSemanticDrag(target, destination, params)
                } else {
                    false
                }
                val completed = gestureSuccess || dispatchBoundedGesture(params)
                if (completed && !gestureSuccess) {
                    params.put("mechanism", if (token == "drag_drop") "gesture_drag_transaction" else "gesture")
                }
                completed
            }
            "ui_global_action" -> {
                val token = params.optString("global_action").trim().lowercase()
                val globalAction = GlobalSystemAction.fromToken(token)
                    ?: throw IllegalArgumentException("Unsupported global_action: $token")
                require(globalAction.plannerAllowed) { "Global action '$token' is reserved for explicit trusted use." }
                require(token in currentSystemActionTokens()) { "Global action '$token' is unavailable on this device." }
                val globalSuccess = performGlobalAction(globalAction.actionId)
                if (globalSuccess) params.put("mechanism", "global_action")
                globalSuccess
            }
            else -> throw IllegalArgumentException("Unknown UI action: ${action}")
        }
        if (!success) throw IllegalStateException("Accessibility action '${action}' failed.")
        val result = JSONObject()
            .put("ok", true)
            .put("action", action)
            .put("observation_id", observationId)
            .put("package", currentPackage)
        if (params.has("mechanism")) {
            result.put("mechanism", params.getString("mechanism"))
        }
        result
    }

    private fun clearActionLeaseLocked() {
        actionObservationLease.clear()
    }

    @SuppressLint("InlinedApi")
    private fun buildNodeActionArguments(
        action: StandardNodeAction,
        node: AccessibilityNodeInfo,
        params: JSONObject
    ): Bundle? = when (action) {
        StandardNodeAction.SET_TEXT -> {
            val text = params.optString("text")
            require(text.isNotEmpty()) { "set_text requires non-empty text." }
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
        }
        StandardNodeAction.SET_PROGRESS -> {
            val value = params.optDouble("value", Double.NaN)
            require(value.isFinite()) { "set_progress requires a finite value." }
            val range = node.rangeInfo ?: throw IllegalArgumentException("Target has no RangeInfo.")
            require(value >= range.min && value <= range.max) {
                "Requested progress $value is outside ${range.min}..${range.max}."
            }
            Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value.toFloat())
            }
        }
        StandardNodeAction.SET_SELECTION -> {
            val start = params.optInt("selection_start", -1)
            val end = params.optInt("selection_end", -1)
            require(start >= 0 && end >= start) { "set_selection requires a valid start/end range." }
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            }
        }
        StandardNodeAction.SCROLL_TO_POSITION -> {
            val row = params.optInt("row", -1)
            val column = params.optInt("column", -1)
            require(row >= 0 || column >= 0) { "scroll_to_position requires row or column." }
            Bundle().apply {
                if (row >= 0) putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, row)
                if (column >= 0) putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, column)
            }
        }
        StandardNodeAction.SCROLL_IN_DIRECTION -> {
            val direction = when (params.optString("direction").lowercase()) {
                "up" -> android.view.View.FOCUS_UP
                "down" -> android.view.View.FOCUS_DOWN
                "left" -> android.view.View.FOCUS_LEFT
                "right" -> android.view.View.FOCUS_RIGHT
                "forward" -> android.view.View.FOCUS_FORWARD
                "backward" -> android.view.View.FOCUS_BACKWARD
                else -> throw IllegalArgumentException("scroll_in_direction requires a supported direction.")
            }
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_DIRECTION_INT, direction)
                if (params.has("scroll_amount")) {
                    val amount = params.getDouble("scroll_amount")
                    require(amount.isFinite() && amount > 0.0) { "scroll_amount must be positive and finite." }
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT, amount.toFloat())
                }
            }
        }
        StandardNodeAction.NEXT_AT_MOVEMENT_GRANULARITY,
        StandardNodeAction.PREVIOUS_AT_MOVEMENT_GRANULARITY -> {
            val granularity = when (params.optString("granularity").lowercase()) {
                "character" -> AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER
                "word" -> AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD
                "line" -> AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
                "paragraph" -> AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH
                "page" -> AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PAGE
                else -> throw IllegalArgumentException("Unsupported movement granularity.")
            }
            require(node.movementGranularities and granularity != 0) {
                "Target does not advertise the requested movement granularity."
            }
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, granularity)
                putBoolean(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN,
                    params.optBoolean("extend_selection", false)
                )
            }
        }
        StandardNodeAction.NEXT_HTML_ELEMENT,
        StandardNodeAction.PREVIOUS_HTML_ELEMENT -> Bundle().apply {
            putString(
                AccessibilityNodeInfo.ACTION_ARGUMENT_HTML_ELEMENT_STRING,
                params.optString("html_element").trim().takeIf(String::isNotEmpty)
                    ?: throw IllegalArgumentException("HTML navigation requires html_element.")
            )
        }
        StandardNodeAction.MOVE_WINDOW -> {
            val x = params.optInt("x", -1)
            val y = params.optInt("y", -1)
            require(x in 0..resources.displayMetrics.widthPixels && y in 0..resources.displayMetrics.heightPixels) {
                "move_window coordinates must be inside the display."
            }
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVE_WINDOW_X, x)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVE_WINDOW_Y, y)
            }
        }
        StandardNodeAction.PRESS_AND_HOLD -> {
            val duration = params.optInt("duration_ms", -1)
            require(duration in 200..5_000) { "press_and_hold duration must be 200..5000 ms." }
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_PRESS_AND_HOLD_DURATION_MILLIS_INT, duration)
            }
        }
        else -> null
    }

    private fun UiSnapshot.toActionAuthority(): ActionObservationAuthority =
        ActionObservationAuthority(
            observationId = id,
            packageName = packageName,
            windowId = windowId,
            stateFingerprint = stateFingerprint,
            rotation = rotation,
            displayWidth = displayBounds.width(),
            displayHeight = displayBounds.height()
        )

    private fun targetWindow(): AccessibilityWindowInfo? {
        val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return appWindows.firstOrNull { it.isFocused && it.root?.packageName?.toString() != packageName }
            ?: appWindows.firstOrNull { it.isActive && it.root?.packageName?.toString() != packageName }
            ?: appWindows.filter { it.root?.packageName?.toString() != packageName }.maxByOrNull { it.layer }
            ?: appWindows.firstOrNull { it.isFocused }
            ?: appWindows.firstOrNull { it.isActive }
            ?: appWindows.maxByOrNull { it.layer }
            ?: windows.firstOrNull()
    }

    private fun currentSystemActionTokens(): Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            systemActions.mapNotNullTo(linkedSetOf()) { action ->
                GlobalSystemAction.fromId(action.id)?.takeIf { it.plannerAllowed }?.token
            }
        } else {
            emptySet()
        }

    /** Runtime-advertised globals are safe to route before a UI hierarchy capture. */
    fun availableGlobalActionTokens(): Set<String> = synchronized(observationLock) {
        currentSystemActionTokens()
    }

    fun performTrustedGlobalAction(token: String): Boolean = synchronized(observationLock) {
        val action = GlobalSystemAction.fromToken(token)
            ?: throw IllegalArgumentException("Unsupported global_action: $token")
        require(action.plannerAllowed) { "Global action '$token' is reserved for explicit trusted use." }
        require(action.token in currentSystemActionTokens()) {
            "Global action '$token' is unavailable on this device."
        }
        performGlobalAction(action.actionId)
    }

    @RequiresApi(Build.VERSION_CODES.S_V2)
    private fun performSemanticDrag(
        target: AccessibilityNodeInfo,
        destination: AccessibilityNodeInfo,
        params: JSONObject,
    ): Boolean {
        val semanticDragAvailable =
            target.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_DRAG_START.id } &&
                destination.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_DRAG_DROP.id }
        if (!semanticDragAvailable) return false

        val started = target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DRAG_START.id)
        if (!started) return false

        val dropped = destination.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DRAG_DROP.id)
        if (!dropped) {
            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DRAG_CANCEL.id)
        }
        if (dropped) params.put("mechanism", "semantic_drag_transaction")
        return dropped
    }

    private fun writeHierarchy(
        root: AccessibilityNodeInfo,
        window: AccessibilityWindowInfo?,
        observationId: String,
        destPath: String
    ): Boolean = try {
        File(destPath).parentFile?.mkdirs()
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val hierarchy = document.createElement("hierarchy").apply {
            setAttribute("schema-version", "2")
            setAttribute("observation-id", observationId)
            setAttribute("package", root.packageName?.toString().orEmpty())
            setAttribute("window-title", window?.title?.toString().orEmpty())
            val rotation = currentDisplayRotation()
            setAttribute("rotation", rotation.toString())
        }
        document.appendChild(hierarchy)
        buildXmlTree(root, hierarchy, document, "0")
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
            transform(DOMSource(document), StreamResult(File(destPath)))
        }
        true
    } catch (error: Exception) {
        Log.e(TAG, "Failed to dump XML", error)
        false
    }

    private fun currentDisplayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { display?.rotation ?: 0 }.getOrDefault(0)
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            }.getOrDefault(0)
        }
    }

    private fun buildXmlTree(node: AccessibilityNodeInfo, parent: Element, document: Document, path: String) {
        val bounds = Rect().also(node::getBoundsInScreen)
        val element = document.createElement("node").apply {
            setAttribute("tree-path", path)
            setAttribute("package", node.packageName?.toString().orEmpty())
            setAttribute("resource-id", node.viewIdResourceName.orEmpty())
            setAttribute("class", node.className?.toString().orEmpty())
            if (node.isPassword) {
                setAttribute("text", "•".repeat(node.text?.length ?: 8))
                setAttribute("content-desc", "•".repeat(node.contentDescription?.length ?: 8))
            } else {
                setAttribute("text", node.text?.toString().orEmpty())
                setAttribute("content-desc", node.contentDescription?.toString().orEmpty())
            }
            setAttribute("checkable", node.isCheckable.toString())
            setAttribute("checked", node.isChecked.toString())
            setAttribute("clickable", node.isClickable.toString())
            setAttribute("enabled", node.isEnabled.toString())
            setAttribute("editable", node.isEditable.toString())
            setAttribute("focusable", node.isFocusable.toString())
            setAttribute("focused", node.isFocused.toString())
            setAttribute("visible", node.isVisibleToUser.toString())
            setAttribute("scrollable", node.isScrollable.toString())
            setAttribute("long-clickable", node.isLongClickable.toString())
            setAttribute("password", node.isPassword.toString())
            setAttribute("selected", node.isSelected.toString())
            setAttribute("bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
        }
        parent.appendChild(element)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> buildXmlTree(child, element, document, "${path}/${index}") }
        }
    }

    private fun findNode(root: AccessibilityNodeInfo, selector: JSONObject): AccessibilityNodeInfo? {
        val treePath = selector.optString("tree_path").trim()
        if (treePath.isNotEmpty()) findByTreePath(root, treePath)?.let { node ->
            if (matchesSelector(node, selector)) return node
        }
        if (matchesSelector(root, selector)) return root
        for (index in 0 until root.childCount) {
            root.getChild(index)?.let { child -> findNode(child, selector)?.let { return it } }
        }
        return null
    }

    private fun findByTreePath(root: AccessibilityNodeInfo, treePath: String): AccessibilityNodeInfo? {
        val indexes = treePath.split('/').mapNotNull(String::toIntOrNull)
        if (indexes.isEmpty() || indexes.first() != 0) return null
        var current = root
        for (index in indexes.drop(1)) current = current.getChild(index) ?: return null
        return current
    }

    private fun matchesSelector(node: AccessibilityNodeInfo, selector: JSONObject): Boolean {
        val treePath = selector.optString("tree_path").trim()
        val fields = listOf(
            "resource_id" to node.viewIdResourceName.orEmpty(),
            "text" to node.text?.toString().orEmpty(),
            "content_desc" to node.contentDescription?.toString().orEmpty(),
            "class" to node.className?.toString().orEmpty()
        )
        var constrained = false
        for ((name, actual) in fields) {
            val expected = selector.optString(name).trim()
            if (expected.isNotEmpty()) {
                constrained = true
                if (actual != expected) return false
            }
        }
        val expectedBounds = selector.optString("bounds").trim()
        if (expectedBounds.isNotEmpty()) {
            val actualBounds = Rect().also(node::getBoundsInScreen)
            val actual = listOf(
                actualBounds.left,
                actualBounds.top,
                actualBounds.right,
                actualBounds.bottom
            ).joinToString(",")
            if (actual != expectedBounds) return false
        }
        val expectedElementId = selector.optString("element_id").trim()
        if (expectedElementId.isNotEmpty()) {
            val actualBounds = Rect().also(node::getBoundsInScreen)
            val actualElementId = ObservedNodeIdentity.compute(
                packageName = node.packageName?.toString(),
                resourceId = node.viewIdResourceName,
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                className = node.className?.toString(),
                left = actualBounds.left,
                top = actualBounds.top,
                right = actualBounds.right,
                bottom = actualBounds.bottom,
                treePath = treePath
            )
            if (actualElementId != expectedElementId) return false
        }
        return constrained || treePath.isNotEmpty()
    }

    private fun performNodeAction(node: AccessibilityNodeInfo, action: Int): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.actionList.any { it.id == action } && current.performAction(action)) return true
            current = current.parent
        }
        return false
    }

    private fun findScrollableAncestor(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isScrollable) return root
        for (index in 0 until root.childCount) {
            root.getChild(index)?.let { findScrollableAncestor(it)?.let { node -> return node } }
        }
        return null
    }

    private fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable) return root
        for (index in 0 until root.childCount) {
            root.getChild(index)?.let { child ->
                findEditableNode(child)?.let { return it }
            }
        }
        return null
    }

    private fun dispatchPointGesture(params: JSONObject, longPress: Boolean): Boolean {
        val bounds = params.optJSONArray("fallback_bounds")
            ?: throw IllegalArgumentException("Target node and fallback_bounds are unavailable.")
        require(bounds.length() == 4) { "fallback_bounds must contain four integers." }
        val left = bounds.getInt(0)
        val top = bounds.getInt(1)
        val right = bounds.getInt(2)
        val bottom = bounds.getInt(3)
        require(right > left && bottom > top) { "fallback_bounds are invalid." }
        require(left >= 0 && top >= 0 && right <= resources.displayMetrics.widthPixels && bottom <= resources.displayMetrics.heightPixels) {
            "fallback_bounds are outside the display."
        }
        val path = Path().apply { moveTo((left + right) / 2f, (top + bottom) / 2f) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, if (longPress) 650 else 80))
            .build()
        val deferred = CompletableDeferred<Boolean>()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                deferred.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                deferred.complete(false)
            }
        }, null)
        return kotlinx.coroutines.runBlocking { deferred.await() }
    }

    private fun dispatchBoundedGesture(params: JSONObject): Boolean {
        val token = params.optString("gesture").trim().lowercase()
        val start = normalizedPoint(params, "start_x", "start_y")
            ?: normalizedBoundsCenter(params.optJSONArray("fallback_bounds"))
        val end = normalizedPoint(params, "end_x", "end_y")
            ?: normalizedBoundsCenter(params.optJSONArray("destination_bounds"))
        val center = normalizedPoint(params, "center_x", "center_y") ?: start
        val points = BoundedGestureCatalog.parsePoints(params.optString("points"))
        val duration = if (params.has("duration_ms")) params.getLong("duration_ms") else when (token) {
            "press_and_hold" -> 650L
            "drag_drop", "polyline_drag" -> 700L
            "pinch_in", "pinch_out", "two_finger_swipe" -> 350L
            else -> 250L
        }
        val plan = BoundedGestureCatalog.build(token, start, end, center, points, duration)
        require(plan.strokes.size <= GestureDescription.getMaxStrokeCount()) {
            "Gesture exceeds the device stroke limit."
        }
        require(plan.strokes.maxOf { it.startTimeMs + it.durationMs } <= GestureDescription.getMaxGestureDuration()) {
            "Gesture exceeds the device duration limit."
        }
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val builder = GestureDescription.Builder()
        plan.strokes.forEach { stroke ->
            val first = stroke.points.first()
            val path = Path().apply {
                moveTo(first.x * width, first.y * height)
                stroke.points.drop(1).forEach { point -> lineTo(point.x * width, point.y * height) }
            }
            builder.addStroke(
                GestureDescription.StrokeDescription(path, stroke.startTimeMs, stroke.durationMs)
            )
        }
        val deferred = CompletableDeferred<Boolean>()
        dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) = deferred.complete(true).let { Unit }
            override fun onCancelled(gestureDescription: GestureDescription) = deferred.complete(false).let { Unit }
        }, null)
        return kotlinx.coroutines.runBlocking { deferred.await() }
    }

    private fun normalizedPoint(params: JSONObject, xKey: String, yKey: String): NormalizedPoint? {
        if (!params.has(xKey) && !params.has(yKey)) return null
        require(params.has(xKey) && params.has(yKey)) { "$xKey and $yKey must be supplied together." }
        return NormalizedPoint(params.getDouble(xKey).toFloat(), params.getDouble(yKey).toFloat())
    }

    private fun normalizedBoundsCenter(bounds: JSONArray?): NormalizedPoint? {
        if (bounds == null) return null
        require(bounds.length() == 4) { "Bounds must contain four integers." }
        val left = bounds.getInt(0)
        val top = bounds.getInt(1)
        val right = bounds.getInt(2)
        val bottom = bounds.getInt(3)
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        require(right > left && bottom > top && left >= 0 && top >= 0 && right <= width && bottom <= height) {
            "Gesture bounds are outside the current display."
        }
        return NormalizedPoint((left + right) / 2f / width, (top + bottom) / 2f / height)
    }

    private fun dispatchScrollGesture(direction: String, params: JSONObject): Boolean {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()

        val bounds = params.optJSONArray("fallback_bounds")
        val (startX, startY, endX, endY) = if (bounds != null && bounds.length() == 4) {
            val left = bounds.getInt(0).toFloat()
            val top = bounds.getInt(1).toFloat()
            val right = bounds.getInt(2).toFloat()
            val bottom = bounds.getInt(3).toFloat()
            val centerX = (left + right) / 2f
            val centerY = (top + bottom) / 2f
            val deltaY = ((bottom - top) * 0.4f).coerceAtLeast(200f)
            val deltaX = ((right - left) * 0.4f).coerceAtLeast(200f)
            when (direction) {
                "down" -> listOf(centerX, (centerY + deltaY / 2f).coerceAtMost(screenHeight - 50f), centerX, (centerY - deltaY / 2f).coerceAtLeast(50f))
                "up" -> listOf(centerX, (centerY - deltaY / 2f).coerceAtLeast(50f), centerX, (centerY + deltaY / 2f).coerceAtMost(screenHeight - 50f))
                "right" -> listOf((centerX + deltaX / 2f).coerceAtMost(screenWidth - 50f), centerY, (centerX - deltaX / 2f).coerceAtLeast(50f), centerY)
                "left" -> listOf((centerX - deltaX / 2f).coerceAtLeast(50f), centerY, (centerX + deltaX / 2f).coerceAtMost(screenWidth - 50f), centerY)
                else -> listOf(centerX, (centerY + 200f).coerceAtMost(screenHeight - 50f), centerX, (centerY - 200f).coerceAtLeast(50f))
            }
        } else {
            val centerX = screenWidth / 2f
            val centerY = screenHeight / 2f
            when (direction) {
                "down" -> listOf(centerX, screenHeight * 0.75f, centerX, screenHeight * 0.25f)
                "up" -> listOf(centerX, screenHeight * 0.25f, centerX, screenHeight * 0.75f)
                "right" -> listOf(screenWidth * 0.8f, centerY, screenWidth * 0.2f, centerY)
                "left" -> listOf(screenWidth * 0.2f, centerY, screenWidth * 0.8f, centerY)
                else -> listOf(centerX, screenHeight * 0.75f, centerX, screenHeight * 0.25f)
            }
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
            .build()
        val deferred = CompletableDeferred<Boolean>()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                deferred.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                deferred.complete(false)
            }
        }, null)
        return kotlinx.coroutines.runBlocking { deferred.await() }
    }

    companion object {
        private const val TAG = "NabuAccessibility"
        private const val EVENT_DEBOUNCE_MS = 40L
        private val SNAPSHOT_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        )
        private val PASSWORD_BLOCKED_NODE_ACTIONS = setOf(
            StandardNodeAction.COPY,
            StandardNodeAction.CUT,
            StandardNodeAction.PASTE,
            StandardNodeAction.SET_TEXT,
            StandardNodeAction.SET_SELECTION
        )

        private val _isConnected = kotlinx.coroutines.flow.MutableStateFlow(false)
        val isConnected: kotlinx.coroutines.flow.StateFlow<Boolean> = _isConnected.asStateFlow()

        @Volatile
        var instance: NabuAccessibilityService? = null
            private set(value) {
                field = value
                _isConnected.value = value != null
            }

        fun requestActionSurface(): Boolean {
            val service = instance ?: return false
            service.showActionSurface()
            return true
        }
    }
}

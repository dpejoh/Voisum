package com.voisum.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import com.voisum.app.R
import com.voisum.app.accessibility.VoisumAccessibilityService
import com.voisum.app.api.AiError
import com.voisum.app.api.AiProviderRouter
import com.voisum.app.debug.DebugLogger
import com.voisum.app.history.HistoryEntity
import com.voisum.app.history.HistoryRepository
import com.voisum.app.injection.TextInjector
import com.voisum.app.recording.AudioRecorderManager
import com.voisum.app.settings.PreferencesManager
import com.voisum.app.settings.PromptPreset
import com.voisum.app.settings.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * Manages the floating bubble overlay window.
 * Handles:
 * - Bubble creation, positioning, visibility tied to keyboard
 * - Drag, tap, long-press gesture detection
 * - State machine: IDLE -> RECORDING -> PROCESSING -> LANGUAGE_FLAG -> UNDO
 * - Radial menu for preset switching
 * - Error banners
 * - Clipboard banners
 */
class FloatingBubbleManager(
    private val context: Context,
    private val service: VoisumAccessibilityService
) {

    companion object {
        private const val TAG = "BubbleManager"
    }

    private val prefs = PreferencesManager(context)
    private val recorder = AudioRecorderManager(context)
    private val injector = TextInjector(context)
    private val router = AiProviderRouter(context)
    private val historyRepo = HistoryRepository(context)
    private val radialMenu = RadialMenuView(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var bubbleView: BubbleView? = null
    private var bannerView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bannerParams: WindowManager.LayoutParams? = null

    private var isShowing = false
    private var currentFocusedNode: AccessibilityNodeInfo? = null
    var currentPackageName: String = ""
        private set

    // Undo support
    private var undoText: String = ""
    private var undoNode: AccessibilityNodeInfo? = null

    // Drag tracking
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialParamX = 0
    private var initialParamY = 0
    private var isDragging = false
    private var totalMovement = 0f
    private val tapThresholdPx = (8 * context.resources.displayMetrics.density)

    // Long press tracking
    private var longPressRunnable: Runnable? = null
    private var isLongPress = false
    private val longPressThresholdMs = 400L

    // Pending hide: use a single reusable token so ALL pending hides can be
    // cancelled with one removeCallbacksAndMessages() call.  (BUG FIX #11)
    private val hideToken = Object()

    private val density = context.resources.displayMetrics.density
    private val bubbleSizePx = (56 * density).toInt()

    fun initialize() {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        DebugLogger.i(TAG, "initialize() — WindowManager acquired")

        recorder.setCallback(object : AudioRecorderManager.RecordingCallback {
            override fun onDurationUpdate(seconds: Int) {
                bubbleView?.updateDuration(seconds)
            }

            override fun onMaxDurationWarning() {
                showBanner("Recording will stop in 15 seconds", null)
            }

            override fun onMaxDurationReached() {
                DebugLogger.i(TAG, "Max recording duration reached")
                stopRecordingAndProcess()
            }

            override fun onError(message: String) {
                DebugLogger.e(TAG, "Recording error: $message")
                showErrorBanner(message, null)
                bubbleView?.transitionToIdle()
            }
        })

        recorder.setMaxDuration(prefs.maxRecordingDurationSeconds)
        DebugLogger.d(TAG, "Max recording duration: ${prefs.maxRecordingDurationSeconds}s")

        radialMenu.setListener(object : RadialMenuView.RadialMenuListener {
            override fun onPresetSelected(preset: PromptPreset) {
                prefs.activePresetId = preset.id
                bubbleView?.showPresetDot(preset.colorHex)
                handler.postDelayed({
                    bubbleView?.hidePresetDot()
                }, 1000)
                if (preset.id != "casual_dm") {
                    bubbleView?.showPresetLabel(preset.name)
                } else {
                    bubbleView?.hidePresetLabel()
                }
            }

            override fun onDismissed() {
                // no-op
            }
        })
    }

    /**
     * Called by AccessibilityService when a focused editable text field is detected.
     */
    fun onEditableFieldFocused(
        node: AccessibilityNodeInfo,
        packageName: String
    ) {
        DebugLogger.i(TAG, "onEditableFieldFocused(pkg=$packageName)")

        // CRITICAL BUG FIX #8: Cancel any pending hide before processing new focus
        cancelPendingHide()

        // CRITICAL BUG FIX #9: Read all needed properties before any potential recycle
        currentPackageName = packageName

        // Store the node reference for later injection
        currentFocusedNode?.recycle()
        currentFocusedNode = AccessibilityNodeInfo.obtain(node)

        // Check per-app preset rules
        val appPreset = prefs.getPresetForApp(packageName)
        if (appPreset != null) {
            prefs.activePresetId = appPreset.id
            DebugLogger.d(TAG, "Auto-switched to preset '${appPreset.name}' for $packageName")
        }

        showBubble()

        // Show preset dot briefly if auto-switched
        if (appPreset != null) {
            bubbleView?.showPresetDot(appPreset.colorHex)
            handler.postDelayed({
                bubbleView?.hidePresetDot()
            }, 1000)
        }
    }

    /**
     * Called when focus leaves an editable field.
     */
    fun onEditableFieldLost() {
        // CRITICAL: Never hide or discard while actively recording or processing.
        // The user is busy using the bubble — system events should not interrupt.
        val bv = bubbleView
        if (bv != null) {
            val state = bv.getState()
            if (state == BubbleView.State.RECORDING || state == BubbleView.State.PROCESSING) {
                DebugLogger.d(TAG, "onEditableFieldLost() IGNORED — bubble is $state")
                return
            }
        }

        DebugLogger.d(TAG, "onEditableFieldLost() — scheduling hide in 500ms")
        // Cancel any previously-scheduled hide first so we never stack them up
        cancelPendingHide()
        // Delay hide to prevent flicker on focus changes between fields
        handler.postAtTime({
            DebugLogger.d(TAG, "Hide runnable executing")
            // Double-check state hasn't changed during the delay
            val currentState = bubbleView?.getState()
            if (currentState == BubbleView.State.RECORDING || currentState == BubbleView.State.PROCESSING) {
                DebugLogger.d(TAG, "Hide cancelled — bubble entered $currentState during delay")
                return@postAtTime
            }
            hideBubble()
        }, hideToken, android.os.SystemClock.uptimeMillis() + 500)
    }

    private fun cancelPendingHide() {
        handler.removeCallbacksAndMessages(hideToken)
    }

    // --- Bubble Visibility ---

    private fun showBubble() {
        if (isShowing) {
            DebugLogger.d(TAG, "showBubble() — already showing, skip")
            return
        }

        // Use AccessibilityService windows to detect keyboard (reliable cross-app)
        val keyboardInfo = service.getKeyboardInfo()
        DebugLogger.d(TAG, "showBubble() keyboardInfo: visible=${keyboardInfo.isVisible}, height=${keyboardInfo.height}")

        // Show bubble even if keyboard is not detected yet — it may appear momentarily
        // Position will be at a reasonable default if keyboard height is unknown

        val bv = BubbleView(context)
        bubbleView = bv

        val side = prefs.bubbleSnappedSide
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val margin = (12 * density).toInt()

        val x = if (side == "left") margin else screenWidth - bubbleSizePx - margin

        // Calculate Y: above keyboard if visible, otherwise default to bottom quarter
        val y = if (keyboardInfo.isVisible && keyboardInfo.height > 0) {
            val keyboardTop = screenHeight - keyboardInfo.height
            keyboardTop - bubbleSizePx - margin
        } else {
            // Default: 75% down the screen (above where keyboard would be)
            (screenHeight * 0.65).toInt()
        }

        // Restore persisted Y offset if available
        val savedY = prefs.bubbleYOffset
        val finalY = if (savedY >= 0) savedY else y

        DebugLogger.d(TAG, "showBubble() position: x=$x, y=$finalY (side=$side, keyboardH=${keyboardInfo.height})")

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = finalY
        }
        bubbleParams = params

        // Set up touch handling
        bv.rootView.setOnTouchListener(bubbleTouchListener)

        try {
            windowManager?.addView(bv.rootView, params)
            isShowing = true
            DebugLogger.i(TAG, "Bubble ADDED to WindowManager")

            // Appear animation: slide up + fade in, 350ms
            bv.rootView.translationY = 50f
            bv.rootView.alpha = 0f
            bv.rootView.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(350)
                .setInterpolator(DecelerateInterpolator(2f))
                .start()

            // Show preset label if non-default
            val activePreset = prefs.getActivePreset()
            if (activePreset.id != "casual_dm") {
                bv.showPresetLabel(activePreset.name)
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to add bubble to WindowManager: ${e.message}")
            isShowing = false
        }
    }

    private fun hideBubble() {
        if (!isShowing) return
        val bv = bubbleView ?: return
        DebugLogger.d(TAG, "hideBubble() — animating out")

        // Disappear animation: fade out, 200ms
        bv.rootView.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                try {
                    windowManager?.removeView(bv.rootView)
                } catch (_: Exception) {}
                bv.destroy()
                bubbleView = null
                isShowing = false
            }
            .start()
    }

    private fun updateBubblePosition() {
        if (!isShowing) return
        val params = bubbleParams ?: return

        val keyboardInfo = service.getKeyboardInfo()
        val screenHeight = context.resources.displayMetrics.heightPixels
        val margin = (12 * density).toInt()

        val newY = if (keyboardInfo.isVisible && keyboardInfo.height > 0) {
            val keyboardTop = screenHeight - keyboardInfo.height
            keyboardTop - bubbleSizePx - margin
        } else {
            (screenHeight * 0.65).toInt()
        }

        val maxY = if (keyboardInfo.isVisible && keyboardInfo.height > 0) {
            screenHeight - keyboardInfo.height - margin
        } else {
            screenHeight - margin
        }

        params.y = minOf(newY, maxY)
        try {
            bubbleView?.rootView?.let { windowManager?.updateViewLayout(it, params) }
        } catch (_: Exception) {}
    }

    // --- Touch Handling ---

    private val bubbleTouchListener = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                initialParamX = bubbleParams?.x ?: 0
                initialParamY = bubbleParams?.y ?: 0
                isDragging = false
                isLongPress = false
                totalMovement = 0f

                // Start long press timer
                longPressRunnable = Runnable {
                    if (!isDragging && totalMovement < tapThresholdPx) {
                        isLongPress = true
                        onLongPress()
                    }
                }
                handler.postDelayed(longPressRunnable!!, longPressThresholdMs)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                totalMovement = abs(dx) + abs(dy)

                if (totalMovement >= tapThresholdPx) {
                    isDragging = true
                    // Cancel long press if dragging
                    longPressRunnable?.let { handler.removeCallbacks(it) }

                    val params = bubbleParams ?: return@OnTouchListener true
                    params.x = (initialParamX + dx).toInt()
                    params.y = (initialParamY + dy).toInt()

                    // Hard clamp Y: never lower than keyboardTop - 12dp
                    val screenHeight = context.resources.displayMetrics.heightPixels
                    val keyboardInfo = service.getKeyboardInfo()
                    val keyboardTop = if (keyboardInfo.isVisible) {
                        screenHeight - keyboardInfo.height
                    } else {
                        screenHeight
                    }
                    val margin = (12 * density).toInt()
                    val maxY = keyboardTop - margin
                    params.y = minOf(params.y, maxY)

                    try {
                        bubbleView?.rootView?.let { windowManager?.updateViewLayout(it, params) }
                    } catch (_: Exception) {}
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                longPressRunnable?.let { handler.removeCallbacks(it) }

                if (isDragging) {
                    // Snap to nearest horizontal edge
                    snapToEdge()
                } else if (!isLongPress && totalMovement < tapThresholdPx) {
                    // Tap
                    onTap()
                }
                isDragging = false
                isLongPress = false
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                longPressRunnable?.let { handler.removeCallbacks(it) }
                isDragging = false
                isLongPress = false
                true
            }

            else -> false
        }
    }

    private fun snapToEdge() {
        val params = bubbleParams ?: return
        val screenWidth = context.resources.displayMetrics.widthPixels
        val margin = (12 * density).toInt()

        val currentCenter = params.x + bubbleSizePx / 2
        val targetX = if (currentCenter < screenWidth / 2) {
            margin // Left edge
        } else {
            screenWidth - bubbleSizePx - margin // Right edge
        }

        val side = if (currentCenter < screenWidth / 2) "left" else "right"
        prefs.bubbleSnappedSide = side
        prefs.bubbleYOffset = params.y

        // Animate to target X
        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                try {
                    bubbleView?.rootView?.let { windowManager?.updateViewLayout(it, params) }
                } catch (_: Exception) {}
            }
            start()
        }
    }

    // --- User Actions ---

    private fun onTap() {
        val bv = bubbleView ?: return
        DebugLogger.d(TAG, "onTap() — current state: ${bv.getState()}")

        when (bv.getState()) {
            BubbleView.State.IDLE -> {
                startRecording()
            }
            BubbleView.State.RECORDING -> {
                stopRecordingAndProcess()
            }
            BubbleView.State.PROCESSING -> {
                // No action during processing
            }
            BubbleView.State.LANGUAGE_FLAG -> {
                // Start new recording
                bv.transitionToIdle()
                startRecording()
            }
            BubbleView.State.UNDO -> {
                performUndo()
            }
        }
    }

    private fun onLongPress() {
        // Open radial menu
        val params = bubbleParams ?: return
        val centerX = params.x + bubbleSizePx / 2
        val centerY = params.y + bubbleSizePx / 2

        windowManager?.let { wm ->
            radialMenu.show(wm, centerX, centerY, prefs.presets)
        }
    }

    // --- Recording ---

    private fun startRecording() {
        if (recorder.isCurrentlyRecording()) return

        DebugLogger.i(TAG, "startRecording()")
        val started = recorder.startRecording()
        if (started) {
            DebugLogger.i(TAG, "Recording started successfully")
            bubbleView?.transitionToRecording()
        } else {
            DebugLogger.e(TAG, "recorder.startRecording() returned false")
        }
    }

    private fun stopRecordingAndProcess() {
        val audioFile = recorder.stopRecording() ?: run {
            DebugLogger.e(TAG, "stopRecording() returned null — no audio file")
            return
        }
        DebugLogger.i(TAG, "Recording stopped, file: ${audioFile.name} (${audioFile.length()} bytes)")
        bubbleView?.transitionToProcessing()

        // Check file size
        if (audioFile.length() > 20 * 1024 * 1024) {
            showErrorBanner(context.getString(R.string.error_recording_too_large), null)
            bubbleView?.transitionToIdle()
            audioFile.delete()
            return
        }

        val activePreset = prefs.getActivePreset()
        val systemPrompt = activePreset.promptBody

        scope.launch {
            val result = router.processAudio(audioFile, systemPrompt)

            result.onSuccess { processingResult ->
                DebugLogger.i(TAG, "AI processing SUCCESS: lang=${processingResult.detectedLanguage}, " +
                    "provider=${processingResult.providerUsed}, textLen=${processingResult.text.length}")
                // Save to history
                val historyEntity = HistoryEntity(
                    detectedLanguage = processingResult.detectedLanguage,
                    audioFilePath = audioFile.absolutePath,
                    aiOutputText = processingResult.text,
                    providerUsed = processingResult.providerUsed,
                    presetUsed = activePreset.name,
                    pasteSucceeded = false
                )

                val historyId = historyRepo.insert(historyEntity)

                // Inject text
                val node = currentFocusedNode
                if (node != null) {
                    val injectionResult = injector.injectText(node, processingResult.text)

                    // Update history with paste success
                    historyRepo.insert(historyEntity.copy(
                        id = historyId,
                        pasteSucceeded = injectionResult.success && !injectionResult.usedClipboard
                    ))

                    if (injectionResult.usedClipboard) {
                        showClipboardBanner()
                    }

                    // Save undo state
                    undoText = injectionResult.previousText
                    undoNode = node

                    // Vibrate
                    triggerHapticFeedback()

                    // Show language flag
                    val flag = prefs.getFlagForLanguage(processingResult.detectedLanguage)
                    if (processingResult.detectedLanguage != "unknown") {
                        bubbleView?.transitionToLanguageFlag(flag)
                        handler.postDelayed({
                            enterUndoState()
                        }, 2500)
                    } else {
                        enterUndoState()
                    }
                } else {
                    bubbleView?.transitionToIdle()
                }
            }

            result.onFailure { error ->
                DebugLogger.e(TAG, "AI processing FAILED: ${error.javaClass.simpleName}: ${error.message}")
                handleAiError(error)
                bubbleView?.transitionToIdle()
            }
        }
    }

    private fun enterUndoState() {
        bubbleView?.transitionToUndo()
        handler.postDelayed({
            if (bubbleView?.getState() == BubbleView.State.UNDO) {
                bubbleView?.transitionToIdle()
            }
        }, 4000)
    }

    private fun performUndo() {
        val node = undoNode ?: return
        val success = injector.undoInjection(node, undoText)
        if (success) {
            showBanner(context.getString(R.string.undo_banner_text), null)
        }
        bubbleView?.transitionToIdle()
    }

    // --- Error Handling ---

    private fun handleAiError(error: Throwable) {
        when (error) {
            is AiError.Network -> showErrorBanner(
                context.getString(R.string.error_no_connection),
                "retry"
            )
            is AiError.InvalidApiKey -> showErrorBanner(
                context.getString(R.string.error_invalid_api_key),
                "settings"
            )
            is AiError.QuotaExceeded -> showErrorBanner(
                context.getString(R.string.error_quota_exceeded, error.provider),
                "settings"
            )
            is AiError.EmptyResponse -> showErrorBanner(
                context.getString(R.string.error_empty_response),
                "retry"
            )
            is AiError.TranscriptionUnavailable -> showErrorBanner(
                "On-device speech not available. Switch to Gemini or ChatGPT in Settings.",
                "settings"
            )
            is AiError.RecordingTooLarge -> showErrorBanner(
                context.getString(R.string.error_recording_too_large),
                null
            )
            else -> showErrorBanner(
                error.message ?: "An error occurred",
                "retry"
            )
        }
    }

    // --- Banners ---

    private fun showErrorBanner(message: String, action: String?) {
        showBanner(message, action)
    }

    private fun showClipboardBanner() {
        showBannerWithProgress(
            context.getString(R.string.clipboard_banner_text),
            3000
        )
    }

    private fun showBanner(message: String, action: String?) {
        removeBanner()

        val view = LayoutInflater.from(context).inflate(R.layout.layout_clipboard_banner, null)
        view.findViewById<TextView>(R.id.banner_text).text = message
        view.findViewById<ProgressBar>(R.id.banner_progress).visibility = View.GONE

        val actionView = view.findViewById<TextView>(R.id.banner_action)
        if (action != null) {
            actionView.visibility = View.VISIBLE
            when (action) {
                "retry" -> {
                    actionView.text = context.getString(R.string.btn_retry)
                    actionView.setOnClickListener {
                        removeBanner()
                        // Re-attempt processing if we have a file
                        recorder.getCurrentFile()?.let { stopRecordingAndProcess() }
                    }
                }
                "settings" -> {
                    actionView.text = context.getString(R.string.btn_settings)
                    actionView.setOnClickListener {
                        removeBanner()
                        val intent = Intent(context, SettingsActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                }
            }
        }

        bannerView = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }
        bannerParams = params

        try {
            windowManager?.addView(view, params)

            // Slide up animation
            view.translationY = 100f
            view.animate()
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator(2f))
                .start()

            // Auto-dismiss after 5 seconds
            handler.postDelayed({ removeBanner() }, 5000)
        } catch (_: Exception) {}
    }

    private fun showBannerWithProgress(message: String, durationMs: Long) {
        removeBanner()

        val view = LayoutInflater.from(context).inflate(R.layout.layout_clipboard_banner, null)
        view.findViewById<TextView>(R.id.banner_text).text = message
        view.findViewById<TextView>(R.id.banner_action).visibility = View.GONE

        val progressBar = view.findViewById<ProgressBar>(R.id.banner_progress)
        progressBar.visibility = View.VISIBLE
        progressBar.max = 100
        progressBar.progress = 100

        bannerView = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }
        bannerParams = params

        try {
            windowManager?.addView(view, params)

            view.translationY = 100f
            view.animate()
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator(2f))
                .start()

            // Animate progress bar countdown
            ValueAnimator.ofInt(100, 0).apply {
                duration = durationMs
                addUpdateListener { anim ->
                    progressBar.progress = anim.animatedValue as Int
                }
                start()
            }

            handler.postDelayed({ removeBanner() }, durationMs)
        } catch (_: Exception) {}
    }

    private fun removeBanner() {
        bannerView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        bannerView = null
    }

    // --- Haptic Feedback ---

    private fun triggerHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(
                    VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            }
        } catch (_: Exception) {}
    }

    // --- Lifecycle ---

    fun destroy() {
        DebugLogger.i(TAG, "destroy() — cleaning up")
        cancelPendingHide()
        if (recorder.isCurrentlyRecording()) {
            recorder.discardRecording()
        }
        radialMenu.dismiss()
        hideBubble()
        removeBanner()
        currentFocusedNode?.recycle()
        currentFocusedNode = null
        undoNode = null
        scope.cancel()
    }
}

package com.voisum.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.voisum.app.debug.DebugLogger
import com.voisum.app.overlay.FloatingBubbleManager

/**
 * AccessibilityService — detects when an editable text field gains focus
 * (TYPE_VIEW_FOCUSED, isEditable = true) and drives all bubble visibility logic.
 *
 * Uses getWindows() + TYPE_INPUT_METHOD to reliably detect keyboard across apps
 * (the transparent-overlay approach does NOT work cross-app).
 *
 * CRITICAL BUG FIX #2: Do not call startForeground() inside the AccessibilityService.
 * CRITICAL BUG FIX #3: Only hide for our own events when className starts with our package.
 * CRITICAL BUG FIX #8: Store pending hide runnable; cancel before new focus.
 * CRITICAL BUG FIX #9: Read all properties from event.source before recycle().
 */
class VoisumAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "A11yService"
        var instance: VoisumAccessibilityService? = null
            private set
    }

    private var bubbleManager: FloatingBubbleManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        DebugLogger.i(TAG, "onServiceConnected — service is live")

        bubbleManager = FloatingBubbleManager(applicationContext, this).also {
            it.initialize()
        }
        DebugLogger.i(TAG, "FloatingBubbleManager initialized")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                handleViewFocused(event)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
            }
            else -> { /* ignored */ }
        }
    }

    private fun handleViewFocused(event: AccessibilityEvent) {
        val source = event.source ?: run {
            DebugLogger.w(TAG, "TYPE_VIEW_FOCUSED but source is null (pkg=${event.packageName})")
            return
        }

        // CRITICAL BUG FIX #9: Read all properties before recycle
        val isEditable = source.isEditable
        val className = source.className?.toString() ?: "?"
        val packageName = event.packageName?.toString() ?: ""

        DebugLogger.d(TAG, "VIEW_FOCUSED pkg=$packageName class=$className isEditable=$isEditable")

        if (isEditable) {
            DebugLogger.i(TAG, "Editable field focused in $packageName — triggering bubble")
            bubbleManager?.onEditableFieldFocused(source, packageName)
        }

        source.recycle()
    }

    // Packages whose WINDOW_STATE_CHANGED must NOT trigger onEditableFieldLost.
    // These fire constantly (status bar, notification shade, keyboard, etc.)
    // and are NOT real app-switches.
    private val ignoredWindowPackages = setOf(
        "com.android.systemui",                     // status bar, nav bar, notification shade
        "com.google.android.inputmethod.latin",     // Gboard
        "com.samsung.android.honeyboard",           // Samsung keyboard
        "com.swiftkey.swiftkey",                    // SwiftKey
        "com.touchtype.swiftkey",                   // SwiftKey (alt pkg)
        "com.sec.android.inputmethod",              // Samsung keyboard (alt)
        "com.microsoft.swiftkey"                    // SwiftKey by Microsoft
    )

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: ""
        val className = event.className?.toString() ?: ""

        // CRITICAL BUG FIX #3: Our overlay FrameLayout fires this event with
        // pkg=com.voisum.app and class=android.widget.FrameLayout.
        // Only react to actual Activity class names from our package.
        if (packageName == "com.voisum.app") {
            if (!className.startsWith("com.voisum.app.")) {
                DebugLogger.d(TAG, "Ignoring own overlay widget event ($className)")
                return
            }
        }

        // CRITICAL BUG FIX #10: Ignore events from system UI and keyboard.
        // These fire constantly and are NOT app-switches — they were killing
        // the bubble and discarding recordings.
        if (packageName in ignoredWindowPackages) {
            DebugLogger.d(TAG, "Ignoring system/IME WINDOW_STATE_CHANGED from $packageName")
            return
        }

        // If the event is from the SAME app that has the bubble, skip — the user
        // is just navigating within that app (e.g. switching tabs, loading content)
        // and the editable field may still be present.  Only a TYPE_VIEW_FOCUSED from
        // a *different* app or a non-editable view should hide the bubble.
        val bubblePkg = bubbleManager?.currentPackageName
        if (bubblePkg != null && packageName == bubblePkg) {
            DebugLogger.d(TAG, "Same-app window change ($packageName) — ignoring, field may still exist")
            return
        }

        // Real app-switch: the focused field is likely gone
        DebugLogger.d(TAG, "App window change (pkg=$packageName) — calling onEditableFieldLost()")
        bubbleManager?.onEditableFieldLost()
    }

    // --- Keyboard detection via accessibility windows ---

    /**
     * Detect keyboard visibility and height by inspecting accessibility windows
     * for TYPE_INPUT_METHOD. This works reliably across apps, unlike the
     * transparent-overlay approach.
     */
    private var lastLoggedKeyboardHeight = -1

    fun getKeyboardInfo(): KeyboardInfo {
        val windowList = try { windows } catch (_: Exception) { null }
        if (windowList == null) {
            return KeyboardInfo(false, 0)
        }

        for (window in windowList) {
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                val bounds = Rect()
                window.getBoundsInScreen(bounds)
                val height = bounds.height()
                val visible = height > 0
                // Only log when keyboard state changes to avoid spam
                if (height != lastLoggedKeyboardHeight) {
                    lastLoggedKeyboardHeight = height
                    DebugLogger.d(TAG, "IME window: height=$height visible=$visible bounds=$bounds")
                }
                return KeyboardInfo(visible, height)
            }
        }

        if (lastLoggedKeyboardHeight != 0) {
            lastLoggedKeyboardHeight = 0
            DebugLogger.d(TAG, "No IME window found — keyboard hidden")
        }
        return KeyboardInfo(false, 0)
    }

    data class KeyboardInfo(val isVisible: Boolean, val height: Int)

    override fun onInterrupt() {
        DebugLogger.w(TAG, "onInterrupt called")
    }

    override fun onDestroy() {
        DebugLogger.i(TAG, "onDestroy — cleaning up")
        bubbleManager?.destroy()
        bubbleManager = null
        instance = null
        super.onDestroy()
    }
}

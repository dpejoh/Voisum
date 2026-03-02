package com.voisum.app.accessibility

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager

/**
 * Measures keyboard height using a transparent full-screen overlay window
 * with ViewTreeObserver.OnGlobalLayoutListener.
 *
 * Keyboard height = screenHeight - visibleFrameBottom - navigationBarHeight.
 * If keyboard height < 100dp, treat keyboard as hidden.
 *
 * Bubble Y position = keyboardTop - bubbleHeight - 12dp.
 */
class KeyboardHeightDetector(private val context: Context) {

    interface KeyboardHeightListener {
        fun onKeyboardHeightChanged(height: Int, isVisible: Boolean)
    }

    private var windowManager: WindowManager? = null
    private var measuringView: View? = null
    private var listener: KeyboardHeightListener? = null
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var lastKeyboardHeight = 0

    private val density = context.resources.displayMetrics.density
    private val minKeyboardHeightDp = 100
    private val minKeyboardHeightPx = (minKeyboardHeightDp * density).toInt()

    fun start(listener: KeyboardHeightListener) {
        this.listener = listener

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        measuringView = View(context).apply {
            // Fully transparent, non-interactive overlay for measurement only
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Make it fully transparent and non-interactive
            alpha = 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Hide from screenshots and screen recording
                flags = flags or WindowManager.LayoutParams.FLAG_SECURE
            }
        }

        try {
            windowManager?.addView(measuringView, params)
        } catch (e: Exception) {
            return
        }

        layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            measureKeyboardHeight()
        }
        measuringView?.viewTreeObserver?.addOnGlobalLayoutListener(layoutListener)
    }

    fun stop() {
        layoutListener?.let { ll ->
            measuringView?.viewTreeObserver?.removeOnGlobalLayoutListener(ll)
        }
        layoutListener = null

        try {
            measuringView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        measuringView = null
        windowManager = null
        listener = null
    }

    fun getLastKeyboardHeight(): Int = lastKeyboardHeight

    fun isKeyboardVisible(): Boolean = lastKeyboardHeight >= minKeyboardHeightPx

    private fun measureKeyboardHeight() {
        val view = measuringView ?: return

        val screenHeight = context.resources.displayMetrics.heightPixels
        val visibleRect = Rect()
        view.getWindowVisibleDisplayFrame(visibleRect)

        val navigationBarHeight = getNavigationBarHeight()
        val keyboardHeight = screenHeight - visibleRect.bottom - navigationBarHeight

        val clamped = maxOf(0, keyboardHeight)
        val isVisible = clamped >= minKeyboardHeightPx

        if (clamped != lastKeyboardHeight) {
            lastKeyboardHeight = clamped
            listener?.onKeyboardHeightChanged(clamped, isVisible)
        }
    }

    private fun getNavigationBarHeight(): Int {
        val resourceId = context.resources.getIdentifier(
            "navigation_bar_height", "dimen", "android"
        )
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
    }

    /**
     * Calculate the Y position for the bubble.
     * bubbleY = keyboardTop - bubbleHeight - 12dp
     * Hard-clamped so it never sits lower than keyboardTop - 12dp.
     */
    fun calculateBubbleY(bubbleHeightPx: Int): Int {
        val screenHeight = context.resources.displayMetrics.heightPixels
        val keyboardTop = screenHeight - lastKeyboardHeight
        val margin = (12 * density).toInt()
        val bubbleY = keyboardTop - bubbleHeightPx - margin

        // Hard clamp: never lower than keyboardTop - margin
        val maxY = keyboardTop - margin
        return minOf(bubbleY, maxY)
    }
}

package com.voisum.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import com.voisum.app.settings.PreferencesManager
import com.voisum.app.settings.PromptPreset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Radial menu that opens from the bubble position.
 * Shows up to 5 prompt preset chips in a semicircle above the bubble.
 *
 * Opens with spring expand animation (200ms, overshoot interpolator).
 * Closes with fast scale collapse (120ms).
 */
class RadialMenuView(private val context: Context) {

    interface RadialMenuListener {
        fun onPresetSelected(preset: PromptPreset)
        fun onDismissed()
    }

    private var windowManager: WindowManager? = null
    private var menuView: RadialCanvas? = null
    private var isShowing = false
    private var listener: RadialMenuListener? = null

    private val density = context.resources.displayMetrics.density
    private val chipRadius = (110 * density).toInt() // Distance from center to chip center
    private val chipWidth = (80 * density).toInt()
    private val chipHeight = (32 * density).toInt()

    private var presets: List<PromptPreset> = emptyList()
    private var anchorX: Int = 0
    private var anchorY: Int = 0

    fun setListener(l: RadialMenuListener) {
        listener = l
    }

    fun show(
        windowMgr: WindowManager,
        centerX: Int,
        centerY: Int,
        presetList: List<PromptPreset>
    ) {
        if (isShowing) return
        this.windowManager = windowMgr
        this.presets = presetList.take(5)
        this.anchorX = centerX
        this.anchorY = centerY

        menuView = RadialCanvas(context, presets, centerX, centerY, chipRadius, density).apply {
            setOnItemClickListener { index ->
                if (index in presets.indices) {
                    listener?.onPresetSelected(presets[index])
                }
                dismiss()
            }
            setOnOutsideTapListener {
                dismiss()
            }
        }

        val menuSize = (chipRadius * 2 + chipWidth + 40 * density).toInt()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowMgr.addView(menuView, params)
            isShowing = true

            // Spring expand animation
            menuView?.let { view ->
                view.scaleX = 0f
                view.scaleY = 0f
                view.pivotX = centerX.toFloat()
                view.pivotY = centerY.toFloat()
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(OvershootInterpolator(1.5f))
                    .start()
            }
        } catch (_: Exception) {}
    }

    fun dismiss() {
        if (!isShowing) return

        menuView?.let { view ->
            view.animate()
                .scaleX(0f)
                .scaleY(0f)
                .setDuration(120)
                .withEndAction {
                    try {
                        windowManager?.removeView(view)
                    } catch (_: Exception) {}
                    isShowing = false
                    listener?.onDismissed()
                }
                .start()
        } ?: run {
            isShowing = false
        }
    }

    fun isVisible(): Boolean = isShowing

    /**
     * Custom View that draws the radial menu chips on a canvas.
     */
    private class RadialCanvas(
        context: Context,
        private val presets: List<PromptPreset>,
        private val centerX: Int,
        private val centerY: Int,
        private val radius: Int,
        private val density: Float
    ) : View(context) {

        private var onItemClick: ((Int) -> Unit)? = null
        private var onOutsideTap: (() -> Unit)? = null

        private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E0303030")
            style = Paint.Style.FILL
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12 * density
            textAlign = Paint.Align.CENTER
        }

        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val chipRects = mutableListOf<RectF>()
        private val chipWidth = (80 * density)
        private val chipHeight = (32 * density)

        fun setOnItemClickListener(listener: (Int) -> Unit) {
            onItemClick = listener
        }

        fun setOnOutsideTapListener(listener: () -> Unit) {
            onOutsideTap = listener
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            chipRects.clear()

            val count = presets.size
            if (count == 0) return

            // Arrange chips in a semicircle above the anchor point
            val startAngle = PI  // 180 degrees (left)
            val endAngle = 2 * PI  // 360 degrees (right) — semicircle above
            val angleStep = if (count > 1) (endAngle - startAngle) / (count - 1) else 0.0

            for (i in presets.indices) {
                val angle = if (count > 1) startAngle + angleStep * i else 1.5 * PI
                val cx = centerX + (radius * cos(angle)).toFloat()
                val cy = centerY + (radius * sin(angle)).toFloat()

                val rect = RectF(
                    cx - chipWidth / 2,
                    cy - chipHeight / 2,
                    cx + chipWidth / 2,
                    cy + chipHeight / 2
                )
                chipRects.add(rect)

                // Draw chip background
                canvas.drawRoundRect(rect, chipHeight / 2, chipHeight / 2, chipPaint)

                // Draw color dot
                try {
                    dotPaint.color = Color.parseColor(presets[i].colorHex)
                } catch (_: Exception) {
                    dotPaint.color = Color.GRAY
                }
                canvas.drawCircle(
                    rect.left + 14 * density,
                    cy,
                    4 * density,
                    dotPaint
                )

                // Draw label (truncated to 14 chars)
                val label = presets[i].name.take(14)
                canvas.drawText(
                    label,
                    cx + 6 * density,
                    cy + textPaint.textSize / 3,
                    textPaint
                )
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                val x = event.x
                val y = event.y

                for (i in chipRects.indices) {
                    if (chipRects[i].contains(x, y)) {
                        onItemClick?.invoke(i)
                        return true
                    }
                }

                // Tapped outside all chips
                onOutsideTap?.invoke()
                return true
            }
            return true
        }
    }
}

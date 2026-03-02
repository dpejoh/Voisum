package com.voisum.app.overlay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.voisum.app.R

/**
 * Manages the visual states of the floating bubble.
 * Five states: IDLE, RECORDING, PROCESSING, LANGUAGE_FLAG, UNDO.
 *
 * All colors are hardcoded hex in layout_bubble.xml because this is inflated
 * from Application context which has no theme (CRITICAL BUG FIX #1).
 */
class BubbleView(context: Context) {

    enum class State {
        IDLE, RECORDING, PROCESSING, LANGUAGE_FLAG, UNDO
    }

    val rootView: View = LayoutInflater.from(context).inflate(R.layout.layout_bubble, null)

    private val bubbleCircle: FrameLayout = rootView.findViewById(R.id.bubble_circle)
    private val rippleView: View = rootView.findViewById(R.id.ripple_view)
    private val progressRing: ProgressBar = rootView.findViewById(R.id.progress_ring)
    private val iconMic: ImageView = rootView.findViewById(R.id.icon_mic)
    private val iconDots: TextView = rootView.findViewById(R.id.icon_dots)
    private val iconFlag: TextView = rootView.findViewById(R.id.icon_flag)
    private val iconUndo: ImageView = rootView.findViewById(R.id.icon_undo)
    private val durationChip: TextView = rootView.findViewById(R.id.duration_chip)
    private val presetDot: View = rootView.findViewById(R.id.preset_dot)
    private val presetLabel: TextView = rootView.findViewById(R.id.preset_label)

    private var currentState: State = State.IDLE
    private var rippleAnimator: ObjectAnimator? = null
    private var dotsAnimator: ValueAnimator? = null

    init {
        transitionToIdle()
    }

    fun getState(): State = currentState

    // --- State transitions ---

    fun transitionToIdle() {
        currentState = State.IDLE
        stopAllAnimations()

        // CrossFade 180ms equivalent: quick fade transitions
        iconMic.apply { alpha = 0f; visibility = View.VISIBLE; animate().alpha(1f).setDuration(180).start() }
        iconDots.visibility = View.GONE
        iconFlag.visibility = View.GONE
        iconUndo.visibility = View.GONE
        rippleView.alpha = 0f
        progressRing.visibility = View.GONE
        durationChip.visibility = View.GONE
    }

    fun transitionToRecording() {
        currentState = State.RECORDING
        stopAllAnimations()

        iconMic.apply { alpha = 0f; visibility = View.VISIBLE; animate().alpha(1f).setDuration(180).start() }
        iconDots.visibility = View.GONE
        iconFlag.visibility = View.GONE
        iconUndo.visibility = View.GONE
        progressRing.visibility = View.GONE
        durationChip.apply { visibility = View.VISIBLE; text = "0:00" }

        // Start pulsing red ripple animation
        startRippleAnimation()
    }

    fun transitionToProcessing() {
        currentState = State.PROCESSING
        stopAllAnimations()

        iconMic.visibility = View.GONE
        iconDots.apply {
            alpha = 0f; visibility = View.VISIBLE
            animate().alpha(1f).setDuration(180).start()
        }
        iconFlag.visibility = View.GONE
        iconUndo.visibility = View.GONE
        rippleView.alpha = 0f
        progressRing.visibility = View.VISIBLE
        durationChip.visibility = View.GONE

        startDotsAnimation()
    }

    fun transitionToLanguageFlag(flag: String) {
        currentState = State.LANGUAGE_FLAG
        stopAllAnimations()

        iconMic.visibility = View.GONE
        iconDots.visibility = View.GONE
        iconFlag.apply {
            text = flag
            alpha = 0f; visibility = View.VISIBLE
            animate().alpha(1f).setDuration(180).start()
        }
        iconUndo.visibility = View.GONE
        rippleView.alpha = 0f
        progressRing.visibility = View.GONE
        durationChip.visibility = View.GONE
    }

    fun transitionToUndo() {
        currentState = State.UNDO
        stopAllAnimations()

        iconMic.visibility = View.GONE
        iconDots.visibility = View.GONE
        iconFlag.visibility = View.GONE
        iconUndo.apply {
            alpha = 0f; visibility = View.VISIBLE
            animate().alpha(1f).setDuration(180).start()
        }
        rippleView.alpha = 0f
        progressRing.visibility = View.GONE
        durationChip.visibility = View.GONE
    }

    // --- Duration display ---

    fun updateDuration(seconds: Int) {
        val minutes = seconds / 60
        val secs = seconds % 60
        durationChip.text = String.format("%d:%02d", minutes, secs)
    }

    // --- Preset indicator ---

    fun showPresetDot(colorHex: String) {
        try {
            presetDot.background.setTint(android.graphics.Color.parseColor(colorHex))
            presetDot.visibility = View.VISIBLE
        } catch (_: Exception) {
            presetDot.visibility = View.GONE
        }
    }

    fun hidePresetDot() {
        presetDot.visibility = View.GONE
    }

    fun showPresetLabel(name: String) {
        presetLabel.text = name
        presetLabel.visibility = View.VISIBLE
    }

    fun hidePresetLabel() {
        presetLabel.visibility = View.GONE
    }

    // --- Animations ---

    private fun startRippleAnimation() {
        rippleView.alpha = 0.6f
        rippleView.scaleX = 1f
        rippleView.scaleY = 1f

        rippleAnimator = ObjectAnimator.ofFloat(rippleView, "alpha", 0.6f, 0f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            start()
        }

        // Scale animation for the ripple
        ObjectAnimator.ofFloat(rippleView, "scaleX", 1f, 1.6f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            start()
        }
        ObjectAnimator.ofFloat(rippleView, "scaleY", 1f, 1.6f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            start()
        }
    }

    private fun startDotsAnimation() {
        val dots = arrayOf("·  ", " · ", "  ·", " · ")
        var index = 0
        dotsAnimator = ValueAnimator.ofInt(0, dots.size - 1).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val newIndex = it.animatedValue as Int
                if (newIndex != index) {
                    index = newIndex
                    iconDots.text = dots[index]
                }
            }
            start()
        }
    }

    private fun stopAllAnimations() {
        rippleAnimator?.cancel()
        rippleAnimator = null
        dotsAnimator?.cancel()
        dotsAnimator = null
        rippleView.animate().cancel()
    }

    fun destroy() {
        stopAllAnimations()
    }
}

package com.os4.musiccover.island

import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator

/** Changes the existing text views' content. Old and new text are never drawn together. */
internal class CapsuleTextFeedback(private val render: () -> Unit) {
    private val state = CapsuleTextFeedbackState()
    val text: String? get() = state.frame.message
    val alpha: Float get() = state.frame.alpha
    val frame: CapsuleTextFeedbackState.Frame get() = state.frame
    private var animator: ValueAnimator? = null
    private var generation = 0

    fun show(message: String) {
        if (!state.begin(message)) return
        cancelAnimation()
        val token = generation
        render()
        animator = ValueAnimator.ofFloat(0f, CapsuleTextFeedbackState.DURATION_MS.toFloat()).apply {
            duration = CapsuleTextFeedbackState.DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (token == generation) {
                    state.at((it.animatedValue as Float).toLong())
                    render()
                }
            }
            start()
        }
    }

    private fun cancelAnimation() {
        generation++
        animator?.removeAllUpdateListeners()
        animator?.cancel()
        animator = null
    }

    fun clear() {
        cancelAnimation()
        state.clear()
        render()
    }
}

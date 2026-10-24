package com.os4.musiccover.island

/** Geometry and opacity only: the current media drawable remains owned by playback binding. */
internal class LyricFeedbackState {
    companion object {
        const val WORD_DRAW_MS = 320L
        const val CLOSE_WORD_MS = 120L
        const val CROSS_DRAW_MS = 240L
        const val QUESTION_DRAW_MS = 280L
        const val WORD_HOLD_MS = 300L
        const val RESTORE_MS = 180L
    }

    enum class Mode { ENABLED, DISABLED, PENDING, UNAVAILABLE }

    data class Frame(val word: Float = 0f, val reveal: Float = 0f, val cross: Float = 0f,
                     val playback: Float = 1f, val question: Float = 0f)
    private var origin = Frame()
    var frame = Frame()
        private set
    private var mode = Mode.DISABLED
    val durationMs: Long
        get() = drawingMs + WORD_HOLD_MS + RESTORE_MS
    private val drawingMs: Long
        get() = when (mode) {
            Mode.ENABLED, Mode.PENDING -> WORD_DRAW_MS
            Mode.DISABLED -> CLOSE_WORD_MS + CROSS_DRAW_MS
            Mode.UNAVAILABLE -> WORD_DRAW_MS + QUESTION_DRAW_MS
        }

    /** Interruptions start at the last drawn frame, including during the return to playback. */
    fun begin(on: Boolean) = begin(if (on) Mode.ENABLED else Mode.DISABLED)

    fun begin(next: Mode) {
        mode = next
        origin = frame
    }

    fun at(ms: Long): Frame {
        val time = ms.coerceAtLeast(0L)
        val written = Frame(word = 1f, reveal = 1f, cross = if (mode == Mode.DISABLED) 1f else 0f,
            playback = 0f, question = if (mode == Mode.UNAVAILABLE) 1f else 0f)
        val restoreAt = drawingMs + WORD_HOLD_MS
        frame = when {
            time >= durationMs -> Frame()
            (mode == Mode.ENABLED || mode == Mode.PENDING) && time < WORD_DRAW_MS ->
                mix(origin, written, time / WORD_DRAW_MS.toFloat())
            mode == Mode.DISABLED && time < CLOSE_WORD_MS -> mix(origin,
                written.copy(cross = origin.cross), time / CLOSE_WORD_MS.toFloat())
            mode == Mode.DISABLED && time < drawingMs -> mix(written.copy(cross = origin.cross), written,
                (time - CLOSE_WORD_MS) / CROSS_DRAW_MS.toFloat())
            mode == Mode.UNAVAILABLE && time < WORD_DRAW_MS -> mix(origin,
                written.copy(question = origin.question), time / WORD_DRAW_MS.toFloat())
            mode == Mode.UNAVAILABLE && time < drawingMs -> mix(written.copy(question = origin.question),
                written, (time - WORD_DRAW_MS) / QUESTION_DRAW_MS.toFloat())
            time < restoreAt -> written
            // Fade the completed drawing without erasing its geometry. A new click can still
            // reverse from this precise partially transparent frame.
            else -> mix(written, written.copy(word = 0f, playback = 1f),
                (time - restoreAt) / RESTORE_MS.toFloat())
        }
        return frame
    }

    fun clear() { frame = Frame(); origin = frame }
    private fun mix(a: Frame, b: Frame, amount: Float): Frame {
        val t = amount.coerceIn(0f, 1f)
        fun lerp(x: Float, y: Float) = x + (y - x) * t
        return Frame(lerp(a.word, b.word), lerp(a.reveal, b.reveal), lerp(a.cross, b.cross),
            lerp(a.playback, b.playback), lerp(a.question, b.question))
    }
}

/** A diagonal word reveal, followed by an X's two strokes or a question's curve and dot. */
internal object LyricFeedbackGeometry {
    const val CROSS_INSET = 0.08f
    fun revealEdge(progress: Float) = progress.coerceIn(0f, 1f) * 2f
    fun firstCrossStroke(progress: Float) = (progress * 2f).coerceIn(0f, 1f)
    fun secondCrossStroke(progress: Float) = (progress * 2f - 1f).coerceIn(0f, 1f)
    fun questionHook(progress: Float) = (progress / 0.82f).coerceIn(0f, 1f)
    fun questionDot(progress: Float) = ((progress - 0.82f) / 0.18f).coerceIn(0f, 1f)
}

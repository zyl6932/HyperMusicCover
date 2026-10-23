package com.os4.musiccover.island

/** One content slot: fade its current owner out before replacing it, including on interruption. */
internal class CapsuleTextFeedbackState {
    companion object {
        const val FADE_MS = 120L
        const val HOLD_MS = 780L
        const val DURATION_MS = FADE_MS * 4 + HOLD_MS
    }

    data class Frame(val message: String? = null, val alpha: Float = 1f)
    var frame = Frame()
        private set
    private var origin = frame
    private var requested: String? = null

    /** Rebinding the same pending result must not restart its animation. */
    fun begin(message: String): Boolean {
        if (requested == message) return false
        requested = message
        origin = frame
        return true
    }

    fun at(ms: Long): Frame {
        val message = requested ?: return frame
        val time = ms.coerceAtLeast(0L)
        frame = when {
            time < FADE_MS -> origin.copy(alpha = origin.alpha * (1f - time / FADE_MS.toFloat()))
            time < FADE_MS * 2 -> Frame(message, (time - FADE_MS) / FADE_MS.toFloat())
            time < FADE_MS * 2 + HOLD_MS -> Frame(message, 1f)
            time < FADE_MS * 3 + HOLD_MS ->
                Frame(message, 1f - (time - FADE_MS * 2 - HOLD_MS) / FADE_MS.toFloat())
            time < DURATION_MS -> Frame(null, (time - FADE_MS * 3 - HOLD_MS) / FADE_MS.toFloat())
            else -> Frame().also { requested = null }
        }
        return frame
    }

    fun clear() { requested = null; frame = Frame(); origin = frame }
}

/** Combines the row's existing morph alpha with feedback; neither writer can override the other. */
internal object CapsuleStatusContent {
    data class Layers(val wordsAlpha: Float, val artworkAlpha: Float, val message: Boolean)

    fun layers(frame: CapsuleTextFeedbackState.Frame, contentAlpha: Float): Layers {
        val alpha = frame.alpha.coerceIn(0f, 1f) * contentAlpha.coerceIn(0f, 1f)
        return Layers(alpha, if (frame.message == null) alpha else 0f, frame.message != null)
    }

    /** A round island cannot legibly fit the full sentence. Its accessible label stays whole. */
    fun compact(message: String): String = when (message) {
        "已进入导航界面" -> "导航\n已进入"
        "已退出导航界面" -> "导航\n已退出"
        "正在打开导航界面" -> "导航\n打开中"
        "内容尚未就绪" -> "尚未\n就绪"
        else -> message
    }
}

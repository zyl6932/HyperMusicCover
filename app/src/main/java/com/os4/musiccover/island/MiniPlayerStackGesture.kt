package com.os4.musiccover.island
import kotlin.math.abs
import com.os4.musiccover.CoverMorphMotion

/** Owns one visible motion; a new drag or a native handoff retires the previous release. */
internal class MiniPlayerSpreadMotion {
    var progress = 0f
    var spring: MiniPlayerSpreadSpring? = null
        private set

    fun takeOver() { spring = null }

    fun finish(toList: Boolean) {
        takeOver()
        progress = if (toList) 1f else 0f
    }

    fun release(toList: Boolean, speed: Float) {
        spring = MiniPlayerSpreadSpring(progress, speed, toList)
    }
}

/** The spread's release keeps the finger's speed, independent of OEM scroll jumps. */
internal class MiniPlayerSpreadSpring(progress: Float, speed: Float, toList: Boolean) {
    private val motion = CoverMorphMotion().apply {
        value = progress
        velocity = speed.coerceIn(-4f, 4f)
        aim(toList)
    }
    val atRest: Boolean get() = motion.atRest()
    fun step(seconds: Float): Float {
        motion.step(seconds.coerceIn(0f, 0.05f), 0.38f)
        return motion.value.coerceIn(-0.025f, 1.025f)
    }
}

/** Downward browsing has its own direction, so reversing a pull never opens the cards. */
internal object MiniPlayerStackGesture {
    fun openingProgress(pulled: Float, travel: Float): Float =
        (pulled / travel.coerceAtLeast(1f)).coerceIn(0f, 1f)

    fun starts(dx: Float, dy: Float): Boolean = dy > 0f && dy >= abs(dx)

    fun commits(dy: Float, velocityY: Float, density: Float, cancelled: Boolean): Boolean =
        !cancelled && dy > 0f &&
            (dy >= 50f * density || dy >= 8f * density && velocityY > 1200f * density)

    fun collapseProgress(startProgress: Float, dy: Float, range: Float): Float =
        (startProgress - dy / range.coerceAtLeast(1f)).coerceIn(0f, 1f)

    fun nextKey(keys: List<String>, selected: String?): String? {
        if (keys.size < 2) return null
        val at = keys.indexOf(selected).coerceAtLeast(0)
        return keys[(at + 1) % keys.size]
    }
}

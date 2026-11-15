package com.os4.musiccover.island

import android.view.View
import com.os4.musiccover.Xp

/** Only the temporary stacking hide belongs to the spring; removal and layout hiding do not. */
internal object SpringRowVisibilityPolicy {
    fun reveal(visibility: Int, stackingHidden: Boolean, layoutHidden: Boolean): Boolean =
        visibility == 4 && stackingHidden && !layoutHidden

    fun restore(stackingHidden: Boolean, layoutHidden: Boolean): Int =
        if (stackingHidden || layoutHidden) 4 else 0
}

internal class SpringRowVisibility(private val view: View) {
    private val injector = runCatching { Xp.callMethod(view, "getInjector") }.getOrNull()
    private val state = runCatching { Xp.callMethod(view, "getViewState") }.getOrNull()
    private var held = false

    private fun stackingHidden(): Boolean = runCatching {
        injector != null && Xp.getObjectField(injector, "shouldHideDueToStacking") == true
    }.getOrDefault(false)

    private fun layoutHidden(): Boolean = runCatching {
        state == null || Xp.getObjectField(state, "hidden") != false
    }.getOrDefault(true)

    /** Called after the native layout, before advancing the first visible spring frame. */
    fun apply(): Boolean {
        if (SpringRowVisibilityPolicy.reveal(view.visibility, stackingHidden(), layoutHidden())) {
            held = true
            view.visibility = View.VISIBLE
        }
        return view.visibility == View.VISIBLE
    }

    fun close() {
        if (held && view.visibility == View.VISIBLE) {
            view.visibility = SpringRowVisibilityPolicy.restore(stackingHidden(), layoutHidden())
        }
        held = false
    }
}

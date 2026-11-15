package com.os4.musiccover.island

import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs

/** Blur the notification templates, leaving HyperOS's wallpaper-sampling background alone. */
internal class NotificationContentBlur(private val row: View) {
    private var content = emptyList<View>()
    private var lastRadius = -1f

    fun apply(radius: Float) {
        if (content.isEmpty() || content.any { !it.isAttachedToWindow }) {
            clear()
            content = contentViews(row)
            lastRadius = -1f
        }
        if (content.isEmpty()) return
        // Callers compensate for row scale; radius is in this content layer's own pixels.
        val next = radius.coerceAtLeast(0f).let { if (it <= 2f) 0f else it }
        if (lastRadius >= 0f && abs(next - lastRadius) <= 2f && (next == 0f) == (lastRadius == 0f)) return
        lastRadius = next
        // DECAL avoids stretching text pixels into a rectangular fringe, as MiniCardMorph does.
        val effect = if (next == 0f) null else RenderEffect.createBlurEffect(next, next, Shader.TileMode.DECAL)
        content.forEach {
            runCatching { it.setRenderEffect(effect) }
            if (effect == null) owners.remove(it) else owners[it] = this
        }
    }

    fun clear() {
        content.forEach {
            if (owners[it] === this) {
                runCatching { it.setRenderEffect(null) }
                owners.remove(it)
            }
        }
        lastRadius = 0f
    }

    companion object {
        private val owners = java.util.WeakHashMap<View, NotificationContentBlur>()

        fun contentViews(root: View): List<View> {
            if (!root.javaClass.name.contains("ExpandableNotificationRow")) return emptyList()
            return NotificationBlurTargets.collect(root, { view ->
                val name = view.javaClass.name
                when {
                    view is ViewStub || name.contains("NotificationBackground") ||
                        name.contains("FakeShadow") || name.contains("NotificationGuts") ->
                        NotificationBlurTargets.Kind.EXCLUDED
                    name.contains("NotificationContentView") || name.contains("NotificationHeaderView") ||
                        view is ImageView || view is TextView -> NotificationBlurTargets.Kind.CONTENT
                    else -> NotificationBlurTargets.Kind.CONTAINER
                }
            }, { view -> if (view is ViewGroup) (0 until view.childCount).map(view::getChildAt) else emptyList() })
        }
    }
}

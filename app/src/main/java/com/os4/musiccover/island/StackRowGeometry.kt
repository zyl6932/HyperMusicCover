package com.os4.musiccover.island

import android.view.View
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Read-only OEM render metadata, resolved once per participating row. */
internal class StackRowGeometry(private val view: View) {
    private fun method(name: String): Method? = runCatching { view.javaClass.getMethod(name) }.getOrNull()
    private fun field(name: String): Field? {
        var type: Class<*>? = view.javaClass
        while (type != null) {
            val current = type
            val found = runCatching { current.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
            if (found != null) return found
            type = type.superclass
        }
        return null
    }
    private val actualHeight = method("getActualHeight")
    private val clipBottom = method("getClipBottomAmount")
    private val extClipBottom = method("getExtClipBottomAmount")
    private val minimumClipHeight = field("mMinimumHeightForClipping")
    private val superZ = method("getSuperTranslationZ")
    private val superAlpha = method("getSuperAlpha")
    private val superTransitionAlpha = method("getSuperTransitionAlpha")
    private val bgRadius = method("getBgRadius")

    private fun number(method: Method?, fallback: Float): Float =
        runCatching { (method?.invoke(view) as? Number)?.toFloat() }.getOrNull()
            ?.takeIf { it.isFinite() } ?: fallback

    fun height(): Float = number(actualHeight, view.height.toFloat()).coerceIn(0f, view.height.coerceAtLeast(0).toFloat())
    fun visibleBottom(): Float = StackOcclusion.visibleBottom(height(), number(clipBottom, 0f),
        number(extClipBottom, 0f), runCatching { (minimumClipHeight?.get(view) as? Number)?.toFloat() }
            .getOrNull() ?: 0f)
    fun z(): Float = view.elevation + number(superZ, view.translationZ)
    fun alpha(): Float = minOf(view.alpha, number(superAlpha, view.alpha)) *
        minOf(view.transitionAlpha, number(superTransitionAlpha, view.transitionAlpha))
    fun radius(): Float = number(bgRadius, 24f * view.resources.displayMetrics.density)

}

package com.os4.musiccover.island

import android.view.View
import com.os4.musiccover.Xp
import com.os4.musiccover.cover.CoverMorphMotion.Box

/** A render override with a separately tracked OEM destination, including writes during reversal. */
internal class RowRenderProperty(private val view: View, name: String) {
    private val get = view.javaClass.getMethod("getSuper$name")
    private val set = view.javaClass.getMethod("setSuper$name", Float::class.javaPrimitiveType)
    private val state = SpringRenderValue(current())
    private fun current() = (get.invoke(view) as Number).toFloat()
    fun read(): Float = state.read(current())
    fun write(value: Float) { set.invoke(view, value); state.written(value) }
    fun restore() { set.invoke(view, read()) }
}

/** Keep notification glass and content in the native stack throughout a spring morph. */
internal class NativeRowMorph(private val view: View) {
    private val x = RowRenderProperty(view, "TranslationX")
    private val y = RowRenderProperty(view, "TranslationY")
    private val sx = RowRenderProperty(view, "ScaleX")
    private val sy = RowRenderProperty(view, "ScaleY")
    private val alpha = RowRenderProperty(view, "Alpha")
    private val transitionAlpha = RowRenderProperty(view, "TransitionAlpha")
    private val xy = IntArray(2)
    private val state = Xp.callMethod(view, "getViewState")
    private val injector = Xp.getObjectField(state, "mInjector")
    private var written = false
    private val backgroundClip = NotificationMorphClip(view)
    private val visibility = SpringRowVisibility(view)

    fun clip(width: Float, height: Float, radius: Float) = backgroundClip.apply(width, height, radius)

    fun restBox(height: Float): Box? {
        val parent = view.parent as? View ?: return null
        parent.getLocationOnScreen(xy)
        val scaleX = sx.read()
        val scaleY = sy.read()
        return Box(xy[0] - parent.scrollX + view.left + x.read() + view.pivotX * (1f - scaleX),
            xy[1] - parent.scrollY + view.top + y.read() + view.pivotY * (1f - scaleY),
            view.width * scaleX, height * scaleY)
    }

    fun place(box: Box, scale: Float, opacity: Float) {
        val parent = view.parent as? View ?: return
        visibility.apply()
        // Read before writing, so a late OEM update becomes the destination, never our pose.
        x.read(); y.read(); sx.read(); sy.read(); alpha.read(); transitionAlpha.read()
        parent.getLocationOnScreen(xy)
        written = true
        sx.write(scale)
        sy.write(scale)
        x.write(box.x - (xy[0] - parent.scrollX + view.left) - view.pivotX * (1f - scale))
        y.write(box.y - (xy[1] - parent.scrollY + view.top) - view.pivotY * (1f - scale))
        // The aggregate island's row can still have numStateAlpha=0 during the whole flight.
        // Bypass only that factor, just as SpringSpreadRows does for ordinary notifications.
        val folme = (Xp.getObjectField(injector, "folmeAlpha") as Number).toFloat()
        val ext = (Xp.callMethod(view, "getExtTransitionAlpha") as Number).toFloat()
        alpha.write(view.alpha * folme)
        transitionAlpha.write(view.transitionAlpha * ext * opacity.coerceIn(0f, 1f))
    }

    fun close() {
        backgroundClip.close()
        if (!written) { visibility.close(); return }
        for (property in listOf(x, y, sx, sy, alpha, transitionAlpha)) runCatching { property.restore() }
        runCatching { Xp.callMethod(view, "onPropertiesUpdate") }
        visibility.close()
        written = false
    }
}

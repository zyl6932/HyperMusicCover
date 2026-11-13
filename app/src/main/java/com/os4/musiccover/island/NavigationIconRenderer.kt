package com.os4.musiccover.island

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.os4.musiccover.Xp

/** Works on a private raster, after any tint inherited from the notification drawable. */
internal object NavigationIconRenderer {
    private val reported = LinkedHashSet<String>()

    fun prepare(source: Drawable?, resources: Resources, dark: Boolean, from: String): Drawable? {
        if (source == null) return null
        return runCatching {
            val side = 128
            val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val drawable = source.constantState?.newDrawable(resources)?.mutate() ?: source
            val old = drawable.copyBounds()
            try {
                val w = drawable.intrinsicWidth.coerceAtLeast(1)
                val h = drawable.intrinsicHeight.coerceAtLeast(1)
                val scale = side.toFloat() / maxOf(w, h)
                val dw = (w * scale).toInt().coerceAtLeast(1)
                val dh = (h * scale).toInt().coerceAtLeast(1)
                drawable.setBounds((side - dw) / 2, (side - dh) / 2, (side + dw) / 2, (side + dh) / 2)
                drawable.draw(Canvas(bitmap))
            } finally { drawable.bounds = old }
            val pixels = IntArray(side * side)
            bitmap.getPixels(pixels, 0, side, 0, 0, side, side)
            val mono = NavigationGlyph.monochrome(pixels)
            val ink = if (dark) android.graphics.Color.WHITE else 0xff1a1a1a.toInt()
            report("extract=$from class=${source.javaClass.simpleName} filter=${source.colorFilter?.javaClass?.simpleName} " +
                "mono=$mono dark=$dark ink=${Integer.toHexString(ink)}")
            if (mono) {
                bitmap.setPixels(NavigationGlyph.recolor(pixels, ink), 0, side, 0, 0, side, side)
                BitmapDrawable(resources, bitmap)
            } else {
                bitmap.recycle()
                source
            }
        }.getOrElse {
            report("extract failed: ${it.javaClass.simpleName}")
            source
        }
    }

    /** Bounded, change-only diagnostics; no notification text, song or location data. */
    fun report(message: String) {
        if (!reported.add(message)) return
        if (reported.size > 32) reported.remove(reported.first())
        Xp.log("MCNavIcon: $message")
    }

    fun finalView(view: android.widget.ImageView, place: String) {
        report("final=$place drawable=${view.drawable?.javaClass?.simpleName} " +
            "tint=${view.imageTintList} filter=${view.colorFilter?.javaClass?.simpleName} " +
            "forceDark=${view.isForceDarkAllowed}")
    }
}

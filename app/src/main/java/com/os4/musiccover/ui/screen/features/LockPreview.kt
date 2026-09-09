package com.os4.musiccover.ui.screen.features

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.os4.musiccover.ModuleBridge
import com.os4.musiccover.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A live picture of the lock screen, to scale, drawn from the same numbers the sliders are
 * sending to the module. It exists so the values on this page can be judged by looking rather
 * than by locking the phone after every drag.
 *
 * The media card, the two shortcut buttons and the clock are not drawn here at all: they are
 * pictures of the real views, captured in SystemUI and painted at the coordinates they occupy
 * on the phone. An earlier version drew its own diagram of the card out of rounded rectangles
 * and it fooled nobody - the text, the seek bar, the buttons and the thumbnail were all
 * invented. Anything that is genuinely SystemUI's gets asked for rather than imitated.
 *
 * The clock is captured UNSCALED and scaled here, so dragging the size slider moves it at once
 * instead of waiting for the next picture. Its glyphs are the OEM's variable font, which this
 * process cannot load - but TimeView strokes them as a Path, and a Path draws into a software
 * canvas like anything else, so they survive the capture. What does not survive is the
 * refracting glass, a RuntimeShader: the digits come back filled as they are at that instant.
 *
 * Two things are still not what the phone draws:
 *
 * - **The blur is faked.** The module's wallpaper blur is a multi-pass affair sized for a
 *   1200x2608 screen. At the size of a thumbnail nothing of it would survive, so the preview
 *   runs a cheap one, and the card's frosting is rebuilt from it.
 * - **The card has no bright rim.** On the phone that comes from MIUI's blur material in the
 *   compositor, not from any drawable SystemUI holds - see the handoff notes.
 *
 * Everything else - the mirrored extension, the vertical position, the feathered seams, where
 * the media card sits, how big the collapsed clock is - follows the phone.
 */
@Composable
fun LockPreview(
    art: Bitmap?,
    bias: Float,
    clockScale: Float,
    glassEnd: Float,
    geometry: ModuleBridge.Geometry,
    card: ModuleBridge.Shot?,
    cardRadius: Float,
    artSlot: ModuleBridge.ArtSlot?,
    cardHideArt: Boolean,
    cardCenterText: Boolean,
    clockHour: Bitmap?,
    clockMinute: Bitmap?,
    date: ModuleBridge.Shot?,
    leftShortcut: ModuleBridge.Shot?,
    rightShortcut: ModuleBridge.Shot?,
    modifier: Modifier = Modifier,
) {
    val screenW = if (geometry.hasScreen) geometry.screenW else FALLBACK_SCREEN_W
    val screenH = if (geometry.hasScreen) geometry.screenH else FALLBACK_SCREEN_H

    // With nothing playing there is no artwork and no media card, and the preview was an empty
    // grey shell that read as broken. These stand in for them: a real cover and a real capture
    // of the card, lifted off a phone, so the page always shows what the lock screen is going to
    // look like. Everything else - the clock, the date, the shortcuts - is live either way.
    val resources = LocalContext.current.resources
    val sampleCover = remember { BitmapFactory.decodeResource(resources, R.drawable.sample_cover) }
    // A photograph cannot restyle itself, so the card was photographed in all four states the
    // two switches can put it in and the switches pick one. Faking it does not work: hiding the
    // thumbnail leaves the OEM's own placeholder note behind in the picture (the real card hides
    // the whole artwork box, note and all), and centring the title moves a glyph run this side
    // has no way to redraw.
    val sampleCardRes = when {
        cardHideArt && cardCenterText -> R.drawable.sample_card_no_art_centered
        cardHideArt -> R.drawable.sample_card_no_art
        cardCenterText -> R.drawable.sample_card_centered
        else -> R.drawable.sample_card
    }
    val sampleCard = remember(sampleCardRes) {
        BitmapFactory.decodeResource(resources, sampleCardRes)
    }
    val cover = art ?: sampleCover
    val shownCard = card ?: sampleShot(sampleCard, geometry, screenW, screenH)
    val shownSlot = if (card != null) artSlot else if (cardHideArt) null else SAMPLE_ART_SLOT

    // Recomposing on every pixel of a drag would rebuild the bitmap as fast as the finger moves,
    // for differences below what a thumbnail can show. A step of 1/200 of the screen height is
    // still finer than one pixel of the preview.
    val biasStep = (bias * 200f).roundToInt()
    val layers = remember(cover, biasStep, screenW, screenH) {
        val w = PREVIEW_PX
        val h = (w * screenH / screenW.toFloat()).roundToInt()
        val paper = composePreview(cover, w, h, biasStep / 200f, w / screenW.toFloat())
        // The card's own background is a hardware blur of whatever is behind it, and a hardware
        // blur draws nothing into the software canvas the module captures with - the captured
        // card comes back as text and buttons on transparency. So the frosting is rebuilt here,
        // out of the wallpaper this page just composed, which is the same picture the phone
        // blurs. Without it a cover positioned over the card would show through sharp.
        val frost = boxBlur(
            Bitmap.createScaledBitmap(paper, max(1, w / 3), max(1, h / 3), true), 3, 2
        )
        paper.asImageBitmap() to frost.asImageBitmap()
    }
    val wallpaper = layers.first
    val frosted = layers.second

    Box(
        modifier = modifier
            .fillMaxWidth(PREVIEW_WIDTH_FRACTION)
            .aspectRatio(screenW / screenH.toFloat())
            .clip(RoundedCornerShape(PREVIEW_CORNER))
            .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(PREVIEW_CORNER))
    ) {
        ComposeCanvas(Modifier.fillMaxSize()) {
            val k = size.width / screenW           // screen pixels -> preview pixels
            drawImage(
                image = wallpaper,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.High,
            )
            // Under the clock, as on the phone: the card is part of the notification area and
            // the collapsed clock sits above it, but a tall cover can bring them close.
            drawFrostedPanel(shownCard, k, frosted,
                if (cardRadius > 0f) cardRadius else 20.dp.toPx())
            // Before the card, so the badge the OEM draws over the artwork's corner - which did
            // survive the capture - still lands on top of it.
            drawCardArt(shownCard, shownSlot, cover, k)
            drawShot(shownCard, k)
            drawShot(leftShortcut, k)
            drawShot(rightShortcut, k)
            // The date is a sibling of the clock, not part of it: the phone scales the clock
            // group alone, so the date keeps its size whatever the size slider says.
            drawShot(date, k)
            if (clockHour == null && clockMinute == null) {
                drawClockPlaceholder(k, geometry, clockScale, glassEnd)
            } else {
                drawClock(k, geometry, clockScale, clockHour, clockMinute)
            }
        }
    }
}

/**
 * The clock, captured unscaled and laid out here exactly as placeCollapsedClock() lays it out on
 * the phone: centred on the screen, the glyphs' top pinned to clockY, and the whole thing scaled
 * about that top - which is why one y serves every scale. The pad the capture was cropped with
 * sits above the glyph top, so it scales off it too.
 *
 * Both trees are painted into the same rectangle. They were cropped to the same pooled glyph
 * box, so the hour lands where the hour goes and the minute where the minute goes, including
 * the stacked clock styles where one sits above the other - and the classic style, where only
 * one tree draws a clock at all and the other capture is simply absent.
 */
private fun DrawScope.drawClock(
    k: Float,
    geometry: ModuleBridge.Geometry,
    clockScale: Float,
    hour: Bitmap?,
    minute: Bitmap?,
) {
    if (!geometry.hasClock) return
    val w = geometry.clockW * clockScale * k
    val h = geometry.clockH * clockScale * k
    // The phone's own transform, repeated: scale about (clockPivotX, clockY). Reproducing it
    // rather than centring means a left-aligned clock style stays left-aligned here too.
    val left = (geometry.clockPivotX
            + (geometry.clockX - geometry.clockPivotX) * clockScale) * k
    val top = (geometry.clockY - geometry.clockPad * clockScale) * k
    val offset = IntOffset(left.roundToInt(), top.roundToInt())
    val dst = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1))
    for (b in listOf(hour, minute)) {
        if (b == null) continue
        drawImage(
            image = b.asImageBitmap(),
            dstOffset = offset,
            dstSize = dst,
            filterQuality = FilterQuality.High,
        )
    }
}

/**
 * The collapsed clock, as the space it takes up.
 *
 * The module reports the glyph box at scale 1 and the screen y its top is pinned to; both come
 * from placeCollapsedClock()'s own rule - pivot on the glyph top, so the top does not move with
 * the scale - which is why one y covers every scale. Without a report (no module, or a lock
 * screen that has not been measured yet) it falls back to what this device measured, which is
 * at least the right order of magnitude for judging the slider.
 */
private fun DrawScope.drawClockPlaceholder(
    k: Float,
    geometry: ModuleBridge.Geometry,
    clockScale: Float,
    glassEnd: Float,
) {
    val boxW = (if (geometry.hasClock) geometry.clockW else FALLBACK_CLOCK_W) * clockScale * k
    val boxH = (if (geometry.hasClock) geometry.clockH else FALLBACK_CLOCK_H) * clockScale * k
    val top = (if (geometry.clockY > 0f) geometry.clockY else FALLBACK_CLOCK_Y) * k
    if (boxW <= 0f || boxH <= 0f) return
    val left = if (geometry.hasClock && geometry.clockPivotX > 0f) {
        (geometry.clockPivotX + (geometry.clockX - geometry.clockPivotX) * clockScale) * k
    } else {
        (size.width - boxW) / 2f
    }
    val corner = CornerRadius(min(boxW, boxH) * 0.14f)

    // Glass strength is the one clock value with nothing geometric to show, so it is shown as
    // how solid the box is - which is what updateGlassValue() actually does to the digits:
    // 0 is transparent refracting glass, 1 is a solid fill.
    drawRoundRect(
        color = Color.White.copy(alpha = 0.05f + 0.45f * glassEnd.coerceIn(0f, 1f)),
        topLeft = Offset(left, top),
        size = Size(boxW, boxH),
        cornerRadius = corner,
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.85f),
        topLeft = Offset(left, top),
        size = Size(boxW, boxH),
        cornerRadius = corner,
        style = Stroke(
            width = 1.dp.toPx(),
            // Dashed, so it reads as "this much room" and not as a drawn clock face.
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(3.dp.toPx(), 2.5f.dp.toPx())
            ),
        ),
    )
}

/**
 * The card's frosted background, in place of the one that could not be captured.
 *
 * The radius is the OEM's own `media_control_bg_radius`, 20dp - passed in rather than read here
 * so this stays a pure drawing function. Density is the same on both sides of the probe (one
 * display, one device), so dp measured in this process is dp on the lock screen.
 */
private fun DrawScope.drawFrostedPanel(
    shot: ModuleBridge.Shot?,
    k: Float,
    frosted: ImageBitmap,
    radiusPx: Float,
) {
    if (shot == null) return
    val rect = Rect(shot.l * k, shot.t * k, (shot.l + shot.w) * k, (shot.t + shot.h) * k)
    val path = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radiusPx * k))) }
    clipPath(path) {
        drawImage(
            image = frosted,
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.High,
        )
        // Frosted glass lightens what is behind it; without this the panel is invisible against
        // an evenly coloured cover, and the captured text appears to float on the wallpaper.
        drawRect(Color.White.copy(alpha = 0.12f))
    }
}

/**
 * The album art, in the hole the capture left for it, clipped to the radius the card clips with.
 */
private fun DrawScope.drawCardArt(
    card: ModuleBridge.Shot?,
    slot: ModuleBridge.ArtSlot?,
    art: Bitmap?,
    k: Float,
) {
    if (card == null || slot == null || art == null) return
    // The slot is in fractions of the card, so it follows wherever the card is drawn and
    // whatever size the card happened to be when its picture was taken.
    val l = (card.l + slot.l * card.w) * k
    val t = (card.t + slot.t * card.h) * k
    val w = slot.w * card.w * k
    val h = slot.h * card.h * k
    val path = Path().apply {
        addRoundRect(RoundRect(Rect(l, t, l + w, t + h), CornerRadius(slot.radius * card.w * k)))
    }
    clipPath(path) {
        drawImage(
            image = art.asImageBitmap(),
            dstOffset = IntOffset(l.roundToInt(), t.roundToInt()),
            dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.High,
        )
    }
}

/**
 * One captured SystemUI view, painted where it lives on the phone.
 *
 * Nothing here interprets what is in the picture - the module drew the real view, including
 * whatever the two card settings did to it - so this is only the mapping from screen pixels to
 * preview pixels.
 */
private fun DrawScope.drawShot(shot: ModuleBridge.Shot?, k: Float) {
    if (shot == null) return
    drawImage(
        image = shot.bitmap.asImageBitmap(),
        dstOffset = IntOffset((shot.l * k).roundToInt(), (shot.t * k).roundToInt()),
        dstSize = IntSize(
            (shot.w * k).roundToInt().coerceAtLeast(1),
            (shot.h * k).roundToInt().coerceAtLeast(1),
        ),
        filterQuality = FilterQuality.High,
    )
}

// ---------------------------------------------------------------------------------------------

/**
 * The cover layout, drawn small.
 *
 * This is Main.composeWallpaper() written a second time, and that is a real cost: one layout
 * with two implementations can drift, and if it does, the phone is right and this is wrong. It
 * is worth it because the alternative - having SystemUI compose a full screen and ship the
 * result back for every frame of a slider drag - cannot keep up with a finger.
 *
 * Only the layout is copied. The blur is a cheap box blur instead of the module's progressive
 * one, because at this size the difference is smaller than a pixel.
 */
private fun composePreview(src: Bitmap, w: Int, h: Int, bias: Float, scale: Float): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = Canvas(out)
    val p = Paint(Paint.FILTER_BITMAP_FLAG)

    val coverH = src.height * (w / src.width.toFloat())
    val top = (h - coverH) * bias.coerceIn(0f, 1f)

    // The artwork extended by mirroring itself above and below, so the rows either side of each
    // seam are the same row and the join is continuous before anything is blurred.
    val bw = max(1, w / 4)
    val bh = max(1, h / 4)
    val bk = bw / w.toFloat()
    val cH = coverH * bk
    val tp = top * bk
    val bg = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
    val bc = Canvas(bg)
    bc.drawBitmap(src, null, RectF(0f, tp, bw.toFloat(), tp + cH), p)
    bc.save()
    bc.translate(0f, tp)
    bc.scale(1f, -1f)
    bc.drawBitmap(src, null, RectF(0f, 0f, bw.toFloat(), cH), p)
    bc.restore()
    bc.save()
    bc.translate(0f, tp + cH)
    bc.scale(1f, -1f)
    bc.drawBitmap(src, null, RectF(0f, -cH, bw.toFloat(), 0f), p)
    bc.restore()

    cv.drawBitmap(boxBlur(bg, 2, 3), null, RectF(0f, 0f, w.toFloat(), h.toFloat()), p)
    cv.drawColor(0x14000000)

    // The sharp band, with its two edges faded into the blur. The module's feather is 240 screen
    // pixels, so it has to be scaled down with everything else.
    val feather = min(240f * scale, coverH / 4f)
    val layer = cv.saveLayer(0f, top, w.toFloat(), top + coverH, null)
    cv.drawBitmap(src, null, RectF(0f, top, w.toFloat(), top + coverH), p)
    val mask = Paint()
    mask.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    mask.shader = LinearGradient(
        0f, top, 0f, top + feather, 0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP
    )
    cv.drawRect(0f, top, w.toFloat(), top + feather, mask)
    mask.shader = LinearGradient(
        0f, top + coverH - feather, 0f, top + coverH,
        0xFF000000.toInt(), 0x00000000, Shader.TileMode.CLAMP
    )
    cv.drawRect(0f, top + coverH - feather, w.toFloat(), top + coverH, mask)
    cv.restoreToCount(layer)
    bg.recycle()
    return out
}

/** A separable box blur, in place of the module's progressive one. Small bitmaps only. */
private fun boxBlur(src: Bitmap, radius: Int, passes: Int): Bitmap {
    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    val tmp = IntArray(w * h)
    repeat(passes) {
        blurAxis(px, tmp, w, h, radius, true)
        blurAxis(tmp, px, w, h, radius, false)
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

private fun blurAxis(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val outer = if (horizontal) h else w
    val inner = if (horizontal) w else h
    for (o in 0 until outer) {
        for (i in 0 until inner) {
            var a = 0
            var red = 0
            var g = 0
            var b = 0
            var n = 0
            for (d in -r..r) {
                val j = i + d
                if (j < 0 || j >= inner) continue
                val c = if (horizontal) src[o * w + j] else src[j * w + o]
                a += (c ushr 24) and 0xFF
                red += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
                n++
            }
            val c = ((a / n) shl 24) or ((red / n) shl 16) or ((g / n) shl 8) or (b / n)
            if (horizontal) dst[o * w + i] = c else dst[i * w + o] = c
        }
    }
}

/**
 * Where the sample card goes.
 *
 * The module measures the real card on the lock screen and keeps that rectangle in its state
 * file, so it can say where the card belongs long after the music stopped - and it is worth
 * asking, because the answer depends on the clock style and on how far down the notification
 * area starts. The fractions below are only for a phone that has never had a card measured:
 * placing the sample by them on a phone the module HAS measured is what put it too high.
 */
private fun sampleShot(
    bitmap: Bitmap?,
    geometry: ModuleBridge.Geometry,
    screenW: Int,
    screenH: Int,
): ModuleBridge.Shot? {
    if (bitmap == null) return null
    if (geometry.hasCard) {
        return ModuleBridge.Shot(
            bitmap = bitmap,
            l = geometry.cardL,
            t = geometry.cardT,
            w = geometry.cardW,
            h = geometry.cardH,
        )
    }
    return ModuleBridge.Shot(
        bitmap = bitmap,
        l = (screenW * SAMPLE_CARD_L).roundToInt(),
        t = (screenH * SAMPLE_CARD_T).roundToInt(),
        w = (screenW * SAMPLE_CARD_W).roundToInt(),
        h = (screenH * SAMPLE_CARD_H).roundToInt(),
    )
}

/**
 * Wide enough to read the layout at arm's length, narrow enough to leave the controls under it
 * about four rows of screen. KernelSU's colour-palette preview uses 0.42 of the width, but its
 * preview scrolls away and ours must not.
 */
private const val PREVIEW_WIDTH_FRACTION = 0.38f
private val PREVIEW_CORNER = 20.dp

/**
 * The wallpaper is composed at this width and stretched to fit. Roughly a preview's worth of
 * real pixels on a 3x screen, which is enough that the sharp band's edge is not visibly
 * resampled, and small enough that a slider drag recomposes it without effort.
 */
private const val PREVIEW_PX = 180

// The sample card's rectangle, as fractions of a 1200x2608 screen: it was measured at
// 42,1700 1116x557, on the lock screen, with the module's own settling rule applied. The
// artwork inside it sits at 48,48 158x158 of the card's own 1116x557, clipped with a 30px
// radius - the numbers putArtSlot() reports for a live one.
private const val SAMPLE_CARD_L = 42f / 1200f
private const val SAMPLE_CARD_T = 1700f / 2608f
private const val SAMPLE_CARD_W = 1116f / 1200f
private const val SAMPLE_CARD_H = 557f / 2608f
private val SAMPLE_ART_SLOT = ModuleBridge.ArtSlot(
    l = 48f / 1116f, t = 48f / 557f, w = 158f / 1116f, h = 158f / 557f, radius = 30f / 1116f
)

// Fallbacks, all measured on the device this was built on (1200x2608 at 480dpi). They only
// apply when the module has not answered - it reports its own, and its numbers win.
private const val FALLBACK_SCREEN_W = 1200
private const val FALLBACK_SCREEN_H = 2608
private const val FALLBACK_CLOCK_W = 506f
private const val FALLBACK_CLOCK_H = 336f
private const val FALLBACK_CLOCK_Y = 334f

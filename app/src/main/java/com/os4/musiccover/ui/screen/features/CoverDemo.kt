package com.os4.musiccover.ui.screen.features

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.os4.musiccover.ui.util.isInDarkTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

/**
 * What cover mode is, in three short plays at the top of its page - in place of the live
 * composite that stood there (LockPreview, which captured the real clock, card and artwork) - the
 * same three-page frame as the islands' (DemoPager): the cover coming out of the pill to take the
 * lock screen and going back into it, the lyrics on it and the two-finger tap between them, and the full-screen
 * AOD keeping both.
 *
 * Drawn as the 趣味拟物 haptics cards are (see SkeuoKit): the lock screen in wireframe, a blue dot
 * for the finger, the camera closing in on whatever is happening. The motion is the module's: the
 * artwork's flight is CoverMorphMotion's bowed path on its spring, and it only flies where the
 * phone flies it - see playTakeOver.
 *
 * Still a preview of this page's settings, the three that change what the phone shows: the
 * cover's style (the whole screen, or a square between the clock and the card), where a
 * full-screen cover sits, and how small the clock goes. They are read every frame, so a slider
 * moves the picture while it plays.
 */
@Composable
fun CoverDemo(coverStyle: Int, bias: Float, clockSize: Float, modifier: Modifier = Modifier) {
    val look by rememberUpdatedState(Look(coverStyle, bias.coerceIn(0f, 1f), clockSize))
    DemoPager(COVER_PAGES, modifier) { page, playing, done ->
        val pal = skeuoPalette(isInDarkTheme())
        val measurer = rememberTextMeasurer()
        val clockSp = with(LocalDensity.current) { CLOCK_UNITS.toSp() }
        val scene = remember(page) { CoverScene() }
        LaunchedEffect(playing) {
            scene.reset(page)
            if (!playing) return@LaunchedEffect
            when (page) {
                0 -> scene.playTakeOver()
                1 -> scene.playLyrics()
                else -> scene.playAod()
            }
            done()
        }
        Canvas(Modifier.fillMaxWidth().height(200.dp).clipToBounds()) {
            drawCover(scene, look, pal, measurer, clockSp)
        }
    }
}

/*
 * What each page says is what the module does, and no more:
 * - the cover follows the media card, not the play button (Main.onMediaUpdate): the pill tapped
 *   opens into the card and the cover comes with it (Main.miniPlayerEnterCover); the card swiped
 *   down goes back into the pill and the cover goes with it;
 * - the two-finger tap is LockLyrics.toggleByTap - lyrics and cover, swapped;
 * - the AOD keeps the cover's small clock and the lyrics only in the full-screen AOD
 *   (ClockCollapse.aodHeld), which is an OEM setting, so the page says so.
 */
private val COVER_PAGES = listOf(
    DemoText("封面接管锁屏", "点按胶囊展开成媒体卡片，专辑封面随之接管锁屏，时钟缩小让出位置；卡片下滑收回胶囊，封面退出。"),
    DemoText("锁屏歌词", "有歌词的歌曲在封面上逐字唱出。双指单击锁屏，在歌词和封面之间切换。"),
    DemoText("息屏保持", "开启全屏息屏显示时，息屏后仍保留封面的小时钟和歌词，亮屏接着唱。"),
)

private class Look(val style: Int, val bias: Float, val clockSize: Float)

// ---- the phone's lock screen, in SkeuoKit's units

private const val PW = PHONE_W
private const val PH = PHONE_H
private const val CLOCK_UNITS = 25f
private const val CLOCK_TOP = 30f
private const val CLOCK_TOP_SMALL = 17f
private const val RY = 196f
private const val DISC_D = 13f

private class Rect2(val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx get() = x + w / 2f
    val cy get() = y + h / 2f
    val right get() = x + w
    val bottom get() = y + h
}

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun lerpBox(a: Rect2, b: Rect2, t: Float) =
    Rect2(lerpF(a.x, b.x, t), lerpF(a.y, b.y, t), lerpF(a.w, b.w, t), lerpF(a.h, b.h, t))
private fun ramp(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private val CARD = Rect2(7f, 130f, 86f, 44f)
private val THUMB = Rect2(CARD.x + 5f, CARD.y + 5f, 15f, 15f)
private val PLAY = Offset(CARD.right - 11f, CARD.y + 12.5f)
/** The pill between the two shortcuts, and its artwork: a circle, as MiniPlayerView draws it. */
private val PILL = Rect2(23f, RY - 6.5f, 54f, 13f)
private val SQUARE = Rect2(15f, 46f, 70f, 70f)
/** The power key, the upper of the two SkeuoKit draws on the right. */
private val POWER = Offset(PW + 1.2f, 50f)

/** Where a full-screen cover's artwork sits: a square across the screen, where the slider puts it. */
private fun fullArt(bias: Float) = Rect2(0f, bias * (PH - PW), PW, PW)

/** The lyric band: between the small clock's date and the card. */
private const val BAND_TOP = 44f
private const val BAND_BOTTOM = 124f
private const val LINE_GAP = 11f
private val LYRIC_WIDTHS = floatArrayOf(0.72f, 0.58f, 0.8f, 0.5f, 0.66f, 0.74f, 0.44f, 0.62f, 0.7f, 0.52f)

/** The cover: a soft blue that is the finger's family, so the page still has one colour. */
private val ART_TOP = Color(0xFF86B4FF)
private val ART_BOTTOM = Color(0xFFBCA7FF)
private val ART_SUN = Color(0xFFFFFFFF)
private val ART_HILL = Color(0xFF5E86F0)

// ---- motion

private fun jelly(response: Float, damping: Float): AnimationSpec<Float> =
    spring(dampingRatio = damping, stiffness = (2.0 * PI / response).pow(2).toFloat(), visibilityThreshold = 0.0005f)

/** The clock's collapse and the wallpaper's crossfade: Main.EASE_COVER[1], 0.38s. */
private val COVER = jelly(0.38f, 0.88f)
/** The artwork's flight: CoverMorphMotion.step, the same response at damping 0.80. */
private val FLIGHT = jelly(0.38f, 0.8f)
/** The pill and the card becoming each other: the card morph's spring (MiniCardMorph). */
private val MORPH = jelly(0.38f, 0.86f)
/** A lyric line handing over to the next, AMLL's scroll spring. */
private val SCROLL = jelly(0.45f, 0.9f)
private val FILL = CubicBezierEasing(0.3f, 0f, 0.7f, 1f)
private val DRAG = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)

private class CoverScene {
    val cam = DemoCamera()
    val finger = Finger()
    /** The second finger of a two-finger tap. */
    val finger2 = Finger()
    val burst = Burst(seed = 7, count = 34)
    /** The media controls as the pill (0) or the card (1), and everything between. */
    val media = Animatable(1f)
    /** The pill's press, 0..1: it gives by 5%. */
    val press = Animatable(0f)
    /** 0 the ordinary lock screen, 1 the cover's: the clock's collapse and the wallpaper's fade. */
    val cover = Animatable(0f)
    /** The artwork's flight between its small place and the cover's, along the bowed path. */
    val fly = Animatable(0f)
    /** Where the flight's small end is: the card's thumbnail, or the pill's circle. */
    var fromPill by mutableStateOf(false)
    /** The full-screen flying copy's opacity: it hands over to the wallpaper and goes. */
    val flyAlpha = Animatable(1f)
    /** The lyric page over the cover. */
    val lyrics = Animatable(0f)
    /** Which line is sung, as a float so the band scrolls between them. */
    val focus = Animatable(0f)
    /** How far along the sung line its fill has got. */
    val fill = Animatable(0f)
    /** The full-screen AOD: 0 lit, 1 dozing. */
    val aod = Animatable(0f)
    /** The line being sung; the ones before it are dimmer. */
    var sung by mutableIntStateOf(0)

    /** Each page starts where the one before it ended, so the three read as one story. */
    suspend fun reset(page: Int) {
        cam.reset()
        finger.reset(); finger2.reset()
        burst.t.snapTo(1f)
        media.snapTo(if (page == 0) 0f else 1f)
        press.snapTo(0f)
        cover.snapTo(if (page == 0) 0f else 1f)
        fly.snapTo(if (page == 0) 0f else 1f)
        fromPill = page == 0
        // No page flies a full-screen copy: the pill's flight is a card-mode one (playTakeOver).
        flyAlpha.snapTo(0f)
        lyrics.snapTo(if (page == 2) 1f else 0f)
        focus.snapTo(if (page == 2) 1f else 0f)
        sung = if (page == 2) 1 else 0
        fill.snapTo(0f)
        aod.snapTo(0f)
    }

    private suspend fun sing(lines: Int, fillMs: Int = 900) {
        repeat(lines) {
            fill.snapTo(0f)
            fill.animateTo(1f, tween(fillMs, easing = FILL))
            sung += 1
            focus.animateTo(sung.toFloat(), SCROLL)
        }
    }

    /** Both fingers down together a finger's width apart. */
    private suspend fun twoFingerTap(x: Float, y: Float) {
        coroutineScope {
            launch { finger.arrive(x - 7f, y, fromDx = -24f, fromDy = 28f) }
            launch { finger2.arrive(x + 7f, y, fromDx = 24f, fromDy = 28f) }
        }
        coroutineScope {
            launch { finger.press(110) }
            launch { finger2.press(110) }
        }
    }

    private suspend fun twoFingerLift() = coroutineScope {
        launch { finger.lift() }
        launch { finger2.lift() }
    }

    /**
     * Page one, the pill first (user, 2026-09-30). Tapped, it opens into the card and the artwork
     * flies straight out of its circle into the cover (CoverMorphLayer.beginMiniScene) as the
     * clock collapses; the card swiped down goes back into the pill, the square back into its
     * circle, the cover out.
     *
     * Only where the phone flies it. The pill's flight is a card-mode one: with the cover
     * full-screen, beginMiniScene declines and the wallpaper's own crossfade is the whole entry,
     * so there it is shown that way - drawFlight shows no full-screen copy unless flyAlpha asks.
     */
    suspend fun playTakeOver() {
        delay(300)
        cam.focus(50f, 184f, 2.2f, 600)
        finger.arrive(PILL.cx, PILL.cy, fromDx = 26f, fromDy = -18f)
        finger.press(110)
        press.animateTo(1f, tween(110))
        // Out of it, all at once, the camera pulling back to take the flight in.
        coroutineScope {
            launch { finger.lift() }
            launch { press.animateTo(0f, MORPH) }
            launch { media.animateTo(1f, MORPH) }
            launch { cover.animateTo(1f, COVER) }
            launch { fly.animateTo(1f, FLIGHT) }
            launch { cam.wide(700) }
            launch { delay(300); burst.fire(1000) }
        }
        delay(800)
        // The card pulled back down into the pill, in the whole view: a second close-up straight
        // after the pull-back would make the page go in and out twice.
        finger.arrive(CARD.cx, CARD.cy, fromDx = 28f, fromDy = -22f)
        finger.press()
        finger.slide(CARD.cx, CARD.cy + 16f, 340) { media.animateTo(0.76f, tween(340, easing = DRAG)) }
        coroutineScope {
            launch { finger.lift(ripple = false) }
            launch { media.animateTo(0f, MORPH) }
            launch { cover.animateTo(0f, COVER) }
            launch { fly.animateTo(0f, FLIGHT) }
        }
        delay(1000)
    }

    /** Page two: the lyrics sing, a two-finger tap hands the cover back, another brings them. */
    suspend fun playLyrics() {
        delay(300)
        lyrics.animateTo(1f, tween(420))
        sing(1)
        cam.focus(50f, 88f, 1.9f, 600)
        sing(1)
        twoFingerTap(PW / 2f, 84f)
        coroutineScope {
            launch { twoFingerLift() }
            launch { lyrics.animateTo(0f, tween(300)) }
        }
        delay(800)
        twoFingerTap(PW / 2f, 84f)
        coroutineScope {
            launch { twoFingerLift() }
            launch { lyrics.animateTo(1f, tween(380)) }
        }
        sing(1)
        cam.wide(650)
        delay(300)
    }

    /**
     * Page three: the power key, the doze keeping the clock and the words, a tap back. The whole
     * phone stays in view throughout - what the doze keeps is the point, and it is all of it.
     */
    suspend fun playAod() {
        delay(500)
        finger.arrive(POWER.x, POWER.y, fromDx = 24f, fromDy = -20f)
        finger.press(110)
        coroutineScope {
            launch { finger.lift(ripple = false) }
            launch { aod.animateTo(1f, tween(520, easing = DRAG)) }
        }
        // Still singing in the doze, more slowly lit, as the still AOD draws it.
        sing(1, fillMs = 1300)
        delay(300)
        finger.arrive(PW / 2f, 150f, fromDx = 30f, fromDy = 26f)
        finger.press(110)
        coroutineScope {
            launch { finger.lift() }
            launch { aod.animateTo(0f, tween(360)) }
        }
        sing(1)
        delay(400)
    }
}

// ---- drawing

/** CoverMorphMotion.frame: the bowed path, the centre stopping at the target, the size overshooting. */
private fun flightBox(small: Rect2, big: Rect2, progress: Float): Rect2 {
    val p = progress.coerceIn(0f, 1f)
    val dx = big.cx - small.cx
    val dy = big.cy - small.cy
    val distance = hypot(dx, dy)
    // 84dp on a phone about 400dp across, in these units.
    val bow = min(distance * 0.12f, 21f)
    val perpendicular = if (dx >= 0f) 1f else -1f
    val ctrlX = (small.cx + big.cx) * 0.5f + perpendicular * (-dy / distance.coerceAtLeast(1f)) * bow
    val ctrlY = (small.cy + big.cy) * 0.5f + perpendicular * (dx / distance.coerceAtLeast(1f)) * bow
    val one = 1f - p
    val cx = one * one * small.cx + 2f * one * p * ctrlX + p * p * big.cx
    val cy = one * one * small.cy + 2f * one * p * ctrlY + p * p * big.cy
    val sizeP = progress.coerceIn(-0.025f, 1.025f)
    val w = small.w + (big.w - small.w) * sizeP
    val h = small.h + (big.h - small.h) * sizeP
    return Rect2(cx - w / 2f, cy - h / 2f, w, h)
}

private fun DrawScope.drawCover(sc: CoverScene, look: Look, pal: SkeuoPalette, measurer: TextMeasurer,
                                clockSp: TextUnit) {
    val (s, o) = sc.cam.view(size.width, size.height, fill = 0.92f)
    val map = { x: Float, y: Float -> Offset(o.x + x * s, o.y + y * s) }
    val c = sc.cover.value.coerceIn(0f, 1.05f)
    val cc = c.coerceAtMost(1f)
    val bg = ramp(0f, 0.7f, c)
    val lyr = sc.lyrics.value
    val dz = sc.aod.value
    val card = look.style == 1
    withTransform({
        translate(o.x, o.y)
        scale(s, s, Offset.Zero)
    }) {
        drawPhoneScreen(pal)
        val screen = Path().apply { addRoundRect(RoundRect(0f, 0f, PW, PH, CornerRadius(PHONE_CORNER))) }
        clipPath(screen) {
            // The doze: the whole lock screen drawn a little smaller, the way keyguard_root_view is.
            val zoom = 1f - 0.05f * dz
            withTransform({ scale(zoom, zoom, Offset(PW / 2f, PH / 2f)) }) {
                // The wallpaper's crossfade. Full-screen, it is the cover, frosted while the lyrics
                // are on it; as a square, the wallpaper is the cover's softened copy.
                if (bg > 0.003f) {
                    drawArtBackdrop(look.bias, soft = if (card) 1f else lyr, alpha = bg)
                    val veil = if (card) 0.22f else 0.08f + 0.14f * lyr
                    drawRect(Color.Black.copy(alpha = veil * bg), Offset.Zero, Size(PW, PH))
                }
                drawFlight(sc, look, card, lyr)
                drawMedia(sc, pal, bg)
                for (x in floatArrayOf(12.5f, 87.5f)) {
                    drawCircle(lerp(pal.pill, Color.White.copy(alpha = 0.3f), bg), DISC_D / 2f, Offset(x, RY))
                }
                // Everything above goes dark in the doze; the clock and the words below stay lit.
                if (dz > 0.003f) {
                    drawRect(Color.Black.copy(alpha = 0.8f * dz), Offset(-10f, -10f), Size(PW + 20f, PH + 20f))
                }
                val lit = 1f - 0.3f * dz
                val small = smallClockScale(look.clockSize)
                val k = lerpF(1f, small, cc)
                val ink = lerp(pal.frame, Color.White, bg.coerceAtLeast(dz)).copy(alpha = lit)
                val clock = measurer.measure("09:41", TextStyle(color = ink, fontSize = clockSp, fontWeight = FontWeight.Bold))
                val top = lerpF(CLOCK_TOP, CLOCK_TOP_SMALL, cc)
                withTransform({ scale(k, k, Offset(PW / 2f, top)) }) {
                    drawText(clock, topLeft = Offset(PW / 2f - clock.size.width / 2f, top))
                }
                val dateY = top + clock.size.height * k + 2.5f
                drawRoundRect(lerp(pal.pill, Color.White.copy(alpha = 0.55f), bg), Offset(PW / 2f - 12f, dateY),
                    Size(24f, 2.4f), CornerRadius(1.2f), alpha = lit)
                drawLyrics(sc, lyr * lit)
            }
            sc.burst.draw(this, CARD.cx, CARD.y + 4f, 44f, 20f, 1.15f)
        }
        drawPhoneFrame(pal)
    }
    drawFinger(sc.finger, pal, map)
    drawFinger(sc.finger2, pal, map)
}

/**
 * The artwork between its small place and the cover's. As a square it stays there once landed -
 * the flight's end is the square's own place - giving way to the lyrics; full-screen, the copy is
 * only up while flyAlpha says so.
 */
private fun DrawScope.drawFlight(sc: CoverScene, look: Look, card: Boolean, lyr: Float) {
    val f = sc.fly.value
    if (f <= 0.003f) return
    // As a square the flight's end is the square itself, always up; full-screen, only the copy.
    val a = if (card) 1f - lyr else sc.flyAlpha.value
    if (a <= 0.003f) return
    val small = if (sc.fromPill) pillArtNow(sc) else THUMB
    val big = if (card) SQUARE else fullArt(look.bias)
    val box = flightBox(small, big, f)
    val p = f.coerceIn(0f, 1f)
    // From the small end's corners - a circle for the pill's, a fifth of the side for the
    // thumbnail's - to the card's own radius, or none full-screen.
    val startR = if (sc.fromPill) small.w / 2f else small.w * 0.2f
    val r = lerpF(startR, if (card) 7f else 0f, p)
    val shrink = if (card) 1f - 0.12f * lyr else 1f
    withTransform({ scale(shrink, shrink, Offset(box.cx, box.cy)) }) {
        drawArt(box, r, a)
        // The thin light edge the square wears, growing in as it arrives (cardDecoration).
        if (card) {
            val deco = ramp(0f, 1f, p)
            if (deco > 0.01f) {
                drawRoundRect(Color.White.copy(alpha = 0.25f * deco * a), Offset(box.x, box.y), Size(box.w, box.h),
                    CornerRadius(r), style = Stroke(0.35f))
            }
        }
    }
}

/** 0.05 of the full clock to all of it, as the slider; 0.09, the default, is about a third here. */
private fun smallClockScale(size: Float): Float {
    val v = (if (size > 0f) size else 0.09f).coerceIn(0.05f, 1f)
    return 0.3f + (v - 0.05f) / 0.95f * 0.7f
}

/** The cover as a picture in a box: its sky, its sun, its hill. */
private fun DrawScope.drawArt(b: Rect2, r: Float, alpha: Float) {
    val clip = Path().apply { addRoundRect(RoundRect(b.x, b.y, b.right, b.bottom, CornerRadius(r))) }
    clipPath(clip) {
        drawRect(Brush.verticalGradient(listOf(ART_TOP, ART_BOTTOM), b.y, b.bottom), Offset(b.x, b.y), Size(b.w, b.h),
            alpha = alpha)
        drawArtMarks(b.x, b.y, b.w, alpha, soft = 0f)
    }
}

/**
 * The cover as the wallpaper. Its square sits where the position slider puts it and the rest of
 * the screen is its sky carried on, the way the module extends a cover to the screen's height.
 * `soft` is how far it is frosted: its marks spread and fade.
 */
internal fun DrawScope.drawArtBackdrop(bias: Float, soft: Float, alpha: Float) {
    drawRect(Brush.verticalGradient(listOf(ART_TOP, ART_BOTTOM), 0f, PH), Offset.Zero, Size(PW, PH), alpha = alpha)
    val a = fullArt(bias)
    drawArtMarks(a.x, a.y, a.w, alpha, soft)
}

private fun DrawScope.drawArtMarks(x: Float, y: Float, w: Float, alpha: Float, soft: Float) {
    drawCircle(ART_SUN.copy(alpha = lerpF(0.5f, 0.26f, soft) * alpha), w * lerpF(0.2f, 0.3f, soft),
        Offset(x + w * 0.66f, y + w * 0.34f))
    val hill = Path().apply {
        moveTo(x, y + w * 0.8f)
        cubicTo(x + w * 0.25f, y + w * 0.6f, x + w * 0.45f, y + w * 0.62f, x + w * 0.62f, y + w * 0.74f)
        cubicTo(x + w * 0.78f, y + w * 0.84f, x + w * 0.9f, y + w * 0.7f, x + w, y + w * 0.66f)
        lineTo(x + w, y + w); lineTo(x, y + w); close()
    }
    drawPath(hill, ART_HILL.copy(alpha = lerpF(0.55f, 0.3f, soft) * alpha))
}

/** The lyric page: bars for lines, the sung one filling white left to right. */
private fun DrawScope.drawLyrics(sc: CoverScene, a: Float) {
    if (a <= 0.003f) return
    val f = sc.focus.value
    val rise = (1f - a) * 6f
    clipRect(0f, BAND_TOP, PW, BAND_BOTTOM) {
        for (i in LYRIC_WIDTHS.indices) {
            val y = BAND_TOP + 16f + (i - f) * LINE_GAP + rise
            if (y < BAND_TOP - 6f || y > BAND_BOTTOM + 6f) continue
            val edge = ramp(BAND_TOP - 2f, BAND_TOP + 12f, y) * (1f - ramp(BAND_BOTTOM - 14f, BAND_BOTTOM, y))
            val w = (PW - 20f) * LYRIC_WIDTHS[i]
            val h = if (i == sc.sung) 3.4f else 2.8f
            val base = when {
                i < sc.sung -> 0.28f
                i == sc.sung -> 0.4f
                else -> 0.34f
            }
            drawRoundRect(Color.White.copy(alpha = base * edge * a), Offset(10f, y), Size(w, h), CornerRadius(h / 2f))
            if (i == sc.sung) {
                val lit = w * sc.fill.value
                if (lit > 0.2f) {
                    drawRoundRect(Color.White.copy(alpha = edge * a), Offset(10f, y), Size(lit, h), CornerRadius(h / 2f))
                }
            }
        }
    }
}

/** The pill's box as it is drawn now, its press included. */
private fun pillNow(sc: CoverScene): Rect2 {
    val k = 1f - 0.05f * sc.press.value
    return Rect2(PILL.cx - PILL.w * k / 2f, PILL.cy - PILL.h * k / 2f, PILL.w * k, PILL.h * k)
}

private fun pillArtNow(sc: CoverScene): Rect2 {
    val p = pillNow(sc)
    val inset = p.h * 0.17f
    return Rect2(p.x + inset, p.y + inset, p.h - inset * 2f, p.h - inset * 2f)
}

/**
 * The media controls: the card (frosted white on the cover, a grey one off it), the pill in the
 * row, or the frame between them - each one's contents out over the first half of the way and
 * the other's in over the second, as MiniCardMorph crossfades them.
 */
private fun DrawScope.drawMedia(sc: CoverScene, pal: SkeuoPalette, bg: Float) {
    val alpha = 1f
    val m = sc.media.value
    val box = lerpBox(pillNow(sc), CARD, m.coerceIn(-0.05f, 1.05f))
    val r = lerpF(PILL.h / 2f, 7f, ramp(0f, 0.6f, m))
    val fill = lerp(pal.pill, Color.White.copy(alpha = 0.32f), bg)
    val ink = lerp(pal.line, Color.White.copy(alpha = 0.85f), bg)
    val dark = lerp(pal.frame, Color.White, bg)
    run {
        drawRoundRect(fill, Offset(box.x, box.y), Size(box.w, box.h), CornerRadius(r), alpha = alpha)
        val clip = Path().apply { addRoundRect(RoundRect(box.x, box.y, box.right, box.bottom, CornerRadius(r))) }
        clipPath(clip) {
            // The pill's: its circle of artwork (unless it is up in the cover), a bar, pause.
            val pillA = alpha * (1f - ramp(0f, 0.45f, m))
            if (pillA > 0.01f) {
                val art = pillArtNow(sc)
                val ax = box.x + (art.x - PILL.x)
                if (sc.fly.value < 0.02f) {
                    drawArt(Rect2(ax, box.cy - art.h / 2f, art.w, art.h), art.w / 2f, pillA)
                }
                drawRoundRect(ink, Offset(ax + art.w + 3f, box.cy - 0.9f), Size(box.w * 0.42f, 1.8f),
                    CornerRadius(0.9f), alpha = pillA)
                val px = box.right - 6f
                drawRoundRect(dark, Offset(px - 1.6f, box.cy - 2.2f), Size(1.2f, 4.4f), CornerRadius(0.6f), alpha = pillA)
                drawRoundRect(dark, Offset(px + 0.6f, box.cy - 2.2f), Size(1.2f, 4.4f), CornerRadius(0.6f), alpha = pillA)
            }
            // The card's, laid out in its own frame and carried by the box.
            val cardA = alpha * ramp(0.55f, 1f, m)
            if (cardA > 0.01f) {
                val kx = box.w / CARD.w
                val ky = box.h / CARD.h
                fun at(x: Float, y: Float) = Offset(box.x + (x - CARD.x) * kx, box.y + (y - CARD.y) * ky)
                // The thumbnail is the cover's until the cover has it; after that the slot stays
                // empty, as the module hides it while the artwork is the wallpaper.
                if (sc.fly.value < 0.02f) {
                    val t = at(THUMB.x, THUMB.y)
                    drawArt(Rect2(t.x, t.y, THUMB.w * kx, THUMB.h * ky), 3f, cardA)
                }
                val tx = THUMB.right + 5f
                drawRoundRect(ink, at(tx, THUMB.y + 3f), Size(30f * kx, 2.4f), CornerRadius(1.2f), alpha = cardA)
                drawRoundRect(ink.copy(alpha = ink.alpha * 0.65f), at(tx, THUMB.y + 8.5f), Size(20f * kx, 1.9f),
                    CornerRadius(0.95f), alpha = cardA)
                val pp = at(PLAY.x, PLAY.y)
                drawRoundRect(dark, Offset(pp.x - 2.4f, pp.y - 3f), Size(1.6f, 6f), CornerRadius(0.8f), alpha = cardA)
                drawRoundRect(dark, Offset(pp.x + 0.8f, pp.y - 3f), Size(1.6f, 6f), CornerRadius(0.8f), alpha = cardA)
                // The seek bar: the one blue on the card, and it moves while the song plays.
                val bar = at(CARD.x + 5f, CARD.bottom - 9f)
                drawRoundRect(ink.copy(alpha = ink.alpha * 0.6f), bar, Size((CARD.w - 10f) * kx, 1.4f),
                    CornerRadius(0.7f), alpha = cardA)
                val played = (0.18f + 0.06f * sc.focus.value + 0.03f * sc.fill.value) * (CARD.w - 10f) * kx
                drawRoundRect(lerp(pal.accent, Color.White, bg), bar, Size(played, 1.4f), CornerRadius(0.7f),
                    alpha = cardA)
            }
        }
    }
}

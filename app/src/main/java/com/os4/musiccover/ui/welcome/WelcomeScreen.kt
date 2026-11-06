package com.os4.musiccover.ui.welcome

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode
import com.os4.musiccover.R
import com.os4.musiccover.ui.component.effect.BgEffectBackground
import com.os4.musiccover.ui.util.isInDarkTheme
import com.os4.musiccover.ui.util.rememberBlurBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.hypot

/**
 * The page shown over the home screen on a first launch: the mark comes up out of the splash
 * screen's own position, light drifts in behind it, and the way in is a round button.
 *
 * Nothing here is drawn over the splash screen itself - the platform owns that window - so the
 * first frame has to look like the splash: the same mark, in the middle, on the same colour,
 * and it stays there until the splash has actually gone (see MainActivity).
 *
 * The grid is HyperCeiler's welcome page - weights 30 / 40 / 20 around the mark, the title
 * 20dp under it, the button on the bottom of the middle band - with its sizes. The way each
 * piece arrives is Oolong's landing page instead (ui/page/landing/Landing.kt): the mark, the
 * title and the way in 350 / 1150 / 2150ms in, each out of its own blur on a critically damped
 * spring.
 */
@Composable
fun WelcomeScreen(
    splashIcon: SplashIcon? = null,
    splashGone: () -> Boolean? = { true },
    fromSplash: Boolean = false,
    modifier: Modifier = Modifier,
    onDone: () -> Unit,
) {
    val paper = remember { Animatable(0f) }
    // A cold start hands the mark over from the splash, so it starts at the splash's place and
    // travels in. A replay from settings has no splash behind it: starting this at 0 drew the
    // mark where the last splash left it for one frame, then the snap moved it - the jump.
    val rise = remember { Animatable(if (fromSplash) 0f else 1f) }
    val name = remember { Animatable(0f) }
    val actions = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            paper.snapTo(1f)
            rise.snapTo(1f)
            name.snapTo(1f)
            actions.snapTo(1f)
            glow.snapTo(1f)
            return@LaunchedEffect
        }
        // The mark sits still until the splash has actually gone: the platform holds it up past
        // this composition - on a cold start for seconds - and anything played behind it is
        // over before anyone sees the page. A null answer means there is no signal to wait for
        // (the listener could not be set) and this composition is all there is.
        while (splashGone() == false) delay(16)
        launch { glow.animateTo(1f, tween(900)) }
        // The splash left the mark in the middle of the screen and the page's layout has it
        // higher up, so it starts travelling at once and comes into focus on the way. This is
        // the one move the original does not have: it has no splash to hand over from.
        if (fromSplash) {
            launch { rise.animateTo(1f, tween(400, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))) }
        } else {
            rise.snapTo(1f)
        }
        // LandingState.enter: the mark, the name and the way in start 350 / 1150 / 2150ms in,
        // out of the same spring each. The original's lens over the mark and its line under the
        // name have no counterpart here.
        launch { delay(350); paper.animateTo(1f, Emerge) }
        launch { delay(1150); name.animateTo(1f, Emerge) }
        launch { delay(2150); actions.animateTo(1f, Rise) }
    }

    // The way out is the button growing until it covers the screen, opening onto the home page
    // drawn under this one.
    fun startReveal() {
        if (reveal.targetValue > 0f) return
        scope.launch {
            reveal.animateTo(1f, tween(800, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
            onDone()
        }
    }

    val dark = isInDarkTheme()
    // The same blurred backdrop the about page's title is filled with.
    val blurBackdrop = rememberBlurBackdrop()
    val logoBlend = remember(dark) {
        if (dark) {
            listOf(
                BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
                BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
            )
        } else {
            listOf(
                BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
                BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
            )
        }
    }
    // The page is drawn on the theme's background, and the light behind it keeps the original's
    // colours; only the title and the way in carry the mark's own.
    val colorScheme = MiuixTheme.colorScheme
    val pageBackground = colorScheme.background
    val ink = colorScheme.onBackground
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .drawWithContent {
                val step = reveal.value
                if (step <= 0f) {
                    drawContent()
                    return@drawWithContent
                }
                val center = androidx.compose.ui.geometry.Offset(
                    size.width / 2f,
                    buttonCenterY(size.height, this),
                )
                val from = ButtonSize.toPx() / 2f
                val to = hypot(size.width, size.height)
                val radius = from + (to - from) * step
                val circle = Path().apply {
                    addOval(
                        Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
                    )
                }
                // Outside the circle: the page as it is. Inside it: whatever the home page has
                // already drawn, because the hole is a real hole.
                clipPath(circle, clipOp = ClipOp.Difference) {
                    this@drawWithContent.drawContent()
                }
            }
            // Inside the draw above rather than outside it: a background applied after this
            // modifier would be drawn over the hole as well, and the circle would open onto the
            // page's own colour instead of the home page.
            .background(pageBackground)
    ) {
        val heightPx = constraints.maxHeight.toFloat()
        val markPx = with(LocalDensity.current) { MarkSize.toPx() }
        val layoutCenterY = (heightPx - markPx) * 30f / 90f + markPx / 2f
        // Where the splash leaves the mark, from the splash's own geometry where it could be
        // read at all (see MainActivity) and from the platform's splash rules otherwise: the
        // icon is drawn in the middle of the screen.
        val splashCenterY = splashIcon?.centerY ?: (heightPx * SplashMarkCenterFallback)
        // The splash leaves a 192dp square behind - measured 576px at this device's density of
        // 3.0 - and the artwork fills the whole icon view, so its width is the view's width.
        // This page draws the mark at 120dp; the fallback is the same 192/120 for the launches
        // whose geometry could not be read.
        val splashContentPx = splashIcon?.let { it.size * SplashIconDrawnRatio }
            ?: (markPx * SplashMarkScaleFallback)
        val risePx = splashCenterY - layoutCenterY
        val riseScale = (splashContentPx / markPx).coerceAtLeast(1f)

        // The about page's own light - the same effect background, with the same presets for a
        // light and a dark theme - rather than the provision page's shader. Faded in on arrival
        // and out with the circle, and it is drawn by Compose, so the clip above reaches it.
        BgEffectBackground(
            dynamicBackground = true,
            isFullSize = true,
            modifier = Modifier.fillMaxSize(),
            bgModifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier,
            alpha = { glow.value * (1f - reveal.value) },
        ) { }

        // And the provision page's own movable light over it: the effect background gives this
        // page the theme's colour, this gives it the glow and the ring that opens on the mark.
        // Halved so the two layers mix instead of the lower one being covered.
        GlowBackground(
            modifier = Modifier
                .fillMaxSize()
                // The provision page runs its light at full strength over a plain content
                // layout; on a light page the white scrim below already lightens it, so the
                // layer itself has to be stronger to read like the original.
                .graphicsLayer { alpha = (if (dark) 0.6f else 0.9f) * glow.value * (1f - reveal.value) },
            circleYOffset = (heightPx / 2f - layoutCenterY) / heightPx,
            // Nothing to soften on a dark page; a light one still wants the white the original
            // put over its shader.
            scrim = if (dark) Color.Transparent else Color.White.copy(alpha = 0.15f),
            // The original's red/blue/violet is its own palette and clashes with a dark page;
            // a dark theme gets the blue-violet the rest of the app is drawn in instead.
            colors = if (dark) GlowDark else GlowPainter.DefaultColors,
            // A dark page is drawn on the About page's own effect background, so the light is
            // only the ring; a light one keeps the original light field and its white scrim.
            ringOnly = dark,
        )

        // The page stays where it is while the circle opens over it: the original's scale-up
        // animates only the round thing being opened, never the page it leaves behind.
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(30f))
            // The mark's own drawing leaves a third of its canvas empty on each side, so the
            // image is a third again wider than the mark itself. The box keeps the layout at the
            // mark's own size.
            Box(Modifier.size(MarkSize)) {
                Image(
                    painter = painterResource(R.drawable.splash_icon),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .requiredSize(MarkBoxSize)
                        .graphicsLayer {
                            // The splash drew the mark a little above the middle; the layout
                            // above puts it a little higher still. It travels that distance
                            // rather than jumping, and comes down to the size the page's layout
                            // draws it at - the platform sizes the splash icon itself.
                            translationY = risePx * (1f - rise.value)
                            val s = 1f + (riseScale - 1f) * (1f - rise.value)
                            scaleX = s
                            scaleY = s
                        },
                )
            }
            Box(
                Modifier
                    .weight(40f)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "HyperMusicCover",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = TitleGap)
                        .condense({ name.value }, blur = 16.dp, rise = 8.dp, fadeFaster = 1.15f)
                        .then(
                            if (blurBackdrop != null) {
                                Modifier.textureBlur(
                                    backdrop = blurBackdrop,
                                    shape = RoundedCornerShape(16.dp),
                                    blurRadius = 150f,
                                    noiseCoefficient = BlurDefaults.NoiseCoefficient,
                                    colors = BlurDefaults.blurColors(blendColors = logoBlend),
                                    contentBlendMode = ComposeBlendMode.DstIn,
                                )
                            } else {
                                Modifier
                            },
                        ),
                    fontSize = NameSizeSp.sp,
                    lineHeight = NameLineHeightSp.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    letterSpacing = (-1).sp,
                    color = ink,
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // The original's click target is the 4.5dp-padded layout around its 70dp
                        // image, so the touch area reaches a little past the disc.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { startReveal() },
                        )
                        .padding(4.5.dp)
                        .condense({ actions.value }, blur = 4.dp, rise = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // The provision page's button, as it draws one with the blur on: a white disc
                    // whose blur is blended with its own two colours, and the arrow on top as a
                    // separate 29x20dp image. That page is always light - it has no dark variant
                    // and its two blend colours turn the disc brown over a dark background - so
                    // a dark page gets the flat disc the original uses when it cannot blur.
                    Box(Modifier.size(ButtonSize), contentAlignment = Alignment.Center) {
                        if (blurBackdrop != null && !dark) {
                            Box(
                                Modifier
                                    .matchParentSize()
                                    // The disc is what the blur is masked by, so it sits inside
                                    // the effect; the other way round the white disc would cover
                                    // the tint.
                                    .textureBlur(
                                        backdrop = blurBackdrop,
                                        shape = CircleShape,
                                        blurRadius = 50f,
                                        noiseCoefficient = BlurDefaults.NoiseCoefficient,
                                        colors = BlurDefaults.blurColors(blendColors = NextBlend),
                                        contentBlendMode = ComposeBlendMode.DstIn,
                                    )
                                    .background(Color.White, CircleShape),
                            )
                            Image(
                                painter = painterResource(R.drawable.welcome_next_arrow),
                                contentDescription = null,
                                modifier = Modifier.size(29.dp, 20.dp),
                            )
                        } else if (dark) {
                            // The blurred disc's colour comes from the page behind it, and over
                            // a dark one the two blend colours turn it brown. This is the
                            // colour the same button reads at on a light page (measured), so a
                            // dark page keeps the button it has there.
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .background(NextDiscOnDark, CircleShape),
                            )
                            Image(
                                painter = painterResource(R.drawable.welcome_next_arrow),
                                contentDescription = null,
                                modifier = Modifier.size(29.dp, 20.dp),
                            )
                        } else {
                            Image(
                                painter = painterResource(R.drawable.welcome_next),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(20f))
        }
    }
}

/** The button hangs on the bottom of the middle band - weight 20 is what is left under it. */
private fun buttonCenterY(heightPx: Float, density: Density): Float {
    val markPx = with(density) { MarkSize.toPx() }
    val buttonHalfPx = with(density) { (ButtonSize / 2).toPx() }
    return heightPx - (heightPx - markPx) * 20f / 90f - buttonHalfPx
}

/** Critically damped, as slow as [response] seconds: seen once, the page takes its time. */
private fun <T> slow(response: Float): AnimationSpec<T> {
    val omega = 2 * PI.toFloat() / response
    return spring(dampingRatio = 1f, stiffness = omega * omega)
}

/** Oolong's entrance springs. */
private val Emerge = slow<Float>(1.5f)
private val Rise = slow<Float>(1.2f)

// HyperCeiler's grid and sizes: a mark (120dp here, 90dp there), the title 20dp under it, a
// 70dp button, and the weights 30 / 40 / 20 that put them where they are on that page.
private val MarkSize = 120.dp

/** The mark's drawing leaves a third of its canvas empty on each side; the image holds that too. */
private val MarkBoxSize = MarkSize * 1.5f
private val TitleGap = 20.dp
private val ButtonSize = 70.dp

// The title is Oolong's 46sp name written out in full: "HyperMusicCover" is long enough that
// 46sp folds it, so it takes the size HyperCeiler's own title reads at and fits on one line.
private const val NameSizeSp = 32f
private const val NameLineHeightSp = 40f

/**
 * What the splash draws when its geometry could not be read: the platform puts the icon in the
 * middle of the screen, at 192dp - one and a half times the 120dp this page's layout uses.
 */
private const val SplashMarkCenterFallback = 0.5f
private const val SplashMarkScaleFallback = 1.6f

/**
 * What part of the splash icon view the artwork itself covers. All of it: the platform draws
 * the drawable at 192dp and crops its empty margin to that same 192dp, so the visible square
 * is the icon view's own width (measured 576px of 576px).
 */
private const val SplashIconDrawnRatio = 1f

// The provision page's own colours for the next button's blur: a dark grey through LinearLight
// and the green through Lab (setupViewBlur(mNext, {0xFF2E2E2E, 0xFF1AF200}, {100, 106})).
private val NextBlend = listOf(
    BlendColorEntry(Color(0xFF2E2E2E), BlurBlendMode.LinearLight),
    BlendColorEntry(Color(0xFF1AF200), BlurBlendMode.Lab),
)

/** What that blurred disc reads at on a light page, for the dark page's flat copy of it. */
private val NextDiscOnDark = Color(0xFF494167)

// The light on a dark page: the About page's own effect background, which this page is drawn
// on top of (BgEffectConfig's OS2 phone dark preset). Its indigo / magenta / blue read as the
// same dark page the About page is; the blue-violet this used to carry washed out to a grey
// periwinkle on black. The preset's warm stop is left out - there are three stops, and the
// other three are the ones the mark's own indigo-to-magenta lives in.
private val GlowDark = listOf(
    Color(0xFF291F73),
    Color(0xFF750F45),
    Color(0xFF004F94),
)

/** Where the system splash drew the app icon, in screen pixels. */
data class SplashIcon(val centerX: Float, val centerY: Float, val size: Float)

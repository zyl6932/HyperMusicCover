// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package com.os4.musiccover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.PathParser
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONObject

/** HyperChanger's compact card, hosted in SystemUI's shortcut area. */
internal class MiniPlayerView(context: Context) : FrameLayout(context) {
    private var materialLayer = ImageView(context)
    private val artwork = ImageView(context)
    private val title = TextView(context)
    private val artist = TextView(context)
    private val textColumn = LinearLayout(context)
    private val toggle = ImageButton(context)
    private val density = resources.displayMetrics.density
    private var lastAppearance: String? = null
    private var lastMaterial: String? = null
    private var lastArtwork: Bitmap? = null
    private var lastPlaying: Boolean? = null
    private var artworkRadiusPx = dp(12).toFloat()
    private var interactionsEnabled = true
    /** Where position() puts the view at rest; a morph moves it without losing this. */
    private var baseX = 0f
    private var baseY = 0f
    private var morphing = false
    private var morphW = 0
    private var morphH = 0
    private var morphRadius = 0f
    private var artworkMorphRadius = Float.NaN
    private var artworkHidden = false
    private var onToggle: (() -> Unit)? = null
    private var onPrevious: (() -> Unit)? = null
    private var onNext: (() -> Unit)? = null
    private var onShowNative: (() -> Unit)? = null
    private var onOpenCover: (() -> Unit)? = null
    private var tracking = false
    private var toggleShown = true
    private var lastHeightRadiusDp = 36f
    private var lastArtRadiusDp = 12f

    init {
        clipToOutline = true
        outlineAmbientShadowColor = Color.TRANSPARENT
        outlineSpotShadowColor = Color.TRANSPARENT
        // A pill at rest; during a morph, the container's own corner. The material layer shares
        // it, so the glass is cut to the same shape as the frame it fills.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height,
                    if (morphing) morphRadius else view.height / 2f)
            }
        }
        materialLayer.scaleType = ImageView.ScaleType.FIT_XY
        materialLayer.clipToOutline = true
        materialLayer.outlineProvider = outlineProvider
        addView(materialLayer, LayoutParams(-1, -1))
        artwork.scaleType = ImageView.ScaleType.CENTER_CROP
        artwork.clipToOutline = true
        artwork.background = rounded(Color.rgb(55, 55, 55), dp(12).toFloat())
        addView(artwork)
        title.apply {
            setTextColor(Color.WHITE)
            textSize = 12.8f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MARQUEE
            isSelected = true
            isSingleLine = true
            includeFontPadding = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        artist.apply {
            setTextColor(Color.argb(232, 255, 255, 255))
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MARQUEE
            isSelected = true
            isSingleLine = true
            includeFontPadding = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        textColumn.orientation = LinearLayout.VERTICAL
        textColumn.gravity = Gravity.CENTER_VERTICAL
        // Each line gets room above and below its glyphs, taken back by the margins so the rest
        // layout is unchanged. Without it the glyphs touched the view's own clip, and scaled onto
        // the card's text in a morph that edge cut through them as a hairline.
        val room = dp(3)
        title.setPadding(0, room, 0, room)
        artist.setPadding(0, room, 0, room)
        textColumn.addView(title, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = -room
            bottomMargin = -room
        })
        textColumn.addView(artist, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = -room
            bottomMargin = -room
        })
        artist.translationY = -dp(2).toFloat()
        addView(textColumn)
        toggle.scaleType = ImageView.ScaleType.CENTER
        toggle.setPadding(dp(8), dp(8), dp(8), dp(8))
        toggle.background = null
        toggle.contentDescription = "播放或暂停"
        toggle.setOnClickListener { onToggle?.invoke() }
        addView(toggle)
        contentDescription = "迷你音乐播放器"
    }

    fun bind(
        trackTitle: String,
        trackArtist: String,
        cover: Bitmap?,
        playing: Boolean,
        config: JSONObject,
        material: String,
        applyMaterial: (ImageView) -> Unit,
        togglePlayback: () -> Unit,
        skipPrevious: () -> Unit,
        skipNext: () -> Unit,
        showNative: () -> Unit,
        openCover: () -> Unit,
    ) {
        val appearance = "$config|$material"
        if (lastAppearance != appearance) {
            lastAppearance = appearance
            if (lastMaterial != null && lastMaterial != material) {
                // A fresh view also drops the previous vendor blur/material state.
                removeView(materialLayer)
                materialLayer = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_XY
                    clipToOutline = true
                    outlineProvider = this@MiniPlayerView.outlineProvider
                }
                addView(materialLayer, 0, LayoutParams(-1, -1))
            }
            lastMaterial = material
            materialAgain = {
                applyMaterial(materialLayer)
                materialLayer.outlineProvider = outlineProvider
                materialLayer.clipToOutline = true
            }
            applyMaterial(materialLayer)
            // The card's recipe gives the layer its own 24dp outline; the pill's shape - and the
            // morph's changing corner - is ours.
            materialLayer.outlineProvider = outlineProvider
            materialLayer.clipToOutline = true
            updateGeometry(config.getDouble(MiniPlayerConfig.HEIGHT_RADIUS).toFloat(),
                config.getDouble(MiniPlayerConfig.ART_RADIUS).toFloat())
        }
        if (title.text.toString() != trackTitle) title.text = trackTitle
        if (artist.text.toString() != trackArtist) artist.text = trackArtist
        if (lastArtwork !== cover) {
            lastArtwork = cover
            artwork.setImageBitmap(cover)
        }
        if (lastPlaying != playing) {
            lastPlaying = playing
            toggle.setImageDrawable(MiniPlayerPathDrawable(
                if (playing) ICON_PAUSE else ICON_PLAY, Color.WHITE))
        }
        onToggle = togglePlayback
        onPrevious = skipPrevious
        onNext = skipNext
        onShowNative = showNative
        onOpenCover = openCover
    }

    /**
     * Only taps reach the pill itself. Every drag - any direction - belongs to
     * MiniPlayerRuntime.routeTouch, which sends a CANCEL here the moment the finger passes the
     * slop and moves the pill through setNudge; so the play button still clicks, and a drag that
     * started on it is a drag.
     */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!acceptsTouch()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> tracking = true
            MotionEvent.ACTION_UP -> {
                if (tracking) onOpenCover?.invoke()
                tracking = false
            }
            MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return true
    }

    /**
     * A skip turns the artwork once round, the way the swipe went - left for the next track,
     * anticlockwise - slowing to a stop. The new artwork lands whenever the player sends it,
     * under the spin rather than as a cut.
     */
    /** The artwork alone, for a scaled copy that arrives between refreshes. */
    fun showArtwork(bitmap: Bitmap?) {
        if (bitmap == null || lastArtwork === bitmap) return
        lastArtwork = bitmap
        artwork.setImageBitmap(bitmap)
    }

    fun performSkip(next: Boolean) {
        if (next) onNext?.invoke() else onPrevious?.invoke()
        if (morphing) return
        artwork.animate().cancel()
        artwork.rotation = 0f
        artwork.animate()
            .rotationBy(if (next) -360f else 360f)
            .setDuration(560L)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.3f, 1f))
            .withEndAction { artwork.rotation = 0f }
            .start()
    }

    /** The drag's offset on top of the rest place, in pixels; a spring takes it home. */
    private var offsetX = 0f
    private var offsetY = 0f
    private val springX = CoverMorphMotion()
    private val springY = CoverMorphMotion()
    private var springLast = 0L
    private val springFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dt = if (springLast == 0L) 1f / 120f
                else ((frameTimeNanos - springLast) / 1e9f).coerceIn(0f, 0.05f)
            springLast = frameTimeNanos
            springX.step(dt, OFFSET_RESPONSE)
            springY.step(dt, OFFSET_RESPONSE)
            if (springX.atRest() && springY.atRest()) {
                applyOffset(0f, 0f)
                return
            }
            applyOffset(springX.value * 100f, springY.value * 100f)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    val nudgeX: Float get() = offsetX
    val nudgeY: Float get() = offsetY

    /** Where the finger has pulled the pill to; the caller rubber-bands it. */
    fun setNudge(x: Float, y: Float) {
        Choreographer.getInstance().removeFrameCallback(springFrame)
        applyOffset(x, y)
    }

    /** Home with the finger's own speed, so a fling carries past the rest place and back. */
    fun springNudgeBack(vx: Float, vy: Float) {
        if (offsetX == 0f && offsetY == 0f) return
        springX.value = offsetX / 100f
        springY.value = offsetY / 100f
        springX.velocity = vx / 100f
        springY.velocity = vy / 100f
        springX.target = 0f
        springY.target = 0f
        springLast = 0L
        Choreographer.getInstance().removeFrameCallback(springFrame)
        Choreographer.getInstance().postFrameCallback(springFrame)
    }

    /** A finger on a pill still springing home: it stays where it is, under the finger. */
    fun holdNudge() {
        Choreographer.getInstance().removeFrameCallback(springFrame)
    }

    /** The morph takes the nudge over (MiniCardMorph's own), so the pill's goes to nothing. */
    fun clearNudge() = setNudge(0f, 0f)

    private fun applyOffset(x: Float, y: Float) {
        offsetX = x
        offsetY = y
        if (morphing) return
        translationX = baseX + offsetX
        translationY = baseY + offsetY
    }

    /** Re-applies the material now; for a first show that came before the pill had a size. */
    private var materialAgain: (() -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!morphing && w > 1 && h > 1 && (oldw <= 1 || oldh <= 1)) materialAgain?.invoke()
    }

    private fun finish() {
        tracking = false
    }

    override fun onDetachedFromWindow() {
        finish()
        super.onDetachedFromWindow()
    }

    val artworkView: View get() = artwork

    /** For `op mini`: the artwork as it stands - shown, alpha, frame, bitmap. */
    fun artworkState(): String {
        val b = (artwork.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        return "v=${artwork.visibility} a=${"%.2f".format(artwork.alpha)} ${artwork.left},${artwork.top} " +
            "${artwork.width}x${artwork.height} bmp=${b?.let { "${it.width}x${it.height}${if (it.isRecycled) " RECYCLED" else ""}" }} " +
            "hidden=$artworkHidden text=${"%.2f".format(textColumn.alpha)}"
    }
    val titleView: TextView get() = title
    val artistView: TextView get() = artist
    val toggleView: View get() = toggle

    /** The size position() asked for; a morph changes the frame, not this. */
    private fun restWidth() = layoutParams?.width?.takeIf { it > 1 } ?: width
    private fun restHeight() = layoutParams?.height?.takeIf { it > 1 } ?: height

    /** The rest frame on screen, whatever a morph is doing to the view meanwhile. */
    fun restBoxOnScreen(): CoverMorphMotion.Box? {
        val host = parent as? View ?: return null
        if (!isAttachedToWindow || restWidth() <= 1 || restHeight() <= 1) return null
        val xy = IntArray(2).also(host::getLocationOnScreen)
        return CoverMorphMotion.Box(xy[0] + left + baseX, xy[1] + top + baseY,
            restWidth().toFloat(), restHeight().toFloat())
    }

    /** The artwork's rest slot on screen: where a flight from the cover has to land. */
    fun artworkRestBoxOnScreen(): CoverMorphMotion.Box? {
        val rest = restBoxOnScreen() ?: return null
        if (artwork.width <= 0 || artwork.height <= 0) return null
        return CoverMorphMotion.Box(rest.x + artwork.left, rest.y + artwork.top,
            artwork.width.toFloat(), artwork.height.toFloat())
    }

    fun artworkRestRadius(): Float = artworkRadiusPx

    fun setBaseTranslation(x: Float, y: Float) {
        baseX = x
        baseY = y
        if (morphing) return
        translationX = x + offsetX
        translationY = y + offsetY
    }

    fun acceptsTouch(): Boolean = interactionsEnabled && (!morphing || layoutOnly)

    /** A frame of its own is on: a morph, a switch or the row's spring is drawing it. */
    fun inMorph(): Boolean = morphing

    /** For `op mini`: why it would not take a touch. */
    fun touchState(): String = "interactive=$interactionsEnabled morphing=$morphing"

    fun setInteractionsEnabled(enabled: Boolean) {
        if (interactionsEnabled == enabled) return
        interactionsEnabled = enabled
        toggle.isEnabled = enabled && !morphing
        if (!enabled) finish()
    }

    /**
     * While a cover flight owns the artwork's pixels, the slot stays empty. A morph that only
     * moves the frame in the row - a switch, the row's spring - does not own the artwork's
     * alpha: the flight out of the cover landed while the small island was pushing the pill
     * narrower, the artwork was left hidden until that spring had settled, and it vanished
     * and came back (filmed 2026-09-25).
     */
    fun setArtworkHidden(hidden: Boolean) {
        if (artworkHidden == hidden) return
        artworkHidden = hidden
        if (!morphing) artwork.alpha = if (hidden) 0f else 1f
        else if (layoutOnly) artwork.alpha = if (hidden) 0f else contentAlpha
    }

    /**
     * [layoutOnly]: the frame only moves and resizes within the row - a switch, the row's layout
     * spring - and the pill keeps taking touches through it. Only a morph into a card or a row
     * shuts them off. The layout spring runs every time a notification comes or goes, and with
     * a busy group chat that shut the pill to touches most of the time (filmed 2026-09-25).
     */
    private var layoutOnly = false

    fun beginMorph(layoutOnly: Boolean = false) {
        if (morphing) return
        this.layoutOnly = layoutOnly
        // Over the media card for the morph - the card's layer is drawn after the pill's - and
        // back down among the lock screen when it ends. Z orders siblings without moving the
        // pill in its parent; the outline's shadow is switched off so the height casts none.
        translationZ = MORPH_Z
        artwork.animate().cancel()
        artwork.rotation = 0f
        morphing = true
        morphW = restWidth()
        morphH = restHeight()
        morphRadius = morphH / 2f
        toggle.isEnabled = layoutOnly && interactionsEnabled
        // A clipping parent clips each child to that child's own bounds: the text column is only
        // as tall as the pill, and the artist, moved onto the card's, was cut through by its
        // bottom edge - filmed as a hairline across the name. The pill's own outline still
        // clips everything to the container.
        clipChildren = false
        textColumn.clipChildren = false
        finish()
    }

    /**
     * The container, in screen pixels. The frame itself is resized - never scaled - so the
     * material fills it at its own resolution and keeps its outline clip. custom.8 stretched it
     * with that clip switched off, and the recording shows it black for the whole flight.
     * setLeftTopRightBottom moves the frame without a layout pass;
     * onLayout() below keeps a pass that happens anyway from putting the rest size back.
     */
    fun setMorphFrame(box: CoverMorphMotion.Box, radius: Float, materialAlpha: Float) {
        if (!morphing) return
        val host = parent as? View ?: return
        val xy = IntArray(2).also(host::getLocationOnScreen)
        morphW = max(1, box.w.roundToInt())
        morphH = max(1, box.h.roundToInt())
        morphRadius = radius.coerceIn(0f, min(morphW, morphH) / 2f)
        setLeftTopRightBottom(left, top, left + morphW, top + morphH)
        materialLayer.setLeftTopRightBottom(0, 0, morphW, morphH)
        translationX = box.x - xy[0] - left
        translationY = box.y - xy[1] - top
        materialLayer.alpha = materialAlpha.coerceIn(0f, 1f)
        // Moving and resizing in the row, the play button keeps to the frame's right end. The
        // layout is already at the new width: laid out there, the button jumped to where the
        // pill was going the moment it set off, and the pill caught up with it (2026-09-25).
        if (layoutOnly) {
            val dx = (morphW - restWidth()).toFloat()
            if (toggle.translationX != dx) toggle.translationX = dx
        }
        invalidateOutline()
        materialLayer.invalidateOutline()
    }

    /** The artwork's corner in its own pixels while it is scaled onto the card's. */
    fun setArtworkMorphRadius(radius: Float) {
        if (artworkMorphRadius == radius) return
        artworkMorphRadius = radius
        artwork.invalidateOutline()
    }

    fun endMorph() {
        if (!morphing) return
        morphing = false
        // The play button back in its own place, where a row move had carried it (setMorphFrame).
        if (layoutOnly) toggle.translationX = 0f
        layoutOnly = false
        artworkMorphRadius = Float.NaN
        artwork.invalidateOutline()
        artwork.alpha = if (artworkHidden) 0f else 1f
        materialLayer.alpha = 1f
        textColumn.clipChildren = true
        clipChildren = true
        translationZ = 0f
        toggle.isEnabled = interactionsEnabled
        translationX = baseX + offsetX
        translationY = baseY + offsetY
        requestLayout()
        invalidateOutline()
        materialLayer.invalidateOutline()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!morphing) {
            super.onLayout(changed, left, top, right, bottom)
            return
        }
        // A layout pass mid-morph has just put the rest frame back (layout() is final): the
        // container frame goes straight back on, in the same pass, before anything is drawn.
        // Children keep their rest places - the morph positions them from there - and only the
        // material follows the frame.
        super.onLayout(changed, left, top, left + restWidth(), top + restHeight())
        setLeftTopRightBottom(left, top, left + morphW, top + morphH)
        materialLayer.layout(0, 0, morphW, morphH)
    }

    /**
     * A notification island has no play button: its text runs on to the edge instead. The
     * button still holds its place in the layout while it is hidden, so hiding it lays out.
     */
    fun setToggleShown(shown: Boolean) {
        if (toggleShown == shown) return
        toggleShown = shown
        toggle.visibility = if (shown) View.VISIBLE else View.GONE
        updateGeometry(lastHeightRadiusDp, lastArtRadiusDp)
    }

    /**
     * Another island has taken the pill: its content comes in from the side the swipe sent it,
     * the pill itself staying where it is.
     */
    fun slideContentIn(fromEnd: Boolean) {
        if (morphing) return
        val dx = dp(28).toFloat() * if (fromEnd) 1f else -1f
        listOf<View>(artwork, textColumn, toggle).forEach { v ->
            v.animate().cancel()
            v.translationX = dx
            v.alpha = 0f
            v.animate().translationX(0f)
                .alpha(if (v === artwork && artworkHidden) 0f else 1f)
                .setDuration(280L)
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.3f, 1f))
                .start()
        }
    }

    /**
     * The pill's content - artwork, text, button - at one alpha, the glass left alone: an
     * island switch brings the new island's content in over the frame as it grows.
     */
    /** The content's alpha a switch last set, for the artwork coming back from a cover flight. */
    private var contentAlpha = 1f

    /** For `op mini`: the content's alpha as drawn (the text column's). */
    fun contentAlphaNow(): Float = textColumn.alpha

    fun setContentAlpha(alpha: Float) {
        val a = alpha.coerceIn(0f, 1f)
        contentAlpha = a
        artwork.alpha = if (artworkHidden) 0f else a
        textColumn.alpha = a
        toggle.alpha = a
    }

    /** The text and the button alone: an island shrinking to its circle keeps its picture. */
    fun setTextAlpha(alpha: Float) {
        val a = alpha.coerceIn(0f, 1f)
        textColumn.alpha = a
        toggle.alpha = a
    }

    private var contentBlur = 0f

    /**
     * The content blurred by [radius] pixels, the glass left sharp: the super island's
     * BIG_ISLAND_BLUR, an island's content going out of focus as it leaves the big island's
     * place and coming into focus as it arrives there.
     */
    fun setContentBlur(radius: Float) {
        val r = if (radius < 0.5f) 0f else radius
        if (kotlin.math.abs(r - contentBlur) < 0.25f && (r == 0f) == (contentBlur == 0f)) return
        contentBlur = r
        val effect = if (r == 0f) null
            else android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.DECAL)
        artwork.setRenderEffect(effect)
        textColumn.setRenderEffect(effect)
        toggle.setRenderEffect(effect)
    }

    private fun updateGeometry(heightRadiusDp: Float, artworkRadiusDp: Float) {
        lastHeightRadiusDp = heightRadiusDp
        lastArtRadiusDp = artworkRadiusDp
        val height = dp(heightRadiusDp * 2f).coerceAtLeast(dp(48))
        val verticalPadding = max(dp(7), height / 9)
        val artworkSize = ((height - verticalPadding * 2) * .75f).toInt().coerceAtLeast(dp(24))
        val horizontalPadding = max(dp(10), height / 7)
        artwork.layoutParams = LayoutParams(artworkSize, artworkSize, Gravity.CENTER_VERTICAL).apply {
            leftMargin = horizontalPadding
        }
        val toggleSize = dp(40).coerceAtMost((height - verticalPadding * 2).coerceAtLeast(dp(34)))
        toggle.layoutParams = LayoutParams(toggleSize, toggleSize, Gravity.CENTER_VERTICAL or Gravity.END).apply {
            rightMargin = max(dp(8), verticalPadding)
        }
        textColumn.layoutParams = LayoutParams(-1, -1, Gravity.CENTER_VERTICAL).apply {
            leftMargin = horizontalPadding + artworkSize + max(dp(10), height / 8)
            rightMargin = if (toggleShown) toggleSize + max(dp(10), verticalPadding) else horizontalPadding
        }
        val radius = dp(artworkRadiusDp).coerceIn(0, artworkSize / 2).toFloat()
        artworkRadiusPx = radius
        artwork.background = rounded(Color.rgb(55, 55, 55), radius)
        artwork.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height,
                    if (artworkMorphRadius.isNaN()) radius else artworkMorphRadius)
            }
        }
        artwork.invalidateOutline()
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }
    private fun dp(value: Int) = (value * density + .5f).toInt()
    private fun dp(value: Float) = (value * density + .5f).toInt()
}

private class MiniPlayerPathDrawable(pathData: String, color: Int) : Drawable() {
    private val path = PathParser.createPathFromPathData(pathData)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0f || height <= 0f) return
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val scale = min(width, height) / 960f
        canvas.scale(scale, scale)
        canvas.translate((width / scale - 960f) / 2f, (height / scale - 960f) / 2f)
        canvas.drawPath(path, paint)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private const val ICON_PLAY =
    "M320 687V273Q320 256 332 244.5Q344 233 360 233Q365 233 370.5 234.5Q376 236 381 239L707 446Q716 452 720.5 461Q725 470 725 480Q725 490 720.5 499Q716 508 707 514L381 721Q376 724 370.5 725.5Q365 727 360 727Q344 727 332 715.5Q320 704 320 687ZM400 346 610 480 400 614ZM400 614 610 480 400 346Z"
private const val ICON_PAUSE =
    "M640 760Q607 760 583.5 736.5Q560 713 560 680V280Q560 247 583.5 223.5Q607 200 640 200Q673 200 696.5 223.5Q720 247 720 280V680Q720 713 696.5 736.5Q673 760 640 760ZM320 760Q287 760 263.5 736.5Q240 713 240 680V280Q240 247 263.5 223.5Q287 200 320 200Q353 200 376.5 223.5Q400 247 400 280V680Q400 713 376.5 736.5Q353 760 320 760Z"

/** Seconds per undamped cycle for the sideways spring home: quicker than the morph's. */
private const val OFFSET_RESPONSE = 0.32f

/** Above every sibling while a morph runs; CoverMorphLayer sits above this again. */
internal const val MORPH_Z = 10000f

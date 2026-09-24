// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package com.os4.musiccover

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.min
import org.json.JSONObject

/** Owns one mini player for each live keyguard shortcut host. */
object MiniPlayerRuntime {
    private data class Held(val left: View, val right: View, val controller: MiniPlayerController)

    /** One thread for scaling artwork, so a track change never decodes on the main thread. */
    internal val artWorker: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "MCMiniArt").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    private val controllers = WeakHashMap<ViewGroup, Held>()
    private var materialFailed = false
    private val selectionLock = Any()
    private val sessionSelection = MiniPlayerSessionSelection()
    private var prefs: SharedPreferences? = null
    private var loader: ClassLoader? = null
    private var lastRoot: WeakReference<View>? = null
    private var lastShortcutController: WeakReference<Any>? = null
    private var hooksInstalled = false

    private fun prefs(context: Context): SharedPreferences = prefs
        ?: context.applicationContext.getSharedPreferences("hmc_mini", Context.MODE_PRIVATE)
            .also { prefs = it }

    @JvmStatic fun configJson(context: Context): String = MiniPlayerConfig.fromPreferences(prefs(context))

    @JvmStatic fun applyConfig(context: Context, raw: String?) {
        MiniPlayerConfig.apply(prefs(context), raw)
        lastRoot?.get()?.let { root -> root.post { attach(root, lastShortcutController?.get()) } }
        refresh()
    }

    @JvmStatic fun refresh() {
        synchronized(controllers) { controllers.values.map { it.controller } }
            .forEach { runCatching { it.refresh() } }
    }

    /**
     * Each hook on its own: a build that renamed one class costs the feature that needed it,
     * not the rest of the mini player.
     */
    @JvmStatic fun install(classLoader: ClassLoader) {
        loader = classLoader
        if (hooksInstalled) return
        hooksInstalled = true
        runCatching {
            val cls = Xp.findClass(
                "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaHeaderView",
                classLoader,
            )
            Xp.hookAll(cls, "setVisibility") { chain ->
                val args = chain.args.toTypedArray()
                if (args.size == 1 && args[0] == View.VISIBLE && keepNativeHeaderInvisible()) {
                    args[0] = View.INVISIBLE
                }
                chain.proceed(args)
            }
        }.onFailure { Xp.log("MCMini: native header visibility hook unavailable: $it") }
        runCatching {
            val cls = Xp.findClass("com.android.keyguard.shortcut.MiuiShortcutController", classLoader)
            Xp.hookAll(cls, "addShortcutViews") { chain ->
                val result = chain.proceed()
                (chain.args.firstOrNull() as? View)?.let { scheduleInstall(it, chain.thisObject) }
                result
            }
        }.onFailure { Xp.log("MCMini: shortcut hook unavailable, no mini player: $it") }
        MiniPlayerScene.install(classLoader)
        installCardMaterialHooks(classLoader)
    }

    /** Bumped whenever the card is dressed; part of the pill's appearance key. */
    @Volatile internal var materialGeneration = 0
        private set

    /** Effects run on the main thread only; this counts how deep inside one another they are. */
    private var effectDepth = 0

    /** One material call the card got, to be made again on the pill with the pill put in. */
    private class Recorded(val method: Method, val args: Array<Any?>, val viewAt: Int)

    /** The card's material as it was last dressed: its background, then every call in order. */
    @Volatile private var cardRecipe: List<Recorded>? = null
    @Volatile private var cardBackground: android.graphics.drawable.Drawable.ConstantState? = null
    private var recording: ArrayList<Recorded>? = null
    private var recordTarget: View? = null
    private var recordDepth = 0

    /**
     * The card's material, copied call for call. NotificationViewEffectHelper has eight media
     * card effects - plain, blur, blur on the keyguard, glass, glass on the keyguard, glass on a
     * light wallpaper, glass in the full-screen AOD - and the keyguard ones run the plain blur
     * inside themselves. Copying three of them by hand, the first version saw that inner blur
     * as the card's material and dressed the pill in the shade's dark one. So nothing is
     * copied by hand any more: while an effect dresses the lock screen's card, every material
     * call made on its media_bg is recorded, and the pill gets the same calls, in order.
     */
    private fun installCardMaterialHooks(classLoader: ClassLoader) {
        val effects = runCatching {
            val helper = Xp.findClass(
                "com.android.systemui.statusbar.notification.style.vieweffect.NotificationViewEffectHelper",
                classLoader)
            val field = helper.declaredFields.first { it.name == "mediaViewEffectsMap" }
            field.isAccessible = true
            (field.get(null) as Map<*, *>).values.filterNotNull().map { it.javaClass }.distinct()
        }.getOrElse {
            Xp.log("MCMini: effect map unavailable, naming the effects instead: $it")
            listOf("MediaViewNormalEffect", "MediaViewBlurEffect", "MediaViewBlurOnKeyguardEffect",
                "MediaViewGlassEffect", "MediaViewGlassOnKeyguardEffect",
                "MediaViewGlassOnKeyguardLightWallPaperEffect", "MediaViewGlassFullAodEffect")
                .mapNotNull { name -> runCatching { Xp.findClass(
                    "com.android.systemui.statusbar.notification.style.vieweffect.$name", classLoader)
                }.getOrNull() }
        }
        effects.forEach { cls ->
            runCatching {
                Xp.hookAll(cls, "apply") { chain ->
                    val target = chain.args.firstOrNull() as? View
                    val card = Main.miniPlayerMediaHeader()
                    val mine = effectDepth == 0 && target != null && (card == null || target === card)
                    if (mine) {
                        recordTarget = Main.miniPlayerMediaBg(target)
                        recording = ArrayList()
                    }
                    effectDepth++
                    val result = try {
                        chain.proceed()
                    } finally {
                        effectDepth--
                    }
                    if (mine) {
                        val bg = recordTarget
                        cardRecipe = recording
                        cardBackground = bg?.background?.constantState
                        recording = null
                        recordTarget = null
                        if (cardEffect != cls.simpleName) Xp.log("MCMini: card material -> ${cls.simpleName}")
                        cardEffect = cls.simpleName
                        materialGeneration++
                        refresh()
                    }
                    result
                }
            }.onFailure { Xp.log("MCMini: ${cls.simpleName} hook unavailable: $it") }
        }
        // What the effects call on the card's background: record the outermost of them.
        listOf(
            "com.android.systemui.statusbar.notification.utils.NotificationUtil" to
                setOf("applyElementViewBlend"),
            "com.miui.systemui.util.MiGlassCompat" to setOf("setMiGlassBlurRadius",
                "setMiGlassCompat", "setMiGlassSdfMaxSizeCompat", "setMiViewMaterialTypeCompat"),
            "com.miui.systemui.util.MiBlurCompat" to setOf("addMiBackgroundBlendColorCompat",
                "clearMiBackgroundBlendColorCompat", "setBlurOutlineEnable", "setGlassOutlineEnable",
                "setMiBackgroundBlendColors", "setMiBackgroundBlendColors\$default",
                "setMiBackgroundBlendColorsNew\$default", "setMiBackgroundBlurModeCompat",
                "setMiBackgroundBlurRadiusCompat", "setMiBackgroundBlurScaleRatioCompat",
                "setMiSelfBlurTypeCompat", "setMiViewBlurModeCompat", "setPassWindowBlurEnabledCompat"),
        ).forEach { (className, names) ->
            runCatching {
                val cls = Xp.findClass(className, classLoader)
                cls.declaredMethods.filter {
                    it.name in names && java.lang.reflect.Modifier.isStatic(it.modifiers)
                        && it.parameterTypes.any { p -> View::class.java.isAssignableFrom(p) }
                }.forEach { m ->
                    m.isAccessible = true
                    Xp.api().hook(m).intercept { chain ->
                        val list = recording
                        val bg = recordTarget
                        if (list == null || bg == null || recordDepth > 0) return@intercept chain.proceed()
                        val args = chain.args.toTypedArray()
                        val at = args.indexOfFirst { it === bg }
                        if (at < 0) return@intercept chain.proceed()
                        recordDepth++
                        try {
                            chain.proceed()
                        } finally {
                            recordDepth--
                            list.add(Recorded(m, args, at))
                        }
                    }
                }
            }.onFailure { Xp.log("MCMini: material recorder on $className unavailable: $it") }
        }
    }

    /** Which effect dressed the card last, for the log and `op mini`. */
    @Volatile private var cardEffect = "none"

    /** The shortcut row has just been built: the pill goes between its two buttons. */
    private fun scheduleInstall(root: View, shortcutController: Any?) {
        lastRoot = WeakReference(root)
        lastShortcutController = shortcutController?.let(::WeakReference)
        fun doInstall() = runCatching { attach(root, shortcutController) }
            .onFailure { Xp.log("MCMini: attach failed: $it") }
        doInstall()
        root.post { doInstall() }
        root.postDelayed({ doInstall() }, 250L)
    }

    private fun attach(root: View, shortcutController: Any?) {
        val config = JSONObject(configJson(root.context))
        val host = root.rootView as? ViewGroup ?: return
        val old = synchronized(controllers) { controllers[host] }
        if (!config.getBoolean(MiniPlayerConfig.ENABLED)) {
            old?.controller?.destroy()
            synchronized(controllers) { controllers.remove(host) }
            return
        }
        val (left, right) = resolveShortcuts(shortcutController) ?: findShortcuts(root) ?: return
        if (left === right) return
        if (old != null && old.left === left && old.right === right) {
            old.controller.refresh()
            return
        }
        old?.controller?.destroy()
        val controller = MiniPlayerController(host, left, right, prefs(root.context),
            loader ?: root.context.classLoader)
        synchronized(controllers) { controllers[host] = Held(left, right, controller) }
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                synchronized(controllers) {
                    if (controllers[host]?.controller === controller) controllers.remove(host)
                }
                controller.destroy()
                host.removeOnAttachStateChangeListener(this)
            }
        })
    }

    private fun resolveShortcuts(controller: Any?): Pair<View, View>? = runCatching {
        val method = controller?.javaClass?.methods?.firstOrNull {
            it.name == "onSystemUIAction\$1" && it.parameterCount == 2
        } ?: return@runCatching null
        fun get(left: Boolean): View? = method.invoke(controller,
            android.os.Bundle().apply { putBoolean("isLeftShortcutView", left) },
            "getShortcutView") as? View
        val left = get(true) ?: return@runCatching null
        val right = get(false) ?: return@runCatching null
        left to right
    }.getOrNull()

    private fun findShortcuts(root: View): Pair<View, View>? {
        var left: View? = null
        var right: View? = null
        fun visit(view: View) {
            val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
            if (name == "shortcut_view_left_layout") left = view
            if (name == "shortcut_view_right_layout") right = view
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
        val l = left ?: return null
        val r = right ?: return null
        return l to r
    }

    @JvmStatic fun wantsNativeArtworkGesture(): Boolean =
        synchronized(controllers) { controllers.values.any { it.controller.wantsNativeArtworkGesture() } }

    /**
     * The card can be swiped down into the pill: in the sliding mode when it is up by choice,
     * and in the cover or lyrics - where the card stands in for the pill - whenever the pill
     * is what the lock screen goes back to.
     */
    @JvmStatic fun wantsNativeCardSwipe(): Boolean = live().any { it.wantsCardSwipe() }

    /** The pill is what the lock screen returns to, without starting a morph of its own. */
    @JvmStatic fun preferMini() = live().forEach { it.preferMini() }

    /**
     * The card was swiped down out of the cover or the lyrics. The next swipe up on the pill
     * goes back there rather than to the plain card - which of the two is the entry's own
     * question (LockLyrics.willAttachOnEntry keeps a two-finger dismissal), so it lands where
     * the swipe down left from. Forgotten on unlocking and with the session.
     */
    @Volatile private var restoreScene = false

    @JvmStatic fun rememberScene() { restoreScene = true }

    @JvmStatic fun forgetRestoreScene() { restoreScene = false }

    internal fun takeRestoreScene(): Boolean = restoreScene.also { restoreScene = false }

    internal fun restorePending(): Boolean = restoreScene

    private var routed: WeakReference<MiniPlayerView>? = null

    /**
     * Hands a whole gesture that starts on the pill to the pill and to nothing else. The pill's
     * own requestDisallowInterceptTouchEvent could not stop swipe-to-unlock, which the keyguard
     * decides above the view tree; this runs from the shade window's dispatch, before it.
     */
    @JvmStatic fun routeTouch(ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            routedX = ev.rawX
            routedY = ev.rawY
            routedDrag = false
            routedMorph = false
            routedTracker?.recycle()
            routedTracker = null
            // A morph still moving on its own is taken hold of where it is: a pull, a let-go and
            // a pull again, as often as the finger likes - it used to shut touches off until it
            // had settled, and a finger landing on it meanwhile went to swipe-to-unlock.
            val catcher = live().firstOrNull { it.catchableAt(ev.rawX, ev.rawY) }
            routed = catcher?.pill()?.let(::WeakReference)
            if (catcher != null && routed != null) {
                routedTracker = VelocityTracker.obtain()
                if (catchDrag(catcher, ev)) {
                    routedDrag = true
                    routedMorph = true
                } else routed = null
            }
            if (routed == null) {
                routed = live().firstNotNullOfOrNull { it.touchTarget(ev.rawX, ev.rawY) }
                    ?.let(::WeakReference)
                routedTracker = if (routed != null) VelocityTracker.obtain() else null
                // A pill still springing home is held where it is, and pulled on from there.
                routed?.get()?.let { pill ->
                    pill.holdNudge()
                    routedBaseX = pill.nudgeX
                    routedBaseY = pill.nudgeY
                }
            }
        }
        val target = routed?.get() ?: return false
        routedTracker?.addMovement(ev)
        val dx = ev.rawX - routedX
        val dy = ev.rawY - routedY
        val d = target.resources.displayMetrics.density
        if (!routedDrag) {
            val slop = ViewConfiguration.get(target.context).scaledTouchSlop
            if (action == MotionEvent.ACTION_MOVE && kotlin.math.hypot(dx, dy) > slop) {
                // From here the gesture moves the pill; it hears no more of it itself.
                routedDrag = true
                dispatchTo(target, ev, MotionEvent.ACTION_CANCEL)
            } else {
                dispatchTo(target, ev, action)
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) endRoute()
                return true
            }
        }
        val nx = pillNudgeX(dx, d)
        when (action) {
            MotionEvent.ACTION_MOVE -> {
                if (!routedMorph && -dy > DRAG_THRESHOLD_DP * d) {
                    // Pulled far enough up to mean it: in the sliding mode the pill starts
                    // turning into the card under the finger, the nudge handed over as it is.
                    val owner = live().firstOrNull { it.canDrag(fromNative = false) }
                    if (owner != null && beginDrag(fromNative = false, ev, startY = routedY)) {
                        routedMorph = true
                        target.clearNudge()
                    }
                }
                if (routedMorph) dragMove(ev, nx)
                else target.setNudge(routedBaseX + nx, routedBaseY + pillNudgeY(dy, d))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val tracker = routedTracker
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f
                val vy = tracker?.yVelocity ?: 0f
                val cancelled = action == MotionEvent.ACTION_CANCEL
                if (routedMorph) {
                    dragEnd(ev, cancelled)
                } else {
                    // A short drag is only ever a nudge: it springs back and does nothing.
                    // Sideways far enough (or flung) skips; up far enough goes back into the
                    // cover or the lyrics the pill came out of.
                    val sideways = kotlin.math.abs(dx) > kotlin.math.abs(dy)
                    val swipe = kotlin.math.max(48f * d, target.width * 0.15f)
                    val fling = 1200f * d
                    if (!cancelled && sideways
                        && (kotlin.math.abs(dx) >= swipe || kotlin.math.abs(vx) > fling)) {
                        target.performSkip(next = dx < 0f)
                    } else if (!cancelled && !sideways && restoreScene
                        && (-dy > DRAG_THRESHOLD_DP * d || -vy > fling)) {
                        if (takeRestoreScene()) Main.miniPlayerEnterCover()
                    }
                    target.springNudgeBack(vx, vy)
                }
                endRoute()
            }
        }
        return true
    }

    private var routedX = 0f
    private var routedY = 0f
    private var routedBaseX = 0f
    private var routedBaseY = 0f
    private var routedDrag = false
    private var routedMorph = false
    private var routedTracker: VelocityTracker? = null

    /** Up past this, a pull stops being a nudge and opens (or goes back into the scene). */
    private const val DRAG_THRESHOLD_DP = 72f

    private fun endRoute() {
        routed = null
        routedTracker?.recycle()
        routedTracker = null
    }

    /** Sideways: close to the finger at first, giving less and less. */
    private fun pillNudgeX(dx: Float, d: Float): Float =
        Math.copySign(MiniCardMorph.rubber(kotlin.math.abs(dx), 56f * d, 0.9f), dx)

    /** Up as the card end of a drag measures it, so the hand-over to the morph is seamless. */
    private fun pillNudgeY(dy: Float, d: Float): Float =
        if (dy < 0f) -MiniCardMorph.rubber(-dy, DRAG_NUDGE_DP * d)
        else MiniCardMorph.rubber(dy, 28f * d)

    private const val DRAG_NUDGE_DP = 48f

    private fun dispatchTo(target: View, ev: MotionEvent, action: Int) {
        val xy = IntArray(2).also(target::getLocationOnScreen)
        val copy = MotionEvent.obtain(ev)
        copy.action = action
        copy.offsetLocation(ev.rawX - ev.x - xy[0], ev.rawY - ev.y - xy[1])
        try {
            target.dispatchTouchEvent(copy)
        } finally {
            copy.recycle()
        }
    }

    // ------------------------------------------------------------ the finger-driven switch

    private var dragOwner: MiniPlayerController? = null
    private var dragFromNative = false
    private var dragStartY = 0f
    private var dragSpan = 1f
    private var dragThreshold = 1f
    private var dragNudgeLimit = 1f
    private var dragTracker: VelocityTracker? = null

    /**
     * A finger has started pulling the pill up, or the card down. Nothing is chosen yet: a
     * short pull only nudges the one it started on and springs back; past [dragThreshold] the
     * container starts changing shape under the finger; the lift decides.
     */
    @JvmStatic @JvmOverloads
    fun beginDrag(fromNative: Boolean, ev: MotionEvent, startY: Float = ev.rawY): Boolean {
        val owner = live().firstOrNull { it.canDrag(fromNative) } ?: return false
        val span = owner.dragSpan() ?: return false
        if (!owner.beginDragMorph(fromNative)) return false
        dragCaught = false
        val density = owner.density()
        dragOwner = owner
        dragFromNative = fromNative
        dragStartY = startY
        dragSpan = span
        dragThreshold = DRAG_THRESHOLD_DP * density
        dragNudgeLimit = DRAG_NUDGE_DP * density
        dragTracker?.recycle()
        dragTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
        return true
    }

    @JvmStatic @JvmOverloads
    fun dragMove(ev: MotionEvent, nudgeX: Float = 0f) {
        val owner = dragOwner ?: return
        dragTracker?.addMovement(ev)
        if (dragCaught) {
            owner.dragTo(banded(caughtProgress(ev.rawY)), caughtNudge, caughtNudgeX + nudgeX)
            return
        }
        val (progress, nudge) = dragPose(ev.rawY)
        owner.dragTo(progress, nudge, nudgeX)
    }

    private var dragCaught = false
    private var caughtBase = 0f
    private var caughtNudge = 0f
    private var caughtNudgeX = 0f
    private var caughtUp = 1f
    private var caughtScene = false

    /** Takes a running morph under the finger: progress follows it from where it was. */
    private fun catchDrag(owner: MiniPlayerController, ev: MotionEvent): Boolean {
        val span = owner.dragSpan() ?: return false
        val grab = owner.grabMorph() ?: return false
        dragOwner = owner
        dragCaught = true
        dragStartY = ev.rawY
        dragSpan = span
        caughtBase = grab.progress
        caughtNudge = grab.nudge
        caughtNudgeX = grab.nudgeX
        caughtScene = owner.morphIsScene()
        // Which way is toward the card: up from the pill, whatever the layout.
        caughtUp = if (owner.cardAbovePill()) 1f else -1f
        dragTracker?.recycle()
        dragTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
        return true
    }

    private fun caughtProgress(y: Float) = caughtBase + caughtUp * (dragStartY - y) / dragSpan

    /** Past either end, the same resistance as a fresh pull. */
    private fun banded(p: Float): Float = when {
        p > 1f -> 1f + MiniCardMorph.rubber(p - 1f, 0.2f)
        p < 0f -> -MiniCardMorph.rubber(-p, 0.2f)
        else -> p
    }

    @JvmStatic fun dragEnd(ev: MotionEvent, cancelled: Boolean) {
        val owner = dragOwner ?: return
        dragOwner = null
        val tracker = dragTracker
        dragTracker = null
        tracker?.addMovement(ev)
        tracker?.computeCurrentVelocity(1000)
        val vy = tracker?.yVelocity ?: 0f
        tracker?.recycle()
        if (dragCaught) {
            dragCaught = false
            val p = caughtProgress(ev.rawY)
            val v = -caughtUp * vy / dragSpan
            val toNative = if (cancelled) p > 0.5f else p + v * 0.18f > 0.5f
            if (!caughtScene) {
                owner.releaseDrag(toNative, v)
                return
            }
            // A scene's morph, caught: where it is let go decides the scene. Up with the cover
            // already off (it was leaving) goes back in; down with it on (it was coming) goes
            // back out; the same way as it was going just carries on.
            owner.releaseMorph(toNative, v)
            if (toNative && !Main.coverModeOn()) {
                if (takeRestoreScene()) Main.miniPlayerTurnScene(true)
            } else if (!toNative && Main.coverModeOn()) {
                preferMini()
                rememberScene()
                Main.miniPlayerTurnScene(false)
            }
            return
        }
        // Distance and speed toward the far end, and the progress that distance has bought.
        val pulled = pulled(ev.rawY)
        val speed = if (dragFromNative) vy else -vy
        val engaged = ((pulled - dragThreshold) / dragSpan).coerceAtLeast(0f)
        // Where a lift like this is headed: past half way, or flung past it, opens.
        val projected = engaged + speed / dragSpan * 0.18f
        val far = !cancelled && projected > 0.5f
        val toNative = if (dragFromNative) !far else far
        val velocity = (if (dragFromNative) -speed else speed) / dragSpan
        owner.releaseDrag(toNative, if (engaged > 0f) velocity else 0f)
    }

    /** Pixels the finger has gone toward the far end (negative: the wrong way). */
    private fun pulled(y: Float) = if (dragFromNative) y - dragStartY else dragStartY - y

    /**
     * Finger to (progress, nudge). Short of the threshold only the nudge moves, rubber-banded;
     * past it the progress follows the finger 1:1 over the span while the nudge the threshold
     * left hands over to it, so crossing is seamless; past the far end, rubber again.
     */
    private fun dragPose(y: Float): Pair<Float, Float> {
        val d = pulled(y)
        val base = if (dragFromNative) 1f else 0f
        val dir = if (dragFromNative) -1f else 1f
        if (d <= dragThreshold) {
            val nudge = if (d >= 0f) MiniCardMorph.rubber(d, dragNudgeLimit)
                else -MiniCardMorph.rubber(-d, dragNudgeLimit * 0.5f)
            return base to nudge
        }
        val held = MiniCardMorph.rubber(dragThreshold, dragNudgeLimit)
        var p = (d - dragThreshold) / dragSpan
        if (p > 1f) p = 1f + MiniCardMorph.rubber(p - 1f, 0.2f)
        val nudge = held * (1f - p.coerceIn(0f, 1f))
        return (base + dir * p) to nudge
    }

    @JvmStatic fun onNativeCardSwipeDown() {
        synchronized(controllers) { controllers.values.map { it.controller } }
            .firstOrNull { it.wantsNativeArtworkGesture() }
            ?.showMiniPlayer()
    }

    /** The torch button: the lock screen element the pill sits between, for probes. */
    @JvmStatic fun shortcutView(): View? = synchronized(controllers) {
        controllers.values.firstOrNull()?.left
    }

    /** For `op mini`: the pill, the card, and the torch button's chain as they are right now. */
    @JvmStatic fun describe(): String {
        val sb = StringBuilder("material=$cardEffect calls=${cardRecipe?.size} " +
            "aod=${MiniPlayerScene.aodActive}")
        synchronized(controllers) { controllers.values.toList() }.forEach { held ->
            sb.append(" || ").append(held.controller.describe())
            sb.append(" || torch:")
            var v: View? = held.left
            while (v != null) {
                val id = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
                    ?: v.javaClass.simpleName
                sb.append(' ').append(id).append("[v=").append(v.visibility)
                    .append(" a=").append("%.2f".format(v.alpha))
                    .append(" ta=").append("%.2f".format(v.transitionAlpha))
                if (v.scaleX != 1f || v.scaleY != 1f) sb.append(" s=").append("%.3f".format(v.scaleY))
                    .append("@").append(v.pivotY.toInt())
                if (v.translationY != 0f) sb.append(" ty=").append(v.translationY.toInt())
                sb.append(']')
                v = v.parent as? View
            }
        }
        return sb.toString()
    }

    @JvmStatic fun nativeHeaderHidden(): Boolean = synchronized(controllers) {
        controllers.values.any { it.controller.nativeHeaderHidden() }
    }

    private fun live(): List<MiniPlayerController> =
        synchronized(controllers) { controllers.values.map { it.controller } }

    /**
     * A tap from the mini player into the cover or lyrics: the pill becomes the card that the
     * scene keeps. False when the mini player is not what is showing, and the OEM card's own
     * route applies.
     */
    @JvmStatic fun prepareSceneEntry(): Boolean =
        live().any { it.beginTransition(toNative = true, scene = true) }

    /** Out of the cover or lyrics: the card shrinks back into the pill. */
    @JvmStatic fun prepareSceneExit(): Boolean =
        live().any { it.beginTransition(toNative = false, scene = true) }

    @JvmStatic fun transitionActive(): Boolean = live().any { it.transitionActive() }

    /** Where a flight from the cover lands, and the corner it lands in. */
    @JvmStatic @JvmName("artworkRestBox") internal fun artworkRestBox(): CoverMorphMotion.Box? =
        live().firstNotNullOfOrNull { it.artworkRestBox() }

    @JvmStatic fun artworkRadius(): Float =
        live().firstNotNullOfOrNull { it.artworkRadius() } ?: 0f

    /** CoverMorphLayer draws the artwork itself while it flies to or from the cover. */
    @JvmStatic fun setArtBridged(bridged: Boolean) = live().forEach { it.setArtBridged(bridged) }

    private fun keepNativeHeaderInvisible(): Boolean = synchronized(controllers) {
        controllers.values.any { it.controller.hardSuppressionActive() }
    }

    internal fun observeSession(token: Any, packageName: String): Boolean {
        val changed = synchronized(selectionLock) { sessionSelection.observe(token) }
        if (changed) {
            restoreScene = false
            Xp.log("MCMini: media session -> $packageName@${Integer.toHexString(token.hashCode())}; " +
                "dynamic choice reset to mini")
        }
        return changed
    }

    internal fun nativeRequested(token: Any?): Boolean = synchronized(selectionLock) {
        sessionSelection.nativeRequestedFor(token)
    }

    /** The choice is made first; the morph then runs toward it, or turns round if one runs. */
    internal fun selectNative(token: Any) {
        val changed = synchronized(selectionLock) { sessionSelection.requestNative(token) }
        if (changed) Xp.log("MCMini: dynamic choice -> native")
        live().forEach { it.beginTransition(toNative = true, scene = false) }
        refresh()
    }

    internal fun selectMini(token: Any) {
        val changed = synchronized(selectionLock) { sessionSelection.requestMini(token) }
        if (changed) Xp.log("MCMini: dynamic choice -> mini")
        live().forEach { it.beginTransition(toNative = false, scene = false) }
        refresh()
    }

    internal fun chooseNative(token: Any) {
        val changed = synchronized(selectionLock) { sessionSelection.requestNative(token) }
        if (changed) Xp.log("MCMini: dynamic choice -> native (pulled)")
    }

    internal fun chooseMini(token: Any) {
        val changed = synchronized(selectionLock) { sessionSelection.requestMini(token) }
        if (changed) Xp.log("MCMini: dynamic choice -> mini (out of the scene)")
    }

    internal fun resetDynamicChoice(reason: String) {
        val changed = synchronized(selectionLock) { sessionSelection.resetChoice() }
        if (changed) Xp.log("MCMini: dynamic choice reset to mini ($reason)")
    }

    internal fun endSession(token: Any, reason: String) {
        val changed = synchronized(selectionLock) { sessionSelection.end(token) }
        if (changed) Xp.log("MCMini: media session ended ($reason)")
    }

    /**
     * The pill's material: the card's recorded calls replayed on the pill's layer, with the
     * pill itself made the blur container they expect above them.
     *
     * HyperOS notification material has two layers. The card's background is only an element:
     * it tints and shapes a blur that an ancestor container provides, and that container is
     * what blurs through the window to the wallpaper. The pill hangs off the window root with
     * no such ancestor - the element alone drew nothing at all - so the pill is made a
     * container the way ElementSurfaceModel.updateBlurContainer makes one.
     */
    internal fun material(view: ImageView, classLoader: ClassLoader) {
        val ctx = view.context
        runCatching {
            val res = ctx.resources
            fun id(type: String, name: String): Int =
                res.getIdentifier(name, type, "com.android.systemui").also {
                    require(it != 0) { "no $type/$name" }
                }
            val util = Class.forName("com.android.systemui.statusbar.notification.utils.NotificationUtil",
                false, classLoader)
            (view.parent as? View)?.let { container ->
                // Blur mode 1, the notification container radius, and through the window.
                util.getMethod("applyContainerViewBlur", Context::class.java, View::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType).invoke(null, ctx, container, 0, 0, false)
                View::class.java.getMethod("setMiGlassBlurRadius", Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType).invoke(container,
                    res.getDimensionPixelSize(id("dimen", "notification_glass_small_blur_max_radius")),
                    res.getDimensionPixelSize(id("dimen", "notification_glass_big_blur_max_radius")))
                runCatching {
                    Class.forName("miuix.core.util.MiuiBlurUtils", false, classLoader)
                        .getMethod("setMiBlurWinType", View::class.java).invoke(null, container)
                }
            }
            val recipe = cardRecipe ?: error("the card has not been dressed yet")
            view.setImageDrawable(null)
            view.background = cardBackground?.newDrawable(res)?.mutate()
            recipe.forEach { call ->
                val args = call.args.copyOf()
                args[call.viewAt] = view
                call.method.invoke(null, *args)
            }
        }.onFailure { error ->
            val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
            if (!materialFailed) {
                materialFailed = true
                Xp.log("MCMini: card material unavailable, plain fill instead: $cause")
            }
            view.background = null
            view.setImageDrawable(GradientDrawable().apply { setColor(0x9E1F2324.toInt()) })
        }
    }
}

private class MiniPlayerController(
    private val host: ViewGroup,
    private val left: View,
    private val right: View,
    private val prefs: SharedPreferences,
    private val loader: ClassLoader,
) {
    private val context = host.context
    private val handler = Handler(Looper.getMainLooper())
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var player: MiniPlayerView? = null
    private var controller: MediaController? = null
    private var header: WeakReference<View>? = null
    private val suppressedHeaders = WeakViewOverrideRegistry<View>()
    private var nativeSuppressionRequested = false
    private var morph: MiniCardMorph? = null
    private var morphScene = false
    private var artBridged = false
    private var lastTrack = ""
    private var cachedCover: Bitmap? = null
    private var refreshPosted = false
    private var positionPosted = false
    private var configuredHeightDp = 72f
    private var config = JSONObject(MiniPlayerConfig.defaultJson())
    private var forceHeaderRefresh = true
    private var lastPresentationLog = ""
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        configStale = true
        scheduleRefresh()
    }
    /** The config is read from preferences only when they change, not on every refresh. */
    private var configStale = true
    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { scheduleRefresh() }
    private val mediaListener = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = scheduleRefresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = scheduleRefresh()
        override fun onSessionDestroyed() = scheduleRefresh()
    }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        updateVisibility()
        if (player?.visibility == View.VISIBLE) {
            position()
            followShortcuts()
        }
        true
    }

    private val followRelative = Matrix()
    private val followHost = Matrix()
    private var followRoot: WeakReference<View>? = null
    private var rowHeldOff = false
    private var shortcutRow: WeakReference<View>? = null

    /**
     * The torch and camera's motion and fade, on the pill, every frame. The pill hangs off the
     * window root, outside everything the OEM animates. Swiping up, keyguard_bottom_area fades
     * to a transitionAlpha of 0.05 and zooms to 0.9 (`op alphasweep`, 2026-09-24) - but the two
     * buttons also move on their own inside it, drawing in toward each other and rising less
     * than the row's zoom alone would carry them; following the row, the pill (filmed) rose
     * faster than they did and kept its width while they closed in. So the pill follows the
     * buttons themselves: its centre on the midpoint of where the two are drawn, its scale the
     * ratio of their drawn spacing to their resting spacing.
     *
     * In the doze the buttons are put away while the pill stays; there it takes only
     * keyguard_root_view's own zoom and fade, and it goes back to the buttons once they have
     * faded all the way back in after the wake - switching earlier, it dropped with their fade
     * and came back: a flash.
     */
    private fun followShortcuts() {
        val view = player ?: return
        if (morph != null) {
            view.setAnimationMatrix(null)
            return
        }
        if (shortcutRow?.get()?.isAttachedToWindow != true) findRow()?.let { shortcutRow = WeakReference(it) }
        val row = shortcutRow?.get() ?: return
        val keyguardRoot = followRoot?.get()
        followHost.reset()
        host.transformMatrixToGlobal(followHost)
        if (!followHost.invert(hostInverse)) return
        var fade = 1f
        var rowFade = 1f
        var aboveRoot = false
        var v: View? = row
        while (v != null && v !== host) {
            if (v === keyguardRoot) aboveRoot = true
            if (aboveRoot) fade *= v.alpha * v.transitionAlpha
            else rowFade *= v.alpha * v.transitionAlpha
            v = v.parent as? View
        }
        if (MiniPlayerScene.aodActive) rowHeldOff = true
        else if (rowHeldOff && rowFade >= 0.99f) rowHeldOff = false
        val matrix = followRelative
        if (!rowHeldOff && left.isShown && right.isShown) {
            fade *= rowFade
            // Where each button is drawn now and where it rests, both in the host's pixels.
            if (!drawnCentre(left, drawnL) || !drawnCentre(right, drawnR)) return
            restCentre(left, restL)
            restCentre(right, restR)
            val restSpan = restR[0] - restL[0]
            if (kotlin.math.abs(restSpan) < 1f) return
            var scale = (drawnR[0] - drawnL[0]) / restSpan
            var mx = (drawnL[0] + drawnR[0]) / 2f - (restL[0] + restR[0]) / 2f
            var my = (drawnL[1] + drawnR[1]) / 2f - (restL[1] + restR[1]) / 2f
            // At rest the arithmetic gives 0.99999 and a hair of shift; writing that every frame
            // invalidated the pill every frame - a redraw that never stopped, blur and all.
            if (kotlin.math.abs(scale - 1f) < 0.0005f) scale = 1f
            if (kotlin.math.abs(mx) < 0.25f) mx = 0f
            if (kotlin.math.abs(my) < 0.25f) my = 0f
            matrix.reset()
            matrix.setTranslate(-(restL[0] + restR[0]) / 2f, -(restL[1] + restR[1]) / 2f)
            matrix.postScale(scale, scale)
            matrix.postTranslate((restL[0] + restR[0]) / 2f + mx, (restL[1] + restR[1]) / 2f + my)
        } else if (keyguardRoot != null) {
            // The keyguard's own zoom only, as a matrix from its untransformed slot.
            matrix.reset()
            keyguardRoot.transformMatrixToGlobal(matrix)
            matrix.postConcat(hostInverse)
            restOrigin(keyguardRoot, restL)
            matrix.preTranslate(-restL[0], -restL[1])
        } else {
            matrix.reset()
        }
        // Into the pill's own frame: conjugated by its slot in the host.
        matrix.preTranslate(view.left.toFloat(), view.top.toFloat())
        matrix.postTranslate(-view.left.toFloat(), -view.top.toFloat())
        // Only a real change is written: each write invalidates the pill.
        matrix.getValues(followValues)
        if (!followValues.contentEquals(lastFollow)) {
            System.arraycopy(followValues, 0, lastFollow, 0, 9)
            if (matrix.isIdentity) view.setAnimationMatrix(null) else view.setAnimationMatrix(matrix)
        }
        if (kotlin.math.abs(view.transitionAlpha - fade) > 0.002f) view.transitionAlpha = fade
    }

    private val hostInverse = Matrix()
    private val scratch = Matrix()
    private val drawnL = FloatArray(2)
    private val drawnR = FloatArray(2)
    private val restL = FloatArray(2)
    private val restR = FloatArray(2)
    private val followValues = FloatArray(9)
    private val lastFollow = FloatArray(9)

    /** A view's centre as drawn, every ancestor's transform included, in host pixels. */
    private fun drawnCentre(v: View, out: FloatArray): Boolean {
        if (!v.isAttachedToWindow || v.width <= 0) return false
        scratch.reset()
        v.transformMatrixToGlobal(scratch)
        scratch.postConcat(hostInverse)
        out[0] = v.width / 2f
        out[1] = v.height / 2f
        scratch.mapPoints(out)
        return true
    }

    /** The same centre with no transform anywhere: layout positions only. */
    private fun restCentre(v: View, out: FloatArray = FloatArray(2)): FloatArray {
        restOrigin(v, out)
        out[0] += v.width / 2f
        out[1] += v.height / 2f
        return out
    }

    private fun restOrigin(v: View, out: FloatArray) {
        var x = 0f
        var y = 0f
        var cur: View? = v
        while (cur != null && cur !== host) {
            val parent = cur.parent as? View
            x += cur.left - (parent?.scrollX ?: 0)
            y += cur.top - (parent?.scrollY ?: 0)
            cur = parent
        }
        out[0] = x
        out[1] = y
    }

    /** keyguard_bottom_area above the torch, and keyguard_root_view above that. */
    private fun findRow(): View? {
        var row: View? = null
        var v: View? = left
        while (v != null && v !== host) {
            val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
            if (name == "keyguard_bottom_area" && row == null) row = v
            if (name == "keyguard_root_view") followRoot = WeakReference(v)
            v = v.parent as? View
        }
        return row ?: left.parent as? View
    }

    init {
        host.clipChildren = false
        host.clipToPadding = false
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        host.viewTreeObserver.addOnPreDrawListener(preDraw)
        left.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedulePosition() }
        right.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedulePosition() }
        runCatching { sessions?.addOnActiveSessionsChangedListener(sessionListener, null, handler) }
        refresh()
    }

    fun destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        runCatching { host.viewTreeObserver.removeOnPreDrawListener(preDraw) }
        runCatching { sessions?.removeOnActiveSessionsChangedListener(sessionListener) }
        runCatching { controller?.unregisterCallback(mediaListener) }
        morph?.cancel()
        restoreHeader()
        player?.let { runCatching { host.removeView(it) } }
        player = null
        controller = null
        handler.removeCallbacksAndMessages(null)
    }

    fun isShowing(): Boolean = player?.visibility == View.VISIBLE

    fun describe(): String {
        val v = player ?: return "no pill"
        val xy = IntArray(2).also(v::getLocationOnScreen)
        val h = header?.get()
        return "pill v=${v.visibility} a=${v.alpha} ta=${v.transitionAlpha} at=${xy[0]},${xy[1]} " +
            "${v.width}x${v.height} morph=${morph != null} header=" +
            (if (h == null) "none" else "v=${h.visibility} a=${h.alpha} ta=${h.transitionAlpha}") +
            " last=[$lastPresentationLog]"
    }
    fun nativeHeaderHidden(): Boolean = nativeSuppressionRequested
    fun visibleHeightDp(): Float = configuredHeightDp

    fun transitionActive(): Boolean = morph != null
    fun hardSuppressionActive(): Boolean = nativeSuppressionRequested && morph == null

    private val morphListener = object : MiniCardMorph.Listener {
        /** A scene exit hands the pill back only once the cover has let go of the lock screen. */
        override fun canSettle(morph: MiniCardMorph, toNative: Boolean) =
            toNative || !morphScene || !Main.coverSceneActive()

        override fun artBridged() = artBridged

        override fun onSettled(morph: MiniCardMorph, toNative: Boolean, completed: Boolean) {
            if (this@MiniPlayerController.morph !== morph) return
            this@MiniPlayerController.morph = null
            morphScene = false
            Xp.log("MCMini: container morph ${if (completed) "landed" else "cancelled"} " +
                "at ${if (toNative) "native" else "mini"}")
            position()
            updateVisibility()
        }
    }

    /**
     * Starts, or turns round, the container morph. A dynamic switch goes wherever the selection
     * now points; a scene route needs the mini player to be the selected presentation.
     */
    fun beginTransition(toNative: Boolean, scene: Boolean): Boolean {
        morph?.let { running ->
            running.aim(toNative)
            morphScene = scene
            return true
        }
        val view = player ?: return false
        val token = controller?.sessionToken ?: return false
        // With both shown side by side (mode 0) the card is already there: nothing to become.
        if (scene && (config.getInt(MiniPlayerConfig.MEDIA_MODE) == 0
                || MiniPlayerRuntime.nativeRequested(token))) return false
        if (!view.isAttachedToWindow || !Main.miniPlayerMorphAllowed()) return false
        val native = transitionHeader() ?: return false
        view.visibility = View.VISIBLE
        updateNativeSuppression(false)
        position()
        val next = MiniCardMorph(view, native, toNative, morphListener)
        morph = next
        morphScene = scene
        if (!next.start()) {
            morph = null
            morphScene = false
            Xp.log("MCMini: no geometry for a morph; switching in place")
            updateVisibility()
            return false
        }
        return true
    }

    fun pill(): MiniPlayerView? = player

    /** A morph moving on its springs under this point - the switch's, or a scene's. */
    fun catchableAt(x: Float, y: Float): Boolean = morph?.catchableAt(x, y) == true

    /** Whether the running morph belongs to a cover or lyrics entry or exit. */
    fun morphIsScene(): Boolean = morph != null && morphScene

    /** Lets go of a caught scene morph toward an end, the scene itself decided by the caller. */
    fun releaseMorph(toNative: Boolean, velocity: Float) {
        morph?.release(toNative, velocity)
    }

    fun grabMorph(): MiniCardMorph.Grab? = morph?.grab()

    fun cardAbovePill(): Boolean {
        val pill = player?.restBoxOnScreen() ?: return true
        val card = transitionHeader() ?: return true
        val xy = IntArray(2).also(card::getLocationOnScreen)
        return xy[1] <= pill.y
    }

    /** Where a cover flight starts or lands: the artwork as a running morph draws it, else its slot. */
    fun artworkRestBox(): CoverMorphMotion.Box? = morph?.artworkBox() ?: player?.artworkRestBoxOnScreen()

    fun artworkRadius(): Float? = player?.artworkRestRadius()

    fun setArtBridged(bridged: Boolean) {
        artBridged = bridged
        player?.setArtworkHidden(bridged)
    }

    private fun transitionHeader(): View? {
        val current = header?.get()?.takeIf { it.isAttachedToWindow }
            ?: Main.miniPlayerMediaHeader()
        if (current != null) header = WeakReference(current)
        return current
    }

    fun wantsNativeArtworkGesture(): Boolean {
        val token = controller?.sessionToken
        return config.getBoolean(MiniPlayerConfig.ENABLED) &&
            config.getInt(MiniPlayerConfig.MEDIA_MODE) == 2 &&
            MiniPlayerRuntime.nativeRequested(token) &&
            Main.miniPlayerCanShow()
    }

    fun density(): Float = context.resources.displayMetrics.density

    /** The dynamic switch can be pulled from this end right now. */
    fun canDrag(fromNative: Boolean): Boolean {
        if (!config.getBoolean(MiniPlayerConfig.ENABLED) || morph != null) return false
        val mode = config.getInt(MiniPlayerConfig.MEDIA_MODE)
        // The sliding mode; or, in any mode that hides the card, a pull back into the scene.
        val backIntoScene = !fromNative && mode != 0 && MiniPlayerRuntime.restorePending()
        if (mode != 2 && !backIntoScene) return false
        val token = controller?.sessionToken ?: return false
        if (!Main.miniPlayerMorphAllowed()) return false
        return MiniPlayerRuntime.nativeRequested(token) == fromNative
    }

    /** How far apart the pill and the card are: what a full pull covers. */
    fun dragSpan(): Float? {
        val pill = player?.restBoxOnScreen() ?: return null
        val card = transitionHeader() ?: return null
        val xy = IntArray(2).also(card::getLocationOnScreen)
        return kotlin.math.abs(pill.y - xy[1]).coerceAtLeast(160f * density())
    }

    fun beginDragMorph(fromNative: Boolean): Boolean {
        val view = player ?: return false
        val native = transitionHeader() ?: return false
        view.visibility = View.VISIBLE
        updateNativeSuppression(false)
        position()
        // Starts at the end it was pulled from; release() aims it once the lift decides.
        val next = MiniCardMorph(view, native, !fromNative, morphListener)
        morph = next
        morphScene = false
        if (!next.startDragging()) {
            morph = null
            updateVisibility()
            return false
        }
        return true
    }

    fun dragTo(progress: Float, nudge: Float, nudgeX: Float) {
        morph?.drag(progress, nudge, nudgeX)
    }

    /** The lift: the choice follows where the container is going, then the springs. */
    fun releaseDrag(toNative: Boolean, velocity: Float) {
        val token = controller?.sessionToken
        val backIntoScene = toNative && MiniPlayerRuntime.takeRestoreScene()
        if (!backIntoScene && token != null) {
            if (toNative) MiniPlayerRuntime.chooseNative(token) else MiniPlayerRuntime.chooseMini(token)
        }
        morph?.release(toNative, velocity)
        if (backIntoScene) {
            // Back into the cover or the lyrics the moment the lift says it is going up - not
            // once the card has landed: the clock, the wallpaper and the artwork start with the
            // card's last stretch. The scene's own entry finds this morph running and takes it
            // over (beginTransition), and its artwork leaves from where the pill's is drawn.
            Main.miniPlayerEnterCover()
        }
    }

    fun wantsCardSwipe(): Boolean {
        if (!config.getBoolean(MiniPlayerConfig.ENABLED)) return false
        val current = controller ?: return false
        if (!isUsable(current)) return false
        val mode = config.getInt(MiniPlayerConfig.MEDIA_MODE)
        return if (Main.coverModeOn()) mode != 0 else wantsNativeArtworkGesture()
    }

    fun preferMini() {
        controller?.sessionToken?.let { token ->
            MiniPlayerRuntime.chooseMini(token)
        }
    }

    /**
     * The pill, when it is showing, takes touches and the point is on it - or near it: the
     * pill is a small target, and a swipe up that began just off it went to swipe-to-unlock.
     * The margin stops short of the torch and camera buttons, which keep their own touches.
     */
    fun touchTarget(x: Float, y: Float): MiniPlayerView? {
        val view = player ?: return null
        val d = density()
        val xy = IntArray(2).also(view::getLocationOnScreen)
        // From just above the pill to the bottom of the screen - a thumb starts a swipe low -
        // and across to the torch and camera, which keep their own touches.
        val lx = buttonEdge(left, inner = true) ?: (xy[0] - 8f * d)
        val rx = buttonEdge(right, inner = false) ?: (xy[0] + view.width + 8f * d)
        val inside = x >= minOf(lx, xy[0].toFloat()) && x < maxOf(rx, xy[0] + view.width.toFloat())
            && y >= xy[1] - 36f * d && y < host.height
        if (!inside || onButton(left, x, y) || onButton(right, x, y)) return null
        val refused = when {
            view.visibility != View.VISIBLE -> "hidden"
            !view.isAttachedToWindow -> "detached"
            morph != null -> "morph running"
            !view.acceptsTouch() -> "not interactive (canShow=${Main.miniPlayerCanShow()} " +
                "aod=${MiniPlayerScene.aodActive})"
            else -> null
        }
        if (refused != null) {
            // The finger was meant for the pill and went to swipe-to-unlock: say why.
            Xp.log("MCMini: down on the pill not taken: $refused")
            return null
        }
        return view
    }

    /**
     * Right above the lock screen's own layer, not at the top of the window: the shade and the
     * control centre are drawn after it, and their blur covers the pill as it covers the torch
     * and camera. Added last, the pill sat above both, sharp - and was hidden for them instead.
     * A morph lifts it over the card for as long as it runs (MiniPlayerView.beginMorph).
     */
    private fun lockScreenLayerIndex(): Int {
        var v: View = left
        while (true) {
            val parent = v.parent as? View ?: return host.childCount
            if (parent === host) {
                val at = host.indexOfChild(v)
                return if (at < 0) host.childCount else at + 1
            }
            v = parent
        }
    }

    /** The torch's right edge or the camera's left edge, where the pill's touch area stops. */
    private fun buttonEdge(button: View, inner: Boolean): Float? {
        if (!button.isShown || button.width <= 0) return null
        val xy = IntArray(2).also(button::getLocationOnScreen)
        return if (inner) (xy[0] + button.width).toFloat() else xy[0].toFloat()
    }

    private fun onButton(button: View, x: Float, y: Float): Boolean {
        if (!button.isShown || button.width <= 0) return false
        val xy = IntArray(2).also(button::getLocationOnScreen)
        return x >= xy[0] && x < xy[0] + button.width && y >= xy[1] && y < xy[1] + button.height
    }

    fun showMiniPlayer() {
        if (!wantsNativeArtworkGesture()) return
        controller?.sessionToken?.let(MiniPlayerRuntime::selectMini)
    }

    fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { scheduleRefresh(); return }
        runCatching { refreshUnsafe() }.onFailure { Xp.log("MCMini: refresh failed: $it") }
    }

    private fun scheduleRefresh() {
        if (refreshPosted) return
        refreshPosted = true
        handler.post { refreshPosted = false; refresh() }
    }

    private fun refreshUnsafe() {
        if (configStale) {
            configStale = false
            config = JSONObject(MiniPlayerConfig.fromPreferences(prefs))
            configuredHeightDp = MiniPlayerConfig.visibleHeightDp(config.toString())
        }
        val config = this.config
        forceHeaderRefresh = true
        val enabled = config.getBoolean(MiniPlayerConfig.ENABLED)
        val mediaMode = config.getInt(MiniPlayerConfig.MEDIA_MODE)
        if (!enabled) MiniPlayerRuntime.resetDynamicChoice("feature disabled")
        else if (mediaMode != 2) MiniPlayerRuntime.resetDynamicChoice("dynamic mode left")
        if (!enabled) {
            player?.visibility = View.GONE
            restoreHeader()
            return
        }
        // Active-session lists can be momentarily empty while a player advances its queue. Keep a
        // still-usable controller instead of flashing the vendor card during that bookkeeping gap.
        val chosen = chooseController() ?: controller?.takeIf(::isUsable)
        if (chosen?.sessionToken != controller?.sessionToken) {
            val previous = controller
            if (chosen == null && previous != null && !isUsable(previous)) {
                MiniPlayerRuntime.endSession(previous.sessionToken, "playback inactive")
            }
            runCatching { previous?.unregisterCallback(mediaListener) }
            controller = chosen
            runCatching { chosen?.registerCallback(mediaListener, handler) }
            cachedCover = null
            // Another player: its artwork, not the last one's.
            thumbShown = null
            lastTrack = ""
        }
        val current = controller
        if (current == null || !isUsable(current)) {
            player?.visibility = View.GONE
            restoreHeader()
            return
        }
        if (MiniPlayerRuntime.observeSession(current.sessionToken, current.packageName)) {
            cachedCover = null
            // Another player: its artwork, not the last one's.
            thumbShown = null
            lastTrack = ""
        }
        val metadata = current.metadata
        val track = current.packageName + "|" +
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (track != lastTrack) {
            if (lastTrack.isNotEmpty()) {
                Xp.log("MCMini: track changed; preserving dynamic choice=" +
                    if (MiniPlayerRuntime.nativeRequested(current.sessionToken)) "native" else "mini")
            }
            lastTrack = track
            cachedCover = null
        }
        val view = player ?: MiniPlayerView(context).also {
            player = it
            host.addView(it, lockScreenLayerIndex(), ViewGroup.LayoutParams(1, 1))
        }
        val cover = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: cachedCover?.takeUnless { it.isRecycled }
            ?: (if (!nativeSuppressionRequested) Main.cardThumbnail() else null)
        if (cover != null && !cover.isRecycled) cachedCover = cover
        val shown = thumbnailFor(cover, view)
        view.bind(
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank { "正在播放" },
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
                .ifBlank { metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty() },
            shown,
            current.playbackState?.state == PlaybackState.STATE_PLAYING,
            config,
            "#${MiniPlayerRuntime.materialGeneration}",
            { target -> MiniPlayerRuntime.material(target, loader) },
            ::togglePlayback,
            { skip(next = false) },
            { skip(next = true) },
            {
                // Up undoes down: out of the cover or lyrics by a swipe, back into it by one.
                if (MiniPlayerRuntime.takeRestoreScene()) Main.miniPlayerEnterCover()
                else if (config.getInt(MiniPlayerConfig.MEDIA_MODE) == 2)
                    MiniPlayerRuntime.selectNative(current.sessionToken)
            },
            {
                MiniPlayerRuntime.forgetRestoreScene()
                Main.miniPlayerEnterCover()
            },
        )
        updateVisibility()
        schedulePosition()
    }

    private var thumb: Bitmap? = null
    /** The artwork last handed to the pill; it stays until a new one is ready to replace it. */
    private var thumbShown: Bitmap? = null

    /**
     * The artwork at the size the pill draws it. A player's album art is often 1000px or more,
     * and handing that straight to the ImageView uploaded the whole thing to the GPU on the
     * frame of every track change. The scaling runs off the main thread; until it lands the
     * pill keeps the artwork it had.
     */
    private fun thumbnailFor(cover: Bitmap?, view: MiniPlayerView): Bitmap? {
        // A skip sends the new title before the new artwork: keep the old one until it comes
        // rather than going blank for those frames - the flash under the spin.
        if (cover == null || cover.isRecycled) return shown(null)
        // By content, not by object: MediaController.getMetadata() is a binder call and hands
        // back a new Bitmap every time. Compared by identity, every refresh was "new artwork",
        // scaled it again, and the scaled one's own refresh fetched another - a loop that never
        // let a scaled one be shown, which left the pill's artwork empty.
        val key = artworkKey(cover)
        if (key == thumbKey) return shown(thumb)
        val side = (view.artworkView.width.takeIf { it > 0 } ?: dp(48f)) * 2
        thumbKey = key
        if (cover.width <= side && cover.height <= side) {
            thumb = cover
            return shown(cover)
        }
        thumb = null
        MiniPlayerRuntime.artWorker.execute {
            val scaled = runCatching {
                val k = side.toFloat() / minOf(cover.width, cover.height)
                Bitmap.createScaledBitmap(cover, (cover.width * k).toInt().coerceAtLeast(1),
                    (cover.height * k).toInt().coerceAtLeast(1), true)
            }.getOrNull()
            handler.post {
                if (thumbKey != key || scaled == null) return@post
                thumb = scaled
                // Straight onto the pill: a refresh here fetched the metadata again for nothing.
                player?.showArtwork(shown(scaled))
            }
        }
        return shown(null)
    }

    private var thumbKey: String? = null

    /** Size and an 8x8 sample of the pixels: cheap, and different whenever the picture is. */
    private fun artworkKey(b: Bitmap): String {
        var hash = 17L
        if (b.config != Bitmap.Config.HARDWARE) {
            runCatching {
                for (j in 0 until 8) for (i in 0 until 8) {
                    hash = hash * 31 + b.getPixel(i * (b.width - 1) / 7, j * (b.height - 1) / 7)
                }
            }
        } else hash = b.generationId.toLong()
        return "${b.width}x${b.height}#$hash"
    }

    /**
     * A skip sends several metadata updates, one of them with no artwork, while the new
     * artwork is still being scaled: each of those used to blank the pill's artwork for a
     * frame - the grey flash, twice per skip on film. Nothing replaces the shown artwork but
     * a ready one.
     */
    private fun shown(ready: Bitmap?): Bitmap? {
        if (ready != null && !ready.isRecycled) thumbShown = ready
        return thumbShown?.takeUnless { it.isRecycled }
    }

    private fun chooseController(): MediaController? {
        val platform = Main.miniPlayerSession()
        if (platform != null && isUsable(platform)) return platform
        val active = runCatching { sessions?.getActiveSessions(null).orEmpty() }.getOrDefault(emptyList())
        return active.firstOrNull(::isUsable)
    }

    private fun isUsable(value: MediaController): Boolean = when (value.playbackState?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_CONNECTING, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }

    private fun updateVisibility() {
        val view = player
        val config = this.config
        val enabled = config.getBoolean(MiniPlayerConfig.ENABLED)
        val current = controller
        val sessionUsable = current?.let(::isUsable) == true
        // Unlocking, the keyguard says it is going away on the first frame of its fade-out.
        // Handing the card back then showed the whole OEM card through that fade - the
        // artwork flashing on every unlock. Until the lock screen is actually off the screen,
        // it keeps what it had; the pill fades with it (followShortcuts).
        if (enabled && sessionUsable && MiniPlayerScene.keyguardGoingAway && Main.onKeyguardNow()) {
            player?.setInteractionsEnabled(false)
            return
        }
        val keyguardOwned = enabled && sessionUsable &&
            !MiniPlayerScene.keyguardGoingAway && Main.keyguardLocked()
        // The lock screen's own choice of pill or card holds through everything that passes over
        // the lock screen - the doze, the PIN pad, the control centre. It used to be dropped
        // for each of them, so the card came back for the doze and for every swipe that raised
        // the pad; the pill now fades and zooms with the shortcut row instead (followShortcuts)
        // and only stops taking touches.
        val sceneVisible = keyguardOwned && Main.miniPlayerPresentable() &&
            !MiniPlayerScene.blocksMiniPlayer
        // Unlocking or a session ending mid-morph: straight to where it was going.
        if (!keyguardOwned) morph?.cancel()
        val controlCenterOpen = keyguardOwned &&
            (MiniPlayerScene.controlCenterIsActive || Main.miniPlayerControlCenterUp())
        val nativeRequested = MiniPlayerRuntime.nativeRequested(current?.sessionToken)
        val presentation = MiniPlayerPresentationPolicy.evaluate(
            MiniPlayerPresentationInput(
                enabled = enabled,
                sessionUsable = sessionUsable,
                mediaMode = config.getInt(MiniPlayerConfig.MEDIA_MODE),
                nativeRequested = nativeRequested,
                keyguardOwned = keyguardOwned,
                sceneVisible = sceneVisible,
                nativeSceneOverride = keyguardOwned && Main.coverSceneActive(),
                transitionActive = morph != null,
                controlCenterOpen = controlCenterOpen,
            ),
        )
        val shown = presentation.showMini
        if (view != null) {
            val target = if (shown) View.VISIBLE else View.GONE
            if (view.visibility != target) view.visibility = target
            view.setInteractionsEnabled(Main.miniPlayerCanShow() && !controlCenterOpen &&
                !MiniPlayerScene.aodActive)
        }
        updateNativeSuppression(presentation.suppressNative)
        if (morph == null && keyguardOwned &&
            (nativeRequested || Main.coverSceneActive())) ensureNativeHeaderVisible()
        val log = "mode=${config.getInt(MiniPlayerConfig.MEDIA_MODE)} nativeRequested=$nativeRequested " +
            "showMini=${presentation.showMini} suppressNative=${presentation.suppressNative} " +
            "keyguardOwned=$keyguardOwned sceneVisible=$sceneVisible center=$controlCenterOpen " +
            "sceneOverride=${Main.coverSceneActive()} transition=${morph != null}"
        if (log != lastPresentationLog) {
            lastPresentationLog = log
            Xp.log("MCMini: presentation $log")
        }
    }

    private fun updateNativeSuppression(suppress: Boolean) {
        val wasRequested = nativeSuppressionRequested
        nativeSuppressionRequested = suppress
        if (suppress) updateHeader()
        else if (wasRequested || suppressedHeaders.hasOverrides) restoreHeader()
    }

    private fun ensureNativeHeaderVisible() {
        val current = transitionHeader() ?: return
        if (current.visibility != View.VISIBLE) current.visibility = View.VISIBLE
        if (current.alpha < 0.99f) current.alpha = 1f
    }

    private fun updateHeader() {
        val current = header?.get()?.takeIf { it.isAttachedToWindow && !forceHeaderRefresh }
            ?: Main.miniPlayerMediaHeader()
        forceHeaderRefresh = false
        val previous = header?.get()
        if (previous !== current) {
            header = current?.let(::WeakReference)
            if (previous != null && current != null) {
                Xp.log("MCMini: native media header replaced while suppression remains active")
            }
        }
        if (current == null) return
        // INVISIBLE keeps the OEM layout measured but prevents its renderer/property animator
        // from winning a frame over an alpha-only suppression during metadata replacement.
        suppressedHeaders.suppress(current,
            if (current.visibility == View.INVISIBLE) View.VISIBLE else current.visibility,
            if (current.alpha <= 0f) 1f else current.alpha) { state ->
            if (current.visibility != state.visibility) current.visibility = state.visibility
            if (current.alpha != state.alpha) current.alpha = state.alpha
        }
    }

    private fun restoreHeader() {
        nativeSuppressionRequested = false
        suppressedHeaders.restore { view, state ->
            view.visibility = state.visibility
            view.alpha = state.alpha
        }
    }

    private fun schedulePosition() {
        if (positionPosted) return
        positionPosted = true
        host.postOnAnimation { positionPosted = false; position() }
    }

    private fun position() {
        val view = player ?: return
        if (view.visibility != View.VISIBLE || host.width <= 0 || host.height <= 0) return
        // Where the buttons are laid out, not where they are drawn: whatever moves them on top
        // of their layout (the swipe, the doze) reaches the pill through followShortcuts.
        // Taking their drawn place here as well moved the pill twice as far as they went.
        val laidOut = left.width > 0 && right.width > 0 && left.height > 0 && right.height > 0
        val l = if (laidOut) restCentre(left) else null
        val r = if (laidOut) restCentre(right) else null
        val centerX = if (l != null && r != null) (l[0] + r[0]) / 2f else host.width / 2f
        val centerY = if (l != null && r != null) (l[1] + r[1]) / 2f else host.height / 2f
        val config = this.config
        val requestedWidth = dp(config.getDouble(MiniPlayerConfig.WIDTH).toFloat())
        val width = MiniPlayerGeometry.widthPx(
            min(requestedWidth, (host.width * .64f).toInt()),
            host.width, centerX, dp(12f))
        val height = dp(MiniPlayerConfig.visibleHeightDp(config.toString()))
        if (view.layoutParams.width != width || view.layoutParams.height != height) {
            view.layoutParams = view.layoutParams.apply { this.width = width; this.height = height }
            schedulePosition()
            return
        }
        // The asked-for size, not view.width: a morph resizes the frame while this still runs.
        val x = centerX - width / 2f - view.left
        val y = centerY - height / 2f - view.top
        view.setBaseTranslation(x, y)
    }

    private fun togglePlayback() = command(
        PlaybackState.ACTION_PAUSE, PlaybackState.ACTION_PLAY,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    ) { current, playing ->
        if (playing) current.transportControls.pause() else current.transportControls.play()
    }

    private fun skip(next: Boolean) = command(
        if (next) PlaybackState.ACTION_SKIP_TO_NEXT else PlaybackState.ACTION_SKIP_TO_PREVIOUS,
        if (next) PlaybackState.ACTION_SKIP_TO_NEXT else PlaybackState.ACTION_SKIP_TO_PREVIOUS,
        if (next) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    ) { current, _ ->
        if (next) current.transportControls.skipToNext() else current.transportControls.skipToPrevious()
    }

    private fun command(actionWhenPlaying: Long, actionWhenPaused: Long, fallbackKey: Int,
                        transport: (MediaController, Boolean) -> Unit) {
        handler.post {
            runCatching {
                val current = chooseController() ?: controller
                val playing = current?.playbackState?.state == PlaybackState.STATE_PLAYING
                val action = if (playing) actionWhenPlaying else actionWhenPaused
                val supported = current?.playbackState?.actions?.and(action) != 0L
                if (current != null && supported) {
                    runCatching { transport(current, playing) }
                        .onFailure { dispatchFallback(fallbackKey) }
                } else dispatchFallback(fallbackKey)
            }.onFailure { Xp.log("MCMini: media command failed: $it") }
        }
    }

    private fun dispatchFallback(key: Int) {
        val manager = audio ?: return
        val now = android.os.SystemClock.uptimeMillis()
        runCatching {
            manager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
            manager.dispatchMediaKeyEvent(KeyEvent(now,
                android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, key, 0))
        }
    }

    private fun dp(value: Float) = (value * context.resources.displayMetrics.density + .5f).toInt()

    private fun Float.approximatelyEquals(other: Float): Boolean = abs(this - other) <= 0.01f
}

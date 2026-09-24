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
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
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
        LockIslands.install(classLoader)
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
     * The card can be swiped down into the pill: when it is up by choice,
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
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) releasePress()
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
            // The finger swells what it is on: the pill, or the torch or camera's disc - whose
            // button still takes the touch itself.
            releasePress()
            val pill = routed?.get()
            val pillOwner = pill?.let { p -> live().firstOrNull { it.pill() === p } }
            routedSmall = pillOwner?.smallIslandAt(ev.rawX, ev.rawY) == true
            val hit = if (pill != null) pillOwner?.takeIf { !routedSmall }?.let { it to PRESS_PILL }
                else live().firstNotNullOfOrNull { c -> c.discAt(ev.rawX, ev.rawY)?.let { c to it } }
            hit?.let { (owner, side) ->
                owner.press(side, true)
                pressed = WeakReference(owner)
                pressedSide = side
            }
            noteTouch("down ${ev.rawX.toInt()},${ev.rawY.toInt()} pill=${pill != null} " +
                "small=$routedSmall caught=$routedMorph disc=${hit?.second}")
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
                if (!routedSmall) dispatchTo(target, ev, MotionEvent.ACTION_CANCEL)
                // Sideways on a row of islands, the pull is the islands'; alone, it is a skip.
                routedOwner = live().firstOrNull { it.pill() === target }
                routedIsland = routedOwner?.islandCount()?.let { it >= 2 } == true &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy)
            } else {
                // A tap on the small island is the small island's: it takes the pill.
                if (!routedSmall) dispatchTo(target, ev, action)
                else if (action == MotionEvent.ACTION_UP) {
                    live().firstOrNull { it.pill() === target }?.selectSmallIsland()
                }
                if (action == MotionEvent.ACTION_UP) noteTouch("tap small=$routedSmall")
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) endRoute()
                return true
            }
        }
        val nx = pillNudgeX(dx, d)
        when (action) {
            MotionEvent.ACTION_MOVE -> {
                if (!routedMorph && !routedIsland && !routedNote && -dy > DRAG_THRESHOLD_DP * d) {
                    // Pulled far enough up to mean it: the pill starts
                    // turning into the card under the finger, the nudge handed over as it is.
                    val owner = if (routedSmall) null else live().firstOrNull { it.canDrag(fromNative = false) }
                    if (owner != null && beginDrag(fromNative = false, ev, startY = routedY)) {
                        routedMorph = true
                        target.clearNudge()
                    } else {
                        // A notification island - the pill's or the small one - opens into its row.
                        val o = routedOwner ?: live().firstOrNull { it.pill() === target }
                        val key = o?.noteKeyFor(routedSmall)
                        if (o != null && key != null && o.beginNoteDrag(key, routedSmall, routedY)) {
                            routedNote = true
                            routedOwner = o
                        }
                    }
                }
                if (routedMorph) dragMove(ev, nx)
                else if (routedNote) routedOwner?.noteDragMove(ev.rawY)
                else if (routedIsland) routedOwner?.islandDrag(dx)
                else if (!routedSmall) target.setNudge(routedBaseX + nx, routedBaseY + pillNudgeY(dy, d))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val tracker = routedTracker
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f
                val vy = tracker?.yVelocity ?: 0f
                val cancelled = action == MotionEvent.ACTION_CANCEL
                if (routedMorph) {
                    dragEnd(ev, cancelled)
                } else if (routedNote) {
                    routedOwner?.noteDragEnd(vy, cancelled)
                    noteTouch("note pull dy=${dy.toInt()} cancelled=$cancelled")
                } else if (routedIsland) {
                    // The super island's threshold: past 50dp (or flung) the switch runs.
                    val commit = !cancelled && (kotlin.math.abs(dx) >= ISLAND_SWIPE_DP * d ||
                        kotlin.math.abs(vx) > 1200f * d)
                    routedOwner?.islandDragEnd(commit, next = dx < 0f)
                    noteTouch("island swipe dx=${dx.toInt()} commit=$commit")
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

    /** The gesture began on the small island, not the pill. */
    private var routedSmall = false

    /** A sideways pull on a row of islands, and the row it pulls on. */
    private var routedIsland = false

    /** An upward pull opening a notification island into its row. */
    private var routedNote = false
    private var routedOwner: MiniPlayerController? = null

    /** The super island's swipe threshold (island_swipe_threshold). */
    private const val ISLAND_SWIPE_DP = 50f

    /** Up past this, a pull stops being a nudge and opens (or goes back into the scene). */
    private const val DRAG_THRESHOLD_DP = 72f

    private var pressed: WeakReference<MiniPlayerController>? = null
    private var pressedSide = PRESS_PILL

    private fun releasePress() {
        pressed?.get()?.press(pressedSide, false)
        pressed = null
    }

    /** The side a press is on: the pill, or a disc's index (0 the torch, 1 the camera). */
    internal const val PRESS_PILL = -1

    private fun endRoute() {
        routedIsland = false
        routedNote = false
        routedOwner = null
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

    /**
     * For the settings preview, in px: host width, then the torch's and the camera's laid-out
     * centre and icon size (x, y, w, h each). Null until a shortcut row has been built.
     */
    @JvmStatic fun shortcutGeometry(): FloatArray? = live().firstNotNullOfOrNull { it.shortcutGeometry() }

    /** The last touches the row saw and what it made of them, for `op mini`. */
    private val touchLog = ArrayDeque<String>()

    @JvmStatic fun noteTouch(what: String) {
        synchronized(touchLog) {
            touchLog.addLast("${android.os.SystemClock.uptimeMillis() % 100000} $what")
            while (touchLog.size > 14) touchLog.removeFirst()
        }
    }

    /** For `op mini`: the pill, the card, and the torch button's chain as they are right now. */
    @JvmStatic fun describe(): String {
        val sb = StringBuilder("material=$cardEffect calls=${cardRecipe?.size} " +
            "aod=${MiniPlayerScene.aodActive} || ${LockIslands.describe()} || touches: " +
            synchronized(touchLog) { touchLog.joinToString(" ; ") })
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

    /** A released notification's row under a point on screen: a swipe down there collapses it. */
    @JvmStatic fun releasedRowAt(x: Float, y: Float): String? =
        live().firstNotNullOfOrNull { it.releasedRowAt(x, y) }

    /** That row folds back into the row of islands. */
    @JvmStatic fun collapseRow(key: String): Boolean = live().any { it.collapseRow(key) }

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
        updateDiscs()
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
        placeSmallIsland(matrix, fade)
        // Into the pill's own frame: conjugated by its slot in the host.
        matrix.preTranslate(view.left.toFloat(), view.top.toFloat())
        matrix.postTranslate(-view.left.toFloat(), -view.top.toFloat())
        // The squeeze, inside that: about the pill's own centre in its slot, its nudge included.
        val sx = squeeze.pillScaleX()
        val sy = squeeze.pillScaleY()
        val shift = squeeze.pillShift()
        if (kotlin.math.abs(sx - 1f) > 0.0005f || kotlin.math.abs(sy - 1f) > 0.0005f
            || kotlin.math.abs(shift) > 0.0005f) {
            pillSqueeze.setScale(sx, sy, view.translationX + view.width / 2f,
                view.translationY + view.height / 2f)
            pillSqueeze.postTranslate(shift * view.width, 0f)
            matrix.preConcat(pillSqueeze)
        }
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
    private val pillSqueeze = Matrix()
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

    // ------------------------------------------------------------ the islands

    /** The row's islands in order: MUSIC_ISLAND, then notification keys. Set by refreshUnsafe. */
    private var islandKeys: List<String> = emptyList()

    /** The island in the pill. The one after it is the small island beside the pill. */
    private var selectedIsland: String? = null

    private var smallIsland: ShortcutDisc? = null
    private var smallKey: String? = null
    private var smallIconKey: Any? = null
    private val smallRest = FloatArray(2)
    private val smallFollow = Matrix()
    private val smallFollowValues = FloatArray(9)
    private val lastSmallFollow = FloatArray(9)
    private val islandListener: () -> Unit = { refresh() }

    /** How many islands the row has while the pill is showing; one is the pill alone. */
    fun islandCount(): Int = if (player?.visibility == View.VISIBLE) islandKeys.size else 0

    fun selectedIsMusic(): Boolean = selectedIsland == MUSIC_ISLAND

    /** The notification a pull opens: the small island's, or the pill's; none for the music. */
    fun noteKeyFor(small: Boolean): String? =
        (if (small) smallKey else selectedIsland)?.takeIf { it != MUSIC_ISLAND }

    /** The next island (a swipe to the left) or the one before it takes the pill. */
    fun switchIsland(next: Boolean) {
        val keys = islandKeys
        if (keys.size < 2) {
            resetIslandDrag()
            return
        }
        val oldBig = selectedIsland
        val oldSmall = smallKey
        preferredSmall = null
        val at = keys.indexOf(selectedIsland).coerceAtLeast(0)
        selectedIsland = keys[(at + if (next) 1 else keys.size - 1) % keys.size]
        refresh()
        startSwap(oldBig, oldSmall)
    }

    /**
     * A tap on the small island opens what it stands for, as a tap on the pill would: a
     * notification comes out as its own row. The music has no row of its own to open - its
     * small island is the pill's to take, so the tap brings it into the pill.
     */
    fun selectSmallIsland() {
        val key = smallKey ?: return
        if (key != MUSIC_ISLAND) {
            expandNote(key)
            return
        }
        val oldBig = selectedIsland
        selectedIsland = key
        refresh()
        startSwap(oldBig, key)
    }

    /** Whether a point on screen is on the small island's circle, a little slop included. */
    fun smallIslandAt(x: Float, y: Float): Boolean {
        val v = smallIsland ?: return false
        if (v.visibility != View.VISIBLE || smallKey == null) return false
        val xy = IntArray(2).also(host::getLocationOnScreen)
        val r = discDiameter() / 2f + dp(6f)
        val dx = x - (xy[0] + smallRest[0])
        val dy = y - (xy[1] + smallRest[1])
        return dx * dx + dy * dy <= r * r
    }

    /**
     * The small island: the island after the pill's, as a circle of the same glass beside it,
     * showing its picture. It sits under the pill in the host, as the super island's small
     * island sits under its big one.
     */
    private fun updateSmallIsland(music: MediaController?, notes: List<LockIslands.Note>) {
        val keys = islandKeys
        // A notification going out of the small island's place or folding back into it holds
        // that place for as long as its flight is out; one just folded in stays there after.
        val collapsing = noteMorphKey?.takeIf { flight != null }
        val preferred = preferredSmall?.takeIf { it in keys && it != selectedIsland }
        val key = when {
            collapsing != null && collapsing != selectedIsland -> collapsing
            keys.size < 2 -> null
            preferred != null -> preferred
            else -> keys[(keys.indexOf(selectedIsland).coerceAtLeast(0) + 1) % keys.size]
        }
        smallKey = key
        // Gone from the row: updateVisibility shrinks it away rather than cutting it.
        if (key == null) return
        val d = discDiameter()
        val frame = discFrame(d)
        val view = smallIsland ?: ShortcutDisc(context).also {
            smallIsland = it
            // Right after the lock screen's layer, which puts it just under the pill.
            host.addView(it, lockScreenLayerIndex(), ViewGroup.LayoutParams(frame, frame))
        }
        if (!smallWide && (view.layoutParams.width != frame || view.layoutParams.height != frame)) {
            view.layoutParams = view.layoutParams.apply { width = frame; height = frame }
        }
        view.dress(MiniPlayerRuntime.materialGeneration) { MiniPlayerRuntime.material(it, loader) }
        if (swap == null && !islandDragging && !smallGrowing) view.setShape(d, d)
        val picture: Any? = if (key == MUSIC_ISLAND) (thumbShown ?: cachedCover)
            else notes.firstOrNull { it.key == key }?.icon ?: LockIslands.noteFor(key)?.icon
        if (picture !== smallIconKey) {
            smallIconKey = picture
            view.setIcon(when (picture) {
                is Bitmap -> android.graphics.drawable.BitmapDrawable(context.resources, picture)
                is android.graphics.drawable.Drawable -> picture
                else -> null
            })
        }
    }

    private fun hideSmallIsland() {
        smallKey = null
        smallGrowing = false
        smallIsland?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
    }

    /** The notification last folded back in: it stays the small island until the row moves on. */
    private var preferredSmall: String? = null

    /** The next visibility pass shows the small island at once: a flight has just landed there. */
    private var snapSmallOnce = false

    /** The small island on its rest place, carried by the row's motion as the pill is. */
    private fun placeSmallIsland(follow: Matrix?, fade: Float) {
        val v = smallIsland ?: return
        if (v.visibility != View.VISIBLE) return
        // Its frame is centred on its rest place; a switch only widens the frame, never moves it.
        val fw = if (v.width > 0) v.width else v.layoutParams.width
        val fh = if (v.height > 0) v.height else v.layoutParams.height
        val tx = smallRest[0] - fw / 2f - v.left
        val ty = smallRest[1] - fh / 2f - v.top
        if (kotlin.math.abs(v.translationX - tx) > 0.25f) v.translationX = tx
        if (kotlin.math.abs(v.translationY - ty) > 0.25f) v.translationY = ty
        if (follow == null || follow.isIdentity) {
            if (lastSmallFollow.any { it != 0f }) {
                lastSmallFollow.fill(0f)
                v.setAnimationMatrix(null)
            }
        } else {
            smallFollow.set(follow)
            smallFollow.preTranslate(v.left.toFloat(), v.top.toFloat())
            smallFollow.postTranslate(-v.left.toFloat(), -v.top.toFloat())
            smallFollow.getValues(smallFollowValues)
            if (!smallFollowValues.contentEquals(lastSmallFollow)) {
                System.arraycopy(smallFollowValues, 0, lastSmallFollow, 0, 9)
                v.setAnimationMatrix(smallFollow)
            }
        }
        if (kotlin.math.abs(v.transitionAlpha - fade) > 0.002f) v.transitionAlpha = fade
    }

    private fun removeSmallIsland() {
        smallIsland?.let { runCatching { host.removeView(it) } }
        smallIsland = null
        smallKey = null
        smallIconKey = null
    }

    // ------------------------------------------------------------ islands in motion

    /**
     * An island switch in flight, as the super island switches (DynamicIslandAnimationDelegate,
     * CHANGE_EASE): the pill grows into its new island out of where that island was - the
     * small island's circle, or the pill's own left end for one coming back out of the stack -
     * while the island it held shrinks under it into the small island's place. Both are frames
     * changing size, never a scale, so the glass stays at its own pixels.
     */
    private class Swap(
        val pillFrom: CoverMorphMotion.Box,
        val pillTo: CoverMorphMotion.Box,
        /** The small island forms out of the pill's place; otherwise it pops up where it is. */
        val smallFromPill: Boolean,
    ) {
        val spring = Jelly(SWAP_RESPONSE, SWAP_DAMPING).apply { value = 0f; target = 1f }
        var last = 0L
    }

    private var swap: Swap? = null

    private val swapFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val s = swap ?: return
            val dt = if (s.last == 0L) 1f / 120f
                else ((frameTimeNanos - s.last) / 1e9f).coerceIn(0f, 0.05f)
            s.last = frameTimeNanos
            s.spring.step(dt)
            applySwap(s)
            if (s.spring.atRest()) endSwap() else Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** The small island on screen, at rest. */
    private fun smallBoxOnScreen(): CoverMorphMotion.Box {
        val xy = IntArray(2).also(host::getLocationOnScreen)
        val d = discDiameter().toFloat()
        return CoverMorphMotion.Box(xy[0] + smallRest[0] - d / 2f, xy[1] + smallRest[1] - d / 2f, d, d)
    }

    /**
     * Starts the switch that has just been made - [oldBig] was in the pill, [oldSmall] beside
     * it. Called after the refresh that put the new islands in, so the pill already holds the
     * new one and only its frame is still where the old one was.
     */
    private fun startSwap(oldBig: String?, oldSmall: String?) {
        val view = player ?: return
        endSwap()
        endRow()
        resetIslandDrag()
        if (view.visibility != View.VISIBLE || morph != null) return
        // The pill's size for the new row, as position() is about to lay it out.
        position()
        val rest = view.restBoxOnScreen() ?: return
        val d = discDiameter().toFloat()
        val fromSmall = oldSmall != null && selectedIsland == oldSmall
        val pillFrom = if (fromSmall) smallBoxOnScreen()
            else CoverMorphMotion.Box(rest.x, rest.y, d, rest.h)
        val s = Swap(pillFrom, rest, smallKey != null && smallKey == oldBig)
        swap = s
        view.beginMorph(layoutOnly = true)
        widenSmallIsland(rest)
        applySwap(s)
        Choreographer.getInstance().postFrameCallback(swapFrame)
    }

    /** A new small island coming up in its place, the pill as it is. */
    private fun startSmallPop() {
        val view = player ?: return
        endSwap()
        if (view.visibility != View.VISIBLE || morph != null || smallKey == null) return
        position()
        val rest = view.restBoxOnScreen() ?: return
        val s = Swap(rest, rest, smallFromPill = false)
        swap = s
        view.beginMorph(layoutOnly = true)
        applySwap(s)
        Choreographer.getInstance().postFrameCallback(swapFrame)
    }

    private fun applySwap(s: Swap) {
        val view = player ?: return
        val p = s.spring.value
        val box = lerpBox(s.pillFrom, s.pillTo, p)
        view.setMorphFrame(box, box.h / 2f, 1f)
        view.setContentAlpha(MiniCardMorph.smooth(0.35f, 0.9f, p))
        val small = smallIsland?.takeIf { it.visibility == View.VISIBLE && smallKey != null } ?: return
        val d = discDiameter().toFloat()
        if (s.smallFromPill) {
            // The pill's old island shrinking into the small island's place, under the new one.
            val xy = IntArray(2).also(host::getLocationOnScreen)
            val restCx = s.pillTo.cx() - xy[0]
            val w = lerp(s.pillTo.w, d, p).coerceAtLeast(d * 0.5f)
            val cx = lerp(restCx, smallRest[0], p)
            small.setShape(w.roundToInt(), d.roundToInt(), (cx - smallRest[0]).roundToInt())
            small.setIconAlpha(MiniCardMorph.smooth(0.55f, 1f, p))
            if (small.alpha != 1f) small.alpha = 1f
        } else {
            // A new small island coming up in its own place.
            val side = lerp(d * 0.4f, d, p).coerceAtLeast(1f).roundToInt()
            small.setShape(side, side, 0)
            small.setIconAlpha(MiniCardMorph.smooth(0.3f, 1f, p))
            small.alpha = MiniCardMorph.smooth(0f, 0.5f, p)
        }
    }

    private fun endSwap() {
        val s = swap ?: return
        swap = null
        Choreographer.getInstance().removeFrameCallback(swapFrame)
        player?.let {
            it.endMorph()
            it.setContentAlpha(1f)
        }
        restoreSmallIslandShape()
        schedulePosition()
        pendingExpand?.let { key ->
            pendingExpand = null
            if (selectedIsland == key) expandNote(key)
        }
    }

    /** Room in the small island's frame for a shape as wide as the pill, where the pill is. */
    private fun widenSmallIsland(rest: CoverMorphMotion.Box) {
        val small = smallIsland ?: return
        val xy = IntArray(2).also(host::getLocationOnScreen)
        val reach = kotlin.math.abs(rest.cx() - xy[0] - smallRest[0]) + rest.w / 2f
        val wide = maxOf(discFrame(discDiameter()), (reach * 2f + dp(16f)).roundToInt())
        if (small.layoutParams.width != wide) {
            small.layoutParams = small.layoutParams.apply { width = wide }
        }
        smallWide = true
    }

    private var smallWide = false

    private fun restoreSmallIslandShape() {
        val small = smallIsland ?: return
        val d = discDiameter()
        val frame = discFrame(d)
        if (smallWide && small.layoutParams.width != frame) {
            small.layoutParams = small.layoutParams.apply { width = frame }
        }
        smallWide = false
        small.setShape(d, d, 0)
        small.setIconAlpha(1f)
        small.alpha = 1f
    }

    private fun lerpBox(a: CoverMorphMotion.Box, b: CoverMorphMotion.Box, t: Float) =
        CoverMorphMotion.Box(lerp(a.x, b.x, t), lerp(a.y, b.y, t),
            maxOf(1f, lerp(a.w, b.w, t)), maxOf(1f, lerp(a.h, b.h, t)))

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    // ---- the row's layout, animated

    /**
     * The pill's place in the row, sprung: every change of the row's layout - a small island
     * coming or going, the pill giving room to a notification folding back in, taking it back
     * when one leaves - runs on the super island's CHANGE_EASE rather than jumping, as its
     * container clip does for each of those transitions. position() sets the target; the pill's
     * own layout is already at the target size, and the frame is drawn at the spring's.
     */
    private val rowLeft = Jelly(SWAP_RESPONSE, SWAP_DAMPING)
    private val rowWidth = Jelly(SWAP_RESPONSE, SWAP_DAMPING)
    private var rowKnown = false
    private var rowAnimating = false
    private var rowLast = 0L

    private val rowFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!rowAnimating) return
            val dt = if (rowLast == 0L) 1f / 120f
                else ((frameTimeNanos - rowLast) / 1e9f).coerceIn(0f, 0.05f)
            rowLast = frameTimeNanos
            rowLeft.step(dt)
            rowWidth.step(dt)
            applyRow()
            if (rowLeft.atRest() && rowWidth.atRest()) endRow()
            else Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** position(): the pill's rest place in the host is now [left] and [width]. */
    private fun rowTarget(left: Float, width: Float) {
        val view = player
        val busy = swap != null || morph != null || view == null || view.visibility != View.VISIBLE
        if (!rowKnown || busy) {
            // Nothing drawn to move from, or another motion owns the frame: straight there.
            rowLeft.value = left; rowLeft.target = left; rowLeft.velocity = 0f
            rowWidth.value = width; rowWidth.target = width; rowWidth.velocity = 0f
            rowKnown = view != null && view.visibility == View.VISIBLE
            if (rowAnimating) endRow()
            return
        }
        if (kotlin.math.abs(rowLeft.target - left) < 0.5f && kotlin.math.abs(rowWidth.target - width) < 0.5f) return
        rowLeft.target = left
        rowWidth.target = width
        if (!rowAnimating) {
            rowAnimating = true
            rowLast = 0L
            view!!.beginMorph(layoutOnly = true)
            applyRow()
            Choreographer.getInstance().postFrameCallback(rowFrame)
        }
    }

    private fun applyRow() {
        val view = player ?: return
        val rest = view.restBoxOnScreen() ?: return
        val xy = IntArray(2).also(host::getLocationOnScreen)
        val box = CoverMorphMotion.Box(xy[0] + rowLeft.value, rest.y,
            rowWidth.value.coerceAtLeast(rest.h), rest.h)
        view.setMorphFrame(box, box.h / 2f, 1f)
    }

    /** The row's spring hands the frame back: at rest, or to another motion taking it. */
    private fun endRow() {
        if (!rowAnimating) return
        rowAnimating = false
        Choreographer.getInstance().removeFrameCallback(rowFrame)
        rowLeft.value = rowLeft.target; rowLeft.velocity = 0f
        rowWidth.value = rowWidth.target; rowWidth.velocity = 0f
        player?.endMorph()
    }

    // ---- the small island coming and going

    /** The small island's presence, 0 gone to 1 there, sprung the same way. */
    private val smallGrow = Jelly(SWAP_RESPONSE, SWAP_DAMPING)
    private var smallGrowing = false
    private var smallGrowLast = 0L

    private val smallGrowFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!smallGrowing) return
            val dt = if (smallGrowLast == 0L) 1f / 120f
                else ((frameTimeNanos - smallGrowLast) / 1e9f).coerceIn(0f, 0.05f)
            smallGrowLast = frameTimeNanos
            smallGrow.step(dt)
            applySmallGrow()
            if (smallGrow.atRest()) {
                smallGrowing = false
                if (smallGrow.target == 0f) smallIsland?.visibility = View.GONE
                else restoreSmallIslandShape()
            } else Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * The small island shown or not: coming up it grows out of nothing in its place, going it
     * shrinks away there - unless the row itself is going, when it just goes with it.
     */
    private fun setSmallShown(shown: Boolean, animate: Boolean) {
        val small = smallIsland ?: return
        val visible = small.visibility == View.VISIBLE
        if (shown && visible && (!smallGrowing || smallGrow.target == 1f)) return
        if (!shown && (!visible || (smallGrowing && smallGrow.target == 0f))) return
        if (!animate || swap != null) {
            smallGrowing = false
            Choreographer.getInstance().removeFrameCallback(smallGrowFrame)
            smallGrow.value = if (shown) 1f else 0f
            smallGrow.target = smallGrow.value
            small.visibility = if (shown) View.VISIBLE else View.GONE
            if (shown) restoreSmallIslandShape()
            return
        }
        if (shown && !visible) {
            smallGrow.value = 0f
            smallGrow.velocity = 0f
            small.visibility = View.VISIBLE
        }
        smallGrow.target = if (shown) 1f else 0f
        if (!smallGrowing) {
            smallGrowing = true
            smallGrowLast = 0L
            applySmallGrow()
            Choreographer.getInstance().postFrameCallback(smallGrowFrame)
        }
    }

    private fun applySmallGrow() {
        val small = smallIsland ?: return
        val d = discDiameter().toFloat()
        val v = smallGrow.value
        val side = lerp(d * 0.4f, d, v).coerceAtLeast(1f).roundToInt()
        small.setShape(side, side, 0)
        small.setIconAlpha(MiniCardMorph.smooth(0.3f, 1f, v))
        small.alpha = MiniCardMorph.smooth(0f, 0.5f, v)
    }

    // ---- the finger on a row of islands

    private var islandDragging = false

    /**
     * The finger pulling sideways on a row of islands, as the super island follows it
     * (IslandSwipeAnimator): progress is the pull over half the width, times 0.14. The pill
     * narrows by that share and fades toward 0.2; pulled toward the small island it leans that
     * way, pulled away the small island reaches toward it, by a quarter of the pill's width.
     */
    fun islandDrag(dx: Float) {
        val view = player ?: return
        // A switch still settling is finished where it was headed: the finger has the row now.
        if (swap != null) endSwap()
        islandDragging = true
        val p = (kotlin.math.abs(dx) / (host.width / 2f).coerceAtLeast(1f) * SWIPE_SHARE)
            .coerceAtMost(SWIPE_SHARE * 1.5f)
        val towardSmall = dx > 0f
        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f
        view.scaleX = 1f - p
        view.scaleY = 1f - p * 0.25f
        view.alpha = (1f - p * 0.8f / SWIPE_SHARE).coerceAtLeast(0.2f)
        val small = smallIsland?.takeIf { it.visibility == View.VISIBLE } ?: return
        val d = discDiameter().toFloat()
        val w = view.width.toFloat()
        if (towardSmall) {
            view.setNudge((smallRest[0] - (view.left + view.translationX + w / 2f)) * p, 0f)
            val side = (d * (1f - 0.2f * p / SWIPE_SHARE)).roundToInt()
            small.setShape(side, side, 0)
            small.alpha = 1f - 0.2f * (p / SWIPE_SHARE).coerceAtMost(1f)
        } else {
            view.setNudge(0f, 0f)
            val rest = view.restBoxOnScreen() ?: return
            widenSmallIsland(rest)
            small.setShape((d + w * p * 0.5f).roundToInt(), d.roundToInt(), -(w * p * 0.25f).roundToInt())
            small.alpha = 1f
        }
    }

    /** The finger is off: over the threshold the switch runs, under it the row springs back. */
    fun islandDragEnd(commit: Boolean, next: Boolean) {
        if (!islandDragging) return
        if (commit) {
            switchIsland(next)
            return
        }
        val view = player ?: return
        islandDragging = false
        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(SPRING_BACK_MS)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.3f, 1.05f))
            .start()
        view.springNudgeBack(0f, 0f)
        restoreSmallIslandShape()
    }

    private fun resetIslandDrag() {
        if (!islandDragging) return
        islandDragging = false
        player?.let {
            it.animate().cancel()
            it.scaleX = 1f
            it.scaleY = 1f
            it.alpha = 1f
            it.clearNudge()
        }
    }

    // ------------------------------------------------------------ notifications out to their rows and back

    /**
     * The notification island on its way out into its row in the stack, or back in from it. The
     * pill keeps showing it until the morph lands, whatever the row's islands have become.
     */
    private var noteMorphKey: String? = null

    /** A small island tapped: it takes the pill first, and opens once it has. */
    private var pendingExpand: String? = null

    private var rowWaitKey: String? = null
    private var rowWaitSince = 0L
    private var stackRef: WeakReference<View>? = null

    /**
     * A tap on a notification island opens it into its own row, as the music island opens into
     * the media card: the stack is given the notification back, and once its row is laid out
     * the island becomes that row - its picture, title and text landing on the row's own.
     *
     * The pill's own island goes out as the pill. A small island goes out as itself: a second
     * pill, the flight, bound to its notification, starts as the small island's circle and grows
     * straight into the row, the pill beside it never changing. The first version switched the
     * small island into the pill and opened it from there - two motions where one was asked for.
     */
    fun expandNote(key: String) {
        if (noteMorphKey != null || morph != null) return
        val view = player
        if (view == null || view.visibility != View.VISIBLE) {
            LockIslands.release(key)
            return
        }
        val fromSmall = selectedIsland != key
        if (fromSmall && (key != smallKey || prepareFlight(key) == null)) {
            LockIslands.release(key)
            return
        }
        endSwap()
        noteMorphKey = key
        flightFromSmall = fromSmall
        LockIslands.release(key)
        rowWaitKey = key
        rowWaitSince = android.os.SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback(rowWait)
    }

    /** Frame by frame until the released notification's row is in the stack and laid out. */
    private val rowWait = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val key = rowWaitKey ?: return
            val row = rowFor(key)
            val flightReady = !flightFromSmall || flight?.let { it.isLaidOut && it.width > 1 } == true
            if (row != null && row.isAttachedToWindow && row.isLaidOut && row.height > 0 &&
                row.width > 0 && flightReady) {
                rowWaitKey = null
                startNoteMorph(key, row, toRow = true)
                return
            }
            if (android.os.SystemClock.uptimeMillis() - rowWaitSince > ROW_WAIT_MS) {
                // No row in time: it is in the stack anyway; the row of islands just moves on.
                Xp.log("MCIsland: row for $key not laid out in time; opened without a morph")
                rowWaitKey = null
                noteMorphKey = null
                noteDrag = null
                dropFlight()
                refresh()
                return
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * A swipe down on a notification's row that came out of the row of islands: it goes back in,
     * the row becoming the pill, as the media card folds back into it. It comes in as the
     * flight and lands on the pill's place; only then does the pill take it, so nothing in the
     * pill jumps while the row is still on its way.
     */
    fun collapseRow(key: String): Boolean {
        if (noteMorphKey != null || morph != null) return false
        val row = rowFor(key) ?: return false
        val view = player
        if (view == null || view.visibility != View.VISIBLE || view.restBoxOnScreen() == null) {
            // No row of islands showing to land in: the notification just goes back in.
            LockIslands.recapture(key)
            return true
        }
        val flight = prepareFlight(key) ?: return false
        endSwap()
        noteMorphKey = key
        flightFromSmall = false
        // The row laid out with this notification as its small island: the pill gives it room
        // on its spring while the row comes down, as the super island's big island does.
        refresh()
        position()
        if (flight.isLaidOut && flight.width > 1) return startNoteMorph(key, row, toRow = false)
        // Laid out on the next frame at the earliest; the morph waits for it.
        flight.post { if (noteMorphKey == key && morph == null) startNoteMorph(key, row, toRow = false) }
        return true
    }

    /** The flight: a pill of its own, bound to [key]'s notification, laid out at the pill's size. */
    private var flight: MiniPlayerView? = null
    private var flightFromSmall = false

    private fun prepareFlight(key: String): MiniPlayerView? {
        val note = LockIslands.noteFor(key) ?: return null
        val pill = player ?: return null
        val view = flight ?: MiniPlayerView(context).also {
            flight = it
            // Under the pill in the host; a morph lifts it above everything with translationZ.
            host.addView(it, lockScreenLayerIndex(), ViewGroup.LayoutParams(1, 1))
        }
        val w = pill.layoutParams.width.coerceAtLeast(1)
        val h = pill.layoutParams.height.coerceAtLeast(1)
        if (view.layoutParams.width != w || view.layoutParams.height != h) {
            view.layoutParams = view.layoutParams.apply { width = w; height = h }
        }
        bindNote(view, note, config)
        // Out of sight until its morph puts it where it starts.
        view.visibility = View.VISIBLE
        view.alpha = 0f
        return view
    }

    private fun dropFlight() {
        flight?.let { runCatching { host.removeView(it) } }
        flight = null
        flightFromSmall = false
    }

    private fun startNoteMorph(key: String, row: View, toRow: Boolean): Boolean {
        val useFlight = flightFromSmall || !toRow
        val view = (if (useFlight) flight else player) ?: return false
        val landing = MiniCardMorph.Landing(
            findNamed(row, ICON_NAMES) { it is ImageView },
            findNamed(row, TITLE_NAMES) { it is TextView },
            findNamed(row, TEXT_NAMES) { it is TextView },
            Main.notificationRowRadius(row),
            dp(8f).toFloat(),
        )
        // The flight's end, both ways: the small island's circle.
        val restBox: (() -> CoverMorphMotion.Box?)? = when {
            !useFlight -> null
            else -> { { smallBoxOnScreen() } }
        }
        view.alpha = 1f
        if (!useFlight) endRow()
        val next = MiniCardMorph(view, row, toRow, noteMorphListener(key), landing, restBox)
        morph = next
        if (useFlight && toRow) smallIsland?.visibility = View.GONE
        val drag = noteDrag?.takeIf { it.key == key && toRow }
        if (drag != null) drag.span = noteDragSpan(row, useFlight)
        val started = if (drag != null) next.startDragging() else next.start()
        if (!started) {
            morph = null
            noteMorphKey = null
            noteDrag = null
            dropFlight()
            if (!toRow) LockIslands.recapture(key)
            refresh()
            return false
        }
        if (drag != null) {
            if (drag.ended) {
                // The finger already let go, committed: it opens from where the pull left it.
                noteDrag = null
                next.drag(noteDragProgress(drag), 0f)
                next.release(drag.commit, drag.velocity)
            } else applyNoteDrag()
        }
        return true
    }

    private fun noteMorphListener(key: String) = object : MiniCardMorph.Listener {
        override fun canSettle(morph: MiniCardMorph, toNative: Boolean) = true
        override fun artBridged() = false
        override fun onSettled(morph: MiniCardMorph, toNative: Boolean, completed: Boolean) {
            if (this@MiniPlayerController.morph === morph) this@MiniPlayerController.morph = null
            noteDrag = null
            val flew = flight != null
            val fromSmall = flightFromSmall
            noteMorphKey = null
            dropFlight()
            if (toNative) {
                // Out in the stack. From the small island the next one comes up in its place and
                // the pill takes its room back, both on their springs; from the pill, the pill
                // takes the next island, growing back out of its end.
                if (!fromSmall) selectedIsland = null
                refresh()
                if (!fromSmall) startSwap(null, null)
            } else {
                // Back in the row as the small island it landed as: the stack lets go of its row.
                LockIslands.recapture(key)
                if (flew) {
                    preferredSmall = key
                    snapSmallOnce = true
                } else selectedIsland = key
                refresh()
            }
        }
    }

    // ---- a finger pulling a notification island out

    /**
     * A notification island - the pill's or the small one - pulled up by the finger into its
     * row, as the music pill is pulled up into the media card. The row only exists once the
     * stack has been given the notification back, a frame or two later, so the finger is
     * followed from the start and the morph picks up wherever it has got to when the row is
     * there. Let go before then, the pull's own distance and speed decide.
     */
    private class NoteDrag(val key: String, val startY: Float) {
        var y = startY
        var ended = false
        var commit = false
        var velocity = 0f
        var span = 1f
    }

    private var noteDrag: NoteDrag? = null

    fun beginNoteDrag(key: String, fromSmall: Boolean, startY: Float): Boolean {
        if (noteMorphKey != null || morph != null) return false
        val view = player ?: return false
        if (view.visibility != View.VISIBLE) return false
        if (fromSmall && (key != smallKey || prepareFlight(key) == null)) return false
        if (!fromSmall && selectedIsland != key) return false
        endSwap()
        resetIslandDrag()
        view.clearNudge()
        noteMorphKey = key
        flightFromSmall = fromSmall
        noteDrag = NoteDrag(key, startY)
        LockIslands.release(key)
        rowWaitKey = key
        rowWaitSince = android.os.SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback(rowWait)
        return true
    }

    fun noteDragMove(y: Float) {
        val drag = noteDrag ?: return
        drag.y = y
        applyNoteDrag()
    }

    fun noteDragEnd(velocityY: Float, cancelled: Boolean) {
        val drag = noteDrag ?: return
        drag.ended = true
        val progress = noteDragProgress(drag)
        drag.commit = !cancelled && (progress > NOTE_DRAG_COMMIT || -velocityY > NOTE_FLING_PX_S * density())
        drag.velocity = -velocityY / drag.span
        val running = morph
        if (running != null) {
            noteDrag = null
            running.release(drag.commit, drag.velocity)
            return
        }
        if (!drag.commit) {
            // Let go short, before the row was even there: nothing opened.
            noteDrag = null
            abandonNoteMorph(drag.key)
        }
        // Committed: the row's arrival opens it on its own.
    }

    private fun noteDragProgress(drag: NoteDrag): Float {
        val raw = (drag.startY - drag.y) / drag.span
        return when {
            raw < 0f -> -MiniCardMorph.rubber(-raw, 0.25f)
            raw > 1f -> 1f + MiniCardMorph.rubber(raw - 1f, 0.25f)
            else -> raw
        }
    }

    private fun applyNoteDrag() {
        val drag = noteDrag ?: return
        val running = morph ?: return
        if (drag.ended) return
        running.drag(noteDragProgress(drag), 0f)
    }

    /** The row's arrival: how far the pull has to go, from the island's place to the row's. */
    private fun noteDragSpan(row: View, fromSmall: Boolean): Float {
        val start = (if (fromSmall) smallBoxOnScreen() else player?.restBoxOnScreen())?.y ?: return 1f
        val xy = IntArray(2).also(row::getLocationOnScreen)
        return kotlin.math.abs(start - xy[1]).coerceAtLeast(160f * density())
    }

    /** A notification that set out and did not open: back in the row as it was. */
    private fun abandonNoteMorph(key: String) {
        rowWaitKey = null
        noteMorphKey = null
        dropFlight()
        LockIslands.recapture(key)
        refresh()
    }

    /** A released notification's row under a point on screen, by key. */
    fun releasedRowAt(x: Float, y: Float): String? {
        val xy = IntArray(2)
        for (key in LockIslands.releasedKeys()) {
            val row = rowFor(key) ?: continue
            if (!row.isShown || row.width <= 0) continue
            row.getLocationOnScreen(xy)
            if (x >= xy[0] && x < xy[0] + row.width && y >= xy[1] && y < xy[1] + row.height) return key
        }
        return null
    }

    /** The lock screen's notification stack, found once in the window. */
    private fun notificationStack(): ViewGroup? {
        (stackRef?.get() as? ViewGroup)?.takeIf { it.isAttachedToWindow }?.let { return it }
        val queue = ArrayDeque<View>()
        queue.add(host)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v.javaClass.name.endsWith("NotificationStackScrollLayout") && v is ViewGroup) {
                stackRef = WeakReference(v)
                return v
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
        }
        return null
    }

    /** A notification's row in the stack, by its key. */
    private fun rowFor(key: String): View? {
        val stack = notificationStack() ?: return null
        for (i in 0 until stack.childCount) {
            val child = stack.getChildAt(i)
            if (!child.javaClass.name.contains("ExpandableNotificationRow")) continue
            val sbn = runCatching {
                Xp.getObjectField(Xp.callMethod(child, "getEntry"), "mSbn") as? android.service.notification.StatusBarNotification
            }.getOrNull() ?: continue
            if (sbn.key == key) return child
        }
        return null
    }

    /** The first shown view under [root] with one of [names] as its id, breadth first. */
    private fun findNamed(root: View, names: Set<String>, kind: (View) -> Boolean): View? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v.id != View.NO_ID && v.isShown && kind(v)) {
                val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
                if (name in names) return v
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
        }
        return null
    }

    // ------------------------------------------------------------ the shortcut discs

    private val discs = arrayOfNulls<ShortcutDisc>(2)

    /** The lock screen is the mini player's: the discs go behind the buttons. updateVisibility. */
    private var discsWanted = false

    private val squeeze = MiniSqueeze(dp(MiniPlayerGeometry.DISC_GAP_DP).toFloat()) { onSqueezeFrame() }
    private val buttonSqueeze = arrayOf(Matrix(), Matrix())
    private val buttonSqueezeSet = BooleanArray(2)
    private val discPoint = FloatArray(2)
    private val discUnit = FloatArray(4)
    private val discRest = FloatArray(2)

    private fun button(side: Int) = if (side == 0) left else right

    /** A disc is a circle as tall as the pill. */
    private fun discDiameter(): Int = dp(MiniPlayerConfig.visibleHeightDp(config.toString()))

    /** The disc's frame: room for its widest swelling and squeeze around the circle. */
    private fun discFrame(d: Int): Int = (d * DISC_FRAME).toInt()

    private fun onSqueezeFrame() {
        if (player?.visibility == View.VISIBLE) followShortcuts()
        updateDiscs()
    }

    /** A finger down on, or off, the pill ([side] PRESS_PILL) or a disc. */
    fun press(side: Int, down: Boolean) {
        if (side == MiniPlayerRuntime.PRESS_PILL) squeeze.pressPill(down) else squeeze.pressDisc(side, down)
    }

    /**
     * Which disc's button a point is on, if its disc is showing: the icon, or the
     * shortcut_view_*_layout around it, which is what takes the tap.
     */
    fun discAt(x: Float, y: Float): Int? = (0..1).firstOrNull {
        if (discs[it]?.visibility != View.VISIBLE) return@firstOrNull false
        val icon = button(it)
        val frame = (icon.parent as? View)?.takeIf { p ->
            runCatching { p.resources.getResourceEntryName(p.id) }.getOrNull()?.endsWith("_layout") == true
        }
        onButton(icon, x, y) || frame != null && onButton(frame, x, y)
    }

    /**
     * Each disc on its button as the button is drawn - its centre, zoom and fade through
     * everything the OEM animates it with (the swipe away, the doze) - then flattened or swollen
     * by the squeeze. The button takes the same squeeze on top of its own motion through its
     * animation matrix, which the OEM does not write and drawnCentre does not read.
     */
    private fun updateDiscs() {
        val d = discDiameter()
        val frame = discFrame(d)
        followHost.reset()
        host.transformMatrixToGlobal(followHost)
        val placed = followHost.invert(hostInverse)
        for (side in 0..1) {
            val button = button(side)
            val shown = placed && discsWanted && button.isShown && button.width > 0 && button.height > 0
            var disc = discs[side]
            if (!shown) {
                if (disc != null && disc.visibility != View.GONE) disc.visibility = View.GONE
                clearButtonSqueeze(side)
                continue
            }
            if (disc == null) {
                disc = ShortcutDisc(context)
                discs[side] = disc
                host.addView(disc, lockScreenLayerSlot(), ViewGroup.LayoutParams(frame, frame))
            }
            if (disc.layoutParams.width != frame || disc.layoutParams.height != frame) {
                disc.layoutParams = disc.layoutParams.apply { width = frame; height = frame }
            }
            disc.dress(MiniPlayerRuntime.materialGeneration) { MiniPlayerRuntime.material(it, loader) }
            if (disc.visibility != View.VISIBLE) disc.visibility = View.VISIBLE
        }
        // The squeeze from this frame's row, then the discs from the squeeze - in that order, or
        // the discs are a frame behind the pill that is pushing them.
        squeezeScene(d.toFloat())
        for (side in 0..1) {
            val button = button(side)
            val disc = discs[side]?.takeIf { it.visibility == View.VISIBLE } ?: continue
            if (!drawnCentre(button, discPoint)) continue
            val zoom = drawnZoom(button)
            val outward = if (side == 0) -1f else 1f
            val cx = discPoint[0] + outward * squeeze.discShift(side) * d * zoom
            // Placed and zoomed with the button; its squeeze is its shape, at its own pixels.
            setIfChanged(disc, cx - frame / 2f - disc.left, discPoint[1] - frame / 2f - disc.top,
                zoom, zoom, chainFade(button))
            disc.setShape((d * squeeze.discScaleX(side)).roundToInt().coerceIn(1, frame),
                (d * squeeze.discScaleY(side)).roundToInt().coerceIn(1, frame))
            squeezeButton(side, button, d)
        }
    }

    /**
     * The row at rest, in host pixels, for the squeeze: the pill's rest frame with its nudge
     * (or a morph's container as drawn) and each showing disc on its button's laid-out centre.
     */
    private fun squeezeScene(d: Float) {
        fun disc(side: Int): CoverMorphMotion.Box? {
            if (discs[side]?.visibility != View.VISIBLE) return null
            restCentre(button(side), discRest)
            return CoverMorphMotion.Box(discRest[0] - d / 2f, discRest[1] - d / 2f, d, d)
        }
        val hostXY = IntArray(2).also(host::getLocationOnScreen)
        val running = morph
        val view = player
        val pill: CoverMorphMotion.Box? = when {
            running != null -> running.containerBox()?.let {
                CoverMorphMotion.Box(it.x - hostXY[0], it.y - hostXY[1], it.w, it.h)
            }
            view != null && view.visibility == View.VISIBLE -> view.restBoxOnScreen()?.let {
                CoverMorphMotion.Box(it.x - hostXY[0] + view.nudgeX, it.y - hostXY[1] + view.nudgeY,
                    it.w, it.h)
            }
            else -> null
        }
        val small = smallIsland?.takeIf { running == null && it.visibility == View.VISIBLE }
        val row = if (pill != null && small != null) {
            val right = smallRest[0] + d / 2f
            CoverMorphMotion.Box(pill.x, pill.y, maxOf(pill.w, right - pill.x), pill.h)
        } else pill
        squeeze.setScene(row, running == null, disc(0), disc(1))
    }

    /**
     * The button rides on its disc in its own slot: moved with it, and swollen or shrunk about
     * its drawn centre there. Both count - a disc only pushed aside, not flattened, left its
     * icon behind when only the scale was checked.
     */
    private fun squeezeButton(side: Int, button: View, d: Int) {
        val k = squeeze.iconScale(side)
        val outward = if (side == 0) -1f else 1f
        // The disc's shift in the button's own pixels: the button's own scale, not its row's.
        val shift = outward * squeeze.discShift(side) * d * button.scaleX
        if (kotlin.math.abs(k - 1f) < 0.0005f && kotlin.math.abs(shift) < 0.25f) {
            clearButtonSqueeze(side)
            return
        }
        val m = buttonSqueeze[side]
        m.setScale(k, k, button.translationX + button.width / 2f,
            button.translationY + button.height / 2f)
        m.postTranslate(shift, 0f)
        button.setAnimationMatrix(m)
        buttonSqueezeSet[side] = true
    }

    private fun clearButtonSqueeze(side: Int) {
        if (!buttonSqueezeSet[side]) return
        buttonSqueezeSet[side] = false
        button(side).setAnimationMatrix(null)
    }

    /** How much a view is scaled on screen, every ancestor's transform included. */
    private fun drawnZoom(v: View): Float {
        scratch.reset()
        v.transformMatrixToGlobal(scratch)
        scratch.postConcat(hostInverse)
        discUnit[0] = 0f
        discUnit[1] = 0f
        discUnit[2] = 1f
        discUnit[3] = 0f
        scratch.mapPoints(discUnit)
        return kotlin.math.hypot(discUnit[2] - discUnit[0], discUnit[3] - discUnit[1])
    }

    /** A view's fade on screen: its own and every ancestor's, up to the host. */
    private fun chainFade(v: View): Float {
        var fade = 1f
        var cur: View? = v
        while (cur != null && cur !== host) {
            fade *= cur.alpha * cur.transitionAlpha
            cur = cur.parent as? View
        }
        return fade
    }

    /** Only real changes: each write invalidates, and a disc at rest must not redraw. */
    private fun setIfChanged(v: View, tx: Float, ty: Float, sx: Float, sy: Float, alpha: Float) {
        if (kotlin.math.abs(v.translationX - tx) > 0.25f) v.translationX = tx
        if (kotlin.math.abs(v.translationY - ty) > 0.25f) v.translationY = ty
        if (kotlin.math.abs(v.scaleX - sx) > 0.0005f) v.scaleX = sx
        if (kotlin.math.abs(v.scaleY - sy) > 0.0005f) v.scaleY = sy
        if (kotlin.math.abs(v.alpha - alpha) > 0.002f) v.alpha = alpha
    }

    private fun removeDiscs() {
        squeeze.reset()
        for (side in 0..1) {
            clearButtonSqueeze(side)
            discs[side]?.let { runCatching { host.removeView(it) } }
            discs[side] = null
        }
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
        LockIslands.addListener(islandListener)
        refresh()
    }

    fun destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        runCatching { host.viewTreeObserver.removeOnPreDrawListener(preDraw) }
        runCatching { sessions?.removeOnActiveSessionsChangedListener(sessionListener) }
        runCatching { controller?.unregisterCallback(mediaListener) }
        morph?.cancel()
        restoreHeader()
        removeDiscs()
        LockIslands.removeListener(islandListener)
        LockIslands.setActive(false)
        removeSmallIsland()
        dropFlight()
        player?.let { runCatching { host.removeView(it) } }
        player = null
        controller = null
        handler.removeCallbacksAndMessages(null)
    }

    fun isShowing(): Boolean = player?.visibility == View.VISIBLE

    fun shortcutGeometry(): FloatArray? {
        if (host.width <= 0 || left.width <= 0 || right.width <= 0) return null
        val l = restCentre(left)
        val r = restCentre(right)
        val li = iconSize(left)
        val ri = iconSize(right)
        return floatArrayOf(host.width.toFloat(), l[0], l[1], li[0], li[1], r[0], r[1], ri[0], ri[1])
    }

    /**
     * The glyph inside a shortcut button, as drawn: its image fitted into the padded box, then
     * only the part that has ink. The drawable's own box carries a wide transparent margin -
     * taken whole, the preview drew the icons several times too big. 0 if there is none.
     */
    private fun iconSize(v: View): FloatArray {
        val image = findImage(v) ?: return floatArrayOf(0f, 0f)
        val w = (image.width - image.paddingLeft - image.paddingRight).toFloat()
        val h = (image.height - image.paddingTop - image.paddingBottom).toFloat()
        val d = image.drawable
        val iw = d.intrinsicWidth.toFloat()
        val ih = d.intrinsicHeight.toFloat()
        if (w <= 0f || h <= 0f || iw <= 0f || ih <= 0f) return floatArrayOf(0f, 0f)
        val k = min(w / iw, h / ih)
        return runCatching { inkSize(d, (iw * k).toInt(), (ih * k).toInt()) }.getOrNull()
            ?: floatArrayOf(0f, 0f)
    }

    /** The opaque extent of a drawable drawn at this size, in pixels. */
    private fun inkSize(d: android.graphics.drawable.Drawable, w: Int, h: Int): FloatArray? {
        if (w <= 0 || h <= 0) return null
        val copy = d.constantState?.newDrawable(context.resources)?.mutate() ?: return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            copy.setBounds(0, 0, w, h)
            copy.draw(android.graphics.Canvas(bitmap))
            val row = IntArray(w)
            var minX = w
            var maxX = -1
            var minY = h
            var maxY = -1
            for (y in 0 until h) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    if ((row[x] ushr 24) < INK_ALPHA) continue
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            if (maxX < minX || maxY < minY) return null
            return floatArrayOf((maxX - minX + 1).toFloat(), (maxY - minY + 1).toFloat())
        } finally {
            bitmap.recycle()
        }
    }

    /** A pixel this opaque counts as the glyph's ink. */
    private val INK_ALPHA = 40

    private fun findImage(v: View): ImageView? {
        if (v is ImageView && v.drawable != null && v.visibility == View.VISIBLE) return v
        if (v !is ViewGroup) return null
        for (i in 0 until v.childCount) findImage(v.getChildAt(i))?.let { return it }
        return null
    }

    fun describe(): String {
        val islands = "rowAnim=$rowAnimating swap=${swap != null} noteMorph=${noteMorphKey != null} " +
            "flight=${flight != null} drag=${noteDrag != null} ${player?.touchState()} " +
            "row=${islandKeys.size} sel=${selectedIsland?.takeLast(24)} " +
            "small=${smallKey?.takeLast(24)} smallShown=${smallIsland?.visibility == View.VISIBLE} "
        val v = player ?: return islands + "no pill"
        val xy = IntArray(2).also(v::getLocationOnScreen)
        val h = header?.get()
        return islands + "pill v=${v.visibility} a=${v.alpha} ta=${v.transitionAlpha} at=${xy[0]},${xy[1]} " +
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
        // The card already chosen: nothing to become.
        if (scene && MiniPlayerRuntime.nativeRequested(token)) return false
        if (!view.isAttachedToWindow || !Main.miniPlayerMorphAllowed()) return false
        val native = transitionHeader() ?: return false
        view.visibility = View.VISIBLE
        updateNativeSuppression(false)
        position()
        endRow()
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
    /** A notification's morph is not the music's to catch: its drag engine would drive it wrong. */
    fun catchableAt(x: Float, y: Float): Boolean =
        noteMorphKey == null && morph?.catchableAt(x, y) == true

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
            MiniPlayerRuntime.nativeRequested(token) &&
            Main.miniPlayerCanShow()
    }

    fun density(): Float = context.resources.displayMetrics.density

    /** The dynamic switch can be pulled from this end right now. */
    fun canDrag(fromNative: Boolean): Boolean {
        if (!config.getBoolean(MiniPlayerConfig.ENABLED) || morph != null) return false
        // Only the music island has a card to open into.
        if (!fromNative && !selectedIsMusic()) return false
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
        endRow()
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
        return Main.coverModeOn() || wantsNativeArtworkGesture()
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
            MiniPlayerRuntime.noteTouch("refused: $refused")
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
        val at = lockScreenLayerSlot()
        return if (at >= host.childCount) at else at + 1
    }

    /** The lock screen's own layer in the host; the discs go right before it, under the buttons. */
    private fun lockScreenLayerSlot(): Int {
        var v: View = left
        while (true) {
            val parent = v.parent as? View ?: return host.childCount
            if (parent === host) {
                val at = host.indexOfChild(v)
                return if (at < 0) host.childCount else at
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
        if (!enabled) MiniPlayerRuntime.resetDynamicChoice("feature disabled")
        if (!enabled) {
            islandKeys = emptyList()
            player?.visibility = View.GONE
            hideSmallIsland()
            LockIslands.setActive(false)
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
        val music = controller?.takeIf(::isUsable)
        val notes = LockIslands.notes
        // The row: the music first, then the notifications in LockIslands' order.
        islandKeys = (if (music != null) listOf(MUSIC_ISLAND) else emptyList()) + notes.map { it.key }
        // A notification on its way out to its row, or in from it, stays in the pill till it lands.
        noteMorphKey?.let { key ->
            if (key !in islandKeys) islandKeys = islandKeys + key
            updateSmallIsland(music, notes)
            updateVisibility()
            schedulePosition()
            return
        }
        if (islandKeys.isEmpty()) {
            player?.visibility = View.GONE
            hideSmallIsland()
            restoreHeader()
            updateVisibility()
            return
        }
        val lost = selectedIsland != null && selectedIsland !in islandKeys
        val selected = selectedIsland?.takeIf { it in islandKeys } ?: islandKeys.first()
        selectedIsland = selected
        // The notification in the pill went (dismissed, answered elsewhere): the next island
        // grows into the pill from its end, as one coming out of the stack does.
        if (lost) handler.post { if (swap == null && morph == null) startSwap(null, null) }
        val view = player ?: MiniPlayerView(context).also {
            player = it
            host.addView(it, lockScreenLayerIndex(), ViewGroup.LayoutParams(1, 1))
        }
        if (music != null) trackMusic(music)
        val note = notes.firstOrNull { it.key == selected }
        if (note == null && music != null) bindMusic(view, music, config)
        else if (note != null) bindNote(view, note, config)
        updateSmallIsland(music, notes)
        updateVisibility()
        schedulePosition()
    }

    /** The session and track the music island is showing; a new one starts its artwork afresh. */
    private fun trackMusic(current: MediaController) {
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
    }

    /** The music island's artwork, as far as it has been fetched and scaled. */
    private fun musicCover(metadata: MediaMetadata?): Bitmap? {
        val cover = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: cachedCover?.takeUnless { it.isRecycled }
            ?: (if (!nativeSuppressionRequested) Main.cardThumbnail() else null)
        if (cover != null && !cover.isRecycled) cachedCover = cover
        return cover
    }

    private fun bindMusic(view: MiniPlayerView, current: MediaController, config: JSONObject) {
        val metadata = current.metadata
        val shown = thumbnailFor(musicCover(metadata), view)
        view.setToggleShown(true)
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
                else MiniPlayerRuntime.selectNative(current.sessionToken)
            },
            {
                MiniPlayerRuntime.forgetRestoreScene()
                Main.miniPlayerEnterCover()
            },
        )
    }

    /**
     * A notification in the pill: its picture, title and newest line, no play button. A tap
     * puts it back in the stack as the OEM's own row for now; the morph into that row is to
     * come.
     */
    private fun bindNote(view: MiniPlayerView, note: LockIslands.Note, config: JSONObject) {
        view.setToggleShown(false)
        view.bind(
            note.title.toString().ifBlank { appLabel(note.pkg) },
            note.text.toString(),
            noteBitmap(note),
            false,
            config,
            "#${MiniPlayerRuntime.materialGeneration}",
            { target -> MiniPlayerRuntime.material(target, loader) },
            {},
            {},
            {},
            {},
            { expandNote(note.key) },
        )
    }

    private val noteBitmaps = HashMap<String, Pair<Long, Bitmap?>>()

    /** A note's picture as the pill's artwork wants it: a bitmap, drawn once per update. */
    private fun noteBitmap(note: LockIslands.Note): Bitmap? {
        noteBitmaps[note.key]?.let { (time, bitmap) -> if (time == note.time) return bitmap }
        val drawable = note.icon
        val side = dp(56f)
        val bitmap = drawable?.let {
            runCatching {
                Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { b ->
                    val canvas = android.graphics.Canvas(b)
                    it.setBounds(0, 0, side, side)
                    it.draw(canvas)
                }
            }.getOrNull()
        }
        if (noteBitmaps.size > 60) noteBitmaps.clear()
        noteBitmaps[note.key] = note.time to bitmap
        return bitmap
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

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
                val ready = shown(scaled)
                if (selectedIsland == MUSIC_ISLAND) player?.showArtwork(ready)
                else if (smallKey == MUSIC_ISLAND) refresh()
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
        // Any island at all: the music, or a notification.
        val sessionUsable = islandKeys.isNotEmpty()
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
        // With music, the lock screen's media card has to be up; a row of notifications alone
        // needs only the lock screen.
        val presentable = if (islandKeys.firstOrNull() == MUSIC_ISLAND) Main.miniPlayerPresentable()
            else Main.miniPlayerIslandsPresentable()
        val sceneVisible = keyguardOwned && presentable && !MiniPlayerScene.blocksMiniPlayer
        // Unlocking or a session ending mid-morph: straight to where it was going.
        if (!keyguardOwned) morph?.cancel()
        discsWanted = keyguardOwned
        val controlCenterOpen = keyguardOwned &&
            (MiniPlayerScene.controlCenterIsActive || Main.miniPlayerControlCenterUp())
        val nativeRequested = MiniPlayerRuntime.nativeRequested(current?.sessionToken)
        val presentation = MiniPlayerPresentationPolicy.evaluate(
            MiniPlayerPresentationInput(
                enabled = enabled,
                sessionUsable = sessionUsable,
                nativeRequested = nativeRequested,
                keyguardOwned = keyguardOwned,
                sceneVisible = sceneVisible,
                nativeSceneOverride = keyguardOwned && Main.coverSceneActive(),
                // A notification's morph is not the media card's: the card stays put away.
                transitionActive = morph != null && noteMorphKey == null,
                controlCenterOpen = controlCenterOpen,
            ),
        )
        val shown = presentation.showMini
        // The stack leaves the notifications out only while the row is there to show them.
        LockIslands.setActive(shown && keyguardOwned)
        if (!keyguardOwned) selectedIsland = null
        // While a flight is out, its end is the small island's place: the small island is the flight.
        val flying = flight != null && morph != null
        setSmallShown(shown && smallKey != null && !flying, animate = shown && !snapSmallOnce)
        snapSmallOnce = false
        if (!keyguardOwned) preferredSmall = null
        if (view != null) {
            val target = if (shown) View.VISIBLE else View.GONE
            if (view.visibility != target) view.visibility = target
            view.setInteractionsEnabled(Main.miniPlayerCanShow() && !controlCenterOpen &&
                !MiniPlayerScene.aodActive)
        }
        updateNativeSuppression(presentation.suppressNative)
        if (morph == null && keyguardOwned &&
            (nativeRequested || Main.coverSceneActive())) ensureNativeHeaderVisible()
        val log = "nativeRequested=$nativeRequested " +
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
        val height = dp(MiniPlayerConfig.visibleHeightDp(config.toString()))
        // Clear of the discs, a circle as tall as the pill on each button. Laid out rather than
        // shown: the buttons are put away in the doze, and the pill must not widen for it.
        val width = MiniPlayerGeometry.clearOfDiscsPx(
            MiniPlayerGeometry.widthPx(min(requestedWidth, (host.width * .64f).toInt()),
                host.width, centerX, dp(12f)),
            centerX, l?.get(0), r?.get(0), height.toFloat(),
            dp(MiniPlayerGeometry.DISC_GAP_DP).toFloat(), dp(MiniPlayerGeometry.MIN_PILL_DP))
        // With a small island the pill makes room for it, the two centred as one group.
        val gap = dp(MiniPlayerGeometry.DISC_GAP_DP)
        val small = smallKey != null
        val pillWidth = if (small) maxOf(dp(MiniPlayerGeometry.MIN_PILL_DP), width - gap - height)
            else width
        val group = pillWidth + if (small) gap + height else 0
        val groupLeft = centerX - group / 2f
        if (small) {
            smallRest[0] = groupLeft + pillWidth + gap + height / 2f
            smallRest[1] = centerY
        }
        if (view.layoutParams.width != pillWidth || view.layoutParams.height != height) {
            view.layoutParams = view.layoutParams.apply { this.width = pillWidth; this.height = height }
            schedulePosition()
            return
        }
        // The asked-for size, not view.width: a morph resizes the frame while this still runs.
        val x = groupLeft - view.left
        val y = centerY - height / 2f - view.top
        view.setBaseTranslation(x, y)
        rowTarget(groupLeft, pillWidth.toFloat())
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

/** A disc's frame against its diameter: room for the widest swelling and squeeze. */
private const val DISC_FRAME = 1.6f

/** The music's place in the row of islands, beside the notifications' keys. */
private const val MUSIC_ISLAND = "\u0000music"

/** An island switch's spring: the super island's CHANGE_EASE. */
private const val SWAP_RESPONSE = 0.4f
private const val SWAP_DAMPING = 0.82f

/** The super island's swipe progress: the pull over half the width, times this. */
private const val SWIPE_SHARE = 0.14f

/** A pull under the threshold goes home in this long. */
private const val SPRING_BACK_MS = 320L

/** How long a released notification's row may take to be laid out before the pill moves on. */
private const val ROW_WAIT_MS = 800L

/** A row's picture, title and text, by the ids the notification templates give them. */
private val ICON_NAMES = setOf("right_icon", "icon", "app_icon", "notification_icon", "left_icon")
private val TITLE_NAMES = setOf("title", "notification_title")
private val TEXT_NAMES = setOf("text", "big_text", "notification_text")

/** A notification island pulled this share of the way to its row opens on release. */
private const val NOTE_DRAG_COMMIT = 0.35f

/** ...or flung up faster than this, in dp per second. */
private const val NOTE_FLING_PX_S = 1200f

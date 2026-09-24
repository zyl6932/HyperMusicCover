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
                    val recorded = recording
                    if (mine && recorded.isNullOrEmpty()) {
                        // An effect that made no material call on a card background - with no
                        // media card on the lock screen, one on another media view: kept, it
                        // replaced the card's recipe with nothing, and the pill, dressed again
                        // from it, went clear while the discs kept their glass (2026-09-25).
                        recording = null
                        recordTarget = null
                        if (emptyEffect != cls.simpleName) {
                            emptyEffect = cls.simpleName
                            Xp.log("MCMini: ${cls.simpleName} recorded no material calls; recipe kept")
                        }
                    } else if (mine) {
                        val bg = recordTarget
                        cardRecipe = recorded
                        cardBackground = bg?.background?.constantState
                        recording = null
                        recordTarget = null
                        runCatching { keepRecipe(target!!.context, recorded!!, bg?.background) }
                            .onFailure { Xp.log("MCMini: recipe not kept: $it") }
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

    // ---- the card's recipe, kept

    /**
     * The recipe is recorded only when the lock screen dresses a media card, and it lived in
     * memory: after SystemUI restarted with no music, there was no card to record from and the
     * row of notification islands was a flat grey fill until music played (2026-09-25). It is
     * kept in SystemUI's own preferences now, call by call - class, method, parameter types and
     * the plain values - and read back the first time a pill is dressed without one.
     */
    private const val RECIPE_KEY = "card_recipe_v1"
    private var recipeLoaded = false

    private fun keepRecipe(context: Context, list: List<Recorded>, bg: android.graphics.drawable.Drawable?) {
        val calls = org.json.JSONArray()
        for (r in list) {
            val args = org.json.JSONArray()
            r.args.forEachIndexed { i, a ->
                args.put(when {
                    i == r.viewAt -> JSONObject().put("t", "view")
                    a == null -> JSONObject().put("t", "null")
                    a is Int -> JSONObject().put("t", "i").put("x", a)
                    a is Long -> JSONObject().put("t", "l").put("x", a)
                    a is Float -> JSONObject().put("t", "f").put("x", a.toDouble())
                    a is Double -> JSONObject().put("t", "d").put("x", a)
                    a is Boolean -> JSONObject().put("t", "b").put("x", a)
                    a is String -> JSONObject().put("t", "s").put("x", a)
                    a is IntArray -> JSONObject().put("t", "ia").put("x", org.json.JSONArray(a.toList()))
                    a is FloatArray -> JSONObject().put("t", "fa")
                        .put("x", org.json.JSONArray(a.map { it.toDouble() }))
                    a is Context -> JSONObject().put("t", "ctx")
                    // Anything else cannot be written down: the recipe is not kept at all.
                    else -> error("argument ${a.javaClass.name} of ${r.method.name}")
                })
            }
            calls.put(JSONObject()
                .put("c", r.method.declaringClass.name)
                .put("m", r.method.name)
                .put("p", org.json.JSONArray(r.method.parameterTypes.map { it.name }))
                .put("v", r.viewAt)
                .put("a", args))
        }
        val kept = JSONObject().put("calls", calls)
        when (bg) {
            is GradientDrawable -> kept.put("bg", JSONObject()
                .put("t", "gradient")
                .put("color", bg.color?.defaultColor ?: 0)
                .put("radius", bg.cornerRadius.toDouble()))
            is android.graphics.drawable.ColorDrawable -> kept.put("bg", JSONObject()
                .put("t", "color").put("color", bg.color))
        }
        val text = kept.toString()
        val p = prefs(context)
        if (p.getString(RECIPE_KEY, null) != text) p.edit().putString(RECIPE_KEY, text).apply()
    }

    /** The recipe kept from an earlier dressing, when none has been recorded since SystemUI started. */
    private fun loadRecipe(context: Context, classLoader: ClassLoader) {
        if (recipeLoaded || cardRecipe != null) return
        recipeLoaded = true
        runCatching {
            val text = prefs(context).getString(RECIPE_KEY, null) ?: return
            val kept = JSONObject(text)
            val calls = kept.getJSONArray("calls")
            val list = ArrayList<Recorded>()
            fun type(name: String): Class<*> = when (name) {
                "int" -> Int::class.javaPrimitiveType!!
                "long" -> Long::class.javaPrimitiveType!!
                "float" -> Float::class.javaPrimitiveType!!
                "double" -> Double::class.javaPrimitiveType!!
                "boolean" -> Boolean::class.javaPrimitiveType!!
                else -> Class.forName(name, false, classLoader)
            }
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val params = c.getJSONArray("p")
                val types = Array(params.length()) { type(params.getString(it)) }
                val method = Class.forName(c.getString("c"), false, classLoader)
                    .getDeclaredMethod(c.getString("m"), *types).apply { isAccessible = true }
                val a = c.getJSONArray("a")
                val args = Array<Any?>(a.length()) { j ->
                    val o = a.getJSONObject(j)
                    when (o.getString("t")) {
                        "view", "null" -> null
                        "i" -> o.getInt("x")
                        "l" -> o.getLong("x")
                        "f" -> o.getDouble("x").toFloat()
                        "d" -> o.getDouble("x")
                        "b" -> o.getBoolean("x")
                        "s" -> o.getString("x")
                        "ia" -> o.getJSONArray("x").let { x -> IntArray(x.length()) { x.getInt(it) } }
                        "fa" -> o.getJSONArray("x").let { x -> FloatArray(x.length()) { x.getDouble(it).toFloat() } }
                        "ctx" -> context
                        else -> error("argument type ${o.getString("t")}")
                    }
                }
                list.add(Recorded(method, args, c.getInt("v")))
            }
            kept.optJSONObject("bg")?.let { bg ->
                cardBackground = when (bg.getString("t")) {
                    "gradient" -> GradientDrawable().apply {
                        setColor(bg.getInt("color"))
                        cornerRadius = bg.getDouble("radius").toFloat()
                    }.constantState
                    "color" -> android.graphics.drawable.ColorDrawable(bg.getInt("color")).constantState
                    else -> null
                }
            }
            cardRecipe = list
            cardEffect = "kept"
            Xp.log("MCMini: card recipe read back from preferences (${list.size} calls)")
        }.onFailure { Xp.log("MCMini: kept recipe unusable: $it") }
    }

    /** Which effect dressed the card last, for the log and `op mini`. */
    @Volatile private var cardEffect = "none"

    /** The last effect whose empty recording was thrown away, for `op mini`. */
    @Volatile private var emptyEffect = "none"

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
            routedPullRefused = false
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
            if (routedSmall && pillOwner != null && !routedMorph) {
                // Taken where it is, the pill's nudge left alone.
                pill?.springNudgeBack(0f, 0f)
                pillOwner.holdSmallNudge()
                pillOwner.smallNudgeNow.let { (x, y) -> routedBaseX = x; routedBaseY = y }
            }
            val hit = if (pill != null) pillOwner?.let { it to if (routedSmall) PRESS_SMALL else PRESS_PILL }
                else live().firstNotNullOfOrNull { c -> c.discAt(ev.rawX, ev.rawY)?.let { c to it } }
            hit?.let { (owner, side) ->
                owner.press(side, true)
                pressed = WeakReference(owner)
                pressedSide = side
            }
            noteTouch("down ${ev.rawX.toInt()},${ev.rawY.toInt()} pill=${pill != null} " +
                "small=$routedSmall caught=$routedMorph disc=${hit?.second}" +
                (pillOwner?.smallProbe(ev.rawX, ev.rawY)?.let { " [$it]" } ?: ""))
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
                        noteTouch("pull up: pill to card (small=$routedSmall)")
                    } else {
                        // A notification island - the pill's or the small one - opens into its row.
                        val o = routedOwner ?: live().firstOrNull { it.pill() === target }
                        val key = o?.noteKeyFor(routedSmall)
                        if (o != null && key != null && o.beginNoteDrag(key, routedSmall, routedY)) {
                            routedNote = true
                            routedOwner = o
                            noteTouch("pull up: note small=$routedSmall")
                        } else if (!routedPullRefused) {
                            routedPullRefused = true
                            noteTouch("pull up: nothing (small=$routedSmall key=${key != null} " +
                                "busy=${o?.noteBusy()})")
                        }
                    }
                }
                if (routedMorph) dragMove(ev, nx)
                else if (routedNote) routedOwner?.noteDragMove(ev.rawY, nx)
                else if (routedIsland) routedOwner?.islandDrag(dx)
                // The small island follows the finger as the pill does, and presses as it does.
                else if (routedSmall) routedOwner?.setSmallNudge(routedBaseX + nx, routedBaseY + pillNudgeY(dy, d))
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
                } else if (routedNote) {
                    routedOwner?.noteDragEnd(vy, cancelled)
                    noteTouch("note pull dy=${dy.toInt()} cancelled=$cancelled")
                } else if (routedIsland) {
                    // The super island's threshold: past 50dp (or flung) the switch runs.
                    val commit = !cancelled && (kotlin.math.abs(dx) >= ISLAND_SWIPE_DP * d ||
                        kotlin.math.abs(vx) > 1200f * d)
                    routedOwner?.islandDragEnd(commit, next = dx < 0f)
                    noteTouch("island swipe dx=${dx.toInt()} commit=$commit")
                } else if (routedSmall) {
                    // The small island's nudge only ever springs home: its sideways swipe is
                    // the row's, its pull up its row's.
                    routedOwner?.springSmallNudgeBack(vx, vy)
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

    /** The pull up this gesture could not open anything with has been logged once. */
    private var routedPullRefused = false

    private var pressed: WeakReference<MiniPlayerController>? = null
    private var pressedSide = PRESS_PILL

    private fun releasePress() {
        pressed?.get()?.press(pressedSide, false)
        pressed = null
    }

    /** The side a press is on: the pill, or a disc's index (0 the torch, 1 the camera). */
    internal const val PRESS_PILL = -1
    internal const val PRESS_SMALL = -2

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
        val owner = dragOwner
        if (owner == null) {
            // A notification's row pulled down into its island: the same pull, the island's morph.
            noteTracker?.addMovement(ev)
            noteOwner?.noteDragMove(ev.rawY, nudgeX)
            return
        }
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
        val owner = dragOwner
        if (owner == null) {
            val note = noteOwner ?: return
            noteOwner = null
            val tracker = noteTracker
            noteTracker = null
            tracker?.addMovement(ev)
            tracker?.computeCurrentVelocity(1000)
            val vy = tracker?.yVelocity ?: 0f
            tracker?.recycle()
            note.noteDragEnd(vy, cancelled)
            return
        }
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
        val (toNative, velocity) = lift(pulled(ev.rawY), if (dragFromNative) vy else -vy,
            dragFromNative, dragThreshold, dragSpan, cancelled)
        owner.releaseDrag(toNative, velocity)
    }

    /**
     * Where a lift goes, for every island's pull alike - the music's into its card, a
     * notification's into its row, and each back: [pulled] pixels toward the far end, [speed]
     * that way in pixels a second. Past half way, or flung past it, it reaches the far end.
     * Returns whether it ends at the card or row, and the progress speed to hand the spring.
     */
    internal fun lift(pulled: Float, speed: Float, fromNative: Boolean, threshold: Float,
                      span: Float, cancelled: Boolean): Pair<Boolean, Float> {
        val engaged = ((pulled - threshold) / span).coerceAtLeast(0f)
        val projected = engaged + speed / span * 0.18f
        val far = !cancelled && projected > 0.5f
        val toNative = if (fromNative) !far else far
        val velocity = (if (fromNative) -speed else speed) / span
        return toNative to if (engaged > 0f) velocity else 0f
    }

    /** A notification row being pulled down into its island, and its finger's speed. */
    private var noteOwner: MiniPlayerController? = null
    private var noteTracker: VelocityTracker? = null

    /** Pixels the finger has gone toward the far end (negative: the wrong way). */
    private fun pulled(y: Float) = if (dragFromNative) y - dragStartY else dragStartY - y

    /**
     * Finger to (progress, nudge). Short of the threshold only the nudge moves, rubber-banded;
     * past it the progress follows the finger 1:1 over the span while the nudge the threshold
     * left hands over to it, so crossing is seamless; past the far end, rubber again.
     */
    private fun dragPose(y: Float): Pair<Float, Float> =
        pose(pulled(y), dragFromNative, dragThreshold, dragSpan, dragNudgeLimit)

    /**
     * The same for every island's pull, [pulled] pixels toward the far end. The nudge is in
     * MiniCardMorph's sense, toward the card or row: pulled from that end, the finger's way
     * is away from it. Given the far end's sign from a card pulled down, the card went up
     * under a finger going down for the first 72dp.
     */
    internal fun pose(pulled: Float, fromNative: Boolean, threshold: Float, span: Float,
                      nudgeLimit: Float): Pair<Float, Float> {
        val base = if (fromNative) 1f else 0f
        val dir = if (fromNative) -1f else 1f
        if (pulled <= threshold) {
            val nudge = if (pulled >= 0f) MiniCardMorph.rubber(pulled, nudgeLimit)
                else -MiniCardMorph.rubber(-pulled, nudgeLimit * 0.5f)
            return base to dir * nudge
        }
        val held = MiniCardMorph.rubber(threshold, nudgeLimit)
        var p = (pulled - threshold) / span
        if (p > 1f) p = 1f + MiniCardMorph.rubber(p - 1f, 0.2f)
        val nudge = held * (1f - p.coerceIn(0f, 1f))
        return (base + dir * p) to dir * nudge
    }

    /** What a pull's threshold and nudge are, in pixels, for this density. */
    internal fun dragThresholdPx(density: Float) = DRAG_THRESHOLD_DP * density
    internal fun dragNudgePx(density: Float) = DRAG_NUDGE_DP * density

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
        val sb = StringBuilder("material=$cardEffect calls=${cardRecipe?.size} empty=$emptyEffect " +
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

    /**
     * That row folds back into the row of islands, following the finger from [ev] on as the
     * media card does when it is pulled down into the pill.
     */
    @JvmStatic fun collapseRow(key: String, ev: MotionEvent): Boolean {
        val owner = live().firstOrNull { it.collapseRow(key, ev.rawY) } ?: return false
        noteOwner = owner
        noteTracker?.recycle()
        noteTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
        return true
    }

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
            if (cardRecipe == null) loadRecipe(ctx, classLoader)
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
        traceScene()
        true
    }

    // ---- a scene entry, every view of the row frame by frame, for `op mini`

    private val sceneTrace = ArrayDeque<String>()
    private var sceneTraceFrames = 0

    private fun startSceneTrace() {
        sceneTrace.clear()
        sceneTraceFrames = 90
    }

    private fun traceScene() {
        if (sceneTraceFrames <= 0) return
        sceneTraceFrames--
        fun v(name: String, view: View?): String {
            if (view == null) return " $name=-"
            val xy = IntArray(2).also(view::getLocationOnScreen)
            return " $name=${view.visibility}/a${"%.2f".format(view.alpha)}/t${"%.2f".format(view.transitionAlpha)}" +
                "@${xy[0]},${xy[1]} ${view.width}x${view.height} z${view.translationZ.toInt()}"
        }
        val sb = StringBuilder("${android.os.SystemClock.uptimeMillis() % 100000} morph=${morph != null} " +
            "scene=${Main.coverSceneActive()} landed=${sceneLandedAt != 0L} solo=$soloRow")
        sb.append(v("pill", player)).append(v("small", smallIsland)).append(v("flight", flight))
            .append(v("ghost", ghostPill)).append(v("gdisc", ghostDisc))
        sb.append(" pillArt=[").append(player?.artworkState()).append(']')
        sceneTrace.addLast(sb.toString())
        while (sceneTrace.size > 100) sceneTrace.removeFirst()
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
        // A flight's morph is not the pill's: the pill (and the small island coming up beside
        // it) still ride with the buttons while the flight is out.
        if (morph != null && flight == null) {
            view.setAnimationMatrix(null)
            // Sliding back in under the pill's end as the pill widens over it.
            if (soloRow) smallIsland?.let { placeSmallIsland(null, it.transitionAlpha) }
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
        // In pixels, not shares: with a small island the row the squeeze measured is wider
        // than the pill, and its shares of the row are not the pill's.
        val sx = squeeze.pillScaleX(view.width.toFloat())
        val sy = squeeze.pillScaleY()
        val shift = squeeze.pillShiftPx()
        if (kotlin.math.abs(sx - 1f) > 0.0005f || kotlin.math.abs(sy - 1f) > 0.0005f
            || kotlin.math.abs(shift) > 0.25f) {
            pillSqueeze.setScale(sx, sy, view.translationX + view.width / 2f,
                view.translationY + view.height / 2f)
            pillSqueeze.postTranslate(shift, 0f)
            matrix.preConcat(pillSqueeze)
        }
        // Only a real change is written: each write invalidates the pill.
        matrix.getValues(followValues)
        if (!followValues.contentEquals(lastFollow)) {
            System.arraycopy(followValues, 0, lastFollow, 0, 9)
            if (matrix.isIdentity) view.setAnimationMatrix(null) else view.setAnimationMatrix(matrix)
        }
        // A notification flying out of the pill, or home into it, is the flight: the pill under
        // it stays out of sight until the flight lands on it.
        val pillFade = if (pillHeld()) 0f else fade
        if (kotlin.math.abs(view.transitionAlpha - pillFade) > 0.002f) view.transitionAlpha = pillFade
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

    /** The row as it was last laid out: where a notification let out of it had its place. */
    private var islandOrder: List<String> = emptyList()

    /**
     * [key] - let out of the row and not among the notes for now - kept where it was in the row,
     * not at its end. Appended, the island after it was the row's first: with three islands, the
     * second pulled up out of the pill had the first's picture come up in the small place instead
     * of the third's, and it went back to the third's once the notification was home (2026-09-25).
     */
    private fun keptInPlace(base: List<String>, key: String): List<String> {
        if (key in base) return base
        val at = islandOrder.indexOf(key)
        if (at < 0) return base + key
        val before = islandOrder.subList(0, at).toSet()
        val insertAt = base.indexOfLast { it in before } + 1
        return base.toMutableList().apply { add(insertAt, key) }
    }

    /** How many islands the row has while the pill is showing; one is the pill alone. */
    fun islandCount(): Int = if (player?.visibility == View.VISIBLE) islandKeys.size else 0

    fun selectedIsMusic(): Boolean = selectedIsland == MUSIC_ISLAND

    /** The notification a pull opens: the small island's, or the pill's; none for the music. */
    fun noteKeyFor(small: Boolean): String? =
        (if (small) smallKey else selectedIsland)?.takeIf { it != MUSIC_ISLAND }

    /** For the touch log: the small island as a DOWN saw it, when there is one. */
    fun smallProbe(x: Float, y: Float): String? {
        val v = smallIsland ?: return null
        val xy = IntArray(2).also(host::getLocationOnScreen)
        val dist = kotlin.math.hypot(x - xy[0] - smallRest[0], y - xy[1] - smallRest[1]).toInt()
        return "sk=${smallKey?.takeLast(12)} v=${v.visibility} grow=$smallGrowing " +
            "at=${(xy[0] + smallRest[0]).toInt()},${(xy[1] + smallRest[1]).toInt()} dist=$dist " +
            "r=${discDiameter() / 2}"
    }

    /** For the touch log: why a notification could not be pulled out. */
    fun noteBusy(): String = "noteMorph=${noteMorphKey != null} morph=${morph != null} " +
        "flight=${flight != null} rowWait=${rowWaitKey != null}"

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
        // Two islands just trade places; with more, one goes into hiding and another comes
        // out of it, as the super island's row does (SwipeEventCoordinator).
        startSwap(oldBig, oldSmall, when {
            keys.size < 3 -> SWAP_PAIR
            next -> SWAP_NEXT
            else -> SWAP_PREV
        })
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

    /**
     * Whether a point on screen is on the small island's circle, a little slop included - less
     * than the gap to the camera's disc, so a tap on that disc's near edge stays the camera's.
     */
    fun smallIslandAt(x: Float, y: Float): Boolean {
        val v = smallIsland ?: return false
        if (v.visibility != View.VISIBLE || smallKey == null) return false
        val xy = IntArray(2).also(host::getLocationOnScreen)
        val r = discDiameter() / 2f + minOf(dp(6f), dp(MiniPlayerGeometry.DISC_GAP_DP) * 3 / 4)
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
        // A notification folding back into the small island's place, or pulled out of it and
        // not let go yet, holds that place for as long as its flight is out: whatever was
        // there shrinks away and the flight lands in it. One that has left for good (a tap, a
        // committed pull) frees it: the next island comes up there while the flight is still
        // on its way, and with none the pill takes the room back - the first version held the
        // place empty till the flight had settled, and the next one popped up half a second
        // after (filmed 2026-09-25).
        val flying = noteMorphKey?.takeIf { flight != null }
        val held = flying?.takeIf { !flightOut }
        val keys = if (flying != null && flightOut) islandKeys - flying else islandKeys
        val preferred = preferredSmall?.takeIf { it in keys && it != selectedIsland }
        val key = when {
            held != null && held != selectedIsland -> held
            keys.size < 2 -> null
            preferred != null -> preferred
            else -> keys[(keys.indexOf(selectedIsland).coerceAtLeast(0) + 1) % keys.size]
        }
        val previous = smallKey
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
        if (swap == null && !islandDragging && !smallGrowing && landingBox == null) view.setShape(d, d)
        // The one showing, going for a flight coming in, goes as itself: it keeps its picture
        // while it shrinks away. It used to take the incoming one's at once - and, asked only
        // while it still showed, took it anyway on the next refresh, mid-shrink. It takes the
        // flight's picture once the flight is landing on it (flightLanding).
        if (key == held && !flightLanding) return
        val showing = view.visibility == View.VISIBLE && !(smallGrowing && smallGrow.target == 0f)
        val picture: Any? = if (key == MUSIC_ISLAND) (thumbShown ?: cachedCover)
            else notes.firstOrNull { it.key == key }?.icon ?: LockIslands.noteFor(key)?.icon
        // Another island in a small island that is showing, nothing else moving it (a switch
        // animates its own): it comes up anew in the place rather than just changing its picture.
        if (previous != null && previous != key && showing && swap == null && !islandDragging &&
            held == null && !snapSmallOnce) regrowSmall()
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
        clearSmallNudge()
        smallKey = null
        smallGrowing = false
        smallIsland?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
    }

    /** A notification just folded back in, not yet given back by the stack: it keeps its place. */
    private var returning: String? = null
    private var returningSince = 0L

    /** The notification last folded back in: it stays the small island until the row moves on. */
    private var preferredSmall: String? = null

    /** The next visibility pass shows the small island at once: a flight has just landed there. */
    private var snapSmallOnce = false

    /**
     * The flight has left the small island's place for good - a tap, or a pull let go past
     * the threshold - and the place is the row's again. Until then it is the flight's to come
     * back to.
     */
    private var flightOut = false

    /**
     * The flight is on its last stretch home: the real small island is already in its place
     * under it and the flight fades off it. Swapped only once it had landed, the glass's
     * refraction and the picture both changed on that frame (filmed 2026-09-25).
     */
    private var flightLanding = false

    /**
     * Landing, the small island is the flight's shape frame by frame - through the spring's
     * overshoot and back - and not its own rest circle: the flight fades off an island moving
     * exactly as it does. Put at rest under the flight, the small island stood still while the
     * flight went through the overshoot invisible, and the landing had no give at all, where
     * the pill's has (2026-09-25). On screen; null when not landing.
     */
    private var landingBox: CoverMorphMotion.Box? = null

    /** The small island on its rest place, carried by the row's motion as the pill is. */
    private fun placeSmallIsland(follow: Matrix?, fade: Float) {
        val v = smallIsland ?: return
        if (v.visibility != View.VISIBLE) return
        // Its frame is centred on its rest place; a switch only widens the frame, never moves it.
        val fw = if (v.width > 0) v.width else v.layoutParams.width
        val fh = if (v.height > 0) v.height else v.layoutParams.height
        landingBox?.let { box ->
            // The flight's shape, where the flight draws it: the morph places both.
            val xy = IntArray(2).also(host::getLocationOnScreen)
            val lw = maxOf(v.layoutParams.width, fw)
            val lh = maxOf(v.layoutParams.height, fh)
            v.translationX = box.cx() - xy[0] - lw / 2f - v.left
            v.translationY = box.cy() - xy[1] - lh / 2f - v.top
            v.scaleX = 1f
            v.scaleY = 1f
            // Whole: it may have been shrinking away for the flight when the flight came in -
            // left at that alpha (0.08), the landing had nothing under the flight (2026-09-25).
            if (v.alpha != 1f) v.alpha = 1f
            v.setIconAlpha(1f)
            v.setShape(box.w.roundToInt().coerceAtLeast(1), box.h.roundToInt().coerceAtLeast(1), 0)
            if (lastSmallFollow.any { it != 0f }) {
                lastSmallFollow.fill(0f)
                v.setAnimationMatrix(null)
            }
            if (kotlin.math.abs(v.transitionAlpha - fade) > 0.002f) v.transitionAlpha = fade
            return
        }
        // The camera pushing on the row takes the small island back first: it is the row's end.
        val d = discDiameter().toFloat()
        val tx = smallRest[0] - fw / 2f - v.left - squeeze.rowGivePx(1) + smallDx + smallNudgeX() +
            squeeze.smallShift() * d
        val ty = smallRest[1] - fh / 2f - v.top + smallNudgeY()
        // Flattened by the pill, by its shape - a scale would draw its edge jagged - when nothing
        // else is shaping it this frame.
        if (swap == null && !smallGrowing && !islandDragging) {
            v.setShape((d * squeeze.smallShapeX()).roundToInt().coerceAtLeast(1),
                (d * squeeze.smallShapeY()).roundToInt().coerceAtLeast(1), 0)
        }
        // A finger on it sinks it about its centre, which is its frame's centre.
        val swell = squeeze.smallSwell()
        if (kotlin.math.abs(v.scaleX - swell) > 0.0005f) {
            v.scaleX = swell
            v.scaleY = swell
        }
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

    /**
     * The small island under a finger that has not opened or switched anything: it follows,
     * rubber-banded as the pill is, any way round, pressing on the camera and the pill; let go,
     * it springs home. In hundreds of pixels, as the pill's nudge spring runs.
     */
    private val smallNudgeXs = Jelly(SMALL_NUDGE_RESPONSE, SMALL_NUDGE_DAMPING)
    private val smallNudgeYs = Jelly(SMALL_NUDGE_RESPONSE, SMALL_NUDGE_DAMPING)
    private var smallNudging = false
    private var smallNudgeLast = 0L

    private fun smallNudgeX() = smallNudgeXs.value * 100f
    private fun smallNudgeY() = smallNudgeYs.value * 100f

    private val smallNudgeFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!smallNudging) return
            val dt = if (smallNudgeLast == 0L) 1f / 120f
                else ((frameTimeNanos - smallNudgeLast) / 1e9f).coerceIn(0f, 0.05f)
            smallNudgeLast = frameTimeNanos
            smallNudgeXs.step(dt)
            smallNudgeYs.step(dt)
            if (smallNudgeXs.atRest() && smallNudgeYs.atRest()) smallNudging = false
            else Choreographer.getInstance().postFrameCallback(this)
            nudgedSmall()
        }
    }

    /** Where the finger has pulled the small island to, in pixels; the caller rubber-bands it. */
    fun setSmallNudge(x: Float, y: Float) {
        holdSmallNudge()
        smallNudgeXs.value = x / 100f; smallNudgeXs.target = smallNudgeXs.value; smallNudgeXs.velocity = 0f
        smallNudgeYs.value = y / 100f; smallNudgeYs.target = smallNudgeYs.value; smallNudgeYs.velocity = 0f
        nudgedSmall()
    }

    /** A finger on a small island still springing home: it stays where it is, under the finger. */
    fun holdSmallNudge() {
        smallNudging = false
        Choreographer.getInstance().removeFrameCallback(smallNudgeFrame)
    }

    val smallNudgeNow: Pair<Float, Float> get() = smallNudgeX() to smallNudgeY()

    /** Home with the finger's own speed, so a fling carries past the place and back. */
    fun springSmallNudgeBack(vx: Float, vy: Float) {
        if (smallNudgeXs.value == 0f && smallNudgeYs.value == 0f) return
        smallNudgeXs.target = 0f
        smallNudgeYs.target = 0f
        smallNudgeXs.velocity = vx / 100f
        smallNudgeYs.velocity = vy / 100f
        if (!smallNudging) {
            smallNudging = true
            smallNudgeLast = 0L
            Choreographer.getInstance().postFrameCallback(smallNudgeFrame)
        }
    }

    /**
     * Straight home: the small island is going, or something else is moving it now. Only the
     * springs: the next frame places it. Laying the row out from here brought the discs back
     * on a controller being destroyed, which removes them first.
     */
    private fun clearSmallNudge() {
        holdSmallNudge()
        for (spring in arrayOf(smallNudgeXs, smallNudgeYs)) {
            spring.value = 0f
            spring.target = 0f
            spring.velocity = 0f
        }
    }

    /** The row again, this frame: the squeeze first, then the small island placed by it. */
    private fun nudgedSmall() {
        updateDiscs()
        if (player?.visibility == View.VISIBLE) followShortcuts()
    }

    private fun removeSmallIsland() {
        clearSmallNudge()
        ghostPill?.let { runCatching { host.removeView(it) } }
        ghostPill = null
        ghostDisc?.let { runCatching { host.removeView(it) } }
        ghostDisc = null
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
        /**
         * SMALL_FROM_PILL: the small island forms out of the pill's place (it was the pill's).
         * SMALL_POP: another island comes up in the small place. SMALL_EMERGE: the next one
         * slides out from under the pill's end, as the super island's HiddenToSmallIsland.
         * SMALL_KEPT: it is the one that was there, and the switch leaves it alone.
         */
        val smallMode: Int,
        /** SWAP_PAIR: two islands trading places. SWAP_NEXT / SWAP_PREV: a row of three or more. */
        val kind: Int = SWAP_PAIR,
    ) {
        /** The pill's frame: CHANGE_EASE out of the small island, APPEAR_EASE out of the middle. */
        val spring = (if (kind == SWAP_PREV) Jelly(APPEAR_RESPONSE, APPEAR_DAMPING)
            else Jelly(SWAP_RESPONSE, SWAP_DAMPING)).apply { value = 0f; target = 1f }
        /** The small island's part: CHANGE_EASE forming out of the pill, APPEAR_EASE emerging. */
        val small = (if (smallMode == SMALL_EMERGE) Jelly(APPEAR_RESPONSE, APPEAR_DAMPING)
            else Jelly(SWAP_RESPONSE, SWAP_DAMPING)).apply { value = 0f; target = 1f }
        /** Out of the middle, the content comes into focus on HIDDEN_EASE, 100ms late. */
        val contentIn = Jelly(HIDDEN_RESPONSE, HIDDEN_DAMPING).apply { value = 0f; target = 1f }
        /** The old big island into the middle: its frame on SHOW_EASE, its content on ALPHA_EASE. */
        val ghostBox = Jelly(SHOW_RESPONSE, SHOW_DAMPING).apply { value = 0f; target = 1f }
        val ghostContent = Jelly(ALPHA_RESPONSE, ALPHA_DAMPING).apply { value = 0f; target = 1f }
        /** The old small island going into hiding where it is, on HIDDEN_EASE. */
        val ghostDisc = Jelly(HIDDEN_RESPONSE, HIDDEN_DAMPING).apply { value = 0f; target = 1f }
        var ghostFrom: CoverMorphMotion.Box? = null
        var ghost = false
        var disc = false
        /** The stand-in goes into the small island's place (BigIslandToSmallIsland), on [small]. */
        var ghostSmall = false
        var started = 0L
        var last = 0L

        fun step(dt: Float, now: Long) {
            spring.step(dt)
            small.step(dt)
            if (now - started >= CONTENT_IN_DELAY_MS) contentIn.step(dt)
            if (ghost) { ghostBox.step(dt); ghostContent.step(dt) }
            if (disc) ghostDisc.step(dt)
        }

        fun atRest(now: Long) = spring.atRest() && small.atRest() &&
            (kind != SWAP_PREV || (now - started >= CONTENT_IN_DELAY_MS && contentIn.atRest())) &&
            (!ghost || (ghostBox.atRest() && ghostContent.atRest())) && (!disc || ghostDisc.atRest())
    }

    private var swap: Swap? = null

    private val swapFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val s = swap ?: return
            val dt = if (s.last == 0L) 1f / 120f
                else ((frameTimeNanos - s.last) / 1e9f).coerceIn(0f, 0.05f)
            s.last = frameTimeNanos
            val now = android.os.SystemClock.uptimeMillis()
            s.step(dt, now)
            applySwap(s)
            if (s.atRest(now)) endSwap() else Choreographer.getInstance().postFrameCallback(this)
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
    private fun startSwap(oldBig: String?, oldSmall: String?, kind: Int = SWAP_PAIR,
                          intoSmall: Boolean = false) {
        val view = player ?: return
        endSwap()
        endRow()
        resetIslandDrag()
        clearSmallNudge()
        // A flight's morph is the flight's: the pill still switches under it.
        if (view.visibility != View.VISIBLE || morph != null && flight == null) return
        // Where the old big island was, before position() lays the pill out for the new row.
        val oldRest = view.restBoxOnScreen()
        val oldSmallBox = smallBoxOnScreen()
        // The pill's size for the new row, as position() is about to lay it out. position() sets
        // the row's spring going toward it; the switch has the frame, so the spring stops again -
        // left running, it drew over the switch every frame and the pill went straight from its
        // old width to its new one instead of growing out of the small island (filmed 2026-09-25).
        position()
        endRow()
        val rest = view.restBoxOnScreen() ?: return
        val d = discDiameter().toFloat()
        val fromSmall = oldSmall != null && selectedIsland == oldSmall
        val pillFrom = when {
            // Out of hiding, the super island's HiddenToBigIsland: out of the middle.
            kind == SWAP_PREV -> cutoutBox(rest)
            fromSmall -> oldSmallBox
            else -> CoverMorphMotion.Box(rest.x, rest.y, d, rest.h)
        }
        // Growing out of the small island's own circle with nothing to take its place: the circle
        // is the pill from here, not something left behind to shrink away under it.
        if (fromSmall && smallKey == null) setSmallShown(false, animate = false)
        // The pill held for a flight landing on it shows nothing of the island leaving it: that
        // island goes to the small place as a stand-in of itself, content and all, and the
        // small island takes over from it there. Formed out of the pill's place as a bare
        // circle, the island's picture came in only at the end - the music vanished from the
        // pill the moment the row was pulled and came back beside it (filmed 2026-09-25).
        val ghostToSmall = intoSmall && oldBig != null && smallKey == oldBig && oldRest != null
        val mode = when {
            smallKey == null -> SMALL_KEPT
            ghostToSmall -> SMALL_FROM_GHOST
            smallKey == oldBig -> SMALL_FROM_PILL
            smallKey != oldSmall -> if (kind == SWAP_NEXT) SMALL_EMERGE else SMALL_POP
            else -> SMALL_KEPT
        }
        val s = Swap(pillFrom, rest, mode, kind)
        s.started = android.os.SystemClock.uptimeMillis()
        swap = s
        // The old big island into hiding (BigIslandToHidden): a stand-in of it, under the
        // row, shrinks into the middle while its content goes out of focus.
        if (kind == SWAP_NEXT && oldRest != null && oldBig != null && startGhost(oldBig, oldRest)) {
            s.ghostFrom = oldRest
            s.ghost = true
        }
        if (ghostToSmall && startGhost(oldBig!!, oldRest!!)) {
            s.ghostFrom = oldRest
            s.ghostSmall = true
        }
        // The old small island into hiding (SmallIslandToHidden): a stand-in of it, where it is.
        // Also when the island leaving the pill takes the small place from it: with three
        // islands, the small one was simply replaced there, gone in a frame (2026-09-25).
        if ((kind == SWAP_PREV || ghostToSmall) && oldSmall != null && oldSmall != smallKey &&
            startGhostDisc(oldSmall)) {
            s.disc = true
        }
        view.beginMorph(layoutOnly = true)
        if (mode != SMALL_KEPT) {
            // The switch has the small island now: whatever it was growing into, it stops.
            smallGrowing = false
            Choreographer.getInstance().removeFrameCallback(smallGrowFrame)
            smallGrow.value = 1f
            smallGrow.target = 1f
            smallGrow.velocity = 0f
            smallIsland?.let { if (it.visibility != View.VISIBLE) it.visibility = View.VISIBLE }
            widenSmallIsland(rest)
        }
        applySwap(s)
        Choreographer.getInstance().postFrameCallback(swapFrame)
    }

    /** The camera cutout's stand-in: where an island hides, a small capsule mid-row. */
    private fun cutoutBox(rest: CoverMorphMotion.Box): CoverMorphMotion.Box {
        val side = rest.h * CUTOUT_SHARE
        return CoverMorphMotion.Box(rest.cx() - side / 2f, rest.cy() - side / 2f, side, side)
    }

    private fun swapBlurPx() = dp(SWAP_BLUR_DP).toFloat()

    private fun applySwap(s: Swap) {
        val view = player ?: return
        val p = s.spring.value
        val grown = lerpBox(s.pillFrom, s.pillTo, p)
        // Soft as the row's spring is: thinner while it widens fast, a touch taller coming back
        // off its overshoot (applyRow). The widening speed in pixels a second.
        val widening = s.spring.velocity * (s.pillTo.w - s.pillFrom.w)
        val squash = (widening / s.pillTo.w.coerceAtLeast(1f) * ROW_SQUASH)
            .coerceIn(-ROW_SQUASH_MAX, ROW_SQUASH_MAX)
        val h = grown.h * (1f - squash)
        val box = CoverMorphMotion.Box(grown.x, grown.y + (grown.h - h) / 2f, maxOf(grown.w, h), h)
        if (pillLandingBox != null) {
            // The pill is under a flight landing on it: the flight has its frame.
        } else if (s.kind == SWAP_PREV) {
            view.setMorphFrame(box, box.h / 2f, 1f)
            // Out of the middle: the content comes into focus as it arrives.
            val c = s.contentIn.value.coerceIn(0f, 1f)
            view.setContentAlpha(c)
            view.setContentBlur((1f - c) * swapBlurPx())
        } else {
            view.setMorphFrame(box, box.h / 2f, 1f)
            view.setContentAlpha(MiniCardMorph.smooth(0.35f, 0.9f, p))
        }
        applyGhost(s)
        applyGhostDisc(s)
        if (s.smallMode == SMALL_KEPT) return
        val small = smallIsland?.takeIf { it.visibility == View.VISIBLE && smallKey != null } ?: return
        val d = discDiameter().toFloat()
        val q = s.small.value
        if (s.smallMode == SMALL_FROM_GHOST) {
            // Under the stand-in, in its very shape, and in its place once it is there - through
            // the overshoot the stand-in has faded out of. Standing as a still circle under it,
            // the small island hid the overshoot and the landing had no give (2026-09-25).
            val box = ghostSmallBox(s)
            if (box != null) {
                val xy = IntArray(2).also(host::getLocationOnScreen)
                small.setShape(box.w.roundToInt().coerceAtLeast(1), box.h.roundToInt().coerceAtLeast(1),
                    (box.cx() - xy[0] - smallRest[0]).roundToInt())
            } else small.setShape(d.roundToInt(), d.roundToInt(), 0)
            small.setIconAlpha(1f)
            small.alpha = MiniCardMorph.smooth(GHOST_HANDOFF, 1f, q)
            return
        }
        if (s.smallMode == SMALL_EMERGE) {
            // Out from under the pill's end: from 0.6 of its size, sliding out to its place.
            val side = lerp(d * HIDDEN_SCALE, d, q).coerceAtLeast(1f).roundToInt()
            small.setShape(side, side, 0)
            small.setIconAlpha(MiniCardMorph.smooth(0.15f, 0.7f, q))
            small.alpha = MiniCardMorph.smooth(0f, 0.12f, q)
            smallDx = lerp(emergeDx(), 0f, q)
            followShortcuts()
            return
        }
        if (s.smallMode == SMALL_FROM_PILL) {
            val p = q
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
            val p = q
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
            // A card morph that has taken the pill since keeps its frame: handed back to rest
            // under it, the pill stood bare in its place with the morph still running - its
            // content cleared by the morph, its glass whole (2026-09-25, `op mini` scene trace).
            if (morph == null || flight != null) it.endMorph()
            it.setContentAlpha(1f)
            it.setContentBlur(0f)
        }
        endGhosts()
        if (smallDx != 0f) {
            smallDx = 0f
            followShortcuts()
        }
        // A small island the switch left alone may be mid-grow on its own spring: not cut short.
        if (s.smallMode != SMALL_KEPT) restoreSmallIslandShape()
        schedulePosition()
        pendingExpand?.let { key ->
            pendingExpand = null
            if (selectedIsland == key) expandNote(key)
        }
    }

    // ---- the islands going into hiding

    /** A stand-in for the big island a switch sends into hiding: a pill of its own, under the row. */
    private var ghostPill: MiniPlayerView? = null

    /** A stand-in for the small island a switch sends into hiding. */
    private var ghostDisc: ShortcutDisc? = null

    /** The small island's offset from its place while it slides out from under the pill. */
    private var smallDx = 0f

    /** Where an emerging small island starts: its centre under the pill's end. */
    private fun emergeDx(): Float {
        val d = discDiameter().toFloat()
        return -(d * 0.8f + dp(MiniPlayerGeometry.DISC_GAP_DP))
    }

    /** Lowest in the row: under the small island, which is under the pill. */
    private fun ghostIndex(): Int {
        val small = smallIsland?.let { host.indexOfChild(it) }?.takeIf { it >= 0 }
        val pill = player?.let { host.indexOfChild(it) }?.takeIf { it >= 0 }
        return small ?: pill ?: lockScreenLayerIndex()
    }

    private fun startGhost(key: String, from: CoverMorphMotion.Box): Boolean {
        val view = ghostPill ?: MiniPlayerView(context).also {
            ghostPill = it
            host.addView(it, ghostIndex(), ViewGroup.LayoutParams(1, 1))
        }
        val w = from.w.roundToInt().coerceAtLeast(1)
        val h = from.h.roundToInt().coerceAtLeast(1)
        if (view.layoutParams.width != w || view.layoutParams.height != h) {
            view.layoutParams = view.layoutParams.apply { width = w; height = h }
        }
        val music = controller?.takeIf(::isUsable)
        val note = if (key == MUSIC_ISLAND) null
            else LockIslands.notes.firstOrNull { it.key == key } ?: LockIslands.noteFor(key)
        when {
            key == MUSIC_ISLAND && music != null -> bindMusic(view, music, config)
            note != null -> bindNote(view, note, config)
            else -> return false
        }
        view.setInteractionsEnabled(false)
        view.visibility = View.VISIBLE
        view.alpha = 1f
        // Laid out now, as the flight is: new, it showed an empty frame until the next pass.
        if (!view.isLaidOut || view.width != w || view.height != h) {
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
        }
        view.beginMorph(layoutOnly = true)
        // Under the small island and the pill: the super island's hidden layer is the lowest.
        view.translationZ = 0f
        view.setMorphFrame(from, from.h / 2f, 1f)
        return true
    }

    /**
     * The island leaving the pill for the small place, this frame: CHANGE_EASE carries it past
     * the circle and back, and it squeezes with its speed as the pill widening does (applySwap)
     * - a touch taller while it narrows fast.
     */
    private fun ghostSmallBox(s: Swap): CoverMorphMotion.Box? {
        val from = s.ghostFrom ?: return null
        val to = smallBoxOnScreen()
        val g = s.small.value
        val shrunk = lerpBox(from, to, g)
        val narrowing = s.small.velocity * (to.w - from.w)
        val squash = (narrowing / from.w.coerceAtLeast(1f) * ROW_SQUASH)
            .coerceIn(-ROW_SQUASH_MAX, ROW_SQUASH_MAX)
        val h = shrunk.h * (1f - squash)
        return CoverMorphMotion.Box(shrunk.x, shrunk.y + (shrunk.h - h) / 2f, shrunk.w, h)
    }

    private fun applyGhost(s: Swap) {
        if (s.ghostSmall) {
            // BigIslandToSmallIsland, on CHANGE_EASE: the island's own pill shrinks into the
            // small island's circle, its lines going first and its picture staying.
            val view = ghostPill ?: return
            val g = s.small.value
            val box = ghostSmallBox(s) ?: return
            view.setMorphFrame(box, box.h / 2f, 1f)
            view.setTextAlpha(1f - MiniCardMorph.smooth(0f, 0.5f, g))
            view.alpha = 1f - MiniCardMorph.smooth(GHOST_HANDOFF, 1f, g)
            return
        }
        if (!s.ghost) return
        val view = ghostPill ?: return
        val from = s.ghostFrom ?: return
        val g = s.ghostBox.value
        val box = lerpBox(from, cutoutBox(s.pillTo), g)
        view.setMorphFrame(box, box.h / 2f, 1f - MiniCardMorph.smooth(0.35f, 0.9f, g))
        val a = s.ghostContent.value.coerceIn(0f, 1f)
        view.setContentAlpha(1f - a)
        view.setContentBlur(a * swapBlurPx())
    }

    private fun startGhostDisc(key: String): Boolean {
        val d = discDiameter()
        val frame = discFrame(d)
        val disc = ghostDisc ?: ShortcutDisc(context).also {
            ghostDisc = it
            host.addView(it, ghostIndex(), ViewGroup.LayoutParams(frame, frame))
        }
        if (disc.layoutParams.width != frame || disc.layoutParams.height != frame) {
            disc.layoutParams = disc.layoutParams.apply { width = frame; height = frame }
        }
        disc.dress(MiniPlayerRuntime.materialGeneration) { MiniPlayerRuntime.material(it, loader) }
        val picture: Any? = if (key == MUSIC_ISLAND) (thumbShown ?: cachedCover)
            else LockIslands.notes.firstOrNull { it.key == key }?.icon ?: LockIslands.noteFor(key)?.icon
        disc.setIcon(when (picture) {
            is Bitmap -> android.graphics.drawable.BitmapDrawable(context.resources, picture)
            is android.graphics.drawable.Drawable -> picture
            else -> null
        })
        disc.setShape(d, d)
        disc.setIconAlpha(1f)
        disc.alpha = 1f
        disc.translationX = smallRest[0] - frame / 2f - disc.left
        disc.translationY = smallRest[1] - frame / 2f - disc.top
        disc.visibility = View.VISIBLE
        return true
    }

    private fun applyGhostDisc(s: Swap) {
        if (!s.disc) return
        val disc = ghostDisc ?: return
        val d = discDiameter().toFloat()
        val h = s.ghostDisc.value.coerceIn(0f, 1f)
        val side = lerp(d, d * HIDDEN_SCALE, h).coerceAtLeast(1f).roundToInt()
        disc.setShape(side, side)
        disc.alpha = 1f - h
    }

    private fun endGhosts() {
        ghostPill?.let {
            it.endMorph()
            it.setContentBlur(0f)
            it.setContentAlpha(1f)
            it.alpha = 1f
            it.visibility = View.GONE
        }
        ghostDisc?.visibility = View.GONE
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
        if (smallWide && (small.layoutParams.width != frame || small.layoutParams.height != frame)) {
            small.layoutParams = small.layoutParams.apply { width = frame; height = frame }
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
    private val rowLeft = Jelly(SWAP_RESPONSE, ROW_DAMPING)
    private val rowWidth = Jelly(SWAP_RESPONSE, ROW_DAMPING)
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
            // Under the media card's morph the morph draws the pill, reading the spring; under a
            // switch the switch does.
            if (!soloRow && swap == null) applyRow()
            if (rowLeft.atRest() && rowWidth.atRest()) endRow()
            else Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** position(): the pill's rest place in the host is now [left] and [width]. */
    private fun rowTarget(left: Float, width: Float) {
        val view = player
        // A flight's morph moves the flight, not the pill: the pill still gives room on its
        // spring. Counted busy, it jumped to its new width the moment a flight set out.
        // The media card's morph is the exception: its pill end is the row's spring, so the
        // spring carries on under it (soloRow).
        val pillMorphing = morph != null && flight == null && !soloRow
        val busy = swap != null || pillMorphing || view == null || view.visibility != View.VISIBLE
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
            if (!soloRow) applyRow()
            Choreographer.getInstance().postFrameCallback(rowFrame)
        }
    }

    private fun applyRow() {
        val view = player ?: return
        val rest = view.restBoxOnScreen() ?: return
        if (pillLandingBox != null) return
        // Also after a media card's morph has handed the pill back mid-spring.
        if (!view.inMorph()) view.beginMorph(layoutOnly = true)
        val xy = IntArray(2).also(host::getLocationOnScreen)
        // Soft, as the pill is everywhere else: widening fast it thins, and coming back off its
        // overshoot it thickens - a squeeze that follows the width's own speed. On its spring
        // alone the pill slid to its new width like a rigid bar (2026-09-25).
        val squash = (rowWidth.velocity / rest.w.coerceAtLeast(1f) * ROW_SQUASH)
            .coerceIn(-ROW_SQUASH_MAX, ROW_SQUASH_MAX)
        val h = rest.h * (1f - squash)
        val box = CoverMorphMotion.Box(xy[0] + rowLeft.value, rest.y + (rest.h - h) / 2f,
            rowWidth.value.coerceAtLeast(h), h)
        view.setMorphFrame(box, box.h / 2f, 1f)
    }

    /** The row's spring hands the frame back: at rest, or to another motion taking it. */
    private fun endRow() {
        if (!rowAnimating) return
        rowAnimating = false
        Choreographer.getInstance().removeFrameCallback(rowFrame)
        rowLeft.value = rowLeft.target; rowLeft.velocity = 0f
        rowWidth.value = rowWidth.target; rowWidth.velocity = 0f
        // The media card's morph owns the pill's frame: not the row's to hand back.
        if (!soloRow) player?.endMorph()
    }

    /**
     * The pill's end of the media card's morph: the row's spring as it is now, so a pill that
     * is still widening (the small island going) or narrowing is followed, not jumped to.
     */
    private fun rowBoxOnScreen(): CoverMorphMotion.Box? {
        val rest = player?.restBoxOnScreen() ?: return null
        if (!rowKnown) return rest
        val xy = IntArray(2).also(host::getLocationOnScreen)
        return CoverMorphMotion.Box(xy[0] + rowLeft.value, rest.y,
            rowWidth.value.coerceAtLeast(rest.h), rest.h)
    }

    /**
     * A media card morph is running: the row is laid out as the pill alone. Into the card, the
     * small island slides back under the pill's end while the pill widens over its place; out
     * of it, the card lands as the whole pill, and only then does the small island come out
     * from under the end and the pill give it room - the super island's order, one motion
     * after the other. It used to land straight on the narrowed pill with the small island
     * already there, and going up the small island stayed till the card had landed, then went.
     */
    private var soloRow = false

    /** The media card morph has just landed on the pill: the small island comes out once. */
    private var soloEnded = false

    /**
     * Before a media card morph: [fromPill] the pill is what is on screen and widens on its
     * spring; otherwise nothing is drawn to move from and the row is put at its end at once.
     */
    private fun beginSoloRow(fromPill: Boolean) {
        endRow()
        soloRow = true
        if (!fromPill) rowKnown = false
    }

    /** The media card morph is over, or never started. */
    private fun endSoloRow() {
        if (!soloRow) return
        soloRow = false
        soloEnded = true
    }

    // ---- the small island coming and going

    /** The small island's presence, 0 gone to 1 there, sprung the same way. */
    private val smallGrow = Jelly(SWAP_RESPONSE, SWAP_DAMPING)
    private var smallGrowing = false
    private var smallGrowLast = 0L

    /**
     * This coming or going is out from under the pill's end, or back in under it (soloRow),
     * on APPEAR_EASE as the super island's HiddenToSmallIsland - not in its own place.
     */
    private var smallEmerge = false

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
                endSmallEmerge()
            } else Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * The small island shown or not: coming up it grows out of nothing in its place, going it
     * shrinks away there - unless the row itself is going, when it just goes with it.
     */
    private fun setSmallShown(shown: Boolean, animate: Boolean, emerge: Boolean = false) {
        val small = smallIsland ?: return
        val visible = small.visibility == View.VISIBLE
        if (visible != shown && traceFrames > 0 || flight != null && visible != shown) {
            trace("small ${if (shown) "show" else "hide"} animate=$animate key=${smallKey?.takeLast(6)} " +
                "noteMorph=${noteMorphKey?.takeLast(6)} out=$flightOut landing=$flightLanding solo=$soloRow")
        }
        if (shown && visible && (!smallGrowing || smallGrow.target == 1f)) return
        if (!shown && (!visible || (smallGrowing && smallGrow.target == 0f))) return
        if (!animate || (swap?.smallMode ?: SMALL_KEPT) != SMALL_KEPT) {
            smallGrowing = false
            Choreographer.getInstance().removeFrameCallback(smallGrowFrame)
            smallGrow.value = if (shown) 1f else 0f
            smallGrow.target = smallGrow.value
            small.visibility = if (shown) View.VISIBLE else View.GONE
            if (shown && landingBox == null) restoreSmallIslandShape()
            endSmallEmerge()
            return
        }
        if (shown && !visible) {
            smallGrow.value = 0f
            smallGrow.velocity = 0f
            small.visibility = View.VISIBLE
        }
        // Turned round midway, it keeps to the path it is on.
        if (!smallGrowing && emerge != smallEmerge) {
            if (emerge) {
                smallEmerge = true
                smallGrow.response = APPEAR_RESPONSE
                smallGrow.damping = APPEAR_DAMPING
            } else endSmallEmerge()
        }
        smallGrow.target = if (shown) 1f else 0f
        if (!smallGrowing) {
            smallGrowing = true
            smallGrowLast = 0L
            applySmallGrow()
            Choreographer.getInstance().postFrameCallback(smallGrowFrame)
        }
    }

    /** Another island in the small island's place: it comes up there from nothing. */
    private fun regrowSmall() {
        endSmallEmerge()
        smallGrow.value = 0f
        smallGrow.velocity = 0f
        smallGrow.target = 1f
        if (!smallGrowing) {
            smallGrowing = true
            smallGrowLast = 0L
            Choreographer.getInstance().postFrameCallback(smallGrowFrame)
        }
        applySmallGrow()
    }

    private fun applySmallGrow() {
        val small = smallIsland ?: return
        val d = discDiameter().toFloat()
        val v = smallGrow.value
        if (smallEmerge) {
            // Under the pill's end at 0.6 of its size, sliding out to its place (SMALL_EMERGE).
            val q = v.coerceAtLeast(0f)
            val side = lerp(d * HIDDEN_SCALE, d, q).coerceAtLeast(1f).roundToInt()
            small.setShape(side, side, 0)
            small.setIconAlpha(MiniCardMorph.smooth(0.15f, 0.7f, q))
            small.alpha = MiniCardMorph.smooth(0f, 0.12f, q)
            smallDx = lerp(emergeDx(), 0f, v)
            followShortcuts()
            return
        }
        val side = lerp(d * 0.4f, d, v).coerceAtLeast(1f).roundToInt()
        small.setShape(side, side, 0)
        small.setIconAlpha(MiniCardMorph.smooth(0.3f, 1f, v))
        small.alpha = MiniCardMorph.smooth(0f, 0.5f, v)
    }

    /** Back to coming and going in its own place, on CHANGE_EASE. */
    private fun endSmallEmerge() {
        if (!smallEmerge) return
        smallEmerge = false
        smallGrow.response = SWAP_RESPONSE
        smallGrow.damping = SWAP_DAMPING
        if (smallDx != 0f && swap == null) {
            smallDx = 0f
            followShortcuts()
        }
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
        if (!islandDragging) springSmallNudgeBack(0f, 0f)
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
        // Out of the pill as out of the small island: a flight of its own, so the island beside
        // it can take the pill the moment it leaves. Going as the pill itself, the pill was the
        // morph's until it had landed on the row, and only then did the next island take the
        // pill - in one jump, from the small island's circle to the whole pill (filmed 2026-09-25).
        if (fromSmall && key != smallKey || prepareFlight(key) == null) {
            LockIslands.release(key)
            return
        }
        endSwap()
        noteMorphKey = key
        flightFromSmall = fromSmall
        flightHome = if (fromSmall) HOME_SMALL else HOME_PILL
        flightOut = false
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
            // The stack fades a returned row in on its own: seen before the morph took it, it
            // stood there whole, went, and came back as the morph's end (filmed 2026-09-25).
            if (row != null) hideRow(row)
            val flightReady = flight?.let { it.isLaidOut && it.width > 1 } ?: true
            if (row != null && row.isAttachedToWindow && row.isLaidOut && row.height > 0 &&
                row.width > 0 && flightReady && rowOnScreen(row)) {
                rowWaitKey = null
                startNoteMorph(key, row, toRow = true)
                return
            }
            if (android.os.SystemClock.uptimeMillis() - rowWaitSince > ROW_WAIT_MS) {
                // No row to land on in time - none laid out, or one the stack keeps out of
                // sight: folded into its count, or faded out. Opened without a morph, the
                // island went into a stack that did not show it, and was simply gone. It
                // comes back into the row instead, where it was.
                val why = when {
                    row == null -> "no row"
                    !rowOnScreen(row) -> "row hidden (folded=${stackFolded()} " +
                        "alpha=${"%.2f".format((row.parent as? View)?.let(::drawnAlpha) ?: 0f)})"
                    else -> "row not laid out"
                }
                Xp.log("MCIsland: $key not opened: $why")
                MiniPlayerRuntime.noteTouch("open $why")
                rowWaitKey = null
                noteDrag = null
                abandonNoteMorph(key)
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
    fun collapseRow(key: String, startY: Float): Boolean {
        if (noteMorphKey != null || morph != null) {
            MiniPlayerRuntime.noteTouch("collapse refused: noteMorph=${noteMorphKey != null} morph=${morph != null}")
            return false
        }
        val row = rowFor(key) ?: run {
            MiniPlayerRuntime.noteTouch("collapse refused: no row")
            return false
        }
        // Nothing left in the row - the only island was let out - and the notification coming
        // back is all of it: the pill. It used to just go back in there with no motion at all.
        val rowEmpty = player?.visibility != View.VISIBLE
        val flight = prepareFlight(key) ?: run {
            if (rowEmpty) {
                MiniPlayerRuntime.noteTouch("collapse: no pill, recaptured")
                LockIslands.recapture(key)
                return true
            }
            MiniPlayerRuntime.noteTouch("collapse refused: no flight")
            return false
        }
        endSwap()
        noteMorphKey = key
        flightFromSmall = false
        flightOut = false
        // Back where it came out of: the pill it was in, or the small island.
        flightHome = if (rowEmpty || key in releasedFromPill) HOME_PILL else HOME_SMALL
        MiniPlayerRuntime.noteTouch("collapse home=${if (flightHome == HOME_PILL) "pill" else "small"} " +
            "empty=$rowEmpty")
        if (rowEmpty) {
            // The row comes back with this notification in the pill - out of sight under the
            // flight until it lands there.
            selectedIsland = key
            refresh()
            position()
            if (player?.visibility != View.VISIBLE || player?.restBoxOnScreen() == null) {
                MiniPlayerRuntime.noteTouch("collapse: no pill, recaptured")
                noteMorphKey = null
                noteDrag = null
                dropFlight()
                LockIslands.recapture(key)
                refresh()
                return true
            }
        } else if (flightHome == HOME_PILL) {
            // The pill is its again: whatever took it goes back to being the small island,
            // forming out of the pill's place while the row comes down onto it.
            val oldBig = selectedIsland
            val oldSmall = smallKey
            selectedIsland = key
            refresh()
            startSwap(oldBig, oldSmall, intoSmall = true)
        }
        noteDrag = NoteDrag(key, startY, opening = false,
            MiniPlayerRuntime.dragThresholdPx(density()), MiniPlayerRuntime.dragNudgePx(density()))
        // The row laid out with this notification as its small island: the pill gives it room
        // on its spring while the row comes down, as the super island's big island does.
        refresh()
        position()
        landTrace.clear()
        trace("collapse target=${smallBoxOnScreen().cx().toInt()} " + smallState())
        if (flight.isLaidOut && flight.width > 1) return startNoteMorph(key, row, toRow = false)
        // Laid out on the next frame at the earliest; the morph waits for it.
        flight.post { if (noteMorphKey == key && morph == null) startNoteMorph(key, row, toRow = false) }
        return true
    }

    /** The flight: a pill of its own, bound to [key]'s notification, laid out at the pill's size. */
    private var flight: MiniPlayerView? = null
    private var flightFromSmall = false

    /** Where the flight's island lives when it is in the row: the pill, or the small island. */
    private var flightHome = HOME_SMALL

    /** Notifications let out of the pill itself: pulled down, their row goes back there. */
    private val releasedFromPill = HashSet<String>()

    /** The pill under a flight landing on it, taking the flight's shape; null otherwise. */
    private var pillLandingBox: CoverMorphMotion.Box? = null

    /** The pill's place is a flight's - out of it and not let go, or coming home to it. */
    private fun pillHeld(): Boolean = flight != null && flightHome == HOME_PILL &&
        selectedIsland == noteMorphKey && pillLandingBox == null &&
        // Pulled up, the pill itself follows the finger until the row is there to morph into.
        (morph != null || rowWaitKey == null)

    /**
     * The flight has left its home for good ([out]) or is headed back to it. Home is the small
     * island's place: the next island comes up there, or the flight's shrinks away for it. Home
     * is the pill: the island beside it takes the pill, growing out of its circle on the super
     * island's CHANGE_EASE (SmallIslandToBigIsland); coming back, that island shrinks into the
     * small place again (BigIslandToSmallIsland) while the flight lands on the pill.
     */
    private fun setFlightOut(out: Boolean) {
        val key = noteMorphKey
        if (flight == null || key == null || out == flightOut) return
        flightOut = out
        if (flightHome != HOME_PILL) {
            refresh()
            return
        }
        if (out) {
            releasedFromPill += key
            val oldSmall = smallKey
            if (selectedIsland == key) {
                selectedIsland = oldSmall ?: islandKeys.firstOrNull { it != key }
                refresh()
                startSwap(key, oldSmall)
            } else refresh()
        } else if (selectedIsland != key) {
            val oldBig = selectedIsland
            val oldSmall = smallKey
            selectedIsland = key
            refresh()
            startSwap(oldBig, oldSmall, intoSmall = true)
        }
    }

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
        // Laid out here and now, not on the next pass: a flight is a new view each time, and
        // its morph waited for that pass - the row stood still under a finger already pulling
        // it down, and the flight then jumped to where the finger had got to (filmed 2026-09-25).
        if (!view.isLaidOut || view.width != w || view.height != h) {
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
        }
        // Out of sight until its morph puts it where it starts.
        view.visibility = View.VISIBLE
        view.alpha = 0f
        return view
    }

    private fun dropFlight() {
        flight?.let { runCatching { host.removeView(it) } }
        flight = null
        flightFromSmall = false
        flightHome = HOME_SMALL
        flightOut = false
        flightLanding = false
        endPillLanding()
        if (landingBox != null) {
            landingBox = null
            smallIsland?.iconAtStart = false
            restoreSmallIslandShape()
        }
    }

    private fun startNoteMorph(key: String, row: View, toRow: Boolean): Boolean {
        val useFlight = flight != null
        val view = (if (useFlight) flight else player) ?: return false
        val landing = MiniCardMorph.Landing(
            findNamed(row, ICON_NAMES) { it is ImageView },
            findNamed(row, TITLE_NAMES) { it is TextView },
            findNamed(row, TEXT_NAMES) { it is TextView },
            Main.notificationRowRadius(row),
            dp(8f).toFloat(),
        )
        // The flight's end, both ways: its home - the small island's circle, or the pill.
        val home = flightHome
        val restBox: (() -> CoverMorphMotion.Box?)? = when {
            !useFlight -> null
            home == HOME_PILL -> { { player?.restBoxOnScreen() } }
            else -> { { smallBoxOnScreen() } }
        }
        view.alpha = 1f
        if (!useFlight) endRow()
        // Kept out of sight while it waited (rowWait); the morph saves the row's own alpha
        // and fades it in from there, in this same frame.
        showRow(row)
        val next = MiniCardMorph(view, row, toRow, noteMorphListener(key), landing, restBox,
            circle = useFlight && home == HOME_SMALL)
        morph = next
        noteSpan = noteDragSpan(row, useFlight && home == HOME_SMALL)
        val drag = noteDrag?.takeIf { it.key == key }
        if (useFlight && toRow && home == HOME_SMALL) {
            // The flight is the small island from here: one hands over to the other in place.
            smallIsland?.visibility = View.GONE
            smallGrowing = false
            Choreographer.getInstance().removeFrameCallback(smallGrowFrame)
        }
        if (drag != null) drag.span = noteSpan
        // The island's own nudge is the morph's from here, as the pill's is handed to the card's.
        if (drag != null && drag.opening) {
            if (flightFromSmall) clearSmallNudge() else player?.clearNudge()
        }
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
                val (p, nudge) = notePose(drag)
                next.drag(p, nudge, drag.nudgeX)
                next.release(drag.toRow, noteVelocity(drag))
            } else applyNoteDrag()
        }
        // The small place, the pill's width and the small island's presence follow the flight:
        // freed (the next island comes up, or the pill takes the room back) or held (what is
        // there shrinks away for the flight coming in). A tap, or a pull already let go past
        // the threshold, is gone for good and frees its home at once.
        if (useFlight) {
            refresh()
            if (toRow && (drag == null || drag.ended && drag.toRow)) setFlightOut(true)
        }
        // The pill it left goes out of sight this frame, not the next.
        if (home == HOME_PILL) followShortcuts()
        return true
    }

    private fun noteMorphListener(key: String) = object : MiniCardMorph.Listener {
        override fun canSettle(morph: MiniCardMorph, toNative: Boolean) = true
        override fun artBridged() = false

        override fun onFrame(morph: MiniCardMorph, progress: Float) {
            val f = flight ?: return
            if (this@MiniPlayerController.morph !== morph || flightOut) return
            if (progress > 0f) restFrames = 0 else restFrames++
            if (!morph.toNative && restFrames <= 10) morph.containerBox()?.let { b ->
                trace("f c=${"%.3f".format(progress)} box=${b.cx().toInt()},${b.w.toInt()}x${b.h.toInt()} " +
                    "fa=${"%.2f".format(f.alpha)} land=$flightLanding " + smallState())
            }
            val landing = !morph.toNative && progress < FLIGHT_HANDOFF
            if (landing != flightLanding) {
                flightLanding = landing
                // Shown at once, whole, with its own picture: it is what the flight becomes.
                if (landing) snapSmallOnce = true
                scheduleRefresh()
            }
            val box = if (landing) morph.containerBox() else null
            if (flightHome == HOME_PILL) {
                if (box != null) landOnPill(box) else endPillLanding()
            } else if (box != null) landOn(box)
            else if (landingBox != null) {
                // Turned back out: the small island goes back to waiting in its place.
                landingBox = null
                smallIsland?.iconAtStart = false
                restoreSmallIslandShape()
            }
            val a = if (landing) MiniCardMorph.smooth(0f, FLIGHT_HANDOFF, progress) else 1f
            if (kotlin.math.abs(f.alpha - a) > 0.002f) f.alpha = a
        }
        override fun onSettled(morph: MiniCardMorph, toNative: Boolean, completed: Boolean) {
            trace("settled toRow=$toNative completed=$completed " + smallState())
            traceFrames = 30
            Choreographer.getInstance().removeFrameCallback(traceFrame)
            Choreographer.getInstance().postFrameCallback(traceFrame)
            if (this@MiniPlayerController.morph === morph) this@MiniPlayerController.morph = null
            noteDrag = null
            val flew = flight != null
            val home = flightHome
            val oldSmall = smallKey
            noteMorphKey = null
            flightOut = false
            dropFlight()
            if (toNative) {
                // Out in the stack. From the small island the next one has already come up in
                // its place (updateSmallIsland); from the pill, the pill takes the next island,
                // growing back out of its end, and the small island stays as it is unless the
                // pill took it.
                // A flight - out of the small island, or a collapse let go back to its row -
                // leaves the pill as it is.
                if (!flew) selectedIsland = null
                refresh()
                if (!flew) startSwap(null, oldSmall)
            } else {
                // Back in the row as the small island it landed as: the stack lets go of its row.
                // The morph has just put the row's alpha back, and the stack takes most of a
                // second to animate a removed row away: it stood there again, whole, after the
                // flight had landed (filmed 2026-09-25). Hidden until it is gone.
                rowFor(key)?.let { hideRowUntilGone(it, key) }
                LockIslands.recapture(key)
                // The stack gives it back a run of the pipeline later: until then the row keeps
                // its place, or the small island showed the next one for a frame or two and then
                // grew this one anew - the icon blinked on landing (2026-09-25).
                returning = key
                returningSince = android.os.SystemClock.uptimeMillis()
                // Asked again once the wait is up, in case the stack never gives it back.
                handler.postDelayed({ if (returning == key) refresh() }, RETURN_WAIT_MS + 50L)
                releasedFromPill -= key
                if (flew && home == HOME_SMALL) {
                    preferredSmall = key
                    snapSmallOnce = true
                } else selectedIsland = key
                refresh()
            }
        }
    }

    /**
     * The pill under a flight landing on it: shown, with the notification it is taking back,
     * in the flight's shape frame by frame - the overshoot included - while the flight fades
     * off it, as the small island takes a flight landing on its circle.
     */
    private fun landOnPill(box: CoverMorphMotion.Box) {
        val view = player ?: return
        val first = pillLandingBox == null
        pillLandingBox = box
        if (!view.inMorph()) view.beginMorph(layoutOnly = true)
        view.setMorphFrame(box, box.h / 2f, 1f)
        // Whole on every frame it is landed on, not just the first: whatever was bringing its
        // content in while it was held (the switch that gave it back) is not done with it yet.
        view.setContentAlpha(1f)
        view.setContentBlur(0f)
        if (first) followShortcuts()
    }

    /** The flight has landed, or turned back: the pill's frame is its own again. */
    private fun endPillLanding() {
        if (pillLandingBox == null) return
        pillLandingBox = null
        // Anything else sizing the pill goes on from here; nothing else, its rest frame.
        if (swap == null && !rowAnimating) player?.endMorph()
        followShortcuts()
    }

    /** The small island takes the flight's shape this frame: room for it, then placed on it. */
    private fun landOn(box: CoverMorphMotion.Box) {
        val small = smallIsland ?: return
        val pad = dp(16f)
        val w = maxOf(discFrame(discDiameter()), (box.w + pad).roundToInt())
        val h = maxOf(discFrame(discDiameter()), (box.h + pad).roundToInt())
        // Only ever widened while landing: the shape shrinks inside the room it was given.
        if (landingBox == null || small.layoutParams.width < w || small.layoutParams.height < h) {
            small.layoutParams = small.layoutParams.apply {
                width = maxOf(width, w)
                height = maxOf(height, h)
            }
            smallWide = true
        }
        landingBox = box
        small.iconAtStart = true
        placeSmallIsland(null, small.transitionAlpha)
    }

    // ---- a finger pulling a notification island out

    /**
     * A notification island - the pill's or the small one - pulled up by the finger into its
     * row, as the music pill is pulled up into the media card. The row only exists once the
     * stack has been given the notification back, a frame or two later, so the finger is
     * followed from the start and the morph picks up wherever it has got to when the row is
     * there. Let go before then, the pull's own distance and speed decide.
     */
    private class NoteDrag(
        val key: String,
        val startY: Float,
        /** Up out of the island into its row; false for a row pulled down into its island. */
        val opening: Boolean,
        val threshold: Float,
        val nudgeLimit: Float,
    ) {
        var y = startY
        var nudgeX = 0f
        var ended = false
        /** Where the lift sends it: its row (true) or its island. */
        var toRow = false
        /** Toward the far end, in pixels per second, as the lift measured it. */
        var speed = 0f
        /** The pull's length, from the island to the row: unknown (0) until the row is there. */
        var span = 0f

        fun pulled() = if (opening) startY - y else y - startY
    }

    private var noteDrag: NoteDrag? = null

    /** The running notification morph's pull length: what a finger catching it moves over. */
    private var noteSpan = 0f

    private fun spanOf(drag: NoteDrag) = drag.span.takeIf { it > 0f } ?: NOTE_SPAN_MIN_DP * density()

    /** The pull as the music's is posed (MiniPlayerRuntime.pose): progress, then nudge. */
    private fun notePose(drag: NoteDrag): Pair<Float, Float> = MiniPlayerRuntime.pose(drag.pulled(),
        !drag.opening, drag.threshold, spanOf(drag), drag.nudgeLimit)

    /** The lift's speed as the morph's progress speed. */
    private fun noteVelocity(drag: NoteDrag): Float =
        (if (drag.opening) drag.speed else -drag.speed) / spanOf(drag)

    /** A notification's morph, let go by a finger that caught it: to its row or its island. */
    private fun releaseNote(toRow: Boolean, velocity: Float) {
        morph?.release(toRow, velocity)
        // A flight headed out frees its home; headed home, it holds it again.
        setFlightOut(toRow)
    }

    fun beginNoteDrag(key: String, fromSmall: Boolean, startY: Float): Boolean {
        if (noteMorphKey != null || morph != null) return false
        val view = player ?: return false
        if (view.visibility != View.VISIBLE) return false
        if (fromSmall && key != smallKey) return false
        if (!fromSmall && selectedIsland != key) return false
        if (prepareFlight(key) == null) return false
        endSwap()
        resetIslandDrag()
        // The island keeps following the finger until its row is there to morph into; its
        // nudge is handed to the morph then (startNoteMorph), as the pill's is to the card's.
        noteMorphKey = key
        flightFromSmall = fromSmall
        flightHome = if (fromSmall) HOME_SMALL else HOME_PILL
        flightOut = false
        noteDrag = NoteDrag(key, startY, opening = true,
            MiniPlayerRuntime.dragThresholdPx(density()), MiniPlayerRuntime.dragNudgePx(density()))
        LockIslands.release(key)
        rowWaitKey = key
        rowWaitSince = android.os.SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback(rowWait)
        return true
    }

    fun noteDragMove(y: Float, nudgeX: Float = 0f) {
        val drag = noteDrag ?: return
        drag.y = y
        drag.nudgeX = nudgeX
        applyNoteDrag()
    }

    /**
     * The finger is off: the music's lift decides (MiniPlayerRuntime.lift). Let go before the
     * row was there, the decision takes the shortest span a pull can have and the real one
     * sets the speed once the row is laid out (startNoteMorph) - with a 1px span, the flight
     * was once flung hundreds of times too fast (filmed 2026-09-25).
     */
    fun noteDragEnd(velocityY: Float, cancelled: Boolean) {
        val drag = noteDrag ?: return
        drag.ended = true
        val speed = if (drag.opening) -velocityY else velocityY
        val (toRow, _) = MiniPlayerRuntime.lift(drag.pulled(), speed, !drag.opening, drag.threshold,
            spanOf(drag), cancelled)
        drag.toRow = toRow
        drag.speed = if (drag.pulled() > drag.threshold) speed else 0f
        val running = morph
        if (running != null) {
            noteDrag = null
            releaseNote(toRow, noteVelocity(drag))
            return
        }
        if (!toRow && drag.opening) {
            // Let go short, before the row was even there: nothing opened.
            noteDrag = null
            abandonNoteMorph(drag.key)
        }
        // Headed for the row: its arrival opens it on its own.
    }

    private fun applyNoteDrag() {
        val drag = noteDrag ?: return
        if (drag.ended) return
        val running = morph
        if (running != null) {
            val (p, nudge) = notePose(drag)
            running.drag(p, nudge, drag.nudgeX)
            return
        }
        if (!drag.opening) return
        // No row to morph into yet: the island itself goes on following the finger, as far as
        // the morph would have carried it - rubber-banded to the threshold, then one to one.
        val pulled = drag.pulled()
        val up = if (pulled <= drag.threshold) MiniCardMorph.rubber(pulled.coerceAtLeast(0f), drag.nudgeLimit)
            else MiniCardMorph.rubber(drag.threshold, drag.nudgeLimit) + (pulled - drag.threshold)
        if (flightFromSmall) setSmallNudge(drag.nudgeX, -up) else player?.setNudge(drag.nudgeX, -up)
    }

    /** The row's arrival: how far the pull has to go, from the island's place to the row's. */
    private fun noteDragSpan(row: View, fromSmall: Boolean): Float {
        val start = (if (fromSmall) smallBoxOnScreen() else player?.restBoxOnScreen())?.y ?: return 1f
        val xy = IntArray(2).also(row::getLocationOnScreen)
        return kotlin.math.abs(start - xy[1]).coerceAtLeast(NOTE_SPAN_MIN_DP * density())
    }

    /** A notification that set out and did not open: back in the row as it was. */
    private fun abandonNoteMorph(key: String) {
        // Whatever the finger had pulled goes home.
        player?.springNudgeBack(0f, 0f)
        springSmallNudgeBack(0f, 0f)
        rowFor(key)?.let { hideRowUntilGone(it, key) }
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

    /** Rows this controller has made invisible (transitionAlpha 0), and what they had. */
    private val hiddenRows = java.util.WeakHashMap<View, Float>()

    private fun hideRow(row: View) {
        if (row in hiddenRows) return
        hiddenRows[row] = row.transitionAlpha
        row.transitionAlpha = 0f
    }

    private fun showRow(row: View) {
        val was = hiddenRows.remove(row) ?: return
        row.transitionAlpha = was
    }

    /**
     * A row going back into the row of islands: invisible for as long as the stack takes to
     * animate it away, its alpha given back after that. Not on detach: the stack re-adds a
     * removed row as a transient view for exactly that animation, so the detach comes first
     * and would show it again for it. Given back whatever happens, a row can never be left
     * invisible in a stack that shows it later - the cover's, the unlocked shade's.
     */
    private fun hideRowUntilGone(row: View, key: String) {
        hideRow(row)
        handler.postDelayed({
            // Released again meanwhile and waiting to be morphed: that morph gives it back.
            if (rowWaitKey != key && !(noteMorphKey == key && morph != null)) showRow(row)
        }, ROW_GONE_MS)
    }

    /** A row's alpha as drawn: its own and every ancestor's up to the window. */
    private fun drawnAlpha(row: View): Float {
        var a = 1f
        var v: View? = row
        while (v != null) {
            a *= v.alpha * v.transitionAlpha
            v = v.parent as? View
        }
        return a
    }

    /**
     * Whether a row is there to be landed on: shown, drawn at least half there, and inside the
     * screen above the row of islands. Not the stack's folded state: it read true with the row
     * plainly drawn (alpha 1, 2026-09-25), and the tap went back into its island - it is only
     * logged now.
     */
    private fun rowOnScreen(row: View): Boolean {
        // The row's own alpha is the stack's fade-in (and our hiding): the morph owns it from
        // here. Its ancestors' are whether the stack shows it at all.
        val parent = row.parent as? View ?: return false
        if (!row.isShown || drawnAlpha(parent) < 0.5f) return false
        val xy = IntArray(2).also(row::getLocationOnScreen)
        val bottom = player?.restBoxOnScreen()?.y ?: host.height.toFloat()
        return xy[1] + row.height > 0 && xy[1] < bottom
    }

    /**
     * The stack folded into its "N notifications" count (KeyguardNotificationState.NUMBER),
     * read as Main.readFoldState reads it but from the stack itself: the media card it starts
     * from is not there with a row of islands showing. Null when it cannot be read.
     */
    private fun stackFolded(): Boolean? = runCatching {
        val stack = notificationStack() ?: return null
        val controller = Xp.getObjectField(stack, "mController")
        val injector = Xp.getObjectField(controller, "mNsslControllerInjector")
        val helper = Xp.getObjectField(injector, "numStateTouchHelper")
        val model = Xp.callMethod(Xp.getObjectField(helper, "notifContainerViewModel"), "get")
        Xp.callMethod(Xp.callMethod(model, "isInNumState"), "getValue") == true
    }.getOrNull()

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
        when (side) {
            MiniPlayerRuntime.PRESS_PILL -> squeeze.pressPill(down)
            MiniPlayerRuntime.PRESS_SMALL -> squeeze.pressSmall(down)
            else -> squeeze.pressDisc(side, down)
        }
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
        // First, so that the discs below see the buttons as they will be drawn.
        holdButtonsThroughDoze()
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

    // ---- the torch and the camera through the doze

    /**
     * The doze does not put the torch and the camera away: it leaves them shown and fades
     * their chain out to a hundredth (`op mini` discs trace, 2026-09-25). The row keeps them:
     * every frame of the doze, before it is drawn, each view from a button's image up to the
     * keyguard's root is put back to full alpha over whatever the doze's animation wrote there
     * this frame - so the buttons are the lock screen's own, drawn as ever, and their discs
     * simply stay on them. The first version drew the glyphs itself onto the discs instead:
     * too big, the torch's half clear, and a flash at every hand-over.
     *
     * After the wake it lets go only once the lock screen's own alpha has been back at full
     * for a moment: it can show a button whole for a frame and then start fading it in from
     * nothing, and let go on that frame the buttons would drop with it.
     */
    private var holdButtons = false
    private var holdSince = 0L
    private var backSince = 0L

    private fun holdButtonsThroughDoze() {
        val root = followRoot?.get()
        val now = android.os.SystemClock.uptimeMillis()
        if (MiniPlayerScene.aodActive && discsWanted && root != null) {
            holdButtons = true
            holdSince = now
        }
        if (!holdButtons) return
        // The lower of the two chains as the doze left it this frame, before it is put back.
        var natural = 1f
        for (side in 0..1) {
            var v: View? = findImage(button(side)) ?: button(side)
            while (v != null && v !== root && v !== host) {
                natural = min(natural, v.alpha * v.transitionAlpha)
                if (v.alpha < 1f) v.alpha = 1f
                if (v.transitionAlpha < 1f) v.transitionAlpha = 1f
                v = v.parent as? View
            }
        }
        traceDoze(natural)
        if (MiniPlayerScene.aodActive) {
            backSince = 0L
            return
        }
        if (natural < 0.99f) backSince = 0L
        else if (backSince == 0L) backSince = now
        if (backSince != 0L && now - backSince > HOLD_SETTLE_MS || now - holdSince > HOLD_MAX_MS ||
            !discsWanted) {
            holdButtons = false
            backSince = 0L
            traceDoze(natural)
        }
    }

    // ---- the doze, frame by frame, for `op mini`

    private val dozeTrace = ArrayDeque<String>()
    private var dozeTraceFrames = 0
    private var dozeTraceState = ""

    private fun traceDoze(natural: Float) {
        val state = "aod=${MiniPlayerScene.aodActive} hold=$holdButtons"
        if (state != dozeTraceState) {
            dozeTraceState = state
            dozeTraceFrames = 60
        }
        if (dozeTraceFrames <= 0) return
        dozeTraceFrames--
        dozeTrace.addLast("${android.os.SystemClock.uptimeMillis() % 100000} $state " +
            "natural=${"%.2f".format(natural)} L=${discs[0]?.let { "${it.visibility}/${"%.2f".format(it.alpha)}" }} " +
            "R=${discs[1]?.let { "${it.visibility}/${"%.2f".format(it.alpha)}" }}")
        while (dozeTrace.size > 120) dozeTrace.removeFirst()
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
        // A flight's morph is the flight's: the row pressing on the discs is still the pill's.
        val running = morph?.takeIf { flight == null }
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
        // The small island at rest where the finger has it; the row is worked out from the two.
        val small = smallIsland?.takeIf { running == null && it.visibility == View.VISIBLE && swap == null &&
            landingBox == null }
            ?.let { CoverMorphMotion.Box(smallRest[0] - d / 2f + smallNudgeX(),
                smallRest[1] - d / 2f + smallNudgeY(), d, d) }
        squeeze.setScene(pill, running == null, disc(0), disc(1), small)
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

    // ---- the landing, frame by frame, for `op mini`

    private val landTrace = ArrayDeque<String>()

    /** Frames the running notification morph has spent at its island's end. */
    private var restFrames = 0
    private var traceFrames = 0

    private fun trace(what: String) {
        landTrace.addLast("${android.os.SystemClock.uptimeMillis() % 100000} $what")
        while (landTrace.size > 200) landTrace.removeFirst()
    }

    /** The small island and the row as they stand this frame, in host pixels. */
    private fun smallState(): String {
        val v = smallIsland ?: return "no small"
        val pill = player
        return "sk=${smallKey?.takeLast(6)} v=${v.visibility} a=${"%.2f".format(v.alpha)} " +
            "ta=${"%.2f".format(v.transitionAlpha)} tx=${v.translationX.toInt()} l=${v.left} " +
            "w=${v.width}/${v.layoutParams.width} rest=${smallRest[0].toInt()} " +
            "pill=${pill?.left?.plus(pill.translationX)?.toInt()}+${pill?.width}/${pill?.layoutParams?.width} " +
            "row=${rowLeft.value.toInt()}+${rowWidth.value.toInt()}>${rowWidth.target.toInt()} " +
            "grow=$smallGrowing:${"%.2f".format(smallGrow.value)} keys=${islandKeys.size} ret=${returning != null} " +
            "held=${pillHeld()} pta=${"%.2f".format(pill?.transitionAlpha ?: -1f)} " +
            "pca=${"%.2f".format(pill?.contentAlphaNow() ?: -1f)} fca=${"%.2f".format(flight?.contentAlphaNow() ?: -1f)} " +
            "ghost=${ghostPill?.let { "${it.visibility}/${"%.2f".format(it.alpha)}/${it.width}" }} swap=${swap?.smallMode}"
    }

    private val traceFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (traceFrames <= 0) return
            traceFrames--
            trace("after " + smallState())
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun describe(): String {
        val islands = "rowAnim=$rowAnimating swap=${swap != null} noteMorph=${noteMorphKey != null} " +
            "flight=${flight != null} drag=${noteDrag != null} ${player?.touchState()} " +
            "row=${islandKeys.size} sel=${selectedIsland?.takeLast(24)} " +
            "small=${smallKey?.takeLast(24)} smallShown=${smallIsland?.visibility == View.VISIBLE} "
        val v = player ?: return islands + "no pill"
        val xy = IntArray(2).also(v::getLocationOnScreen)
        val icons = "pillArt=[${v.artworkState()}] smallIcon=[${smallIsland?.iconState()}] "
        val h = header?.get()
        return islands + icons + "pill v=${v.visibility} a=${v.alpha} ta=${v.transitionAlpha} at=${xy[0]},${xy[1]} " +
            "${v.width}x${v.height} morph=${morph != null} header=" +
            (if (h == null) "none" else "v=${h.visibility} a=${h.alpha} ta=${h.transitionAlpha}") +
            " last=[$lastPresentationLog] || land: " + landTrace.joinToString(" ; ") +
            " || doze: " + dozeTrace.joinToString(" ; ") + " || scene: " + sceneTrace.joinToString(" ; ")
    }
    fun nativeHeaderHidden(): Boolean = nativeSuppressionRequested
    fun visibleHeightDp(): Float = configuredHeightDp

    fun transitionActive(): Boolean = morph != null
    fun hardSuppressionActive(): Boolean = nativeSuppressionRequested && morph == null

    /** A morph into the cover or the lyrics landed on the card at this uptime, the scene not up yet. */
    private var sceneLandedAt = 0L

    private val morphListener = object : MiniCardMorph.Listener {
        /** A scene exit hands the pill back only once the cover has let go of the lock screen. */
        override fun canSettle(morph: MiniCardMorph, toNative: Boolean) =
            toNative || !morphScene || !Main.coverSceneActive()

        override fun artBridged() = artBridged

        override fun onSettled(morph: MiniCardMorph, toNative: Boolean, completed: Boolean) {
            if (this@MiniPlayerController.morph !== morph) return
            this@MiniPlayerController.morph = null
            // Into the cover or the lyrics: landed on the card a moment before the scene has
            // the lock screen, the pill was the lock screen's again for those frames and came
            // up bare between the torch and the camera - its artwork still in the flight
            // (filmed 2026-09-25). It stays down until the scene is up, or not coming.
            if (toNative && morphScene) sceneLandedAt = android.os.SystemClock.uptimeMillis()
            morphScene = false
            Xp.log("MCMini: container morph ${if (completed) "landed" else "cancelled"} " +
                "at ${if (toNative) "native" else "mini"}")
            if (soloRow) {
                // At the card the row goes with the pill, still laid out alone; at the pill the
                // small island comes out from under its end and the pill narrows for it.
                updateVisibility()
                endSoloRow()
            }
            position()
            updateVisibility()
        }
    }

    /**
     * Starts, or turns round, the container morph. A dynamic switch goes wherever the selection
     * now points; a scene route needs the mini player to be the selected presentation.
     */
    fun beginTransition(toNative: Boolean, scene: Boolean): Boolean {
        if (scene) startSceneTrace()
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
        // A switch still settling is finished where it was headed: the card morph has the pill.
        endSwap()
        beginSoloRow(fromPill = toNative && view.visibility == View.VISIBLE)
        view.visibility = View.VISIBLE
        updateNativeSuppression(false)
        position()
        val next = MiniCardMorph(view, native, toNative, morphListener, restBox = ::rowBoxOnScreen,
            circle = false)
        morph = next
        morphScene = scene
        if (!next.start()) {
            morph = null
            morphScene = false
            endSoloRow()
            Xp.log("MCMini: no geometry for a morph; switching in place")
            updateVisibility()
            return false
        }
        return true
    }

    fun pill(): MiniPlayerView? = player

    /** The row can be shown and touched: with music, the media card has to be up; without, not. */
    private fun canShow(): Boolean =
        if (islandKeys.firstOrNull() == MUSIC_ISLAND) Main.miniPlayerCanShow() else Main.miniPlayerIslandsCanShow()

    /** A morph moving on its springs under this point - the switch's, or a scene's. */
    /** A notification's morph is not the music's to catch: its drag engine would drive it wrong. */
    fun catchableAt(x: Float, y: Float): Boolean = noteDrag == null && morph?.catchableAt(x, y) == true

    /** Whether the running morph belongs to a cover or lyrics entry or exit. */
    fun morphIsScene(): Boolean = morph != null && morphScene

    /** Lets go of a caught scene morph toward an end, the scene itself decided by the caller. */
    fun releaseMorph(toNative: Boolean, velocity: Float) {
        morph?.release(toNative, velocity)
    }

    fun grabMorph(): MiniCardMorph.Grab? = morph?.grab()

    fun cardAbovePill(): Boolean {
        // A notification's row is always above its island.
        if (noteMorphKey != null) return true
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
        if (noteMorphKey != null) return noteSpan.takeIf { it > 0f }
        val pill = player?.restBoxOnScreen() ?: return null
        val card = transitionHeader() ?: return null
        val xy = IntArray(2).also(card::getLocationOnScreen)
        return kotlin.math.abs(pill.y - xy[1]).coerceAtLeast(160f * density())
    }

    fun beginDragMorph(fromNative: Boolean): Boolean {
        val view = player ?: return false
        val native = transitionHeader() ?: return false
        endSwap()
        beginSoloRow(fromPill = !fromNative && view.visibility == View.VISIBLE)
        view.visibility = View.VISIBLE
        updateNativeSuppression(false)
        position()
        // Starts at the end it was pulled from; release() aims it once the lift decides.
        val next = MiniCardMorph(view, native, !fromNative, morphListener, restBox = ::rowBoxOnScreen,
            circle = false)
        morph = next
        morphScene = false
        if (!next.startDragging()) {
            morph = null
            endSoloRow()
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
        if (noteMorphKey != null) {
            releaseNote(toNative, velocity)
            return
        }
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
        // The small island first: the camera's button (its shortcut_view_right_layout above
        // all) reaches well past its disc, over the small island's right half. Asked second, a
        // tap there was the camera's - and opened it (filmed 2026-09-25). Taken here, the whole
        // gesture is the row's and the button never hears of it.
        val onSmall = smallIslandAt(x, y)
        if (!onSmall && (!inside || onButton(left, x, y) || onButton(right, x, y))) return null
        val refused = when {
            view.visibility != View.VISIBLE -> "hidden"
            !view.isAttachedToWindow -> "detached"
            morph != null -> "morph running"
            !view.acceptsTouch() -> "not interactive (canShow=${canShow()} " +
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
        returning?.let { key ->
            val waited = android.os.SystemClock.uptimeMillis() - returningSince
            when {
                notes.any { it.key == key } -> returning = null
                waited > RETURN_WAIT_MS || !Main.keyguardLocked() -> returning = null
                else -> islandKeys = keptInPlace(islandKeys, key)
            }
        }
        // A notification on its way out to its row, or in from it, stays in the pill till it lands.
        noteMorphKey?.let { key -> islandKeys = keptInPlace(islandKeys, key) }
        // Notifications out as their rows keep their places too, for when they come back.
        var order = islandKeys
        for (k in islandOrder) if (k !in order && LockIslands.isReleased(k)) order = keptInPlace(order, k)
        islandOrder = order
        noteMorphKey?.let { key ->
            // The pill itself morphing: it keeps what it is showing. Under a flight it goes on
            // being the row's (the island beside the flight can take it).
            if (flight == null) {
                updateSmallIsland(music, notes)
                updateVisibility()
                schedulePosition()
                return
            }
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
        if (lost) {
            val oldSmall = smallKey
            handler.post { if (swap == null && morph == null) startSwap(null, oldSmall) }
        }
        val view = player ?: MiniPlayerView(context).also {
            player = it
            host.addView(it, lockScreenLayerIndex(), ViewGroup.LayoutParams(1, 1))
        }
        if (music != null) trackMusic(music)
        // A notification let out and on its way home to the pill is not among the notes yet - nor
        // for a run of the pipeline after it has landed (returning). Looked up in the notes
        // alone, the pill took the music for those frames: the music flashed in the pill right
        // after the notification had landed there (2026-09-25).
        val note = notes.firstOrNull { it.key == selected }
            ?: selected.takeIf { it != MUSIC_ISLAND }?.let(LockIslands::noteFor)
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
        // The discs stay while any island is out as its row: the last notification pulled out
        // of a row with no music left the row empty, and the torch and camera lost their glass
        // with it (2026-09-25) - where the music, out as its card, still counts as an island.
        discsWanted = keyguardOwned || enabled && !MiniPlayerScene.keyguardGoingAway &&
            Main.keyguardLocked() && LockIslands.releasedKeys().isNotEmpty()
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
        if (sceneLandedAt != 0L && (Main.coverSceneActive() ||
                android.os.SystemClock.uptimeMillis() - sceneLandedAt > SCENE_WAIT_MS)) sceneLandedAt = 0L
        val shown = presentation.showMini && sceneLandedAt == 0L
        // The stack leaves the notifications out only while the row is there to show them.
        LockIslands.setActive(shown && keyguardOwned)
        if (!keyguardOwned) selectedIsland = null
        // While a flight is out and coming back, its end is the small island's place: the small
        // island is the flight, and whatever was there makes way. Once it is out for good the
        // place is the next island's.
        // Coming in (a collapse), from the moment the flight exists: counted only once its morph
        // ran, the small island grew in its place for the frame before and then shrank for it.
        // Going out it waits for the morph, which takes it over in place.
        val held = flight != null && (morph != null || !flightFromSmall) && !flightOut &&
            smallKey == noteMorphKey && !flightLanding
        setSmallShown(shown && smallKey != null && !held && !soloRow, animate = shown && !snapSmallOnce,
            emerge = soloRow || soloEnded)
        snapSmallOnce = false
        soloEnded = false
        if (!keyguardOwned) preferredSmall = null
        if (!Main.keyguardLocked()) releasedFromPill.clear()
        if (view != null) {
            val target = if (shown) View.VISIBLE else View.GONE
            if (view.visibility != target) view.visibility = target
            view.setInteractionsEnabled(canShow() && !controlCenterOpen && !MiniPlayerScene.aodActive)
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
        // With a small island the pill makes room for it, the two centred as one group - and
        // the group kept clear of both discs: the camera's touch area is wider than its disc,
        // and a group reaching into it lost the small island's taps to the camera.
        val gap = dp(MiniPlayerGeometry.DISC_GAP_DP)
        val small = smallKey != null && !soloRow
        val pillWidth = if (!small) width else MiniPlayerGeometry.pillBesideIslandPx(width,
            MiniPlayerGeometry.clearOfDiscsPx(
                MiniPlayerGeometry.widthPx(host.width, host.width, centerX, dp(12f)),
                centerX, l?.get(0), r?.get(0), height.toFloat(), gap.toFloat(), 0),
            height, gap, dp(MiniPlayerGeometry.MIN_PILL_DP))
        val group = pillWidth + if (small) gap + height else 0
        val groupLeft = centerX - group / 2f
        if (small) {
            smallRest[0] = groupLeft + pillWidth + gap + height / 2f
            smallRest[1] = centerY
        }
        // The asked-for size, not view.width: a morph resizes the frame while this still runs.
        // The spring first, then the new size: set the other way round, the pill was drawn at
        // its new width for the frame before its spring set out from the old one.
        val x = groupLeft - view.left
        val y = centerY - height / 2f - view.top
        view.setBaseTranslation(x, y)
        rowTarget(groupLeft, pillWidth.toFloat())
        if (view.layoutParams.width != pillWidth || view.layoutParams.height != height) {
            view.layoutParams = view.layoutParams.apply { this.width = pillWidth; this.height = height }
            schedulePosition()
        }
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

/** The small island's nudge home: MiniPlayerView's OFFSET_RESPONSE, CoverMorphMotion's damping. */
private const val SMALL_NUDGE_RESPONSE = 0.32f
private const val SMALL_NUDGE_DAMPING = 0.8f

/** How long the pill waits, down, for a scene its morph has just landed into. */
private const val SCENE_WAIT_MS = 1000L

/** After the doze, the buttons are the lock screen's again within this long whatever happens. */
private const val HOLD_MAX_MS = 2000L

/** ...and once its own alpha has been back at full for this long. */
private const val HOLD_SETTLE_MS = 300L

/** A disc's frame against its diameter: room for the widest swelling and squeeze. */
private const val DISC_FRAME = 1.6f

/** A flight's home: the small island's circle, or the pill. */
private const val HOME_SMALL = 0
private const val HOME_PILL = 1

/**
 * The row's layout spring: CHANGE_EASE's response with more give, so the pill overshoots its
 * new width and comes back - and squeezes with its speed, [ROW_SQUASH] of its height per
 * width-per-second, at most [ROW_SQUASH_MAX].
 */
private const val ROW_DAMPING = 0.7f
private const val ROW_SQUASH = 0.02f
private const val ROW_SQUASH_MAX = 0.08f

/** What a switch does with the small island: see Swap.smallMode. */
private const val SMALL_KEPT = 0
private const val SMALL_FROM_PILL = 1
private const val SMALL_POP = 2
private const val SMALL_EMERGE = 3
private const val SMALL_FROM_GHOST = 4

/** From here on, a stand-in shrinking into the small island hands over to it. */
private const val GHOST_HANDOFF = 0.85f

/** What kind of switch: see Swap.kind. */
private const val SWAP_PAIR = 0
private const val SWAP_NEXT = 1
private const val SWAP_PREV = 2

/**
 * The super island's other springs (FolmeEase.spring(damping, response)): APPEAR 0.7/0.5,
 * SHOW 0.95/0.35, HIDDEN 1.0/0.2 (0.99 here: the spring's closed form divides by
 * sqrt(1 - damping^2)), ALPHA 0.95/0.15.
 */
private const val APPEAR_RESPONSE = 0.5f
private const val APPEAR_DAMPING = 0.7f
private const val SHOW_RESPONSE = 0.35f
private const val SHOW_DAMPING = 0.95f
private const val HIDDEN_RESPONSE = 0.2f
private const val HIDDEN_DAMPING = 0.99f
private const val ALPHA_RESPONSE = 0.15f
private const val ALPHA_DAMPING = 0.95f

/** HiddenToBigIsland brings the content in on HIDDEN_EASE with this delay. */
private const val CONTENT_IN_DELAY_MS = 100L

/** A hidden island's size against its own (createHiddenProperties: 20% in on each side). */
private const val HIDDEN_SCALE = 0.6f

/** The cutout's stand-in, as a share of the row's height. */
private const val CUTOUT_SHARE = 0.55f

/** How far an island's content goes out of focus leaving the big island's place. */
private const val SWAP_BLUR_DP = 10f

/** The music's place in the row of islands, beside the notifications' keys. */
private const val MUSIC_ISLAND = "\u0000music"

/** An island switch's spring: the super island's CHANGE_EASE. */
private const val SWAP_RESPONSE = 0.4f
private const val SWAP_DAMPING = 0.82f

/** The super island's swipe progress: the pull over half the width, times this. */
private const val SWIPE_SHARE = 0.14f

/** A pull under the threshold goes home in this long. */
private const val SPRING_BACK_MS = 320L

/** How long a notification folded back in may take to come back from the stack. */
private const val RETURN_WAIT_MS = 1500L

/** A row hidden on its way out of the stack shows again if it is still there after this. */
private const val ROW_GONE_MS = 1200L

/** How long a released notification's row may take to be laid out before the pill moves on. */
private const val ROW_WAIT_MS = 800L

/** A row's picture, title and text, by the ids the notification templates give them. */
private val ICON_NAMES = setOf("right_icon", "icon", "app_icon", "notification_icon", "left_icon")
private val TITLE_NAMES = setOf("title", "notification_title")
private val TEXT_NAMES = setOf("text", "big_text", "notification_text")

/** Below this progress, a flight coming home fades off the small island shown under it. */
private const val FLIGHT_HANDOFF = 0.18f

/** The shortest a notification pull can be, island to row, in dp. */
private const val NOTE_SPAN_MIN_DP = 160f

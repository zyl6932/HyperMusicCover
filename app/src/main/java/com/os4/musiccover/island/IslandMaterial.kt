package com.os4.musiccover.island

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.os4.musiccover.Gen
import com.os4.musiccover.Main
import com.os4.musiccover.Xp
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * What the lock screen islands and the shortcut discs are dressed in, and the colour of their
 * words: decided the way SystemUI decides it for its own lock screen rows, not guessed from the
 * calls it happens to make.
 *
 * SystemUI keeps the material as state (NotificationMaterialStateInteractor: the material type,
 * BLUR / GLASS, and the view state - its NotificationStyleType, light wallpaper, keyguard
 * notifications shown - plus the full AOD). Each row's binder
 * (ExpandableNotificationRowViewBinder.bindInternal$updateEffect) turns that into one effect
 * type, keys it, and only when the key changes clears the old effect and applies the new one
 * with a Context fixed to the style (getFixUiModeContext). The effects read nothing but
 * resources from that Context. The media card's words come from MiuiMediaViewControllerImpl
 * .updateForegroundColors: a dark Context in the full AOD or on glass, its own otherwise.
 *
 * The islands follow the same: the key is the effect an ordinary lock screen row wears now; an
 * effect's calls are recorded only from inside that effect's own apply, one recipe per effect
 * and style; and the islands are dressed again only when the key or its recipe changes.
 * Before this, every material call on any row was taken as the material, last one wins - the
 * wake's every-frame blend re-dressed the discs on every frame (#135), and a change of material
 * made away from the lock screen was never seen (2026-11-10).
 */
internal object IslandMaterial {

    class Recorded(val method: Method, val args: Array<Any?>, val viewAt: Int)

    class Recipe(val calls: List<Recorded>, val background: Drawable.ConstantState?, val signature: Any)

    /** The effect a lock screen row wears now, and the style its Context is fixed to. */
    class State(val type: Any, val typeName: String, val effect: Any, val style: Any, val styleName: String) {
        val key: String get() = "${effect.javaClass.simpleName}/$styleName"
    }

    // ---- the OEM's state

    private var interactor: WeakReference<Any>? = null
    private var viewStateLazy: Field? = null
    private var materialTypeField: Field? = null
    private var fullAodMethod: Method? = null
    private var fixContextMethod: Method? = null
    private var glassMethod: Method? = null
    private val getValue = HashMap<Class<*>, Method>()
    private val stateFields = HashMap<String, Field>()
    private var rowEffects: Map<*, *>? = null
    private var mediaEffects: Map<*, *>? = null
    private var typeForMaterial: Map<*, *>? = null
    private var effectTypes: Array<Any>? = null
    private var fullAodFlag: Field? = null
    private var fullAodControllerField: Field? = null
    private var willAnimField: Field? = null
    private var darkContextMethod: Method? = null
    private var backgroundNormal: Method? = null
    private var injector: Method? = null

    /** Told when a recipe arrives or changes, with its key. */
    private var onRecipe: ((String) -> Unit)? = null

    fun install(classLoader: ClassLoader, recipeChanged: (String) -> Unit) {
        onRecipe = recipeChanged
        val base = "com.android.systemui.statusbar.notification.style"
        runCatching {
            val cls = Xp.findClass("$base.domain.NotificationMaterialStateInteractor", classLoader)
            viewStateLazy = cls.getDeclaredField("materialViewState\$delegate").apply { isAccessible = true }
            materialTypeField = cls.getDeclaredField("materialType").apply { isAccessible = true }
            fullAodMethod = cls.getDeclaredMethod("shouldApplyFullAodEffect").apply { isAccessible = true }
            glassMethod = cls.getDeclaredMethod("isGlassMaterialType").apply { isAccessible = true }
            fixContextMethod = cls.declaredMethods.first {
                it.name == "getFixUiModeContext" && it.parameterTypes.size == 2
            }.apply { isAccessible = true }
            // The interactor is a dagger singleton nothing hands out; every binder asks it
            // isGlassMaterialType, so the first ask - cold start or a hot reload alike - gives it.
            Xp.hookAll("sysui.mini.material_state", cls, "isGlassMaterialType") { chain ->
                if (interactor?.get() !== chain.thisObject) interactor = WeakReference(chain.thisObject)
                chain.proceed()
            }
        }.onFailure { Xp.w("MCMini: material state unavailable, islands keep their last material: $it") }
        runCatching {
            val helper = Xp.findClass("$base.vieweffect.NotificationViewEffectHelper", classLoader)
            fun map(name: String) = helper.getDeclaredField(name).apply { isAccessible = true }.get(null) as Map<*, *>
            rowEffects = map("notificationRowEffectsMap")
            mediaEffects = map("mediaViewEffectsMap")
            typeForMaterial = map("mapMaterialTypeToNotifEffectType")
            @Suppress("UNCHECKED_CAST")
            effectTypes = Xp.findClass("$base.data.NotifEffectType", classLoader).enumConstants as Array<Any>
        }.onFailure { Xp.w("MCMini: effect maps unavailable: $it") }
        runCatching {
            fullAodFlag = Xp.findClass("com.android.systemui.statusbar.notification.fullaod.NotifiFullAodController",
                classLoader).getDeclaredField("mEnableFullAod").apply { isAccessible = true }
        }.onFailure { Xp.w("MCMini: full-AOD flag unavailable for the ink: $it") }
        runCatching {
            fullAodControllerField = Xp.findClass("$base.domain.NotificationMaterialStateInteractor", classLoader)
                .getDeclaredField("notifiFullAodController").apply { isAccessible = true }
            willAnimField = Xp.findClass("com.android.systemui.statusbar.notification.fullaod.NotifiFullAodController",
                classLoader).getDeclaredField("mWillDoAodAnim").apply { isAccessible = true }
        }.onFailure { Xp.w("MCMini: full-AOD run flag unavailable, the islands change at once into the AOD: $it") }
        runCatching {
            darkContextMethod = Xp.findClass("$base.domain.NotificationContextRepository\$Companion", classLoader)
                .getDeclaredMethod("createDarkContext", Context::class.java).apply { isAccessible = true }
        }.onFailure { Xp.w("MCMini: dark context unavailable for the ink: $it") }
        runCatching {
            val row = Xp.findClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", classLoader)
            injector = row.getMethod("getInjector").apply { isAccessible = true }
            backgroundNormal = injector!!.returnType.getMethod("getBackgroundNormal").apply { isAccessible = true }
        }.onFailure { Xp.w("MCMini: row background unavailable, rows are not recorded: $it") }
        installRecorder(classLoader)
        rowEffects?.values?.filterNotNull()?.map { it.javaClass }?.distinct()?.forEach { cls ->
            runCatching { Xp.hookAll("sysui.mini.row_apply", cls, "apply") { chain -> recordRow(chain, cls, clear = false) } }
                .onFailure { Xp.w("MCMini: ${cls.simpleName}.apply not recorded: $it") }
            runCatching { Xp.hookAll("sysui.mini.row_clear", cls, "clear") { chain -> recordRow(chain, cls, clear = true) } }
        }
        mediaEffects?.values?.filterNotNull()?.map { it.javaClass }?.distinct()?.forEach { cls ->
            runCatching { Xp.hookAll("sysui.mini.apply", cls, "apply") { chain -> recordMedia(chain, cls) } }
                .onFailure { Xp.w("MCMini: ${cls.simpleName}.apply not recorded: $it") }
        }
    }

    private fun method(o: Any, name: String): Method = getValue.getOrPut(o.javaClass) {
        var c: Class<*>? = o.javaClass
        var m: Method? = null
        while (c != null && m == null) {
            m = runCatching { c!!.getDeclaredMethod(name) }.getOrNull()
            c = c.superclass
        }
        (m ?: o.javaClass.getMethod(name)).apply { isAccessible = true }
    }

    private fun stateField(o: Any, name: String): Any? =
        stateFields.getOrPut(name) { o.javaClass.getDeclaredField(name).apply { isAccessible = true } }.get(o)

    private var stateAt = 0L
    private var stateCached: State? = null

    /**
     * The effect an ordinary lock screen row wears now, as the row binder picks it: on the
     * keyguard, BLUR -> BLUR_ROW_ON_KEYGUARD; GLASS -> GLASS_ROW_FULL_AOD in the full AOD,
     * GLASS_ROW_ON_KEYGUARD_LIGHT_WALL_PAPER on a light wallpaper, GLASS_ROW_ON_KEYGUARD
     * otherwise; anything else the material type's own. The islands are only ever on the
     * keyguard, so the keyguard branch is taken whether or not the stack shows rows. Read at
     * most once a frame's time: it is asked on every frame the discs follow their buttons.
     */
    fun state(): State? {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - stateAt < STATE_MS) return stateCached
        stateAt = now
        stateCached = runCatching { readState() }.getOrNull()
        return stateCached
    }

    private fun readState(): State? {
        val owner = interactor?.get() ?: return null
        // The full AOD's colour run is about to take the rows across: the binder leaves their
        // effect as it is until the run is through (updateEffect returns early while
        // mWillDoAodAnim), and so do the islands. Changed at once, they went to the dark plate,
        // then the run took them back to the light one and down again - black, white, black on
        // the light frost into the AOD (the user, 2026-11-10).
        val held = stateCached
        if (held != null && willAnimField != null) {
            val controller = fullAodControllerField?.get(owner)
            if (controller != null && willAnimField!!.getBoolean(controller)) return held
        }
        val lazy = viewStateLazy?.get(owner) ?: return null
        val flow = method(lazy, "getValue").invoke(lazy) ?: return null
        val vs = method(flow, "getValue").invoke(flow) ?: return null
        val style = stateField(vs, "notifStyle") ?: return null
        val light = stateField(vs, "isLightWallPaper") as? Boolean ?: false
        val material = materialTypeField?.get(owner) ?: return null
        val fullAod = fullAodMethod?.invoke(owner) as? Boolean ?: false
        val typeName = when ((material as Enum<*>).name) {
            "BLUR" -> "BLUR_ROW_ON_KEYGUARD"
            "GLASS" -> when {
                fullAod -> "GLASS_ROW_FULL_AOD"
                light -> "GLASS_ROW_ON_KEYGUARD_LIGHT_WALL_PAPER"
                else -> "GLASS_ROW_ON_KEYGUARD"
            }
            else -> (typeForMaterial?.get(material) as? Enum<*>)?.name ?: return null
        }
        val type = effectTypes?.firstOrNull { (it as Enum<*>).name == typeName } ?: return null
        val effect = rowEffects?.get(type) ?: return null
        return State(type, typeName, effect, style, (style as Enum<*>).name)
    }

    /** The media card's effect for the same state: its recipe stands in until a row's is recorded. */
    private fun mediaKey(s: State): String? {
        val name = s.typeName.replace("_ROW_", "_MEDIA_")
        val type = effectTypes?.firstOrNull { (it as Enum<*>).name == name } ?: return null
        val effect = mediaEffects?.get(type) ?: return null
        return "media:${effect.javaClass.simpleName}/${s.styleName}"
    }

    private fun mediaEffect(s: State): Any? {
        val name = s.typeName.replace("_ROW_", "_MEDIA_")
        val type = effectTypes?.firstOrNull { (it as Enum<*>).name == name } ?: return null
        return mediaEffects?.get(type)
    }

    // ---- recording

    private val recipes = HashMap<String, Recipe>()
    private val clears = HashMap<String, Recipe>()
    private var effectDepth = 0
    private var recording: ArrayList<Recorded>? = null
    private var recordTarget: View? = null
    private var recordDepth = 0
    /** The glass's maximum size comes with a row's size, outside any effect: the last one seen. */
    @Volatile private var lastSdf: Recorded? = null
    /** The last row an effect was recorded from, for the container's blur (matchRowContainer). */
    @Volatile var lastRow: WeakReference<View>? = null
        private set

    /** What the effects call on a row's background or the card's media_bg: the outermost of them. */
    private fun installRecorder(classLoader: ClassLoader) {
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
                    it.name in names && java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                        it.parameterTypes.any { p -> View::class.java.isAssignableFrom(p) }
                }.forEach { m ->
                    m.isAccessible = true
                    Xp.hook("sysui.mini.glass." + Xp.slug(className.substringAfterLast('.')) + "." + Xp.slug(m.name), m) { chain ->
                        val list = recording
                        val bg = recordTarget
                        val args = chain.args.toTypedArray()
                        if (list == null || bg == null || recordDepth > 0) {
                            if (list == null && m.name == "setMiGlassSdfMaxSizeCompat") {
                                val at = args.indexOfFirst { it is View }
                                val v = args.getOrNull(at) as? View
                                if (v != null && v !is MaterialElement && v.javaClass.name.contains("NotificationBackgroundView"))
                                    lastSdf = Recorded(m, Array(args.size) { i -> copyArray(args[i]) }, at)
                            }
                            return@hook chain.proceed()
                        }
                        val at = args.indexOfFirst { it === bg }
                        if (at < 0) return@hook chain.proceed()
                        recordDepth++
                        try {
                            chain.proceed()
                        } finally {
                            recordDepth--
                            // Copied: the OEM hands the same array to the next dressing, changed.
                            list.add(Recorded(m, Array(args.size) { i -> copyArray(args[i]) }, at))
                        }
                    }
                }
            }.onFailure { Xp.w("MCMini: material recorder on $className unavailable: $it") }
        }
    }

    private fun copyArray(value: Any?): Any? = when (value) {
        is IntArray -> value.copyOf()
        is FloatArray -> value.copyOf()
        else -> value
    }

    private fun rowBackground(row: Any?): View? = runCatching {
        backgroundNormal?.invoke(injector?.invoke(row ?: return null)) as? View
    }.getOrNull()

    private fun styleName(): String? = runCatching {
        val owner = interactor?.get() ?: return null
        val lazy = viewStateLazy?.get(owner) ?: return null
        val flow = method(lazy, "getValue").invoke(lazy) ?: return null
        val vs = method(flow, "getValue").invoke(flow) ?: return null
        (stateField(vs, "notifStyle") as? Enum<*>)?.name
    }.getOrNull()

    /** One row effect's apply or clear, recorded off the row's background when it is the outermost. */
    private fun recordRow(chain: io.github.libxposed.api.XposedInterface.Chain, cls: Class<*>, clear: Boolean): Any? {
        val row = chain.args.firstOrNull() as? View
        val bg = if (effectDepth == 0 && recording == null) rowBackground(row) else null
        if (bg != null) {
            recordTarget = bg
            recording = ArrayList()
        }
        effectDepth++
        val result = try {
            chain.proceed()
        } finally {
            effectDepth--
        }
        if (bg != null) {
            val list = recording.orEmpty()
            recording = null
            recordTarget = null
            if (list.isNotEmpty()) {
                if (!clear) lastRow = WeakReference(row)
                val style = styleName() ?: "?"
                if (clear) clears[cls.simpleName] = Recipe(list, null, signature(cls.name, list, null))
                else keep("${cls.simpleName}/$style", list, bg.background, bg.context)
            }
        }
        return result
    }

    /** One media effect on the lock screen's card, recorded off its media_bg when it is the outermost. */
    private fun recordMedia(chain: io.github.libxposed.api.XposedInterface.Chain, cls: Class<*>): Any? {
        val target = chain.args.firstOrNull() as? View
        val card = Main.miniPlayerMediaHeader()
        val mine = effectDepth == 0 && recording == null && target != null && (card == null || target === card)
        val bg = if (mine) Main.miniPlayerMediaBg(target) else null
        if (bg != null) {
            recordTarget = bg
            recording = ArrayList()
        }
        effectDepth++
        val result = try {
            chain.proceed()
        } finally {
            effectDepth--
        }
        if (bg != null) {
            val list = recording.orEmpty()
            recording = null
            recordTarget = null
            if (list.isNotEmpty()) keep("media:${cls.simpleName}/${styleName() ?: "?"}", list, bg.background, bg.context)
        }
        return result
    }

    private fun keep(key: String, list: List<Recorded>, bg: Drawable?, context: Context) {
        val sig = signature(key, list, bg)
        if (recipes[key]?.signature == sig) return
        recipes[key] = Recipe(list, bg?.constantState, sig)
        runCatching { save(context, key, list, bg) }.onFailure { Xp.log("MCMini: recipe $key not kept: $it") }
        MiniPlayerRuntime.matNoteFor("recipe <- $key: " + list.joinToString(" ; ") { it.method.name })
        onRecipe?.invoke(key)
    }

    /** A recipe's values: the effect, the background, and every call with its arguments. */
    private fun signature(effect: String, calls: List<Recorded>, bg: Drawable?): Any {
        val background: Any? = when (bg) {
            null -> null
            is GradientDrawable -> listOf(bg.javaClass.name, bg.color?.defaultColor, bg.color?.isStateful,
                bg.cornerRadius, MiniPlayerMaterialState.snapshot(bg.cornerRadii), bg.shape,
                bg.gradientType, bg.orientation, MiniPlayerMaterialState.snapshot(bg.colors))
            is android.graphics.drawable.ColorDrawable -> listOf(bg.javaClass.name, bg.color)
            else -> bg.javaClass.name
        }
        return listOf(effect, background, calls.map { call ->
            listOf(call.method, call.viewAt, call.args.mapIndexed { i, arg ->
                if (i == call.viewAt || arg is Context) null else MiniPlayerMaterialState.snapshot(arg)
            })
        })
    }

    // ---- choosing and replaying

    /** Whether a recipe with [key] is what the islands would wear now: worth dressing them again. */
    fun wouldWear(key: String): Boolean {
        val s = state() ?: return false
        if (key == s.key) return true
        return recipes[s.key] == null && key == mediaKey(s)
    }

    /** The key the islands are dressed by; the last one known when the state cannot be read. */
    fun islandKey(context: Context?): String? {
        val k = state()?.key
        if (k != null) {
            if (k != lastKnownKey) {
                lastKnownKey = k
                context?.let { c -> runCatching { prefs(c).edit().putString(LAST_KEY, k).apply() } }
            }
            return k
        }
        if (lastKnownKey == null && context != null) lastKnownKey = runCatching { prefs(context).getString(LAST_KEY, null) }.getOrNull()
        return lastKnownKey
    }

    private var lastKnownKey: String? = null
    private val dressedAs = java.util.WeakHashMap<View, String>()

    /**
     * Replays on [view] the recipe for the islands' key: the row's, recorded or kept; asking
     * SystemUI to apply the effect once more to a lock screen row when there is none; the media
     * card's for the same state meanwhile (kept, or likewise asked for). Throws when nothing is
     * there at all, for material() to fall back on its plain fill. Returns what it wore.
     */
    fun replay(view: ImageView, classLoader: ClassLoader): String {
        val ctx = view.context
        val key = islandKey(ctx) ?: error("no material state yet")
        val s = state()
        val row = recipes[key] ?: load(ctx, classLoader, key)
        val worn: String
        val recipe = if (row != null) {
            worn = key
            row
        } else {
            s?.let { requestRow(it) }
            val mk = s?.let(::mediaKey)
            val media = mk?.let { recipes[it] ?: load(ctx, classLoader, it) }
            if (media == null) s?.let { requestMedia(it) }
            if (media == null) {
                // Nothing to put on yet, and the recipe is on its way: a view already dressed
                // keeps what it wears until it lands, rather than a plain fill for a frame.
                dressedAs[view]?.let { return "kept $it" }
                error("no recipe for $key")
            }
            worn = mk ?: "none"
            media
        }
        // The effect it wore before taken off first, as the binder clears the old effect.
        val before = dressedAs[view]
        if (before != null && before != worn) {
            clears[before.substringAfter("media:").substringBefore('/')]?.let { c -> play(view, c) }
        }
        view.setImageDrawable(null)
        view.background = recipe.background?.newDrawable(ctx.resources)?.mutate()
        play(view, recipe)
        lastSdf?.let { sdf ->
            val args = sdf.args.copyOf()
            args[sdf.viewAt] = view
            runCatching { sdf.method.invoke(null, *args) }
        }
        dressedAs[view] = worn
        return worn
    }

    private fun play(view: View, recipe: Recipe) {
        for (call in recipe.calls) {
            val args = call.args.copyOf()
            args[call.viewAt] = view
            // The recorded Context stays: it is the one fixed to the style (getFixUiModeContext),
            // and the blend reads its colours from it.
            call.method.invoke(null, *args)
        }
    }

    private val asked = HashMap<String, Long>()

    private fun mayAsk(key: String): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val at = asked[key]
        if (at != null && now - at < ASK_MS) return false
        asked[key] = now
        return true
    }

    /**
     * No recipe for the row's effect: SystemUI applies it once more to a lock screen row, as its
     * own binder does on every rebind - the row already wears it, so nothing changes on screen -
     * and the recorder takes it. Posted: this is the middle of a dressing.
     */
    private fun requestRow(s: State) {
        if (!mayAsk(s.key)) return
        Gen.post {
            runCatching {
                val row = ordinaryRow() ?: return@runCatching
                val owner = interactor?.get() ?: return@runCatching
                val ctx = fixContextMethod!!.invoke(owner, s.style, false) as Context
                applyMethod(s.effect).invoke(s.effect, row, ctx)
                MiniPlayerRuntime.matNoteFor("asked ${s.typeName} of a row for ${s.key}")
            }.onFailure { Xp.log("MCMini: row effect not asked again: $it") }
        }
    }

    /** No row to ask: the card's effect for the same state, once more on the card. */
    private fun requestMedia(s: State) {
        val mk = mediaKey(s) ?: return
        if (!mayAsk(mk)) return
        Gen.post {
            runCatching {
                if (recipes[s.key] != null) return@runCatching
                val header = Main.miniPlayerMediaHeader() ?: return@runCatching
                val effect = mediaEffect(s) ?: return@runCatching
                val owner = interactor?.get() ?: return@runCatching
                val ctx = fixContextMethod!!.invoke(owner, s.style, false) as Context
                applyMethod(effect).invoke(effect, header, ctx)
                MiniPlayerRuntime.matNoteFor("asked ${effect.javaClass.simpleName} of the card for $mk")
            }.onFailure { Xp.log("MCMini: media effect not asked again: $it") }
        }
    }

    private fun applyMethod(effect: Any): Method = effect.javaClass.declaredMethods.first {
        it.name == "apply" && it.parameterTypes.size == 2 && it.parameterTypes[0] == Any::class.java
    }.apply { isAccessible = true }

    /** A shown ordinary row of the lock screen's stack: not the media card's, not a focus card. */
    private fun ordinaryRow(): View? {
        lastRow?.get()?.takeIf { it.isAttachedToWindow && it.isShown }?.let { return it }
        val stack = MiniPlayerRuntime.stackForProbe() ?: return null
        for (i in 0 until stack.childCount) {
            val c = stack.getChildAt(i)
            if (!c.isShown || !c.javaClass.name.contains("ExpandableNotificationRow")) continue
            val n = runCatching {
                (Xp.getObjectField(Xp.callMethod(c, "getEntry"), "mSbn") as
                    android.service.notification.StatusBarNotification).notification
            }.getOrNull() ?: continue
            if (n.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)) continue
            if (runCatching { Xp.getObjectField(Xp.getObjectField(Xp.callMethod(c, "getEntry"), "mSbn"), "mIsFocusNotification") as Boolean }
                    .getOrDefault(false)) continue
            return c
        }
        return null
    }

    // ---- the words

    private var inkAt = 0L
    private var inkCached = intArrayOf(android.graphics.Color.WHITE, android.graphics.Color.WHITE)
    private var darkFor: WeakReference<Context>? = null
    private var dark: Context? = null

    /**
     * The card's words as MiuiMediaViewControllerImpl.updateForegroundColors colours them: from a
     * dark Context in the full AOD or on glass, from SystemUI's own otherwise (light or dark as
     * the phone is) - media_primary_text, and notification_media_action_button_color for the
     * buttons. [0] the title, [1] the buttons. Asked on every disc frame, so held for a moment.
     */
    fun ink(context: Context): IntArray {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - inkAt < STATE_MS) return inkCached
        inkAt = now
        runCatching {
            val fullAod = fullAodFlag?.getBoolean(null) ?: false
            val glass = interactor?.get()?.let { glassMethod?.invoke(it) as? Boolean }
                ?: (android.provider.Settings.Secure.getInt(context.contentResolver, "material_style", 1) == 1)
            val from = if (fullAod || glass) darkContext(context) else context
            val ids = inkIds ?: context.resources.let { res ->
                intArrayOf(res.getIdentifier("media_primary_text", "color", "com.android.systemui"),
                    res.getIdentifier("notification_media_action_button_color", "color", "com.android.systemui"))
            }.also { require(it[0] != 0 && it[1] != 0) { "no media text colours" }; inkIds = it }
            inkCached = intArrayOf(from.getColor(ids[0]), from.getColor(ids[1]))
        }.onFailure { if (!inkFailed) { inkFailed = true; Xp.w("MCMini: ink unavailable, white: $it") } }
        return inkCached
    }

    private var inkFailed = false
    /** media_primary_text, notification_media_action_button_color: looked up once. */
    private var inkIds: IntArray? = null

    private fun darkContext(context: Context): Context {
        if (darkFor?.get() === context) dark?.let { return it }
        val made = darkContextMethod?.invoke(null, context) as? Context ?: run {
            val config = android.content.res.Configuration(context.resources.configuration)
            config.uiMode = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            context.createConfigurationContext(config)
        }
        darkFor = WeakReference(context)
        dark = made
        return made
    }

    // ---- kept between starts

    private const val PREFS = "hmc_mini"
    private const val LAST_KEY = "island_material_key"
    private var prefsHeld: android.content.SharedPreferences? = null
    private var sysuiVersion: Long = -1L

    private fun prefs(context: Context): android.content.SharedPreferences = prefsHeld
        ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefsHeld = it }

    /** Kept per SystemUI build: another build's resources may dress the same effect differently. */
    private fun recipeKey(context: Context, key: String): String {
        if (sysuiVersion < 0) sysuiVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)
        return "island_recipe_v2/$sysuiVersion/$key"
    }

    private fun save(context: Context, key: String, list: List<Recorded>, bg: Drawable?) {
        val calls = JSONArray()
        for (r in list) {
            val args = JSONArray()
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
                    a is IntArray -> JSONObject().put("t", "ia").put("x", JSONArray(a.toList()))
                    a is FloatArray -> JSONObject().put("t", "fa").put("x", JSONArray(a.map { it.toDouble() }))
                    a is Context -> JSONObject().put("t", "ctx")
                    // Anything else cannot be written down: the recipe is not kept at all.
                    else -> error("argument ${a.javaClass.name} of ${r.method.name}")
                })
            }
            calls.put(JSONObject()
                .put("c", r.method.declaringClass.name)
                .put("m", r.method.name)
                .put("p", JSONArray(r.method.parameterTypes.map { it.name }))
                .put("v", r.viewAt)
                .put("a", args))
        }
        val kept = JSONObject().put("calls", calls)
        when (bg) {
            is GradientDrawable -> kept.put("bg", JSONObject().put("t", "gradient")
                .put("color", bg.color?.defaultColor ?: 0).put("radius", bg.cornerRadius.toDouble()))
            is android.graphics.drawable.ColorDrawable -> kept.put("bg", JSONObject().put("t", "color").put("color", bg.color))
        }
        val text = kept.toString()
        val p = prefs(context)
        val name = recipeKey(context, key)
        if (p.getString(name, null) != text) p.edit().putString(name, text).apply()
    }

    private val loaded = HashSet<String>()

    /** A recipe kept from an earlier start, when none has been recorded since this one. */
    private fun load(context: Context, classLoader: ClassLoader, key: String): Recipe? {
        if (!loaded.add(key)) return recipes[key]
        return runCatching {
            val text = prefs(context).getString(recipeKey(context, key), null) ?: return null
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
            val bg: Drawable.ConstantState? = kept.optJSONObject("bg")?.let { b ->
                when (b.getString("t")) {
                    "gradient" -> GradientDrawable().apply {
                        setColor(b.getInt("color"))
                        cornerRadius = b.getDouble("radius").toFloat()
                    }.constantState
                    "color" -> android.graphics.drawable.ColorDrawable(b.getInt("color")).constantState
                    else -> null
                }
            }
            Recipe(list, bg, signature(key, list, null)).also {
                recipes[key] = it
                Xp.log("MCMini: recipe $key read back (${list.size} calls)")
            }
        }.onFailure { Xp.log("MCMini: kept recipe $key unusable: $it") }.getOrNull()
    }

    // ---- probe

    fun describe(): String {
        val s = state()
        return "imat=[state=${s?.let { "${it.typeName}/${it.styleName}" } ?: "none"} key=${s?.key ?: lastKnownKey} " +
            "interactor=${interactor?.get() != null} recipes={" +
            recipes.entries.joinToString(",") { (k, r) -> "$k:${r.calls.size}" } +
            "} clears={${clears.keys.joinToString(",")}} sdf=${lastSdf != null} row=${lastRow?.get() != null} " +
            "ink=${inkCached.joinToString("/") { Integer.toHexString(it) }}]"
    }

    /** Retirement: the views and the row it held. The hooks are the ledger's. */
    fun retire() {
        dressedAs.clear()
        lastRow = null
        recording = null
        recordTarget = null
        effectDepth = 0
        recordDepth = 0
        darkFor = null
        dark = null
    }
}

/** The OEM's state and the ink are read at most this often: about a frame. */
private const val STATE_MS = 32L

/** A row or the card is asked for a missing effect at most this often per key. */
private const val ASK_MS = 2000L

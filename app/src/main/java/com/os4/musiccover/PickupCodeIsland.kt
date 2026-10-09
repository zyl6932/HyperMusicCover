package com.os4.musiccover

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import org.json.JSONObject

/**
 * A mini program order page's pickup code, as a focus island - ColorOS's 取餐码 (Gleaner's
 * observeagent), the part of it that needs no background display: the code is read while the page
 * is open.
 *
 * ColorOS reads the page with ViewExtract, its own framework code inside the app's process. Here
 * it is the assist structure, asked for from SystemUI (which holds GET_TOP_ACTIVITY_INFO) by task:
 * IActivityTaskManager.requestAssistDataForTask. Nothing is hooked in 微信 or 支付宝 and no
 * accessibility service is used, and both platforms hand the page over - 微信's mini programs are
 * XWeb WebViews, whose DOM text comes back in the structure (755 and 823 nodes on two real order
 * pages, 5-17 ms), and 支付宝's are drawn with real views, which come back as text nodes
 * (697 nodes / 132 texts on a real order list, 12 ms). [PickupParse] finds the code in either.
 *
 * Only the mini programs [BRANDS] names are read, by the task's own label (the mini program's
 * name, 「霸王茶姬」); ColorOS's list is cloud config by appId, which SystemUI is not handed. Both
 * platforms give a mini program a task of its own, labelled with it, which is what makes the label
 * the brand; which activities those tasks are is asked of the rules XML
 * (`wx_mini_activity` / `ali_mini_activity`), not assumed. While one is in front it is read a
 * second after it arrives and every [EVERY] ms after, as the order list and the order are pages of
 * one task and moving between them changes nothing SystemUI hears. A code found is shown whatever
 * the order's state; the island goes [LIFE] after the code was last seen, or when swiped away
 * (that code is then not shown again for [LIFE]).
 *
 * A new code floats the island open, and so does the order turning ready to collect ([READY]);
 * anything else about a code already up only updates it. The island's being up is asked of the
 * system rather than remembered, since the notification's own timeout takes it away unannounced.
 */
internal object PickupCodeIsland {

    private const val TAG = "MCPickup: "
    private const val SYSUI = "com.android.systemui"
    /** The app a tap falls back to, and the icon when the task carries none: it is the common case. */
    private const val WECHAT = "com.tencent.mm"
    private const val ALIPAY = "com.eg.android.AlipayGphone"
    private const val TOP_OBSERVER = "com.miui.systemui.functions.MiuiTopActivityObserver"
    /**
     * The brand tag ColorOS hands its recognizer. Every brand in its config has one; a tag it does
     * not know is answered as `common`, which is what the plugin falls back to anyway - and the 47
     * brands' tags live in that config, which is not ours to read yet, so this is the honest empty
     * one rather than a guess at a name.
     */
    private const val OEM_APP = "common"

    /**
     * The notification id a card goes out under, and the base it counts from.
     *
     * The back screen keeps a *widget* per (package, id) and slides one out by itself only when it
     * is a new widget - a notification that updates one it already has is drawn silently (see
     * [post]). 小爱's own pickup notification is a new id for every order for exactly that reason,
     * and one fixed id here meant only the first code of a session floated and every later one
     * waited to be pulled down by hand (2026-10-09). So a fresh code takes the next id and a quiet
     * update keeps the one it has.
     */
    private const val ID = 1241
    /** How many ids [ID] counts through before it starts over; far more codes than one session sees. */
    private const val IDS = 512
    private const val CHANNEL = "mc_pickup"
    private const val PIC = "miui.focus.pic_mc_pickup"
    private const val PIC_AOD = "miui.focus.pic_mc_aod"
    private const val PIC_MODEL = "miui.focus.pic_mc_model"

    /**
     * What this card calls itself in the focus and rear params. Ours, not 小爱's (`memory`): the
     * back screen's allow-list is keyed on it, and a business it does not know is one of the two
     * things that can have this card dropped on the way there.
     */
    private const val BUSINESS = "pickup_code"

    /**
     * What the rear copy calls itself. 小爱's own pickup notification posts as `memory`, and the
     * back screen only builds a widget for a business its list names - see where this is used.
     */
    private const val REAR_BUSINESS = "memory"

    /** The display the back screen's launcher runs on, `displayId=1` in `dumpsys activity top`. */
    private const val REAR_DISPLAY = 1

    private const val ACTION_OPEN = "com.os4.musiccover.PICKUP_OPEN"
    /** The island's own "back down" broadcast, which the plugin listens for and collapses on. */
    private const val ACTION_COLLAPSE_ISLAND = "com.miui.action.ACTION_COLLAPSE_ISLAND"
    private const val ACTION_GONE = "com.os4.musiccover.PICKUP_GONE"

    /**
     * What the tap needs, carried in the tap itself.
     *
     * The card outlives the process that posted it - a SystemUI restart, which is how a new build is
     * picked up, leaves the card on the back screen and this process with none of the state the tap
     * reads ([shownTask], [host], the mini program's launch url). Tapping it then opened nothing at
     * all, or the app's own home instead of the order page (2026-10-10). So the post writes what the
     * tap would otherwise have to remember.
     */
    private const val EXTRA_TASK = "mc_task"
    private const val EXTRA_HOST = "mc_host"
    private const val EXTRA_SCHEME = "mc_scheme"

    private const val FIRST = 1_000L
    /** Reads after the first that come quickly, a page just brought back may still be filling. */
    private const val QUICK = 2
    private const val QUICK_GAP = 1_000L
    private const val RETRIES = 10
    private const val RETRY_GAP = 300L
    private const val EVERY = 5_000L
    /** How often the page is read while the mini program is not the front activity. */
    private const val SLOW = 30_000L
    /** How long a task is read on after the mini program leaves the front before it is let go. */
    private const val LEFT = 2 * 60_000L
    /** A parked task is let go after this, as ColorOS's observeagent does. */
    private const val PARK_MAX = 30 * 60_000L
    /** How long a task stays unparked after the island was tapped. */
    private const val NO_PARK = 5 * 60_000L
    /** Reads of one stay in front, at most: an hour at [EVERY]. */
    private const val MAX_READS = 720
    private const val LIFE = 30 * 60_000L
    private const val TIMEOUT = 3_000L
    /** How long after the top-activity observer appears its state is read, for the front activity. */
    private const val STARTUP_LOOK = 2_000L
    /** The logo's fade-in, in ms: ColorOS's own `alpha_up_in` is 300ms on `coui_ease_move`. */
    private const val LOGO_FADE = 300L

    /**
     * The order states worth floating the island open for - the food can be collected. [PickupParse]
     * keeps the order's wording in its own list; this is what is done with a state, not reading it.
     * 已完成 is in: some pages call the ready state that (蜜雪冰城's read 「订单已完成」).
     */
    private val READY = setOf(
        "待取餐", "请取餐", "可取餐", "已出餐", "已准备完毕", "制作完成", "已完成",
    )

    /** The order is over: nothing left to wait on, and a parked task is let go. */
    private val DONE = setOf("已完成", "已取消", "已退款")

    /**
     * The order is being made. OPPO's config gives this state its own interval beside the plain
     * ordered one ([PickupObserve]); the two are equal in the file it ships, so this only matters
     * if a release ever makes them differ.
     */
    private val MAKING = setOf(
        "制作中", "制茶中", "备餐中", "配餐中", "出餐中", "餐厅准备中", "精心制作中", "已接单",
    )

    /** The mini programs read, matched in the task label. */
    private val BRANDS = listOf(
        // ColorOS's own (Gleaner, assets/observeAgent/applet-wechat-observe-config.json, v5) ...
        "麦当劳", "肯德基", "瑞幸", "奈雪", "霸王茶姬", "CoCo", "茶百道", "古茗", "沪上阿姨", "书亦",
        "益禾堂", "1点点", "幸运咖", "挪瓦", "NOWWA", "库迪", "喜茶", "甜啦啦", "M Stand", "Manner",
        "太平洋咖啡", "星巴克", "去茶山", "kuddo", "阿嬷手作", "peet", "混果汁", "konomi", "丘大叔", "华莱士",
        // ... and more of the same kind.
        "luckin", "蜜雪冰城", "一点点", "都可", "七分甜", "柠季", "茶颜悦色", "Tims", "汉堡王", "塔斯汀",
        "必胜客", "德克士", "老乡鸡", "袁记",
    )

    /** The settings switch, kept with Main's state. On unless turned off. */
    @JvmField var sOn = true

    /**
     * Whether the card is attached to the notification.
     *
     * **On, and it is the point of the card now**: this is HyperOS's own pickup card, taken from
     * 小爱同学's layout (`PickupCard`), so there is no longer a second card style to prefer - the
     * reason it was off was that the old one was ColorOS's and the system's own template read
     * better beside the system's island. Turned off, the template is back and the code is read
     * from the param alone (probe: `op pickup --ez card false`).
     */
    @JvmField var sCard = true

    private val bg: Handler by lazy { Handler(HandlerThread("mc-pickup").apply { start() }.looper) }

    // All below on [bg].
    private var lastTop: ComponentName? = null
    private var taskId = -1
    /** The app the tracked task belongs to - 微信 or 支付宝 - which is resumed behind its pages. */
    private var host = WECHAT
    /** Whether the tracked mini program is the front activity, which sets how often it is read. */
    private var inFront = false
    /** Until when the task is not parked again, set when the island is tapped (see [left]). */
    private var noParkUntil = 0L
    private var brand = ""
    /** The mini program the page being read belongs to, out of its own launch intent. */
    private var program: Program? = null
    /** This page's entry in ColorOS's rules - its colours, pictures and brand tag, or null. */
    private var oemRule: PickupRule? = null
    /** What ColorOS's recognizer last answered, for the probe's `describe()`. Not shown to anyone. */
    private var lastOem = ""
    /** The picture opacity the card is drawn at; 1f except while [fade] is stepping it up. */
    private var cardAlpha = 1f
    /** The opacity a floated card starts at; [fade] steps it up to 1. */
    private val FADE_FROM = 0.15f
    private var reads = 0
    /** Reads finished since this task was parked, which is what OPPO's first-after interval counts. */
    private var parkedReads = 0
    /** The task [parkedReads] belongs to, so a new park starts the count over. */
    private var parkedTask = -1
    private var gen = 0
    private var shownKey: String? = null
    /** The state [shownKey] was posted with, to tell 制作中 → 待取餐 from a re-read of the same. */
    private var shownStatus: String? = null
    /** The notification tag [shownKey] was posted under, null for the untagged one. */
    private var shownTag: String? = null
    /** The notification id [shownKey] was posted under, which the back screen's widget is keyed on. */
    private var shownId = ID
    /** Fresh codes posted by this process, counted into the id's range ([ID]). */
    private var ids = 0
    private var shownTask = -1
    private var muted: String? = null
    private var mutedUntil = 0L
    private var receivers = false
    private var lastRead = ""
    /** The front page's first texts when no code was found, for the probe only (not logged). */
    private var lastHead = ""
    private var iconSaid = false

    fun install(cl: ClassLoader) {
        runCatching {
            Xp.hookAll(Xp.findClass(TOP_OBSERVER, cl), "updateTopActivity") { chain ->
                val out = chain.proceed()
                runCatching {
                    val state = Xp.getObjectField(chain.thisObject, "mState")
                    val top = Xp.getObjectField(state, "topActivity") as? ComponentName
                    bg.post { front(top) }
                }
                out
            }
            Xp.log(TAG + "watching the front activity")
        }.onFailure { Xp.log(TAG + "front activity not watched: $it") }

        // And whatever is in front *now*, because the observer above only speaks when the front
        // activity changes. A SystemUI restart - which is how a new build is picked up - leaves the
        // mini program sitting in front with nothing changing, so the tracker would stay empty until
        // the user moved away and came back, which reads as "the island stopped working".
        //
        // Asked of ActivityManager rather than of the observer's own state: that state is only
        // filled in on the way through an update, and the observer may well have been built before
        // this module's hooks went in. Only the mini program is handed on - `front` is what decides
        // what is in front, and a wrong name there would stick.
        bg.postDelayed({
            runCatching {
                val ctx = Main.appContext() ?: return@runCatching
                @Suppress("DEPRECATION")
                val top = ctx.getSystemService(ActivityManager::class.java)
                    .getRunningTasks(1).firstOrNull()?.topActivity
                if (top != null && miniHost(ctx, top) != null) {
                    Xp.log(TAG + "starting on " + top.shortClassName)
                    front(top)
                }
            }
        }, STARTUP_LOOK)

        // The card's picture is an animated WebP - ColorOS's drink, 53 frames of a cup filling -
        // and the platform will not play it on its own. Resources hands an animated image back as
        // an AnimatedImageDrawable that is *not running*: ImageDecoder's own contract says so
        // ("To start its animation, call AnimatedImageDrawable.start()"), ImageView never calls it,
        // and nothing in the RemoteViews path does either. A card drawn from the resource therefore
        // holds its first frame, which is the half-full cup that used to look like the picture
        // sitting too high.
        //
        // So it is started here, once, as the card is built: the cup fills the first time the card
        // comes up and stays full - the artwork is a one-shot 53-frame WebP, and no second start is
        // reachable (the RemoteViews is never applied again, the view is never detached and
        // re-attached, no island callback reaches SystemUI, and the copies the island hands over are
        // not the ones being drawn - all of that was measured in the log on 2026-10-08).
        //
        // The notification is posted from this process, so the ImageView that draws it is one this
        // module can reach. The icon setter is the narrow place to watch - it is the one call
        // RemoteViews' own `setImageViewIcon` makes, and little else in SystemUI goes through it.
        // (Switching the card to `setImageViewResource` or `setImageViewBitmap` would move this to
        // `setImageDrawable`, and a bitmap carries the one frame whatever is done to it.)
        runCatching {
            Xp.hookAll(android.widget.ImageView::class.java, "setImageIcon") { chain ->
                val out = chain.proceed()
                val v = chain.thisObject
                // Guarded: this runs inside the host's own `setImageIcon`, and an exception here
                // would take the whole card down with it - a RemoteViews that throws while it is
                // applied simply does not appear.
                if (v is android.widget.ImageView && v.id == R.id.mc_pickup_icon) runCatching {
                    (v.drawable as? android.graphics.drawable.AnimatedImageDrawable)?.start()
                    fadeLogoIn(v)
                }
                out
            }
            Xp.log(TAG + "watching the card's picture")
        }.onFailure { Xp.log(TAG + "card's picture not watched: $it") }
    }

    /**
     * The brand's mark, faded in over the drink once the drink has finished filling.
     *
     * This is ColorOS's own card behaviour, taken from its implementation: `LevelDView` (the view
     * their `picture_d19`/`picture_d20` layers are) fades the second layer in over the first, and
     * its `card_modular_center_access_code.xml` anchors that layer 42dp above the picture's bottom -
     * the layout here carries the same layer, transparent. The delay is the picture's own length
     * (`PickupArt.duration`), because the drinks differ: 1749ms for the 53-frame ones, 600ms for the
     * 20-frame ones.
     *
     * The fade itself is a real animator on the live view. It has to be: a re-posted notification
     * would build the card again, and a card built again starts the drink over from an empty cup.
     * The module is in the same process as the host, so the view is one it can reach - which is also
     * how the drink is started in the first place.
     */
    private fun fadeLogoIn(picture: android.widget.ImageView) {
        val ms = PickupCard.lastAnimationMs
        Main.main().postDelayed({
            runCatching {
                val logo = (picture.parent as? android.view.View)
                    ?.findViewById(R.id.mc_pickup_logo) as? android.view.View ?: return@runCatching
                android.animation.ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f)
                    .setDuration(LOGO_FADE)
                    .start()
            }
        }, if (ms > 0) ms.toLong() else 0L)
    }

    fun setOn(on: Boolean) {
        sOn = on
        bg.post {
            if (!on) {
                stop()
                takeDown("switched off")
                PickupPark.releaseFrom("switched off")
            }
        }
    }

    /** The task the front mini program is being read from, -1 for none (the probe's `do vdtest`). */
    fun trackedTask(): Int = taskId

    /**
     * One read of an arbitrary task, for the park experiment (the probe's `do vdtest`): the assist
     * request a tracked read makes, handed back parsed and counted rather than shown. [done] runs on
     * [bg]; the island's own state is not touched.
     */
    fun readOnce(task: Int, done: (PickupParse.Result?, Int) -> Unit) {
        // Answered once: the assist reply, or [TIMEOUT]. A request that is never answered would
        // otherwise park the caller's loop for good.
        val said = java.util.concurrent.atomic.AtomicBoolean(false)
        fun once(r: PickupParse.Result?, n: Int) {
            if (said.compareAndSet(false, true)) bg.post { done(r, n) }
        }
        val timeout = Runnable { once(null, 0) }
        bg.postDelayed(timeout, TIMEOUT)
        val asked = request(task) { st, ex ->
            bg.post {
                bg.removeCallbacks(timeout)
                program = programOf(ex)
                val nodes = if (st != null) runCatching { nodes(st) }.getOrNull() else null
                once(nodes?.let { parse(it) }, nodes?.size ?: 0)
            }
        }
        if (!asked) {
            bg.removeCallbacks(timeout)
            once(null, 0)
        }
    }

    // Built rather than concatenated: an `if` in the middle of a `+` chain swallows what follows
    // it into its own else branch, which is how the oem= segment went missing from this line while
    // the recognizer behind it was answering all along.
    fun describe(): String = buildString {
        append("on=$sOn task=$taskId brand=$brand reads=$reads shown=${shownKey != null}")
        program?.let { append(" appId=${it.appId}") }
        program?.name?.takeIf { it.isNotEmpty() }?.let { append(" name=$it") }
        append(" up=${Main.appContext()?.let { up(it) }}")
        append(" last=$lastRead")
        if (lastHead.isNotEmpty()) append(" head=[$lastHead]")
        if (lastOem.isNotEmpty()) append(" oem=[$lastOem]")
    }

    /** The probe's `do=read`: one read now, of whatever is tracked. */
    fun readNow() = bg.post { if (taskId >= 0) read(taskId, gen) }

    /**
     * The app whose mini program [top] is, or null when it is not one.
     *
     * Asked of the rules rather than of the one class name this file used to carry:
     * `wx_mini_activity` and `ali_mini_activity` are the containers ColorOS follows, and 支付宝
     * spreads its mini programs over twenty of them (`XRiverActivity$App01` and its siblings),
     * so a name written here would be wrong on the other platform and stale on this one.
     */
    private fun miniHost(ctx: Context, top: ComponentName): String? {
        val policy = runCatching { PickupRules.get(ctx).recognitionPolicy() }.getOrNull() ?: return null
        return if (policy.isMiniProgramActivity(top.packageName, top.className)) top.packageName else null
    }

    /**
     * The back screen, or null on a phone that has none.
     *
     * Asked before the rear card is built so that a phone without the second display posts nothing
     * for it, and asked here rather than trusted from the rear card's own fallback, which exists
     * only so its arithmetic cannot divide by a null.
     */
    private fun rearDisplay(c: Context): android.view.Display? = runCatching {
        c.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(REAR_DISPLAY)
    }.getOrNull()

    /**
     * The mini program a page belongs to, out of the page's own launch intent: the appId, and the
     * name it was searched for when it was reached from a search. Null when the page carries
     * neither - a page in one of the same containers that is not a mini program, or one whose
     * intent could not be read.
     */
    private class Program(val appId: String, val name: String, val scheme: String = "")

    /**
     * 支付宝 puts the appId in a nested `startParams` bundle, beside the query and the
     * `alipays://platformapi/startapp?appId=…` scheme it was opened with (measured 2026-10-09:
     * `appId = 2021002163601771`, `globalSearchQuery = 蜜雪冰城`).
     *
     * Read as a field and never as a regex over the string extras, which is the fallback ColorOS
     * has (`"appId"\s*:\s*"(\d{8,20})"`): the renderer's own `__config__ta_render_anr_cfg` in this
     * very bundle carries `"appId":"2021001178689171,…"`, a list of *other* mini programs, and a
     * regex finds that first. (ColorOS's own readers take `mExtras.appInfo.appId`; 支付宝's pages
     * have not shown that shape, so it is looked for second rather than relied on.)
     */
    private fun programOf(extras: Bundle?): Program? {
        if (extras == null) return null
        val start = runCatching { extras.getBundle("startParams") }.getOrNull()
        val info = runCatching { extras.getBundle("mExtras")?.getBundle("appInfo") }.getOrNull()
        val appId = text(start, "appId").ifEmpty { text(info, "appId") }
        if (appId.matches(NUMERIC_ID)) {
            // `ap_framework_scheme` is the `alipays://platformapi/startapp?appId=…` the system was
            // handed when this page was opened, and it is what a tap on the island uses to come
            // back to it (see [openNow]).
            return Program(appId, text(start, "globalSearchQuery"),
                text(start, "ap_framework_scheme"))
        }
        // 微信 has no such bundle, and ColorOS's own reader for it (`Gleaner`'s `yc/k`) does not
        // look for one: it walks the extras for a string shaped like 微信's appId. Reading every
        // string is safe here in a way it was not for 支付宝's numeric ids, where the renderer's
        // own config carries a list of *other* mini programs' ids in the same bundle - a `wx`
        // followed by sixteen hex digits is a shape nothing else in an intent has.
        wechatId(extras)?.let { return Program(it, "") }
        return null
    }

    /** 微信's appId in a page's extras, one level of nested bundles deep. */
    private fun wechatId(extras: Bundle, depth: Int = 0): String? {
        val keys = runCatching { extras.keySet() }.getOrNull() ?: return null
        for (key in keys) {
            val id = text(extras, key).lowercase()
            if (id.matches(WX_ID)) return id
        }
        if (depth >= 1) return null
        for (key in keys) {
            val nested = runCatching { extras.getBundle(key) }.getOrNull() ?: continue
            wechatId(nested, depth + 1)?.let { return it }
        }
        return null
    }

    private val NUMERIC_ID = Regex("\\d{8,20}")
    private val WX_ID = Regex("wx[a-f0-9]{16}")

    private fun text(from: Bundle?, key: String): String =
        runCatching { from?.getString(key) }.getOrNull().orEmpty()

    /**
     * [top] is MiuiTopActivityObserver's, which can be ahead of the task list: the task it names
     * may not be listed yet, or not carry its label yet (微信 sets a mini program's label once
     * the mini program is up). Looked for again then, [RETRIES] times.
     */
    private fun front(top: ComponentName?, retry: Int = 0) {
        if (retry == 0) {
            if (top == lastTop) return
            lastTop = top
        } else if (top != lastTop) {
            return
        }
        // No context is read as "not a mini program" rather than as a reason to stop looking: the
        // top must still be handed on, or a task being kept is never let go.
        val ctx = Main.appContext()
        val pkg = if (sOn && ctx != null && top != null) miniHost(ctx, top) else null
        if (ctx == null || top == null || pkg == null) {
            // A parked task must not stay on the hidden display once its own app is in front again:
            // the mini program tapped from its list would come back on a display nobody can see.
            if (top != null && top.packageName == host) PickupPark.releaseFrom("its app is in front again")
            left(top)
            return
        }
        @Suppress("DEPRECATION")
        val info = runCatching {
            ctx.getSystemService(ActivityManager::class.java).getRunningTasks(8)
                .firstOrNull { it.topActivity == top }
        }.getOrNull()
        val label = info?.taskDescription?.label.orEmpty()
        // Both platforms give a mini program a task of its own, and normally name it - but not
        // always: 支付宝 runs one inside the app's own task, which carries no description at all
        // (measured 2026-10-09, 蜜雪冰城's page sitting in task 44419), so on 支付宝 the task is
        // taken without a label and the page itself is asked for its appId on the first read.
        if (info == null || (label.isEmpty() && pkg != ALIPAY)) {
            if (retry < RETRIES) bg.postDelayed({ front(top, retry + 1) }, RETRY_GAP)
            else Xp.log(TAG + "no labelled task for ${top.shortClassName}")
            return
        }
        // The list still scopes 微信, whose pages are a whole app's - a chat or a search result can
        // name a brand without being one. 支付宝's scope is the appId its page carries, which is
        // part of the page rather than of the task, and is checked where the page is read.
        if (pkg == WECHAT && BRANDS.none { label.contains(it, ignoreCase = true) }) {
            Xp.d(TAG + "mini program not on the list: $label")
            stop()
            return
        }
        // Both platforms give a mini program a task of its own, so the same app can be tracking a
        // different one than it did before - which is why this is set before the task is compared.
        host = pkg
        if (info.taskId == taskId) {
            inFront = true
            return
        }
        stop()
        taskId = info.taskId
        brand = label
        reads = 0
        inFront = true
        val g = gen
        Xp.log(TAG + "tracking task $taskId ($label)")
        bg.postDelayed({ read(taskId, g) }, FIRST)
    }

    /**
     * The front activity is not the mini program, or not any more. Being in front is how a task is
     * found, not what it is read for: the app's own pages, and whatever comes over the mini program
     * while the user is still in it, arrive as a top that is not it - and with the top settling back
     * on the mini program no further event comes, so a read loop killed here never came back. A
     * second order in the same mini program stopped updating the island that way (2026-10-08: the
     * task was dropped and its reads froze at 7 while the user sat in the mini program). So the task
     * is kept and read on, slower; only [LEFT] spent without being in front ends it - the app's own
     * pages are worth being read over, the phone in a pocket is not.
     */
    private fun left(top: ComponentName?) {
        if (taskId < 0) {
            stop()
            return
        }
        if (!inFront) return
        inFront = false
        Xp.log(TAG + "left the mini program: top=" + (top?.flattenToShortString() ?: "none") +
            ", reading task $taskId on")
        // Whether the page is kept off-screen is the one thing about this path that the card cannot
        // show, and it decides whether the order is read at all from here on: unparked, the task is
        // let go after [LEFT] and the card freezes at whatever it last read - the state a drink that
        // was still being made then never leaves (2026-10-10).
        val parkable = top?.packageName != host && waiting() && observes() &&
            SystemClock.uptimeMillis() >= noParkUntil
        Xp.log(TAG + "left: park=" + parkable + " (waiting=" + waiting() + " observes=" + observes()
            + " noParkFor=" + ((noParkUntil - SystemClock.uptimeMillis()).coerceAtLeast(0) / 1000) + "s)")
        // Out of the app altogether: the order is worth waiting on, so the task goes onto the hidden
        // display where the mini program keeps its own page current (PickupPark, §6). Not while the
        // app itself is in front: a mini program started from its own list would come back on a
        // display nobody can see, and the app coming to the front releases it for the same reason.
        // Not right after the island was tapped either: the user is in the mini program, and the
        // front flickers through the launcher as they move about it - parking on that flicker took
        // the mini program away from them mid-use (2026-10-08). And not at all unless the rules ask
        // for it: `use_observer` is ColorOS's own switch for the polling this park is.
        if (parkable) {
            PickupPark.parkFrom(taskId)
            return
        }
        bg.postDelayed({ if (!inFront && taskId >= 0 && PickupPark.parked() != taskId) stop() }, LEFT)
    }

    /**
     * Whether the rules allow a page to be kept off-screen at all - ColorOS's `use_observer`,
     * 「启用帮看轮询服务刷新订单状态」. Their observeagent is that polling; the park here is the same
     * thing done with the framework's own hidden display, so it is what the key gates. Off, or
     * unreadable, the task is still read while it is in front and dropped when it is left.
     */
    private fun observes(): Boolean {
        val ctx = Main.appContext() ?: return false
        return runCatching { PickupRules.get(ctx).recognitionPolicy().useObserver }.getOrDefault(false)
    }

    /**
     * Whether the order being shown is one whose state is still worth waiting for - a code is up and
     * the order is not over. A park is what the wait costs, so it is not made for nothing.
     */
    private fun waiting(): Boolean =
        shownKey != null && !over(shownStatus)

    /** Whether an order that reads [s] has nothing left to wait for: ready, or over. */
    private fun over(s: String?): Boolean =
        s != null && (READY.any { s.contains(it) } || DONE.any { s.contains(it) })

    private fun battery(ctx: Context): Int =
        ctx.getSystemService(android.os.BatteryManager::class.java)
            ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    /**
     * A battery saver on, or a phone running hot. Neither is something ColorOS's observe config
     * has a key for - its two battery keys are in [PickupObserve] - and neither is a level to wait
     * out, so a read is skipped rather than slowed. ColorOS also skips while a game is being played
     * or the camera is open; neither is something this process can see for nothing, so neither is
     * here.
     */
    private fun tired(ctx: Context): Boolean {
        val pm = ctx.getSystemService(android.os.PowerManager::class.java) ?: return false
        return pm.isPowerSaveMode ||
            pm.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_MODERATE
    }

    private fun stop() {
        gen++
        taskId = -1
        inFront = false
        PickupPark.releaseFrom("the tracked task was let go")
        bg.removeCallbacksAndMessages(null)
    }

    private fun read(task: Int, g: Int) {
        if (g != gen || task != taskId) return
        if (++reads > MAX_READS) return
        val ctx = Main.appContext() ?: return
        val parked = PickupPark.parked() == task
        if (parked && parkedTask != task) {
            parkedTask = task
            parkedReads = 0
        }
        if (!parked) parkedTask = -1
        if (parked) {
            // §6.3's gates, in the order the doc has them: below 10% the wait is over, and a low
            // battery, a battery saver or a phone running hot skip a read. The park's own clock is
            // asked, not one kept here: the probe parks tasks too, and one of those would look like
            // a park that had already lasted [PARK_MAX].
            val level = battery(ctx)
            val since = PickupPark.sinceMs()
            when {
                since > 0 && SystemClock.uptimeMillis() - since > PARK_MAX -> {
                    Xp.log(TAG + "parked for ${PARK_MAX / 60_000} min")
                    PickupPark.releaseFrom("parked long enough")
                }
                level in 0..PickupObserve.intervals.skipPercent -> {
                    Xp.log(TAG + "battery $level%: the wait is over")
                    PickupPark.releaseFrom("battery $level%")
                }
                tired(ctx) -> {
                    next(task, g)
                    return
                }
            }
            parkedReads++
        }
        // Not while the screen is off or the phone is locked, parked or not: the page is not being
        // looked at, and a parked order waiting behind a locked screen is where these reads spent
        // most of their time. ColorOS skips them there too. The timer is left running, so the first
        // read after the phone is picked up is at most one interval away - the lock screen's island
        // shows the state as of the last read until then.
        val power = ctx.getSystemService(android.os.PowerManager::class.java)
        val keyguard = ctx.getSystemService(android.app.KeyguardManager::class.java)
        if (power?.isInteractive == false || keyguard?.isKeyguardLocked == true) {
            next(task, g)
            return
        }
        val t0 = SystemClock.uptimeMillis()
        val timeout = Runnable { if (g == gen) { lastRead = "timeout"; next(task, g) } }
        bg.postDelayed(timeout, TIMEOUT)
        val asked = request(task) { st, ex ->
            bg.post {
                bg.removeCallbacks(timeout)
                if (g != gen) return@post
                program = programOf(ex)
                // 支付宝's mini program pages carry their appId in their own launch intent; a page
                // in the same container without one is not a mini program page - an H5 or a service
                // page reached the same way, which is where reading every XRiverActivity would end
                // up. Asked here rather than up front because the appId is part of the page, not of
                // the task, and only exists once there is a page to read. A page whose text did
                // come back but whose appId did not is left alone rather than tracked on nothing.
                if (host == ALIPAY && st != null && program == null) {
                    Xp.d(TAG + "no appId on the page: not a mini program" +
                        if (ex == null) " (nothing came back with the structure)" else " (extras, but no appId)")
                    lastRead = "no appId"
                    stop()
                    return@post
                }
                val nodes = if (st != null) runCatching { nodes(st) }.getOrNull() else null
                val r = nodes?.let { parse(it) }
                val ms = SystemClock.uptimeMillis() - t0
                lastRead = when {
                    st == null -> "no structure (${ms}ms)"
                    nodes == null -> "unreadable (${ms}ms)"
                    r == null -> "no code in ${nodes.size} texts (${ms}ms)"
                    else -> "code found in ${nodes.size} texts (${ms}ms)"
                }
                if (r != null) {
                    lastHead = ""
                    Xp.d(TAG + "$brand: ${r.code} ${r.label} ${r.status} ${r.store}")
                    show(r, task)
                    // §6.6, and one state further: an order that is ready to collect is nothing to
                    // wait for any more, so the park ends there and not only at 已完成 - the wait is
                    // for the change, and the user is on their way to the counter.
                    if (over(r.status)) PickupPark.releaseFrom("the order reads ${r.status}")
                } else if (nodes != null) {
                    val front = nodes.maxOfOrNull { it.page } ?: -1
                    lastHead = "page $front: " + nodes.filter { it.page == front }.take(12)
                        .joinToString("|") { it.text.take(12) }
                }
                next(task, g)
            }
        }
        if (!asked) {
            bg.removeCallbacks(timeout)
            lastRead = "not asked"
            next(task, g)
        }
    }

    private fun next(task: Int, g: Int) {
        if (g != gen || task != taskId) return
        val gap = when {
            PickupPark.parked() == task -> parkedGap()
            reads <= QUICK -> QUICK_GAP
            inFront -> EVERY
            else -> SLOW
        }
        bg.postDelayed({ read(task, g) }, gap)
    }

    /** OPPO's intervals for an order being waited on: what it is doing decides how often to look. */
    private fun parkedGap(): Long {
        val cfg = PickupObserve.intervals
        val s = shownStatus
        if (s != null && READY.any { s.contains(it) }) return cfg.ready
        if (parkedReads <= 1) return cfg.firstAfter
        val gap = if (s != null && MAKING.any { s.contains(it) }) cfg.making else cfg.ordered
        val level = Main.appContext()?.let { battery(it) } ?: -1
        return if (level in 1 until cfg.lowBatteryPercent) maxOf(gap, cfg.lowBattery) else gap
    }

    /**
     * IActivityTaskManager.requestAssistDataForTask(receiver, taskId, callingPackage,
     * attributionTag, fetchStructure) - Android 17's five; the appop it notes is checked against
     * SystemUI's own package. The receiver is a bare Binder: IAssistDataReceiver's transaction 1
     * is onHandleAssistData(Bundle), 2 the screenshot, both oneway.
     */
    private fun request(task: Int, done: (AssistStructure?, Bundle?) -> Unit): Boolean {
        val receiver = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == 1) {
                    val b = runCatching {
                        data.enforceInterface("android.app.IAssistDataReceiver")
                        if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else null
                    }.getOrNull()
                    b?.classLoader = AssistStructure::class.java.classLoader
                    @Suppress("DEPRECATION")
                    val st = runCatching { b?.getParcelable<AssistStructure>("structure") }.getOrNull()
                    // The page's own launch intent rides along in `content`, and it is read apart
                    // from the structure and after it. 支付宝 puts a Parcelable of its own class in
                    // this same bundle (…fulllinktracker…SyncData, seen 2026-10-09) which does not
                    // unmarshal in SystemUI at all; one try around both would throw a page's text
                    // away for the sake of its intent, and the text is what the island is for.
                    val extras = runCatching {
                        @Suppress("DEPRECATION")
                        b?.getParcelable<android.app.assist.AssistContent>("content")?.intent?.extras
                    }.getOrNull()
                    done(st, extras)
                    return true
                }
                if (code == 2) return true
                return super.onTransact(code, data, reply, flags)
            }
        }
        receiver.attachInterface(null, "android.app.IAssistDataReceiver")
        return runCatching {
            val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
            val recv = Class.forName("android.app.IAssistDataReceiver")
            val proxy = Class.forName("android.app.IAssistDataReceiver\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, receiver)
            val m = atm.javaClass.methods.firstOrNull { it.name == "requestAssistDataForTask" }
                ?: error("no requestAssistDataForTask")
            val ok = when (m.parameterTypes.size) {
                5 -> m.invoke(atm, proxy, task, SYSUI, null, true)
                4 -> m.invoke(atm, proxy, task, SYSUI, null)
                else -> error("requestAssistDataForTask${m.parameterTypes.toList()}")
            }
            ok as? Boolean ?: true
        }.onFailure {
            Xp.log(TAG + "assist request failed: $it")
        }.getOrDefault(false)
    }

    /** The structure's texts in tree order, each with the WebView it is in. */
    private fun nodes(st: AssistStructure): List<PickupParse.Node> {
        val out = ArrayList<PickupParse.Node>()
        var pages = -1
        fun walk(n: AssistStructure.ViewNode, page: Int) {
            var p = page
            if (n.className?.endsWith("WebView") == true) p = ++pages
            n.text?.let { if (it.isNotBlank()) out.add(PickupParse.Node(it.toString(), p)) }
            for (i in 0 until n.childCount) walk(n.getChildAt(i), p)
        }
        for (i in 0 until st.windowNodeCount) walk(st.getWindowNodeAt(i).rootViewNode, -1)
        return out
    }

    /**
     * The page's code: ColorOS's own recognizer first, this file's reading of the page second.
     *
     * [PickupOem] is OPPO's plugin - the one ColorOS's 流体云 runs - and it answers for a page by
     * its rules; when the rules find nothing it would fall to its NER model, which is the part left
     * out of the bundle we ship (see [PickupOem]). So this is OPPO's first rung and none of its
     * second: a page the rules cannot read falls through to [PickupParse] exactly as before.
     *
     * The code it answers is taken whole. The label and the shop beside it still come from this
     * file's own reading, since the plugin has neither, and so does the order's wording: the plugin
     * says `completed`/`uncompleted`, which is its own vocabulary rather than the page's, and it is
     * the page's that [ready] and [over] read.
     */
    private fun parse(nodes: List<PickupParse.Node>): PickupParse.Result? {
        val fallback = PickupParse.parse(nodes)
        val content = nodes.joinToString("\n") { it.text }
        val texts = nodes.map { it.text }
        brandOf(content).let { (rule, name) ->
            oemRule = rule
            brand = name
        }
        val out = PickupOem.recognize(texts, oemRule?.tagAppName?.takeIf { it.isNotEmpty() } ?: OEM_APP, "")
        if (out == null) {
            lastOem = "off"
            return fallback
        }
        // Said whether or not a code came out, so the probe can tell "did not run" from "ran and
        // found nothing" from "answered" - three different things to go and fix.
        lastOem = (out.code.ifEmpty { "none" }) + " ${out.status} pt=${out.processType}" +
            " page=${out.orderPage} n=${nodes.size}" +
            (if (out.product.isEmpty()) "" else " ${out.product}") +
            (if (out.orderTime.isEmpty()) "" else " ${out.orderTime}") +
            (if (out.temperature.isEmpty()) "" else " ${out.temperature}")
        if (out.code.isEmpty()) return fallback
        return PickupParse.Result(
            code = out.code,
            label = fallback?.label?.takeIf { it.isNotEmpty() } ?: "取餐码",
            status = fallback?.status,
            store = fallback?.store,
            product = out.product.ifEmpty { null },
            oemStatus = out.status.ifEmpty { null },
            temperature = out.temperature.ifEmpty { null },
        )
    }

    /**
     * The brand of the page being read: its entry in ColorOS's rules, and what to call it.
     *
     * Four ways in, most exact first:
     *  1. the appId the page's own launch intent carries, matched against a rule's `origin_id` -
     *     which is how 支付宝's entries in the config are keyed, and needs no guess;
     *  2. the task label, scored together with the page's text ([PickupRules.matchPage]) - how the
     *     微信 brands are found, and still the better answer there because the text says what the
     *     label cannot;
     *  3. the name, searched by [PickupRules.matchBrand] across **both** platforms, because a brand
     *     is one company whichever app it is reached through: 蜜雪冰城's rule is written for 微信
     *     and names the same logo, the same drink and the same red, so it draws its 支付宝 card too;
     *  4. nothing, and the card is drawn from the order's own drink ([PickupArt.model]).
     *
     * The name is a heuristic - it is the query the page was opened from, so it is the brand in the
     * common case and something else when it is not - and its failure is harmless: 「奶茶」 finds no
     * rule anywhere and falls to 4. Identity is always the appId; the name only chooses artwork.
     */
    private fun brandOf(content: String): Pair<PickupRule?, String> {
        val rules = Main.appContext()?.let { runCatching { PickupRules.get(it) }.getOrNull() }
        val appId = program?.appId.orEmpty()
        if (appId.isNotEmpty()) {
            rules?.ruleForOrigin(host, appId)?.let { return it to brand.ifEmpty { it.label } }
            // 微信's appId is not what the rules are keyed by - they use the `gh_…` original id -
            // and the cloud's applet list is the only thing here carrying one for the other. That
            // is half of what fetching it is for; the other half is its `disabled` flag.
            if (host == WECHAT) {
                val origin = PickupCloud.originOf(appId)
                if (origin != null) {
                    rules?.ruleForOrigin(WECHAT, origin)
                        ?.let { return it to brand.ifEmpty { it.label } }
                }
            }
        }
        if (brand.isNotEmpty()) {
            rules?.matchPage(host, brand, content)?.let { return it to brand.ifEmpty { it.label } }
        }
        val name = program?.name?.takeIf { it.isNotEmpty() } ?: brand
        if (name.isNotEmpty()) {
            rules?.matchBrand(name)?.let { return it to brand.ifEmpty { it.label } }
        }
        // Nothing corroborated the name, so nothing is claimed: the task label still stands - it is
        // the mini program's own name, which is what 微信 puts there - but a search query that
        // matched no rule is not shown as a brand.
        return null to brand
    }

    // ---------------------------------------------------------------- the island

    private fun show(r: PickupParse.Result, task: Int) {
        val key = "$brand|${r.code}|${r.status}|${r.store}"
        val now = SystemClock.elapsedRealtime()
        if (muted == "$brand|${r.code}" && now < mutedUntil) return
        val ctx = Main.appContext() ?: return
        // The notification goes by itself [LIFE] after it was posted, and the island with it; nothing
        // on that path tells us (a timeout is not a dismissal, so no delete intent, and [takeDown] is
        // only for the switch). So ask the system: with the island gone, shownKey is not evidence of
        // anything, and what comes next is a fresh code as far as the island is concerned.
        if (shownKey != null && up(ctx) == false) clear()
        if (key == shownKey) return
        // A new code floats the island open; so does the order turning ready to collect. A state
        // changing again while it is already ready only updates, and so does the shop.
        val sameCode = shownKey?.startsWith("$brand|${r.code}|") == true
        val fresh = !sameCode || ready(r.status) && !ready(shownStatus)
        cardAlpha = if (fresh) FADE_FROM else 1f
        runCatching { post(ctx, r, task, fresh) }
            .onSuccess {
                shownKey = key
                shownStatus = r.status
                shownTask = task
                if (fresh) fade(ctx, r, task, gen)
            }
            .onFailure {
                Xp.log(TAG + "not posted: $it")
                cardAlpha = 1f
            }
    }

    /**
     * The card's pictures folding in.
     *
     * A RemoteViews cannot animate - there is no animator to hand it, and nothing in the island's
     * renderer will run one - so the only fade available is to post the notification again with
     * the pictures drawn more opaque, and let each post be a frame. Four steps over ~300ms, silent
     * (the tag is unchanged, so it is an update of the same island and not a new one), and only
     * for a code that is being floated up; a steady state re-read leaves the card alone.
     */
    private fun fade(c: Context, r: PickupParse.Result, task: Int, g: Int) {
        if (!sCard) return
        val steps = floatArrayOf(0.3f, 0.6f, 0.85f, 1f)
        steps.forEachIndexed { i, a ->
            bg.postDelayed({
                if (g != gen) return@postDelayed
                cardAlpha = a
                runCatching { post(c, r, task, false) }
                    .onFailure { Xp.log(TAG + "fade step $i not posted: $it") }
            }, 80L * (i + 1))
        }
    }

    private fun ready(status: String?): Boolean = status != null && READY.any { status.contains(it) }

    /** A colour out of the rules XML as `#RRGGBB`, or the fallback when it cannot be read. */
    private fun colour(value: String?, fallback: String): String = runCatching {
        val parsed = android.graphics.Color.parseColor(value?.takeIf { it.isNotEmpty() } ?: fallback)
        String.format("#%06X", 0xFFFFFF and parsed)
    }.getOrDefault(fallback)

    /**
     * ColorOS's verdict on the order, said in the page's vocabulary - its own words are
     * completed/uncompleted/waiting, which mean nothing to a reader. This is the fallback, not the
     * first choice: see [post], and 麦当劳 below.
     *
     * Its three values are coarser than the page's own words and its default is the middle one -
     * the recognizer sets `orderStatus = uncompleted` when it starts and only moves it to
     * `completed` on a marker it knows - so `uncompleted` means "not read as finished" rather than
     * "being made". Shown as 「制作中」 that turned a page reading 「已准备完毕」 into an order still
     * in the kitchen (2026-10-09, 支付宝's 麦当劳 order).
     */
    private fun oemTitle(r: PickupParse.Result): String? = when (r.oemStatus) {
        "completed" -> "已完成"
        "uncompleted" -> "制作中"
        "waiting" -> "等待出码"
        else -> null
    }

    /** Nothing is up: forget what the island was showing. */
    private fun clear() {
        shownKey = null
        shownStatus = null
        shownTag = null
    }

    /**
     * Whether our own notification is still posted, the island going when it goes. Only this
     * process's notifications are listed, and only ours is on [CHANNEL] - matched on that rather
     * than on an id, which changes with every fresh code ([ID]). null is the system not saying -
     * not an answer, so the caller keeps what it had; reading a failure as "gone" would float the
     * island open again on every read.
     */
    private fun up(c: Context): Boolean? = runCatching {
        c.getSystemService(NotificationManager::class.java)?.activeNotifications
            ?.any { it.notification.channelId == CHANNEL }
    }.getOrNull()

    /**
     * Every card of ours cancelled.
     *
     * The back screen's widget for a card is removed when that card's *notification* is removed, and
     * each fresh code goes out under an id of its own ([ID]) - so the one before has to be taken
     * down by hand, or its widget stays on the back screen beside the new one. Matched on the
     * channel rather than on [shownId], which is also what catches a card left posted by an earlier
     * run of this process.
     */
    private fun sweep(nm: NotificationManager) {
        runCatching {
            nm.activeNotifications.filter { it.notification.channelId == CHANNEL }
                .forEach { nm.cancel(it.tag, it.id) }
        }
    }

    private fun takeDown(why: String) {
        if (shownKey == null) return
        clear()
        Main.appContext()?.getSystemService(NotificationManager::class.java)?.let { sweep(it) }
        Xp.log(TAG + "taken down: $why")
    }

    /**
     * The tap's receiver, in place as soon as this process has a Context.
     *
     * Registering it on the way out of a [post] is not enough, and the case it misses is the one
     * the tap exists for: a SystemUI restart leaves the last card on the back screen while this
     * process is tracking an order it has not posted anything for, so nothing reaches [post] - and
     * the tap on that card was then a broadcast with no receiver behind it, which is to say the
     * button did nothing at all (2026-10-10, after a restart).
     */
    fun wake(ctx: Context) {
        receivers(ctx)
    }

    private fun receivers(ctx: Context) {
        if (receivers) return
        receivers = true
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    ACTION_OPEN -> bg.post { open(c, i) }
                    ACTION_GONE -> bg.post {
                        val k = shownKey ?: return@post
                        muted = k.split('|').take(2).joinToString("|")
                        mutedUntil = SystemClock.elapsedRealtime() + LIFE
                        clear()
                        PickupPark.releaseFrom("swiped away")
                        Xp.log(TAG + "swiped away")
                    }
                }
            }
        }
        ctx.registerReceiver(r, IntentFilter().apply {
            addAction(ACTION_OPEN)
            addAction(ACTION_GONE)
        }, Context.RECEIVER_NOT_EXPORTED)
    }

    /**
     * The mini program's task back in front, over its own app's task so that Back from it lands in
     * the app, and the app itself if the task is gone.
     *
     * The app's task is moved forward first, and the mini program's without [MOVE_TASK_WITH_HOME]:
     * that flag brings the task's home along, and the home of an appbrand task is the launcher -
     * tapping the island opened the mini program over the desktop, so Back took the user out of 微信
     * altogether (2026-10-08, the top the module saw right after a tap was com.miui.home/.launcher).
     */
    private fun open(c: Context, i: Intent) {
        // Tapping the island is the user going into the mini program: nothing is parked again for
        // [NO_PARK] while they are in there. See [left] - the front flickers through the launcher as
        // they move about it, and parking on that flicker takes it away from them mid-use.
        noParkUntil = SystemClock.uptimeMillis() + NO_PARK
        // Where the tap should go, in order of how much it is *this* card's: the task the card was
        // posted for, then the task this process is tracking, then nothing.
        //
        // The first is the faithful one, but it is not always still there: a SystemUI restart takes
        // the parked mini program's task with it and the app makes another for the same page
        // (measured 2026-10-10 - 44999 before the restart, 45007 after), while the card that is up
        // is the older one, and tapping it has to land somewhere. It did not: the only task left to
        // try was the one from the card, so the button fell through to opening 微信 itself.
        val tasks = listOf(i.getIntExtra(EXTRA_TASK, -1), shownTask, taskId)
            .filter { it >= 0 }.distinct()
        val pkg = i.getStringExtra(EXTRA_HOST)?.takeIf { it.isNotEmpty() } ?: host
        val scheme = i.getStringExtra(EXTRA_SCHEME).orEmpty().ifEmpty {
            if (pkg == ALIPAY) program?.scheme.orEmpty() else ""
        }
        // The one path in this file with nothing else to go on when it does not work.
        Xp.log(TAG + "tap: pkg=$pkg tasks=$tasks scheme=${scheme.isNotEmpty()} known=${shownKey != null}")
        if (PickupPark.parked() >= 0) {
            // §6.5: the task goes back to the display the user can see before it is brought to the
            // front - moved to the front while parked, it would come up on the hidden one.
            PickupPark.releaseFrom("the island was tapped") { openNow(c, tasks, pkg, scheme) }
        } else {
            openNow(c, tasks, pkg, scheme)
        }
    }

    private fun openNow(c: Context, tasks: List<Int>, pkg: String, scheme: String) {
        // 支付宝 runs a mini program inside its own app's task, and by the time the island is
        // tapped that task is usually not sitting on the mini program any more - so moving the
        // task to the front lands on whatever is, which is the app's own home (measured 2026-10-09:
        // tapping the order button on a 蜜雪冰城 code opened 支付宝 itself). The page's own launch
        // url has no such problem: it names the mini program, and asking for it again re-opens that
        // page - warm, because 支付宝 keeps the mini program alive.
        if (scheme.isNotEmpty() && startScheme(c, scheme, pkg)) {
            collapse(c)
            return
        }
        val am = c.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        val running = runCatching { am.getRunningTasks(64) }.getOrDefault(emptyList())
        // The first of the candidates that is still running, and is moved: the app's own task first,
        // then the mini program's, so that Back lands in the app rather than out of it.
        var tried = 0
        val moved = tasks.any { target ->
            if (running.none { it.taskId == target }) return@any false
            tried++
            runCatching {
                running.firstOrNull { it.taskId != target && it.baseActivity?.packageName == pkg }
                    ?.let { am.moveTaskToFront(it.taskId, 0) }
                am.moveTaskToFront(target, 0)
            }.isSuccess
        }
        if (!moved) {
            Xp.log(TAG + "tap: none of $tasks running for $pkg (tried $tried), opening the app itself")
            runCatching {
                c.packageManager.getLaunchIntentForPackage(pkg)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { c.startActivity(it) }
            }.onFailure { Xp.log(TAG + "$pkg not opened: $it") }
        }
        collapse(c)
    }

    /**
     * The island back down to its capsule, which the system does not do for this tap on its own.
     *
     * 小爱's card is a RemoteViews and the plugin watches its clicks
     * (`FocusNotifPreHandler.handleRemoteViewClick`) - but only collapses when the PendingIntent is
     * an *Activity* one, and this one cannot be: the tap has to run this module's own code (bring
     * the mini program's task forward, or hand 支付宝 back its page's launch url), and that needs a
     * broadcast. A broadcast PendingIntent only opens the app, so the island stayed expanded over
     * it (2026-10-09). The broadcast sent here is the very one the plugin's own Activity branch
     * sends.
     *
     * Nothing else collapses it: the `expandedTime` timer returns early once the user has expanded
     * the island by hand (`DynamicIslandSafeguardsController.delayCollapsed$lambda$3`), and a
     * re-post of the same notification reuses the island's view with its expanded state intact.
     */
    private fun collapse(c: Context) {
        runCatching { c.sendBroadcast(Intent(ACTION_COLLAPSE_ISLAND)) }
            .onFailure { Xp.log(TAG + "island not collapsed: $it") }
    }

    /**
     * The page's own launch url, handed back to the app that gave it.
     *
     * [pkg] is the app the card was posted for, not [host]: after a SystemUI restart the field is
     * back at its default, and naming the wrong package here fails the launch silently.
     */
    private fun startScheme(c: Context, scheme: String, pkg: String): Boolean = runCatching {
        c.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(scheme))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Named, so nothing else is offered and nothing needs to be visible to this process.
            .setPackage(pkg))
    }.onFailure { Xp.log(TAG + "scheme not opened: $it") }.isSuccess

    @android.annotation.SuppressLint("NotificationPermission")
    private fun post(c: Context, r: PickupParse.Result, task: Int, fresh: Boolean) {
        receivers(c) // already on at startup; this is for the path where that had no Context yet
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "取餐码",
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
        val pic = icon(c, task)
        // ColorOS's recognizer names the drink; this file's reading of the page names the shop. The
        // second line leads with the drink, the way ColorOS's own card does, and falls back to the
        // shop for the brands whose pages carry no product name (蜜雪冰城's, for one).
        // The page's own words first, the recognizer's verdict only where the page has none: its
        // three values cannot say 「已准备完毕」, and its middle one is what it defaults to when it
        // read nothing it knows (see [oemTitle]). Both mean the same thing to a reader - what the
        // order is doing - so the page, which has the finer vocabulary, is the one to hear.
        val title = r.status ?: oemTitle(r) ?: r.label
        // 「麦当劳示例广场餐厅」 under 麦当劳 is 「示例广场餐厅」.
        val store = r.store?.removePrefix(brand)?.trimStart('（', '(', ' ')?.ifEmpty { null }
        // The rules' own `<show_meal_name>`, which says whether the meal's name is shown at all;
        // off, the shop stands in its place - the same thing the card already does for the brands
        // whose pages carry no product name. Every rule for a page this module reads sets it true
        // today (the single exception, 肯德基's own app, is a path this module does not read).
        val meal = if (oemRule?.showMealName == false) null else r.product?.ifEmpty { null }
        val detail = meal ?: store
        val content = listOfNotNull(brand, detail).joinToString(" · ")
        // The animated drink picture, as an Icon for the param's own icon slot: an Icon is
        // resolved and drawn by the system, which is the only route by which an animated WebP can
        // play here - a RemoteViews ImageView never plays one, however it is handed over.
        // The brand's logo, for the slot the system draws itself (the island's icon and the lock
        // screen's capsule); the card's own picture is the drink, and that one is set by the card.
        val modelIcon = PickupCard.logoIcon(oemRule)
        // The island, in the shape the system's own renderer reads. Everything here comes out of
        // the island plugin (`miui.systemui.dynamicisland`): `IslandTemplateFactory.chooseModule`
        // picks the left area by `imageTextInfoLeft.type` (1 is the picture-and-text module, 5 the
        // icon-and-fixed-width-digit one, anything else throws IslandParamsException), and the right
        // area by `imageTextInfoRight.type` when there is one - which is why there is not, here; it
        // would win over `textInfo`.
        //   param_island { islandProperty, islandPriority, highlightColor,
        //                  bigIslandArea { imageTextInfoLeft {type,picInfo,textInfo{title,showHighlightColor}},
        //                                  textInfo {title,showHighlightColor} },
        //                  smallIslandArea { picInfo {type,pic} } }
        // The two halves say different things: the left is ours - our picture and the order's state,
        // which is the text that changes as the order moves (show() keys the post on the status) -
        // and the right is the official one, the code itself in the brand's colour. `highlightColor`
        // is where that colour comes from (`IslandTextViewHolder` paints `textInfo` with it when
        // `showHighlightColor` is true, at 80% alpha); without it the code reads white.
        // `smallIslandArea` stays wrapped in a `picInfo`, as 小爱's own notifications write it and as
        // the plugin's model (`SmallIslandArea` has `picInfo`/`combinePicInfo` and nothing else)
        // reads it. Flat `{type,pic}` parses to nothing and leaves the small island without a picture.
        val accent = colour(oemRule?.pickupColor, "#FFFFFF")
        val island = JSONObject()
            .put("islandProperty", 1)
            .put("islandPriority", 2)
            .put("highlightColor", accent)
            .put("bigIslandArea", JSONObject()
                .put("imageTextInfoLeft", JSONObject()
                    .put("type", 1)
                    .put("picInfo", JSONObject().put("type", 1).put("pic", PIC))
                    .put("textInfo", JSONObject()
                        .put("title", title)
                        .put("showHighlightColor", false)))
                .put("textInfo", JSONObject()
                    .put("title", r.code)
                    .put("showHighlightColor", true)))
            .put("smallIslandArea", JSONObject()
                .put("picInfo", JSONObject().put("type", 1).put("pic", PIC)))
        val param = JSONObject()
            .put("protocol", 1)
            .put("business", BUSINESS)
            .put("scene", "template_v2")
            .put("ticker", "${r.label} ${r.code}")
            .put("tickerPic", PIC)
            .put("aodTitle", "${r.label} ${r.code}")
            .put("aodPic", PIC)
            .put("enableFloat", fresh)
            // The island's own float, a second gate: FocusNotifPreHandler writes it as
            // miui.island.firstFloat from this key and defaults it to false when the key is missing,
            // and FocusNotificationController floats only when it is true. Left out, an update never
            // brings the island up - a new code in the same notification (id 1241 is one key) showed
            // in the shade alone. 地铁乘车码's card sets the same key the same way.
            .put("islandFirstFloat", fresh)
            .put("updatable", true)
            // No `outEffectSrc`. It is the ring of light around the drawn card - the plugin copies
            // it to `miui.effect.src` (`TemplateFactoryV3`) and SystemUI's glow layer draws it - and
            // AICR sets `outer_glow` when it posts. The pickup card does not have it: 小爱's own
            // focus notification carries no effect at all, and with this key set the card came up
            // inside an orange-and-blue halo that the official one never has.
            .put("param_island", island)
            // ColorOS animates the drink picture through this slot; the reference implementation
            // does the same, with the Icon under PIC_MODEL in miui.focus.pics and `autoplay` set.
            .put("iconTextInfo", JSONObject()
                .put("title", r.code)
                .put("content", detail)
                .put("subContent", title)
                .put("animIconInfo", JSONObject()
                    .put("type", if (modelIcon != null) 3 else 0)
                    .put("src", PIC_MODEL)
                    .put("autoplay", true)
                    .put("loop", false)
                    .put("number", 0)))
            .put("title", r.code)
            .put("content", content)
            .put("baseInfo", JSONObject()
                .put("type", 2)
                .put("title", r.code)
                .put("content", content)
                .put("subContent", title))
            .put("picInfo", JSONObject().put("type", 1).put("pic", PIC))
        // The always-on picture is the drink's own still where ColorOS ships one for this brand, and
        // the logo where it does not - the same file the card's left half is drawn from. Set here,
        // before the param is written out: the notification carries a string, not this object.
        val aod = PickupArt.aod(oemRule, PickupArt.model(oemRule, brand, r.product, r.temperature))
            ?.let { PickupArt.bitmap(it, 200) }
        if (aod != null) param.put("aodPic", PIC_AOD)
        val extras = Bundle()
        extras.putString("miui.focus.param", JSONObject().put("param_v2", param).toString())
        // And under `custom`, which is the key the island reads once the notification carries a
        // card - and here the object has to go in **unwrapped**. The plugin picks the key by
        // whether there is a custom view and unwraps `param_v2` on only one of the two paths
        // (`DynamicIslandUtils.resolveFocusParam`):
        //     string = extras.getString(hasCustomFocusView(sbn) ? "…param.custom" : "…param")
        //     return (hasCustomFocusView || obj == null || obj.optJSONObject("param_v2") == null)
        //            ? obj : obj.optJSONObject("param_v2")
        // Without a card it unwraps for us, which is why the wrapped form worked while the card was
        // off. With a card it hands back exactly what was written, and every reader downstream
        // (`FocusNotifUtils`, `FocusNotifPreHandler`, `TemplateFactoryV3`) then looks for
        // `param_island` on what it was given. A wrapped string puts `param_island` one level too
        // deep: the island loses both halves and falls back to the notification's own app icon.
        // This is also how the reference implementation writes it - AICR's `jd3.b` puts
        // `param_island` straight into `.custom` and never writes `miui.focus.param` at all.
        extras.putString("miui.focus.param.custom", param.toString())
        extras.putBundle("miui.focus.pics", Bundle().apply {
            putParcelable(PIC, Icon.createWithBitmap(pic))
            if (aod != null) putParcelable(PIC_AOD, Icon.createWithBitmap(aod))
            if (modelIcon != null) putParcelable(PIC_MODEL, modelIcon)
        })
        // The tap carries the task it is about, its app, and (for 支付宝) the mini program's launch
        // url, so that it still knows where to go after a SystemUI restart has emptied this process
        // of everything it read - see [EXTRA_TASK]. FLAG_UPDATE_CURRENT keeps them current: the same
        // PendingIntent goes out with every card, and the one in the notification is the last card's.
        val tap = PendingIntent.getBroadcast(c, ID, Intent(ACTION_OPEN).setPackage(SYSUI)
            .putExtra(EXTRA_TASK, task)
            .putExtra(EXTRA_HOST, host)
            .putExtra(EXTRA_SCHEME, if (host == ALIPAY) program?.scheme else null),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val gone = PendingIntent.getBroadcast(c, ID + 1, Intent(ACTION_GONE).setPackage(SYSUI),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(Icon.createWithBitmap(pic))
            .setContentTitle("${r.label} ${r.code}")
            .setContentText(listOf(content, title).filter { it.isNotEmpty() }.joinToString(" · "))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(!fresh)
            .setShowWhen(false)
            .setAutoCancel(false)
            .setTimeoutAfter(LIFE)
            .setDeleteIntent(gone)
            .setContentIntent(tap)
            .addExtras(extras)
            .build()
        // The card ColorOS draws, in place of the focus template the system draws. Attached only
        // when it was actually built: a RemoteViews that fails to inflate takes the whole island
        // with it, and a card with a missing layout is worse than the template.
        if (sCard) {
            runCatching {
                PickupCard.build(c, oemRule, r.code, meal, r.temperature, store, brand, tap,
                    cardAlpha, false) to
                    PickupCard.build(c, oemRule, r.code, meal, r.temperature, store, brand, tap,
                        cardAlpha, true)
            }.onSuccess { (day, night) ->
                // Every key Xiaomi's own focus notification sets, which is what this copies (the
                // assistant's card builder, `jd3.b`): the card in both themes, the always-on form,
                // and the island's expanded form.
                //
                // `miui.focus.rv.deco.*` and `miui.focus.rv.tiny*` are deliberately NOT here: that
                // same code sets those only when `MiuiMultiDisplayTypeInfo.isFlipDevice` is true -
                // they are the flip's cover-screen and its decorated window. On a normal phone the
                // card that comes up when the island is tapped is `miui.focus.rv`, which is why
                // posting only the deco keys left it looking like the plain template.
                n.extras.putParcelable("miui.focus.rv", day)
                n.extras.putParcelable("miui.focus.rvNight", night)
                n.extras.putParcelable("miui.focus.rv.fullAod", night)
                n.extras.putParcelable("miui.focus.rv.island.expand", night)
            }.onFailure { Xp.log(TAG + "card not built: $it") }
        }
        // The back screen, if this phone has one (2026-10-09). Xiaomi's own pickup notification
        // carries exactly these keys and nothing else does: `miui.rear.rv` is the card the back
        // screen draws - our card, in the shape that screen's own card is cut to, so it brings our
        // drink and its animation with it - and `miui.rear.param` is what names the business the
        // back screen's allow-list is keyed on. 小爱's own notification (com.miui.voiceassist,
        // business `memory`) is the shape being copied here.
        //
        // Its own card rather than the island's: the two screens are not the same shape, and the
        // one above is drawn by SystemUI around the island while this one is the whole card.
        if (rearDisplay(c) != null) {
            runCatching {
                PickupCard.rear(c, oemRule, r.code, meal, r.temperature, store, brand, tap)
            }.onSuccess { card ->
                // Both keys get the same card: the rear one is dark to begin with, and the screen
                // it goes on is the one that is always at least a little dark.
                n.extras.putParcelable("miui.rear.rv", card)
                n.extras.putParcelable("miui.rear.rvAOD", card)
                n.extras.putString("miui.rear.param", JSONObject()
                    .put("rear_param_v1", JSONObject()
                        // `memory`, which is what 小爱's own pickup notification calls itself, and
                        // not `pickup_code`: the back screen builds a widget from a *business it
                        // knows* (its list is the whitelist's - memory, foodDelivery, music, ...),
                        // and one it does not know gets no widget at all, RemoteViews or no. The
                        // card under it is still ours - `miui.rear.rv` above is what it draws.
                        .put("business", REAR_BUSINESS)
                        .put("index", 1))
                    .toString())
            }.onFailure { Xp.log(TAG + "rear card not built: $it") }
        }
        // A post that must float the island gets a notification key of its own. MIUI keeps per-key
        // state for a focus notification (FocusNotificationController's hasEverExpandedKeys and its
        // island-data map), and with one fixed key - no tag, the same for every order - the island
        // came up for a key once and never again after it had gone: a second code, or one re-read
        // after the user tapped the island away, was left in the shade alone while the module kept
        // posting. A tag makes that a new key, and the one left behind is dropped first (two focus
        // notifications at once would be two islands). A quiet update keeps the key it was posted
        // under, so it stays one island being updated.
        //
        // The same rule carries the *id*, which the back screen's widget is keyed on: a fresh code
        // is a new id and so a new widget there, which is the only post that screen slides out by
        // itself - see [ID]. The card before it is swept first, since its widget only goes when its
        // own notification does.
        val tag = if (fresh) "mc-" + SystemClock.elapsedRealtime() else shownTag
        val id = if (fresh) ID + (++ids % IDS) else shownId
        runCatching {
            if (fresh) sweep(nm)
            nm.notify(tag, id, n)
        }.onSuccess {
            shownTag = tag
            shownId = id
        }
        Xp.log(TAG + (if (fresh) "up: " else "updated: ") + brand + " (tag=${tag ?: "-"})")
    }

    /**
     * The mini program's own icon, which its task carries (TaskDescription: in memory, or the file
     * the system keeps it in); the brand's logo when the task has none; and the app's own icon only
     * when neither is there.
     *
     * 支付宝 runs a mini program inside the app's own task, and that description carries no icon at
     * all - so the app's own mark is what the small island and the lock screen's capsule showed for
     * a 支付宝 order: 支付宝's logo on 蜜雪冰城's pickup (2026-10-09). By then the brand is known,
     * and its logo is the picture ColorOS puts in that slot too.
     */
    private fun icon(c: Context, task: Int): Bitmap {
        val fromTask = runCatching {
            @Suppress("DEPRECATION")
            val td = c.getSystemService(ActivityManager::class.java).getRunningTasks(64)
                .firstOrNull { it.taskId == task }?.taskDescription ?: return@runCatching null
            val cls = td.javaClass
            (cls.getMethod("getInMemoryIcon").invoke(td) as? Bitmap) ?: run {
                val file = cls.getMethod("getIconFilename").invoke(td) as? String ?: return@run null
                cls.getMethod("loadTaskDescriptionIcon", String::class.java, Int::class.java)
                    // UserHandle's hash is its user id.
                    .invoke(null, file, android.os.Process.myUserHandle().hashCode()) as? Bitmap
            }
        }.onFailure {
            if (!iconSaid) { iconSaid = true; Xp.log(TAG + "task icon: $it") }
        }.getOrNull()
        if (fromTask != null) return fromTask
        val logo = PickupArt.logo(oemRule)?.let { PickupArt.bitmap(it, 144) }
        if (logo != null) {
            if (!iconSaid) { iconSaid = true; Xp.log(TAG + "task icon: none, the brand's logo instead") }
            return logo
        }
        if (!iconSaid) { iconSaid = true; Xp.log(TAG + "task icon: none, $host's instead") }
        val d = c.packageManager.getApplicationIcon(host)
        val size = 144
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            d.setBounds(0, 0, size, size)
            d.draw(Canvas(it))
        }
    }
}

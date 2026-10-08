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
 * A 微信 order page's pickup code, as a focus island - ColorOS's 取餐码 (Gleaner's observeagent),
 * the part of it that needs no background display: the code is read while the page is open.
 *
 * ColorOS reads the page with ViewExtract, its own framework code inside 微信's process. Here it
 * is the assist structure, asked for from SystemUI (which holds GET_TOP_ACTIVITY_INFO) by task:
 * IActivityTaskManager.requestAssistDataForTask. A mini program is an XWeb WebView, and its DOM
 * text comes back in the structure - 755 and 823 nodes on two real order pages, 5-17 ms - with
 * nothing hooked in 微信 and no accessibility service. [PickupParse] finds the code in it.
 *
 * Only the mini programs [BRANDS] names are read, by the task's own label (the mini program's
 * name, 「霸王茶姬」); ColorOS's list is cloud config by appId, which SystemUI is not handed. While
 * one is in front it is read a second after it arrives and every [EVERY] ms after, as the
 * order list and the order are pages of one task and moving between them changes nothing
 * SystemUI hears. A code found is shown whatever the order's state; the island goes [LIFE] after
 * the code was last seen, or when swiped away (that code is then not shown again for [LIFE]).
 *
 * A new code floats the island open, and so does the order turning ready to collect ([READY]);
 * anything else about a code already up only updates it. The island's being up is asked of the
 * system rather than remembered, since the notification's own timeout takes it away unannounced.
 */
internal object PickupCodeIsland {

    private const val TAG = "MCPickup: "
    private const val SYSUI = "com.android.systemui"
    private const val WECHAT = "com.tencent.mm"
    private const val MINI = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI"
    private const val TOP_OBSERVER = "com.miui.systemui.functions.MiuiTopActivityObserver"
    /**
     * The brand tag ColorOS hands its recognizer. Every brand in its config has one; a tag it does
     * not know is answered as `common`, which is what the plugin falls back to anyway - and the 47
     * brands' tags live in that config, which is not ours to read yet, so this is the honest empty
     * one rather than a guess at a name.
     */
    private const val OEM_APP = "common"

    private const val ID = 1241
    private const val CHANNEL = "mc_pickup"
    private const val PIC = "miui.focus.pic_mc_pickup"
    private const val PIC_AOD = "miui.focus.pic_mc_aod"
    private const val PIC_MODEL = "miui.focus.pic_mc_model"
    private const val ACTION_OPEN = "com.os4.musiccover.PICKUP_OPEN"
    private const val ACTION_GONE = "com.os4.musiccover.PICKUP_GONE"

    private const val FIRST = 1_000L
    /** Reads after the first that come quickly, a page just brought back may still be filling. */
    private const val QUICK = 2
    private const val QUICK_GAP = 1_000L
    private const val RETRIES = 10
    private const val RETRY_GAP = 300L
    private const val EVERY = 5_000L
    /** How often the page is read while the mini program is not the front activity. */
    private const val SLOW = 30_000L
    /** How long a task is read on after 微信 leaves the front before it is let go. */
    private const val LEFT = 2 * 60_000L
    /** How often a parked page is read: OPPO's own three intervals, by what the order is doing. */
    private const val PARK_FIRST = 2 * 60_000L
    private const val PARK_MAKING = 60_000L
    private const val PARK_READY = 3 * 60_000L
    /** A parked task is let go after this, as ColorOS's observeagent does. */
    private const val PARK_MAX = 30 * 60_000L
    /** How long a task stays unparked after the island was tapped. */
    private const val NO_PARK = 5 * 60_000L
    /** Battery, in percent: below the first the reads slow down, below the second the park ends. */
    private const val LOW = 20
    private const val EMPTY = 10
    /** Reads of one stay in front, at most: an hour at [EVERY]. */
    private const val MAX_READS = 720
    private const val LIFE = 30 * 60_000L
    private const val TIMEOUT = 3_000L
    /** How long after the top-activity observer appears its state is read, for the front activity. */
    private const val STARTUP_LOOK = 2_000L

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
    /** Whether the tracked mini program is the front activity, which sets how often it is read. */
    private var inFront = false
    /** Until when the task is not parked again, set when the island is tapped (see [left]). */
    private var noParkUntil = 0L
    private var brand = ""
    /** This page's entry in ColorOS's rules - its colours, pictures and brand tag, or null. */
    private var oemRule: PickupRule? = null
    /** What ColorOS's recognizer last answered, for the probe's `describe()`. Not shown to anyone. */
    private var lastOem = ""
    /** The picture opacity the card is drawn at; 1f except while [fade] is stepping it up. */
    private var cardAlpha = 1f
    /** The opacity a floated card starts at; [fade] steps it up to 1. */
    private val FADE_FROM = 0.15f
    private var reads = 0
    private var gen = 0
    private var shownKey: String? = null
    /** The state [shownKey] was posted with, to tell 制作中 → 待取餐 from a re-read of the same. */
    private var shownStatus: String? = null
    /** The notification tag [shownKey] was posted under, null for the untagged one. */
    private var shownTag: String? = null
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
                if (top != null && top.packageName == WECHAT && top.className.startsWith(MINI)) {
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
                }
                out
            }
            Xp.log(TAG + "watching the card's picture")
        }.onFailure { Xp.log(TAG + "card's picture not watched: $it") }
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
        val asked = request(task) { st ->
            bg.post {
                bg.removeCallbacks(timeout)
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
        append(" up=${Main.appContext()?.let { up(it) }}")
        append(" last=$lastRead")
        if (lastHead.isNotEmpty()) append(" head=[$lastHead]")
        if (lastOem.isNotEmpty()) append(" oem=[$lastOem]")
    }

    /** The probe's `do=read`: one read now, of whatever is tracked. */
    fun readNow() = bg.post { if (taskId >= 0) read(taskId, gen) }

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
        if (!sOn || top == null || top.packageName != WECHAT || !top.className.startsWith(MINI)) {
            // A parked task must not stay on the hidden display once 微信 is in front again: the mini
            // program tapped from its own list would come back on a display nobody can see.
            if (top?.packageName == WECHAT) PickupPark.releaseFrom("微信 is in front again")
            left(top)
            return
        }
        val ctx = Main.appContext() ?: return
        @Suppress("DEPRECATION")
        val info = runCatching {
            ctx.getSystemService(ActivityManager::class.java).getRunningTasks(8)
                .firstOrNull { it.topActivity == top }
        }.getOrNull()
        val label = info?.taskDescription?.label.orEmpty()
        if (info == null || label.isEmpty()) {
            if (retry < RETRIES) bg.postDelayed({ front(top, retry + 1) }, RETRY_GAP)
            else Xp.log(TAG + "no labelled task for ${top.shortClassName}")
            return
        }
        val name = BRANDS.firstOrNull { label.contains(it, ignoreCase = true) }
        if (name == null) {
            Xp.d(TAG + "mini program not on the list: $label")
            stop()
            return
        }
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
     * found, not what it is read for: 微信's own pages, and whatever comes over the mini program while
     * the user is still in it, arrive as a top that is not it - and with the top settling back on the
     * mini program no further event comes, so a read loop killed here never came back. A second order
     * in the same mini program stopped updating the island that way (2026-10-08: the task was dropped
     * and its reads froze at 7 while the user sat in the mini program). So the task is kept and read
     * on, slower; only [LEFT] spent without being in front ends it - 微信's own pages are worth being
     * read over, the phone in a pocket is not.
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
        // Out of 微信 altogether: the order is worth waiting on, so the task goes onto the hidden
        // display where the mini program keeps its own page current (PickupPark, §6). Not while 微信
        // itself is in front: a mini program started from its own list would come back on a display
        // nobody can see, and 微信 coming to the front releases it for the same reason. Not right
        // after the island was tapped either: the user is in the mini program, and the front flickers
        // through the launcher as they move about it - parking on that flicker took the mini program
        // away from them mid-use (2026-10-08).
        if (top?.packageName != WECHAT && waiting() && SystemClock.uptimeMillis() >= noParkUntil) {
            PickupPark.parkFrom(taskId)
            return
        }
        bg.postDelayed({ if (!inFront && taskId >= 0 && PickupPark.parked() != taskId) stop() }, LEFT)
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
     * The rest of §6.3's list: a battery below [LOW] percent, a battery saver on, a phone running
     * hot. ColorOS also skips while a game is being played or the camera is open; neither is
     * something this process can see for nothing, so neither is here.
     */
    private fun tired(ctx: Context): Boolean {
        val pm = ctx.getSystemService(android.os.PowerManager::class.java) ?: return false
        return battery(ctx) in 1 until LOW || pm.isPowerSaveMode ||
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
                level in 0..EMPTY -> {
                    Xp.log(TAG + "battery $level%: the wait is over")
                    PickupPark.releaseFrom("battery $level%")
                }
                tired(ctx) -> {
                    next(task, g)
                    return
                }
            }
        }
        // Not while the screen is off or locked: the page is not being looked at. A parked one is -
        // the state wanted on the lock screen is exactly the parked page's, and it is kept current
        // there - so it is read on, at the park's own intervals.
        val power = ctx.getSystemService(android.os.PowerManager::class.java)
        val keyguard = ctx.getSystemService(android.app.KeyguardManager::class.java)
        if (!parked && (power?.isInteractive == false || keyguard?.isKeyguardLocked == true)) {
            next(task, g)
            return
        }
        val t0 = SystemClock.uptimeMillis()
        val timeout = Runnable { if (g == gen) { lastRead = "timeout"; next(task, g) } }
        bg.postDelayed(timeout, TIMEOUT)
        val asked = request(task) { st ->
            bg.post {
                bg.removeCallbacks(timeout)
                if (g != gen) return@post
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
        val s = shownStatus ?: return PARK_FIRST
        return if (READY.any { s.contains(it) }) PARK_READY else PARK_MAKING
    }

    /**
     * IActivityTaskManager.requestAssistDataForTask(receiver, taskId, callingPackage,
     * attributionTag, fetchStructure) - Android 17's five; the appop it notes is checked against
     * SystemUI's own package. The receiver is a bare Binder: IAssistDataReceiver's transaction 1
     * is onHandleAssistData(Bundle), 2 the screenshot, both oneway.
     */
    private fun request(task: Int, done: (AssistStructure?) -> Unit): Boolean {
        val receiver = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == 1) {
                    val st = runCatching {
                        data.enforceInterface("android.app.IAssistDataReceiver")
                        val b = if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else null
                        b?.classLoader = AssistStructure::class.java.classLoader
                        @Suppress("DEPRECATION")
                        b?.getParcelable<AssistStructure>("structure")
                    }.getOrNull()
                    done(st)
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
        // The brand's entry in ColorOS's own rules, which carries its colours, its pictures and the
        // tag its recognizer wants. Scored against the task label - the same low-cost scope ColorOS
        // uses where an applet id and a route are not observable, and the fallback path its config
        // describes.
        val content = nodes.joinToString("\n") { it.text }
        val texts = nodes.map { it.text }
        oemRule = Main.appContext()?.let {
            runCatching { PickupRules.get(it).matchPage(WECHAT, brand, content) }.getOrNull()
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
        )
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
     * completed/uncompleted/waiting, which mean nothing to a reader. Null when it did not answer,
     * so the page's wording stands; this is shown, never decided on (see [PickupParse.Result]).
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
     * process's notifications are listed, and only this one is ours ([ID], [CHANNEL]). null is the
     * system not saying - not an answer, so the caller keeps what it had; reading a failure as
     * "gone" would float the island open again on every read.
     */
    private fun up(c: Context): Boolean? = runCatching {
        c.getSystemService(NotificationManager::class.java)?.activeNotifications
            ?.any { it.id == ID && it.notification.channelId == CHANNEL }
    }.getOrNull()

    private fun takeDown(why: String) {
        if (shownKey == null) return
        val tag = shownTag
        clear()
        Main.appContext()?.getSystemService(NotificationManager::class.java)?.let {
            if (tag == null) it.cancel(ID) else it.cancel(tag, ID)
        }
        Xp.log(TAG + "taken down: $why")
    }

    private fun receivers(ctx: Context) {
        if (receivers) return
        receivers = true
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    ACTION_OPEN -> bg.post { open(c) }
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
     * The mini program's task back in front, over 微信's own task so that Back from it lands in 微信,
     * and 微信 itself if the task is gone.
     *
     * 微信's task is moved forward first, and the mini program's without [MOVE_TASK_WITH_HOME]: that
     * flag brings the task's home along, and the home of an appbrand task is the launcher - tapping
     * the island opened the mini program over the desktop, so Back took the user out of 微信
     * altogether (2026-10-08, the top the module saw right after a tap was com.miui.home/.launcher).
     */
    private fun open(c: Context) {
        // Tapping the island is the user going into the mini program: nothing is parked again for
        // [NO_PARK] while they are in there. See [left] - the front flickers through the launcher as
        // they move about it, and parking on that flicker takes it away from them mid-use.
        noParkUntil = SystemClock.uptimeMillis() + NO_PARK
        if (PickupPark.parked() >= 0) {
            // §6.5: the task goes back to the display the user can see before it is brought to the
            // front - moved to the front while parked, it would come up on the hidden one.
            PickupPark.releaseFrom("the island was tapped") { openNow(c) }
        } else {
            openNow(c)
        }
    }

    private fun openNow(c: Context) {
        val am = c.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        val tasks = runCatching { am.getRunningTasks(64) }.getOrDefault(emptyList())
        val ok = shownTask >= 0 && tasks.any { it.taskId == shownTask } && runCatching {
            tasks.firstOrNull { it.taskId != shownTask && it.baseActivity?.packageName == WECHAT }
                ?.let { am.moveTaskToFront(it.taskId, 0) }
            am.moveTaskToFront(shownTask, 0)
        }.isSuccess
        if (!ok) {
            runCatching {
                c.packageManager.getLaunchIntentForPackage(WECHAT)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { c.startActivity(it) }
            }.onFailure { Xp.log(TAG + "微信 not opened: $it") }
        }
    }

    @android.annotation.SuppressLint("NotificationPermission")
    private fun post(c: Context, r: PickupParse.Result, task: Int, fresh: Boolean) {
        receivers(c)
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
        val title = oemTitle(r) ?: r.status ?: r.label
        // 「麦当劳示例广场餐厅」 under 麦当劳 is 「示例广场餐厅」.
        val store = r.store?.removePrefix(brand)?.trimStart('（', '(', ' ')?.ifEmpty { null }
        val detail = r.product?.ifEmpty { null } ?: store
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
            .put("business", "pickup_code")
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
        val aod = PickupArt.aod(oemRule, PickupArt.model(oemRule, brand, r.product))
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
        val tap = PendingIntent.getBroadcast(c, ID, Intent(ACTION_OPEN).setPackage(SYSUI),
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
            // Two cards, one per theme: the official card has no background of its own - SystemUI
            // draws the backdrop behind it - so its text and its rule are picked against that
            // backdrop, and the notification carries both rather than asking which is showing.
            runCatching {
                PickupCard.build(c, oemRule, r.code, r.product, store, brand, tap, cardAlpha, false) to
                    PickupCard.build(c, oemRule, r.code, r.product, store, brand, tap, cardAlpha, true)
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
        // A post that must float the island gets a notification key of its own. MIUI keeps per-key
        // state for a focus notification (FocusNotificationController's hasEverExpandedKeys and its
        // island-data map), and with one fixed key - 1241, no tag, the same for every order - the
        // island came up for a key once and never again after it had gone: a second code, or one
        // re-read after the user tapped the island away, was left in the shade alone while the
        // module kept posting. A tag makes that a new key, and the one left behind is dropped first
        // (two focus notifications at once would be two islands). A quiet update keeps the key it
        // was posted under, so it stays one island being updated.
        val tag = if (fresh) "mc-" + SystemClock.elapsedRealtime() else shownTag
        runCatching {
            if (tag != shownTag) {
                if (shownTag == null) nm.cancel(ID) else nm.cancel(shownTag, ID)
            }
            nm.notify(tag, ID, n)
        }.onSuccess { shownTag = tag }
        Xp.log(TAG + (if (fresh) "up: " else "updated: ") + brand + " (tag=${tag ?: "-"})")
    }

    /**
     * The mini program's own icon, which its task carries (TaskDescription: in memory, or the
     * file the system keeps it in); 微信's when it has none.
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
        if (!iconSaid) { iconSaid = true; Xp.log(TAG + "task icon: none, 微信's instead") }
        val d = c.packageManager.getApplicationIcon(WECHAT)
        val size = 144
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            d.setBounds(0, 0, size, size)
            d.draw(Canvas(it))
        }
    }
}

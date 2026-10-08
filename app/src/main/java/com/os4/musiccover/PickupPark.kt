package com.os4.musiccover

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/**
 * The hidden display a mini program's order page waits on between visits - ColorOS's
 * ObserverVirtualDisplay, which its observeagent moves the task onto and refreshes the page in.
 *
 * Why a display at all: 微信 suspends a mini program once it is not in front, so a page left in the
 * background stops asking for its order's state. The code can still be read off such a task (that is
 * the island's first stage), but it is the code as of the moment the user left. A task on a display
 * of its own reads as visible, so the mini program stays up and keeps its own page current, with
 * nothing hooked inside 微信.
 *
 * The display is cheap and invisible: a 1200x2608 buffer whose frames are taken and dropped (a page
 * that is never drawn is a page whose WebView stops being asked to draw, so the reader is what keeps
 * it going), and no renderer of ours. It is a display the system has, not the user.
 *
 * [FLAGS] is ColorOS's own set. DESTROY_CONTENT_ON_REMOVAL, which ColorOS also sets, is left out on
 * purpose: with it, a display that goes away while a task is parked on it - this process being
 * killed, SystemUI being restarted (which this module does to itself while developing) - destroys the
 * task, and with it the user's order page. Without it the system moves the task back to display 0,
 * which is what [release] does anyway whenever there is time to.
 */
internal object PickupPark {

    private const val TAG = "MCPickupPark: "
    private const val NAME = "mc-pickup-park"
    private const val W = 1200
    private const val H = 2608
    private const val DPI = 480

    /**
     * PUBLIC | OWN_CONTENT_ONLY | TRUSTED | OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED - AOSP's flags, the
     * set ColorOS builds its own hidden display with. TRUSTED is the one behind a permission
     * (ADD_TRUSTED_DISPLAY), so whether creating this succeeds from SystemUI rather than from a shell
     * is a real question about the module, not a detail.
     */
    private const val FLAGS = 1 or 8 or 1024 or 16384 or 65536

    /** OPPO's own interval for an order being made, the shortest of its three. */
    const val EVERY = 30_000L

    private val bg: Handler by lazy { Handler(HandlerThread("mc-pickup-park").apply { start() }.looper) }

    // All below on [bg], but for [parked] and [since], which anyone may read.
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    @Volatile private var parked = -1
    /** When the current park began, for whoever decides it has lasted long enough. */
    @Volatile private var since = 0L
    private var until = 0L
    private var every = EVERY
    private var reads = 0
    private val seen = LinkedHashSet<String>()

    // ------------------------------------------------------------------ the display

    /**
     * The hidden display, made if it is not up yet. ADD_TRUSTED_DISPLAY is checked against this
     * process - SystemUI's uid, not adb's - so a refusal here is the answer to that question rather
     * than a bug: it is logged, and said in the probe's reply with the exception's own text.
     */
    private fun ensure(): String {
        val ctx = Main.appContext() ?: return "no context"
        vd?.let { return "display ${it.display.displayId}" }
        return runCatching {
            val dm = ctx.getSystemService(DisplayManager::class.java) ?: error("no DisplayManager")
            val r = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2)
            // Frames have to be taken: a producer with no free buffer left stops drawing, and the
            // page's own redraws are half of what keeps it current.
            r.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, bg)
            val d = dm.createVirtualDisplay(NAME, W, H, DPI, r.surface, FLAGS)
                ?: error("createVirtualDisplay returned null")
            reader = r
            vd = d
            Xp.log(TAG + "hidden display ${d.display.displayId} (${W}x${H}@${DPI}, flags $FLAGS)")
            "display ${d.display.displayId}"
        }.getOrElse {
            Xp.log(TAG + "no hidden display: $it")
            "no display: $it"
        }
    }

    /**
     * IActivityTaskManager.moveRootTaskToDisplay(task, display): 微信's appbrand task onto the hidden
     * display, and back to 0 the same way. This phone's `am` has no move-stack, and the service is an
     * interface, so the method is found by name the way the assist request is.
     */
    private fun move(task: Int, display: Int): String = runCatching {
        val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        val m = atm.javaClass.methods.firstOrNull { it.name == "moveRootTaskToDisplay" }
            ?: error("no moveRootTaskToDisplay")
        m.invoke(atm, task, display)
        Xp.log(TAG + "task $task -> display $display")
        "task $task -> display $display"
    }.getOrElse {
        Xp.log(TAG + "task $task not moved to $display: $it")
        "move failed: $it"
    }

    /**
     * Whatever is parked goes back to the phone's own display first, always, and only then is the
     * display let go. Worth re-reading before changing.
     */
    fun release(why: String): String {
        val t = parked
        parked = -1
        val back = if (t >= 0) move(t, 0) else "nothing parked"
        val out = runCatching {
            vd?.release()
            reader?.close()
            val s = "released ($why): $back"
            Xp.log(TAG + s)
            s
        }.getOrElse { "release failed: $it" }
        vd = null
        reader = null
        since = 0L
        until = 0L
        return out
    }

    /** [release] from any thread: the display's own thread does the work, [then] after it. */
    fun releaseFrom(why: String, then: (() -> Unit)? = null) {
        if (parked < 0 && vd == null) {
            then?.invoke()
            return
        }
        bg.post {
            release(why)
            then?.invoke()
        }
    }

    /** [park] from any thread, once the order on screen is one worth waiting on. */
    fun parkFrom(task: Int) {
        if (task < 0 || parked == task) return
        bg.post { park(task) }
    }

    /**
     * [task] onto the hidden display and left there: the reads are the island's own loop (it reads
     * the page wherever the task is), and [release] - or the display going away with this process -
     * is what ends it. Returns what happened, for the probe's reply.
     */
    private fun park(task: Int): String {
        if (parked >= 0) return "already parked: $parked"
        val made = ensure()
        if (made.startsWith("no display")) return made
        val id = vd?.display?.displayId ?: return "no display"
        val moved = move(task, id)
        if (moved.startsWith("move failed")) return "$made, $moved"
        parked = task
        since = SystemClock.uptimeMillis()
        Xp.log(TAG + "parked task $task on $id")
        return "$made, $moved"
    }

    fun parked(): Int = parked

    /** When the current park began, 0 while nothing is parked. */
    fun sinceMs(): Long = since

    fun state(): String = "parked=$parked vd=${vd?.display?.displayId ?: -1}" + if (parked >= 0) {
        " reads=$reads at=${(SystemClock.uptimeMillis() - since) / 1000}s" +
            " left=${((until - SystemClock.uptimeMillis()).coerceAtLeast(0)) / 1000}s" +
            if (seen.isEmpty()) "" else " seen=$seen"
    } else ""

    // ------------------------------------------------------------------ the probe's experiment

    /**
     * `do vdtest`: ColorOS's second stage by hand, on the module's own account. [task] is parked on
     * the hidden display and its page read every [everyMs] for [minutes], each read logged with what
     * it found, and then the task is put back. The island's own state is not touched.
     *
     * What this settles, on the phone rather than in the abstract: whether SystemUI holds
     * ADD_TRUSTED_DISPLAY and can have such a display at all; whether a mini program's page keeps its
     * own order state current while it is parked and the user is somewhere else (if it does, nothing
     * ever has to be sent into the display, and the module needs no INJECT_EVENTS); and whether the
     * task survives the round trip, page and all, as the shell probe measured.
     */
    fun experiment(task: Int, minutes: Int, everyMs: Long = 0): String {
        val made = park(task)
        if (made.startsWith("no display") || made.startsWith("move failed") || made.startsWith("already")) {
            return made
        }
        until = SystemClock.uptimeMillis() + minutes * 60_000L
        every = if (everyMs > 0) everyMs else EVERY
        reads = 0
        seen.clear()
        Xp.log(TAG + "reading task $task every ${every / 1000}s for $minutes min")
        bg.post { read(task) }
        return "$made, ${minutes} min"
    }

    private fun read(task: Int) {
        if (parked != task) return
        if (SystemClock.uptimeMillis() >= until) {
            Xp.log(TAG + "parked $task for its ${(until - since) / 60_000} min: $reads reads, $seen")
            release("the probe's time is up")
            return
        }
        val t0 = SystemClock.uptimeMillis()
        PickupCodeIsland.readOnce(task) { r, texts ->
            val ms = SystemClock.uptimeMillis() - t0
            reads++
            r?.status?.let { seen += it }
            Xp.log(TAG + "parked ${(SystemClock.uptimeMillis() - since) / 1000}s: " +
                (if (r == null) "no code in $texts texts" else "${r.code} ${r.label} / ${r.status} / ${r.store}") +
                " (${ms}ms)")
            if (r != null && FINISHED.any { r.status?.contains(it) == true }) {
                release("the order is over ($r.status)")
                return@readOnce
            }
            bg.postDelayed({ read(task) }, every)
        }
    }

    /** The probe's reply: the display and the park, with the states the parked reads saw. */
    fun describe(): String = "park: " + state()

    /** An order that is over: nothing left to wait for. (已完成 is the ready state at 蜜雪冰城.) */
    private val FINISHED = setOf("已完成", "已取消", "已退款")
}

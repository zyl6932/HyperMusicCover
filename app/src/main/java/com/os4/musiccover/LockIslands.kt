package com.os4.musiccover

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.view.View
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The lock screen's notification islands: the notifications the keyguard would show in its
 * stack, shown in the mini player's row instead and taken out of the stack while the row shows.
 *
 * The keyguard decides what its stack shows in one place, KeyguardCoordinator's finalize filter
 * (it asks KeyguardNotificationVisibilityProviderImpl.shouldHideNotification). Hooking that
 * filter does both halves at once. Everything it would have let through - and that is not the
 * media card's own notification - is an island candidate; while [active], those are filtered
 * out, which is the stack's own way of leaving a notification off the lock screen: no row to
 * hide by hand, the AOD, the heads-up and the unlocked shade untouched. A pipeline run ends
 * with Pluggable.onCleanup, which is where the candidates of that run become [notes].
 *
 * The row (MiniPlayerController) turns [active] on only while it is showing; with it off the
 * notifications go straight back to the stack - in the cover, under the control centre, with
 * the feature off - rather than vanishing.
 */
internal object LockIslands {
    /** The one island every notification but the focus ones shares (stackMembers). */
    const val STACK_KEY = "mc:stack"

    /** One notification as an island. */
    class Note(
        val key: String,
        val pkg: String,
        val title: CharSequence,
        val text: CharSequence,
        val icon: Drawable?,
        val focus: Boolean,
        val time: Long,
        val intent: PendingIntent?,
        val group: String?,
        val summary: Boolean,
        /** Shown as the lock screen shows it: the public version. */
        val redacted: Boolean = false,
        /**
         * The super island's own ranking of it, from the focus notification's param_island:
         * islandProperty, islandPriority (1 and 1 without one, as FocusNotifUtils fills in), and
         * islandOrder - whether each update makes it the newest again (DynamicIslandWindowView
         * .updateTime).
         */
        val property: Int = 1,
        val priority: Int = 1,
        val order: Boolean = false,
        /**
         * When it became an island, wall clock: the entry's creation, as the super island's time
         * is its view's making - and for one with islandOrder, its latest update (postTime).
         */
        val since: Long = 0L,
        /** The focus template's timer, the row's chronometer; null for one with none. */
        val timer: Timer? = null,
        /** The focus template's moving picture, the row's Lottie; null for a still one. */
        val anim: Anim? = null,
        /** The focus template's picture when it is a view of the plugin's, shown over [icon]. */
        val live: Live? = null,
        /** The focus template's buttons, in the row's order: the last is its main one. */
        val buttons: List<Button> = emptyList(),
        /** For `op mini`: where [icon] came from (focusPicture), "app" for the app's own. */
        val iconFrom: String = "",
    )

    /**
     * A focus template's button as the plugin builds it (ModuleButtonViewHolder.buildAction),
     * the same rule for every app: [iconName] its picture's name - actionIcon(Dark), else the
     * name the app gave its Notification.Action ("icon_name"), else the action's key - looked
     * up in the plugin's own pictures; its plate [bg] and [press] (the dark ones: the lock screen
     * draws the template dark); and what it does, a Notification.Action kept in the extras'
     * miui.focus.actions or an intent URI of [intentType] 1 activity, 2 broadcast, 3 service.
     * [index] is its place in the row (focus_button_icon1, 2, 3), whose own button a tap presses.
     */
    class Button(
        val index: Int,
        val iconName: String?,
        val label: CharSequence?,
        val action: Notification.Action?,
        val intentUri: String?,
        val intentType: Int,
        val bg: Int?,
        val press: Int?,
        val picture: android.graphics.drawable.Icon?,
        val row: WeakReference<View?>,
        val postTime: Long,
        /** 0 a picture, 1 a picture in a progress ring, 2 its title on its plate. */
        val type: Int = 0,
        val titleColor: Int? = null,
        val progress: Progress? = null,
    )

    /**
     * A progress button's ring (ActionInfo.progressInfo): [value] 0 to 100, or run by the
     * template's timer when [auto]; anticlockwise when [ccw]; from [color] to [colorEnd].
     */
    class Progress(val value: Int, val auto: Boolean, val ccw: Boolean, val color: Int?, val colorEnd: Int?)

    /**
     * A focus template's animIconInfo: [src] the plugin's name for one of its own Lottie files
     * (LottieResUtils.getLottieRes), played on a loop - [number] times, or for ever - when
     * [autoplay], held still when not (ModuleAnimationTextViewHolder.playAnimation). [row] is
     * the notification's row, whose picture view carries the plugin's context and classes.
     */
    class Anim(val src: String, val number: Int, val autoplay: Boolean, val row: WeakReference<View>) {
        val repeat get() = if (number > 0) number else -1
    }

    /**
     * A focus template's timerInfo, as the plugin runs its chronometer (ModuleViewHolder
     * .initTimerData / setTimerData, TimerSystemCurrentUtils): above 0 it counts up from
     * [whenMs], below 0 down to it; 1, -1, 3 and -3 are running, the rest held at [systemMs].
     */
    class Timer(val type: Int, val whenMs: Long, val systemMs: Long, val totalMs: Long = 0L) {
        val running get() = type == 1 || type == -1 || type == 3 || type == -3

        /** ±3 and ±4 read in minutes and seconds only, the minutes past sixty (NotificationTimeKeeper). */
        val minutes get() = type == 3 || type == 4 || type == -3 || type == -4

        /** Its share of its total, 0 to 100, as a progress button's ring runs it; -1 with no total. */
        fun progress(now: Long = System.currentTimeMillis()): Float =
            if (totalMs > 0L) elapsedMs(now).toFloat() / totalMs * 100f else -1f

        /** What the chronometer reads now, in milliseconds. */
        fun elapsedMs(now: Long = System.currentTimeMillis()): Long {
            val at = if (running) now else systemMs
            return (if (type > 0) at - whenMs else whenMs - at).coerceAtLeast(0L)
        }

        /** As the chronometer draws it: M:SS, or H:MM:SS past the hour; MM:SS for the minute kinds. */
        fun text(now: Long = System.currentTimeMillis()): String {
            val s = elapsedMs(now) / 1000L
            return if (minutes) String.format(java.util.Locale.ROOT, "%02d:%02d", s / 60, s % 60)
            else android.text.format.DateUtils.formatElapsedTime(s)
        }
    }

    /** An island's standing for a place in the row, as the super island's compareState reads it. */
    class Rank(val property: Int, val priority: Int, val time: Long)

    /**
     * This lock screen's islands in the super island's order: the one it would put in the big
     * island first (see [rankOf]).
     */
    @Volatile var notes: List<Note> = emptyList()
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var filter: WeakReference<Any>? = null
    private var active = false
    private var focusCheck: java.lang.reflect.Method? = null

    /** Candidates of the pipeline run in progress, in the order the filter saw them. */
    private val pending = LinkedHashMap<String, Note>()
    /** This run found the lock screen: its candidates are all there is, and the rest are gone. */
    private var lockedRun = false

    /** Put back in the stack by a tap: an island no more until the lock screen goes. */
    private val released = HashSet<String>()

    /**
     * Every notification that is not a focus one is one island, [STACK_KEY] (2026-09-25, the
     * user's rule: QQ and WeChat made an island of every message). These are its notifications,
     * the newest - the one it shows - first, whether it is in the row or out in the stack.
     */
    @Volatile var stackMembers: List<String> = emptyList()
        private set

    /** The stack island as the row shows it; null with no notification in it. */
    private var stackNote: Note? = null
    private var stackFrom: List<Note> = emptyList()

    /** The stack island tapped open: all of its notifications back in the stack, new ones too. */
    private var stackOut = false

    fun install(classLoader: ClassLoader) {
        focusCheck = runCatching {
            Xp.findClass("com.android.systemui.statusbar.notification.utils.FocusUtils", classLoader)
                .getDeclaredMethod("isFocusNotification", Notification::class.java)
                .also { it.isAccessible = true }
        }.getOrNull()
        val filterClass = runCatching {
            Xp.findClass("com.android.systemui.statusbar.notification.collection.coordinator." +
                "KeyguardCoordinator\$notifFilter\$1", classLoader)
        }.getOrElse {
            Xp.log("MCIsland: keyguard filter unavailable, no notification islands: $it")
            return
        }
        runCatching {
            Xp.hookAll(filterClass, "shouldFilterOut") { chain ->
                val hidden = chain.proceed() as Boolean
                runCatching { consider(chain.thisObject, chain.args, hidden) }
                    .getOrDefault(hidden)
            }
        }.onFailure { Xp.log("MCIsland: filter hook failed: $it"); return }
        runCatching {
            val pluggable = Xp.findClass("com.android.systemui.statusbar.notification.collection." +
                "listbuilder.pluggable.Pluggable", classLoader)
            Xp.hookAll(pluggable, "onCleanup") { chain ->
                val result = chain.proceed()
                if (chain.thisObject === filter?.get()) runCatching { commit() }
                result
            }
        }.onFailure { Xp.log("MCIsland: run end unavailable: $it") }
    }

    /** For `op mini`: what the filter has, and whether the stack is leaving it out. */
    fun describe(): String = "islands active=$active cover=$cover filter=${filter?.get() != null} " +
        "released=${released.size} stack=${stackMembers.size}${if (stackOut) "/out" else ""} notes=" + notes.joinToString(",") {
            (if (it.focus) "F:" else "") + (if (it.redacted) "R:" else "") + it.pkg +
                (if (!it.focus || it.redacted) "<${it.iconFrom}>" else "") +
                "[${it.property}/${it.priority}${if (it.order) "/o" else ""} " +
                "t=-${(System.currentTimeMillis() - it.since) / 1000}s]" +
                (if (it.focus && !it.redacted) "{${it.timer?.let { t -> "timer ${t.type} ${t.text()} " } ?: ""}" +
                    "'${it.title.take(20)}'/'${it.text.take(20)}' icon=${it.icon?.javaClass?.simpleName}<${it.iconFrom}> " +
                    "anim=${it.anim?.let { a -> "${a.src}/${a.autoplay}/row=${a.row.get() != null}" }} " +
                    (it.live?.let { l -> "live=${l.id} " } ?: "") +
                    "btn=${it.buttons.joinToString("/") { b -> "${b.index}:t${b.type}:${b.iconName}" }}}" else "")
        } + if (creationUnreadable) " creation=unreadable" else ""

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Whether the row is showing the islands, and so whether the stack should leave them out.
     * A change runs the pipeline again at once, so the stack follows the row.
     */
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        // The row has the islands again: the cover's hold on the focus ones is over.
        if (value && cover) {
            main.removeCallbacks(coverOff)
            cover = false
        }
        invalidate("row ${if (value) "showing" else "gone"}")
    }

    /**
     * The music's cover is up: the lock screen is the media card's alone, and the focus
     * notifications (a timer, navigation) stay out of its stack as they stay out of it when the
     * row shows them - the user asked for the card alone in the cover (2026-09-25). Leaving the
     * cover, they stay out until the row has them again (setActive) or a moment has passed:
     * let back in at once, their rows stood in the stack for the frames before the row took them.
     *
     * With the row showing, [active] already keeps every one of them out, so the cover changes
     * nothing the filter decides and the pipeline is not run for it: that run was a whole list
     * rebuild on the first frame of every entry, and a dropped frame each time (traced
     * 2026-09-25, 22 entries out of 22). [setActive] runs it if the row goes while the cover is up.
     */
    private var cover = false

    fun setCoverMode(value: Boolean) {
        if (value) {
            main.removeCallbacks(coverOff)
            if (!cover) {
                cover = true
                if (!active) invalidate("cover up")
            }
        } else if (cover) {
            main.removeCallbacks(coverOff)
            main.postDelayed(coverOff, COVER_RELEASE_MS)
        }
    }

    private val coverOff = Runnable {
        if (cover) {
            cover = false
            if (!active) invalidate("cover gone")
        }
    }

    private const val COVER_RELEASE_MS = 1500L

    /** A tapped island goes back into the stack, for the rest of this lock screen. */
    fun release(key: String) { android.os.Trace.beginSection("MC li.release"); try {
        if (key == STACK_KEY) {
            if (!stackOut) {
                stackOut = true
                invalidate("stack released")
            }
            return
        }
        if (released.add(key)) invalidate("island $key released")
    } finally { android.os.Trace.endSection() } }

    /** A notification put back in the stack comes back into the row: collapsed into it. */
    fun recapture(key: String) { android.os.Trace.beginSection("MC li.recapture"); try {
        if (key == STACK_KEY) {
            if (stackOut) {
                stackOut = false
                invalidate("stack recaptured")
            }
            return
        }
        if (released.remove(key)) invalidate("island $key recaptured")
    } finally { android.os.Trace.endSection() } }

    fun isReleased(key: String): Boolean = if (key == STACK_KEY) stackOut else key in released

    fun releasedKeys(): List<String> = released.toList() + if (stackOut) listOf(STACK_KEY) else emptyList()

    /** A notification as last read, released or not: what a row collapsing back will show. */
    fun noteFor(key: String): Note? = if (key == STACK_KEY) stackNote else readCache[key]?.second

    /** [key]'s notifications: the stack island's, or itself. */
    fun membersOf(key: String): List<String> = if (key == STACK_KEY) stackMembers else listOf(key)

    /** The lock screen went away: every notification is an island again next time. */
    fun resetReleased() {
        if (released.isEmpty() && !stackOut) return
        released.clear()
        stackOut = false
        invalidate("released cleared")
    }

    private fun invalidate(reason: String) {
        val f = filter?.get() ?: return
        // Each is a rebuild of the whole notification list on the next frame (3-8ms on the lock
        // screen); named in a system trace, so the ones landing mid-animation can be told apart.
        traced("MC li.invalidate $reason") {
            runCatching { Xp.callMethod(f, "invalidateList", "MusicCover: $reason") }
        }
    }

    /**
     * The screen going off locks it: the pipeline is run then, so the islands are read by the
     * time it wakes. Unlocked runs read nothing, and nothing ran again until the row showed and
     * asked (setActive) - woken from the doze, the pill stood alone for four frames and then
     * narrowed for the small island coming up beside it (filmed 2026-09-26).
     */
    private var screenWatch = false

    private fun watchScreen() {
        if (screenWatch) return
        val ctx = Main.sAppCtx ?: return
        screenWatch = true
        runCatching {
            ctx.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                    // The keyguard locks a moment after the screen goes: once then, once to be sure.
                    for (delay in SCREEN_OFF_READS) main.postDelayed({ invalidate("screen off") }, delay)
                }
            }, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF))
        }.onFailure { screenWatch = false }
    }

    private val SCREEN_OFF_READS = longArrayOf(150L, 900L)

    /** One filter call: records the candidate, and filters it out if the row has it. */
    private fun consider(filterObject: Any, args: List<Any?>, hidden: Boolean): Boolean {
        if (filter?.get() !== filterObject) filter = WeakReference(filterObject)
        watchScreen()
        if (hidden) return true
        val entry = args.firstOrNull() ?: return false
        if (!lockedOrLocking(filterObject)) {
            // Unlocked: what a tap put back is an island again on the next lock screen.
            released.clear()
            stackOut = false
            // Unlocking, the keyguard is "not locked" from the first frame of its fade-out,
            // while the row is still drawn on it: letting the islands go then put every one
            // of them in the fading stack at once - a flash of rows on each unlock (filmed
            // 2026-09-25). They stay islands until the lock screen is off the screen; the row
            // turning off after that runs the pipeline again (setActive).
            if (!active || !Main.onKeyguardNow()) return false
            val key = (Xp.getObjectField(entry, "mSbn") as? StatusBarNotification)?.key
            val held = key != null && notes.any { it.key == key || it.key == STACK_KEY && key in stackMembers }
            if (held) watchUnlock()
            return held
        }
        lockedRun = true
        val note = read(entry, redacted(filterObject, entry)) ?: return false
        pending[note.key] = note
        val out = if (note.focus) note.key in released else stackOut
        return active && !out || cover && note.focus && !out
    }

    /**
     * Islands held through an unlock's fade-out go back the moment the lock screen is off the
     * screen. Nothing else is sure to run the pipeline then - the row stops updating while the
     * keyguard goes away - so this looks, frame-ish, for as long as it takes.
     */
    private fun watchUnlock() {
        if (unlockWatch) return
        unlockWatch = true
        val started = android.os.SystemClock.uptimeMillis()
        main.post(object : Runnable {
            override fun run() {
                val waited = android.os.SystemClock.uptimeMillis() - started
                if (Main.onKeyguardNow() && waited < UNLOCK_WATCH_MS) {
                    main.postDelayed(this, 32L)
                    return
                }
                unlockWatch = false
                invalidate("lock screen off the screen")
            }
        })
    }

    private var unlockWatch = false
    private const val UNLOCK_WATCH_MS = 3000L

    /** The provider's own reading of the keyguard: showing, or about to be. */
    private fun lockedOrLocking(filterObject: Any): Boolean = runCatching {
        val found = provider?.get() ?: Xp.getObjectField(
            Xp.getObjectField(filterObject, "this\$0"), "keyguardNotificationVisibilityProvider")
            .also { provider = WeakReference(it) }
        Xp.callMethod(found, "isLockedOrLocking") as Boolean
    }.getOrElse { Main.keyguardLocked() }

    private var provider: WeakReference<Any>? = null

    /**
     * Whether the lock screen shows this notification's public version, as its row would: the
     * stack's own rule (SensitiveContentCoordinatorImpl.onBeforeRenderList) - the user's lock
     * screen is in public mode and the notification needs redaction. That coordinator only runs
     * on what the stack renders, which an island is not, so the entry's own flag can be stale
     * and the rule is asked again here; the flag still counts (app lock and the like set it).
     *
     * Filmed 2026-09-25: an island showed a QQ group's name and message, and the row it opened
     * into said only "QQ / 你有一条新消息" - the island had shown what the lock screen hides, and
     * the landing jumped from one to the other.
     */
    private fun redacted(filterObject: Any, entry: Any): Boolean {
        // A focus notification whose template is up shows in full whatever the rule says:
        // ExpandableNotificationRow.setHideSensitive shows the public version only when
        // !injector.focusNotificationWithinInflatedView. Redacted by the rule, a stopwatch's
        // island read "你有一条新消息" and its row "1:44:24 秒表" (2026-09-25).
        if (focusTemplateUp(entry)) return false
        val flagged = runCatching {
            Xp.callMethod(Xp.getObjectField(entry, "mSensitive"), "getValue") == true
        }.getOrDefault(false)
        if (flagged) return true
        return runCatching {
            // consider() has just asked the provider about the keyguard, which found it.
            val found = provider?.get() ?: Xp.getObjectField(
                Xp.getObjectField(filterObject, "this\$0"), "keyguardNotificationVisibilityProvider")
            val manager = Xp.getObjectField(found, "lockscreenUserManager")
            @Suppress("DEPRECATION")
            val owner = (Xp.getObjectField(entry, "mSbn") as StatusBarNotification).userId
            val current = runCatching { Xp.getObjectField(manager, "mCurrentUserId") as Int }
                .getOrDefault(owner)
            val public = Xp.callMethod(manager, "isLockscreenPublicMode", current) == true ||
                Xp.callMethod(manager, "isLockscreenPublicMode", owner) == true
            public && (Xp.callMethod(manager, "getRedactionType", entry) as Number).toInt() != 0
        }.getOrElse {
            if (!redactionUnreadable) {
                redactionUnreadable = true
                Xp.log("MCIsland: redaction unreadable, islands show full content: $it")
            }
            false
        }
    }

    private var redactionUnreadable = false

    /** The entry's row has its focus template inflated (the row injector's own flag). */
    private fun focusTemplateUp(entry: Any): Boolean = runCatching {
        val row = Xp.getObjectField(entry, "row") ?: return false
        val injector = rowInjector(row) ?: return false
        Xp.getObjectField(injector, "focusNotificationWithinInflatedView") == true
    }.getOrDefault(false)

    private fun rowInjector(row: Any): Any? {
        runCatching { return Xp.callMethod(row, "getInjector") }
        var c: Class<*>? = row.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (!f.type.name.endsWith("Injector")) continue
                f.isAccessible = true
                val v = f.get(row) ?: continue
                if (hasField(v.javaClass, "focusNotificationWithinInflatedView")) return v
            }
            c = c.superclass
        }
        return null
    }

    private fun hasField(start: Class<*>, name: String): Boolean {
        var c: Class<*>? = start
        while (c != null && c != Any::class.java) {
            if (c.declaredFields.any { it.name == name }) return true
            c = c.superclass
        }
        return false
    }

    /**
     * Read notes by key, kept while the notification is unchanged: the filter runs for every
     * notification on every pipeline run, and loading an icon each time is not free. Redacted
     * and not are two readings of one notification, and the stamp tells them apart.
     */
    private val readCache = HashMap<String, Pair<Long, Note>>()

    /** The run is over: its candidates are the islands. */
    private fun commit() {
        val all = pending.values.toList()
        pending.clear()
        stamp(all, lockedRun)
        lockedRun = false
        // A group shows as its children; its summary only when it has none here.
        val grouped = all.filter { !it.summary && it.group != null }.mapNotNull { it.group }.toSet()
        val shown = all.filter { !(it.summary && it.group in grouped) }
        // Every notification but the focus ones in one island, the newest in front.
        val members = shown.filter { !it.focus }.sortedWith(bigFirst)
        stackMembers = members.map { it.key }
        stackNote = aggregate(members)
        val next = (shown.filter { it.focus && it.key !in released } +
            listOfNotNull(stackNote?.takeIf { !stackOut })).sortedWith(bigFirst)
        // Unchanged is the same reading (read() hands back the cached note): by key and time, a
        // stopwatch paused or resumed - its `when` the same - never reached the island.
        if (next.size == notes.size && next.indices.all { next[it] === notes[it] }) return
        notes = next
        main.post { listeners.forEach { runCatching { it() } } }
    }

    /**
     * Each island's last reading, released ones included, for [rankOf]. Its time is the note's
     * own ([Note.since]): stamped here at first sight it was wiped by every unlock - the
     * unlocked runs read nothing - and on the next lock screen every island had the same time,
     * a tie compareState gives to whichever is asked about second (2026-09-25). A stopwatch is
     * not reposted every second either: its timerInfo counts by itself, and only start, pause
     * and lap update it. So the unlocked runs leave the readings be.
     */
    private val lastRead = HashMap<String, Note>()

    private fun stamp(all: List<Note>, locked: Boolean) {
        for (note in all) lastRead[note.key] = note
        if (locked) {
            val keys = all.mapTo(HashSet()) { it.key }
            lastRead.keys.retainAll(keys)
        }
    }

    /**
     * The stack island: its newest notification's picture, title and line, with how many there
     * are when more than one. The same object while its notifications are the same readings, so
     * an unchanged run changes nothing (commit).
     */
    private fun aggregate(members: List<Note>): Note? {
        if (members.isEmpty()) {
            stackFrom = emptyList()
            return null
        }
        val cached = stackNote
        if (cached != null && members.size == stackFrom.size && members.indices.all { members[it] === stackFrom[it] }) {
            return cached
        }
        stackFrom = members
        val lead = members.first()
        val n = members.size
        return Note(STACK_KEY, lead.pkg, lead.title,
            if (n > 1) "$n 条通知 · ${lead.text}" else lead.text,
            lead.icon, focus = false, time = lead.time, intent = lead.intent, group = null,
            summary = false, redacted = lead.redacted, since = members.maxOf { it.since }, iconFrom = lead.iconFrom)
    }

    /** A notification island's standing, released or not; null for one this lock screen has not got. */
    fun rankOf(key: String): Rank? {
        val note = (if (key == STACK_KEY) stackNote else lastRead[key]) ?: return null
        return Rank(note.property, note.priority, note.since)
    }

    /**
     * The super island's compareState (StateHandler): whether [origin] takes the place [cur]
     * holds - the big island's when [big], the small one's when not. Read from the plugin's own
     * code (2026-09-25): islandProperty first, the big place going to 1 over 2 and the small one
     * to 2 over 1; then the lower islandPriority; then the newer island.
     */
    fun takesPlace(cur: Rank, origin: Rank, big: Boolean): Boolean {
        if (cur.property != origin.property) {
            return if (big) origin.property == 1 && cur.property == 2
            else cur.property == 1 && origin.property == 2
        }
        if (cur.priority != origin.priority) return cur.priority > origin.priority
        return cur.time <= origin.time
    }

    /** The big place's order: the island that would take it from every other first. */
    private val bigFirst = Comparator<Note> { a, b ->
        val ra = rankOf(a.key) ?: return@Comparator 1
        val rb = rankOf(b.key) ?: return@Comparator -1
        when {
            ra.property != rb.property -> if (ra.property == 1) -1 else if (rb.property == 1) 1 else 0
            ra.priority != rb.priority -> ra.priority.compareTo(rb.priority)
            else -> rb.time.compareTo(ra.time)
        }
    }

    /** A notification as an island, or null for one that is not: the media card's own. */
    private fun read(entry: Any, redacted: Boolean): Note? {
        val sbn = Xp.getObjectField(entry, "mSbn") as? StatusBarNotification ?: return null
        val n = sbn.notification ?: return null
        val stamp = (sbn.postTime * 31 + n.`when`) * 2 + if (redacted) 1 else 0
        readCache[sbn.key]?.let { (at, note) -> if (at == stamp) return note }
        return readFresh(sbn, n, redacted, createdAt(entry, sbn), entry)?.also {
            if (readCache.size > 200) readCache.clear()
            readCache[sbn.key] = stamp to it
        }
    }

    /**
     * The entry's creation as wall clock. This HyperOS build has no getCreationTime(); the
     * field is looked up by name, creationTime or mCreationTime, and holds elapsedRealtime (read
     * as uptime it was hours in the future, the time the phone slept). Without it the
     * notification's latest post stands in.
     */
    private fun createdAt(entry: Any, sbn: StatusBarNotification): Long {
        val field = creationField ?: if (creationUnreadable) null else findCreationField(entry.javaClass)
        val elapsed = field?.let { runCatching { it.getLong(entry) }.getOrNull() }
        if (elapsed == null || elapsed <= 0L) {
            creationUnreadable = true
            return sbn.postTime
        }
        return System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - elapsed)
    }

    private fun findCreationField(start: Class<*>): java.lang.reflect.Field? {
        var c: Class<*>? = start
        while (c != null && c != Any::class.java) {
            c.declaredFields.firstOrNull {
                it.type == Long::class.javaPrimitiveType &&
                    (it.name == "creationTime" || it.name == "mCreationTime")
            }?.let { it.isAccessible = true; creationField = it; return it }
            c = c.superclass
        }
        creationUnreadable = true
        Xp.log("MCIsland: no creation time on ${start.name}, islands rank by their latest post")
        return null
    }

    private var creationField: java.lang.reflect.Field? = null
    private var creationUnreadable = false

    private fun readFresh(sbn: StatusBarNotification, n: Notification, redacted: Boolean,
                          created: Long, entry: Any): Note? {
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null
        // Redacted, the row is built from the public version, or from nothing but the app.
        val shown = if (redacted) n.publicVersion else n
        val extras = shown?.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE_BIG)
            ?: extras?.getCharSequence(Notification.EXTRA_TITLE)
            ?: if (redacted) appLabel(sbn.packageName) else ""
        val text = (if (redacted) null else extras?.let(::lastMessage))
            ?: extras?.getCharSequence(Notification.EXTRA_TEXT)
            ?: (if (redacted) null else extras?.getCharSequence(Notification.EXTRA_BIG_TEXT))
            ?: if (redacted) hiddenText() else ""
        val focus = runCatching { focusCheck?.invoke(null, n) as? Boolean }.getOrNull() == true
        val island = islandParams(n)
        val order = island?.optBoolean("islandOrder", false) ?: false
        // Shown in full, a focus notification's row is its template, not its title and text:
        // the island shows what the row does, so the landing is one and the same.
        val template = if (focus && !redacted) focusTemplate(n) ?: rowTemplate(entry) else null
        val picture = template?.let { focusPicture(n, sbn, entry, it.pics + islandPics(island)) }
        val icon = picture?.icon ?: iconOf(sbn, shown ?: n, redacted)
        val iconFrom = picture?.from ?: lastIconFrom
        val round = roundIcon(icon)
        return Note(
            key = sbn.key,
            pkg = sbn.packageName,
            title = template?.title?.takeIf { it.isNotEmpty() || template.timer != null } ?: title,
            text = template?.text ?: text,
            icon = round ?: icon,
            iconFrom = if (round != null) "$iconFrom/round" else iconFrom,
            timer = template?.timer,
            buttons = if (template != null) focusButtons(n, sbn, entry) else emptyList(),
            anim = picture?.anim,
            live = picture?.live,
            focus = focus,
            time = if (n.`when` > 0) n.`when` else sbn.postTime,
            intent = n.contentIntent,
            group = if (sbn.isGroup) sbn.groupKey else null,
            summary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            redacted = redacted,
            property = island?.optInt("islandProperty", 1) ?: 1,
            priority = island?.optInt("islandPriority", 1) ?: 1,
            order = order,
            // A restart of SystemUI makes every entry anew at once, all with one creation time:
            // one posted before it counts from its post.
            since = if (order) maxOf(created, sbn.postTime) else minOf(created, sbn.postTime),
        )
    }

    /**
     * [d] cut to the small island's circle when it is a picture that fills its box - an app's
     * adaptive icon (this build's icon mask is a square: the pickup-code notification's came
     * out a rectangle beside QQ's round one, 2026-09-26), a photo, an avatar; null for one
     * left whole, as the super island leaves it - a glyph (the stopwatch, an arrow), a picture
     * already round, anything the circle would cut into its see-through edges. Filled is read
     * off the picture: the middles of its four edges opaque. A picture not square is cut from
     * its middle.
     */
    private fun roundIcon(d: Drawable?): Drawable? = runCatching {
        if (d == null) return null
        val res = (Main.sAppCtx ?: return null).resources
        val side = (48 * res.displayMetrics.density).toInt()
        val src = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
        val w = d.intrinsicWidth
        val h = d.intrinsicHeight
        // Its bounds put back: the drawable may be one SystemUI draws elsewhere too.
        val old = d.copyBounds()
        if (w > 0 && h > 0 && w != h) {
            val k = side.toFloat() / minOf(w, h)
            val dw = (w * k).toInt()
            val dh = (h * k).toInt()
            d.setBounds((side - dw) / 2, (side - dh) / 2, (side + dw) / 2, (side + dh) / 2)
        } else {
            d.setBounds(0, 0, side, side)
        }
        d.draw(android.graphics.Canvas(src))
        d.bounds = old
        val m = side / 2
        val e = (side * 0.04f).toInt().coerceAtLeast(1)
        val filled = listOf(m to e, m to side - 1 - e, e to m, side - 1 - e to m)
            .all { (x, y) -> android.graphics.Color.alpha(src.getPixel(x, y)) >= 200 }
        if (!filled) {
            src.recycle()
            return null
        }
        val out = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawCircle(m.toFloat(), m.toFloat(), m.toFloat(),
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP)
            })
        src.recycle()
        android.graphics.drawable.BitmapDrawable(res, out)
    }.getOrNull()

    /** [pics]: the pictures its first area names, in the order the plugin would try them. */
    private class Template(val title: String, val text: String, val timer: Timer?, val pics: List<Pic>)

    /**
     * A picture a focus notification names, and how the plugin draws a name of its [kind]:
     * [BUNDLE] one of the notification's own (miui.focus.pics, else FocusIconCache); [LOTTIE]
     * one of the plugin's Lottie files (LottieResUtils), its still (StaticResUtils) when it does
     * not move; [SHADER] the island's shader icon (IslandIconViewHolder.showShaderIcon), a
     * plugin drawable for the names it knows, else one of the notification's own; [VIDEO] and
     * [FLASH] no picture but a view of the plugin's (a [Live]).
     */
    private class Pic(val kind: Int, val name: String, val number: Int = 0, val autoplay: Boolean = false,
                      val loop: Boolean = true) {
        override fun toString() = "${KINDS[kind]}:$name"

        companion object {
            const val BUNDLE = 0
            const val LOTTIE = 1
            const val SHADER = 2
            const val VIDEO = 3
            const val FLASH = 4
            val KINDS = arrayOf("pic", "lottie", "shader", "video", "flash")
        }
    }

    /**
     * A picture that is one of the plugin's own views, running itself once it is in a window:
     * [FLASH] the torch's light (FlashLightView, a RuntimeShader), [VIDEO] a clip by its name
     * (TextureVideoView, VideoResUtils), looped when [loop]. Each place that shows it makes its
     * own (MiniPlayerRuntime.liveArt): a view has one parent.
     */
    class Live(val kind: String, val src: String, val loop: Boolean, val row: WeakReference<View?>) {
        val id get() = "$kind#$src#$loop"

        companion object {
            const val FLASH = "flash"
            const val VIDEO = "video"
        }
    }

    /** What the pill shows for a focus notification, and where it came from (for `op mini`). */
    private class Picture(val icon: Drawable?, val anim: Anim?, val live: Live?, val from: String)

    /**
     * What a focus notification's row shows in its first area, read as the plugin's
     * TemplateFactoryV3.chooseModule picks it: animTextInfo, else coverInfo, iconTextInfo,
     * baseInfo, highlightInfo, chatInfo. Each has a title and content; a timerInfo takes the
     * title's line (the animation text module's chronometer, focus_title gone). Null for a
     * notification with no param_v2. One that draws its own row (miui.focus.rv) is read from
     * miui.focus.param.custom ([customTemplate]).
     */
    private fun focusTemplate(n: Notification): Template? = runCatching {
        val raw = n.extras.getString("miui.focus.param") ?: return customTemplate(n)
        val root = org.json.JSONObject(raw)
        fun str(o: org.json.JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k, "")
        fun timerOf(t: org.json.JSONObject?): Timer? = t?.let {
            val type = it.optInt("timerType", 0)
            if (type == 0) null else Timer(type,
                it.optLong("timerWhen", System.currentTimeMillis()),
                it.optLong("timerSystemCurrent", System.currentTimeMillis()),
                it.optLong("timerTotal", 0L))
        }
        // The notification's own pictures by these names, dark first as the lock screen draws.
        fun named(o: org.json.JSONObject, vararg keys: String) =
            keys.map { str(o, it) }.filter { it.isNotEmpty() }.map { Pic(Pic.BUNDLE, it) }
        // What it gives the status bar and the AOD: a last resort before the row's picture.
        fun ticker(o: org.json.JSONObject) = named(o, "tickerPicDark", "tickerPic", "aodPic")
        // The flat template, the first protocol's and a param_v2 with no first-area module
        // (a navigation's "直行98米 / 高德导航中"): its title, content and timer at the top, its
        // picture the one it gives the status bar.
        fun flat(o: org.json.JSONObject): Template? {
            val title = str(o, "title")
            val content = str(o, "content")
            val timer = timerOf(o)
            if (title.isEmpty() && content.isEmpty() && timer == null) return null
            return Template(title, content, timer, ticker(o) + named(o, "picFunction"))
        }
        val v2 = root.optJSONObject("param_v2") ?: return flat(root)
        val area = FOCUS_AREA_A.firstOrNull { v2.optJSONObject(it) != null } ?: return flat(v2) ?: flat(root)
        val info = v2.getJSONObject(area)
        val timer = timerOf(info.optJSONObject("timerInfo"))
        // Each first-area module's own picture, as its view holder (moduleV3) reads it.
        val icon = info.optJSONObject("animIconInfo")
        val pics = when (area) {
            // ModuleAnimationTextViewHolder: a plugin Lottie by src, its still when it has none.
            "animTextInfo" -> icon?.let { listOf(Pic(Pic.LOTTIE, str(it, "src"), it.optInt("number", 0),
                it.optBoolean("autoplay", false))) }.orEmpty()
            "coverInfo" -> named(info, "picCover")
            // ModuleNewImageTextViewHolder.showEffects: type 1 plays a looped video by src, type 2
            // is the torch's light (FlashLightView); else one of its own pictures.
            "iconTextInfo" -> when (icon?.optInt("type", 0)) {
                null -> emptyList()
                1 -> listOf(Pic(Pic.VIDEO, str(icon, "src")))
                2 -> listOf(Pic(Pic.FLASH, "light"))
                else -> named(icon, "srcDark", "src")
            }
            "highlightInfo" -> named(info, "picFunctionDark", "picFunction")
            "chatInfo" -> named(info, "picProfileDark", "picProfile")
            else -> named(info, "picFunction")
        }.filter { it.name.isNotEmpty() }
        Template(str(info, "title"), str(info, "content").ifEmpty { str(info, "subContent") }, timer,
            pics + ticker(v2))
    }.getOrNull()

    /**
     * A focus notification that draws its own row (miui.focus.rv, the assistant's train and
     * flight cards): its layout is the app's, but what it gives its super island
     * (miui.focus.param.custom's param_island) is the same card said in a pill's words - the
     * train number, then its state ("检票中", "检票口 A5"). Its texts in the big island's
     * order, left, middle, right, the first the title; else what it gives the AOD (aodTitle).
     * Its picture the island's, then the AOD's. Null with neither, and the row is read.
     */
    private fun customTemplate(n: Notification): Template? = runCatching {
        val root = org.json.JSONObject(n.extras.getString("miui.focus.param.custom") ?: return null)
        fun str(o: org.json.JSONObject?, k: String) = if (o == null || o.isNull(k)) "" else o.optString(k, "")
        val island = root.optJSONObject("param_island")
        val big = island?.optJSONObject("bigIslandArea")
        val lines = ArrayList<String>()
        fun text(t: org.json.JSONObject?) {
            if (t == null) return
            lines += listOf(str(t, "frontTitle"), str(t, "title")).filter { it.isNotEmpty() }.joinToString(" ")
            lines += str(t, "content")
        }
        // IslandTextViewHolder and the digit modules: frontTitle and title on one line, content after.
        text(big?.optJSONObject("imageTextInfoLeft")?.optJSONObject("textInfo"))
        text(big?.optJSONObject("textInfo"))
        text(big?.optJSONObject("progressTextInfo")?.optJSONObject("textInfo"))
        for (k in listOf("sameWidthDigitInfo", "fixedWidthDigitInfo")) big?.optJSONObject(k)?.let {
            lines += str(it, "digit")
            lines += str(it, "content")
        }
        text(big?.optJSONObject("imageTextInfoRight")?.optJSONObject("textInfo"))
        lines.removeAll { it.isBlank() }
        if (lines.isEmpty()) str(root, "aodTitle").takeIf { it.isNotEmpty() }?.let { lines += it }
        if (lines.isEmpty()) return null
        val pics = listOf("tickerPicDark", "tickerPic", "aodPic").map { str(root, it) }
            .filter { it.isNotEmpty() }.map { Pic(Pic.BUNDLE, it) }
        Template(lines[0], lines.drop(1).joinToString(" "), null, islandPics(island) + pics)
    }.getOrNull()

    /**
     * The pictures a focus notification gives its super island (param_island), as
     * IslandIconViewHolder.bind reads a picInfo's type: 1, 4 and 5 one of its own, 2 and 7 a
     * plugin Lottie, 3 a shader icon, 6 a video (setMP4Icon). The big island's left picture first,
     * the one beside its text as the pill's is, then its only picture, the small island's, and
     * its right one.
     */
    private fun islandPics(island: org.json.JSONObject?): List<Pic> {
        if (island == null) return emptyList()
        val big = island.optJSONObject("bigIslandArea")
        return listOfNotNull(
            big?.optJSONObject("imageTextInfoLeft")?.optJSONObject("picInfo"),
            big?.optJSONObject("picInfo"),
            island.optJSONObject("smallIslandArea")?.optJSONObject("picInfo"),
            big?.optJSONObject("imageTextInfoRight")?.optJSONObject("picInfo"),
        ).mapNotNull { p ->
            val name = if (p.isNull("pic")) "" else p.optString("pic", "")
            if (name.isEmpty()) return@mapNotNull null
            when (p.optInt("type", 0)) {
                1, 4, 5 -> Pic(Pic.BUNDLE, name)
                // Played unless it says autoplay false (IslandIconViewHolder.playAnimation).
                2, 7 -> Pic(Pic.LOTTIE, name, p.optInt("number", -1), p.optBoolean("autoplay", true))
                3 -> Pic(Pic.SHADER, name)
                6 -> Pic(Pic.VIDEO, name, loop = p.optBoolean("loop", false))
                else -> null
            }
        }
    }

    /**
     * The picture for the pill: the first of [pics] that draws, and on the way the first that
     * lives - a plugin view, else a Lottie - which is shown in its place, the still kept for
     * whatever needs a picture (a flight's copy, the thumbnail). Past them, the picture the row
     * draws. Null with none, and the app's icon stands in.
     */
    private fun focusPicture(n: Notification, sbn: StatusBarNotification, entry: Any, pics: List<Pic>): Picture? {
        val row = runCatching { Xp.getObjectField(entry, "row") as? View }.getOrNull()
        val plugin = pluginOf(row)
        var anim: Anim? = null
        var live: Live? = null
        val from = ArrayList<String>()
        for (p in pics) {
            when (p.kind) {
                Pic.VIDEO -> if (live == null && anim == null && p.name.isNotEmpty()) {
                    live = Live(Live.VIDEO, p.name, p.loop, WeakReference(row))
                    from += p.toString()
                }
                Pic.FLASH -> if (live == null && anim == null) {
                    live = Live(Live.FLASH, p.name, true, WeakReference(row))
                    from += p.toString()
                }
                Pic.LOTTIE -> if (live == null && anim == null && row != null && plugin != null &&
                    runCatching { plugin.call(LOTTIE_RES, "getLottieRes", p.name, p.number) as Int }.getOrDefault(-1) > 0) {
                    anim = Anim(p.name, p.number, p.autoplay, WeakReference(row))
                    from += p.toString()
                }
            }
            val still = runCatching { still(p, n, sbn, plugin) }.getOrNull() ?: continue
            if (from.lastOrNull() != p.toString()) from += p.toString()
            return Picture(still, anim, live, from.joinToString("+"))
        }
        runCatching { rowIcon(entry) }.getOrNull()?.let { return Picture(it, anim, live, (from + "row").joinToString("+")) }
        return if (anim != null || live != null) Picture(null, anim, live, from.joinToString("+")) else null
    }

    /** [p] as a still, or null when it does not draw here. */
    private fun still(p: Pic, n: Notification, sbn: StatusBarNotification, plugin: Plugin?): Drawable? = when (p.kind) {
        Pic.LOTTIE -> plugin?.let { pl ->
            (pl.call(STATIC_RES, "getStaticRes", p.name) as Int).takeIf { it > 0 }?.let { pl.ctx.getDrawable(it) }
        }
        Pic.VIDEO, Pic.FLASH -> null
        Pic.SHADER -> SHADER_PICTURES[p.name]?.let { plugin?.drawable(it) } ?: ownPicture(n, sbn, p.name, plugin)
        else -> ownPicture(n, sbn, p.name, plugin)
    }

    /**
     * One of the notification's own pictures by name: in its miui.focus.pics, else where the
     * plugin keeps those an update left out (ModuleViewHolder.getIcon, FocusIconCache).
     */
    private fun ownPicture(n: Notification, sbn: StatusBarNotification, name: String, plugin: Plugin?): Drawable? {
        val ctx: Context = Main.sAppCtx ?: return null
        @Suppress("DEPRECATION")
        val icon = n.extras.getBundle("miui.focus.pics")?.get(name) as? android.graphics.drawable.Icon
            ?: plugin?.let { runCatching { it.call(ICON_CACHE, "get", sbn.key, name) }.getOrNull() }
                as? android.graphics.drawable.Icon
            ?: return null
        return runCatching { icon.loadDrawable(ctx) }.getOrNull()
    }

    /** The shader icons' pictures, by the name the island gives them (showShaderIcon). */
    private val SHADER_PICTURES = mapOf("flash_light_icon" to "flash_light_icon", "call" to "call_icon")
    private const val LOTTIE_RES = "miui.systemui.util.LottieResUtils"
    private const val STATIC_RES = "miui.systemui.util.StaticResUtils"
    private const val ICON_CACHE = "miui.systemui.notification.focus.FocusIconCache"

    /** The plugin's context (its resources) and classes. */
    class Plugin(val ctx: Context, val loader: ClassLoader) {
        /** One of its Kotlin objects' methods, found by name and argument count. */
        fun call(cls: String, method: String, vararg args: Any?): Any? {
            val c = loader.loadClass(cls)
            val instance = runCatching { c.getField("INSTANCE").get(null) }.getOrNull()
            return c.methods.first { it.name == method && it.parameterTypes.size == args.size }.invoke(instance, *args)
        }

        fun drawable(name: String): Drawable? {
            val id = ctx.resources.getIdentifier(name, "drawable", PLUGIN)
            return if (id == 0) null else runCatching { ctx.getDrawable(id) }.getOrNull()
        }
    }

    private const val PLUGIN = "miui.systemui.plugin"

    /**
     * The plugin as [row] has it - a view of the plugin's in it, whose context and classes are
     * the running plugin's, its FocusIconCache the one it fills - else the plugin's package.
     */
    fun pluginOf(row: View?): Plugin? {
        // Not SystemUI's, not the framework's, not ours: the plugin's.
        val others = setOf(row?.javaClass?.classLoader, View::class.java.classLoader, LockIslands::class.java.classLoader)
        if (row != null) {
            val queue = ArrayDeque<View>()
            queue.add(row)
            while (queue.isNotEmpty()) {
                val v = queue.removeFirst()
                val loader = v.javaClass.classLoader
                if (loader != null && others.none { it === loader }) {
                    return Plugin(v.context, loader)
                }
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
            }
        }
        return pluginPackage?.let { Plugin(it, it.classLoader) }
    }

    private val pluginPackage: Context? by lazy {
        runCatching {
            Main.sAppCtx!!.createPackageContext(PLUGIN, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull()
    }

    private fun focusButtons(n: Notification, sbn: StatusBarNotification, entry: Any): List<Button> = runCatching {
        val root = org.json.JSONObject(n.extras.getString("miui.focus.param") ?: return emptyList())
        val list = root.optJSONObject("param_v2")?.optJSONArray("actions") ?: root.optJSONArray("actions")
        val bundle = n.extras.getBundle("miui.focus.actions")
        val pics = n.extras.getBundle("miui.focus.pics")
        val row = WeakReference(runCatching { Xp.getObjectField(entry, "row") as? View }.getOrNull())
        fun str(o: org.json.JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k, "")
        fun color(o: org.json.JSONObject, vararg keys: String): Int? =
            keys.map { str(o, it) }.firstOrNull { it.isNotEmpty() }
                ?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }
        @Suppress("DEPRECATION")
        fun picture(name: String?) = name?.let { pics?.get(it) as? android.graphics.drawable.Icon }
        val out = ArrayList<Button>()
        if (list != null) {
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val key = str(o, "action")
                @Suppress("DEPRECATION")
                val action = if (key.isEmpty()) null else bundle?.get(key) as? Notification.Action
                val uri = str(o, "actionIntent").ifEmpty { null }
                if (action == null && uri == null) continue
                val name = str(o, "actionIconDark").ifEmpty { str(o, "actionIcon") }
                    .ifEmpty { action?.extras?.getString("icon_name").orEmpty() }.ifEmpty { key }.ifEmpty { null }
                val ring = o.optJSONObject("progressInfo")?.let { pi ->
                    Progress(pi.optInt("progress", 0), pi.optBoolean("isAutoProgress", false),
                        pi.optBoolean("isCCW", false), color(pi, "colorProgressDark", "colorProgress"),
                        color(pi, "colorProgressEndDark", "colorProgressEnd"))
                }
                out += Button(i, name, str(o, "actionTitle").ifEmpty { null } ?: action?.title, action, uri,
                    o.optInt("actionIntentType", 1), color(o, "actionBgColorDark", "actionBgColor"),
                    color(o, "actionBgPressColorDark", "actionBgPressColor"), picture(name), row, sbn.postTime,
                    o.optInt("type", 0), color(o, "actionTitleColorDark", "actionTitleColor"), ring)
            }
        } else if (bundle != null) {
            // Protocol 1 names no buttons: its actions are the bundle's, in their keys' order.
            for (key in bundle.keySet().sorted()) {
                @Suppress("DEPRECATION")
                val action = bundle.get(key) as? Notification.Action ?: continue
                val name = action.extras?.getString("icon_name") ?: key
                out += Button(out.size, name, action.title, action, null, 1, null, null, picture(name), row, sbn.postTime)
            }
        }
        out
    }.getOrDefault(emptyList())

    private val FOCUS_AREA_A = listOf("animTextInfo", "coverInfo", "iconTextInfo", "baseInfo",
        "highlightInfo", "chatInfo")

    /**
     * The row's focus template picture view: a Lottie view or a still. Found by its name, not by
     * an id looked up in SystemUI - the ids are the plugin's own, and SystemUI has none of them.
     */
    fun focusIconView(row: View): android.widget.ImageView? {
        val queue = ArrayDeque<View>()
        queue.add(row)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v is android.widget.ImageView && v.id != View.NO_ID && v.visibility == View.VISIBLE) {
                val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
                if (name in FOCUS_ICON_NAMES) return v
            }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
        }
        return null
    }

    /** The picture the row's focus template shows now, drawn once into a bitmap of its own. */
    private fun rowIcon(entry: Any): Drawable? {
        val row = Xp.getObjectField(entry, "row") as? View ?: return null
        val view = focusIconView(row) ?: anyRowPicture(row) ?: return null
        val d = view.drawable ?: return null
        val side = maxOf(view.width, d.intrinsicWidth, 1).coerceAtMost(512)
        val bitmap = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
        val old = android.graphics.Rect(d.bounds)
        d.setBounds(0, 0, side, side)
        d.draw(android.graphics.Canvas(bitmap))
        d.bounds = old
        return android.graphics.drawable.BitmapDrawable(row.resources, bitmap)
    }

    private val FOCUS_ICON_NAMES = listOf("focus_animation", "focus_animation_static", "focus_icon",
        "focus_large_icon", "focus_small_icon")

    /**
     * Whatever the row's focus template shows when its parameters say nothing this reads: an
     * app's own layout, a protocol not known here. Its first two lines, buttons left out.
     */
    private fun rowTemplate(entry: Any): Template? = runCatching {
        val row = Xp.getObjectField(entry, "row") as? View ?: return null
        val lines = ArrayList<String>()
        val queue = ArrayDeque<View>()
        queue.add(row)
        while (queue.isNotEmpty() && lines.size < 2) {
            val v = queue.removeFirst()
            if (v.visibility != View.VISIBLE) continue
            val name = runCatching { if (v.id != View.NO_ID) v.resources.getResourceEntryName(v.id) else "" }.getOrDefault("")
            if (name.startsWith("focus_button") || name == "veto") continue
            if (v is android.widget.TextView && v !is android.widget.Button) {
                v.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { lines += it }
            }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
        }
        if (lines.isEmpty()) null else Template(lines[0], lines.getOrElse(1) { "" }, null, emptyList())
    }.getOrNull()

    /** Past the named ones, any picture the template shows: not a button's, not its backdrop. */
    private fun anyRowPicture(row: View): android.widget.ImageView? {
        val queue = ArrayDeque<View>()
        queue.add(row)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v.visibility != View.VISIBLE) continue
            val name = runCatching { if (v.id != View.NO_ID) v.resources.getResourceEntryName(v.id) else "" }.getOrDefault("")
            if (name.startsWith("focus_button") || name.endsWith("bg_image") || name == "veto") continue
            if (v is android.widget.ImageView && v.drawable != null && v.width > 0) return v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
        }
        return null
    }

    /**
     * The focus notification's param_island, where the plugin looks for it (FocusNotifUtils):
     * in miui.focus.param's param_v2 (a stopwatch's), at its top level, or in
     * miui.focus.param.custom. Null for a notification with none, which ranks as 1 and 1.
     */
    private fun islandParams(n: Notification): org.json.JSONObject? = runCatching {
        n.extras.getString("miui.focus.param")?.let { raw ->
            val root = org.json.JSONObject(raw)
            root.optJSONObject("param_v2")?.optJSONObject("param_island")?.let { return@runCatching it }
            root.optJSONObject("param_island")?.let { return@runCatching it }
        }
        n.extras.getString("miui.focus.param.custom")?.let {
            org.json.JSONObject(it).optJSONObject("param_island")
        }
    }.getOrNull()

    /** A messaging notification's newest line, which its plain text often is not. */
    private fun lastMessage(extras: android.os.Bundle): CharSequence? {
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val last = messages.lastOrNull() as? android.os.Bundle ?: return null
        return last.getCharSequence("text")
    }

    /**
     * The notification's own picture if it has one, else its app's icon. Redacted, only the
     * public version's own picture counts: the private one is a contact's face.
     */
    private fun iconOf(sbn: StatusBarNotification, n: Notification, redacted: Boolean): Drawable? {
        val ctx: Context = Main.sAppCtx ?: return null
        n.getLargeIcon()?.takeIf { !redacted || n !== sbn.notification }?.let { icon ->
            runCatching { icon.loadDrawable(ctx) }.getOrNull()?.let { lastIconFrom = "large"; return it }
        }
        rowAppIcon(sbn, ctx)?.let { lastIconFrom = "row-app"; return it }
        lastIconFrom = "app"
        return runCatching { ctx.packageManager.getApplicationIcon(sbn.packageName) }.getOrNull()
    }

    /** Where the last [iconOf] found its picture, for `op mini`. */
    private var lastIconFrom = "app"

    /**
     * The app icon the notification's own row shows, by SystemUI's own hand
     * (NotifImageUtil.applyAppIconAllowCustom, what every Miui row wrapper binds its app_icon
     * with), drawn into a view of ours: a system sender's own picture (miui.appIcon, for the
     * senders on SystemUI's config_canCustomNotificationAppIcon - the hotspot's, where "android"
     * as an app is a blank robot), the entry's app icon, a dual app's badge, the small icon last.
     */
    private fun rowAppIcon(sbn: StatusBarNotification, ctx: Context): Drawable? = runCatching {
        val util = appIconUtil ?: sbn.javaClass.classLoader!!.loadClass(NOTIF_IMAGE_UTIL)
            .methods.first { it.name == "applyAppIconAllowCustom" && it.parameterTypes.size == 4 }
            .also { appIconUtil = it }
        val view = android.widget.ImageView(ctx)
        util.invoke(null, ctx, sbn, view, true)
        view.drawable
    }.onFailure {
        if (!appIconUnreadable) {
            appIconUnreadable = true
            Xp.log("MCIsland: row app icon unreadable: $it")
        }
    }.getOrNull()

    private var appIconUtil: java.lang.reflect.Method? = null
    private var appIconUnreadable = false
    private const val NOTIF_IMAGE_UTIL = "com.android.systemui.statusbar.notification.utils.NotifImageUtil"

    private fun appLabel(pkg: String): CharSequence = runCatching {
        val pm = Main.sAppCtx!!.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))
    }.getOrDefault(pkg)

    /**
     * The line a redacted row shows for its content: SystemUI's own notification_hidden_text
     * ("你有一条新消息"), not the framework's of the same name ("新通知") - taking the framework's,
     * the island said one thing and the row it opened into another (filmed 2026-09-25).
     */
    private fun hiddenText(): CharSequence {
        val ctx = Main.sAppCtx
        for ((res, pkg) in listOfNotNull(ctx?.let { it.resources to it.packageName },
            android.content.res.Resources.getSystem() to "android")) {
            val id = res.getIdentifier("notification_hidden_text", "string", pkg)
            if (id != 0) runCatching { return res.getText(id) }
        }
        return ""
    }
}

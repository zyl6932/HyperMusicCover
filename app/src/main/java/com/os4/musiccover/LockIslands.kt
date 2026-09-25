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
        /** The focus template's buttons, in the row's order: the last is its main one. */
        val buttons: List<Button> = emptyList(),
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
        "released=${released.size} notes=" + notes.joinToString(",") {
            (if (it.focus) "F:" else "") + (if (it.redacted) "R:" else "") + it.pkg +
                "[${it.property}/${it.priority}${if (it.order) "/o" else ""} " +
                "t=-${(System.currentTimeMillis() - it.since) / 1000}s]" +
                (if (it.focus && !it.redacted) "{${it.timer?.let { t -> "timer ${t.type} ${t.text()} " } ?: ""}" +
                    "'${it.title.take(20)}'/'${it.text.take(20)}' icon=${it.icon?.javaClass?.simpleName} " +
                    "anim=${it.anim?.let { a -> "${a.src}/${a.autoplay}/row=${a.row.get() != null}" }} " +
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
        if (released.add(key)) invalidate("island $key released")
    } finally { android.os.Trace.endSection() } }

    /** A notification put back in the stack comes back into the row: collapsed into it. */
    fun recapture(key: String) { android.os.Trace.beginSection("MC li.recapture"); try {
        if (released.remove(key)) invalidate("island $key recaptured")
    } finally { android.os.Trace.endSection() } }

    fun isReleased(key: String): Boolean = key in released

    fun releasedKeys(): List<String> = released.toList()

    /** A notification as last read, released or not: what a row collapsing back will show. */
    fun noteFor(key: String): Note? = readCache[key]?.second

    /** The lock screen went away: every notification is an island again next time. */
    fun resetReleased() {
        if (released.isEmpty()) return
        released.clear()
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

    /** One filter call: records the candidate, and filters it out if the row has it. */
    private fun consider(filterObject: Any, args: List<Any?>, hidden: Boolean): Boolean {
        if (filter?.get() !== filterObject) filter = WeakReference(filterObject)
        if (hidden) return true
        val entry = args.firstOrNull() ?: return false
        if (!lockedOrLocking(filterObject)) {
            // Unlocked: what a tap put back is an island again on the next lock screen.
            released.clear()
            // Unlocking, the keyguard is "not locked" from the first frame of its fade-out,
            // while the row is still drawn on it: letting the islands go then put every one
            // of them in the fading stack at once - a flash of rows on each unlock (filmed
            // 2026-09-25). They stay islands until the lock screen is off the screen; the row
            // turning off after that runs the pipeline again (setActive).
            if (!active || !Main.onKeyguardNow()) return false
            val key = (Xp.getObjectField(entry, "mSbn") as? StatusBarNotification)?.key
            val held = key != null && notes.any { it.key == key }
            if (held) watchUnlock()
            return held
        }
        lockedRun = true
        val note = read(entry, redacted(filterObject, entry)) ?: return false
        pending[note.key] = note
        return active && note.key !in released || cover && note.focus && note.key !in released
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
        val next = all.filter { !(it.summary && it.group in grouped) && it.key !in released }
            .sortedWith(bigFirst)
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

    /** A notification island's standing, released or not; null for one this lock screen has not got. */
    fun rankOf(key: String): Rank? {
        val note = lastRead[key] ?: return null
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
        return Note(
            key = sbn.key,
            pkg = sbn.packageName,
            title = template?.title?.takeIf { it.isNotEmpty() || template.timer != null } ?: title,
            text = template?.text ?: text,
            icon = template?.let { focusIcon(n, it.pic, entry) } ?: iconOf(sbn, shown ?: n, redacted),
            timer = template?.timer,
            buttons = if (template != null) focusButtons(n, sbn, entry) else emptyList(),
            anim = template?.animIcon?.let { a ->
                val src = if (a.isNull("src")) "" else a.optString("src", "")
                val row = runCatching { Xp.getObjectField(entry, "row") as? View }.getOrNull()
                if (src.isEmpty() || row == null) null
                else Anim(src, a.optInt("number", 0), a.optBoolean("autoplay", false), WeakReference(row))
            },
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

    private class Template(val title: String, val text: String, val timer: Timer?, val pic: String?,
                           val animIcon: org.json.JSONObject?)

    /**
     * What a focus notification's row shows in its first area, read as the plugin's
     * TemplateFactoryV3.chooseModule picks it: animTextInfo, else coverInfo, iconTextInfo,
     * baseInfo, highlightInfo, chatInfo. Each has a title and content; a timerInfo takes the
     * title's line (the animation text module's chronometer, focus_title gone). Null for a
     * notification with no param_v2.
     */
    private fun focusTemplate(n: Notification): Template? = runCatching {
        val raw = n.extras.getString("miui.focus.param") ?: return null
        val root = org.json.JSONObject(raw)
        fun str(o: org.json.JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k, "")
        fun timerOf(t: org.json.JSONObject?): Timer? = t?.let {
            val type = it.optInt("timerType", 0)
            if (type == 0) null else Timer(type,
                it.optLong("timerWhen", System.currentTimeMillis()),
                it.optLong("timerSystemCurrent", System.currentTimeMillis()),
                it.optLong("timerTotal", 0L))
        }
        // The flat template, the first protocol's and a param_v2 with no first-area module
        // (a navigation's "直行98米 / 高德导航中"): its title, content and timer at the top, its
        // picture the one it gives the status bar, dark first as the lock screen draws it.
        fun flat(o: org.json.JSONObject): Template? {
            val title = str(o, "title")
            val content = str(o, "content")
            val timer = timerOf(o)
            if (title.isEmpty() && content.isEmpty() && timer == null) return null
            val pic = listOf("tickerPicDark", "tickerPic", "picFunction", "aodPic").map { str(o, it) }
                .firstOrNull { it.isNotEmpty() }
            return Template(title, content, timer, pic, null)
        }
        val v2 = root.optJSONObject("param_v2") ?: return flat(root)
        val info = FOCUS_AREA_A.firstNotNullOfOrNull { v2.optJSONObject(it) } ?: return flat(v2) ?: flat(root)
        val timer = timerOf(info.optJSONObject("timerInfo"))
        val pic = info.optJSONObject("animIconInfo")?.let { str(it, "src") }?.ifEmpty { null }
            ?: listOf("picFunction", "picCover", "picProfile").map { str(info, it) }.firstOrNull { it.isNotEmpty() }
        Template(str(info, "title"), str(info, "content").ifEmpty { str(info, "subContent") }, timer, pic,
            info.optJSONObject("animIconInfo"))
    }.getOrNull()

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
     * The template's picture: from the notification's own miui.focus.pics when it is there,
     * else as the row draws it - a plugin built-in (a stopwatch's Lottie) is only in the row -
     * else null, and the app's icon stands in.
     */
    private fun focusIcon(n: Notification, pic: String?, entry: Any): Drawable? {
        val ctx: Context = Main.sAppCtx ?: return null
        if (pic != null) runCatching {
            @Suppress("DEPRECATION")
            (n.extras.getBundle("miui.focus.pics")?.get(pic) as? android.graphics.drawable.Icon)
                ?.loadDrawable(ctx)
        }.getOrNull()?.let { return it }
        return runCatching { rowIcon(entry) }.getOrNull()
    }

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
        if (lines.isEmpty()) null else Template(lines[0], lines.getOrElse(1) { "" }, null, null, null)
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
            runCatching { icon.loadDrawable(ctx) }.getOrNull()?.let { return it }
        }
        return runCatching { ctx.packageManager.getApplicationIcon(sbn.packageName) }.getOrNull()
    }

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

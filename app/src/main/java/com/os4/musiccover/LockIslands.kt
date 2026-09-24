package com.os4.musiccover

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
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
    )

    /** This lock screen's islands, focus notifications first, then newest first. */
    @Volatile var notes: List<Note> = emptyList()
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var filter: WeakReference<Any>? = null
    private var active = false
    private var focusCheck: java.lang.reflect.Method? = null

    /** Candidates of the pipeline run in progress, in the order the filter saw them. */
    private val pending = LinkedHashMap<String, Note>()

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
    fun describe(): String = "islands active=$active filter=${filter?.get() != null} " +
        "released=${released.size} notes=" + notes.joinToString(",") {
            (if (it.focus) "F:" else "") + (if (it.redacted) "R:" else "") + it.pkg
        }

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
        invalidate("row ${if (value) "showing" else "gone"}")
    }

    /** A tapped island goes back into the stack, for the rest of this lock screen. */
    fun release(key: String) {
        if (released.add(key)) invalidate("island $key released")
    }

    /** A notification put back in the stack comes back into the row: collapsed into it. */
    fun recapture(key: String) {
        if (released.remove(key)) invalidate("island $key recaptured")
    }

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
        runCatching { Xp.callMethod(f, "invalidateList", "MusicCover: $reason") }
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
        val note = read(entry, redacted(filterObject, entry)) ?: return false
        pending[note.key] = note
        return active && note.key !in released
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
        // A group shows as its children; its summary only when it has none here.
        val grouped = all.filter { !it.summary && it.group != null }.mapNotNull { it.group }.toSet()
        val next = all.filter { !(it.summary && it.group in grouped) && it.key !in released }
            .sortedWith(compareByDescending<Note> { it.focus }.thenByDescending { it.time })
        if (next.map { it.key to it.time } == notes.map { it.key to it.time }) return
        notes = next
        main.post { listeners.forEach { runCatching { it() } } }
    }

    /** A notification as an island, or null for one that is not: the media card's own. */
    private fun read(entry: Any, redacted: Boolean): Note? {
        val sbn = Xp.getObjectField(entry, "mSbn") as? StatusBarNotification ?: return null
        val n = sbn.notification ?: return null
        val stamp = (sbn.postTime * 31 + n.`when`) * 2 + if (redacted) 1 else 0
        readCache[sbn.key]?.let { (at, note) -> if (at == stamp) return note }
        return readFresh(sbn, n, redacted)?.also {
            if (readCache.size > 200) readCache.clear()
            readCache[sbn.key] = stamp to it
        }
    }

    private fun readFresh(sbn: StatusBarNotification, n: Notification, redacted: Boolean): Note? {
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
        return Note(
            key = sbn.key,
            pkg = sbn.packageName,
            title = title,
            text = text,
            icon = iconOf(sbn, shown ?: n, redacted),
            focus = focus,
            time = if (n.`when` > 0) n.`when` else sbn.postTime,
            intent = n.contentIntent,
            group = if (sbn.isGroup) sbn.groupKey else null,
            summary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            redacted = redacted,
        )
    }

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

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
            (if (it.focus) "F:" else "") + it.pkg
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
            return false
        }
        val note = read(entry) ?: return false
        pending[note.key] = note
        return active && note.key !in released
    }

    /** The provider's own reading of the keyguard: showing, or about to be. */
    private fun lockedOrLocking(filterObject: Any): Boolean = runCatching {
        val found = provider?.get() ?: Xp.getObjectField(
            Xp.getObjectField(filterObject, "this\$0"), "keyguardNotificationVisibilityProvider")
            .also { provider = WeakReference(it) }
        Xp.callMethod(found, "isLockedOrLocking") as Boolean
    }.getOrElse { Main.keyguardLocked() }

    private var provider: WeakReference<Any>? = null

    /**
     * Read notes by key, kept while the notification is unchanged: the filter runs for every
     * notification on every pipeline run, and loading an icon each time is not free.
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
    private fun read(entry: Any): Note? {
        val sbn = Xp.getObjectField(entry, "mSbn") as? StatusBarNotification ?: return null
        val n = sbn.notification ?: return null
        val stamp = sbn.postTime * 31 + n.`when`
        readCache[sbn.key]?.let { (at, note) -> if (at == stamp) return note }
        return readFresh(sbn, n)?.also {
            if (readCache.size > 200) readCache.clear()
            readCache[sbn.key] = stamp to it
        }
    }

    private fun readFresh(sbn: StatusBarNotification, n: Notification): Note? {
        val extras = n.extras
        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: ""
        val text = lastMessage(extras)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ""
        val focus = runCatching { focusCheck?.invoke(null, n) as? Boolean }.getOrNull() == true
        return Note(
            key = sbn.key,
            pkg = sbn.packageName,
            title = title,
            text = text,
            icon = iconOf(sbn, n),
            focus = focus,
            time = if (n.`when` > 0) n.`when` else sbn.postTime,
            intent = n.contentIntent,
            group = if (sbn.isGroup) sbn.groupKey else null,
            summary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        )
    }

    /** A messaging notification's newest line, which its plain text often is not. */
    private fun lastMessage(extras: android.os.Bundle): CharSequence? {
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val last = messages.lastOrNull() as? android.os.Bundle ?: return null
        return last.getCharSequence("text")
    }

    /** The notification's own picture if it has one, else its app's icon. */
    private fun iconOf(sbn: StatusBarNotification, n: Notification): Drawable? {
        val ctx: Context = Main.sAppCtx ?: return null
        n.getLargeIcon()?.let { icon ->
            runCatching { icon.loadDrawable(ctx) }.getOrNull()?.let { return it }
        }
        return runCatching { ctx.packageManager.getApplicationIcon(sbn.packageName) }.getOrNull()
    }
}

package com.os4.musiccover.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.os4.musiccover.SettingSpec
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's own copy of the module's settings, which is the truth from stage 5.3 on; the remote
 * preferences group is the delivery channel into the hosts, and an op is the fallback when that
 * channel cannot be relied on.
 *
 * Kept in the device-protected storage, like the group the module reads: the lock screen wants the
 * settings before anyone has unlocked, and a credential-encrypted file is not there then. The keys
 * are the query's ([SettingSpec.key]) and so the group's; the two are one contract.
 *
 * Writes are local at once - a switch must not wait for a broadcast - and a batch is delivered
 * after half a second of quiet, so a slider dragging at sixty frames a second is one write, not
 * sixty. Every batch carries a counter; the module answers the last one it applied, and one that
 * does not come back falls back to the ops the page used before this stage. Nothing is ever shown
 * as applied that the module has not confirmed.
 *
 * Stage 5.4's migration lives here too: the first time the app finds a module that reads the group
 * and a local copy with no marker, it asks the module for every setting it holds, writes the whole
 * set to the group together with the marker and the counter, and only then marks its own copy. The
 * values are the ones the module already has, so the batch that switches the module over changes
 * nothing on screen.
 */
object SettingsStore {

    private const val TAG = "MCSettings"

    /** The per-write trace, for the test build only: a slider would flood any other build. */
    private val verbose = com.os4.musiccover.BuildConfig.S5_TEST

    private const val PREFS_NAME = "settings"

    /** How long the page must be quiet before a batch goes out; a slider's burst is one batch. */
    private const val FLUSH_AFTER_MS = 500L

    /** When to ask the module what it has applied: shortly after a batch, then once more. */
    private const val CONFIRM_AFTER_MS = 300L
    private const val RECONFIRM_AFTER_MS = 2_000L

    /** A full reconcile is a whole group's worth of writes; do not repeat it every query. */
    private const val RECONCILE_GAP_MS = 3_000L

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.w(TAG, "coroutine failed", e) },
    )
    private val lock = Any()

    @Volatile private var prefs: SharedPreferences? = null

    @Volatile private var appContext: Context? = null

    @Volatile private var service: XposedService? = null

    /** Whether the module reads the group (its query has `settings_prefs`); null until asked. */
    @Volatile private var capable: Boolean? = null

    /** The last counter the module answered; its group may be behind when it is below ours. */
    @Volatile private var moduleRev = -1L

    @Volatile private var migrated = false

    /** The Android user this process runs as; anything but 0 cannot reach SystemUI's rows. */
    @Volatile private var userId = 0

    private var flushJob: Job? = null
    private var migrating = false
    private var lastReconcile = 0L

    /** Values waiting for the next batch, keyed the way the group is, with how to replay one. */
    private val outbox = SettingsOutbox<Item>()

    private val unconfirmedFlow = MutableStateFlow(0)
    private val migratedFlow = MutableStateFlow(false)

    /**
     * How many batches the module has not confirmed. A page shows one line while this is above
     * zero: a switch that looks moved while the lock screen kept the old value is the one thing
     * this stage must not do.
     */
    val unconfirmed: StateFlow<Int> = unconfirmedFlow

    /** Whether the app's copy is the source; pages let themselves be edited once it is. */
    val migratedState: StateFlow<Boolean> = migratedFlow

    private class Item(val value: Any, val replay: () -> Unit)

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(lock) {
            if (prefs != null) return
            val app = context.applicationContext
            appContext = app
            userId = Process.myUid() / 100_000
            val p = app.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            outbox.seedRev(p.getLong(SettingSpec.PREFS_REV, 0L))
            if (p.getBoolean(SettingSpec.PREFS_DIRTY, false)) outbox.markDirty()
            migrated = p.getBoolean(SettingSpec.PREFS_MIGRATED, false)
            prefs = p
        }
        migratedFlow.value = migrated
        Log.i(TAG, "init migrated=$migrated rev=${outbox.rev} dirty=${outbox.dirty} user=$userId")
    }

    /**
     * One setting changed on a page. The local copy is written here; true means this store will
     * deliver it, and the caller must not send its op. False means the module cannot be reached
     * this way yet - the page's op is still the way the value arrives.
     */
    fun put(context: Context, spec: SettingSpec, value: Any, replay: () -> Unit): Boolean =
        putKey(context, spec.key, value, replay)

    /** The lyric style's two halves go in one at a time; every other setting has one key. */
    fun putKey(context: Context, key: String, value: Any, replay: () -> Unit): Boolean {
        init(context)
        val p = prefs ?: return false
        val same = synchronized(lock) { holds(p, key, value) }
        if (!same) {
            // The local copy is ahead of the group until something delivers it. An op from the
            // page covers the session; the mark is what makes the reconcile write it down when
            // the module can take the group again - and it is kept in the file, because the app
            // can be killed before that happens.
            p.edit {
                write(this, key, value)
                if (migrated) putBoolean(SettingSpec.PREFS_DIRTY, true)
            }
            if (migrated) synchronized(lock) { outbox.markDirty() }
        }
        if (verbose) Log.i(TAG, "put $key=$value same=$same deliverable=${deliverable()}")
        if (!deliverable()) return false
        // A page that sets the value it already has has nothing to send.
        if (same) return true
        synchronized(lock) { outbox.put(key, Item(value, replay)) }
        schedule()
        return true
    }

    /** Whether the app's copy is the source, and pages may be edited while the module is away. */
    fun editable(): Boolean = migrated

    /** A secondary user's writes cannot reach SystemUI, which only reads user 0's rows. */
    fun isThirdPartyUser(): Boolean = userId != 0

    /**
     * The settings half of a query's answer, from the app's copy when the module is not the
     * source yet it still has values - which is how a page keeps its values after SystemUI goes.
     * A key the app never kept stays as the module answered it: the clock size is the one such
     * value, answered as the fraction the clock is actually at.
     */
    fun overlay(state: ModuleBridge.State): ModuleBridge.State {
        val p = prefs ?: return state
        if (!migrated) return state
        return state.copy(
            coverFeature = p.getBoolean(SettingSpec.COVER_FEATURE.key, state.coverFeature),
            bias = p.getFloat(SettingSpec.BIAS.key, state.bias),
            coverStyle = p.getInt(SettingSpec.COVER_STYLE.key, state.coverStyle),
            clockHeightDp = p.getFloat(SettingSpec.CLOCK_HEIGHT.key, state.clockHeightDp),
            clockSize = if (p.contains(SettingSpec.CLOCK_SIZE.key)) {
                p.getFloat(SettingSpec.CLOCK_SIZE.key, state.clockSize)
            } else {
                state.clockSize
            },
            aodBigClock = if (p.contains(SettingSpec.AOD_SMALL.key)) {
                !p.getBoolean(SettingSpec.AOD_SMALL.key, !state.aodBigClock)
            } else {
                state.aodBigClock
            },
            forceColon = p.getBoolean(SettingSpec.COLON.key, state.forceColon),
            mcTitleTap = p.getBoolean(SettingSpec.MC_TITLE_TAP.key, state.mcTitleTap),
            mcPageButton = p.getBoolean(SettingSpec.MC_PAGE_BUTTON.key, state.mcPageButton),
            mediaBarGlow = p.getBoolean(SettingSpec.SEEK_GLOW.key, state.mediaBarGlow),
            hideFingerprint = p.getBoolean(SettingSpec.HIDE_FP.key, state.hideFingerprint),
            motionCover = p.getBoolean(SettingSpec.MOTION.key, state.motionCover),
            motionOnData = p.getBoolean(SettingSpec.MOTION_ON_DATA.key, state.motionOnData),
            lyrics = p.getBoolean(SettingSpec.LYRICS.key, state.lyrics),
            lyricsKeepOn = p.getBoolean(SettingSpec.LYRIC_KEEP_ON.key, state.lyricsKeepOn),
            lyricsHdr = p.getBoolean(SettingSpec.LYRIC_HDR.key, state.lyricsHdr),
            lyricsTrans = p.getBoolean(SettingSpec.LYRIC_TRANS.key, state.lyricsTrans),
            lyricsRoma = p.getBoolean(SettingSpec.LYRIC_ROMA.key, state.lyricsRoma),
            lyricsOnline = p.getBoolean(SettingSpec.LYRIC_ONLINE.key, state.lyricsOnline),
            lyricsAlign = p.getInt(SettingSpec.LYRIC_ALIGN.key, state.lyricsAlign),
            lyricSizeSp = p.getFloat(SettingSpec.LYRIC_SIZE.key, state.lyricSizeSp),
            lyricBgSizeSp = p.getFloat(SettingSpec.LYRIC_BG_SIZE.key, state.lyricBgSizeSp),
            lyricAppearance = p.getString(SettingSpec.LYRIC_APPEARANCE.key, state.lyricAppearance)
                ?: state.lyricAppearance,
            fpAvoid = p.getInt(SettingSpec.FP_AVOID.key, state.fpAvoid),
            transit = p.getBoolean(SettingSpec.TRANSIT.key, state.transit),
            pickup = p.getBoolean(SettingSpec.PICKUP.key, state.pickup),
        )
    }

    /**
     * A query that answered: what the module can do with the group, and what it has applied.
     * The first answer a running app gets is also where the migration starts.
     */
    fun onModuleAnswered(state: ModuleBridge.State) {
        if (prefs == null) return
        // What the module can do, not what it is doing: before the migration it answers
        // settings_prefs=false and still reads the group afterwards.
        capable = state.canReadPrefs
        moduleRev = state.settingsRev
        // A module that caught up owes nothing more: the "not confirmed" line goes away even
        // when the confirmation itself timed out - a SystemUI restarting under a batch reads
        // the group on its way up and is already there by the next query.
        if (state.canReadPrefs && state.settingsRev >= outbox.rev) {
            if (unconfirmedFlow.value != 0) unconfirmedFlow.value = 0
        }
        if (!state.canReadPrefs) return
        scope.launch {
            if (!migrated) migrate() else maybeReconcile()
        }
    }

    /** LSPosed bound the service: the channel to the group exists from now on. */
    fun onServiceBound(service: XposedService) {
        this.service = service
        Log.i(TAG, "service bound, migrated=$migrated rev=${outbox.rev}")
        if (prefs == null) return
        scope.launch {
            if (!migrated) migrate() else reconcile()
        }
    }

    fun onServiceDied() {
        service = null
        Log.w(TAG, "service died")
    }

    /** The app's copy as one line, for the test build's dump (S5TestReceiver). */
    internal fun dumpLine(): String {
        val p = prefs ?: return "store not ready"
        val all = p.all
        val sb = StringBuilder("migrated=").append(migrated)
            .append(" rev=").append(outbox.rev)
            .append(" dirty=").append(outbox.dirty)
            .append(" user=").append(userId)
            .append(" capable=").append(capable)
        for (spec in SettingsPlan.specs) {
            sb.append(' ').append(spec.key).append('=')
                .append(if (all.containsKey(spec.key)) all[spec.key] else "-")
        }
        return sb.toString()
    }

    /** Honours a page's changes without waiting out the quiet period; test restore uses it. */
    internal fun flushNow(context: Context) {
        init(context)
        if (verbose) Log.i(TAG, "flushNow, outbox=${synchronized(lock) { !outbox.isEmpty() }}")
        scope.launch {
            flushJob?.cancel()
            commitBatch()
        }
    }

    /**
     * Rewrites the local copy and the group from what the module holds right now, for the test
     * build's restore: an op put the snapshot back into the module's memory, and this makes the
     * app's copy and the group say the same before SystemUI restarts. Same values, new counter.
     */
    internal fun resync(context: Context) {
        init(context)
        scope.launch {
            val svc = service ?: return@launch
            val snap = ModuleBridge.settingsSnapshot(context, includeUnset = true) ?: return@launch
            val mark = synchronized(lock) { outbox.mark() }
            val r = synchronized(lock) { outbox.nextRev() }
            val ok = withContext(Dispatchers.IO) {
                runCatching { writeRemote(svc, snap.values, r, false) }.getOrDefault(false)
            }
            if (!ok) {
                Log.w(TAG, "resync write failed, rev=$r")
                return@launch
            }
            prefs?.edit {
                for ((key, value) in snap.values) write(this, key, value)
                putLong(SettingSpec.PREFS_REV, r)
            }
            synchronized(lock) { outbox.clean(mark) }
            clearDirtyMark()
            moduleRev = r
            unconfirmedFlow.value = 0
            Log.i(TAG, "resynced rev=$r keys=${snap.values.size}")
        }
    }

    // ------------------------------------------------------------------ delivery

    /**
     * Null is "not asked yet", which is where every app start begins: a module that has been
     * migrated cannot lose the ability by restarting the app, and waiting for the first query
     * would send the first change of every launch down the op path. Only an explicit false -
     * a module that answered without the key - stops the group.
     */
    private fun deliverable(): Boolean =
        migrated && userId == 0 && service != null && capable != false

    private fun schedule() {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(FLUSH_AFTER_MS)
            commitBatch()
        }
    }

    private suspend fun commitBatch() {
        val mark = synchronized(lock) { outbox.mark() }
        val batch = synchronized(lock) {
            if (outbox.isEmpty()) {
                if (verbose) Log.i(TAG, "commit: nothing waiting")
                return
            }
            outbox.drain()
        }
        if (verbose) Log.i(TAG, "commit: ${batch.size} key(s) ${batch.keys}")
        val svc = service
        if (svc == null || !deliverable()) {
            // The channel went between the change and the batch: send the ops the page would
            // have sent, and let the reconcile pick the values up when the service comes back.
            Log.w(TAG, "no channel for ${batch.keys}, sending ops")
            fallback(batch)
            return
        }
        val r = synchronized(lock) { outbox.nextRev() }
        prefs?.edit { putLong(SettingSpec.PREFS_REV, r) }
        val ok = withContext(Dispatchers.IO) {
            runCatching { writeRemote(svc, batch.mapValues { it.value.value }, r, false) }
                .getOrDefault(false)
        }
        if (!ok) {
            Log.w(TAG, "batch rev=$r (${batch.keys}) not written")
            unconfirmedFlow.value += 1
            return
        }
        synchronized(lock) { outbox.clean(mark) }
        clearDirtyMark()
        Log.i(TAG, "batch rev=$r ${batch.keys}")
        confirm(r, batch)
    }

    /** Once nothing is waiting the mark goes too; a write that landed during the batch keeps it. */
    private fun clearDirtyMark() {
        if (!synchronized(lock) { outbox.dirty }) {
            prefs?.edit { remove(SettingSpec.PREFS_DIRTY) }
        }
    }

    /**
     * Asks twice whether the module applied [r]. A module that has not says so by answering a
     * lower counter, or not at all; either way the batch goes out as ops so the session still
     * gets it, and the page says one line about it instead of pretending.
     */
    private suspend fun confirm(r: Long, batch: Map<String, Item>) {
        if (revAtLeast(r, CONFIRM_AFTER_MS)) {
            Log.i(TAG, "confirmed rev=$r")
            return
        }
        if (revAtLeast(r, RECONFIRM_AFTER_MS)) {
            Log.i(TAG, "confirmed rev=$r on the second ask")
            return
        }
        Log.w(TAG, "rev=$r not applied (module rev=$moduleRev), sending ops")
        fallback(batch)
        unconfirmedFlow.value += 1
    }

    private suspend fun revAtLeast(r: Long, waitMs: Long): Boolean {
        val ctx = appContext ?: return false
        delay(waitMs)
        val probe = ModuleBridge.settingsProbe(ctx) ?: return false
        if (!probe.alive) return false
        probe.capable?.let { capable = it }
        moduleRev = probe.rev
        return probe.capable == true && probe.rev >= r
    }

    private fun fallback(batch: Map<String, Item>) {
        for (item in batch.values) runCatching { item.replay() }
    }

    private fun writeRemote(
        svc: XposedService,
        values: Map<String, Any>,
        r: Long,
        migrate: Boolean,
    ): Boolean {
        val e = svc.getRemotePreferences(SettingSpec.PREFS_GROUP).edit()
        for ((key, value) in values) {
            when (value) {
                is Boolean -> e.putBoolean(key, value)
                is Float -> e.putFloat(key, value)
                is Int -> e.putInt(key, value)
                is String -> e.putString(key, value)
                else -> return false
            }
        }
        if (migrate) e.putBoolean(SettingSpec.PREFS_MIGRATED, true)
        e.putLong(SettingSpec.PREFS_REV, r)
        return e.commit()
    }

    // ------------------------------------------------------------------ migration and reconcile

    /**
     * Stage 5.4: hand the module's own settings to the app and switch the module over, in one
     * group commit. Only when the module reads the group and this user can write rows SystemUI
     * reads; an old module or a secondary user keeps the file as the source, exactly as before.
     */
    private suspend fun migrate() {
        if (migrated) return
        if (migrating) return
        if (userId != 0) return
        val svc = service ?: return
        val ctx = appContext ?: return
        migrating = true
        try {
            val snap = ModuleBridge.settingsSnapshot(ctx)
            if (snap == null) {
                Log.i(TAG, "migrate: no answer, trying again on the next query")
                return
            }
            if (!snap.capable || snap.values.isEmpty()) {
                Log.i(TAG, "migrate: module has nothing to hand over (capable=${snap.capable})")
                return
            }
            val r = synchronized(lock) { outbox.nextRev() }
            val ok = withContext(Dispatchers.IO) {
                runCatching { writeRemote(svc, snap.values, r, true) }.getOrDefault(false)
            }
            // Counting on is harmless: a counter the module never saw only makes the next batch
            // look newer, and the migration runs again with a higher one.
            if (!ok) {
                Log.w(TAG, "migrate: writing the group failed, rev=$r")
                return
            }
            synchronized(lock) {
                prefs?.edit {
                    for ((key, value) in snap.values) write(this, key, value)
                    putBoolean(SettingSpec.PREFS_MIGRATED, true)
                    putLong(SettingSpec.PREFS_REV, r)
                    remove(SettingSpec.PREFS_DIRTY)
                }
                migrated = true
            }
            capable = true
            moduleRev = r
            migratedFlow.value = true
            unconfirmedFlow.value = 0
            Log.i(TAG, "migrated rev=$r keys=${snap.values.size}")
        } finally {
            migrating = false
        }
    }

    /**
     * The whole local set pushed again, with a new counter. This is what makes a bound service
     * enough on its own: anything written while it was away - or to a group LSPosed has since
     * been asked to delete - is one batch here.
     */
    private suspend fun reconcile() {
        if (!migrated || userId != 0) return
        val svc = service ?: return
        val ctx = appContext ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReconcile < RECONCILE_GAP_MS) return
        val probe = ModuleBridge.settingsProbe(ctx) ?: return
        probe.capable?.let { capable = it }
        moduleRev = probe.rev
        // Either the counter is behind or a value was written with no way to deliver it (an
        // older module, a service that was away). Both are settled by one whole-set write.
        if (probe.capable != true || (probe.rev >= outbox.rev && !outbox.dirty)) return
        lastReconcile = now
        val values = readLocal() ?: return
        val mark = synchronized(lock) { outbox.mark() }
        val r = synchronized(lock) { outbox.nextRev() }
        prefs?.edit { putLong(SettingSpec.PREFS_REV, r) }
        val ok = withContext(Dispatchers.IO) {
            runCatching { writeRemote(svc, values, r, false) }.getOrDefault(false)
        }
        if (!ok) {
            Log.w(TAG, "reconcile rev=$r write failed")
            unconfirmedFlow.value += 1
            return
        }
        synchronized(lock) { outbox.clean(mark) }
        clearDirtyMark()
        Log.i(TAG, "reconciled rev=$r keys=${values.size} (module had ${probe.rev})")
        if (revAtLeast(r, CONFIRM_AFTER_MS)) unconfirmedFlow.value = 0
        else unconfirmedFlow.value += 1
    }

    /** What a query can bring the page's way: the module answered and has values behind ours. */
    private fun maybeReconcile() {
        if (userId != 0) return
        if (service == null) return
        if (verbose) Log.i(TAG, "maybe reconcile: moduleRev=$moduleRev rev=${outbox.rev} dirty=${outbox.dirty}")
        if (moduleRev < 0) return
        if (moduleRev >= outbox.rev && !outbox.dirty) return
        if (SystemClock.elapsedRealtime() - lastReconcile < RECONCILE_GAP_MS) return
        scope.launch { reconcile() }
    }

    /** Every key the app's copy holds, by type. Null when nothing has been kept yet. */
    private fun readLocal(): Map<String, Any>? {
        val p = prefs ?: return null
        val out = LinkedHashMap<String, Any>()
        for (spec in SettingsPlan.specs) {
            val key = spec.key
            if (p.contains(key)) out[key] = read(p, spec)
        }
        return out.ifEmpty { null }
    }

    private fun write(e: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is Boolean -> e.putBoolean(key, value)
            is Float -> e.putFloat(key, value)
            is Int -> e.putInt(key, value)
            is String -> e.putString(key, value)
        }
    }

    private fun holds(p: SharedPreferences, key: String, value: Any): Boolean = when (value) {
        is Boolean -> p.contains(key) && p.getBoolean(key, false) == value
        is Float -> p.contains(key) && p.getFloat(key, 0f) == value
        is Int -> p.contains(key) && p.getInt(key, 0) == value
        is String -> p.contains(key) && p.getString(key, null) == value
        else -> false
    }

    private fun read(p: SharedPreferences, spec: SettingSpec): Any = when (spec.kind) {
        SettingSpec.Kind.BOOL -> p.getBoolean(spec.key, spec.defBool)
        SettingSpec.Kind.FLOAT -> p.getFloat(spec.key, spec.defFloat)
        SettingSpec.Kind.INT -> p.getInt(spec.key, spec.defInt)
        SettingSpec.Kind.STRING -> p.getString(spec.key, spec.defString) ?: spec.defString
    }
}

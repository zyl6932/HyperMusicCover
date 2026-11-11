package com.os4.musiccover;

import com.os4.musiccover.fingerprint.InstallFingerprint;
import com.os4.musiccover.hotreload.HotReload;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The settings, read from the remote preferences group the app writes (architecture plan, stage
 * 5.2). The app's own preferences are the truth from 5.3 on; this group is the delivery channel
 * into the hosts. Until the app has migrated ({@link SettingSpec#PREFS_MIGRATED}) the state file
 * is still the only source, and the module behaves exactly as it did before this stage - one
 * source or the other, never a key from each.
 *
 * <p>Read at install, before any view exists: the values are set and the effects are left to the
 * lock screen, which is how {@link ModuleState#loadState} has always treated them. A change while
 * the process runs arrives on a binder thread; the key is only remembered there, and one
 * main-thread pass applies the batch through {@link Settings#applyBatch} - a slider's burst is
 * one state-file write, one After, one finish.
 */
final class SettingsPrefs {
    private SettingsPrefs() {
    }

    private static final String TAG = "[MCProbe] settings: ";

    private static volatile SharedPreferences sPrefs;
    /** True once the table's values came from the group; false while the file is the source. */
    private static volatile boolean sFromPrefs;
    /** The app's write counter the last applied batch carried. */
    private static volatile long sRev;

    /** Keys the listener has seen, for the next main-thread pass. */
    private static final Set<String> DIRTY = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean POSTED = new AtomicBoolean();

    private static final Runnable FLUSH = new Runnable() {
        @Override
        public void run() {
            POSTED.set(false);
            Context c = Main.appContext();
            if (c == null) return;   // boot() flushes; the keys are kept
            try {
                flush(c);
            } catch (Throwable t) {
                Xp.w(TAG + "applying the app's changes failed: " + t);
            }
        }
    };

    private static final SharedPreferences.OnSharedPreferenceChangeListener LISTENER = (p, key) -> {
        if (key == null) return;
        DIRTY.add(key);
        if (!POSTED.compareAndSet(false, true)) return;
        try {
            Main.main().post(FLUSH);
        } catch (Throwable t) {
            // No main loop yet; the keys wait for boot's flush.
            POSTED.set(false);
        }
    };

    /** Whether the settings come from the app's group rather than the state file. */
    static boolean usesPrefs() {
        return sFromPrefs;
    }

    /** The last write from the app this generation applied, for the app's confirmation. */
    static long rev() {
        return sRev;
    }

    /**
     * From Main's install, once per generation, after the features have bound their effects.
     * A group that cannot be read or listened to leaves the state file as the source, which is
     * exactly what the module did before this stage.
     */
    static synchronized void install() {
        if (sPrefs != null) return;
        SharedPreferences p;
        try {
            p = Xp.api().getRemotePreferences(SettingSpec.PREFS_GROUP);
        } catch (Throwable t) {
            Xp.w(TAG + "no remote preferences, the state file stays the source: " + t);
            return;
        }
        try {
            // Before reading, so a write in between is not lost; the callback only records.
            Gen.prefListener(p, LISTENER);
        } catch (Throwable t) {
            Xp.w(TAG + "listener not registered, the state file stays the source: " + t);
            return;
        }
        sPrefs = p;
        long t0 = SystemClock.elapsedRealtimeNanos();
        boolean migrated = false;
        long rev = 0L;
        try {
            migrated = p.getBoolean(SettingSpec.PREFS_MIGRATED, false);
            rev = p.getLong(SettingSpec.PREFS_REV, 0L);
            if (migrated) {
                readIntoTable(p);
                sRev = rev;
            }
        } catch (Throwable t) {
            // The listener stays: a later migrated marker still switches the source.
            Xp.w(TAG + "reading the group failed, the state file stays the source: " + t);
        }
        Xp.log(TAG + "installed gen=" + HotReload.generation() + " migrated=" + migrated
                + " rev=" + rev + " read=" + us(SystemClock.elapsedRealtimeNanos() - t0)
                + " source=" + (sFromPrefs ? "prefs" : "file"));
        // The group may name an installation whose files this process does not belong to
        // (stage 5.5). boot() has the Context; the install can run before it does.
        checkInstall(Main.appContext());
    }

    /**
     * Acts on the group's installation id. The group is only read here, never written: the app
     * is the one that makes ids.
     */
    private static void checkInstall(Context c) {
        SharedPreferences p = sPrefs;
        if (p == null || c == null) return;
        try {
            InstallFingerprint.seen(c, p.getString(SettingSpec.PREFS_INSTALL, null));
        } catch (Throwable t) {
            Xp.w(TAG + "reading the installation id failed: " + t);
        }
    }

    /**
     * The source switch's cold-start half: values only, no effects - nothing is up yet. Also what
     * a migration arriving at a running SystemUI does: the values it carries are the ones the
     * module just answered with, so nothing on screen may move.
     */
    private static void readIntoTable(SharedPreferences p) {
        int n = 0;
        for (Settings.Entry<?> e : Settings.all()) {
            if (!Settings.persisted(e)) continue;
            try {
                Settings.Change change = changeFor(e, p);
                if (change == null) continue;
                change.setOnly();
                n++;
            } catch (Throwable t) {
                Xp.w(TAG + "unreadable " + e.spec.key + ": " + t);
            }
        }
        sFromPrefs = true;
        Xp.log(TAG + "the settings now come from the app's group (" + n + " keys)");
    }

    /**
     * One main-thread pass over what the listener saw. While the file is the source only a
     * migrated marker is acted on - the app may migrate while SystemUI is running (stage 5.4).
     */
    static void flush(Context c) {
        SharedPreferences p = sPrefs;
        if (p == null || c == null) return;
        // Before the keys are read: a reinstallation arriving here while SystemUI runs must be
        // acted on even when the change is the only thing in this batch.
        checkInstall(c);
        List<String> keys = drain();
        if (keys.isEmpty()) return;

        boolean migrated;
        try {
            migrated = p.getBoolean(SettingSpec.PREFS_MIGRATED, false);
        } catch (Throwable t) {
            return;   // the next change tries again
        }
        // What the callback delivered and what the group says at this point. A running write
        // from the app must arrive here as changed values; a generation that never switches is
        // either not called or reads a snapshot that lagged behind its callback.
        Xp.log(TAG + "flush: " + keys.size() + " key(s) " + keys
                + " migrated=" + migrated + " fromPrefs=" + sFromPrefs);
        if (!sFromPrefs) {
            if (!migrated) return;   // still the file's settings; nothing to apply
            readIntoTable(p);
            sRev = p.getLong(SettingSpec.PREFS_REV, 0L);
            return;
        }

        List<Settings.Change> changes = new ArrayList<>();
        for (String key : keys) {
            if (SettingSpec.PREFS_MIGRATED.equals(key) || SettingSpec.PREFS_REV.equals(key)) {
                continue;
            }
            Settings.Entry<?> e = entryForKey(key);
            if (e == null) continue;   // not a setting of ours, or not one that is kept
            try {
                Settings.Change change = changeFor(e, p);
                if (change != null) changes.add(change);
            } catch (Throwable t) {
                Xp.w(TAG + "unreadable " + key + ": " + t);
            }
        }
        boolean applied = Settings.applyBatch(c, changes);
        try {
            sRev = p.getLong(SettingSpec.PREFS_REV, sRev);
        } catch (Throwable ignored) {
        }
        Xp.log(TAG + "applied " + changes.size() + " change(s), rev=" + sRev
                + (applied ? "" : ", nothing differed"));
    }

    /** The keys seen so far, and nobody else's. A key arriving during this stays for the next. */
    private static List<String> drain() {
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = DIRTY.iterator(); it.hasNext(); ) {
            keys.add(it.next());
            it.remove();
        }
        return keys;
    }

    /** The entry a prefs key belongs to; null when it is not a setting this stage keeps. */
    static Settings.Entry<?> entryForKey(String key) {
        for (Settings.Entry<?> e : Settings.all()) {
            if (!Settings.persisted(e)) continue;
            if (e == Settings.LYRIC_STYLE) {
                // Two keys, one entry: either one means the whole style is read again.
                if (SettingSpec.LYRIC_SIZE.key.equals(key)
                        || SettingSpec.LYRIC_BG_SIZE.key.equals(key)) {
                    return e;
                }
                continue;
            }
            if (e.spec.key.equals(key)) return e;
        }
        return null;
    }

    /** The whole value one entry should have, read from the group; null if it is not one of ours. */
    private static Settings.Change changeFor(Settings.Entry<?> e, SharedPreferences p) {
        SettingSpec s = e.spec;
        if (e == Settings.LYRIC_STYLE) {
            // One entry, two keys: a batch that moved one of them must not reset the other. What
            // the module holds now is the best answer for a key the group does not carry.
            if (!p.contains(SettingSpec.LYRIC_SIZE.key) && !p.contains(SettingSpec.LYRIC_BG_SIZE.key)) {
                return null;
            }
            com.os4.musiccover.lyrics.LyricStyle cur = Settings.LYRIC_STYLE.get();
            return Settings.lyricStyle(
                    p.getFloat(SettingSpec.LYRIC_SIZE.key, cur.sizeSp),
                    p.getFloat(SettingSpec.LYRIC_BG_SIZE.key, cur.bgSizeSp));
        }
        // A key the group does not carry leaves the value the module already has. The migration
        // (stage 5.4) writes only what the state file held, and the clock size is the one setting
        // that is absent until someone chooses it - reading the default there would choose it.
        if (!p.contains(s.key)) return null;
        if (e == Settings.COVER_STYLE) {
            return Settings.coverStyle(p.getInt(s.key, s.defInt));
        }
        if (e == Settings.LYRIC_APPEARANCE) {
            return Settings.lyricAppearance(p.getString(s.key, s.defString));
        }
        if (e instanceof Settings.Bool) {
            return ((Settings.Bool) e).change(p.getBoolean(s.key, s.defBool));
        }
        if (e instanceof Settings.Int) {
            return ((Settings.Int) e).change(p.getInt(s.key, s.defInt));
        }
        if (e instanceof Settings.Real) {
            return ((Settings.Real) e).change(p.getFloat(s.key, s.defFloat));
        }
        return null;
    }

    private static String us(long ns) {
        return (ns / 1000L) + "µs";
    }
}

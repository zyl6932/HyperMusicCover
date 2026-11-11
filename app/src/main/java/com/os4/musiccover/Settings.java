package com.os4.musiccover;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.os4.musiccover.lyrics.LyricStyle;
import com.os4.musiccover.lyrics.LyricAppearance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every setting the app's pages change, in one table: its value, the op and extra that set it,
 * what that op does without the extra, when the change is saved, what else it sets off, the
 * key it is kept under in the state file, and how the query answers it.
 *
 * A setting used to be four hand-kept places that had to agree - the app's setter, an op
 * branch in ProbeReceiver, a static field in whichever class used it, and a line in the state
 * file - plus a line in the query. Here it is one entry; ProbeReceiver dispatches the op
 * through {@link #dispatch}, ModuleState writes and reads through {@link Entry#files}, and the
 * query is {@link #query}.
 *
 * The three names a setting can have are kept apart on purpose and none of them was changed
 * to match the others: the query key is {@link SettingSpec#key}, the op is {@link Entry#op},
 * the file key is the first argument of {@link Entry#file}. A file key renamed is a setting an
 * old file no longer restores; an op renamed breaks adb habits and an app talking to a module
 * of another version.
 *
 * Not here: what the module measures or keeps between starts without the user having chosen it
 * (cover, lyrichidden, sawlyric, covergap, cardrect, clockfull, clockref), and the configs that
 * keep their own storage (minicfg, fpquick, the shade page's shade_* keys).
 */
public final class Settings {
    private Settings() {}

    /** What an op does when it comes without its extra. */
    enum Absent {
        /** The opposite of the current value: `--es op lyricroma` alone flips it. */
        TOGGLE,
        /** A fixed value, often but not always the default (clocksize goes to 1). */
        DEFAULT,
        /** The current value, so the op changes nothing. */
        KEEP,
        /** Not touched at all: neither applied nor saved. */
        IGNORE,
        /** The next value in the spec's range, wrapping: `--es op fadesync` alone. */
        CYCLE,
    }

    /** When a change is written to the state file. */
    enum Save {
        NOW,
        /** Folded with the next half second's changes: the slider ones. */
        SOON,
        /** The apply step saves itself (Main.setBias and the like), or nothing is kept. */
        NONE,
    }

    /** A key's value as it goes into the file, or null to leave the key out this time. */
    interface Write {
        String value();
    }

    /** Applies a value read back from the file. Throwing costs this key its default, no more. */
    interface Read {
        void apply(String v) throws Exception;
    }

    /**
     * What runs once after a change. A feature registers its own (stage 5.1), so one instance
     * shared by several entries is what {@link #applyBatch} dedupes by.
     */
    public interface After {
        void run(Context c);
    }

    /**
     * One entry and the whole value it should have - one setting's share of a batch (see
     * {@link #applyBatch}). [same] is read when the change is made: the batch leaves that entry
     * alone rather than applying the value and trusting the effect to notice.
     */
    static final class Change {
        final Entry<?> entry;
        final boolean same;
        private final java.util.function.Consumer<Context> run;
        private final Runnable set;

        Change(Entry<?> entry, boolean same, java.util.function.Consumer<Context> run,
               Runnable set) {
            this.entry = entry;
            this.same = same;
            this.run = run;
            this.set = set;
        }

        /** Writes the value and nothing else - a cold start's read, before any view exists. */
        void setOnly() {
            set.run();
        }
    }

    /** One line of the state file. */
    static final class FileKey {
        final String name;
        final Write write;
        final Read read;

        FileKey(String name, Write write, Read read) {
            this.name = name;
            this.write = write;
            this.read = read;
        }
    }

    /** Every entry, in the order the ops apply them. */
    /**
     * Whether the state file had a clock size when it was last read. The query answers it beside
     * the size itself, because the size it answers is the one the clock is at either way - and
     * the app's migration (stage 5.4) must not write that back as if it had been chosen: doing so
     * would fix the clock to this style's height instead of following it.
     */
    private static boolean sClockSizeFromFile;

    static boolean clockSizeFromFile() {
        return sClockSizeFromFile;
    }

    private static final List<Entry<?>> ALL = new ArrayList<>();
    /** Every op's entries, in that order. */
    private static final Map<String, List<Entry<?>>> OPS = new HashMap<>();
    /** What an op does once its entries are through, whichever of them applied. */
    private static final Map<String, After> FINISH = new HashMap<>();

    public abstract static class Entry<E extends Entry<E>> {
        final SettingSpec spec;
        String op;
        String extra;
        Absent absent = Absent.IGNORE;
        Save save = Save.NOW;
        After after;
        final List<FileKey> files = new ArrayList<>();
        private Answer answer;

        Entry(SettingSpec spec) {
            this.spec = spec;
            ALL.add(this);
        }

        @SuppressWarnings("unchecked")
        private E self() {
            return (E) this;
        }

        /** Set by `--es op [op] --e? [extra] ...`; [absent] says what the op does without it. */
        E op(String op, String extra, Absent absent) {
            this.op = op;
            this.extra = extra;
            this.absent = absent;
            List<Entry<?>> list = OPS.get(op);
            if (list == null) OPS.put(op, list = new ArrayList<>());
            list.add(this);
            return self();
        }

        E save(Save save) {
            this.save = save;
            return self();
        }

        /** Run after a change the op applied, after it is saved. Features register their own. */
        public E then(After after) {
            this.after = after;
            return self();
        }

        /** Kept in the state file under [name]. */
        E file(String name, Write write, Read read) {
            files.add(new FileKey(name, write, read));
            return self();
        }

        /** Answered in the query by [answer] instead of under the spec's key. */
        E answer(Answer answer) {
            this.answer = answer;
            return self();
        }

        /** Reads the op's extra and applies it. False when nothing was applied. */
        abstract boolean fromIntent(Context c, Intent i);

        /** The value under the spec's key, by its type. */
        abstract void putDefault(Bundle out);

        abstract String describe();

        final void query(Bundle out) {
            if (answer != null) answer.put(out);
            else putDefault(out);
        }
    }

    interface Answer {
        void put(Bundle out);
    }

    // ---------------------------------------------------------------- the kinds

    public static final class Bool extends Entry<Bool> {
        public interface Apply {
            /** False when the value did not change and nothing follows. */
            boolean apply(Context c, boolean v);
        }

        private volatile boolean value;
        private boolean absentValue;
        private Apply effect;

        Bool(SettingSpec spec) {
            super(spec);
            value = spec.defBool;
        }

        public boolean on() {
            return value;
        }

        public void set(boolean v) {
            value = v;
        }

        /** The value for {@link Absent#DEFAULT}, and the fallback for an extra of the wrong type. */
        Bool absent(boolean v) {
            absentValue = v;
            return this;
        }

        public Bool apply(Apply apply) {
            this.effect = apply;
            return this;
        }

        /** Written as 1 or 0, read as on only for "1". */
        Bool file(String name) {
            return file(name, () -> on() ? "1" : "0", v -> set("1".equals(v)));
        }

        @Override
        boolean fromIntent(Context c, Intent i) {
            boolean next;
            switch (absent) {
                case TOGGLE:
                    next = i.getBooleanExtra(extra, !on());
                    break;
                case DEFAULT:
                    next = i.getBooleanExtra(extra, absentValue);
                    break;
                case IGNORE:
                    if (!i.hasExtra(extra)) return false;
                    next = i.getBooleanExtra(extra, absentValue);
                    break;
                default:
                    next = i.getBooleanExtra(extra, on());
            }
            return apply(c, next);
        }

        /** The op path's whole-value apply: the bound effect, or the value alone. */
        boolean apply(Context c, boolean v) {
            if (effect != null) return effect.apply(c, v);
            set(v);
            return true;
        }

        /**
         * The batch's way in (stage 5.2): a value the entry already has is not applied at all, so
         * a migration arriving with what memory holds changes nothing on screen.
         */
        boolean applyValue(Context c, boolean v) {
            if (v == on()) return false;
            return apply(c, v);
        }

        Change change(boolean v) {
            return new Change(this, v == on(), c -> applyValue(c, v), () -> set(v));
        }

        @Override
        void putDefault(Bundle out) {
            out.putBoolean(spec.key, on());
        }

        @Override
        String describe() {
            return on() ? "on" : "off";
        }
    }

    public static final class Real extends Entry<Real> {
        public interface Apply {
            boolean apply(Context c, float v);
        }

        /** Whether a whole value is the one the entry already has; see CLOCK_HEIGHT. */
        interface Same {
            boolean is(float v);
        }

        private volatile float value;
        private float absentValue;
        private Apply effect;
        private Same same = v -> Float.compare(value, v) == 0;

        Real(SettingSpec spec) {
            super(spec);
            value = spec.defFloat;
            absentValue = spec.defFloat;
        }

        public float get() {
            return value;
        }

        public void set(float v) {
            value = v;
        }

        Real absent(float v) {
            absentValue = v;
            return this;
        }

        public Real apply(Apply apply) {
            this.effect = apply;
            return this;
        }

        /** Overrides "is this the value already here" for an entry whose setter keeps it elsewhere. */
        Real sameWith(Same same) {
            this.same = same;
            return this;
        }

        /** Written as Float.toString, read back as it is - the apply step is not run. */
        Real file(String name) {
            return file(name, () -> String.valueOf(get()), v -> set(Float.parseFloat(v)));
        }

        @Override
        boolean fromIntent(Context c, Intent i) {
            float next;
            if (absent == Absent.IGNORE) {
                if (!i.hasExtra(extra)) return false;
                next = i.getFloatExtra(extra, get());
            } else if (absent == Absent.KEEP) {
                next = i.getFloatExtra(extra, get());
            } else {
                next = i.getFloatExtra(extra, absentValue);
            }
            return apply(c, next);
        }

        /** The op path's whole-value apply: the bound effect, or the value alone. */
        boolean apply(Context c, float v) {
            if (effect != null) return effect.apply(c, v);
            set(v);
            return true;
        }

        /** The batch's way in: a value the entry already has is not applied at all. */
        boolean applyValue(Context c, float v) {
            if (same.is(v)) return false;
            return apply(c, v);
        }

        Change change(float v) {
            return new Change(this, same.is(v), c -> applyValue(c, v), () -> set(v));
        }

        @Override
        void putDefault(Bundle out) {
            out.putFloat(spec.key, get());
        }

        @Override
        String describe() {
            return String.valueOf(get());
        }
    }

    public static final class Int extends Entry<Int> {
        public interface Apply {
            boolean apply(Context c, int v);
        }

        private volatile int value;
        private int absentValue;
        private Apply effect;
        /** Validates a whole value from any source: the op, the file, or the app's write. */
        private java.util.function.IntUnaryOperator check = v -> v;

        Int(SettingSpec spec) {
            super(spec);
            value = spec.defInt;
            absentValue = spec.defInt;
        }

        public int get() {
            return value;
        }

        public void set(int v) {
            value = v;
        }

        Int absent(int v) {
            absentValue = v;
            return this;
        }

        public Int apply(Apply apply) {
            this.effect = apply;
            return this;
        }

        /** Keeps a whole value inside the spec's range wherever it came from. */
        Int clamp(java.util.function.IntUnaryOperator check) {
            this.check = check;
            return this;
        }

        int checked(int v) {
            return check.applyAsInt(v);
        }

        /** Written as a decimal, read back as it is - the apply step is not run. */
        Int file(String name) {
            return file(name, () -> String.valueOf(get()), v -> set(checked(Integer.parseInt(v))));
        }

        @Override
        boolean fromIntent(Context c, Intent i) {
            int next;
            switch (absent) {
                case IGNORE:
                    if (!i.hasExtra(extra)) return false;
                    next = i.getIntExtra(extra, get());
                    break;
                case KEEP:
                    next = i.getIntExtra(extra, get());
                    break;
                case CYCLE: {
                    // Anything outside the range counts as absent, so a bad value cycles too.
                    int lo = (int) spec.min, hi = (int) spec.max;
                    int v = i.getIntExtra(extra, lo - 1);
                    next = v >= lo && v <= hi ? v : lo + (get() - lo + 1) % (hi - lo + 1);
                    break;
                }
                default:
                    next = i.getIntExtra(extra, absentValue);
            }
            return apply(c, checked(next));
        }

        /** The op path's whole-value apply: the bound effect, or the value alone. */
        boolean apply(Context c, int v) {
            if (effect != null) return effect.apply(c, v);
            set(v);
            return true;
        }

        /** The batch's way in: a value the entry already has is not applied at all. */
        boolean applyValue(Context c, int v) {
            int next = checked(v);
            if (next == get()) return false;
            return apply(c, next);
        }

        Change change(int v) {
            int next = checked(v);
            return new Change(this, next == get(), c -> applyValue(c, next), () -> set(next));
        }

        @Override
        void putDefault(Bundle out) {
            out.putInt(spec.key, get());
        }

        @Override
        String describe() {
            return String.valueOf(get());
        }
    }

    /** A value object (a style), set by an op whose extras only it knows how to read. */
    public static final class Ref<T> extends Entry<Ref<T>> {
        public interface Apply<T> {
            /** Applies a whole value. False when nothing changed. */
            boolean apply(Context c, T v);
        }

        /** Reads an op's extras into the whole value it means; null when the op has none. */
        interface Params<T> {
            T parse(Intent i);
        }

        private volatile T value;
        private Apply<T> effect;
        private Params<T> params;
        private java.util.function.BiPredicate<T, T> same = (a, b) -> a == b;

        Ref(SettingSpec spec, T initial) {
            super(spec);
            value = initial;
        }

        public T get() {
            return value;
        }

        public void set(T v) {
            value = v;
        }

        public Ref<T> apply(Apply<T> apply) {
            this.effect = apply;
            return this;
        }

        /** How the op's extras become a whole value - the `key`/`v` pair, or the appearance JSON. */
        Ref<T> params(Params<T> params) {
            this.params = params;
            return this;
        }

        /** Whether two values are the same; identity unless the value object says otherwise. */
        Ref<T> sameAs(java.util.function.BiPredicate<T, T> same) {
            this.same = same;
            return this;
        }

        @Override
        boolean fromIntent(Context c, Intent i) {
            T next = params == null ? null : params.parse(i);
            return next != null && apply(c, next);
        }

        /** The op path's whole-value apply: the bound effect, or the value alone. */
        boolean apply(Context c, T v) {
            if (effect != null) return effect.apply(c, v);
            set(v);
            return true;
        }

        /** The batch's way in: a value the entry already has is not applied at all. */
        boolean applyValue(Context c, T v) {
            if (same.test(get(), v)) return false;
            return apply(c, v);
        }

        Change change(T v) {
            return new Change(this, same.test(get(), v), c -> applyValue(c, v), () -> set(v));
        }

        @Override
        void putDefault(Bundle out) {
            out.putString(spec.key, String.valueOf(get()));
        }

        @Override
        String describe() {
            return String.valueOf(get());
        }
    }

    // ---------------------------------------------------------------- the table

    // The cover.

    /**
     * 启用音乐锁屏封面, the app's master switch for the cover. Unlike AUTO it is the user's, and it
     * shuts every way in rather than only the automatic one: off, the card going up does not open the
     * cover and neither does a tap on the artwork or on the music island - the island opens the plain
     * card instead. The rest of the module (islands, shade, card tweaks) goes on. On when absent from
     * the state file: every phone had the cover before the switch existed.
     */
    public static final Bool COVER_FEATURE = new Bool(SettingSpec.COVER_FEATURE)
            .op("coverfeature", "on", Absent.DEFAULT).absent(true).save(Save.NONE)
            .apply((c, v) -> {
                Main.setCoverFeature(v);
                return true;
            })
            .file("coverfeature");

    /**
     * Cover mode follows the media card. Not the user's: with it off the module does nothing at all,
     * which is not a state worth offering - uninstalling is the way to turn the module off. The adb
     * "auto" op can still flip it for a debugging session; not kept, and loadState turns it back on
     * at every start whatever an old file said.
     */
    public static final Bool AUTO = new Bool(SettingSpec.AUTO)
            .op("auto", "on", Absent.DEFAULT).absent(true).save(Save.NONE)
            .apply((c, v) -> {
                Main.setAuto(v);
                return true;
            });

    /** Kept as it was set: a value read from the file is not clamped (setBias clamps). */
    public static final Real BIAS = new Real(SettingSpec.BIAS)
            .op("bias", "v", Absent.DEFAULT).save(Save.NONE)
            .apply((c, v) -> {
                Main.setBias(v);
                return true;
            })
            .file("bias");

    /**
     * Only the mode is the user's: the square's size, place and corners are fixed, and
     * covercardfill, covercardpos, covercardcorner and the dp covercardsize are not read back -
     * a file from before that is not allowed to hold them open. See CoverCardStyle.
     */
    public static final Ref<CoverCardStyle> COVER_STYLE =
            new Ref<CoverCardStyle>(SettingSpec.COVER_STYLE, CoverCardStyle.defaults())
            .op("coverstyle", "key", Absent.IGNORE).save(Save.SOON)
            .params(i -> i.hasExtra("key")
                    ? Settings.COVER_STYLE.get().with(i.getStringExtra("key"),
                            i.getFloatExtra("v", Float.NaN))
                    : null)
            .sameAs((a, b) -> a.mode == b.mode && Float.compare(a.fill, b.fill) == 0
                    && Float.compare(a.pos, b.pos) == 0 && Float.compare(a.corner, b.corner) == 0)
            .apply((c, next) -> {
                CoverCardStyle was = Settings.COVER_STYLE.get();
                Settings.COVER_STYLE.set(next);
                CoverCardLayer.style(next);
                CoverCardLayer.refresh();
                if (Main.sCoverMode && next.mode != was.mode) {
                    // Leaving 保留壁纸, the dim belongs to the style that is going away. The
                    // clock may well stay held (another style's cover), so no exit frame will
                    // clear it.
                    if (!Main.keepsWallpaper()) KeepDim.apply(0f);
                    if (next.mode == CoverCardStyle.CARD) {
                        CoverCardLayer.attach(CoverPush.coverLayer());
                        CoverCardLayer.playback(Main.sCoverCardPlaying);
                    }
                    CoverPush.pushArtAsync(true, false);
                    // Into or out of 保留壁纸, the clock changes hands between cover mode and
                    // the lyric page.
                    Main.syncCoverHold(true);
                }
                return true;
            })
            .file("coverstyle", () -> String.valueOf(Settings.COVER_STYLE.get().mode),
                    v -> Settings.COVER_STYLE.set(Settings.COVER_STYLE.get().with("mode", Float.parseFloat(v))))
            // No covercardcorner: the square's corners are the media card's own, and the app
            // draws them from the cardradius it gets with the pictures.
            .answer(out -> {
                CoverCardStyle s = Settings.COVER_STYLE.get();
                out.putInt(SettingSpec.COVER_STYLE.key, s.mode);
                out.putFloat(SettingSpec.COVER_CARD_FILL.key, s.fill);
                out.putFloat(SettingSpec.COVER_CARD_POS.key, s.pos);
            });

    // The clock.

    /**
     * A pending pre-dp value is written as itself: it cannot be converted until a confirmed box
     * exists, and writing the default over it would lose the setting the user actually had.
     */
    public static final Real CLOCK_HEIGHT = new Real(SettingSpec.CLOCK_HEIGHT)
            .op("clockscale", "v", Absent.DEFAULT)
            // A value too small for dp is a pre-dp one, kept in Main until the clock is measured
            // rather than written here (setClockHeightDp). Comparing the mirror alone would call
            // that value "already set".
            .sameWith(v -> Float.compare(Float.isNaN(Main.sClockLegacyK)
                    ? Settings.CLOCK_HEIGHT.get() : Main.sClockLegacyK, v) == 0)
            .apply((c, v) -> {
                Main.setClockHeightDp(v);
                return true;
            })
            // A settled clock has no frames left to carry a new size on; ask for one.
            .then(c -> ClockCollapse.refresh())
            .file("clock", () -> String.valueOf(Float.isNaN(Main.sClockLegacyK)
                            ? Settings.CLOCK_HEIGHT.get() : Main.sClockLegacyK),
                    v -> Main.setClockHeightDp(Float.parseFloat(v)));

    /** NaN = follow the height; the query answers the size the clock is at either way. */
    public static final Real CLOCK_SIZE = new Real(SettingSpec.CLOCK_SIZE)
            .op("clocksize", "v", Absent.DEFAULT).absent(1f)
            .apply((c, v) -> {
                Main.setClockSize(v);
                return true;
            })
            .then(c -> ClockCollapse.refresh())
            .file("clocksize", () -> Float.isNaN(Settings.CLOCK_SIZE.get()) ? null : String.valueOf(Settings.CLOCK_SIZE.get()),
                    v -> {
                        sClockSizeFromFile = true;
                        Main.setClockSize(Float.parseFloat(v));
                    })
            .answer(out -> {
                out.putFloat(SettingSpec.CLOCK_SIZE.key, Main.effectiveClockSize());
                // Beside the size, not under a setting's key: what is asked is whether the user
                // ever chose a size, not what the clock came to. See sClockSizeFromFile.
                out.putBoolean("clocksizeset", sClockSizeFromFile);
            });

    /**
     * How far the date and the clock are moved together, in dp, from where cover mode puts them.
     * Positive is down.
     *
     * clockoff, glass and spring are not kept: the clock's offset, the glass clock's end and the
     * transition's spring are all fixed - no slider, and loadState reads none of them. Each is still
     * reachable from its own op, for the session.
     */
    public static final Real CLOCK_OFFSET = new Real(SettingSpec.CLOCK_OFFSET)
            .op("clockoffset", "v", Absent.DEFAULT)
            .apply((c, v) -> {
                Main.setClockOffsetDp(v);
                return true;
            })
            .then(c -> ClockCollapse.refresh());

    public static final Real GLASS_END = new Real(SettingSpec.GLASS_END)
            .op("glassend", "v", Absent.DEFAULT)
            .apply((c, v) -> {
                Settings.GLASS_END.set(Main.clamp01(v));
                return true;
            })
            .then(c -> ClockCollapse.refresh());

    /**
     * What the transition runs on, and the wallpaper crossfade with it (see Main.fadeMsFor). Fixed at
     * the OEM preset for this transition; `op clockspring` moves it for the rest of the session.
     * Nothing forced on screen: see Main.setClockResponse, which also saves.
     */
    public static final Real SPRING = new Real(SettingSpec.SPRING)
            .op("clockspring", "v", Absent.DEFAULT).save(Save.NONE)
            .apply((c, v) -> {
                Main.setClockResponse(v);
                return true;
            });

    /**
     * Keep cover mode's small clock in the always-on display instead of handing it back to the OEM's.
     *
     * About the FULL-SCREEN AOD only - the one that shows the whole lock screen, dimmed. That is the
     * mode the user asked for, and the one where the OEM itself is trying to show the lock screen's
     * clock (see ClockCollapse.sAodHeld). The plain linkage AOD and the classic plugin AOD are left
     * exactly as they were.
     *
     * One setting for both views of the lock screen: the lyrics are a layer over cover mode rather
     * than a mode of their own, so "cover" and "lyrics" have no separate AOD to disagree about.
     *
     * On by default: the grow-back was tried and read as the cover being taken away at the moment the
     * screen went off. The app's row says it the other way round (the OEM's big clock wanted), and
     * inverts it, once, where it reads the key.
     */
    public static final Bool AOD_SMALL = new Bool(SettingSpec.AOD_SMALL)
            .op("aodclock", "small", Absent.TOGGLE)
            .then(c -> {
                // The pose a held doze was drawn at is the small one, and the fall into an OEM
                // doze aims at that remembered pose rather than at the live clock. Left
                // standing, turning the setting off would still land on the small clock once.
                // Dropped, it is the state a doze that never settled is in.
                ClockCollapse.forgetAodPose();
                Xp.log(Main.TAG + "AOD keeps the small clock " + (Settings.AOD_SMALL.on() ? "on" : "off")
                        + " (full-screen AOD now: " + Main.fullAodOn() + ")");
            })
            .file("aodsmall");

    /**
     * The always-on display draws its own clock in its own process, which can read neither this
     * flag nor the file it is saved in, so the switch is mirrored somewhere both can see. Nothing
     * to re-apply here: the clock asks its bean whether to draw the colon on the next layout,
     * which the keyguard does every time it comes up.
     */
    public static final Bool COLON = new Bool(SettingSpec.COLON)
            .op("colon", "on", Absent.TOGGLE)
            .then(c -> HyperTweaks.publishColon(Main.sAppCtx))
            .file("colon");

    // The media card. One op for the four, saved and applied once whichever came.

    /**
     * The media card, restyled: its thumbnail hidden. This is the first thing the module changes
     * about the card itself, and it follows the same ownership rule as the cut-out: the card is
     * rebound on every track change and re-laid out constantly, so the look is asserted every frame
     * from a guard rather than set once.
     *
     * This and MC_ART_IN_LYRICS are both ON, and neither is the user's: the app has no switches for
     * them and the state file does not keep them. Off was the cautious default while they were new;
     * on is the look this ships with - the artwork is already the wallpaper, so the thumbnail is
     * showing it a second time. Both apply in cover mode only. `op mediacard` still moves them for
     * the rest of the session.
     */
    public static final Bool MC_HIDE_ART = new Bool(SettingSpec.MC_HIDE_ART)
            .op("mediacard", "hideart", Absent.IGNORE).absent(false).save(Save.NONE);

    /**
     * Whether the hidden thumbnail comes back while the lock screen lyrics are up.
     *
     * Only means anything with MC_HIDE_ART on, which is what the app's layout says: it is the
     * exception to that, not a setting of its own. Also fixed on, and for the same reason: the lyrics
     * are up for most of a track, and a card that has lost its thumbnail by then has nothing to come
     * back for.
     */
    public static final Bool MC_ART_IN_LYRICS = new Bool(SettingSpec.MC_ART_IN_LYRICS)
            .op("mediacard", "lyricart", Absent.IGNORE).absent(false).save(Save.NONE);

    /**
     * Whether tapping the card's title line toggles playback.
     *
     * It exists because of where the real button is: on this card it sits over the fingerprint
     * sensor, so a thumb aiming at pause is as likely to unlock the phone instead. The title is the
     * one part of the card that is both large and nowhere near the sensor.
     *
     * Applied on the lock screen only, like the two above, and for a sharper reason than
     * consistency: the shade shows the SAME card view, and in the shade the real button works and the
     * title is the OEM's. Held the same way too - asserted from the card guard rather than set once,
     * because the guard is already what tells us the card is still ours.
     */
    public static final Bool MC_TITLE_TAP = new Bool(SettingSpec.MC_TITLE_TAP)
            .op("mediacard", "titletap", Absent.IGNORE).absent(false).save(Save.NONE)
            .file("mctap");

    /**
     * A button on the card that turns the cover page into the lyric page and back - the same switch
     * as the two-finger tap, for anyone who would rather see it than know it. It covers the player's
     * own button to the right of "next", which is why it is off by default. Only drawn in cover mode,
     * on the lock screen, for a track that has lyrics: anywhere else there is only one page and
     * nothing for it to do. See CardPageButton.
     */
    public static final Bool MC_PAGE_BUTTON = new Bool(SettingSpec.MC_PAGE_BUTTON)
            .op("mediacard", "pagebtn", Absent.IGNORE).absent(false).save(Save.NONE)
            .file("mcpage");

    static {
        FINISH.put("mediacard", c -> {
            ModuleState.saveState();
            Xp.log(Main.TAG + "media card hideArt=" + Settings.MC_HIDE_ART.on()
                    + " artInLyrics=" + Settings.MC_ART_IN_LYRICS.on()
                    + " titleTap=" + Settings.MC_TITLE_TAP.on() + " pageBtn=" + Settings.MC_PAGE_BUTTON.on());
            MediaCard.applyMediaCard();
        });
    }

    /**
     * The lock screen media card's progress bar, lit the way the island's is.
     *
     * On by default. Switched off it takes effect on the next card the OEM builds: the mode is read in
     * the widget's constructor and the shader it decides on is a final field, so a bar already
     * running it cannot be talked out of it - the probe changes the one on screen in place instead
     * (ProbeReceiver.settingAnswer, which also answers what became of it).
     */
    public static final Bool SEEK_GLOW = new Bool(SettingSpec.SEEK_GLOW)
            .op("seekglow", "on", Absent.TOGGLE)
            .file("seekglow");

    // The transition.

    /**
     * A single tap on the cover puts the wallpaper back, the way tapping Apple's full-bleed artwork
     * does. Tapping again brings the cover back.
     */
    public static final Bool TAP_TOGGLE = new Bool(SettingSpec.TAP_TOGGLE)
            .op("tap", "on", Absent.TOGGLE)
            .file("tap");

    /**
     * Whether the wallpaper crossfades between the album cover and the lock wallpaper instead of
     * swapping in one frame. The fade itself runs in the wallpaper process, which is the only place
     * that holds both pictures; all this decides is whether to ask for it.
     */
    public static final Bool FADE_WALLPAPER = new Bool(SettingSpec.FADE_WALLPAPER)
            .op("fadewp", "on", Absent.TOGGLE)
            .file("fadewp");

    /**
     * Apple Music's motion artwork in place of the still cover. Off by default. See MotionCover.
     */
    public static final Bool MOTION = new Bool(SettingSpec.MOTION)
            .op("motion", "on", Absent.IGNORE).absent(false)
            // The cover on screen now gets one, or loses its own.
            .then(c -> {
                if (Main.sCoverMode) CoverPush.pushArtAsync(true, false);
            })
            .file("motion");

    /**
     * Look up and download motion artwork on a metered network too - mobile data. Off by default.
     */
    public static final Bool MOTION_ON_DATA = new Bool(SettingSpec.MOTION_ON_DATA)
            .op("motion", "data", Absent.IGNORE).absent(false)
            .then(c -> {
                if (Settings.MOTION_ON_DATA.on()) Main.main().post(MotionCover::retry);
            })
            .file("motiondata");

    /**
     * Whether the COVER VIDEO ITSELF crossfades into the cover, in the wallpaper window.
     *
     * Off, and it is off because of what it cost when it was on: measured on the device, the cards'
     * blurred background stopped switching to the cover at all - the window never showed the new
     * frame. A cover video with a fade in it is a file of a dozen frames, a frame of the user's own
     * wallpaper has to be decoded to build it, and both ends of it are converted to YUV; any of those
     * going wrong leaves the reload at the end of that chain never happening, while the SystemUI view
     * - which is put up by the other process entirely - goes on showing the cover. A one-frame cover,
     * which is what this was before the fade existed, cannot fail that way.
     *
     * The lag it was meant to hide is handled where it actually lives: see FADE_MODE. Kept as a switch
     * rather than deleted because the muxer now handles any frame count (checked off the device: two
     * samples in, byte-identical file out) and this is one adb line to try again. A change re-pushes
     * the art, because the cover video on the device right now was built with whichever value was in
     * force when it was pushed.
     */
    public static final Bool VIDEO_FADE = new Bool(SettingSpec.VIDEO_FADE)
            .op("vcfade", "on", Absent.TOGGLE)
            .then(c -> {
                if (Main.sCoverMode) CoverPush.pushArtAsync(true, false);
            })
            .file("vcfade");

    /** `--ei mode 0|1|2` picks one; without it the op cycles, to compare the three on the phone. */
    public static final Int FADE_MODE = new Int(SettingSpec.FADE_MODE)
            .op("fadesync", "mode", Absent.CYCLE)
            .then(c -> Xp.log(Main.TAG + "cover fade mode = " + CoverPush.fadeModeName()
                    + " (0 off, 1 hold for the wallpaper window, 2 stretch by the"
                    + " last measured gap " + CoverPush.sCoverFadeGapMs + "ms)"))
            .file("fsmode2");

    // The fingerprint.

    /**
     * Hide the lock screen fingerprint ring. Unlike the card switches, this one is not tied to cover
     * mode: an icon that appeared and vanished as the music started and stopped would read as a glitch
     * rather than as a setting.
     */
    public static final Bool HIDE_FP = new Bool(SettingSpec.HIDE_FP)
            .op("hidefp", "on", Absent.TOGGLE)
            .then(c -> Fingerprint.applyHideFp())
            .file("hidefp");

    /**
     * Whether the notification stack keeps clear of the fingerprint icon: 0 leaves it to the system,
     * 1 never avoids it, 2 always does. Not a boolean, because "off" here would mean two different
     * things - stop reserving the space, or reserve it even with no print enrolled.
     */
    public static final Int FP_AVOID = new Int(SettingSpec.FP_AVOID)
            .op("fpavoid", "mode", Absent.DEFAULT)
            .then(c -> Main.refreshNotificationSink())
            .file("fpavoid");

    // The lyrics.

    /**
     * Whether the lyrics are the lock screen's to show: the master switch. The two-finger tap swaps
     * cover and lyrics only while it is on, and switching it takes the tap's answer with it (see
     * LockLyrics.setEnabled).
     *
     * Kept as "lyricmaster" rather than the old "lyrics", which is not read: a file from when that one
     * was written may still hold a 0 nobody remembers setting.
     */
    public static final Bool LYRICS = new Bool(SettingSpec.LYRICS)
            .op("lyrics", "on", Absent.TOGGLE)
            .file("lyricmaster");

    /**
     * Keep the screen lit while lyrics are playing on the lock screen.
     */
    public static final Bool LYRIC_KEEP_ON = new Bool(SettingSpec.LYRIC_KEEP_ON)
            .op("lyrickeep", "on", Absent.TOGGLE)
            .file("lyrickeep");

    /**
     * A held note's glow brighter than white on an HDR screen.
     *
     * Off by default, and it was off for a reason that has since been fixed: the colour mode is a
     * whole window's, and the lyrics used to share the lock screen's with the clock and the media
     * card, so the card's material was drawn as HDR content and brightened (reported 2026-09-16, still
     * there on 2026-09-29 with the headroom request removed - SurfaceFlinger picks its own 5.00). With
     * this switch on the lyrics have a window of their own, and only it is switched - see LyricWindow;
     * with it off they stay in the keyguard's layer, since the window costs them the lock screen's
     * z-order (see LockLyrics.attach). Off by default until that has been lived with.
     */
    public static final Bool LYRIC_HDR = new Bool(SettingSpec.LYRIC_HDR)
            .op("lyrichdr", "on", Absent.TOGGLE)
            .file("lyrichdr");

    /**
     * Whether a line's translation is drawn under it.
     *
     * On by default, because it is what the lyrics look like when the source has one: off is for
     * people who read the language and find the second line in the way. It is not a filter on what is
     * fetched - the layout drops the translation's rows, so the lines close up rather than leaving a
     * gap where it was. The view notices the switch itself and lays the lines out again around it;
     * refresh only has to start the frames that let it. Left at the default when absent from the file:
     * the key did not exist before this setting did.
     */
    public static final Bool LYRIC_TRANS = new Bool(SettingSpec.LYRIC_TRANS)
            .op("lyrictrans", "on", Absent.TOGGLE)
            .file("lyrictrans");

    /**
     * Whether a line's romanisation is drawn under it, over the translation. Off by default and apart
     * from the translation's switch - either can be shown alone (#64). Like the translation it is laid
     * out, not fetched: switching it redraws what is already there.
     */
    public static final Bool LYRIC_ROMA = new Bool(SettingSpec.LYRIC_ROMA)
            .op("lyricroma", "on", Absent.TOGGLE)
            .file("lyricroma");

    /**
     * Whether a lyric may come off the network (PR #103): the database, the TTML Hub, the catalogues,
     * the queue's read-ahead and the translations borrowed from any of them. Off, only the player's own
     * session, its file and the Lyricon bridge are read. On by default. Saved only when it changed.
     */
    public static final Bool LYRIC_ONLINE = new Bool(SettingSpec.LYRIC_ONLINE)
            .op("lyriconline", "on", Absent.TOGGLE)
            .file("lyriconline");

    /**
     * Where the lines settle in their column: LockLyrics.ALIGN_LEFT, _CENTER or _RIGHT.
     *
     * Unlike the switches this cannot be drawn around once changed - the alignment is baked into every
     * StaticLayout - so LyricView lays the lines out again when this differs from what the layout in
     * use was built with (see its builtAlign). Left is what the lyrics did before the setting existed,
     * and what a state file written before it loads as.
     *
     * Saved the way the style is: a quick run of picks is one write. Clamped here, from the file
     * as from an op (the feature's setAlign clamps again, harmlessly).
     */
    public static final Int LYRIC_ALIGN = new Int(SettingSpec.LYRIC_ALIGN)
            .op("lyricalign", "v", Absent.KEEP).save(Save.SOON)
            // The clamp LockLyrics.setAlign does, kept in the table now that the effect is the
            // feature's: the file and the app's write come through here too.
            .clamp(v -> v < 0 ? 0 : Math.min(v, 2))
            .file("lyricalign", () -> String.valueOf(Settings.LYRIC_ALIGN.get()),
                    v -> Settings.LYRIC_ALIGN.set(Settings.LYRIC_ALIGN.checked(Integer.parseInt(v))));

    /**
     * Immutable snapshot: the UI, renderer and state loader all use the same validated values. The two
     * sizes are the user's (the 歌词外观 page); the band's other four values are fixed and not kept,
     * see LyricStyle. Clamped by LyricStyle.with, from the file as from an op.
     */
    public static final Ref<LyricStyle> LYRIC_STYLE =
            new Ref<LyricStyle>(SettingSpec.LYRIC_SIZE, LyricStyle.DEFAULT)
            .op("lyricstyle", "key", Absent.IGNORE).save(Save.SOON)
            .params(i -> i.hasExtra("key")
                    ? Settings.LYRIC_STYLE.get().with(i.getStringExtra("key"),
                            i.getFloatExtra("v", Float.NaN))
                    : null)
            .sameAs((a, b) -> Float.compare(a.fill, b.fill) == 0
                    && Float.compare(a.pos, b.pos) == 0
                    && Float.compare(a.sideDp, b.sideDp) == 0
                    && Float.compare(a.sizeSp, b.sizeSp) == 0
                    && Float.compare(a.bgSizeSp, b.bgSizeSp) == 0 && a.weight == b.weight)
            .file("lyricsize", () -> String.valueOf(Settings.LYRIC_STYLE.get().sizeSp),
                    v -> Settings.LYRIC_STYLE.set(
                            Settings.LYRIC_STYLE.get().with("size", Float.parseFloat(v))))
            .file("lyricbgsize", () -> String.valueOf(Settings.LYRIC_STYLE.get().bgSizeSp),
                    v -> Settings.LYRIC_STYLE.set(
                            Settings.LYRIC_STYLE.get().with("bgsize", Float.parseFloat(v))))
            .answer(out -> {
                LyricStyle s = Settings.LYRIC_STYLE.get();
                out.putFloat(SettingSpec.LYRIC_BG_SIZE.key, s.bgSizeSp);
                out.putFloat(SettingSpec.LYRIC_FILL.key, s.fill);
                out.putFloat(SettingSpec.LYRIC_POS.key, s.pos);
                out.putFloat(SettingSpec.LYRIC_SIDE.key, s.sideDp);
                out.putFloat(SettingSpec.LYRIC_SIZE.key, s.sizeSp);
                out.putInt(SettingSpec.LYRIC_WEIGHT.key, s.weight);
            });

    /**
     * Colours and opacity of the lead and the backing vocal; the default is the official look. One
     * line of JSON in the file: no '=' or newline in it to confuse the reader.
     */
    public static final Ref<LyricAppearance> LYRIC_APPEARANCE =
            new Ref<LyricAppearance>(SettingSpec.LYRIC_APPEARANCE, LyricAppearance.DEFAULT)
            .op("lyricappearance", "json", Absent.IGNORE).save(Save.SOON)
            .params(i -> i.hasExtra("json")
                    ? LyricAppearance.parse(i.getStringExtra("json")) : null)
            .sameAs((a, b) -> a.toJson().equals(b.toJson()))
            .file("lyricappearance", () -> Settings.LYRIC_APPEARANCE.get().toJson(),
                    v -> Settings.LYRIC_APPEARANCE.set(LyricAppearance.parse(v)))
            .answer(out -> out.putString(SettingSpec.LYRIC_APPEARANCE.key,
                    Settings.LYRIC_APPEARANCE.get().toJson()));

    // The islands.

    /**
     * The app's 「高德公交地铁」 switch: off, neither the trip's lock screen page nor its island in 高德
     * (AmapTransitShare.setEnabled) is put up; 高德 is told. On when absent from the file: the page and
     * the island shipped before their switch.
     */
    public static final Bool TRANSIT = new Bool(SettingSpec.TRANSIT)
            .op("transitcfg", "on", Absent.IGNORE).absent(true)
            .file("transit");

    /**
     * 取餐码: a 微信 order page's pickup code on the island. On unless turned off.
     */
    public static final Bool PICKUP = new Bool(SettingSpec.PICKUP)
            .op("pickup", "on", Absent.IGNORE).absent(true)
            .file("pickup");

    /**
     * Whether the pickup card is attached to the notification.
     *
     * On, and it is the point of the card now: this is HyperOS's own pickup card, taken from 小爱同学's
     * layout (PickupCard), so there is no longer a second card style to prefer. Turned off (`op pickup
     * --ez card false`) the system's focus template is back and the code is read from the param alone.
     * Kept, or that would last only until the next SystemUI start.
     */
    public static final Bool PICKUP_CARD = new Bool(SettingSpec.PICKUP_CARD)
            .op("pickup", "card", Absent.IGNORE).absent(true)
            .file("card");

    // ---------------------------------------------------------------- the doors

    /** Every entry, in the table's order. */
    static List<Entry<?>> all() {
        return Collections.unmodifiableList(ALL);
    }

    /**
     * Applies [op] if it is one of the table's: each of its entries reads its extra, applies
     * what that says, saves and runs what follows a change. False when [op] is not a setting's.
     * Whatever else the op does (an answer, a command riding on it) is the caller's.
     */
    static boolean dispatch(Context c, Intent i, String op) {
        List<Entry<?>> entries = OPS.get(op);
        if (entries == null) return false;
        for (Entry<?> e : entries) {
            if (!e.fromIntent(c, i)) continue;
            Xp.log(Main.TAG + "setting " + e.spec.key + " = " + e.describe());
            // With the app's group as the source an op changes this session only (decision B):
            // the group is the truth, and the file is a downgrade copy the batches keep current.
            if (!SettingsPrefs.usesPrefs()) {
                if (e.save == Save.NOW) ModuleState.saveState();
                else if (e.save == Save.SOON) ModuleState.saveStateSoon();
            }
            if (e.after != null) e.after.run(c);
        }
        After finish = FINISH.get(op);
        if (finish != null) finish.run(c);
        return true;
    }

    /**
     * Applies whole values as one batch - what one write from the app's settings becomes (stage
     * 5.2). Entries go in the table's order, each at most once; one already holding its value is
     * left alone, so a migration arriving with what memory already has does nothing at all. The
     * state file is written once for the batch, an After shared by several entries runs once, and
     * an op's finish runs once whichever of its entries changed - the media card's four are one
     * op and still one finish.
     *
     * <p>An adb op does not come through here: {@link #dispatch} keeps applying each op on its
     * own, a repeated value included.
     */
    static boolean applyBatch(Context c, List<Change> changes) {
        Map<Entry<?>, Change> byEntry = new HashMap<>();
        for (Change change : changes) byEntry.put(change.entry, change);
        List<Change> ordered = new ArrayList<>(byEntry.values());
        ordered.sort(Comparator.comparingInt(ch -> ALL.indexOf(ch.entry)));

        List<Change> applied = new ArrayList<>();
        for (Change change : ordered) {
            if (change.same) continue;
            change.run.accept(c);
            Xp.log(Main.TAG + "setting " + change.entry.spec.key + " = "
                    + change.entry.describe());
            applied.add(change);
        }
        if (applied.isEmpty()) return false;

        boolean now = false, soon = false;
        for (Change change : applied) {
            if (change.entry.save == Save.NOW) now = true;
            else if (change.entry.save == Save.SOON) soon = true;
        }
        if (now) ModuleState.saveState();
        else if (soon) ModuleState.saveStateSoon();

        // By instance: a feature registering one After for several of its entries gets one run.
        Set<After> afters = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Change change : applied) {
            After after = change.entry.after;
            if (after != null && afters.add(after)) after.run(c);
        }

        Set<String> ops = new LinkedHashSet<>();
        for (Change change : applied) {
            if (change.entry.op != null) ops.add(change.entry.op);
        }
        for (String op : ops) {
            After finish = FINISH.get(op);
            if (finish != null) finish.run(c);
        }
        return true;
    }

    /**
     * Whether the app keeps this setting in its own preferences (stage 5.2). The six without a
     * file key are the ones nothing keeps today (AUTO forces itself on at every start, and the
     * rest are per-session debug values); they stay out of prefs the same way.
     */
    static boolean persisted(Entry<?> e) {
        return !e.files.isEmpty();
    }

    /** A whole value for one of the value-object entries, from the parts a prefs read gives. */
    static Change coverStyle(int mode) {
        return COVER_STYLE.change(COVER_STYLE.get().with("mode", mode));
    }

    static Change lyricStyle(float sizeSp, float bgSizeSp) {
        return LYRIC_STYLE.change(LYRIC_STYLE.get().with("size", sizeSp).with("bgsize", bgSizeSp));
    }

    static Change lyricAppearance(String json) {
        return LYRIC_APPEARANCE.change(LyricAppearance.parse(json));
    }

    /**
     * What a first installation holds: every kept setting back to its default, applied as one
     * batch so the effects run the way they do for a page's change (stage 5.5). Called when the
     * installation fingerprint finds that this host's files belong to an installation that is
     * gone; a setting that is already at its default is left alone.
     */
    static void resetDefaults(Context c) {
        List<Change> changes = new ArrayList<>();
        for (Entry<?> e : ALL) {
            if (!persisted(e)) continue;
            Change change = defaultChange(e);
            if (change != null) changes.add(change);
        }
        applyBatch(c, changes);
    }

    private static Change defaultChange(Entry<?> e) {
        if (e == COVER_STYLE) return COVER_STYLE.change(CoverCardStyle.defaults());
        if (e == LYRIC_STYLE) return LYRIC_STYLE.change(LyricStyle.DEFAULT);
        if (e == LYRIC_APPEARANCE) return LYRIC_APPEARANCE.change(LyricAppearance.DEFAULT);
        // A first installation has no size of its own: NaN follows the clock's height, where
        // the spec's default would fix it to one fraction.
        if (e == CLOCK_SIZE) return CLOCK_SIZE.change(Float.NaN);
        if (e instanceof Bool) return ((Bool) e).change(e.spec.defBool);
        if (e instanceof Int) return ((Int) e).change(e.spec.defInt);
        if (e instanceof Real) return ((Real) e).change(e.spec.defFloat);
        return null;
    }

    /** Every setting's answer to the query. */
    static void query(Bundle out) {
        for (Entry<?> e : ALL) e.query(out);
    }
}

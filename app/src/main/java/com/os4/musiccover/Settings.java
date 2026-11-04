package com.os4.musiccover;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.os4.musiccover.lyrics.LockLyrics;
import com.os4.musiccover.lyrics.LyricStyle;
import com.os4.musiccover.lyrics.LyricAppearance;
import com.os4.musiccover.pickup.PickupCodeIsland;
import com.os4.musiccover.transit.AmapTransitScene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    interface After {
        void run(Context c);
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

        /** Run after a change the op applied, after it is saved. */
        E then(After after) {
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
        interface Store {
            boolean get();

            void set(boolean v);
        }

        interface Apply {
            /** False when the value did not change and nothing follows. */
            boolean apply(Context c, boolean v);
        }

        private volatile boolean value;
        private Store store;
        private boolean absentValue;
        private Apply apply;

        Bool(SettingSpec spec) {
            super(spec);
            value = spec.defBool;
        }

        public boolean on() {
            return store != null ? store.get() : value;
        }

        public void set(boolean v) {
            if (store != null) store.set(v);
            else value = v;
        }

        Bool store(Store store) {
            this.store = store;
            return this;
        }

        /** The value for {@link Absent#DEFAULT}, and the fallback for an extra of the wrong type. */
        Bool absent(boolean v) {
            absentValue = v;
            return this;
        }

        Bool apply(Apply apply) {
            this.apply = apply;
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
            if (apply != null) return apply.apply(c, next);
            set(next);
            return true;
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
        interface Store {
            float get();

            void set(float v);
        }

        interface Apply {
            boolean apply(Context c, float v);
        }

        private volatile float value;
        private Store store;
        private float absentValue;
        private Apply apply;

        Real(SettingSpec spec) {
            super(spec);
            value = spec.defFloat;
            absentValue = spec.defFloat;
        }

        public float get() {
            return store != null ? store.get() : value;
        }

        public void set(float v) {
            if (store != null) store.set(v);
            else value = v;
        }

        Real store(Store store) {
            this.store = store;
            return this;
        }

        Real absent(float v) {
            absentValue = v;
            return this;
        }

        Real apply(Apply apply) {
            this.apply = apply;
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
            if (apply != null) return apply.apply(c, next);
            set(next);
            return true;
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
        interface Store {
            int get();

            void set(int v);
        }

        interface Apply {
            boolean apply(Context c, int v);
        }

        private volatile int value;
        private Store store;
        private int absentValue;
        private Apply apply;

        Int(SettingSpec spec) {
            super(spec);
            value = spec.defInt;
            absentValue = spec.defInt;
        }

        public int get() {
            return store != null ? store.get() : value;
        }

        public void set(int v) {
            if (store != null) store.set(v);
            else value = v;
        }

        Int store(Store store) {
            this.store = store;
            return this;
        }

        Int absent(int v) {
            absentValue = v;
            return this;
        }

        Int apply(Apply apply) {
            this.apply = apply;
            return this;
        }

        /** Written as a decimal, read back as it is - the apply step is not run. */
        Int file(String name) {
            return file(name, () -> String.valueOf(get()), v -> set(Integer.parseInt(v)));
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
            if (apply != null) return apply.apply(c, next);
            set(next);
            return true;
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
        interface Store<T> {
            T get();

            void set(T v);
        }

        interface Apply {
            /** Reads the op's extras and applies them. False when nothing changed. */
            boolean apply(Context c, Intent i);
        }

        private volatile T value;
        private Store<T> store;
        private Apply apply;

        Ref(SettingSpec spec, T initial) {
            super(spec);
            value = initial;
        }

        public T get() {
            return store != null ? store.get() : value;
        }

        public void set(T v) {
            if (store != null) store.set(v);
            else value = v;
        }

        Ref<T> store(Store<T> store) {
            this.store = store;
            return this;
        }

        Ref<T> apply(Apply apply) {
            this.apply = apply;
            return this;
        }

        @Override
        boolean fromIntent(Context c, Intent i) {
            return apply != null && apply.apply(c, i);
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

    public static final Bool COVER_FEATURE = new Bool(SettingSpec.COVER_FEATURE)
            .store(new Bool.Store() {
                public boolean get() { return Main.sCoverFeatureOn; }
                public void set(boolean v) { Main.sCoverFeatureOn = v; }
            })
            .op("coverfeature", "on", Absent.DEFAULT).absent(true).save(Save.NONE)
            .apply((c, v) -> {
                Main.setCoverFeature(v);
                return true;
            })
            .file("coverfeature");

    /** Not kept: loadState turns it on at every start, whatever an old file said. */
    public static final Bool AUTO = new Bool(SettingSpec.AUTO)
            .store(new Bool.Store() {
                public boolean get() { return Main.sAuto; }
                public void set(boolean v) { Main.sAuto = v; }
            })
            .op("auto", "on", Absent.DEFAULT).absent(true).save(Save.NONE)
            .apply((c, v) -> {
                Main.setAuto(v);
                return true;
            });

    /** Kept as it was set: a value read from the file is not clamped (setBias clamps). */
    public static final Real BIAS = new Real(SettingSpec.BIAS)
            .store(new Real.Store() {
                public float get() { return Main.sBias; }
                public void set(float v) { Main.sBias = v; }
            })
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
            .store(new Ref.Store<CoverCardStyle>() {
                public CoverCardStyle get() { return Main.sCoverCardStyle; }
                public void set(CoverCardStyle v) { Main.sCoverCardStyle = v; }
            })
            .op("coverstyle", "key", Absent.IGNORE).save(Save.SOON)
            .apply((c, i) -> {
                CoverCardStyle was = Main.sCoverCardStyle;
                CoverCardStyle next = was.with(i.getStringExtra("key"),
                        i.getFloatExtra("v", Float.NaN));
                Main.sCoverCardStyle = next;
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
            .file("coverstyle", () -> String.valueOf(Main.sCoverCardStyle.mode),
                    v -> Main.sCoverCardStyle = Main.sCoverCardStyle.with("mode", Float.parseFloat(v)))
            // No covercardcorner: the square's corners are the media card's own, and the app
            // draws them from the cardradius it gets with the pictures.
            .answer(out -> {
                CoverCardStyle s = Main.sCoverCardStyle;
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
            .store(new Real.Store() {
                public float get() { return Main.sClockHeightDp; }
                public void set(float v) { Main.sClockHeightDp = v; }
            })
            .op("clockscale", "v", Absent.DEFAULT)
            .apply((c, v) -> {
                Main.setClockHeightDp(v);
                return true;
            })
            // A settled clock has no frames left to carry a new size on; ask for one.
            .then(c -> ClockCollapse.refresh())
            .file("clock", () -> String.valueOf(Float.isNaN(Main.sClockLegacyK)
                            ? Main.sClockHeightDp : Main.sClockLegacyK),
                    v -> Main.setClockHeightDp(Float.parseFloat(v)));

    /** NaN = follow the height; the query answers the size the clock is at either way. */
    public static final Real CLOCK_SIZE = new Real(SettingSpec.CLOCK_SIZE)
            .store(new Real.Store() {
                public float get() { return Main.sClockSize; }
                public void set(float v) { Main.sClockSize = v; }
            })
            .op("clocksize", "v", Absent.DEFAULT).absent(1f)
            .apply((c, v) -> {
                Main.setClockSize(v);
                return true;
            })
            .then(c -> ClockCollapse.refresh())
            .file("clocksize", () -> Float.isNaN(Main.sClockSize) ? null : String.valueOf(Main.sClockSize),
                    v -> Main.setClockSize(Float.parseFloat(v)))
            .answer(out -> out.putFloat(SettingSpec.CLOCK_SIZE.key, Main.effectiveClockSize()));

    /**
     * clockoff, glass and spring are not kept: the clock's offset, the glass clock's end and the
     * transition's spring are all fixed. Each is still reachable from its own op, for the
     * session.
     */
    public static final Real CLOCK_OFFSET = new Real(SettingSpec.CLOCK_OFFSET)
            .store(new Real.Store() {
                public float get() { return Main.sClockOffsetDp; }
                public void set(float v) { Main.sClockOffsetDp = v; }
            })
            .op("clockoffset", "v", Absent.DEFAULT)
            .apply((c, v) -> {
                Main.setClockOffsetDp(v);
                return true;
            })
            .then(c -> ClockCollapse.refresh());

    public static final Real GLASS_END = new Real(SettingSpec.GLASS_END)
            .store(new Real.Store() {
                public float get() { return Main.sGlassEnd; }
                public void set(float v) { Main.sGlassEnd = v; }
            })
            .op("glassend", "v", Absent.DEFAULT)
            .apply((c, v) -> {
                Main.sGlassEnd = Main.clamp01(v);
                return true;
            })
            .then(c -> ClockCollapse.refresh());

    /** Nothing forced on screen: see Main.setClockResponse, which also saves. */
    public static final Real SPRING = new Real(SettingSpec.SPRING)
            .store(new Real.Store() {
                public float get() { return Main.sClockResponse; }
                public void set(float v) { Main.sClockResponse = v; }
            })
            .op("clockspring", "v", Absent.DEFAULT).save(Save.NONE)
            .apply((c, v) -> {
                Main.setClockResponse(v);
                return true;
            });

    public static final Bool AOD_SMALL = new Bool(SettingSpec.AOD_SMALL)
            .store(new Bool.Store() {
                public boolean get() { return Main.sAodSmall; }
                public void set(boolean v) { Main.sAodSmall = v; }
            })
            .op("aodclock", "small", Absent.TOGGLE)
            .then(c -> {
                // The pose a held doze was drawn at is the small one, and the fall into an OEM
                // doze aims at that remembered pose rather than at the live clock. Left
                // standing, turning the setting off would still land on the small clock once.
                // Dropped, it is the state a doze that never settled is in.
                ClockCollapse.forgetAodPose();
                Xp.log(Main.TAG + "AOD keeps the small clock " + (Main.sAodSmall ? "on" : "off")
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
            .store(new Bool.Store() {
                public boolean get() { return HyperTweaks.sForceColon; }
                public void set(boolean v) { HyperTweaks.sForceColon = v; }
            })
            .op("colon", "on", Absent.TOGGLE)
            .then(c -> HyperTweaks.publishColon(Main.sAppCtx))
            .file("colon");

    // The media card. One op for the four, saved and applied once whichever came.

    public static final Bool MC_HIDE_ART = new Bool(SettingSpec.MC_HIDE_ART)
            .store(new Bool.Store() {
                public boolean get() { return Main.sMcHideArt; }
                public void set(boolean v) { Main.sMcHideArt = v; }
            })
            .op("mediacard", "hideart", Absent.IGNORE).absent(false).save(Save.NONE);

    public static final Bool MC_ART_IN_LYRICS = new Bool(SettingSpec.MC_ART_IN_LYRICS)
            .store(new Bool.Store() {
                public boolean get() { return Main.sMcArtInLyrics; }
                public void set(boolean v) { Main.sMcArtInLyrics = v; }
            })
            .op("mediacard", "lyricart", Absent.IGNORE).absent(false).save(Save.NONE);

    public static final Bool MC_TITLE_TAP = new Bool(SettingSpec.MC_TITLE_TAP)
            .store(new Bool.Store() {
                public boolean get() { return Main.sMcTitleTap; }
                public void set(boolean v) { Main.sMcTitleTap = v; }
            })
            .op("mediacard", "titletap", Absent.IGNORE).absent(false).save(Save.NONE)
            .file("mctap");

    public static final Bool MC_PAGE_BUTTON = new Bool(SettingSpec.MC_PAGE_BUTTON)
            .store(new Bool.Store() {
                public boolean get() { return Main.sMcPageBtn; }
                public void set(boolean v) { Main.sMcPageBtn = v; }
            })
            .op("mediacard", "pagebtn", Absent.IGNORE).absent(false).save(Save.NONE)
            .file("mcpage");

    static {
        FINISH.put("mediacard", c -> {
            ModuleState.saveState();
            Xp.log(Main.TAG + "media card hideArt=" + Main.sMcHideArt
                    + " artInLyrics=" + Main.sMcArtInLyrics
                    + " titleTap=" + Main.sMcTitleTap + " pageBtn=" + Main.sMcPageBtn);
            MediaCard.applyMediaCard();
        });
    }

    /**
     * The card on screen was built before this switch was read: the probe changes it in place
     * (ProbeReceiver, which also answers what became of it).
     */
    public static final Bool SEEK_GLOW = new Bool(SettingSpec.SEEK_GLOW)
            .store(new Bool.Store() {
                public boolean get() { return HyperTweaks.sBarGlow; }
                public void set(boolean v) { HyperTweaks.sBarGlow = v; }
            })
            .op("seekglow", "on", Absent.TOGGLE)
            .file("seekglow");

    // The transition.

    public static final Bool TAP_TOGGLE = new Bool(SettingSpec.TAP_TOGGLE)
            .store(new Bool.Store() {
                public boolean get() { return Main.sTapToggle; }
                public void set(boolean v) { Main.sTapToggle = v; }
            })
            .op("tap", "on", Absent.TOGGLE)
            .file("tap");

    public static final Bool FADE_WALLPAPER = new Bool(SettingSpec.FADE_WALLPAPER)
            .store(new Bool.Store() {
                public boolean get() { return Main.sFadeWp; }
                public void set(boolean v) { Main.sFadeWp = v; }
            })
            .op("fadewp", "on", Absent.TOGGLE)
            .file("fadewp");

    public static final Bool MOTION = new Bool(SettingSpec.MOTION)
            .store(new Bool.Store() {
                public boolean get() { return MotionCover.sEnabled; }
                public void set(boolean v) { MotionCover.sEnabled = v; }
            })
            .op("motion", "on", Absent.IGNORE).absent(false)
            // The cover on screen now gets one, or loses its own.
            .then(c -> {
                if (Main.sCoverMode) CoverPush.pushArtAsync(true, false);
            })
            .file("motion");

    public static final Bool MOTION_ON_DATA = new Bool(SettingSpec.MOTION_ON_DATA)
            .store(new Bool.Store() {
                public boolean get() { return MotionCover.sOnData; }
                public void set(boolean v) { MotionCover.sOnData = v; }
            })
            .op("motion", "data", Absent.IGNORE).absent(false)
            .then(c -> {
                if (MotionCover.sOnData) Main.main().post(MotionCover::retry);
            })
            .file("motiondata");

    /**
     * Off by default: see the note on Main.sVideoFade. A change re-pushes the art, because the
     * cover video on the device right now was built with whichever value was in force when it
     * was pushed.
     */
    public static final Bool VIDEO_FADE = new Bool(SettingSpec.VIDEO_FADE)
            .store(new Bool.Store() {
                public boolean get() { return Main.sVideoFade; }
                public void set(boolean v) { Main.sVideoFade = v; }
            })
            .op("vcfade", "on", Absent.TOGGLE)
            .then(c -> {
                if (Main.sCoverMode) CoverPush.pushArtAsync(true, false);
            })
            .file("vcfade");

    /** `--ei mode 0|1|2` picks one; without it the op cycles, to compare the three on the phone. */
    public static final Int FADE_MODE = new Int(SettingSpec.FADE_MODE)
            .store(new Int.Store() {
                public int get() { return Main.sFadeMode; }
                public void set(int v) { Main.sFadeMode = v; }
            })
            .op("fadesync", "mode", Absent.CYCLE)
            .then(c -> Xp.log(Main.TAG + "cover fade mode = " + CoverPush.fadeModeName()
                    + " (0 off, 1 hold for the wallpaper window, 2 stretch by the"
                    + " last measured gap " + CoverPush.sCoverFadeGapMs + "ms)"))
            .file("fsmode2");

    // The fingerprint.

    /** Also read on its own, before the state file is, by Fingerprint.peekHideFp. */
    public static final Bool HIDE_FP = new Bool(SettingSpec.HIDE_FP)
            .store(new Bool.Store() {
                public boolean get() { return Fingerprint.sHideFp; }
                public void set(boolean v) { Fingerprint.sHideFp = v; }
            })
            .op("hidefp", "on", Absent.TOGGLE)
            .then(c -> Fingerprint.applyHideFp())
            .file("hidefp");

    public static final Int FP_AVOID = new Int(SettingSpec.FP_AVOID)
            .store(new Int.Store() {
                public int get() { return Fingerprint.sFpAvoid; }
                public void set(int v) { Fingerprint.sFpAvoid = v; }
            })
            .op("fpavoid", "mode", Absent.DEFAULT)
            .then(c -> Main.refreshNotificationSink())
            .file("fpavoid");

    // The lyrics.

    /**
     * Kept as "lyricmaster" rather than the old "lyrics", which is not read: a file from when
     * that one was written may still hold a 0 nobody remembers setting.
     */
    public static final Bool LYRICS = new Bool(SettingSpec.LYRICS)
            .store(new Bool.Store() {
                public boolean get() { return LockLyrics.sEnabled; }
                public void set(boolean v) { LockLyrics.sEnabled = v; }
            })
            .op("lyrics", "on", Absent.TOGGLE)
            .apply((c, v) -> {
                LockLyrics.setEnabled(v, Main.sTrackKey, Main.sWatched);
                return true;
            })
            .file("lyricmaster");

    public static final Bool LYRIC_KEEP_ON = new Bool(SettingSpec.LYRIC_KEEP_ON)
            .store(new Bool.Store() {
                public boolean get() { return LockLyrics.sKeepOn; }
                public void set(boolean v) { LockLyrics.sKeepOn = v; }
            })
            .op("lyrickeep", "on", Absent.TOGGLE)
            .then(c -> LockLyrics.refresh())
            .file("lyrickeep");

    public static final Bool LYRIC_HDR = new Bool(SettingSpec.LYRIC_HDR)
            .store(new Bool.Store() {
                public boolean get() { return LockLyrics.sHdr; }
                public void set(boolean v) { LockLyrics.sHdr = v; }
            })
            .op("lyrichdr", "on", Absent.TOGGLE)
            .then(c -> LockLyrics.refresh())
            .file("lyrichdr");

    /**
     * The view notices the switch itself and lays the lines out again around it; refresh only
     * has to start the frames that let it. Left at the default when absent from the file: the
     * key did not exist before this setting did.
     */
    public static final Bool LYRIC_TRANS = new Bool(SettingSpec.LYRIC_TRANS)
            .store(new Bool.Store() {
                public boolean get() { return LockLyrics.sTrans; }
                public void set(boolean v) { LockLyrics.sTrans = v; }
            })
            .op("lyrictrans", "on", Absent.TOGGLE)
            .then(c -> LockLyrics.refresh())
            .file("lyrictrans");

    public static final Bool LYRIC_ROMA = new Bool(SettingSpec.LYRIC_ROMA)
            .store(new Bool.Store() {
                public boolean get() { return LockLyrics.sRoma; }
                public void set(boolean v) { LockLyrics.sRoma = v; }
            })
            .op("lyricroma", "on", Absent.TOGGLE)
            .then(c -> LockLyrics.refresh())
            .file("lyricroma");

    /** Saved only when it changed. */
    public static final Bool LYRIC_ONLINE = new Bool(SettingSpec.LYRIC_ONLINE)
            .store(new Bool.Store() {
                public boolean get() { return LockLyrics.sOnline; }
                public void set(boolean v) { LockLyrics.sOnline = v; }
            })
            .op("lyriconline", "on", Absent.TOGGLE)
            .apply((c, v) -> LockLyrics.setOnline(v))
            .file("lyriconline");

    /**
     * Same shape as the translation switch, saved the way the style is: a quick run of picks is
     * one write. Clamped in setAlign, from the file as from an op.
     */
    public static final Int LYRIC_ALIGN = new Int(SettingSpec.LYRIC_ALIGN)
            .store(new Int.Store() {
                public int get() { return LockLyrics.sAlign; }
                public void set(int v) { LockLyrics.sAlign = v; }
            })
            .op("lyricalign", "v", Absent.KEEP).save(Save.SOON)
            .apply((c, v) -> LockLyrics.setAlign(v))
            .then(c -> LockLyrics.refresh())
            .file("lyricalign", () -> String.valueOf(LockLyrics.sAlign),
                    v -> LockLyrics.setAlign(Integer.parseInt(v)));

    /**
     * The two sizes are the user's (the 歌词外观 page); the band's other four values are fixed
     * and not kept, see LyricStyle. Clamped by LyricStyle.with, from the file as from an op.
     */
    public static final Ref<LyricStyle> LYRIC_STYLE =
            new Ref<LyricStyle>(SettingSpec.LYRIC_SIZE, LockLyrics.sStyle)
            .store(new Ref.Store<LyricStyle>() {
                public LyricStyle get() { return LockLyrics.sStyle; }
                public void set(LyricStyle v) { LockLyrics.sStyle = v; }
            })
            .op("lyricstyle", "key", Absent.IGNORE).save(Save.SOON)
            .apply((c, i) -> LockLyrics.setStyle(i.getStringExtra("key"),
                    i.getFloatExtra("v", Float.NaN)))
            .file("lyricsize", () -> String.valueOf(LockLyrics.sStyle.sizeSp),
                    v -> LockLyrics.setStyle("size", Float.parseFloat(v)))
            .file("lyricbgsize", () -> String.valueOf(LockLyrics.sStyle.bgSizeSp),
                    v -> LockLyrics.setStyle("bgsize", Float.parseFloat(v)))
            .answer(out -> {
                LyricStyle s = LockLyrics.sStyle;
                out.putFloat(SettingSpec.LYRIC_BG_SIZE.key, s.bgSizeSp);
                out.putFloat(SettingSpec.LYRIC_FILL.key, s.fill);
                out.putFloat(SettingSpec.LYRIC_POS.key, s.pos);
                out.putFloat(SettingSpec.LYRIC_SIDE.key, s.sideDp);
                out.putFloat(SettingSpec.LYRIC_SIZE.key, s.sizeSp);
                out.putInt(SettingSpec.LYRIC_WEIGHT.key, s.weight);
            });

    /** One line of JSON in the file: no '=' or newline in it to confuse the reader. */
    public static final Ref<LyricAppearance> LYRIC_APPEARANCE =
            new Ref<LyricAppearance>(SettingSpec.LYRIC_APPEARANCE, LockLyrics.sAppearance)
            .store(new Ref.Store<LyricAppearance>() {
                public LyricAppearance get() { return LockLyrics.sAppearance; }
                public void set(LyricAppearance v) { LockLyrics.sAppearance = v; }
            })
            .op("lyricappearance", "json", Absent.IGNORE).save(Save.SOON)
            .apply((c, i) -> {
                if (!i.hasExtra("json")) return false;
                LockLyrics.setAppearance(i.getStringExtra("json"));
                return true;
            })
            .file("lyricappearance", () -> LockLyrics.sAppearance.toJson(), LockLyrics::setAppearance)
            .answer(out -> out.putString(SettingSpec.LYRIC_APPEARANCE.key,
                    LockLyrics.sAppearance.toJson()));

    // The islands.

    /** On when absent from the file: the trip's page and island shipped before their switch. */
    public static final Bool TRANSIT = new Bool(SettingSpec.TRANSIT)
            .store(new Bool.Store() {
                public boolean get() { return AmapTransitScene.sOn; }
                public void set(boolean v) { AmapTransitScene.sOn = v; }
            })
            .op("transitcfg", "on", Absent.IGNORE).absent(true)
            .apply((c, v) -> {
                AmapTransitScene.INSTANCE.setOn(c, v);
                return true;
            })
            .file("transit");

    public static final Bool PICKUP = new Bool(SettingSpec.PICKUP)
            .store(new Bool.Store() {
                public boolean get() { return PickupCodeIsland.sOn; }
                public void set(boolean v) { PickupCodeIsland.sOn = v; }
            })
            .op("pickup", "on", Absent.IGNORE).absent(true)
            .apply((c, v) -> {
                PickupCodeIsland.INSTANCE.setOn(v);
                return true;
            })
            .file("pickup");

    /**
     * `--ez card false` draws the island with the system's focus template again, which is the
     * way back if ColorOS's card does not come up on a build. Kept, or it would last only until
     * the next SystemUI start.
     */
    public static final Bool PICKUP_CARD = new Bool(SettingSpec.PICKUP_CARD)
            .store(new Bool.Store() {
                public boolean get() { return PickupCodeIsland.sCard; }
                public void set(boolean v) { PickupCodeIsland.sCard = v; }
            })
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
            if (e.save == Save.NOW) ModuleState.saveState();
            else if (e.save == Save.SOON) ModuleState.saveStateSoon();
            if (e.after != null) e.after.run(c);
        }
        After finish = FINISH.get(op);
        if (finish != null) finish.run(c);
        return true;
    }

    /** Every setting's answer to the query. */
    static void query(Bundle out) {
        for (Entry<?> e : ALL) e.query(out);
    }
}

package com.os4.musiccover;

import com.os4.musiccover.lyrics.LockLyrics;
import com.os4.musiccover.island.MiniPlayerRuntime;
import com.os4.musiccover.shade.ShadeLayer;
import com.os4.musiccover.transit.AmapTransitScene;
import com.os4.musiccover.pickup.PickupCodeIsland;

import java.util.function.BooleanSupplier;

/**
 * The module's settings file in SystemUI's storage: writing every setting out (at once, or
 * debounced for values that change per frame) and reading them back at start, one key at a time
 * so a bad value costs only itself.
 *
 * Every key is one entry of {@link #KEYS}: how it is written and how it is read back, side by
 * side. A new setting is one entry there, nowhere else in this class.
 *
 * Carved out of Main (2026-10-14, after Diagnostics). The writer and the reader used to be two
 * hand-kept lists, a concatenation and an if/else chain, that had to agree key for key; the
 * table replaced both (2026-11-04) and writes the same bytes.
 */
public final class ModuleState {
    private ModuleState() {}

    static final String STATE_FILE = "mc_cover_state";

    /** A key's value as it goes into the file, or null to leave the key out this time. */
    private interface Write {
        String value();
    }

    /** Applies a value read back from the file. Throwing costs this key its default, no more. */
    private interface Read {
        void apply(String v) throws Exception;
    }

    private interface Setter {
        void set(boolean on);
    }

    /** One line, or for {@link #shadeBlock} several, of the file. */
    private abstract static class Entry {
        /** Appends "\n" + each line this entry writes. */
        abstract void write(StringBuilder out);

        /** Applies [v] if [k] is this entry's key; false if it is not. */
        abstract boolean read(String k, String v) throws Exception;
    }

    private static Entry key(String name, Write w, Read r) {
        return new Entry() {
            @Override
            void write(StringBuilder out) {
                String v = w.value();
                if (v != null) out.append('\n').append(name).append('=').append(v);
            }

            @Override
            boolean read(String k, String v) throws Exception {
                if (!name.equals(k)) return false;
                r.apply(v);
                return true;
            }
        };
    }

    /** A switch, written as 1 or 0 and read as on only for "1". */
    private static Entry bool(String name, BooleanSupplier get, Setter set) {
        return key(name, () -> get.getAsBoolean() ? "1" : "0", v -> set.set("1".equals(v)));
    }

    /**
     * The whole shade settings page, in one prefix - the keys and their meaning belong to
     * ShadeLayer.configure.
     */
    private static Entry shadeBlock() {
        return new Entry() {
            @Override
            void write(StringBuilder out) {
                out.append(ShadeLayer.dumpCfg());
            }

            @Override
            boolean read(String k, String v) {
                if (!k.startsWith("shade_")) return false;
                ShadeLayer.configure(k.substring(6), Integer.parseInt(v));
                return true;
            }
        };
    }

    /** What the file's cover key said; read by loadState after the walk, not applied in it. */
    private static boolean sCoverInFile;

    /**
     * Every key, in the order saveState writes them. loadState applies them in the order the
     * file has them, which for a file this class wrote is the same. Some go through a setter
     * that saves as part of its own contract (setClockHeightDp does); sLoading keeps those
     * saves from happening mid-walk, so the order here is only what the file looks like.
     */
    private static final Entry[] KEYS = {
            bool("cover", () -> Main.sCoverMode, on -> sCoverInFile = on),
            bool("coverfeature", () -> Main.sCoverFeatureOn, on -> Main.sCoverFeatureOn = on),
            // "auto" was a stored setting; following the card is unconditional now.
            key("bias", () -> String.valueOf(Main.sBias), v -> Main.sBias = Float.parseFloat(v)),
            // Only the mode. The square's size, place and corners, the clock's offset, the glass
            // end and the spring are all fixed now, and nothing reads them - writing them down
            // would be a value nothing could ever have set. covercardfill, covercardpos,
            // covercardcorner and the dp covercardsize before them are not read: a file from
            // before that is not allowed to hold them open - see CoverCardStyle. coverstyle IS
            // read: which of the two shapes the cover takes is still the user's.
            key("coverstyle", () -> String.valueOf(Main.sCoverCardStyle.mode),
                    v -> Main.sCoverCardStyle = Main.sCoverCardStyle.with("mode", Float.parseFloat(v))),
            // A pending pre-dp value is written as itself: it cannot be converted until a
            // confirmed box exists, and writing the default over it would lose the setting the
            // user actually had.
            key("clock", () -> String.valueOf(Float.isNaN(Main.sClockLegacyK)
                            ? Main.sClockHeightDp : Main.sClockLegacyK),
                    v -> Main.setClockHeightDp(Float.parseFloat(v))),
            key("clocksize", () -> Float.isNaN(Main.sClockSize) ? null : String.valueOf(Main.sClockSize),
                    v -> Main.setClockSize(Float.parseFloat(v))),
            // A measurement, like cardrect: the full clock the size is a fraction of.
            key("clockfull", () -> ClockCollapse.fullUnitState() == null
                            ? null : String.valueOf(ClockCollapse.fullUnitState()),
                    ClockCollapse::restoreFullUnit),
            // The anchored styles' full clock, which every other style's size is read against -
            // kept apart so switching to one of those does not lose it.
            key("clockref", () -> Float.isNaN(ClockCollapse.refUnitState())
                            ? null : String.valueOf(ClockCollapse.refUnitState()),
                    v -> ClockCollapse.restoreRefUnit(Float.parseFloat(v))),
            // clockoff, glass and spring are not read: the clock's offset, the glass clock's end
            // and the transition's spring are all fixed. Each is still reachable from its own op,
            // for the session.
            bool("mctap", () -> Main.sMcTitleTap, on -> Main.sMcTitleTap = on),
            bool("mcpage", () -> Main.sMcPageBtn, on -> Main.sMcPageBtn = on),
            bool("tap", () -> Main.sTapToggle, on -> Main.sTapToggle = on),
            shadeBlock(),
            bool("fadewp", () -> Main.sFadeWp, on -> Main.sFadeWp = on),
            bool("motion", () -> MotionCover.sEnabled, on -> MotionCover.sEnabled = on),
            bool("motiondata", () -> MotionCover.sOnData, on -> MotionCover.sOnData = on),
            bool("vcfade", () -> Main.sVideoFade, on -> Main.sVideoFade = on),
            key("fsmode2", () -> String.valueOf(Main.sFadeMode),
                    v -> Main.sFadeMode = Integer.parseInt(v)),
            // A measurement rather than a setting, and kept for the same reason the geometry is:
            // a fresh SystemUI should not have to relearn it to use it.
            key("covergap", () -> String.valueOf(CoverPush.sCoverFadeGapMs),
                    v -> CoverPush.sCoverFadeGapMs = Long.parseLong(v)),
            // Also read on its own, before this file is, by Fingerprint.peekHideFp.
            bool("hidefp", () -> Fingerprint.sHideFp, on -> Fingerprint.sHideFp = on),
            // The lyrics master switch, a setting again (2026-10-14). A new key rather than the
            // old "lyrics", which is not read: a file from when that one was written may still
            // hold a 0 nobody remembers setting.
            bool("lyricmaster", () -> LockLyrics.sEnabled, on -> LockLyrics.sEnabled = on),
            bool("colon", () -> HyperTweaks.sForceColon, on -> HyperTweaks.sForceColon = on),
            bool("seekglow", () -> HyperTweaks.sBarGlow, on -> HyperTweaks.sBarGlow = on),
            bool("lyrickeep", () -> LockLyrics.sKeepOn, on -> LockLyrics.sKeepOn = on),
            // The two-finger tap's page.
            bool("lyrichidden", () -> LockLyrics.sTapHidden, on -> LockLyrics.sTapHidden = on),
            bool("lyrichdr", () -> LockLyrics.sHdr, on -> LockLyrics.sHdr = on),
            // Written as 1 or 0 like the rest, but left at the default when absent: the key did
            // not exist before this setting did, and the lyrics are supposed to look the way
            // they always have on a file that predates it.
            bool("lyrictrans", () -> LockLyrics.sTrans, on -> LockLyrics.sTrans = on),
            bool("aodsmall", () -> Main.sAodSmall, on -> Main.sAodSmall = on),
            // Off when absent, which is what every file from before it had.
            bool("lyricroma", () -> LockLyrics.sRoma, on -> LockLyrics.sRoma = on),
            // On when absent: every file from before it looked lyrics up online.
            bool("lyriconline", () -> LockLyrics.sOnline, on -> LockLyrics.sOnline = on),
            // 0 left, 1 centre, 2 right, and left when the key is absent: a file from before this
            // setting is one where the lyrics always sat left. Clamped in the setter.
            key("lyricalign", () -> String.valueOf(LockLyrics.sAlign),
                    v -> LockLyrics.setAlign(Integer.parseInt(v))),
            // lyricmaxlines is not read: every overlapping line is lit, see LyricFocus.
            // The two sizes are the user's again (the 歌词外观 page, 2026-10-22); the band's other
            // four values are still fixed and not written, see LyricStyle. Clamped by
            // LyricStyle.with, like an op's value.
            key("lyricsize", () -> String.valueOf(LockLyrics.sStyle.sizeSp),
                    v -> LockLyrics.setStyle("size", Float.parseFloat(v))),
            key("lyricbgsize", () -> String.valueOf(LockLyrics.sStyle.bgSizeSp),
                    v -> LockLyrics.setStyle("bgsize", Float.parseFloat(v))),
            // One line of JSON: no '=' or newline in it to confuse the reader below.
            key("lyricappearance", () -> LockLyrics.sAppearance.toJson(), LockLyrics::setAppearance),
            // The dp lyricoff and lyricgap from before the shares are dropped: what they meant
            // depends on the room, which is not known here. lyricfill, lyricpos, lyricside and
            // lyricweight are dropped with them: the band and its weight are fixed, see
            // LyricStyle.DEFAULT.
            // Not a setting - whether the last lookup got its lyric from the session. Kept across
            // restarts so the settings page does not accuse a working provider module of doing
            // nothing merely because nothing has played yet; updated in both directions so it
            // stops claiming one is working once it is not.
            bool("sawlyric", () -> LockLyrics.sSawSessionLyric, on -> LockLyrics.sSawSessionLyric = on),
            key("fpavoid", () -> String.valueOf(Fingerprint.sFpAvoid),
                    v -> Fingerprint.sFpAvoid = Integer.parseInt(v)),
            // On when absent: the trip's page and island shipped before their switch.
            bool("transit", () -> AmapTransitScene.sOn, on -> AmapTransitScene.sOn = on),
            bool("pickup", () -> PickupCodeIsland.sOn, on -> PickupCodeIsland.sOn = on),
            // Read back at startup, so it has to be written too: without it `--ez card false`
            // lasted until the next SystemUI restart.
            bool("card", () -> PickupCodeIsland.sCard, on -> PickupCodeIsland.sCard = on),
            // Read with restoreLegacyConfig, not applyConfig: this file is migration data, and a
            // config the user has since changed must win over what was saved here.
            key("minicfg", () -> android.util.Base64.encodeToString(
                            MiniPlayerRuntime.configJson(Main.sAppCtx)
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            android.util.Base64.NO_WRAP),
                    v -> MiniPlayerRuntime.restoreLegacyConfig(Main.sAppCtx,
                            new String(android.util.Base64.decode(v, android.util.Base64.DEFAULT),
                                    java.nio.charset.StandardCharsets.UTF_8))),
            // Not a setting - a measurement. Kept so the app's preview is to scale from the first
            // frame after a SystemUI restart, instead of only once the phone has been locked
            // again.
            key("cardrect", () -> MediaCard.sCardL + "," + MediaCard.sCardT + ","
                            + MediaCard.sCardW + "," + MediaCard.sCardH,
                    v -> {
                        String[] r = v.split(",");
                        // Same sanity check the sampler applies, because a file written before
                        // it existed can hold a reading taken from the shade.
                        if (r.length == 4 && Integer.parseInt(r[1]) >= Main.sScreenH / 3) {
                            MediaCard.sCardL = Integer.parseInt(r[0]);
                            MediaCard.sCardT = Integer.parseInt(r[1]);
                            MediaCard.sCardW = Integer.parseInt(r[2]);
                            MediaCard.sCardH = Integer.parseInt(r[3]);
                        }
                    }),
            // "offdelay" was the pause timer, before the card became the switch; not read.
    };

    public static void saveState() {
        if (Main.sAppCtx == null) return;
        // Never while loadState is still walking the file. Some of the setters it applies values
        // through save as part of their own contract - setClockHeightDp does - and a save taken
        // mid-parse writes every key the parse has NOT reached yet at its DEFAULT. The keys that
        // go through a setter sit ahead of mctap, tap, lyrickeep and lyrichdr in the file, so
        // restoring a good file once quietly rewrote it with those four off. The run itself looked
        // fine, because the loop went on to fill memory in correctly from the copy it had already
        // read; the damage only showed at the NEXT SystemUI start, which is why it read as
        // "installing the app turns some switches off". loadState saves once at the end instead.
        if (sLoading) return;
        android.util.AtomicFile af = new android.util.AtomicFile(
                new java.io.File(Main.sAppCtx.getFilesDir(), STATE_FILE));
        java.io.FileOutputStream f = null;
        try {
            // Every value is read before the file is opened for writing: one that throws leaves
            // the last whole file where it was.
            StringBuilder out = new StringBuilder();
            for (Entry e : KEYS) e.write(out);
            // Each entry starts its line with "\n"; the file starts without one and ends with one.
            byte[] body = (out.substring(1) + "\n").getBytes();
            f = af.startWrite();
            f.write(body);
            af.finishWrite(f);
        } catch (Throwable t) {
            if (f != null) af.failWrite(f);
            Xp.w(Main.TAG + "saveState failed: " + t);
        }
    }

    static final Runnable sSaveState = new Runnable() {
        @Override
        public void run() { saveState(); }
    };

    /**
     * For values that change as fast as the screen draws. The card rectangle is sampled from a
     * pre-draw listener while the card is animating in, and writing the file on every frame of
     * that would be a file write per frame for a number that settles in a few hundred ms.
     */
    static void saveStateSoon() {
        Main.main().removeCallbacks(sSaveState);
        Main.main().postDelayed(sSaveState, 500L);
    }

    /** Set while the file is being applied, to keep a setter from writing a half-read state back. */
    static volatile boolean sLoading;

    /** The file, or the backup an interrupted write leaves (AtomicFile.exists is not in the SDK). */
    static boolean stateExists(android.util.AtomicFile f) {
        java.io.File base = f.getBaseFile();
        return base.exists() || new java.io.File(base.getPath() + ".bak").exists();
    }

    static void loadState() {
        if (Main.sAppCtx == null) return;
        // An AtomicFile, so a SystemUI killed in the middle of saveState leaves the last whole
        // file behind rather than a truncated one that silently resets every setting.
        android.util.AtomicFile f = new android.util.AtomicFile(
                new java.io.File(Main.sAppCtx.getFilesDir(), STATE_FILE));
        if (!stateExists(f)) return;
        boolean cover = false;
        sCoverInFile = false;
        sLoading = true;
        try {
            String body = new String(f.readFully()).trim();
            // The first version of this file was a bare "1"/"0"; keep reading those so an
            // upgrade does not silently drop a cover that is still on the phone.
            if (body.length() <= 1) {
                cover = "1".equals(body);
            } else {
                for (String line : body.split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                    // Every key in its own try. The loop's outer catch RETURNS, so without this
                    // one unreadable value takes every setting BELOW it in the file down with it,
                    // silently - and the log would say "loadState failed" without ever naming the
                    // key. One bad number should cost its own setting and nothing else.
                    try {
                        for (Entry e : KEYS) {
                            if (e.read(k, v)) break;
                        }
                    } catch (Throwable t) {
                        Xp.log(Main.TAG + "setting unreadable, keeping the default: "
                                + k + "=" + v + " (" + t + ")");
                    }
                }
                cover = sCoverInFile;
            }
        } catch (Throwable t) {
            Xp.w(Main.TAG + "loadState failed: " + t);
            return;
        } finally {
            sLoading = false;
        }
        // The mode fpavoid has just come back to. A policy flow the OEM's combine already had
        // attached was made with whatever this process started at - 0, "leave it to the system" -
        // and NotificationSink.withAvoidance hands the decision to that flow whenever it carries
        // one, so a phone restarted with 始终下沉 saved sank nothing until the setting was touched
        // again. Nothing else refreshed this on the way up: 展开背景时下移通知 used to, by
        // accident, on its scene changes and only while it was switched on (2026-10-09).
        Main.refreshNotificationSink();
        Xp.log(Main.TAG + "state restored: cover=" + cover + " bias=" + Main.sBias);
        if (cover) {
            Main.enterCoverMode(false);
            // The composed cover the shade reveals does not survive a restart, and the wallpaper
            // process has no reason to ask for art again - it kept its own texture on disk and
            // believes it already has one. Without this the lock screen comes back correct and
            // the shade stays empty until the next track change. Non-fresh, so it is not
            // suppressed for being the same artwork as last time.
            CoverPush.pushArtAsync(true, false);
        }
        // Always follow the card, whatever the file said.
        Main.setAuto(true);
    }
}

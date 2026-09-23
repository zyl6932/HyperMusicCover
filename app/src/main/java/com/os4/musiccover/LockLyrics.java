package com.os4.musiccover;

import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lock screen lyrics: which song's lines are loaded, where the singing is, and whether the view
 * should be in the keyguard at all. The drawing is LyricView's; Main only reports events here.
 *
 * Everything runs on SystemUI's main thread except the fetch, which LyricSource does on its own
 * threads and hands back on main.
 *
 * The lines belong to the TRACK, not to cover mode. Leaving cover mode and coming back on the
 * same song - two taps on the cover - keeps them, where the first version dropped them on exit
 * and never looked the same song up again.
 */
final class LockLyrics {
    private LockLyrics() {
    }

    private static final String TAG = "[MCLyric] ";

    static volatile boolean verbose;

    /** The user's switch. Off by default: it fetches from the network inside SystemUI. */
    static volatile boolean sEnabled;

    /**
     * Whether the lock screen's two-finger tap has taken the lyrics away.
     *
     * A view of the switch, not the switch: the app's setting is untouched, and this only says
     * the lock screen shows the cover instead. It stands until the next two-finger tap - across
     * songs, lock screens, the card going away and coming back, and a SystemUI restart (it is in
     * the state file). It used to be dropped on every fresh entry into cover mode, which the user
     * read as the choice being forgotten whenever the media card went.
     *
     * The one other thing that clears it is the app's switch: turning the lyrics on while a tap
     * had hidden them would otherwise leave the switch saying on over a lock screen showing the
     * cover. Written by setEnabled, toggleByTap and the state file, and nowhere else.
     */
    static volatile boolean sTapHidden;

    private static String sKey = "";
    private static List<LyricLine> sLines = Collections.emptyList();
    private static int sVersion;
    /** Bumped per lookup, so an answer overtaken by the next song is dropped. */
    private static int sGen;
    private static String sWhy = "";

    private static LyricView sView;
    private static MediaController sController;
    private static volatile PlaybackState sState;
    private static long sStateReadAt;

    /** The user's second switch: keep the screen lit while lyrics are playing on the lock screen. */
    static volatile boolean sKeepOn;
    private static boolean sHolding;

    /**
     * The third switch: a held note's glow brighter than white on an HDR screen. Off by default:
     * the HDR colour mode is the whole shade window's, and with it on the media card's material
     * changes colour too (reported 2026-09-16). Doing it without that needs its own window.
     */
    static volatile boolean sHdr = false;
    /**
     * The fourth switch: whether a line's translation is drawn under it.
     *
     * On by default, because it is what the lyrics look like when the source has one: off is for
     * people who read the language and find the second line in the way. It is not a filter on
     * what is fetched - the layout drops the translation's rows, so the lines close up rather
     * than leaving a gap where it was.
     */
    static volatile boolean sTrans = true;
    /** Immutable snapshot: the UI, renderer and state loader all use the same validated values. */
    static volatile LyricStyle sStyle = LyricStyle.DEFAULT;

    static boolean setStyle(String key, float value) {
        LyricStyle next = sStyle.with(key, value);
        if (next == sStyle) return false;
        sStyle = next;
        LyricView view = sView;
        if (view != null) view.kick();
        return true;
    }
    /** How far above SDR white the window may go; the text asks for less than this. */
    private static final float HDR_HEADROOM = 4f;
    private static Object sShadeWindow;
    private static boolean sHdrApplied;
    private static boolean sModeOwned;
    private static int sOrigColorMode;
    private static float sOrigHeadroom;

    /** A lookup is in the air; the blur is held rather than dropped while it is. */
    private static boolean sLoading;
    /**
     * Which route the lines on screen came from, as a LyricSource.SRC_ constant.
     *
     * Kept so the session can win later: anything short of the session's own lyric is provisional
     * and is replaced if one turns up, because a provider module writes it seconds after the
     * track starts and the key cannot see the difference.
     */
    private static int sSource = LyricSource.SRC_NONE;
    /**
     * What the wallpaper process was last told about the blur, and when that answer was made.
     * 0 = nothing decided yet, which is what makes the first one always go.
     *
     * Volatile because every cover push reads it off the push thread: a track change carries the
     * answer with it, which is what settles a switch the other process missed.
     *
     * The answer and its time are one long - bit 0 the answer, the rest the uptime it was decided
     * at - because they are read as a pair by a push that is built on another thread. As two
     * fields a decision landing between the reads would send one answer with another's time, and
     * the time is what the wallpaper process uses to drop an answer older than the one in hand.
     */
    private static volatile long sBlurSent;

    /**
     * How long the lyrics hold back to let the background blur transition settle.
     * Measured on device: wallpaper FastPlayer reload takes ~150ms-250ms (total ~266ms from broadcast),
     * and TransitionDrawable / crossfade takes ~300ms-370ms.
     */
    private static final long BLUR_ENTER_DELAY_MS = 280L;
    private static final long BLUR_ENTER_MIN_MS = 200L;

    private static volatile long sBlurStartedAt;
    private static volatile long sTrackChangedAt;
    private static volatile boolean sBlurVideoReloaded;

    private static final Runnable BLUR_KICK = new Runnable() {
        @Override
        public void run() {
            LyricView v = sView;
            if (v != null) v.kick();
        }
    };

    private static void scheduleBlurKick(long delayMs) {
        Main.main().removeCallbacks(BLUR_KICK);
        Main.main().postDelayed(BLUR_KICK, Math.max(16L, delayMs));
    }

    static void onVideoReload() {
        sBlurVideoReloaded = true;
        LyricView v = sView;
        if (v != null) v.kick();
    }

    static boolean blurSettled() {
        // Only the video wallpaper's window has a reload to wait out. A still wallpaper frosts
        // its own texture in the same fade as the cover, and holding the band back there only
        // made it blink out for 280ms on every track change.
        if (!Main.sVideoWallpaper || !blurWanted()) return true;
        long now = SystemClock.uptimeMillis();
        long blurElapsed = now - sBlurStartedAt;
        if (sBlurStartedAt > 0L && blurElapsed >= 0 && blurElapsed < BLUR_ENTER_DELAY_MS) {
            return sBlurVideoReloaded && blurElapsed >= BLUR_ENTER_MIN_MS;
        }
        long trackElapsed = now - sTrackChangedAt;
        if (sTrackChangedAt > 0L && trackElapsed >= 0 && trackElapsed < BLUR_ENTER_DELAY_MS) {
            return sBlurVideoReloaded && trackElapsed >= BLUR_ENTER_MIN_MS;
        }
        return true;
    }

    /**
     * Attached and only waiting out the video window's reload (blurSettled). The lyrics are
     * coming, so for anything that lays itself out around them - the card's thumbnail - they
     * are already up: answered by wantsShown() alone, the thumbnail went out and the title
     * slid to the middle on every entry, then both came back when the band did.
     */
    static boolean heldForBlur() {
        return wantsAttached() && !blurSettled();
    }

    /** Whether the cover should be frosted right now, for a push to carry over. */
    static boolean blurWanted() {
        return (sBlurSent & 1L) != 0L;
    }

    /**
     * Puts this process's answer, and when it was made, on a cover push.
     *
     * The push is built on the worker while the answer is decided on the main thread, so the
     * answer a push reads can be one a tap has already replaced - and the push, being the slow
     * half, arrives after the switch that replaced it. Without the time on it the wallpaper
     * process has no way to tell which of the two is the newer answer and takes the last one to
     * arrive: the cover comes in sharp under its lyrics and stays that way for the song, because
     * both sides believe they agree. See WallpaperProbe.takeBlurDecision.
     */
    static void putBlurOn(Intent out) {
        long state = sBlurSent;      // one read: the answer and its time travel together
        out.putExtra("lyricblur", (state & 1L) != 0L);
        out.putExtra("blurseq", state >>> 1);
    }

    /** Writes an answer down, timed on the clock both processes share, and answers with it. */
    private static long setBlurSent(boolean on) {
        sBlurSent = SystemClock.uptimeMillis() << 1 | (on ? 1L : 0L);
        return sBlurSent;
    }

    private static boolean sDemo;
    private static long sDemoT0;

    private static View sCard;
    private static long sCardLookAt;

    /**
     * A session has carried its own lyric at some point since SystemUI started.
     *
     * The settings page asks, to tell "a provider module is installed" apart from "a provider
     * module is working". Those are different: LyricInfo is an LSPosed module, and one that is
     * installed but not enabled, or enabled without the player in its scope, writes nothing at
     * all while still being present in the package list. Only having read a real lyric off a
     * session proves the whole chain.
     */
    static boolean sSawSessionLyric;

    /** Lines plus the route they came by - a cache hit has to answer both. */
    private static final class Cached {
        final List<LyricLine> lines;
        final int source;

        Cached(List<LyricLine> lines, int source) {
            this.lines = lines;
            this.source = source;
        }
    }

    /** Songs recently shown, so skipping back and forth does not go to the network each time. */
    private static final Map<String, Cached> CACHE =
            new LinkedHashMap<String, Cached>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cached> e) {
                    return size() > 12;
                }
            };

    // ------------------------------------------------------------------ for the view

    static int version() {
        return sVersion;
    }

    static List<LyricLine> lines() {
        return sLines;
    }

    /**
     * Whether the lyrics are wanted at all: the switch says so, and no tap has taken them away.
     * A demo asks for them itself, whatever the switch says.
     */
    private static boolean wanted() {
        return sEnabled && !sTapHidden || sDemo;
    }

    /**
     * Whether this track has lyrics to show - the lyric page is only the page while it does, and
     * a track without (a Douyin or Bilibili video, a song no source has) keeps the cover. It was
     * the page whenever the switch was on, and in card mode that hid the square over nothing.
     *
     * Through a lookup the last answer stands, the same hold the cover's blur has: a song with
     * lyrics followed by one still being looked up keeps the lyric page rather than flashing the
     * cover up for the length of the lookup, and a song without keeps the cover.
     *
     * The cache counts as well as the lines on screen: a two-finger tap that hides the lyrics
     * drops the lines, and the song still has them to bring back.
     */
    static boolean hasLyrics() {
        return sDemo || !sLines.isEmpty() || CACHE.containsKey(sKey)
                || (sLoading && sHadLyrics);
    }

    /** Whether the last settled answer - not one still loading - had lines. See hasLyrics(). */
    private static boolean sHadLyrics;

    /** Whether the view belongs in the keyguard right now. */
    static boolean wantsAttached() {
        return wanted() && Main.coverModeOn() && hasLyrics();
    }

    /** The lyric page a tap into cover mode will land on. */
    static boolean willAttachOnEntry() {
        return hasLyrics() && CoverMorphRoute.lyricsAfterEntry(sEnabled, sTapHidden, sDemo);
    }

    static boolean willAttachAfterTapToggle() {
        return hasLyrics() && CoverMorphRoute.lyricsAfterToggle(sEnabled, sTapHidden, sDemo);
    }

    /**
     * Whether it should be visible: attached, lit, and the clock settled small.
     *
     * Through ENTER as well, wake or toggle alike: they come up with the clock rather than after
     * it (user, 2026-09-16 - waiting for the landing made them turn up late). An early version
     * held them back because a recording (02:23) had them arriving on top of the full-size clock;
     * that was the band being stale, and it is measured from the clock's live ink every frame
     * now, so through the flight they follow it down instead.
     */
    static boolean wantsShown() {
        if (!wantsAttached()) return false;
        // Hold the band back until the cover's blur transition has settled, so it does not
        // land over an artwork that is still sharpening. blurSettled() answers true when there
        // is no blur pending, so the held-AOD path below still shows through untouched.
        if (!blurSettled()) return false;
        ClockCollapse.Phase p = ClockCollapse.phase();
        if (p == ClockCollapse.Phase.ON || p == ClockCollapse.Phase.ENTER) {
            return Main.screenOnCached();
        }
        return inHeldAod();
    }

    /**
     * The full-screen always-on display that kept cover mode's clock - the lock screen, dimmed.
     *
     * The lyrics belong to it for the same reason the clock does: it is this lock screen being
     * shown, not the OEM's own doze, and both the clock's ink and the media card are still laid
     * out for the lyrics to sit between. Only that one doze: with the clock handed back to the
     * OEM there is nothing measured to sit under, and `inkBottomOnScreen()` says NaN.
     *
     * No switch of its own. The one that decides this is "keep the small clock in the full-screen
     * AOD", which is what makes the doze this lock screen; where that is on, carrying the lyrics
     * through is what the lock screen was showing.
     */
    static boolean inHeldAod() {
        return ClockCollapse.aodHeld() && !Main.screenOnCached();
    }

    /**
     * The media card, looked up again only when the one we hold has left the window.
     *
     * "Left the window" now includes "is not being shown": the card can be up in the tree and
     * hidden for a whole song - see cardTopOnScreen() - and a card nobody can see is a card whose
     * position nothing should be measured from, so the lookup is retried rather than held. What
     * comes back is still whatever the tree holds, shown or not: describe() reports whether the
     * card is up, and answering "none" there would lose the difference between "hidden" and
     * "not there at all".
     *
     * The lookup is findLockScreenView rather than findSysuiView for the same reason: the id is
     * declared by the media card and by both media island layouts, and the first match in the
     * tree is only the card while no island copy is up.
     */
    static View card() {
        View c = sCard;
        if (c != null && c.isShown() && c.isAttachedToWindow()) return c;
        long now = SystemClock.uptimeMillis();
        if (now - sCardLookAt < 500L) return c;
        sCardLookAt = now;
        sCard = Main.findLockScreenView("mi_media_controls");
        return sCard;
    }

    /** The band stops above the card the lock screen is showing. */
    private static final int BAND_LIVE = 0;
    /** ... at the end of the block the card would have filled. */
    private static final int BAND_SPACE = 1;
    /** ... off the card fractions LockPreview.kt carries for a phone nothing was measured on. */
    private static final int BAND_DEFAULT = 2;
    /**
     * The card's own share of the screen - the same two fractions the app's preview falls back to
     * (LockPreview.kt's SAMPLE_CARD_T and SAMPLE_CARD_H), so that a phone which has never had a
     * card measured puts the module's band and the app's preview in the same place.
     */
    private static final float CARD_TOP_FRACTION = 1700f / 2608f;
    private static final float CARD_HEIGHT_FRACTION = 557f / 2608f;
    /** Which of the three answered last; -1 until one has. */
    private static volatile int sBandSrc = -1;

    /**
     * Where the band's lower edge sits on screen, in pixels.
     *
     * Above the card where there is a card to read, because that is the only reading that follows
     * it through the cover morph. The case the other two routes exist for is a card that is not up
     * at all: the lock screen's media card is hidden outright by HyperLight's music capsule, which
     * hooks MiuiMediaHeaderView.setVisibility and rewrites the OEM's VISIBLE into GONE (measured
     * 2026-09-21). The lyrics used to give up entirely there - the band could not be measured, so
     * they never faded in and the lock screen showed no lyrics at all.
     *
     * With no card drawn there is nothing to stop above, so the band takes the block the card
     * would have filled instead of reserving a place for something nobody is drawing. That block
     * is the recorded rectangle's BOTTOM edge, not its top: it is the whole of the gap the user
     * sees between the lyrics and the shortcut buttons once the capsule has moved the media
     * elsewhere. The recorded rectangle lags - the card is not sampled while it is hidden, and
     * onLockTap's notes record a reading of 1360 against a card actually at 1700 - so the route is
     * logged and reported rather than passed off as a measurement.
     *
     * Nothing on record at all leaves the two fractions the app's preview uses.
     */
    static float bandBottomOnScreen() {
        View c = card();
        if (c != null && c.isShown() && c.isAttachedToWindow()) {
            int[] loc = new int[2];
            c.getLocationOnScreen(loc);
            // Below the clock, like the card. Anything higher is the shade's copy or an island,
            // and neither is what the band is measured against.
            if (loc[1] >= Main.screenHeight() / 3) {
                setBandSource(BAND_LIVE);
                return loc[1];
            }
        }
        float recorded = Main.sampledCardBottom();
        if (!Float.isNaN(recorded)) {
            setBandSource(BAND_SPACE);
            return recorded;
        }
        setBandSource(BAND_DEFAULT);
        return Main.screenHeight() * (CARD_TOP_FRACTION + CARD_HEIGHT_FRACTION);
    }

    /** Which route answered bandBottomOnScreen(), as something readable in a broadcast result. */
    static String bandSource() {
        switch (sBandSrc) {
            case BAND_LIVE:
                return "live";
            case BAND_SPACE:
                return "space";
            case BAND_DEFAULT:
                return "default";
            default:
                return "?";
        }
    }

    /**
     * Written once a frame from the band, so it only speaks when the answer changes: a card that
     * stays hidden for the whole song would otherwise log its line with every pre-draw.
     */
    private static void setBandSource(int src) {
        if (src == sBandSrc) return;
        sBandSrc = src;
        Xp.log(TAG + "band anchored to the " + bandSource() + " card position");
    }

    /** Where the singing is, extrapolated from the last position the session reported. */
    static int positionMs() {
        if (sDemo) {
            long t = SystemClock.uptimeMillis() - sDemoT0;
            return t < 0 ? 0 : (int) t;
        }
        PlaybackState s = sState;
        if (s == null) return 0;
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            long dt = SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime();
            if (dt > 0) pos += (long) (dt * s.getPlaybackSpeed());
        }
        return pos < 0 ? 0 : (int) Math.min(pos, Integer.MAX_VALUE);
    }

    static boolean playing() {
        if (sDemo) return true;
        PlaybackState s = sState;
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    // ------------------------------------------------------------------ from Main

    /** The card is showing a track. Same key as last time is a no-op. */
    static void onTrack(String key, MediaController c) {
        key = lyricKey(key, c);
        sController = c;
        readState(true);
        if (sDemo) {
            if (verbose) Xp.log(TAG + "demo is held, not looking " + key + " up");
            return;
        }
        if (key.equals(sKey)) {
            // Same song - but not necessarily the same evidence. A provider module (LyricInfo
            // and the ColorOS ones write the whole lyric to the session; the player itself may
            // too) cannot publish a lyric until it knows what is playing, so it writes one into
            // a session that already exists. None of the fields this key is built from change
            // when it does, which is the point of the key - so without this, the lyric a module
            // just went and fetched would sit on the session unread for the whole song, and
            // whatever we settled for in the first second would stand.
            //
            // The question is "is the session carrying a payload this song has not been read
            // against", not "did we settle for something worse than the session's own". The
            // second one cannot be asked of the case it most needs to catch: a payload read
            // during the track change belongs to the song before it as often as not, and once
            // one of those has been recorded as the session's own lyric, a gate that only fires
            // when the source is something else can never replace it. Asking after the payload
            // instead both fires for that and cannot loop, because each payload is tried once.
            String info = sEnabled && !key.isEmpty() && !sLoading ? LyricSource.infoFor(c) : null;
            if (info != null && !info.equals(sInfoSeen) && LyricSource.usable(info)
                    && sInfoTries < MAX_INFO_TRIES) {
                sInfoSeen = info;
                sInfoTries++;
                Xp.log(TAG + "the session is carrying a lyric " + key
                        + " has not been read against; re-reading (" + sInfoTries + ")");
                CACHE.remove(key);
                // The lines already up are NOT cleared. A re-read is looking for something
                // better than what is on screen, and the first version emptied the view before
                // it knew whether there was any: a re-read that came back with nothing left the
                // song with no lyrics at all for the rest of its play. lookup() keeps them.
                lookup(key, c, true);
                return;
            }
            return;
        }
        sKey = key;
        sTrackChangedAt = SystemClock.uptimeMillis();
        sBlurVideoReloaded = false;
        if (sEnabled && !key.isEmpty()) scheduleBlurKick(BLUR_ENTER_DELAY_MS + 16L);
        // A different song: the budget above is per track, and so is the payload it was spent on.
        sInfoSeen = null;
        sInfoTries = 0;
        // The previous song's route says nothing about this one, and leaving it set would let a
        // track that follows a session-lyric track skip the upgrade check entirely.
        sSource = LyricSource.SRC_NONE;
        // Set before the lines are emptied, so the blur is held across the lookup instead of
        // being dropped by the empty set and put back when the answer lands.
        //
        // A cached answer counts as a lookup too, short as it is. Excluding it meant the empty
        // set below sent the blur off and the cache hit one line later sent it straight back on
        // - two messages, in the middle of the track change's crossfade, which is exactly when
        // the wallpaper process holds a switch back to wait for the fade. Those two could then
        // land in the wrong order and leave the cover sharp.
        sLoading = sEnabled && !key.isEmpty();
        setLines(Collections.<LyricLine>emptyList(), "track changed");
        if (!sEnabled || key.isEmpty()) return;
        Cached hit = CACHE.get(key);
        if (hit != null) {
            sLoading = false;
            sSource = hit.source;
            setLines(hit.lines, "cached");
            return;
        }
        // What the lookup about to start will read the session as, so a payload that turns up
        // after it - the provider module's real one - can be told apart from this one.
        sInfoSeen = LyricSource.infoFor(c);
        lookup(key, c, false);
    }

    /** How many times one song may be re-read because the session published something new. */
    private static final int MAX_INFO_TRIES = 3;
    /** The payload the lookup for sKey was started against, so the next one can be recognised. */
    private static String sInfoSeen;
    /** How much of MAX_INFO_TRIES this song has spent. */
    private static int sInfoTries;

    /**
     * Starts the lookup for `key` and puts whatever it finds on screen.
     *
     * `keepCurrent` is the whole difference between the two callers. A track change has already
     * emptied the view, so anything found is an improvement on nothing and an empty answer costs
     * nothing to apply. A re-read is looking for something better than what is already up, and
     * an empty answer there must not be applied at all - the lines on screen are the best that
     * has been found for this song and there is nothing to replace them with.
     */
    private static void lookup(final String want, MediaController c, final boolean keepCurrent) {
        final int gen = ++sGen;
        sLoading = true;
        LyricSource.load(c, new LyricSource.Callback() {
            @Override
            public void onLines(List<LyricLine> lines, String why, int source) {
                if (gen != sGen || !want.equals(sKey) || sDemo) {
                    Xp.log(TAG + "lyrics for " + want + " arrived after the track changed");
                    return;
                }
                sLoading = false;
                if (lines.isEmpty() && keepCurrent) {
                    Xp.log(TAG + "the re-read found nothing (" + why + "); keeping the "
                            + sLines.size() + " lines already up");
                    // Not setLines: the lines have not changed. The blur still has to be told,
                    // because sLoading was what was holding it across the lookup.
                    refresh();
                    return;
                }
                sSource = source;
                // What the LAST lookup found, not what any lookup ever found.
                //
                // It has to fall as well as rise. Written once and kept, it said "a provider
                // module is working" for as long as the file lasted - so disabling the module
                // and restarting left the settings page still convinced, and the advice that
                // should have appeared never did.
                //
                // SRC_NONE is deliberately not an answer either way: finding nothing can mean
                // the network was down or the song simply has no lyrics anywhere, neither of
                // which says anything about the provider.
                //
                // Nor is SRC_LOCAL, for the opposite reason. The file's own lyric outranks the
                // session's, so a song that has one never reports what the session was carrying
                // - the module may have been working perfectly and simply not been needed.
                // Neither answer is available, so the last real one stands.
                if (source != LyricSource.SRC_NONE && source != LyricSource.SRC_LOCAL) {
                    boolean fromSession = source == LyricSource.SRC_LYRIC_INFO;
                    if (fromSession != sSawSessionLyric) {
                        sSawSessionLyric = fromSession;
                        Main.saveState();
                    }
                }
                if (!lines.isEmpty()) CACHE.put(want, new Cached(lines, source));
                setLines(lines, why);
            }
        });
    }

    /**
     * Which song the lyrics belong to - deliberately without the title.
     *
     * Salt Player writes the line being sung INTO the title (for car and status bar lyrics) and
     * moves "artist - title" into the artist field. Keyed on the title, every line of every song
     * was a new track: the lines were thrown away and parsed again a line at a time, which on
     * screen was the lyrics blinking out and back (state log 2026-09-16 02:42). Artist, album
     * and duration hold still across a song under either convention.
     */
    private static String lyricKey(String cardKey, MediaController c) {
        String fallback = cardKey == null ? "" : cardKey;
        if (c == null || fallback.isEmpty()) return fallback;
        try {
            android.media.MediaMetadata md = c.getMetadata();
            if (md == null) return fallback;
            String artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
            String album = md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM);
            long dur = md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
            if (artist == null && album == null && dur <= 0) return fallback;
            return c.getPackageName() + "|" + artist + "|" + album + "|" + dur;
        } catch (Throwable t) {
            return fallback;
        }
    }

    static void onPlaybackState(PlaybackState s) {
        sState = s;
        sStateReadAt = SystemClock.uptimeMillis();
        LyricView v = sView;
        if (v != null) v.kick();
    }

    /** Cover mode came on. Puts the view in the keyguard if the switch is on. */
    static void attach() {
        updateBlur();
        if (!wantsAttached()) return;
        final View anchor = Main.sContainer;
        if (anchor == null) return;
        try {
            int id = anchor.getResources().getIdentifier(
                    "keyguard_foreground_layer", "id", "com.android.systemui");
            View layer = id == 0 ? null : anchor.getRootView().findViewById(id);
            if (!(layer instanceof ViewGroup)) {
                Xp.log(TAG + "keyguard_foreground_layer not found");
                return;
            }
            if (sView == null || sView.getContext() != anchor.getContext()) {
                sView = new LyricView(anchor.getContext());
            }
            LyricView v = sView;
            if (v.getParent() != layer) {
                if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
                ((ViewGroup) layer).addView(v, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                Xp.log(TAG + "view attached, " + sLines.size() + " lines");
            }
            v.kick();
            startTick();
        } catch (Throwable t) {
            Xp.log(TAG + "attach failed: " + Log.getStackTraceString(t));
        }
    }

    /** Called by the view itself once it has faded out with no reason to stay. */
    static void detach(LyricView v) {
        if (wantsAttached()) return;
        if (sHolding) {
            sHolding = false;
            v.setKeepScreenOn(false);
        }
        if (v.getParent() instanceof ViewGroup) {
            ((ViewGroup) v.getParent()).removeView(v);
            Xp.log(TAG + "view detached");
        }
        Main.main().removeCallbacks(TICK);
    }

    /** Anything that may change whether the view should be showing. */
    static void refresh() {
        updateBlur();
        updateHdr();
        if (wantsAttached()) attach();
        LyricView v = sView;
        if (v != null) v.kick();
        CoverCardLayer.refresh();
    }

    /** The app's switch. The setting: it is written to the state file and the app reads it back. */
    static void setEnabled(boolean on, String key, MediaController c) {
        sEnabled = on;
        // The switch is the master, so it takes the tap's answer with it. Switching the lyrics
        // off and on again brings them back; without this, turning them on while a previous tap
        // had hidden them would leave the lock screen on the cover with the switch saying on.
        sTapHidden = false;
        Xp.log(TAG + "lyrics " + (on ? "on" : "off"));
        show(on, key, c, "switched off");
    }

    /**
     * The lock screen's two-finger tap: the cover and the lyrics, swapped until the next one.
     * The app's switch is not touched - a tap that was not meant would otherwise read as the
     * setting turning itself off - but the page it picked is saved, see sTapHidden.
     *
     * With the switch off the tap does nothing at all rather than turning the lyrics on: that
     * would be the lock screen writing the app's setting, which is what it no longer does.
     */
    static void toggleByTap(String key, MediaController c) {
        if (!sEnabled) return;
        sTapHidden = !sTapHidden;
        Xp.log(TAG + "two-finger tap: lyrics " + (sTapHidden ? "hidden" : "shown"));
        show(!sTapHidden, key, c, "hidden by the two-finger tap");
        Main.saveState();
    }

    /**
     * The show/hide half, shared by the switch and the tap; they differ only in whether the
     * answer is written down. Off is where the two part company in the log: the reason says
     * which of them did it and one of them is a setting.
     */
    private static void show(boolean on, String key, MediaController c, String why) {
        if (on) {
            sKey = "";
            onTrack(key, c);
        } else if (!sDemo) {
            sGen++;
            sLoading = false;
            setLines(Collections.<LyricLine>emptyList(), why);
        }
        refresh();
    }

    /**
     * Plays a lyric file by database id on its own clock, whatever is playing - so the look can
     * be judged without a player that publishes an id, or a song the database has.
     */
    static void demo(String id, boolean apple) {
        sDemo = true;
        final int gen = ++sGen;
        sLoading = true;
        sKey = "demo:" + id;
        sTrackChangedAt = SystemClock.uptimeMillis();
        sBlurVideoReloaded = false;
        scheduleBlurKick(BLUR_ENTER_DELAY_MS + 16L);
        setLines(Collections.<LyricLine>emptyList(), "demo loading");
        LyricSource.loadById(id, apple, new LyricSource.Callback() {
            @Override
            public void onLines(List<LyricLine> lines, String why, int source) {
                if (gen != sGen || !sDemo) return;
                sLoading = false;
                sDemoT0 = SystemClock.uptimeMillis();
                setLines(lines, "demo " + why);
                refresh();
            }
        });
        refresh();
    }

    static void endDemo(String key, MediaController c) {
        if (!sDemo) return;
        sDemo = false;
        sKey = "";
        onTrack(key, c);
        refresh();
    }

    /** The SRC_ constant as something readable in a broadcast result. */
    private static String srcName(int source) {
        switch (source) {
            case LyricSource.SRC_LYRIC_INFO:
                return "session";
            case LyricSource.SRC_LOCAL:
                return "local";
            case LyricSource.SRC_LYRICON:
                return "lyricon";
            case LyricSource.SRC_DATABASE:
                return "amll";
            case LyricSource.SRC_NETEASE:
                return "netease";
            case LyricSource.SRC_KUGOU:
                return "kugou";
            case LyricSource.SRC_LRCLIB:
                return "lrclib";
            default:
                return "none";
        }
    }

    static String describe() {
        LyricView v = sView;
        View c = Main.sContainer;
        View card = card();
        return "enabled=" + sEnabled + " tap=" + (sTapHidden ? "hidden" : "shown")
                + " demo=" + sDemo + " key=" + sKey + " lines=" + sLines.size()
                + " has=" + hasLyrics() + " loading=" + sLoading
                + " (" + sWhy + ") src=" + srcName(sSource)
                + " sessionHasLyric=" + LyricSource.hasLyricInfo(sController)
                + " pos=" + positionMs() + " playing=" + playing()
                + " cover=" + Main.coverModeOn() + " screen=" + Main.screenOnCached()
                // What the wallpaper process was last told, and when: the other side prints the
                // answer it holds and when it was decided, so the two readings side by side say
                // whether a switch was lost on the way rather than guessing at the cover.
                + " blur=" + (blurWanted() ? "on" : "off") + "@" + (sBlurSent >>> 1)
                + " phase=" + ClockCollapse.phase()
                + " inAod=" + inHeldAod() + " held=" + ClockCollapse.aodHeld()
                + " cardP=" + Main.cardProgress()
                + " container=" + (c == null ? "none" : c.getAlpha() + "/shown=" + c.isShown())
                + " card=" + (card == null ? "none" : "shown=" + card.isShown())
                // Where the band's lower edge came from: live stops above the card, space fills
                // the block it would have taken (what happens while the music capsule hides it),
                // default is the fractions. A hidden card is visible in this line as the route
                // the lyrics are being placed on rather than as a missing reading.
                + " bandSrc=" + bandSource()
                + " clockBottom=" + ClockCollapse.contentBottomOnScreen()
                + " ink=" + ClockCollapse.inkBottomOnScreen()
                + " shown=" + wantsShown() + " blurSettled=" + blurSettled()
                + " blurElapsed=" + (sBlurStartedAt > 0 ? (SystemClock.uptimeMillis() - sBlurStartedAt) + "ms" : "none")
                + " tick=" + sTicking
                + " view={" + (v == null ? "none" : v.describe()) + "}";
    }

    /**
     * The wallpaper process restarted, or may have: tell it again.
     *
     * Told again rather than forgotten: the answer is still the answer, and this only skips the
     * "already sent that" short circuit below. What it must not do is make the answer look older
     * than the last one it gave - the other side drops those - so the clock it is timed on runs
     * on from here like any other decision.
     */
    static void resendBlur() {
        updateBlur(true);
    }

    /**
     * Frosts the cover while there are lyrics on it, so they read over any artwork.
     *
     * Held through a track change's lookup rather than dropped and re-applied, which would pulse
     * the cover sharp and back on every song. Nothing is sent while cover mode is off: leaving
     * it fades the cover out whole, and the wallpaper process clears the blur with it. The
     * decision itself is written down either way, because a cover push carries it.
     */
    private static void updateBlur() {
        updateBlur(false);
    }

    /** again = tell the other side even when the answer has not changed. */
    private static void updateBlur(boolean again) {
        if (!Main.coverModeOn()) {
            setBlurSent(false);
            return;
        }
        boolean on = wanted();
        boolean want = on && (!sLines.isEmpty() || (sLoading && blurWanted()));
        long cur = sBlurSent;
        boolean wasOn = (cur & 1L) != 0L;
        if (!again && cur != 0L && wasOn == want) return;
        boolean turningOn = want && !wasOn;
        long state = setBlurSent(want);
        // Video wallpaper needs a moment to reload and cross-fade before the band lands over it;
        // arm the settle timer when blur turns on, and clear it when it turns off. blurSettled()
        // reads these to hold wantsShown() back through the transition.
        if (turningOn) {
            sBlurStartedAt = SystemClock.uptimeMillis();
            sBlurVideoReloaded = false;
            scheduleBlurKick(BLUR_ENTER_DELAY_MS + 16L);
        } else if (!want) {
            Main.main().removeCallbacks(BLUR_KICK);
            sBlurStartedAt = 0L;
            sTrackChangedAt = 0L;
            sBlurVideoReloaded = false;
        }
        CoverPush.sendToWallpaper("lyricblur", want, state >>> 1);
        CoverPush.updateVideoCoverBlur(want);
        Xp.log(TAG + "cover blur " + (want ? "on" : "off") + (again ? " (told again)" : ""));
    }

    // ------------------------------------------------------------------ internals

    private static void setLines(List<LyricLine> lines, String why) {
        sLines = lines == null ? Collections.<LyricLine>emptyList() : lines;
        // Only a settled answer: the empty set a track change puts up while it looks is not one.
        if (!sLoading) sHadLyrics = !sLines.isEmpty();
        sVersion++;
        sWhy = why;
        Xp.log(TAG + sLines.size() + " lines: " + why);
        refresh();
    }

    /** The session's position is re-read now and then, not per frame: it is a binder call. */
    private static void readState(boolean force) {
        MediaController c = sController;
        if (c == null || sDemo) return;
        long now = SystemClock.uptimeMillis();
        if (!force && now - sStateReadAt < 1000L) return;
        sStateReadAt = now;
        try {
            sState = c.getPlaybackState();
        } catch (Throwable ignored) {
        }
    }

    private static boolean sTicking;

    /**
     * Also called by the view whenever it is attached to a window: a keyguard rebuilt around the
     * view re-attaches it without going through attach(), and the tick stops when it finds the
     * view detached - with it stopped, a line-timed song never moves on to its next line.
     */
    static void startTick() {
        Main.main().removeCallbacks(TICK);
        Main.main().post(TICK);
    }

    /**
     * Wakes the view at the next line start, or twice a second, whichever is sooner - the view
     * asks for no frames of its own while nothing moves, so this is what moves the focus on a
     * line-timed file.
     */
    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            LyricView v = sView;
            sTicking = v != null && v.isAttachedToWindow();
            if (!sTicking) return;
            long delay = 1000L;
            holdScreen(v);
            updateHdr();
            if ((Main.screenOnCached() || inHeldAod()) && !sLines.isEmpty()) {
                readState(false);
                v.kick();
                if (playing()) {
                    delay = 500L;
                    int pos = positionMs();
                    // The stack moves a second ahead of each line's first word (LyricView.LEAD_MS).
                    int next = nextStartAfter(pos + 1000);
                    if (next >= 0) delay = Math.max(16L, Math.min(delay, next - 1000L - pos + 8L));
                }
            }
            Main.main().postDelayed(this, delay);
        }
    };

    /**
     * Keeps the lock screen lit while the lyrics are playing, and lets it sleep again once they
     * are not - paused, hidden, switched off.
     *
     * keepScreenOn on the view is the whole mechanism. The keyguard's auto-sleep is not a timer of
     * SystemUI's own: NotificationShadeWindowControllerImpl.apply() sets the shade window's
     * userActivityTimeout to 10s on the lock screen (5s in the editor), and nothing else in
     * SystemUI puts the phone to sleep on a clock. The view's flag becomes FLAG_KEEP_SCREEN_ON on
     * that same window, for which the window manager holds a screen wake lock - and a held wake
     * lock outranks a user activity timeout. Nothing is faked as a touch.
     */
    private static void holdScreen(LyricView v) {
        // NOT in a doze, and this is the one that has to be got right: setKeepScreenOn on this
        // view is the whole mechanism above, and the view is in the SHADE window - so asking for
        // it while the display is dozing takes a screen wake lock and pulls the phone out of the
        // AOD at full brightness. Called from the tick, which never stops, so this would have
        // fired within a second of the screen going off.
        boolean want = sKeepOn && wantsShown() && !inHeldAod()
                && !sLines.isEmpty() && playing();
        if (want == sHolding) return;
        sHolding = want;
        v.setKeepScreenOn(want);
        Xp.log(TAG + (want ? "holding the screen on" : "screen may sleep again"));
    }

    /** The shade window controller, seen on its first apply. */
    static void noteShadeWindow(Object controller) {
        sShadeWindow = controller;
    }

    /** Whether the singing words should be drawn in HDR right now. */
    static boolean hdrWanted() {
        // Never in a doze: the colour mode is the whole shade window's and the headroom is 4x,
        // which is the opposite of what a display that has just dimmed itself wants. The glow
        // that arms this needs the lyrics on screen, so without this the AOD would ask for HDR.
        return sHdr && sGlowing && wantsShown() && !inHeldAod() && !sLines.isEmpty();
    }

    private static boolean sGlowing;
    private static long sGlowEndAt;

    /**
     * The view says whether a held note is glowing. The window only goes HDR for those; it is
     * let go a moment after the last one, so two notes close together do not flip it twice.
     */
    static void setGlowing(boolean glowing) {
        long now = SystemClock.uptimeMillis();
        if (glowing) {
            sGlowEndAt = 0L;
            if (!sGlowing) {
                sGlowing = true;
                updateHdr();
            }
        } else if (sGlowing) {
            if (sGlowEndAt == 0L) sGlowEndAt = now;
            if (now - sGlowEndAt >= 800L) {
                sGlowing = false;
                sGlowEndAt = 0L;
                updateHdr();
            }
        }
    }

    /**
     * Writes the colour mode into the shade window's pending attributes, or puts back what the
     * OEM had there. Called inside the OEM's own apply, so it lands in the same update.
     */
    static void applyHdrTo(android.view.WindowManager.LayoutParams lp) {
        if (lp == null) return;
        if (hdrWanted()) {
            if (!sModeOwned) {
                sOrigColorMode = lp.getColorMode();
                sOrigHeadroom = lp.getDesiredHdrHeadroom();
                sModeOwned = true;
            }
            lp.setColorMode(android.content.pm.ActivityInfo.COLOR_MODE_HDR);
            lp.setDesiredHdrHeadroom(HDR_HEADROOM);
        } else if (sModeOwned) {
            lp.setColorMode(sOrigColorMode);
            lp.setDesiredHdrHeadroom(sOrigHeadroom);
            sModeOwned = false;
        }
    }

    /** Asks the OEM to re-apply its window attributes when what we want of them has changed. */
    static void updateHdr() {
        boolean want = hdrWanted();
        if (want == sHdrApplied) return;
        Object w = sShadeWindow;
        if (w == null) return;
        sHdrApplied = want;
        try {
            Xp.callMethod(w, "applyWindowLayoutParams");
            Xp.log(TAG + "lock screen window HDR " + (want ? "on" : "off"));
        } catch (Throwable t) {
            Xp.log(TAG + "applyWindowLayoutParams failed: " + t);
        }
    }

    /** Every string in the session's metadata, and lyricInfo written to a file whole. */
    // "lyricInfo" is the players' own key, not a framework one - see LyricSource.lyricInfoOf.
    @android.annotation.SuppressLint("WrongConstant")
    static String dumpMetadata(android.content.Context ctx, MediaController c) {
        if (c == null) return "no session";
        StringBuilder sb = new StringBuilder(c.getPackageName());
        try {
            android.media.MediaMetadata md = c.getMetadata();
            if (md == null) return sb.append(" no metadata").toString();
            for (String k : md.keySet()) {
                CharSequence v = md.getText(k);
                sb.append(" | ").append(k).append('=');
                if (v == null) {
                    sb.append("(non-text)");
                } else {
                    String t = v.toString();
                    sb.append(t.length() > 80 ? t.substring(0, 80) + "...(" + t.length() + ")" : t);
                }
            }
            String info = md.getString("lyricInfo");
            if (info != null) {
                java.io.File f = new java.io.File(ctx.getFilesDir(), "mc_lyricinfo.json");
                java.io.FileOutputStream out = new java.io.FileOutputStream(f);
                out.write(info.getBytes("UTF-8"));
                out.close();
                f.setReadable(true, false);
                sb.append(" | written ").append(f.getAbsolutePath());
            }
        } catch (Throwable t) {
            sb.append(" | failed: ").append(t);
        }
        return sb.toString();
    }

    private static int nextStartAfter(int pos) {
        List<LyricLine> l = sLines;
        int lo = 0, hi = l.size() - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (l.get(mid).start > pos) {
                best = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return best < 0 ? -1 : l.get(best).start;
    }
}

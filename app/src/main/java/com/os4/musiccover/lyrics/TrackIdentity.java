package com.os4.musiccover.lyrics;

import android.media.MediaMetadata;
import android.media.session.MediaController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.os4.musiccover.Xp;

/**
 * Which song a session is playing - worked out once, here, for everything that asks.
 *
 * Several players on HyperOS write the line being sung into TITLE (for car, Bluetooth and status
 * bar lyrics) and move the song's name into ARTIST, each spelt its own way:
 *
 * - Salt Player (com.salt.music): "歌手 - 歌名", the song after a spaced hyphen;
 * - 汽水音乐 (com.luna.music): "歌名 — 歌手", the song before a spaced em dash - switching back and
 *   forth to the plain shape (TITLE the song, ARTIST the singer) in the middle of a song (#56);
 * - QQ 音乐 with a Bluetooth device connected: "歌名-歌手", a bare hyphen (#47);
 * - 网易云 with "蓝牙设备歌词" on: "歌名 - 歌手", the song BEFORE a spaced hyphen (#127).
 *
 * The lyric key, the lyricInfo gate, the Lyricon bridge, the search, the local files, the motion
 * cover, the music island and the cover's track comparison each used to work this out for
 * themselves, from one hard-wired direction per separator. NetEase spells the same separator as
 * Salt the other way round, so its song was read as "张杰" and its singer as "剑心 (…)": the gate
 * refused the session's own lyric and the search asked for the wrong song.
 *
 * So the fields are read every way they could be meant (readings), each reading is given the
 * strongest evidence there is for it (a level), and the answer sticks for the rest of the song:
 *
 *   A  a catalogue scored it as the song (≥ 85), or a payload the player published names it
 *   B  the album is its song (a single), or this song was published under its name earlier on
 *   C  the table below says this player spells it this way, or it has been learned; or it is
 *      ARTIST and TITLE as published and TITLE has held still
 *   D  a separator merely allows it; or it is as published and TITLE is a line - it keeps
 *      moving, or ARTIST is spelt the way the player spells it only while singing
 *
 * One reading at the top is the song. Several tied are all handed to the scoring, which tries
 * each against every search result and keeps the best - a wrong reading puts the song's name
 * where its singer goes and loses 50 or 30 of the 85 it needs, so trying more of them does not
 * let a wrong song through (LyricMatch).
 */
public final class TrackIdentity {

    static final int D = 1, C = 2, B = 3, A = 4;

    /** How a reading was taken out of the fields. */
    static final int AS_PUBLISHED = 0, SONG_FIRST = 1, SONG_LAST = 2, FROM_ALBUM = 3;

    /** 汽水's separator: spaces on both sides of an em dash. */
    static final String EM = " " + (char) 0x2014 + " ";
    static final int SEP_NONE = 0, SEP_EM = 1, SEP_SPACED = 2, SEP_BARE = 3;

    /** QQ 音乐 publishes this as the length of every song when it is not saying (2026-10-07). */
    static final long QQ_PLACEHOLDER_MS = 60_000L;

    /** One way of reading the fields as a song and its singer. */
    static final class Reading {
        final String song;
        final String singer;
        final int shape;
        final int sep;
        /**
         * Only an exact match counts: a bare hyphen is part of a great many names - A-Lin, Jay-Z -
         * and containment against one half of "A-Lin" would agree with nearly anything.
         */
        final boolean exact;
        final int level;
        final String why;

        Reading(String song, String singer, int shape, int sep, boolean exact, int level,
                String why) {
            this.song = song;
            this.singer = singer;
            this.shape = shape;
            this.sep = sep;
            this.exact = exact;
            this.level = level;
            this.why = why;
        }

        Reading at(int level, String why) {
            return level == this.level ? this : new Reading(song, singer, shape, sep, exact, level, why);
        }

        /** The song and singer, which is what makes two readings the same one. */
        String id() {
            return song + '\u0001' + singer;
        }

        boolean split() {
            return shape == SONG_FIRST || shape == SONG_LAST;
        }

        @Override
        public String toString() {
            return "\"" + song + "\"/\"" + singer + "\" " + "?DCBA".charAt(level) + " (" + why + ")";
        }
    }

    /** A player known to sing into TITLE, and how it spells the song into ARTIST. */
    private static final class Spelling {
        final String pkg;
        final int sep;
        final int shape;
        /**
         * The player spells ARTIST this way only while TITLE is a line: 汽水 and NetEase go back
         * to the plain shape otherwise. Not Salt, whose ARTIST always reads "歌手 - 歌名", nor QQ,
         * whose bare hyphen is in a great many names.
         */
        final boolean onlyWhileSinging;

        Spelling(String pkg, int sep, int shape, boolean onlyWhileSinging) {
            this.pkg = pkg;
            this.sep = sep;
            this.shape = shape;
            this.onlyWhileSinging = onlyWhileSinging;
        }
    }

    /** The one place the players' spellings are written down. Prefixes: QQ ships several builds. */
    private static final Spelling[] SPELLINGS = {
            new Spelling("com.salt.music", SEP_SPACED, SONG_LAST, false),
            new Spelling("com.luna.music", SEP_EM, SONG_FIRST, true),
            new Spelling("com.tencent.qqmusic", SEP_BARE, SONG_FIRST, false),
            new Spelling("com.netease.cloudmusic", SEP_SPACED, SONG_FIRST, true),
    };

    private static Spelling spelling(String pkg) {
        if (pkg == null) return null;
        for (Spelling s : SPELLINGS) {
            if (pkg.startsWith(s.pkg)) return s;
        }
        return null;
    }

    // ------------------------------------------------------------------ the snapshot

    final String pkg;
    final String title;
    final String artist;
    final String album;
    /** The duration when it can be believed, else 0. */
    final long durationMs;
    final String mediaId;
    private final String songKey;
    private final boolean titleIsLine;
    /** Strongest first; the order within a level is the order they were listed in. */
    final List<Reading> readings;

    private TrackIdentity(String pkg, String title, String artist, String album, long durationMs,
                          String mediaId, String songKey, boolean titleIsLine,
                          List<Reading> readings) {
        this.pkg = pkg;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.durationMs = durationMs;
        this.mediaId = mediaId;
        this.songKey = songKey;
        this.titleIsLine = titleIsLine;
        this.readings = readings;
    }

    public static TrackIdentity of(MediaController c) {
        if (c == null) return of(null, null, null, null, 0L, null);
        try {
            return of(c.getPackageName(), c.getMetadata());
        } catch (Throwable t) {
            return of(c.getPackageName(), null, null, null, 0L, null);
        }
    }

    public static TrackIdentity of(String pkg, MediaMetadata md) {
        if (md == null) return of(pkg, null, null, null, 0L, null);
        String artist = str(md, MediaMetadata.METADATA_KEY_ARTIST);
        if (artist.isEmpty()) artist = str(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        long dur = 0L;
        try {
            dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        } catch (Throwable ignored) {
        }
        return of(pkg, str(md, MediaMetadata.METADATA_KEY_TITLE), artist,
                str(md, MediaMetadata.METADATA_KEY_ALBUM), dur,
                str(md, MediaMetadata.METADATA_KEY_MEDIA_ID));
    }

    /**
     * The identity of the song these fields describe, given everything learned about it so far.
     * With no package nothing is remembered or looked up: fields typed by hand into the probe.
     */
    static TrackIdentity of(String pkg, String title, String artist, String album, long dur,
                            String mediaId) {
        title = trim(title);
        artist = trim(artist);
        album = trim(album);
        mediaId = trim(mediaId);
        boolean trusted = dur > 0L && !(isQq(pkg) && dur == QQ_PLACEHOLDER_MS);
        long durationMs = trusted ? dur : 0L;
        List<Reading> listed = list(pkg, title, artist, album);
        String key = keyOf(pkg, album, durationMs, mediaId, listed, artist, title);
        boolean line;
        List<Reading> out;
        synchronized (LOCK) {
            line = movesOf(key) >= 2;
            out = levelled(pkg, key, title, album, listed, line);
        }
        return new TrackIdentity(pkg, title, artist, album, durationMs,
                mediaId.isEmpty() ? null : mediaId, key, line, Collections.unmodifiableList(out));
    }

    /** Every reading of the fields, at the level the fields alone give it. */
    private static List<Reading> list(String pkg, String title, String artist, String album) {
        List<Reading> out = new ArrayList<>(5);
        if (!title.isEmpty()) {
            out.add(new Reading(title, artist, AS_PUBLISHED, SEP_NONE, false, C, "as published"));
        }
        int sep = separatorOf(artist);
        if (sep != SEP_NONE) {
            Spelling known = spelling(pkg);
            // A spaced hyphen is still a hyphen to the player that spells with a bare one.
            boolean tabled = known != null && (known.sep == sep
                    || known.sep == SEP_BARE && sep == SEP_SPACED);
            Integer learned = tabled ? null : learnedShape(pkg, sep);
            if (tabled) {
                int before = out.size();
                cut(out, artist, sep, known.shape, C, "how " + known.pkg + " spells it");
                if (known.onlyWhileSinging && out.size() > before && !out.isEmpty()
                        && out.get(0).shape == AS_PUBLISHED) {
                    out.set(0, out.get(0).at(D, "this spelling means TITLE is a line"));
                }
            } else if (learned != null) {
                cut(out, artist, sep, learned, C, "learned for " + pkg);
            } else {
                cut(out, artist, sep, SONG_FIRST, D, "the separator allows it");
                cut(out, artist, sep, SONG_LAST, D, "the separator allows it");
            }
        }
        // Only as a last resort: a single's album is the single, true often enough to be worth
        // trying and not often enough to be tried first - Salt playing 逃跑计划's 夜空中最亮的星
        // off the album 世界 once went looking for a song called 世界.
        if (out.isEmpty() && !album.isEmpty()) {
            out.add(new Reading(album, artist, FROM_ALBUM, SEP_NONE, false, D, "the album"));
        }
        return out;
    }

    private static int separatorOf(String artist) {
        if (artist.contains(EM)) return SEP_EM;
        if (artist.contains(" - ")) return SEP_SPACED;
        return artist.indexOf('-') > 0 ? SEP_BARE : SEP_NONE;
    }

    /**
     * ARTIST cut into song and singer in one direction. The song may carry the separator itself
     * ("i'm so tired... (Stripped - Live in LA)") far more often than the singer does, so the cut
     * is on the singer's side: the first separator when the song comes last, the last one when it
     * comes first. A bare hyphen is cut at both its first and its last, since either half may
     * carry one of its own.
     */
    private static void cut(List<Reading> out, String a, int sep, int shape, int level, String why) {
        if (sep == SEP_BARE) {
            int first = a.indexOf('-');
            int last = a.lastIndexOf('-');
            add(out, a, first, 1, shape, sep, level, why);
            if (last != first) add(out, a, last, 1, shape, sep, level, why);
            return;
        }
        String s = sep == SEP_EM ? EM : " - ";
        int at = shape == SONG_LAST ? a.indexOf(s) : a.lastIndexOf(s);
        add(out, a, at, s.length(), shape, sep, level, why);
    }

    private static void add(List<Reading> out, String a, int at, int len, int shape, int sep,
                            int level, String why) {
        if (at <= 0) return;
        String head = a.substring(0, at).trim();
        String tail = a.substring(at + len).trim();
        if (head.isEmpty() || tail.isEmpty()) return;
        String song = shape == SONG_FIRST ? head : tail;
        String singer = shape == SONG_FIRST ? tail : head;
        for (Reading r : out) {
            if (r.song.equals(song) && r.singer.equals(singer)) return;
        }
        out.add(new Reading(song, singer, shape, sep, sep == SEP_BARE, level, why));
    }

    /**
     * The song, as the fields that hold still through it - deliberately without TITLE, which a
     * player singing into it moves with every line, and without the singer, which 汽水 and QQ
     * spell two ways in one song.
     *
     * MEDIA_ID is the strongest of them: a player that publishes its catalogue's id names the
     * song outright. It is added to the album and the duration rather than put in their place,
     * so a player that publishes the same id for everything cannot run two songs together - at
     * worst it is no help. When the duration cannot be believed (QQ's 60000 placeholder, or none
     * at all) a catalogue id stands in for it; without one the song's name does, read the way the
     * fields alone say - a name that a player singing into TITLE keeps still.
     */
    private static String keyOf(String pkg, String album, long durationMs, String mediaId,
                                List<Reading> listed, String artist, String title) {
        if (pkg == null) return "";
        // No metadata at all: nothing to tell one song from the next.
        if (album.isEmpty() && durationMs <= 0L && mediaId.isEmpty() && artist.isEmpty()
                && title.isEmpty()) {
            return "";
        }
        String tail;
        if (catalogueId(mediaId)) {
            tail = "#" + mediaId;
        } else if (durationMs > 0L) {
            // Any other MEDIA_ID - an internal uri, a queue position - is not trusted to hold
            // still through a song, and the duration already tells songs apart.
            tail = "";
        } else {
            tail = stableName(listed, artist, title);
        }
        return pkg + "|" + album + "|" + (durationMs > 0L ? durationMs : "") + "|" + tail;
    }

    /** A song id out of a catalogue - digits, and long enough not to be a queue position. */
    private static boolean catalogueId(String id) {
        if (id == null || id.length() < 5) return false;
        for (int i = 0; i < id.length(); i++) {
            if (!Character.isDigit(id.charAt(i))) return false;
        }
        return true;
    }

    /**
     * The song's name for a key that has nothing better: the song of a reading the player is
     * known to spell, else ARTIST whole when it holds a separator (a player of unknown spelling
     * may be singing into TITLE), else TITLE.
     */
    private static String stableName(List<Reading> listed, String artist, String title) {
        for (Reading r : listed) {
            if (r.split() && r.level >= C) return r.song;
        }
        for (Reading r : listed) {
            if (r.split()) return artist;
        }
        return title;
    }

    /** The levels evidence gives the readings, the ones this song has earned kept as well. */
    private static List<Reading> levelled(String pkg, String key, String title, String album,
                                          List<Reading> listed, boolean line) {
        List<Reading> out = new ArrayList<>(listed.size() + 1);
        for (Reading r : listed) {
            Reading best = r;
            if (r.shape == AS_PUBLISHED && line) {
                best = r.at(D, "TITLE keeps moving: a line being sung");
            }
            if (r.split() || r.shape == FROM_ALBUM) {
                if (!album.isEmpty() && r.song.equalsIgnoreCase(album) && best.level < B) {
                    best = r.at(B, "the album is its song");
                }
                // Salt with its lyric off: TITLE the song, and ARTIST "歌手 - 歌名" repeating it.
                if (best.level < B && r.split() && !line && Names.same(title, r.song, true)) {
                    best = r.at(B, "TITLE is its song");
                }
                if (best.level < B && publishedUnder(pkg, key, r.song)) {
                    best = r.at(B, "published under this name earlier in the song");
                }
            }
            out.add(best);
        }
        // Sticking: what this song has earned is not lost when the next line arrives, nor when
        // the player goes back to the other of its two spellings (汽水, QQ).
        Map<String, Reading> earned = key.isEmpty() ? null : sEarned.get(key);
        if (earned != null) {
            for (Reading e : earned.values()) {
                boolean found = false;
                for (int i = 0; i < out.size(); i++) {
                    Reading r = out.get(i);
                    if (r.id().equals(e.id())) {
                        found = true;
                        if (e.level > r.level) out.set(i, r.at(e.level, e.why));
                    }
                }
                if (!found) out.add(e);
            }
        }
        // Stable: within a level, the listed order - as published first.
        Collections.sort(out, (x, y) -> y.level - x.level);
        return out;
    }

    // ------------------------------------------------------------------ asking it

    /** Which song, as the fields that hold still through it. "" without a package. */
    public String songKey() {
        return songKey;
    }

    /** Whether TITLE has moved more than once under this song: a line being sung. */
    public boolean titleIsLine() {
        return titleIsLine;
    }

    /** The strongest reading, or null when the session named nothing at all. */
    Reading best() {
        return readings.isEmpty() ? null : readings.get(0);
    }

    /** Whether one reading stands above the rest. */
    boolean certain() {
        return readings.size() < 2 || readings.get(1).level < readings.get(0).level;
    }

    /**
     * The reading to search under and show. When the top is tied: TITLE as published while it may
     * still be the song (C) - ARTIST goes along whole, so the search has the other readings' words
     * too. Once TITLE is known to be a line it is left out, and the first reading of ARTIST is
     * searched: ARTIST's own words, the song's name and the singer's both in them, for the
     * catalogue to rank and the scoring to tell which way round they go.
     */
    Reading primary() {
        Reading top = best();
        if (top == null || certain()) return top;
        Reading other = null;
        for (Reading r : readings) {
            if (r.level < top.level) break;
            if (r.shape == AS_PUBLISHED) {
                if (r.level >= C) return r;
            } else if (other == null) {
                other = r;
            }
        }
        return other != null ? other : top;
    }

    /**
     * What a lookup under this identity would be asked for - the readings scored and how sure -
     * with TITLE as published standing for itself rather than its text, which a player singing
     * into it changes with every line. Two of these differ when the song has come to be read
     * differently (LockLyrics.rereadIfIdentityMoved).
     */
    String lookupSignature() {
        StringBuilder sb = new StringBuilder();
        for (Reading r : scored()) {
            sb.append(r.shape == AS_PUBLISHED && r.song.equals(title) ? "TITLE" : r.id())
                    .append('@').append(r.level).append(';');
        }
        return sb.toString();
    }

    /** The song's name, or TITLE when nothing better is known. */
    public String song() {
        Reading p = primary();
        return p == null ? title : p.song;
    }

    public String singer() {
        Reading p = primary();
        return p == null ? artist : p.singer;
    }

    /**
     * Whether song() is something other than the TITLE now published: read out of ARTIST, or the
     * title this song was confirmed under before the singing moved it.
     */
    public boolean songIsRead() {
        Reading p = primary();
        return p != null && !p.song.equals(title);
    }

    /** The readings a search result is scored against: the one, or every one when tied. */
    List<Reading> scored() {
        if (readings.isEmpty()) return readings;
        return certain() ? Collections.singletonList(readings.get(0)) : readings;
    }

    /**
     * Whether a name a source claims - a lyricInfo payload's songName - is this song, by any
     * reading of the fields or by the album. A reading that agrees has the payload behind it now
     * and is raised to A: the player itself is saying which song it is.
     *
     * A payload still left over from the song before agrees with no reading of this one, so the
     * gate keeps refusing those - the reason it exists (LyricSource.infoFor).
     */
    boolean accepts(String name) {
        return accepts(name, null);
    }

    /**
     * As accepts(name), with the singer the source gives as well: it must not disagree. A source
     * with no singer of its own says nothing against it.
     */
    boolean accepts(String name, String singer) {
        if (name == null || name.trim().isEmpty()) return false;
        boolean blank = singer == null || singer.trim().isEmpty();
        for (Reading r : readings) {
            if (!Names.same(name, r.song, r.exact)) continue;
            // Either side without a singer says nothing against it.
            if (!blank && !r.singer.isEmpty() && !Names.same(singer, r.singer, r.exact)) continue;
            earn(r, A, "the player's own payload names it");
            return true;
        }
        return blank && Names.same(name, album);
    }

    /** A catalogue scored a result as this song under `r` (LyricMatch). */
    void confirm(Reading r) {
        earn(r, A, "a catalogue matched it");
    }

    private void earn(Reading r, int level, String why) {
        if (songKey.isEmpty() || r.level >= level) return;
        synchronized (LOCK) {
            Map<String, Reading> earned = sEarned.get(songKey);
            if (earned == null) {
                earned = new LinkedHashMap<>();
                sEarned.put(songKey, earned);
            }
            Reading had = earned.get(r.id());
            if (had == null || had.level < level) {
                earned.put(r.id(), r.at(level, why));
                log("[MCIdentity] " + songKey + ": " + r.at(level, why));
            }
            // A player not in the table, now known to spell this separator this way round.
            if (r.split() && spelling(pkg) == null && pkg != null) {
                Integer was = sLearned.put(pkg + "|" + r.sep, r.shape);
                if (was == null || was != r.shape) {
                    log("[MCIdentity] " + pkg + " puts the song "
                            + (r.shape == SONG_FIRST ? "first" : "last"));
                }
            }
        }
    }

    @Override
    public String toString() {
        return songKey + (titleIsLine ? " (TITLE is a line)" : "") + " " + readings;
    }

    // ------------------------------------------------------------------ what has been seen

    private static final Object LOCK = new Object();
    private static final int SONGS = 64;

    /** pkg|title -> the song it was published under, for two titles of one song (Main.sameTrack). */
    private static final Map<String, String> sSongOf = bounded(SONGS);
    /** songKey -> the last title, and how many times it has moved. */
    private static final Map<String, String> sLastTitle = bounded(SONGS);
    private static final Map<String, Integer> sMoves = bounded(SONGS);
    /** songKey -> the readings it has earned beyond what its fields give them. */
    private static final Map<String, Map<String, Reading>> sEarned = bounded(32);
    /**
     * pkg|separator -> which side the song is on, for players not in the table. Not written to
     * a file: a SystemUI restart starts it again, which costs one tied search.
     */
    private static final Map<String, Integer> sLearned = bounded(16);

    private static <V> Map<String, V> bounded(final int max) {
        return new LinkedHashMap<String, V>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > max;
            }
        };
    }

    private static int movesOf(String key) {
        Integer m = key.isEmpty() ? null : sMoves.get(key);
        return m == null ? 0 : m;
    }

    private static Integer learnedShape(String pkg, int sep) {
        if (pkg == null) return null;
        synchronized (LOCK) {
            return sLearned.get(pkg + "|" + sep);
        }
    }

    /**
     * Whether this song was published under `song` as its TITLE: a player singing into TITLE
     * usually starts a song with its name there. A title that starts with it, up to a space or a
     * bracket, counts too - QQ's own title can carry the singer ("乌鸦 - 许嵩 (Vae Xu)" over
     * "乌鸦-许嵩", 2026-10-06).
     */
    private static boolean publishedUnder(String pkg, String key, String song) {
        if (pkg == null || key.isEmpty()) return false;
        String prefix = pkg + "|";
        for (Map.Entry<String, String> e : sSongOf.entrySet()) {
            if (!key.equals(e.getValue()) || !e.getKey().startsWith(prefix)) continue;
            if (titleStartsWith(e.getKey(), prefix.length(), song)) return true;
        }
        return false;
    }

    private static boolean titleStartsWith(String key, int from, String song) {
        if (!key.startsWith(song, from)) return false;
        int end = from + song.length();
        if (end == key.length()) return true;
        char c = key.charAt(end);
        return c == ' ' || c == '(' || c == (char) 0xFF08;
    }

    /**
     * A metadata update, written down before anything compares a track key against its title.
     * The keys Main compares are the card's, built from TITLE, and compared long after the
     * metadata they came from is gone - so each title is noted against the song it was published
     * with, and two titles of one song are the same track.
     */
    public static void note(String pkg, MediaMetadata md) {
        if (pkg == null || md == null) return;
        noteTitle(pkg, str(md, MediaMetadata.METADATA_KEY_TITLE), of(pkg, md).songKey);
    }

    /** note(), once the title and the song it belongs to are read. */
    static void noteTitle(String pkg, String title, String key) {
        if (pkg == null || title == null || title.isEmpty() || key.isEmpty()) return;
        synchronized (LOCK) {
            sSongOf.put(pkg + "|" + title, key);
            String last = sLastTitle.put(key, title);
            if (last != null && !last.equals(title)) {
                sMoves.put(key, movesOf(key) + 1);
            }
        }
    }

    public static void note(MediaController c) {
        if (c == null) return;
        try {
            note(c.getPackageName(), c.getMetadata());
        } catch (Throwable ignored) {
        }
    }

    /** Whether two titles from one player were last seen on the same song. */
    public static boolean sameSong(String pkg, String titleA, String titleB) {
        synchronized (LOCK) {
            String a = sSongOf.get(pkg + "|" + titleA);
            return a != null && a.equals(sSongOf.get(pkg + "|" + titleB));
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Xp.log, which the JVM unit tests' android.jar stubs cannot run. */
    private static void log(String msg) {
        try {
            Xp.log(msg);
        } catch (Throwable ignored) {
        }
    }

    static boolean isQq(String pkg) {
        return pkg != null && pkg.startsWith("com.tencent.qqmusic");
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String str(MediaMetadata md, String key) {
        try {
            String s = md.getString(key);
            return s == null ? "" : s.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** For tests: forget everything seen. */
    static void reset() {
        synchronized (LOCK) {
            sSongOf.clear();
            sLastTitle.clear();
            sMoves.clear();
            sEarned.clear();
            sLearned.clear();
        }
    }
}

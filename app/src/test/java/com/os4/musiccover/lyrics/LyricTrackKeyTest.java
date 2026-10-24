package com.os4.musiccover.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class LyricTrackKeyTest {
    @Test public void firstManualEntryUsesSessionBeforeAnyAutomaticCoverKeyExists() {
        assertEquals("player|artist|album|240000", LyricTrackKey.fromMetadata(
                "", "player", "song", "artist", "album", 240000));
    }

    @Test public void titleOnlySessionCanAlsoStartAFirstLookup() {
        assertEquals("player|song", LyricTrackKey.fromMetadata(
                null, "player", "song", null, null, 0));
    }

    @Test public void titleWithCurrentLyricsDoesNotChangeExistingStableTrackIdentity() {
        assertEquals(LyricTrackKey.fromMetadata("player|song", "player", "song",
                        "artist", "album", 240000),
                LyricTrackKey.fromMetadata("player|current line", "player", "current line",
                        "artist", "album", 240000));
    }

    @Test public void sessionChangeReplacesAStaleBackgroundTrackKey() {
        assertNotEquals(LyricTrackKey.fromMetadata("old", "player", "song",
                        "artist", "album", 240000),
                LyricTrackKey.fromMetadata("old", "player", "new song",
                        "new artist", "new album", 180000));
    }

    @Test public void missingSessionMetadataRetainsRealCardIdentity() {
        assertEquals("player|song|artist", LyricTrackKey.fromMetadata(
                "player|song|artist", "player", null, null, null, 0));
    }

    @Test public void emptyEvidenceDoesNotInventASongToFetch() {
        assertEquals("", LyricTrackKey.fromMetadata(null, "player", " ", null, null, 0));
        assertEquals("", LyricTrackKey.fromMetadata(null, null, "song", null, null, 0));
    }
}

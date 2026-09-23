package com.os4.musiccover;

import org.junit.Test;

import static org.junit.Assert.*;

public class CoverMorphRouteTest {
    @Test public void artworkTransitionsMorphButNormalToLyricsKeepsTheThumbnail() {
        assertTrue(CoverMorphRoute.shouldMorph(CoverMorphRoute.NORMAL, CoverMorphRoute.COVER));
        assertTrue(CoverMorphRoute.shouldMorph(CoverMorphRoute.COVER, CoverMorphRoute.NORMAL));
        assertTrue(CoverMorphRoute.shouldMorph(CoverMorphRoute.LYRICS, CoverMorphRoute.COVER));
        assertTrue(CoverMorphRoute.shouldMorph(CoverMorphRoute.COVER, CoverMorphRoute.LYRICS));
        assertFalse(CoverMorphRoute.shouldMorph(CoverMorphRoute.NORMAL, CoverMorphRoute.LYRICS));
        assertFalse(CoverMorphRoute.shouldMorph(CoverMorphRoute.LYRICS, CoverMorphRoute.NORMAL));
        assertFalse(CoverMorphRoute.shouldMorph(CoverMorphRoute.LYRICS, CoverMorphRoute.LYRICS));
    }

    @Test public void aTwoFingerDismissalOutlastsEveryEntry() {
        assertFalse(CoverMorphRoute.lyricsAfterEntry(true, true, false));
        assertTrue(CoverMorphRoute.lyricsAfterEntry(true, false, false));
        assertFalse(CoverMorphRoute.lyricsAfterEntry(false, false, false));
        assertTrue(CoverMorphRoute.lyricsAfterEntry(false, true, true));
    }

    @Test public void aTwoFingerTapOnlyMorphsIfItActuallyChangesThePage() {
        assertFalse(CoverMorphRoute.lyricsAfterToggle(true, false, false));
        assertTrue(CoverMorphRoute.lyricsAfterToggle(true, true, false));
        assertTrue(CoverMorphRoute.lyricsAfterToggle(true, true, true));
    }
}

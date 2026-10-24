package com.os4.musiccover.lyrics;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class LyricFocusTest {
    private LyricLine line(int start, int end) {
        return new LyricLine("voice", null, start, end, false, null, null, null);
    }

    @Test public void unfinishedOldVoiceSurvivesManyNewLines() {
        List<LyricLine> lines = Arrays.asList(line(0, 10000), line(1000, 2000),
                line(2000, 3000), line(3000, 4000), line(4000, 5000));
        LyricFocus focus = new LyricFocus();
        focus.update(lines, 4500, 3, 4);
        assertEquals(2, focus.count);
        assertTrue(focus.contains(0));
        assertTrue(focus.contains(4));
        assertFalse(focus.visible(2));
        assertEquals(0, focus.distance(0));
        float[] base = {0, 120, 240, 360, 480};
        float[] heights = {100, 100, 100, 100, 100};
        focus.layout(base, heights, 20);
        assertEquals(120, focus.position(4, base), 0);
        assertEquals(220, focus.height(base, heights), 0);
    }

    @Test public void newestFourWinAndLimitOneIsRespected() {
        List<LyricLine> lines = Arrays.asList(line(0, 1000), line(0, 1000),
                line(0, 1000), line(0, 1000), line(0, 1000));
        LyricFocus focus = new LyricFocus();
        focus.update(lines, 500, 99, 4);
        assertEquals(4, focus.count);
        assertFalse(focus.contains(0));
        assertEquals(1, focus.first);
        assertTrue(focus.update(lines, 500, 1, 4));
        assertEquals(1, focus.count);
        assertTrue(focus.contains(4));
        assertEquals(1, LyricFocus.clampLimit(-1));
    }

    @Test public void backingVoiceExtendsParentWithoutTakingSlot() {
        LyricLine parent = line(100, 200);
        parent.bg = line(0, 900);
        LyricFocus focus = new LyricFocus();
        List<LyricLine> lines = Arrays.asList(parent, line(300, 800));
        focus.update(lines, 50, 2, 0);
        assertTrue(focus.contains(0));
        focus.update(lines, 500, 2, 1);
        assertEquals(2, focus.count);
        focus.update(lines, 850, 2, 1);
        assertEquals(1, focus.count);
        assertEquals(0, focus.last);
    }

    @Test public void seekAndExclusiveEndDoNotRetainStaleSelection() {
        LyricFocus focus = new LyricFocus();
        List<LyricLine> lines = Arrays.asList(line(0, 100), line(50, 200));
        assertTrue(focus.update(lines, 75, 3, 1));
        assertFalse(focus.update(lines, 80, 3, 1));
        assertTrue(focus.update(lines, 100, 3, 1));
        assertFalse(focus.contains(0));
        focus.update(lines, 25, 3, 0);
        assertEquals(0, focus.last);
        focus.update(lines, 200, 3, 1);
        assertEquals(0, focus.count);
        assertEquals(1, focus.last);
        focus.reset();
        assertEquals(-1, focus.first);
    }

    @Test public void layoutIncludesFullBackingAndTranslationHeight() {
        LyricFocus focus = new LyricFocus();
        focus.update(Arrays.asList(line(0, 100), line(0, 100)), 50, 4, 1);
        float[] base = {0, 320};
        focus.layout(base, new float[]{300, 200}, 20);
        assertEquals(520, focus.height(base, new float[]{300, 200}), 0);
    }
}

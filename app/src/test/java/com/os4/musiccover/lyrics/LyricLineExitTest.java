package com.os4.musiccover.lyrics;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class LyricLineExitTest {
    private static LyricLine line(int start, int end) {
        return new LyricLine("歌词", null, start, end, false, null, null, null);
    }

    private static void capture(LyricLineExit exits, int index, float y) {
        exits.capture(index, 40f, y, 1.1f, 0.9f, 1f, 1f, 0f);
    }

    @Test public void actualEndKeepsLastDrawnPositionAndShrinksWhileFading() {
        List<LyricLine> lines = Arrays.asList(line(0, 1000), line(1000, 2000));
        LyricLineExit exits = new LyricLineExit();
        exits.step(lines, 900, 100L, true, false, true);
        capture(exits, 0, 500f);
        exits.step(lines, 999, 199L, true, false, true);
        assertFalse(exits.row(0).departing());
        assertTrue(exits.step(lines, 1000, 200L, true, false, true));
        LyricLineExit.Row row = exits.row(0);
        assertEquals(500f, row.y, 0f);
        assertEquals(1f, row.alpha(200L), 0f);
        assertEquals(1f, row.scale(200L), 0f);
        capture(exits, 0, 200f); // the next focus's upward scroll must not move this pose
        assertEquals(500f, row.y, 0f);
        assertEquals(0.5f, row.alpha(350L), 0f);
        assertEquals(0.925f, row.scale(350L), 0.0001f);
        exits.step(lines, 1300, 500L, true, false, true);
        assertTrue(row.finished);
        assertEquals(0f, row.alpha(500L), 0f);
        assertEquals(0.85f, row.scale(500L), 0.0001f);
        assertFalse(exits.row(1).departing());
        assertFalse(exits.moving());
        assertEquals(2, lines.size()); // presentation never removes lyric data
    }

    @Test public void leadWaitsForBackgroundAndLeavesUnfinishedOverlapVisible() {
        LyricLine lead = line(0, 1000);
        lead.bg = line(400, 2400);
        lead.end = Math.max(lead.end, lead.bg.end);
        List<LyricLine> lines = Arrays.asList(lead, line(500, 3000));
        LyricLineExit exits = new LyricLineExit();
        exits.step(lines, 900, 100L, true, false, true);
        capture(exits, 0, 400f);
        capture(exits, 1, 700f);
        exits.step(lines, 1000, 200L, true, false, true);
        assertFalse(exits.row(0).departing());
        exits.step(lines, 1800, 1000L, true, false, true);
        assertFalse(exits.row(0).departing());
        exits.step(lines, 2400, 1600L, true, false, true);
        assertTrue(exits.row(0).departing());
        assertFalse(exits.row(1).departing());
        assertEquals(2400, lead.bg.end);
    }

    @Test public void losingFocusToANewerSingerCannotEndAnUnfinishedVoice() {
        List<LyricLine> lines = Arrays.asList(line(0, 10000), line(1000, 2000));
        LyricLineExit exits = new LyricLineExit();
        exits.step(lines, 900, 100L, true, false, true);
        capture(exits, 0, 400f);
        LyricFocus focus = new LyricFocus();
        focus.update(lines, 1100, 1, 1);
        assertFalse(focus.contains(0));
        exits.step(lines, 1100, 300L, true, false, true);
        assertFalse(exits.row(0).departing());
    }

    @Test public void wordTimingExtendingTheDeclaredEndStillFinishesBeforeExit() {
        LyricLine timed = new LyricLine("歌词", null, 0, 1000, false,
                new int[]{0, 700}, new int[]{700, 1800}, new int[]{1, 2});
        List<LyricLine> lines = Arrays.asList(timed);
        LyricLineExit exits = new LyricLineExit();
        exits.step(lines, 900, 100L, true, false, true);
        capture(exits, 0, 400f);
        exits.step(lines, 1000, 200L, true, false, true);
        assertFalse(exits.row(0).departing());
        exits.step(lines, 1800, 1000L, true, false, true);
        assertTrue(exits.row(0).departing());
    }

    @Test public void forwardSeekDoesNotReplayEveryCrossedEndAndBackwardSeekCanSingAgain() {
        List<LyricLine> lines = Arrays.asList(line(0, 1000), line(1000, 2000));
        LyricLineExit exits = new LyricLineExit();
        exits.step(lines, 900, 100L, true, false, true);
        capture(exits, 0, 400f);
        capture(exits, 1, 600f);
        exits.step(lines, 2500, 200L, true, true, true);
        assertFalse(exits.moving());
        assertFalse(exits.row(0).departing());
        assertFalse(exits.row(1).departing());
        exits.step(lines, 900, 300L, true, true, true);
        capture(exits, 0, 450f);
        exits.step(lines, 950, 550L, true, false, true);
        exits.step(lines, 1000, 600L, true, false, true);
        assertTrue(exits.row(0).departing());
        assertEquals(450f, exits.row(0).y, 0f);
    }

    @Test public void pauseAtEndCanFinishTheExistingExitWithoutAdvancingPlayback() {
        List<LyricLine> lines = Arrays.asList(line(0, 1000));
        LyricLineExit exits = new LyricLineExit();
        exits.step(lines, 900, 100L, true, false, true);
        capture(exits, 0, 400f);
        exits.step(lines, 1000, 200L, false, false, true);
        assertTrue(exits.moving());
        exits.step(lines, 1000, 350L, false, false, true);
        assertEquals(0.5f, exits.row(0).alpha(350L), 0f);
        exits.step(lines, 1000, 500L, false, false, true);
        assertTrue(exits.row(0).finished);
        assertFalse(exits.moving());
    }

    @Test public void sleepStillFramesAndUnseenLinesDoNotAnimateStalePoses() {
        List<LyricLine> lines = Arrays.asList(line(0, 1000));
        LyricLineExit exits = new LyricLineExit();
        exits.step(lines, 900, 100L, true, false, true);
        capture(exits, 0, 400f);
        exits.step(lines, 5000, 4200L, true, false, true);
        assertFalse(exits.moving());
        exits.reset(1);
        exits.step(lines, 900, 5000L, true, false, false);
        capture(exits, 0, 400f);
        exits.step(lines, 1000, 5100L, true, false, false);
        assertFalse(exits.moving());
        exits.reset(1);
        exits.step(lines, 900, 6000L, true, false, true);
        exits.step(lines, 1000, 6100L, true, false, true);
        assertFalse(exits.moving());
    }

    @Test public void relayoutKeepsCompletedExitButNewTrackAndLoopResetIt() {
        List<LyricLine> lines = Arrays.asList(line(0, 1000));
        LyricLineExit exits = new LyricLineExit();
        exits.step(lines, 900, 100L, true, false, true);
        capture(exits, 0, 400f);
        exits.step(lines, 1000, 200L, true, false, true);
        exits.finishForLayout();
        assertTrue(exits.row(0).finished);
        assertTrue(exits.row(0).departing());
        exits.step(lines, 50, 300L, true, false, true);
        assertFalse(exits.row(0).departing());
        capture(exits, 0, 500f);
        exits.step(lines, 1000, 1250L, true, false, true);
        assertTrue(exits.row(0).departing());
        exits.reset(1); // a different song can have the same row count
        assertFalse(exits.row(0).departing());
        assertFalse(exits.row(0).captured);
    }

    @Test public void movingCardFadesDepartingRowBeforeClippingWithoutDoublingCapturedFade() {
        // A row already dimmed by its captured pose starts at that same alpha.
        assertEquals(0.5f, LyricLineExit.edgeAlpha(0.5f, 400f, 500f,
                300f, 600f, 100f), 0f);
        assertEquals(0.5f, LyricLineExit.edgeAlpha(0.5f, 400f, 500f,
                300f, 550f, 100f), 0f);
        // The upward moving card fades it to zero before clipping through its bottom.
        assertEquals(0.25f, LyricLineExit.edgeAlpha(0.5f, 400f, 500f,
                300f, 525f, 100f), 0f);
        assertEquals(0f, LyricLineExit.edgeAlpha(0.5f, 400f, 500f,
                300f, 500f, 100f), 0f);
        assertEquals(0f, LyricLineExit.edgeAlpha(0.5f, 400f, 500f,
                300f, 450f, 100f), 0f);
        // Shrinking/closing upward must respect the other band edge as well.
        assertEquals(0.2f, LyricLineExit.edgeAlpha(0.5f, 320f, 420f,
                300f, 600f, 100f), 0.0001f);
    }
}

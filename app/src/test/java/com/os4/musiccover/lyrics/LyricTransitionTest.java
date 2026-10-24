package com.os4.musiccover.lyrics;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class LyricTransitionTest {
    @Test public void resizingPreservesVisibleTopAndSpringDisplacement() {
        float[] oldHeight = {80}, height = {160}, scale = {0.97f};
        float[] scroll = {20}, aim = {40}, next = {60};
        float top = LyricTransition.scaledTop(100 - scroll[0], oldHeight[0], scale[0]);
        float displacement = scroll[0] - aim[0];
        LyricTransition.resize(oldHeight, height, 0.5f, scale, scroll, aim, next);
        assertEquals(top, LyricTransition.scaledTop(100 - scroll[0], height[0], scale[0]), 0.001f);
        assertEquals(displacement, scroll[0] - aim[0], 0.001f);
        assertEquals(20, next[0] - aim[0], 0.001f);
        assertEquals(80 * 0.97f, height[0] * scale[0], 0.001f);
    }

    @Test public void scalingKeepsTextCenterAtEveryFontSize() {
        for (float font : new float[]{18, 25, 36}) {
            for (float scale : new float[]{0.90f, 0.97f, 1f, 1.025f, 1.4f}) {
                float height = 90 * font / 25;
                float top = LyricTransition.scaledTop(150, height, scale);
                assertEquals(150 + height / 2, top + height * scale / 2, 0.001f);
            }
        }
    }

    @Test public void centeringRetargetKeepsPositionAndVelocityDisplacementContinuous() {
        float anchor = 300;
        float[] scroll = {40, 70}, aim = {100, 100}, next = {120, 120};
        float oldY = anchor + 200 - scroll[1];
        float oldDisplacement = scroll[1] - aim[1];
        LyricTransition.shift(80, scroll, aim, next);
        anchor += 80;
        assertEquals(oldY, anchor + 200 - scroll[1], 0);
        assertEquals(oldDisplacement, scroll[1] - aim[1], 0);
        assertEquals(20, next[1] - aim[1], 0);
    }

    private LyricFocus focusAt(int position, float[] base, float[] heights) {
        LyricFocus focus = new LyricFocus();
        focus.update(Arrays.asList(
                new LyricLine("held", null, 0, 1000, false, null, null, null),
                new LyricLine("short", null, 0, 100, false, null, null, null),
                new LyricLine("new", null, 50, 1000, false, null, null, null)), position, 4, 2);
        focus.layout(base, heights, 20);
        return focus;
    }

    @Test public void compactAndExpandPreserveScreenPositionsAndSpringDisplacement() {
        for (float font : new float[]{18, 25, 36}) {
            float height = 100 * font / 25;
            float[] base = {0, height + 20, 2 * (height + 20)};
            float[] heights = {height, height, height};
            LyricTransition transition = new LyricTransition();
            transition.reset(base);
            float[] scroll = {30, 40, 50}, aim = {60, 60, 60}, next = {70, 70, 70};
            float oldY = base[2] - scroll[2];
            float displacement = scroll[2] - aim[2];
            float pending = next[2] - aim[2];
            transition.rebase(focusAt(150, base, heights), base, scroll, aim, next);
            assertEquals(oldY, transition.position(2) - scroll[2], 0.0001f);
            assertEquals(displacement, scroll[2] - aim[2], 0.0001f);
            assertEquals(pending, next[2] - aim[2], 0.0001f);
            transition.rebase(focusAt(75, base, heights), base, scroll, aim, next);
            assertEquals(oldY, transition.position(2) - scroll[2], 0.0001f);
        }
    }

    @Test public void fadeCanReverseWithoutJumpAndEventuallyStops() {
        float[] base = {0, 120, 240}, heights = {100, 100, 100};
        LyricTransition transition = new LyricTransition();
        transition.reset(base);
        LyricFocus compact = focusAt(150, base, heights);
        transition.update(compact, base, -1, 0.6f, 0.016f, false);
        assertTrue(transition.alpha(1) > 0 && transition.alpha(1) < 1);
        float previousAlpha = transition.alpha(1);
        LyricFocus expanded = focusAt(75, base, heights);
        transition.update(expanded, base, -1, 0.6f, 0, false);
        assertEquals(previousAlpha, transition.alpha(1), 0);
        for (int frame = 0; frame < 200; frame++) {
            transition.update(expanded, base, -1, 0.6f, 0.016f, false);
        }
        assertEquals(1, transition.alpha(1), 0);
        assertFalse(transition.moving);
    }

    @Test public void tallWrappedRowsKeepTheirFullLayoutDuringEntryAndRetarget() {
        for (float height : new float[]{400f, 4000f, 40000f}) {
            float[] base = {0, height + 20, 2 * (height + 20)};
            float[] heights = {height, height, height};
            LyricFocus focus = focusAt(75, base, heights);
            LyricTransition transition = new LyricTransition();
            transition.reset(base);
            transition.update(focus, base, -1, 0.6f, 0f, true);
            for (int frame = 0; frame < 120; frame++) {
                transition.update(focus, base, -1, 0.6f, 1f / 120f, false);
                assertEquals(height + 20, transition.position(1) - transition.position(0), 0f);
                assertEquals(1f, transition.alpha(0), 0f);
            }
        }
    }

    @Test public void departingOverlapRemainsVisibleDuringFade() {
        float[] base = {0, 120, 240}, heights = {100, 100, 100};
        LyricTransition transition = new LyricTransition();
        transition.reset(base);
        LyricFocus compact = focusAt(150, base, heights);
        for (int frame = 0; frame < 15; frame++) {
            transition.update(compact, base, -1, 0.6f, 0.016f, false);
        }
        assertTrue(transition.alpha(1) > 0.15f && transition.alpha(1) < 0.9f);
        for (int frame = 0; frame < 200; frame++) {
            transition.update(compact, base, -1, 0.6f, 0.016f, false);
        }
        assertEquals(0, transition.alpha(1), 0);
        assertFalse(transition.moving);
    }

    @Test public void stillModeSettlesImmediately() {
        float[] base = {0, 120, 240}, heights = {100, 100, 100};
        LyricTransition transition = new LyricTransition();
        transition.reset(base);
        transition.update(focusAt(150, base, heights), base, -1, 0.6f, 0, true);
        assertEquals(0, transition.alpha(1), 0);
        assertFalse(transition.moving);
    }

    @Test public void uninitializedSeekTimestampCannotOverflowIntoRecentSeek() {
        assertFalse(LyricTransition.recentSeek(10000, Long.MIN_VALUE, 250));
        assertTrue(LyricTransition.recentSeek(10000, 9900, 250));
        assertFalse(LyricTransition.recentSeek(10000, 9750, 250));
        assertFalse(LyricTransition.recentSeek(10000, 11000, 250));
    }

    @Test public void motionAdaptsToCountAndMeasuredHeight() {
        float single = LyricTransition.motionWeight(1, 100, 600);
        float four = LyricTransition.motionWeight(4, 460, 600);
        assertTrue(four > single);
        assertTrue(LyricTransition.motionWeight(4, 660, 600) > four);
        assertTrue(LyricTransition.motionWeight(4, 660, 0) < 2);
    }
}

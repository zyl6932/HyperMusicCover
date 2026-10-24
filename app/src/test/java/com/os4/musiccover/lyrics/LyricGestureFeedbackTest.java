package com.os4.musiccover.lyrics;

import org.junit.Test;
import static org.junit.Assert.*;

public class LyricGestureFeedbackTest {
    private static void settle(LyricGestureFeedback state, float speed) {
        for (int i = 0; i < 240; i++) state.step(speed, 1f / 120f);
    }

    @Test public void fasterUpwardMotionShrinksMoreWithAHardFloor() {
        LyricGestureFeedback slow = new LyricGestureFeedback();
        LyricGestureFeedback fast = new LyricGestureFeedback();
        settle(slow, 500f);
        settle(fast, 2000f);
        assertTrue(slow.scale() < 1f);
        assertTrue(fast.scale() < slow.scale());
        settle(fast, Float.MAX_VALUE);
        assertEquals(LyricGestureFeedback.MIN_SCALE, fast.scale(), 0f);
        assertTrue(fast.moving()); // still sample the bridge until its finger goes away
    }

    @Test public void pixelVelocityIsDensityIndependentAndDownwardMotionDoesNotShrink() {
        assertEquals(800f, LyricGestureFeedback.upwardSpeed(-1600f, 2f), 0f);
        assertEquals(800f, LyricGestureFeedback.upwardSpeed(-2400f, 3f), 0f);
        assertEquals(0f, LyricGestureFeedback.upwardSpeed(2400f, 3f), 0f);
        assertEquals(0f, LyricGestureFeedback.upwardSpeed(Float.NaN, 3f), 0f);
        assertEquals(0f, LyricGestureFeedback.upwardSpeed(Float.NEGATIVE_INFINITY, 3f), 0f);
        assertEquals(0f, LyricGestureFeedback.upwardSpeed(-2400f, 0f), 0f);
        assertEquals(0f, LyricGestureFeedback.upwardSpeed(-2400f, Float.NaN), 0f);
        assertEquals(2400f, LyricGestureFeedback.upwardSpeed(-Float.MAX_VALUE, Float.MIN_VALUE), 0f);
    }

    @Test public void releaseOrCancelReturnsSmoothlyAndStopsRequestingFrames() {
        LyricGestureFeedback state = new LyricGestureFeedback();
        settle(state, 2400f);
        state.step(Float.NaN, 1f / 120f); // real UP/CANCEL, never card inertia
        assertTrue(state.scale() > LyricGestureFeedback.MIN_SCALE);
        assertTrue(state.scale() < 1f);
        assertTrue(state.moving());
        settle(state, Float.NaN);
        assertEquals(1f, state.scale(), 0f);
        assertFalse(state.moving());
    }

    @Test public void reverseDirectionAndASecondDragContinueFromTheCurrentScale() {
        LyricGestureFeedback state = new LyricGestureFeedback();
        settle(state, 2000f);
        float shrunk = state.scale();
        state.step(0f, 1f / 120f);
        assertTrue(state.scale() > shrunk);
        state.step(Float.NaN, 1f / 120f);
        float returning = state.scale();
        state.step(2400f, 1f / 120f);
        assertTrue(state.scale() < returning);
        assertTrue(state.scale() >= LyricGestureFeedback.MIN_SCALE);
        assertTrue(state.scale() < 1f); // no restart at 1 on consecutive drags
    }

    @Test public void invalidAndHugeFrameGapsCannotPoisonOrOvershootTheState() {
        LyricGestureFeedback state = new LyricGestureFeedback();
        assertFalse(state.step(2400f, Float.NaN));
        assertFalse(state.step(2400f, Float.POSITIVE_INFINITY));
        assertFalse(state.step(2400f, -1f));
        assertEquals(1f, state.scale(), 0f);
        state.step(2400f, Float.MAX_VALUE);
        assertTrue(state.scale() > LyricGestureFeedback.MIN_SCALE);
        assertTrue(state.scale() < 1f);
        float before = state.scale();
        state.step(Float.POSITIVE_INFINITY, 1f / 120f);
        assertTrue(state.scale() > before);
        settle(state, -100f);
        assertEquals(1f, state.scale(), 0f);
        state.reset();
        assertFalse(state.moving());
        assertEquals(1f, state.scale(), 0f);
    }

    @Test public void refreshRateDoesNotChangeTheResponseForTheSameElapsedTime() {
        LyricGestureFeedback hz60 = new LyricGestureFeedback();
        LyricGestureFeedback hz120 = new LyricGestureFeedback();
        for (int i = 0; i < 12; i++) hz60.step(1800f, 1f / 60f);
        for (int i = 0; i < 24; i++) hz120.step(1800f, 1f / 120f);
        assertEquals(hz60.scale(), hz120.scale(), 0.00001f);
    }

    @Test public void entryAndDragTogetherStayBoundedAndUseTheCurrentVisibility() {
        assertEquals(0.94f, LyricGestureFeedback.presentationScale(0f, 1f), 0f);
        assertEquals(1f, LyricGestureFeedback.presentationScale(1f, 1f), 0f);
        assertEquals(LyricGestureFeedback.MIN_SCALE,
                LyricGestureFeedback.presentationScale(0.2f, LyricGestureFeedback.MIN_SCALE), 0f);
        assertEquals(1f, LyricGestureFeedback.entryOffset(0f), 0f);
        assertEquals(0.3f, LyricGestureFeedback.entryOffset(0.7f), 0.00001f);
        // Reversal reads the new visibility, without starting another timed entrance.
        assertEquals(0.6f, LyricGestureFeedback.entryOffset(0.4f), 0.00001f);
        assertEquals(0f, LyricGestureFeedback.entryOffset(1f), 0f);
        assertEquals(0f, LyricGestureFeedback.entryOffset(Float.NaN), 0f);
        assertEquals(1f, LyricGestureFeedback.presentationScale(Float.NaN, Float.NaN), 0f);
        assertEquals(1f, LyricGestureFeedback.presentationScale(5f, Float.POSITIVE_INFINITY), 0f);
        assertEquals(1f, LyricGestureFeedback.entryOffset(-5f), 0f);
    }
}

package com.os4.musiccover;
import com.os4.musiccover.clock.ClockDateLayout;
import com.os4.musiccover.clock.ClockPlacement;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClockDateLayoutTest {
    @Test public void aDateReturningInAodCannotOverlapTheHeldTime() {
        assertEquals(334f, ClockDateLayout.heldTimeTop(228f, Float.NaN, 76f, 30f), 0f);
        assertEquals(228f, ClockDateLayout.heldTimeTop(334f, 228f, 0f, 30f), 0f);
        // Repeated frames and interrupted entry poses must not accumulate a shift.
        assertEquals(334f, ClockDateLayout.heldTimeTop(334f, 228f, 76f, 30f), 0f);
        assertEquals(698.5f, ClockDateLayout.heldTimeTop(698.5f, 582.6f, 76f, 30f), 0f);
    }

    @Test public void wakeRestylingCannotReserveADateHiddenLaterInPreDraw() {
        ClockDateLayout<Object> layout = new ClockDateLayout<>();
        Object date = new Object();
        layout.commit(null, 0f);
        for (int frame = 0; frame < 120; frame++) {
            // OEM briefly restores the date; another pre-draw hides it before drawing.
            assertNull(layout.forFrame(date));
            assertEquals(228f, ClockPlacement.timeTop(228f,
                    layout.heightForFrame(76f), 30f), 0f);
            assertFalse(layout.commit(null, 0f));
        }
    }

    @Test public void aRealDateChangeTakesEffectAtTheNextSharedLayout() {
        ClockDateLayout<Object> layout = new ClockDateLayout<>();
        Object date = new Object();
        layout.commit(null, 0f);
        assertTrue(layout.commit(date, 76f));
        assertSame(date, layout.forFrame(null));
        assertEquals(334f, ClockPlacement.timeTop(228f,
                layout.heightForFrame(0f), 30f), 0f);
        assertTrue(layout.commit(null, 0f));
        assertNull(layout.forFrame(date));
    }

    @Test public void aRebuiltClockCannotReuseThePreviousStylesDate() {
        ClockDateLayout<Object> layout = new ClockDateLayout<>();
        Object oldDate = new Object(), newDate = new Object();
        layout.commit(oldDate, 76f);
        layout.reset();
        assertSame(newDate, layout.forFrame(newDate));
        assertEquals(90f, layout.heightForFrame(90f), 0f);
    }
}

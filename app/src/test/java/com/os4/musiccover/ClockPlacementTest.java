package com.os4.musiccover;

import com.os4.musiccover.clock.ClockPlacement;
import org.junit.Test;

import static org.junit.Assert.*;

public class ClockPlacementTest {
    @Test public void normalCutoutKeepsTheExistingTopLineAtEveryDensity() {
        for (float d : new float[]{1f, 2f, 3f, 4f}) {
            assertEquals(76f * d, ClockPlacement.blockTop(d, 48f * d, 0f), 0.001f);
        }
    }

    @Test public void tallCutoutAndNegativeOffsetCannotPutContentInTheUnsafeArea() {
        assertEquals(108f, ClockPlacement.blockTop(1f, 100f, 0f), 0.001f);
        assertEquals(108f, ClockPlacement.blockTop(1f, 100f, -60f), 0.001f);
        assertEquals(56f, ClockPlacement.blockTop(1f, 48f, -60f), 0.001f);
        assertEquals(376f, ClockPlacement.blockTop(1f, 48f, 300f), 0.001f);
    }

    @Test public void missingDateReclaimsItsWholeSlotIncludingTheGap() {
        assertEquals(334f, ClockPlacement.timeTop(228f, 76f, 30f), 0.001f);
        assertEquals(228f, ClockPlacement.timeTop(228f, 0f, 30f), 0.001f);
    }

    @Test public void entryAndExitCanFinishWithoutAnyClockOrDateGeometry() {
        assertEquals(0f, ClockPlacement.progress(0f, 1f, 0f, 0.15f), 0f);
        assertEquals(0f, ClockPlacement.progress(0f, 1f, 0.1f, 0.15f), 0f);
        assertEquals(1f, ClockPlacement.progress(0f, 1f, 1f, 0.15f), 0f);
        assertEquals(1f, ClockPlacement.progress(0f, 1f, 1.02f, 0.15f), 0f);
        assertEquals(0.5f, ClockPlacement.progress(1f, 0f, 0.5f, 0f), 0f);
        assertEquals(0f, ClockPlacement.progress(1f, 0f, 1f, 0f), 0f);
    }
}

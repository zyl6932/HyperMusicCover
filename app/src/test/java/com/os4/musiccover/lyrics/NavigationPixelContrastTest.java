package com.os4.musiccover.lyrics;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class NavigationPixelContrastTest {
    private static NavigationPixelContrast.Counts page(int bright, int dark) {
        int[] pixels = new int[bright + dark];
        Arrays.fill(pixels, 0, bright, 0xfff3f3f3);
        Arrays.fill(pixels, bright, pixels.length, 0xff131313);
        return NavigationPixelContrast.count(pixels);
    }

    @Test public void actualDayAndNightPixelsChooseOppositeInkWithoutAnySystemTheme() {
        assertEquals(NavigationPixelContrast.BLACK, page(90, 10).preferred());
        assertEquals(NavigationPixelContrast.WHITE, page(10, 90).preferred());
        assertEquals(90, page(90, 10).bright);
        assertEquals(10, page(90, 10).dark);
    }

    @Test public void countsTheMajorityInsteadOfAveragingBrightRoadsIntoADarkBackground() {
        int[] pixels = new int[100];
        Arrays.fill(pixels, 0, 60, 0xff828282);
        Arrays.fill(pixels, 60, 100, 0xff000000);
        NavigationPixelContrast.Counts counts = NavigationPixelContrast.count(pixels);
        assertEquals(60, counts.bright);
        assertEquals(NavigationPixelContrast.BLACK, counts.preferred());
        // A simple RGB mean would instead suggest white, contradicting most sampled pixels.
        assertEquals(NavigationPixelContrast.WHITE, LyricContrastColors.fromBackground(0xff4e4e4e));
    }

    @Test public void transparentEmptyOrUnexpectedlyLargeBuffersAreUnknown() {
        assertFalse(NavigationPixelContrast.count(null).usable());
        assertFalse(NavigationPixelContrast.count(new int[100]).usable());
        assertFalse(NavigationPixelContrast.count(new int[8193]).usable());
        int[] mostlyTransparent = new int[100];
        Arrays.fill(mostlyTransparent, 0, 70, 0xffffffff);
        assertEquals(0, NavigationPixelContrast.count(mostlyTransparent).preferred());
        assertEquals(0, page(30, 30).preferred());
    }

    @Test public void mapDayNightChangeNeedsTwoDominantObservations() {
        NavigationPixelContrast.Stability state = new NavigationPixelContrast.Stability();
        assertEquals(NavigationPixelContrast.BLACK, state.accept(page(95, 5)));
        assertEquals(NavigationPixelContrast.BLACK, state.accept(page(10, 90)));
        assertEquals(NavigationPixelContrast.WHITE, state.accept(page(10, 90)));
        assertEquals(NavigationPixelContrast.WHITE, state.accept(page(95, 5)));
        assertEquals(NavigationPixelContrast.BLACK, state.accept(page(95, 5)));
    }

    @Test public void routeMotionAndFailedSamplesCannotBuildUpAFalseThemeChange() {
        NavigationPixelContrast.Stability state = new NavigationPixelContrast.Stability();
        state.accept(page(90, 10));
        assertEquals(NavigationPixelContrast.BLACK, state.accept(page(49, 51)));
        assertEquals(NavigationPixelContrast.BLACK, state.accept(page(10, 90)));
        assertEquals(NavigationPixelContrast.BLACK, state.accept(null));
        assertEquals(NavigationPixelContrast.BLACK, state.accept(page(10, 90)));
        assertEquals(NavigationPixelContrast.BLACK, state.accept(page(90, 10)));
    }

    @Test public void replacementSurfaceStartsUnknownRatherThanInheritingAnotherMap() {
        NavigationPixelContrast.Stability state = new NavigationPixelContrast.Stability();
        state.accept(page(90, 10));
        state.reset();
        assertEquals(0, state.color());
        assertEquals(0, state.accept(null));
        assertEquals(NavigationPixelContrast.WHITE, state.accept(page(10, 90)));
    }

    @Test public void onlyADominantOppositeSampleRequestsPromptConfirmation() {
        NavigationPixelContrast.Stability state = new NavigationPixelContrast.Stability();
        state.accept(page(90, 10));
        assertFalse(state.needsConfirmation());
        state.accept(page(49, 51));
        assertFalse(state.needsConfirmation());
        state.accept(page(10, 90));
        assertTrue(state.needsConfirmation());
        assertEquals(NavigationPixelContrast.BLACK, state.color());
        state.accept(page(10, 90));
        assertFalse(state.needsConfirmation());
        assertEquals(NavigationPixelContrast.WHITE, state.color());
        state.accept(page(90, 10));
        assertTrue(state.needsConfirmation());
        state.accept(null);
        assertFalse(state.needsConfirmation());
    }
}

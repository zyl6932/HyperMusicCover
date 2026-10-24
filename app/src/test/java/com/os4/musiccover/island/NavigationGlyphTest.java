package com.os4.musiccover.island;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class NavigationGlyphTest {
    private int[] glyph() {
        int[] pixels = new int[36];
        Arrays.fill(pixels, 10, 20, 0xff000000);
        pixels[20] = 0x80000000;
        return pixels;
    }
    @Test public void darkDirectionGetsWhiteWithoutChangingAlphaOrOriginal() {
        int[] original = glyph();
        assertTrue(NavigationGlyph.monochrome(original));
        int[] white = NavigationGlyph.recolor(original, 0xffffffff);
        assertEquals(0xff000000, original[10]);
        assertEquals(0xffffffff, white[10]);
        assertEquals(0x80ffffff, white[20]);
        assertEquals(0, white[0] >>> 24);
        assertEquals(0xff1a1a1a, NavigationGlyph.recolor(original, 0xff1a1a1a)[10]);
    }
    @Test public void multicolorAndOpaqueAppTilesStayUntouched() {
        int[] multicolor = glyph();
        multicolor[12] = 0xff34cc22;
        assertFalse(NavigationGlyph.monochrome(multicolor));
        int[] opaque = new int[36];
        Arrays.fill(opaque, 0xff000000);
        assertFalse(NavigationGlyph.monochrome(opaque));
        assertFalse(NavigationGlyph.monochrome(new int[36]));
    }
}

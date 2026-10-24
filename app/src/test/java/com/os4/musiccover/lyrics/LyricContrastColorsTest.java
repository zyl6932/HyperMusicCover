package com.os4.musiccover.lyrics;

import org.junit.Test;
import static org.junit.Assert.*;

public class LyricContrastColorsTest {
    @Test public void lightAndDarkBackgroundsChooseOppositeInk() {
        assertEquals(0xff000000, LyricContrastColors.fromBackground(0xffffffff));
        assertEquals(0xffffffff, LyricContrastColors.fromBackground(0xff000000));
        assertEquals(0xff000000, LyricContrastColors.fromBackground(0xffeeeecc));
        assertEquals(0xffffffff, LyricContrastColors.fromBackground(0xff102040));
    }

    @Test public void midGrayUsesLinearContrastRatherThanAnArbitraryHalfRgbThreshold() {
        assertEquals(0xffffffff, LyricContrastColors.fromBackground(0xff757575));
        assertEquals(0xff000000, LyricContrastColors.fromBackground(0xff767676));
        assertEquals(0xff000000, LyricContrastColors.fromBackground(0xff999999));
    }

    @Test public void unknownTransparentInputDoesNotInventAResolvedBackground() {
        assertEquals(0, LyricContrastColors.fromBackground(0));
        assertEquals(0, LyricContrastColors.fromBackground(0x00ffffff));
        assertEquals(0, LyricContrastColors.fromForeground(0));
    }

    @Test public void systemForegroundKeepsItsDirectionInsteadOfBeingInvertedTwice() {
        assertEquals(0xff000000, LyricContrastColors.fromForeground(0xff202020));
        assertEquals(0xffffffff, LyricContrastColors.fromForeground(0xffeeeeee));
        assertEquals(0xffffffff, LyricContrastColors.fromForeground(0xffe6ddaa));
        assertEquals(0xff000000, LyricContrastColors.fromForeground(0xff202840));
    }
}

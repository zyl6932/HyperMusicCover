package com.os4.musiccover.lyrics;

import org.junit.Test;
import static org.junit.Assert.*;

public class LyricAppearanceTest {
    @Test public void defaultsMatchOfficialBackingStyle() {
        LyricAppearance a = LyricAppearance.parse("{}");
        assertEquals(LyricAppearance.OFFICIAL, a.bgMode);
        assertEquals(0.72f, a.bgOpacity, 0f);
        assertEquals(17f, LyricStyle.DEFAULT.bgSizeSp, 0f);
        assertEquals(17f, LyricStyle.DEFAULT.with("size", 36f).bgSizeSp, 0f);
        assertEquals(25f, LyricStyle.DEFAULT.with("bgsize", 30f).sizeSp, 0f);
        assertFalse(LyricStyle.DEFAULT.sameLayout(LyricStyle.DEFAULT.with("bgsize", 20f)));
    }
    @Test public void independentAppearanceSurvivesPersistence() {
        LyricAppearance a = new LyricAppearance(1, 2, 0x123456, 0xabcdef, .4f, .8f);
        LyricAppearance b = LyricAppearance.parse(a.toJson());
        assertEquals(a.toJson(), b.toJson());
        assertEquals(0xffabcdef, b.bgColor);
    }
    @Test public void systemColorsAndIndependentFontBoundsSurviveConfiguration() {
        LyricAppearance system = new LyricAppearance(LyricAppearance.SYSTEM, LyricAppearance.CUSTOM,
                0x123456, 0xabcdef, 1f, .72f);
        assertEquals(LyricAppearance.SYSTEM, LyricAppearance.parse(system.toJson()).mainMode);
        LyricStyle style = LyricStyle.DEFAULT.with("size", 99f).with("bgsize", -1f);
        assertEquals(36f, style.sizeSp, 0f);
        assertEquals(10f, style.bgSizeSp, 0f);
        assertSame(style, style.with("bgsize", Float.NaN));
    }
    @Test public void invalidSettingsHaveSafeDefaults() {
        assertSame(LyricAppearance.DEFAULT, LyricAppearance.parse("invalid"));
        LyricAppearance a = new LyricAppearance(-1, 9, -1, -1, Float.NaN, 5f);
        assertEquals(0, a.mainMode);
        assertEquals(0, a.bgMode);
        assertEquals(1f, a.mainOpacity, 0f);
        assertEquals(1f, a.bgOpacity, 0f);
    }
}

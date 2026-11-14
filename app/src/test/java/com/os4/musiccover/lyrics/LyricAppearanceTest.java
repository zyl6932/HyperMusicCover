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
    @Test public void theTranslationSizeIsItsOwn() {
        assertEquals(15f, LyricStyle.DEFAULT.transSizeSp, 0f);
        assertEquals(25f, LyricStyle.DEFAULT.with("transsize", 30f).sizeSp, 0f);
        assertEquals(17f, LyricStyle.DEFAULT.with("transsize", 30f).bgSizeSp, 0f);
        assertFalse(LyricStyle.DEFAULT.sameLayout(LyricStyle.DEFAULT.with("transsize", 20f)));
    }
    @Test public void theTranslationLookIsItsOwn() {
        LyricAppearance a = LyricAppearance.DEFAULT;
        assertEquals(LyricAppearance.OFFICIAL, a.transMode);
        assertEquals(0.62f, a.transOpacity, 0f);
        // A JSON from before the translation had a row reads back looking as it did.
        LyricAppearance old = LyricAppearance.parse(
                "{\"mainMode\":1,\"mainOpacity\":0.5,\"bgMode\":2,\"bgOpacity\":0.3}");
        assertEquals(1, old.mainMode);
        assertEquals(0, old.transMode);
        assertEquals(0.62f, old.transOpacity, 0f);
        LyricAppearance t = new LyricAppearance(0, 0, -1, -1, 1f, 0.72f,
                LyricAppearance.CUSTOM, 0x123456, 0.4f);
        assertEquals(t.toJson(), LyricAppearance.parse(t.toJson()).toJson());
        assertEquals(0xff123456, LyricAppearance.parse(t.toJson()).transColor);
        assertEquals(0.4f, LyricAppearance.parse(t.toJson()).transOpacity, 0f);
    }
    @Test public void independentAppearanceSurvivesPersistence() {
        LyricAppearance a = new LyricAppearance(1, 2, 0x123456, 0xabcdef, .4f, .8f);
        LyricAppearance b = LyricAppearance.parse(a.toJson());
        assertEquals(a.toJson(), b.toJson());
        assertEquals(0xffabcdef, b.bgColor);
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

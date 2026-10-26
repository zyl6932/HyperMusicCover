package com.os4.musiccover.lyrics;

import org.junit.Test;
import static org.junit.Assert.*;

public class LyricSourcePolicyTest {
    @Test public void defaultStartsWithProviderThenMetadataAndDirectory() {
        LyricSourcePolicy.Config config = new LyricSourcePolicy.Config(false, true, "qq,local", null);
        assertEquals("lyricon", config.sources()[0]);
        assertEquals("metadata", config.sources()[1]);
        assertEquals("local", config.sources()[2]);
    }

    @Test public void customOrderIsDeduplicatedAndCompleted() {
        LyricSourcePolicy.Config config = new LyricSourcePolicy.Config(true, true, "QQ,qq,invalid, local ", null);
        assertEquals(10, config.sources().length);
        assertEquals("qq", config.sources()[0]);
        assertEquals("local", config.sources()[1]);
        assertEquals(LocalLyricFormat.TTML, config.localFormat);
    }

    @Test public void formatsAreWhitelistedAndKeepRequestedPriority() {
        assertArrayEquals(new String[]{"lrc", "ttml", "yrc", "krc", "lys"}, LocalLyricFormat.formats("LRC"));
        assertEquals(LyricSourcePolicy.DEFAULT_SOURCES, LyricSourcePolicy.normalizeSources(null));
    }

    @Test public void lowerPriorityCannotOverwriteOriginalLyrics() {
        LyricSourcePolicy.Config config = new LyricSourcePolicy.Config(false, true, null, null);
        assertFalse(config.mayReplace("metadata", "qq"));
        assertFalse(config.mayReplace("metadata", "local"));
        assertTrue(config.mayReplace("local", "lyricon"));
        assertTrue(config.mayReplace("qq", "metadata"));
        assertTrue(config.mayReplace("metadata", "metadata"));
    }

    @Test public void disablingNetworkPreservesLocalAndProviderRoutes() {
        LyricSourcePolicy.Config config = new LyricSourcePolicy.Config(true, false, "qq,metadata", null);
        for (String source : config.sources()) assertEquals(!LyricSourcePolicy.network(source), config.permits(source));
        assertTrue(config.permits("lyricon"));
        assertFalse(config.mayReplace("none", "qq"));
        assertTrue(config.mayReplace("qq", "metadata"));
    }

    @Test public void requestedOnlinePriorityIsHonoredWhenEnabled() {
        LyricSourcePolicy.Config config = new LyricSourcePolicy.Config(true, true, "qq,local,metadata", null);
        assertTrue(config.mayReplace("metadata", "qq"));
        assertFalse(config.mayReplace("qq", "metadata"));
    }
    @Test public void positionsMoveStablyWithoutTies() {
        assertEquals("local,lyricon,metadata", LyricSourcePolicy.move("lyricon,metadata,local", "local", 1));
        assertEquals("lyricon,local,metadata", LyricSourcePolicy.move("lyricon,metadata,local", "metadata", 3));
    }

    @Test public void disabledNetworkNeverCallsOnlineSourcesEvenAfterLocalMisses() {
        LyricSourcePolicy.Config config = new LyricSourcePolicy.Config(true, false, "qq,lyricon,metadata,local", null);
        java.util.List<String> called = new java.util.ArrayList<>();
        Object result = LyricSourcePolicy.first(config, source -> { called.add(source); return null; }, () -> true);
        assertNull(result);
        assertEquals(java.util.Arrays.asList("lyricon", "metadata", "local"), called);
    }

    @Test public void parsingFailureFallsThroughButUsablePreferredSourceStopsRequests() {
        LyricSourcePolicy.Config config = new LyricSourcePolicy.Config(false, true, null, null);
        java.util.List<String> called = new java.util.ArrayList<>();
        String result = LyricSourcePolicy.first(config, source -> {
            called.add(source);
            return source.equals("metadata") ? "valid" : null;
        }, () -> true);
        assertEquals("valid", result);
        assertEquals(java.util.Arrays.asList("lyricon", "metadata"), called);
    }

    @Test public void supersededAnswerIsNeverPublished() {
        final boolean[] current = {true};
        String result = LyricSourcePolicy.first(new LyricSourcePolicy.Config(false, true, null, null),
                source -> { current[0] = false; return "stale"; }, () -> current[0]);
        assertNull(result);
    }
}

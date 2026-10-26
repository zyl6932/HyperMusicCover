package com.os4.musiccover.lyrics;
import org.junit.Test;
import static org.junit.Assert.*;

public class LocalLyricFormatTest {
    @Test public void migrateLegacyOrderOnlyOnceAndKeepNewSelection() {
        assertEquals("lrc", LocalLyricFormat.migrate(null, "yrc, LRC,ttml,krc"));
        assertEquals("ttml", LocalLyricFormat.migrate(null, "lys,ttml,lrc"));
        assertEquals("ttml", LocalLyricFormat.migrate("ttml", "lrc,ttml"));
        assertEquals("lrc", LocalLyricFormat.migrate("lrc", "ttml,lrc"));
        assertEquals("ttml", LocalLyricFormat.migrate(null, "invalid"));
        assertEquals("ttml", LocalLyricFormat.migrate(null, null));
    }
    @Test public void localChoiceNeverChangesGlobalPriorityOrNetworkPermission() {
        LyricSourcePolicy.Config ttml = new LyricSourcePolicy.Config(true, false, "metadata,local,lyricon,qq", "ttml");
        LyricSourcePolicy.Config lrc = new LyricSourcePolicy.Config(true, false, ttml.sourceOrder, "lrc");
        assertArrayEquals(ttml.sources(), lrc.sources());
        assertFalse(lrc.permits("qq"));
        assertArrayEquals(new String[]{"lrc", "ttml", "yrc", "krc", "lys"}, lrc.formats());
    }
    @Test public void aLegacyOrderCannotActAsARuntimeSetting() {
        assertEquals("ttml", new LyricSourcePolicy.Config(true, true, null, "lrc,ttml").localFormat);
    }
}

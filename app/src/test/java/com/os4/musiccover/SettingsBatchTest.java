package com.os4.musiccover;

import static org.junit.Assert.assertFalse;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/**
 * A batch carrying what memory already has changes nothing (stage 5.1). That is the shape the
 * 5.4 migration arrives in, and it must not repaint the lock screen. No effects are bound in a
 * JVM test, and the no-op path must not need any: it must not touch Main.
 */
public class SettingsBatchTest {

    @Test
    public void aBatchOfWhatIsAlreadyThereIsANoOp() {
        boolean lyrics = Settings.LYRICS.on();
        float bias = Settings.BIAS.get();
        int fpAvoid = Settings.FP_AVOID.get();
        CoverCardStyle cover = Settings.COVER_STYLE.get();
        try {
            Settings.LYRICS.set(true);
            Settings.BIAS.set(0.5f);
            Settings.FP_AVOID.set(2);
            CoverCardStyle style = CoverCardStyle.defaults();
            Settings.COVER_STYLE.set(style);

            assertFalse(Settings.applyBatch(null, Arrays.asList(
                    Settings.LYRICS.change(true),
                    Settings.BIAS.change(0.5f),
                    Settings.FP_AVOID.change(2),
                    Settings.COVER_STYLE.change(style))));
        } finally {
            Settings.LYRICS.set(lyrics);
            Settings.BIAS.set(bias);
            Settings.FP_AVOID.set(fpAvoid);
            Settings.COVER_STYLE.set(cover);
        }
    }

    @Test
    public void anEmptyBatchIsANoOp() {
        assertFalse(Settings.applyBatch(null, Collections.emptyList()));
    }
}

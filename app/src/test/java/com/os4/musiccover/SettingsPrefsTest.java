package com.os4.musiccover;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * The prefs key table (stage 5.2). The keys are the app's side of the contract: a setting the
 * app keeps but the module cannot find is a value that never arrives, and a key two settings
 * answer to is one setting silently reading the other's value.
 */
public class SettingsPrefsTest {

    /** The six the table keeps no file key for; they stay out of the app's group too. */
    private static final Set<String> NOT_KEPT = new HashSet<>(Arrays.asList(
            SettingSpec.AUTO.key, SettingSpec.CLOCK_OFFSET.key, SettingSpec.GLASS_END.key,
            SettingSpec.SPRING.key, SettingSpec.MC_HIDE_ART.key,
            SettingSpec.MC_ART_IN_LYRICS.key));

    @Test
    public void theOnlySettingsKeptOutOfPrefsAreTheSixWithoutAFileKey() {
        Set<String> out = new HashSet<>();
        Set<String> kept = new HashSet<>();
        for (Settings.Entry<?> e : Settings.all()) {
            if (Settings.persisted(e)) kept.add(e.spec.key);
            else out.add(e.spec.key);
        }
        assertEquals(NOT_KEPT, out);
        assertEquals(30, kept.size());
    }

    @Test
    public void everyKeptSettingCanBeFoundFromItsKey() {
        for (Settings.Entry<?> e : Settings.all()) {
            if (!Settings.persisted(e)) continue;
            String key = e == Settings.LYRIC_STYLE ? SettingSpec.LYRIC_SIZE.key : e.spec.key;
            assertSame(e.spec.key, e, SettingsPrefs.entryForKey(key));
        }
        // The style's other half is the same entry, not a second one.
        assertSame(Settings.LYRIC_STYLE, SettingsPrefs.entryForKey(SettingSpec.LYRIC_BG_SIZE.key));
    }

    /** Not one of ours, and not one that is kept: both answer null rather than a wrong entry. */
    @Test
    public void keysThatAreNotKeptSettingsFindNothing() {
        assertNull(SettingsPrefs.entryForKey(SettingSpec.AUTO.key));
        assertNull(SettingsPrefs.entryForKey(SettingSpec.PREFS_MIGRATED));
        assertNull(SettingsPrefs.entryForKey(SettingSpec.PREFS_REV));
        assertNull(SettingsPrefs.entryForKey("nonsense"));
    }
}

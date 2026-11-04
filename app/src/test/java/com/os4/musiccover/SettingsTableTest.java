package com.os4.musiccover;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * The settings table's names. Each of the three - query key, state-file key, op and extra - is
 * how one side finds a setting, and two settings answering to the same one is a setting that
 * silently reads the other's value: the pickup card's switch overwrote "card", whether the media
 * card was showing, in the query for as long as both existed.
 */
public class SettingsTableTest {

    @Test
    public void queryKeysAreUnique() throws Exception {
        Set<String> keys = new HashSet<>();
        for (Field f : SettingSpec.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != SettingSpec.class) continue;
            String key = ((SettingSpec) f.get(null)).key;
            assertTrue("query key \"" + key + "\" twice (" + f.getName() + ")", keys.add(key));
        }
        assertFalse(keys.isEmpty());
    }

    @Test
    public void everyQueryKeyOfTheTableIsInTheSpec() throws Exception {
        Set<SettingSpec> specs = new HashSet<>();
        for (Field f : SettingSpec.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == SettingSpec.class) {
                specs.add((SettingSpec) f.get(null));
            }
        }
        for (Settings.Entry<?> e : Settings.all()) assertTrue(specs.contains(e.spec));
    }

    @Test
    public void fileKeysAreUnique() {
        Set<String> keys = new HashSet<>();
        for (Settings.Entry<?> e : Settings.all()) {
            for (Settings.FileKey k : e.files) {
                assertTrue("file key \"" + k.name + "\" twice", keys.add(k.name));
            }
        }
    }

    @Test
    public void opsAndExtrasAreUnique() {
        Set<String> seen = new HashSet<>();
        for (Settings.Entry<?> e : Settings.all()) {
            if (e.op == null) continue;
            assertNotNull(e.op + " has no extra", e.extra);
            assertTrue(e.op + " --" + e.extra + " twice", seen.add(e.op + "/" + e.extra));
        }
    }

    @Test
    public void everyEntryHasAnOp() {
        for (Settings.Entry<?> e : Settings.all()) assertNotNull(e.spec.key + " has no op", e.op);
    }

    @Test
    public void cyclingNeedsARange() {
        for (Settings.Entry<?> e : Settings.all()) {
            if (e.absent != Settings.Absent.CYCLE) continue;
            assertEquals(SettingSpec.Kind.INT, e.spec.kind);
            assertTrue(e.spec.key, Float.isFinite(e.spec.min) && Float.isFinite(e.spec.max)
                    && e.spec.min < e.spec.max);
        }
    }
}

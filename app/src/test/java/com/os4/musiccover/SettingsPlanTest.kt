package com.os4.musiccover

import com.os4.musiccover.app.SettingsOutbox
import com.os4.musiccover.app.SettingsPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's copy of the key table (stage 5.3). The two halves are one contract: a setting the
 * module keeps under a key the app does not write is a value that never arrives, and a key the
 * app writes that the module does not read is one that quietly does nothing.
 */
class SettingsPlanTest {

    @Test
    fun theTablesNameTheSameSettings() {
        val module = Settings.all()
            .filter { Settings.persisted(it) }
            .map { it.spec.key }
            .toSet()
        // The app's list is one key per row, and the lyric style's second key has no entry of
        // its own on the module's side: it is the same Ref, filed under two names.
        val app = SettingsPlan.specs.map { it.key }.toSet() - SettingSpec.LYRIC_BG_SIZE.key
        assertEquals(module, app)
        assertEquals(31, SettingsPlan.specs.size)
    }

    @Test
    fun everyKeptKeyPointsAtTheModulesOwnSpec() {
        for (entry in Settings.all()) {
            if (!Settings.persisted(entry)) continue
            val spec = SettingsPlan.specs.firstOrNull { it.key == entry.spec.key }
            assertSame(entry.spec, spec)
        }
    }

    @Test
    fun theStyleIsTheOnlySettingWithTwoKeys() {
        assertTrue(SettingsPlan.isLyricStyleKey(SettingSpec.LYRIC_SIZE.key))
        assertTrue(SettingsPlan.isLyricStyleKey(SettingSpec.LYRIC_BG_SIZE.key))
        assertFalse(SettingsPlan.isLyricStyleKey(SettingSpec.LYRICS.key))
        assertFalse(SettingsPlan.isLyricStyleKey(SettingSpec.BIAS.key))
    }

    /** The six without a file key are per-session values; writing them back would undo that. */
    @Test
    fun theSessionOnlySettingsAreNotKept() {
        val session = setOf(
            SettingSpec.AUTO.key, SettingSpec.CLOCK_OFFSET.key, SettingSpec.GLASS_END.key,
            SettingSpec.SPRING.key, SettingSpec.MC_HIDE_ART.key, SettingSpec.MC_ART_IN_LYRICS.key,
        )
        val app = SettingsPlan.specs.map { it.key }.toSet()
        assertTrue((app intersect session).isEmpty())
    }

    @Test
    fun aBurstOnOneKeyLeavesOneValue() {
        val box = SettingsOutbox<String>()
        box.put("bias", "0.1")
        box.put("bias", "0.3")
        box.put("bias", "0.4")
        assertEquals(mapOf("bias" to "0.4"), box.drain())
        assertTrue(box.isEmpty())
    }

    @Test
    fun keysKeepTheirOwnValues() {
        val box = SettingsOutbox<Int>()
        box.put("a", 1)
        box.put("b", 2)
        box.put("a", 3)
        assertEquals(mapOf("a" to 3, "b" to 2), box.drain())
    }

    @Test
    fun theCounterOnlyGoesUp() {
        val box = SettingsOutbox<Int>()
        box.seedRev(7)
        assertEquals(8, box.nextRev())
        assertEquals(9, box.nextRev())
        assertEquals(9, box.rev)
    }

    @Test
    fun anEmptyOutboxDrainsToNothing() {
        assertTrue(SettingsOutbox<Int>().drain().isEmpty())
    }

    /**
     * A value written while a batch is still on its way is not cleaned away when that batch
     * comes back: the reconcile has to see that the local copy is ahead again.
     */
    @Test
    fun aWriteDuringADeliveryStaysDirty() {
        val box = SettingsOutbox<Int>()
        box.markDirty()
        val mark = box.mark()
        assertTrue(box.dirty)
        box.clean(mark)
        assertFalse(box.dirty)

        box.markDirty()
        val newer = box.mark()
        box.markDirty()
        box.clean(newer)
        assertTrue(box.dirty)
    }
}

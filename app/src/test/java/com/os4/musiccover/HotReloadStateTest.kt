package com.os4.musiccover

import com.os4.musiccover.hotreload.HotReloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The saved state is the only thing that crosses a hot reload. Its reader must never throw: an
 * unknown or damaged shape becomes "adopt without state", which costs runtime state and nothing
 * else.
 */
class HotReloadStateTest {

    private fun encoded(): Array<Any?> = HotReloadState.encode(
        "0.9.48-hrstep4",
        "com.android.systemui",
        null,
        null,
        arrayOf(arrayOf<Any>("apple", arrayOf<Any>("held", "payload"))),
        3,
        947063,
    )

    @Test
    fun roundTrip() {
        val state = HotReloadState.decode(encoded())!!
        assertEquals(1, state.schema)
        assertEquals("0.9.48-hrstep4", state.fromBuild)
        assertEquals("com.android.systemui", state.process)
        assertEquals(3, state.reloadCount)
        assertEquals(947063, state.fromVersionCode)
        assertEquals(1, state.features.size)
        assertEquals("apple", state.features[0][0])
        assertEquals("held", (state.features[0][1] as Array<*>)[0])
    }

    @Test
    fun unknownMagicIsNotOurs() {
        val a = encoded()
        a[0] = "SOME.Other.State"
        assertNull(HotReloadState.decode(a))
    }

    @Test
    fun futureSchemaIsRefusedRatherThanGuessed() {
        val a = encoded()
        a[1] = 99
        assertNull(HotReloadState.decode(a))
    }

    @Test
    fun missingSlotsTakeDefaults() {
        val state = HotReloadState.decode(arrayOf<Any?>(HotReloadState.MAGIC, 1))!!
        assertNull(state.fromBuild)
        assertNull(state.process)
        assertNull(state.hostLoader)
        assertNull(state.context)
        assertEquals(0, state.features.size)
        assertEquals(1, state.reloadCount)
        assertEquals(0, state.fromVersionCode)
    }

    @Test
    fun notAnObjectArrayIsNotOurs() {
        assertNull(HotReloadState.decode(null))
        assertNull(HotReloadState.decode("HMC.HotReloadState"))
        assertNull(HotReloadState.decode(intArrayOf(1, 2)))
    }

    @Test
    fun aModuleClassIsRefusedBeforeTheCommitStep() {
        assertThrows(IllegalArgumentException::class.java) {
            HotReloadState.encode(
                "build", "process", null, null,
                arrayOf(arrayOf<Any>("core", ModuleThing())),
                1,
                1,
            )
        }
    }

    /** Loaded by the same loader as HotReloadState, which is exactly what must not travel. */
    private class ModuleThing
}

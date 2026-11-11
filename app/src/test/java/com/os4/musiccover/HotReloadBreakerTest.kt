package com.os4.musiccover

import com.os4.musiccover.hotreload.HotReload
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The breaker names a build by the version code compiled into the generation that writes the
 * pending marker. That is what makes the block work under autoHotReload, where the extras are
 * null and the old generation has no other way to learn which build is replacing it.
 *
 * A marker that predates the format - or a truncated one - must name no build at all: a wrong
 * version would either block a good build or fail to block a bad one.
 */
class HotReloadBreakerTest {

    @Test
    fun theMarkerNamesTheBuildThatWroteIt() {
        val mark = HotReload.pendingMark(947054L, "?", 1_700_000_000_000L)
        assertEquals("947054@1700000000000@?", mark)
        assertEquals(947054L, HotReload.versionOf(mark))
    }

    @Test
    fun theBuildStringDoesNotConfuseTheVersion() {
        // An extras build mark carrying an '@' must not be read as the version.
        val mark = HotReload.pendingMark(947054L, "947000@abc", 1L)
        assertEquals(947054L, HotReload.versionOf(mark))
    }

    @Test
    fun markersFromOlderBuildsNameNoBuild() {
        assertEquals(-1L, HotReload.versionOf("?@1700000000000"))
        assertEquals(-1L, HotReload.versionOf("947054"))
        assertEquals(-1L, HotReload.versionOf("@123"))
        assertEquals(-1L, HotReload.versionOf(""))
        assertEquals(-1L, HotReload.versionOf(null))
    }
}

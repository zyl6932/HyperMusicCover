package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Test

class AodContentSnapshotTest {
    @Test fun advancingTimeDoesNotChangeAStableStateInAod() {
        val snapshot = AodContentSnapshot<Any, String>()
        val state = Any()
        snapshot.setPaused(true)
        var reads = 0
        assertEquals("00:10", snapshot.value(state) { reads++; "00:10" })
        repeat(5) { assertEquals("00:10", snapshot.value(state) { reads++; "00:20" }) }
        assertEquals(1, reads)
    }

    @Test fun aNewNotificationStateUpdatesImmediatelyWithoutResumingTicks() {
        val snapshot = AodContentSnapshot<Any, String>()
        val running = Any()
        val stopped = Any()
        snapshot.setPaused(true)
        assertEquals("00:10", snapshot.value(running) { "00:10" })
        assertEquals("已暂停", snapshot.value(stopped) { "已暂停" })
        assertEquals("00:10", snapshot.value(running) { "00:20" })
    }

    @Test fun wakingOrTurningOffTheOptionImmediatelyReturnsToLiveContent() {
        val snapshot = AodContentSnapshot<Any, String>()
        val state = Any()
        snapshot.setPaused(true)
        snapshot.value(state) { "00:10" }
        snapshot.setPaused(false)
        assertEquals("00:20", snapshot.value(state) { "00:20" })
        assertEquals("00:21", snapshot.value(state) { "00:21" })
        snapshot.setPaused(true)
        assertEquals("00:22", snapshot.value(state) { "00:22" })
        snapshot.setPaused(true)
        assertEquals("00:22", snapshot.value(state) { "00:23" })
    }
}

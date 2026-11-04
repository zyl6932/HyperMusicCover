package com.os4.musiccover.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An unsynced lyric dressed as LRC - every line the same stamp - is not a lyric to hold a timed
 * one back for. The first case is the payload Apple's static lyric was published as (2026-11-04).
 */
class UntimedLyricTest {

    @Test
    fun everyLineAtZeroIsOneStamp() {
        val text = "[00:00.000]和你在一起时身体会升温\n[00:00.000]f d a u气温太高被你蒸发\n" +
            "[00:00.000]努力在失控前给自己降温\n[00:00.000]背对背流着泪往下坠\n"
        assertTrue(LyricSource.oneStamp(text))
    }

    @Test
    fun aTimedLyricIsNot() {
        val text = "[00:12.300]第一句\n[00:15.800]第二句\n[00:19.010]第三句\n"
        assertFalse(LyricSource.oneStamp(text))
    }

    @Test
    fun wordStampsDoNotCount() {
        // Word stamps are <..>; the line stamps here differ, so this is a timed lyric.
        val text = "[00:01.000]<00:01.000>a<00:01.500>b\n[00:03.000]<00:03.000>c\n"
        assertFalse(LyricSource.oneStamp(text))
    }

    @Test
    fun oneLineIsNotEnoughToSay() {
        assertFalse(LyricSource.oneStamp("[00:00.000]只有一行\n"))
    }
}

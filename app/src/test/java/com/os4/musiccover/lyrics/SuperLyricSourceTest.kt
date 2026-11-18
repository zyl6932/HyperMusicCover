package com.os4.musiccover.lyrics

import com.hchen.superlyricapi.LyricView
import com.hchen.superlyricapi.model.Line
import com.hchen.superlyricapi.model.Lyric
import com.hchen.superlyricapi.model.PlaybackState
import com.hchen.superlyricapi.model.Word
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SuperLyric half of the bridge pair: how a pushed LyricView becomes this module's lines
 * (convert), and when it is allowed to (isTrack).
 */
class SuperLyricSourceTest {

    private fun word(text: String, start: Long, end: Long) = Word(text, start, end)

    private fun line(
        text: String, start: Long, end: Long,
        words: List<Word>? = null, translation: String? = null, phonetic: String? = null
    ) = Line(text, start, end, words, translation, phonetic)

    private fun lyric(id: String, title: String, artist: String, vararg lines: Line) =
        Lyric(id, title, artist, "", 0L, lines.toList())

    private fun view(lyric: Lyric, publisher: String?, index: Int = 0) =
        LyricView(lyric, index, 0L, 1f, PlaybackState.PLAYING, publisher)

    private fun identity(pkg: String, title: String, artist: String) =
        TrackIdentity.of(pkg, title, artist, "", 0L, null)

    // ---- convert ----

    @Test
    fun wordsGiveALineWordTimes() {
        val out = requireNotNull(SuperLyricSource.convert(
            line("hello world", 1000, 3000, listOf(
                word("hello", 1000, 2000), word(" world", 2000, 3000))), 3000))
        assertEquals("hello world", out.text)
        assertTrue(out.hasWords())
        assertArrayEquals(intArrayOf(1000, 2000), out.sylStart)
        assertEquals("hello world".length, out.charEnd.last())
    }

    @Test
    fun wordsThatDoNotMatchThePublishedTextStillTime() {
        // The published text carries decorations the words never did; the drawn string is the
        // words' own, as on the bridge's lead line.
        val out = requireNotNull(SuperLyricSource.convert(
            line("[vocal] hello world", 1000, 3000, listOf(
                word("hello", 1000, 2000), word(" world", 2000, 3000))), 3000))
        assertEquals("hello world", out.text)
        assertTrue(out.hasWords())
        assertArrayEquals(intArrayOf(1000, 2000), out.sylStart)
    }

    @Test
    fun timesPastAnIntFallBackToLineTiming() {
        // A time past an Int would wrap negative and send the fill backwards: line-timed.
        val out = requireNotNull(SuperLyricSource.convert(
            line("something else entirely", 1000, 3000, listOf(
                word("hello", 1000, Long.MAX_VALUE), word("world", 2000, 3000))), 3000))
        assertEquals("something else entirely", out.text)
        assertFalse(out.hasWords())
    }

    @Test
    fun aLineWithNoWordsStaysLineTimed() {
        val out = requireNotNull(SuperLyricSource.convert(
            line("no words here", 500, 2500), 2500))
        assertEquals("no words here", out.text)
        assertFalse(out.hasWords())
        assertEquals(500, out.start)
        assertEquals(2500, out.end)
    }

    @Test
    fun translationAndPhoneticAreHungOnTheLine() {
        val out = requireNotNull(SuperLyricSource.convert(
            line("test", 1000, 2000, translation = "测试", phonetic = "tesuto"), 2000))
        assertEquals("测试", out.translation)
        assertEquals("tesuto", out.roma)
    }

    // ---- isTrack ----

    @Test
    fun publisherMismatchNeverPlaces() {
        val v = view(lyric("1", "Song", "Artist", line("x", 0, 1000)), "pkg.a")
        assertFalse(SuperLyricSource.isTrack(v, identity("pkg.b", "Song", "Artist"), "pkg.b"))
    }

    @Test
    fun titleMismatchNeverPlaces() {
        // A lyric on its way out from under the previous song must not answer for this one.
        val v = view(lyric("1", "Old Song", "Artist", line("x", 0, 1000)), "pkg.a")
        assertFalse(SuperLyricSource.isTrack(v, identity("pkg.a", "New Song", "Artist"), "pkg.a"))
    }

    @Test
    fun samePublisherSameTitlePlaces() {
        val v = view(lyric("1", "Song", "Artist", line("x", 0, 1000)), "pkg.a")
        assertTrue(SuperLyricSource.isTrack(v, identity("pkg.a", "Song", "Artist"), "pkg.a"))
    }

    @Test
    fun blankTitleFallsBackToPublisherOnly() {
        // A lyric that names no title can only place itself by who pushed it.
        val v = view(lyric("1", "", "", line("x", 0, 1000)), "pkg.a")
        assertTrue(SuperLyricSource.isTrack(v, identity("pkg.a", "Song", "Artist"), "pkg.a"))
        assertFalse(SuperLyricSource.isTrack(v, identity("pkg.b", "Song", "Artist"), "pkg.b"))
    }
}

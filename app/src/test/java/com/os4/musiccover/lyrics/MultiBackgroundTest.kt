package com.os4.musiccover.lyrics

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Several background vocals on one line, and the word maps the renderer can be handed (PR #106). */
class MultiBackgroundTest {
    private fun fixture() =
        javaClass.classLoader!!.getResource("lyrics/overlapping-backgrounds.ttml")!!.readText()

    @Test
    fun everyBackgroundOfATtmlLineIsKept() {
        val lines = LyricParse.parse(fixture())
        assertEquals(listOf("Lead tail", "Overlap", "Final"), lines.map { it.text })
        val lead = lines.first()
        assertEquals(5000, lead.sylEnd.last())
        assertEquals(listOf("echo one", "echo two"), lead.backgrounds().map { it.text })
        assertEquals(listOf(1500, 2000), lead.backgrounds().map { it.start })
        assertEquals(listOf(6000, 7000), lead.backgrounds().map { it.end })
        // Lit until the last of them is sung, alongside the line that starts under it.
        assertEquals(7000, lead.end)
        val focus = LyricFocus()
        focus.update(lines, 3500, 3, 1)
        assertTrue(focus.contains(0))
        assertTrue(focus.contains(1))
    }

    @Test
    fun aTtmlLineOfOnlyABackgroundVocalIsThatVocal() {
        val lines = LyricParse.parse("""<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>""" +
            """<p begin="00:01.000" end="00:02.000"><span begin="00:01.000" end="00:02.000">Lead</span></p>""" +
            """<p begin="00:03.000" end="00:05.000"><span ttm:role="x-bg" begin="00:03.000" end="00:05.000">""" +
            """<span begin="00:03.000" end="00:04.000">(ooh </span><span begin="00:04.000" end="00:05.000">ah)</span></span></p>""" +
            """</div></body></tt>""")
        assertEquals(listOf("Lead", "ooh ah"), lines.map { it.text })
        val echo = lines[1]
        assertTrue(echo.hasWords())
        assertEquals(3000, echo.start)
        assertEquals(0, echo.backgrounds().size)
    }

    @Test
    fun aBridgeLineOfOnlyABackgroundVocalIsThatVocal() {
        val source = RichLyricLine(begin = 1000, end = 2000, text = "",
            secondary = "(echo)", secondaryWords = listOf(LyricWord(text = "echo", begin = 1500, end = 4000)))
        val line = LyriconSource.convert(source, 3000)!!
        assertEquals("echo", line.text)
        assertTrue(line.hasWords())
        assertEquals(1500, line.start)
        assertEquals(4000, line.end)
        assertEquals(0, line.backgrounds().size)
        assertNull(LyriconSource.convert(RichLyricLine(begin = 1000, end = 2000, text = ""), 3000))
    }

    @Test
    fun characterRangesPastTheTextLeaveTheLineLineTimed() {
        val line = LyricLine("keep", null, 1000, 5000, false,
            intArrayOf(1000, 2000), intArrayOf(2000, 3000), intArrayOf(1, 99))
        assertEquals("keep", line.text)
        assertFalse(line.hasWords())
        assertNull(line.charEnd)
        assertEquals(4f, line.sungChars(1500), 0f)
        val backwards = LyricLine("ab", null, 1000, 3000, false,
            intArrayOf(1000, 2000), intArrayOf(2000, 3000), intArrayOf(2, 1))
        assertFalse(backwards.hasWords())
    }

    @Test
    fun wordTimesOutOfOrderStayWordTimed() {
        val line = LyricLine("ab", null, 1500, 2500, false,
            intArrayOf(1000, 900), intArrayOf(5000, 3000), intArrayOf(1, 2))
        assertTrue(line.hasWords())
        // Starts with its first word, and runs to the latest word end, not the last one's.
        assertEquals(1000, line.start)
        assertEquals(5000, line.end)
        assertArrayEquals(intArrayOf(1000, 900), line.sylStart)
    }

    @Test
    fun theLastIntHasNothingAfterIt() {
        assertEquals(50, LyricParse.nextAfter(intArrayOf(10, Int.MAX_VALUE), Int.MAX_VALUE, 50))
    }
}

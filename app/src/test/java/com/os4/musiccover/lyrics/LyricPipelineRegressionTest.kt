package com.os4.musiccover.lyrics

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.junit.Assert.*
import org.junit.Test

class LyricPipelineRegressionTest {
    private fun fixture() = javaClass.getResource("/lyrics/overlapping-backgrounds.ttml")!!.readText()

    @Test fun ttmlKeepsEveryBackgroundAndDoesNotCutTailAtNextLead() {
        val lines = LyricParse.parse(fixture())
        assertEquals(3, lines.size)
        val lead = lines.first()
        assertEquals("Lead tail", lead.text)
        assertEquals(5000, lead.sylEnd.last())
        assertEquals(2, lead.backgrounds().size)
        assertEquals(listOf("echo one", "echo two"), lead.backgrounds().map { it.text })
        assertEquals(listOf(1500, 2000), lead.backgrounds().map { it.start })
        assertEquals(listOf(6000, 7000), lead.backgrounds().map { it.end })
        assertEquals(7000, lead.end)
        val focus = LyricFocus()
        focus.update(lines, 3500, 3, 1)
        assertTrue(focus.contains(0))
        assertTrue(focus.contains(1))
        assertEquals(8000, lines.last().start)
    }

    @Test fun bridgeKeepsIndependentMainAndSecondaryWordData() {
        val source = RichLyricLine(begin = 1000, end = 2000, text = "主唱",
            words = listOf(LyricWord(text = "主唱", begin = 1000, end = 2500)),
            secondary = "(和声)和声",
            secondaryWords = listOf(LyricWord(text = "和声", begin = 1500, end = 4000)))
        val line = LyriconConverter.convert(source, 3000)!!
        assertEquals("主唱", line.text)
        assertEquals("和声", line.bg.text)
        assertEquals(2500, line.sylEnd.last())
        assertEquals(4000, line.bg.sylEnd.last())
        assertEquals(4000, line.end)
        assertEquals("(和声)和声", source.secondary)
    }

    @Test fun secondaryOnlyLineKeepsItsIndependentTiming() {
        val source = RichLyricLine(begin = 1000, end = 2000, text = "",
            secondaryWords = listOf(LyricWord(text = "echo", begin = 1500, end = 4000)))
        val line = LyriconConverter.convert(source, 3000)!!
        assertEquals("", line.text)
        assertEquals("echo", line.backgrounds().single().text)
        assertEquals(4000, line.end)
    }

    @Test fun mainWordsOutsideDeclaredBoundsAreNotTruncatedOrAliased() {
        val starts = intArrayOf(1000, 2000)
        val ends = intArrayOf(5000, 3000)
        val chars = intArrayOf(1, 2)
        val line = LyricLine("ab", null, 1500, 2500, false, starts, ends, chars)
        starts[0] = 9999
        ends[0] = 0
        chars[1] = 99
        assertEquals(1000, line.start)
        assertEquals(5000, line.end)
        assertArrayEquals(intArrayOf(1000, 2000), line.sylStart)
        assertArrayEquals(intArrayOf(1, 2), line.charEnd)
    }

    @Test fun badWordBoundsCannotEnterRendererAndDoNotEraseText() {
        val line = LyricLine("keep", null, -1, 5000, false,
            intArrayOf(1000, 900), intArrayOf(2000, 3000), intArrayOf(1, 99))
        assertEquals("keep", line.text)
        assertFalse(line.hasWords())
        assertEquals(0, line.start)
        assertEquals(4f, line.sungChars(1500), 0f)
        val valid = LyricLine("ab", null, 1000, 2000, false,
            intArrayOf(1000, 1500), intArrayOf(5000, 2000), intArrayOf(1, 2))
        assertEquals(5000, valid.end)
    }

    @Test fun ordinaryLrcAndAbnormalInputStaySafe() {
        val lines = LyricParse.parse("[00:01.00]First\n[00:02.00]\n[00:03.00]Last")
        assertEquals(listOf("First", "Last"), lines.map { it.text })
        assertEquals(listOf(1000, 3000), lines.map { it.start })
        assertTrue(lines.all { !it.hasWords() && it.backgrounds().isEmpty() })
        assertTrue(LyricParse.parse("<tt><body>broken").isEmpty())
        assertEquals(50, LyricParse.nextAfter(intArrayOf(10, Int.MAX_VALUE), Int.MAX_VALUE, 50))
    }
}

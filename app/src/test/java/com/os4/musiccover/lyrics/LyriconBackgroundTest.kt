package com.os4.musiccover.lyrics

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LyriconBackgroundTest {
    private fun word(text: String, begin: Long, end: Long) =
        LyricWord(text = text, begin = begin, end = end)

    private fun line(text: String?, vararg words: LyricWord) = RichLyricLine(
        begin = 1000, end = 2000, text = "主唱", secondary = text,
        secondaryWords = words.toList())

    private fun convert(line: RichLyricLine): LyricLine =
        requireNotNull(LyriconBackground.convert(line, 6000))

    private fun assertTimed(background: LyricLine, text: String, starts: IntArray, ends: IntArray) {
        assertEquals(text, background.text)
        assertTrue(background.hasWords())
        assertArrayEquals(starts, background.sylStart)
        assertArrayEquals(ends, background.sylEnd)
        assertEquals(text.length, background.charEnd.last())
        assertTrue(background.charEnd.toList().zipWithNext().all { (first, second) -> first < second })
        assertEquals(0f, background.sungChars(starts.first() - 1), 0f)
        assertEquals(text.length.toFloat(), background.sungChars(ends.last()), 0f)
    }

    @Test
    fun parenthesesInFullTextDoNotDiscardWordTimes() {
        val source = line("(为什么 还相信)", word("为什么", 1200, 1500), word("还相信", 1500, 2400))
        val background = convert(source)
        assertTimed(background, "为什么 还相信", intArrayOf(1200, 1500), intArrayOf(1500, 2400))
        assertEquals("(为什么 还相信)", source.secondary)
        assertEquals(1200, background.start)
        assertEquals(2400, background.end)
    }

    @Test
    fun parenthesesOnTimedWordsAreHiddenWithoutShiftingTime() {
        val background = convert(line("(为什么 还相信)",
            word("(为什么 ", 1200, 1500), word("还相信)", 1500, 2400)))
        assertTimed(background, "为什么 还相信", intArrayOf(1200, 1500), intArrayOf(1500, 2400))
    }

    @Test
    fun separateBracketTokensDoNotBecomeSyllables() {
        val background = convert(line("（ 啊 ）", word("（", 1000, 1100),
            word(" 啊 ", 1200, 2400), word("）", 2500, 2600)))
        assertTimed(background, "啊", intArrayOf(1200), intArrayOf(2400))
    }

    @Test
    fun duplicatedFlamingoTextUsesTimedWordsOnce() {
        val background = convert(line("(为什么 还相信)为什么 还相信",
            word("为什么 ", 1200, 1500), word("还相信", 1500, 2400)))
        assertTimed(background, "为什么 还相信", intArrayOf(1200, 1500), intArrayOf(1500, 2400))
    }

    @Test
    fun missingFullTextStillUsesSecondaryWords() {
        val background = convert(line(null, word("(啊)", 900, 3200)))
        assertTimed(background, "啊", intArrayOf(900), intArrayOf(3200))
    }

    @Test
    fun englishSpacesArePreservedWhenWordTokensOmitThem() {
        val background = convert(line("(Fifty, fifty feet up underground)",
            word("Fifty,", 1200, 1400), word("fifty", 1400, 1600),
            word("feet", 1600, 1800), word("up", 1800, 2000), word("underground", 2000, 3200)))
        assertEquals("Fifty, fifty feet up underground", background.text)
        assertTrue(background.hasWords())
        assertEquals(background.text.length, background.charEnd.last())
    }

    @Test
    fun legitimateRepetitionsAndInternalParenthesesRemain() {
        val background = convert(line("(Oh (echo) Oh)",
            word("Oh ", 1200, 1500), word("(echo) ", 1500, 1900), word("Oh", 1900, 2400)))
        assertTimed(background, "Oh (echo) Oh", intArrayOf(1200, 1500, 1900), intArrayOf(1500, 1900, 2400))
        val separate = convert(line("(Oh) (Oh)", word("(Oh) ", 1200, 1500), word("(Oh)", 1500, 2400)))
        assertEquals("(Oh) (Oh)", separate.text)
    }

    @Test
    fun unbalancedPunctuationIsNotBlindlyRemoved() {
        assertEquals("(Oh", convert(line("(Oh", word("(Oh", 1200, 2400))).text)
    }

    @Test
    fun plainBackgroundStaysSafeAndHidesOuterBrackets() {
        val background = convert(line("(和声)"))
        assertEquals("和声", background.text)
        assertFalse(background.hasWords())
        assertNull(background.sylStart)
        assertNull(LyriconBackground.convert(line("()"), 6000))
    }

    @Test
    fun invalidTimestampsAreNotNarrowedIntoNegativeIntegers() {
        val background = convert(line("(和声)", word("和声", Int.MAX_VALUE.toLong() + 1, 9000000000)))
        assertEquals("和声", background.text)
        assertFalse(background.hasWords())
    }

    @Test
    fun missingWordEndUsesTheExistingTailPolicy() {
        val background = convert(line("(啊)", word("啊", 1200, 0)))
        assertTimed(background, "啊", intArrayOf(1200), intArrayOf(2000))
    }

    @Test
    fun ownerRemainsActiveUntilBackgroundFinishes() {
        val source = line("(和声)", word("和声", 2200, 4200))
        val owner = LyricLine("主唱", null, 1000, 2000, false, null, null, null)
        LyriconBackground.attach(owner, source, 6000)
        assertEquals(4200, owner.end)
        assertEquals(2200, owner.bg.start)
        assertTrue(owner.bg.hasWords())
        assertFalse(owner.hasWords())
    }

    @Test
    fun realProtocolModelPreservesSecondaryWordsThroughSerialization() {
        val source = line("(和声)", word("和", 1200, 1800), word("声", 1800, 2400))
        val encoded = Json.encodeToString(RichLyricLine.serializer(), source)
        val received = Json.decodeFromString(RichLyricLine.serializer(), encoded)
        assertTimed(convert(received), "和声", intArrayOf(1200, 1800), intArrayOf(1800, 2400))
    }
}

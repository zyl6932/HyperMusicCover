package com.os4.musiccover.lyrics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Keep the v0.9.45 inline-translation fix when selecting sources in priority order. */
class InlineTranslationsTest {
    @Test
    fun inlineTranslationKeepsBackingVocalsAndClipsWordCharacters() {
        val text = "Hello\u2009你好"
        val line = LyricLine(text, null, 1000, 4000, false,
            intArrayOf(1000, 2000), intArrayOf(2000, 4000), intArrayOf(5, text.length))
        val backing = LyricLine("World", null, 1500, 4500, false, null, null, null)
        line.addBackground(backing)
        val result = LyricParse.splitInlineTranslations(listOf(line)).single()
        assertEquals("Hello", result.text)
        assertEquals("你好", result.translation)
        assertArrayEquals(intArrayOf(5), result.charEnd)
        assertArrayEquals(intArrayOf(1000), result.sylStart)
        assertSame(backing, result.backgrounds().single())
    }

    @Test
    fun multipleInlineTranslationsStayUnderTheOriginal() {
        val line = LyricLine("Hello\u202f你好\u2009您好", "原有译文",
            1000, 4000, false, null, null, null)
        val result = LyricParse.splitInlineTranslations(listOf(line)).single()
        assertEquals("Hello", result.text)
        assertEquals("原有译文\n你好\n您好", result.translation)
    }

    @Test
    fun typographicSpacesInLatinLyricsAreNotTranslations() {
        val line = LyricLine("Hello\u2009world", null, 1000, 4000, false, null, null, null)
        val lines = listOf(line)
        assertSame(lines, LyricParse.splitInlineTranslations(lines))
    }
}

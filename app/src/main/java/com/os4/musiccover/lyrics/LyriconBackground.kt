package com.os4.musiccover.lyrics

import io.github.proify.lyricon.lyric.model.RichLyricLine

internal object LyriconBackground {
    fun attach(owner: LyricLine, line: RichLyricLine, nextStart: Int) {
        val background = convert(line, nextStart) ?: return
        owner.addBackground(background)
    }

    fun convert(line: RichLyricLine, nextStart: Int): LyricLine? {
        val plain = visible(line.secondary.orEmpty())
        val words = line.secondaryWords.orEmpty().filter { !it.text.isNullOrEmpty() }
        val joined = words.joinToString("") { it.text.orEmpty() }
        val range = visibleRange(joined)
        val tokens = ArrayList<String>()
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        var offset = 0
        var valid = true
        for (word in words) {
            val text = word.text.orEmpty()
            val from = maxOf(offset, range.first)
            val until = minOf(offset + text.length, range.second)
            if (from < until) {
                val token = joined.substring(from, until)
                if (token.isNotBlank()) {
                    if (word.begin !in 0L..Int.MAX_VALUE.toLong()
                        || word.end !in 0L..Int.MAX_VALUE.toLong()
                        || (starts.isNotEmpty() && word.begin < starts.last())) {
                        valid = false
                        break
                    }
                    tokens.add(token)
                    starts.add(word.begin.toInt())
                    ends.add(maxOf(word.begin, word.end).toInt())
                }
            }
            offset += text.length
        }
        if (valid && tokens.isNotEmpty()) {
            val timedText = joined.substring(range.first, range.second)
            val mapped = mapWords(plain, tokens)
            val text = if (mapped != null) plain else timedText
            val chars = mapped ?: mapWords(timedText, tokens)!!
            val startArray = starts.toIntArray()
            val endArray = ends.toIntArray()
            val fallbackEnd = line.end.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            LyricParse.closeUntimedTail(startArray, endArray, fallbackEnd, nextStart)
            return LyricLine(text, null, startArray.first(), endArray.maxOrNull()!!,
                line.isAlignedRight, startArray, endArray, chars)
        }
        val text = plain.ifEmpty { visible(joined) }
        if (text.isEmpty()) return null
        return LyricLine(text, null, line.begin.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            line.end.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            line.isAlignedRight, null, null, null)
    }

    private fun mapWords(text: String, tokens: List<String>): IntArray? {
        val chars = IntArray(tokens.size)
        var at = 0
        for ((index, token) in tokens.withIndex()) {
            val trimmed = token.trim()
            val found = text.indexOf(trimmed, at)
            if (found < 0 || text.substring(at, found).isNotBlank()) return null
            at = found + trimmed.length
            chars[index] = at
        }
        if (text.substring(at).isNotBlank()) return null
        chars[chars.lastIndex] = text.length
        return chars
    }

    private fun visible(text: String): String {
        val range = visibleRange(text)
        return text.substring(range.first, range.second)
    }

    private fun visibleRange(text: String): Pair<Int, Int> {
        var from = 0
        var until = text.length
        while (from < until && text[from].isWhitespace()) from++
        while (until > from && text[until - 1].isWhitespace()) until--
        if (until - from >= 2) {
            val open = text[from]
            val close = when (open) {
                '(' -> ')'
                '（' -> '）'
                else -> '\u0000'
            }
            if (close != '\u0000' && text[until - 1] == close) {
                var depth = 0
                var encloses = true
                for (index in from until until) {
                    if (text[index] == open) depth++
                    if (text[index] == close) depth--
                    if (depth == 0 && index < until - 1) {
                        encloses = false
                        break
                    }
                }
                if (encloses && depth == 0) {
                    from++
                    until--
                    while (from < until && text[from].isWhitespace()) from++
                    while (until > from && text[until - 1].isWhitespace()) until--
                }
            }
        }
        return Pair(from, until)
    }
}

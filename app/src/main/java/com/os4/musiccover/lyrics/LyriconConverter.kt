package com.os4.musiccover.lyrics

import io.github.proify.lyricon.lyric.model.RichLyricLine

/** Android-independent conversion of the main and secondary tracks received over Lyricon. */
internal object LyriconConverter {
    fun convert(line: RichLyricLine, nextStart: Int): LyricLine? {
        val text = line.text?.trim().orEmpty()
        val words = line.words?.takeIf { words -> words.all {
            it.begin in 0L..Int.MAX_VALUE.toLong() && it.end in 0L..Int.MAX_VALUE.toLong()
        } }
        var built: LyricLine? = null
        if (!words.isNullOrEmpty()) {
            val sb = StringBuilder()
            val starts = ArrayList<Int>(words.size)
            val ends = ArrayList<Int>(words.size)
            val chars = ArrayList<Int>(words.size)
            for (w in words) {
                val t = w.text ?: continue
                if (t.isEmpty()) continue
                sb.append(t)
                starts.add(w.begin.toInt())
                // A word with no end of its own is left to the tail-closing pass below, which is
                // the same thing every other word-timed source here needs.
                ends.add(if (w.end > w.begin) w.end.toInt() else w.begin.toInt())
                chars.add(sb.length)
            }
            var n = sb.length
            while (n > 0 && sb[n - 1].isWhitespace()) n--
            if (n > 0 && starts.isNotEmpty()) {
                for (k in chars.indices) if (chars[k] > n) chars[k] = n
                val s = starts.toIntArray()
                val e = ends.toIntArray()
                LyricParse.closeUntimedTail(s, e, line.end.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), nextStart)
                built = LyricLine(sb.substring(0, n), line.translation,
                    LyricParse.romaOf(line.roma, sb.substring(0, n)), line.begin.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    line.end.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), line.isAlignedRight, s, e, chars.toIntArray())
            }
        }
        if (built == null) {
            built = LyricLine(text, line.translation, LyricParse.romaOf(line.roma, text),
                line.begin.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), line.end.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), line.isAlignedRight, null, null, null)
        }
        // The background vocal, which the renderer hangs under the line rather than beside it.
        LyriconBackground.attach(built, line, nextStart)
        return built.takeIf { it.text.isNotEmpty() || it.backgrounds().isNotEmpty() }
    }

}

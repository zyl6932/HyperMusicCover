package com.os4.musiccover

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser

/**
 * Whatever a lyric source handed us, turned into lines the renderer can draw.
 *
 * The formats are not ours to choose - the AMLL database alone ships TTML, LRC, YRC, QRC and
 * Lyricify for the same song - so AutoParser sniffs the format and this is the only place that
 * knows there was ever more than one. Kotlin only because AutoParser's constructor is all default
 * arguments, which Java cannot call.
 */
object LyricParse {

    /**
     * The same thing for a source that ships its translation separately.
     *
     * NetEase does: the timed lyric comes back under "yrc" (or "lrc") and the translated lines
     * under "tlyric", as a second plain LRC with no link to the first beyond its timestamps. So
     * the two are joined here by time rather than by index - the counts do not match, because
     * the translation has no entries for the instrumental breaks or the credits, and pairing
     * them off in order would slide every translation one line up partway through the song.
     */
    @JvmStatic
    fun parse(body: String, translation: String?): List<LyricLine> {
        val lines = parse(body)
        if (translation.isNullOrBlank() || lines.isEmpty()) return lines
        val tr = lrc(translation)
        if (tr.isEmpty()) return lines
        val texts = assign(lines, tr)
        val out = ArrayList<LyricLine>(lines.size)
        for ((i, line) in lines.withIndex()) {
            val text = texts[i]
            out.add(if (text == null) line else withTranslation(line, text))
        }
        return out
    }

    /**
     * The same again with a romanisation beside the translation - QQ Music, NetEase and Kuwo
     * ship one for Japanese and Korean songs, as a third timed text joined by time like the
     * translation. It is shown in the translation's place, above the translation when there is
     * both, and goes with the translation switch.
     */
    @JvmStatic
    fun parse(body: String, translation: String?, roma: String?): List<LyricLine> {
        val lines = parse(body, translation)
        if (roma.isNullOrBlank() || lines.isEmpty()) return lines
        val ro = lrc(roma)
        if (ro.isEmpty()) return lines
        val best = assign(lines, ro)
        val out = ArrayList<LyricLine>(lines.size)
        for ((i, line) in lines.withIndex()) {
            val r = best[i]
            val merged = if (r == null) null else withRoma(r, line.text, line.translation)
            out.add(if (merged == null || merged == line.translation) line
                else withTranslation(line, merged))
        }
        return out
    }

    /**
     * Each timed entry of a translation or romanisation, given to the ONE line nearest it - not
     * each line taking the nearest entry. A catalogue leaves its credit lines untranslated, and
     * asked the other way round the credits 500ms before the first verse took the verse's
     * translation as their own (QQ's Lemon, 2026-09-24). One entry, one line; the closer of two
     * claimants keeps it. See TRANSLATION_WINDOW_MS for the window.
     */
    private fun assign(lines: List<LyricLine>, entries: List<Pair<Int, String>>): Array<String?> {
        val starts = IntArray(lines.size) { lines[it].start }
        val best = arrayOfNulls<String>(lines.size)
        val gaps = IntArray(lines.size) { Int.MAX_VALUE }
        for ((at, text) in entries) {
            var i = starts.binarySearch(at)
            if (i < 0) {
                val ins = -i - 1
                i = when {
                    ins == 0 -> 0
                    ins >= starts.size -> starts.size - 1
                    at - starts[ins - 1] <= starts[ins] - at -> ins - 1
                    else -> ins
                }
            }
            val gap = kotlin.math.abs(starts[i] - at)
            if (gap <= TRANSLATION_WINDOW_MS && gap < gaps[i]) {
                gaps[i] = gap
                best[i] = text
            }
        }
        return best
    }

    /**
     * The romanisation over the translation, as the one text the renderer draws under a line.
     * Left out when it only repeats the line - a catalogue "romanises" an English song into
     * itself.
     */
    internal fun withRoma(roma: String?, text: String, translation: String?): String? {
        val r = roma?.trim()?.replace(Regex("\\s+"), " ")
        if (r.isNullOrEmpty() || letters(r) == letters(text)) return translation
        return if (translation.isNullOrBlank()) r else r + "\n" + translation.trim()
    }

    private fun letters(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * How far a translated line's time may sit from its line's and still be its translation.
     *
     * A window rather than an equality test: yrc and tlyric are timed independently, and the
     * same line measured here starts at 4390ms in the word-timed copy and 4705ms in the LRC -
     * close enough to be obviously the same line, far enough apart that matching exactly would
     * find nothing at all. The window is wide enough for that drift and narrower than the gap
     * between two sung lines, so the nearest line inside it is the right one.
     */
    private const val TRANSLATION_WINDOW_MS = 1500

    /** Timestamped lines of a plain LRC, in time order, with the empty ones left out. */
    private fun lrc(body: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        for (raw in body.split('\n')) {
            val m = LRC_TIME.find(raw) ?: continue
            val text = raw.substring(m.range.last + 1).trim()
            if (text.isEmpty()) continue
            val min = m.groupValues[1].toIntOrNull() ?: continue
            val sec = m.groupValues[2].toIntOrNull() ?: continue
            val frac = m.groupValues[3]
            // Two digits are hundredths, three are milliseconds.
            val ms = when (frac.length) {
                0 -> 0
                3 -> frac.toIntOrNull() ?: 0
                else -> (frac.toIntOrNull() ?: 0) * 10
            }
            out.add(Pair(min * 60000 + sec * 1000 + ms, text))
        }
        out.sortBy { it.first }
        return out
    }

    private val LRC_TIME = Regex("^\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    /** The same line carrying a translation it did not come with. */
    private fun withTranslation(line: LyricLine, text: String): LyricLine {
        val copy = LyricLine(line.text, text, line.start, line.end, line.opposite,
            line.sylStart, line.sylEnd, line.charEnd)
        copy.bg = line.bg
        return copy
    }

    @JvmStatic
    fun parse(body: String): List<LyricLine> {
        val lyrics = AutoParser().parse(body)
        val src = lyrics.lines
        val out = ArrayList<LyricLine>(src.size)
        // Where the tail of a line may run to when the file left it without an end of its own:
        // the arrival of the line after it. Looked up by time and not read off the list: the list
        // is sorted only at the end of this function, and a background vocal handed back as a
        // line of its own starts inside the line it echoes - taken as the next line, it would cut
        // the last word down to the few milliseconds before the echo.
        val mains = src.filter { it !is KaraokeLine.AccompanimentKaraokeLine }
            .map { it.start }.sorted().toIntArray()
        for (line in src) {
            val nextStart = nextAfter(mains, line.start, line.end)
            when (line) {
                // Background vocals overlap the main line in time, so they are not lines of their
                // own - the renderer finds the singing line by start time, and one would steal
                // the focus for the length of an echo. They hang under the main line they belong
                // to (the last one started by then), and stretch it if they outlast it.
                is KaraokeLine.AccompanimentKaraokeLine -> {
                    val b = karaoke(line, nextStart) ?: continue
                    val owner = out.lastOrNull { it.start <= b.start } ?: continue
                    if (owner.bg == null) {
                        owner.bg = b
                        if (b.end > owner.end) owner.end = b.end
                    }
                }
                is KaraokeLine -> {
                    val main = karaoke(line, nextStart)
                    if (main != null) {
                        out.add(main)
                        // Where the accompaniment actually arrives. The branch above is written
                        // for a parser that hands background vocals back as lines of their own,
                        // and lyrics-core 0.4.7 does not: it hangs them on the main line as a
                        // property, so that branch never fires and every background vocal was
                        // being dropped. Both are kept - which shape comes back is the library's
                        // business, and a version that goes back to separate lines still works.
                        val acc = (line as? KaraokeLine.MainKaraokeLine)
                            ?.accompanimentLines?.firstOrNull()
                        val b = acc?.let { karaoke(it, nextStart) }
                        if (b != null) {
                            main.bg = b
                            if (b.end > main.end) main.end = b.end
                        }
                    }
                }
                is SyncedLine -> {
                    // Instrumental breaks arrive as empty lines; a row of nothing would take a
                    // slot in the stack for its whole duration.
                    if (line.content.isBlank()) continue
                    out.add(LyricLine(line.content.trim(), line.translation,
                        line.start, line.end, false, null, null, null))
                }
            }
        }
        out.sortBy { it.start }
        return speakers(out)
    }

    /**
     * The first of the sorted starts that is later than t: when the line starting at t is followed
     * by another. The last line of a song has nothing after it, and gets the fallback.
     */
    internal fun nextAfter(sorted: IntArray, t: Int, fallback: Int): Int {
        val i = sorted.binarySearch(t + 1)
        val at = if (i >= 0) i else -i - 1
        return if (at < sorted.size) sorted[at] else fallback
    }

    private fun karaoke(line: KaraokeLine, nextStart: Int): LyricLine? {
        val syl = line.syllables
        if (syl.isEmpty()) return null
        val text = StringBuilder()
        val starts = IntArray(syl.size)
        val ends = IntArray(syl.size)
        val chars = IntArray(syl.size)
        for ((k, s) in syl.withIndex()) {
            text.append(s.content)
            starts[k] = s.start
            ends[k] = s.end
            chars[k] = text.length
        }
        // Trailing spaces belong to the last word in English files and would push a wrapped
        // line's measured width past its ink. Leading ones cannot be trimmed without shifting
        // every syllable's character range, and the files do not have them.
        var n = text.length
        while (n > 0 && text[n - 1].isWhitespace()) n--
        if (n == 0) return null
        for (k in chars.indices) if (chars[k] > n) chars[k] = n
        closeUntimedTail(starts, ends, line.end, nextStart)
        // The file's own romanisation - TTML's x-roman, a KRC's language block - joins the
        // translation the same way a separately shipped one does.
        val shown = text.substring(0, n)
        return LyricLine(shown, withRoma(line.phonetic, shown, line.translation), line.start,
            line.end, line.alignment == KaraokeAlignment.End, starts, ends, chars)
    }

    /**
     * The words at the end of a line that the source left without a span of their own take the
     * room it was leaving them.
     *
     * Word timing routinely runs out before the line does. A file that times a line by its words
     * alone has nowhere to read the last word's end from, and one that carries a duration per
     * word has nothing to say for a note the singer holds on: either way the last word arrives
     * with its end missing, or set equal to its own start. That is invisible anywhere else in a
     * line - sungChars() walks the syllables looking for the one the moment falls in, and a word
     * nobody is inside is simply stepped over - but at the end of a line there is no next word to
     * hand the fill on to, so the walk runs off the end of the array and reports the whole line
     * sung. The fill then crosses the last word in a single frame instead of sweeping it, and the
     * word is never the second long that the glow asks for.
     *
     * A word left without a span runs to the end of the line, and to the arrival of the line after
     * it when the file has no end to offer - which is the case for every file that times a line by
     * its words and nothing else. Not every such wait is a note, though: a singer will hold a word
     * through the last bar of a chorus but nobody holds one across an interlude, and the renderer
     * answers the second kind with its interlude dots (`LyricView.LULL_MS`, the same four seconds),
     * so past that the word keeps the nominal span and leaves the rest of the wait to them. Words
     * that share a start - which is what a wholly untimed tail looks like - divide the stretch
     * between them, rather than crossing together, so the fill still moves through them one at a
     * time.
     */
    internal fun closeUntimedTail(starts: IntArray, ends: IntArray, lineEnd: Int, nextStart: Int) {
        var k = 0
        while (k < starts.size) {
            if (ends[k] > starts[k]) {
                k++
                continue
            }
            // The run of untimed words this one belongs to.
            var last = k
            while (last + 1 < starts.size && ends[last + 1] <= starts[last + 1]) last++
            val from = starts[k]
            // A later word that starts later is where the run has to be over by. At the tail
            // there is none of those, and the line's own end closes it.
            var to = lineEnd
            for (m in last + 1 until starts.size) {
                if (starts[m] > from) {
                    to = starts[m]
                    break
                }
            }
            // A line whose own end is no later than its last word - which is what a file that
            // times lines by their words alone reports - leaves nothing to go on but the next
            // line's arrival.
            // The dots are drawn when what is left after this line's end reaches the lull, and
            // that end is the nominal one when the word does not run on - so it is the wait
            // after the nominal span that decides, or a gap just over four seconds would get
            // neither the held word nor the dots.
            if (to <= from) {
                val nominal = from + NOMINAL_WORD_MS * (last - k + 1)
                to = if (nextStart > from && nextStart - nominal < MAX_HELD_MS) nextStart
                else nominal
            }
            val count = last - k + 1
            for (m in k..last) ends[m] = from + (to - from) * (m - k + 1) / count
            k = last + 1
        }
    }

    /**
     * What a word is given when even the line it sits in has no end left to reach. Only a file
     * that times its lines by words alone gets here; about a word's worth of room, so it sweeps
     * rather than snaps.
     */
    private const val NOMINAL_WORD_MS = 300

    /**
     * How long a wait may be left after a last word's nominal span before it stops being a note
     * held across it and becomes an interlude, which the renderer is about to draw its dots
     * through anyway (the same four seconds; see LyricView.LULL_MS).
     */
    private const val MAX_HELD_MS = 4000

    /** "筷：" or "Jay: " at the head of a line - a name, then a full- or half-width colon. */
    private val LABEL = Regex("^([^\\s\\d:：]{1,6})\\s*[:：]\\s*")

    /** Labels that mean everyone at once. Drawn on the first singer's side. */
    private val TOGETHER = setOf("合", "合唱", "全", "All", "ALL", "all")

    /**
     * Duets marked the way NetEase lyrics mark them: the singer's name as a prefix on the line
     * where the voice changes ("筷：苍茫的天涯是我的爱", "凤：变成蜡烛燃烧自己"), holding until
     * the next prefix. MeiLoX reads the same prefixes to put the two voices on either side; the
     * files carry no other trace of who sings what.
     *
     * A name only counts once it has marked two lines, which leaves out the credits at the top
     * ("作词: ...", "作曲: ...") that appear once each. It takes two such names to be a duet; the
     * first to sing is on the left, the second on the right, and the prefix is not shown. A file
     * whose lines already say which side they are on (TTML's agents) is left as it is.
     */
    private fun speakers(lines: List<LyricLine>): List<LyricLine> {
        if (lines.any { it.opposite }) return lines
        val labels = lines.map { LABEL.find(it.text)?.groupValues?.get(1) }
        val counts = labels.filterNotNull().groupingBy { it }.eachCount()
        val singers = LinkedHashSet<String>()
        for (l in labels) {
            if (l != null && l !in TOGETHER && (counts[l] ?: 0) >= 2) singers.add(l)
        }
        if (singers.size < 2) return lines
        val order = singers.toList()
        val out = ArrayList<LyricLine>(lines.size)
        var right = false
        for ((i, line) in lines.withIndex()) {
            val label = labels[i]
            val known = label != null && (label in TOGETHER || label in singers)
            if (known) right = label !in TOGETHER && order.indexOf(label) % 2 == 1
            val cut = if (known) LABEL.find(line.text)!!.range.last + 1 else 0
            out.add(relabel(line, cut, right) ?: continue)
        }
        return out
    }

    /** The same line with its first `cut` characters gone and put on the given side. */
    private fun relabel(line: LyricLine, cut: Int, opposite: Boolean): LyricLine? {
        if (cut >= line.text.length) return null
        val text = line.text.substring(cut)
        val result = if (line.sylStart == null) {
            LyricLine(text, line.translation, line.start, line.end, opposite, null, null, null)
        } else {
            // Syllables that lay wholly inside the prefix go with it; the rest shift left.
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            val chars = ArrayList<Int>()
            for (k in line.sylStart.indices) {
                val e = line.charEnd[k] - cut
                if (e <= 0) continue
                starts.add(line.sylStart[k])
                ends.add(line.sylEnd[k])
                chars.add(e)
            }
            if (chars.isEmpty()) return null
            LyricLine(text, line.translation, line.start, line.end, opposite,
                starts.toIntArray(), ends.toIntArray(), chars.toIntArray())
        }
        result.bg = line.bg
        return result
    }
}
